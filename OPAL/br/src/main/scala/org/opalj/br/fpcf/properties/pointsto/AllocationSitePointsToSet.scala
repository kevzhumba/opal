/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package fpcf
package properties
package pointsto

import org.opalj.collection.immutable.Chain
import org.opalj.collection.immutable.LongLinkedTrieSet
import org.opalj.collection.immutable.LongLinkedTrieSet1
import org.opalj.collection.immutable.Naught
import org.opalj.collection.immutable.UIDSet
import org.opalj.fpcf.Entity
import org.opalj.fpcf.FallbackReason
import org.opalj.fpcf.OrderedProperty
import org.opalj.fpcf.PropertyIsNotDerivedByPreviouslyExecutedAnalysis
import org.opalj.fpcf.PropertyKey
import org.opalj.fpcf.PropertyMetaInformation
import org.opalj.fpcf.PropertyStore

/**
 * Represent the set of types that have allocations reachable from the respective entry points.
 *
 * @author Florian Kuebler
 */
sealed trait AllocationSitePointsToSetPropertyMetaInformation extends PropertyMetaInformation {

    final type Self = AllocationSitePointsToSet
}

trait AllocationSitePointsToSet
    extends PointsToSetLike[AllocationSite, LongLinkedTrieSet, AllocationSitePointsToSet]
    with OrderedProperty
    with AllocationSitePointsToSetPropertyMetaInformation {

    final def key: PropertyKey[AllocationSitePointsToSet] = AllocationSitePointsToSet.key

    override def toString: String = s"PointsTo(size=${elements.size})"

    override def checkIsEqualOrBetterThan(e: Entity, other: AllocationSitePointsToSet): Unit = {
        if (!types.subsetOf(other.types)) {
            throw new IllegalArgumentException(s"$e: illegal refinement of property $other to $this")
        }
    }

    assert(numElements >= numTypes)
}

object AllocationSitePointsToSet extends AllocationSitePointsToSetPropertyMetaInformation {

    def apply(
        allocationSite: AllocationSite, allocatedType: ObjectType
    ): AllocationSitePointsToSet = {
        new AllocationSitePointsToSet1(allocationSite, allocatedType)
    }

    def apply(
        allocationSiteNew: AllocationSite,
        allocatedTypeNew:  ObjectType,
        allocationSiteOld: AllocationSite,
        allocatedTypeOld:  ObjectType
    ): AllocationSitePointsToSet = {
        assert(allocationSiteOld != allocationSiteNew)
        new AllocationSitePointsToSetN(
            LongLinkedTrieSet(allocationSiteNew, allocationSiteOld),
            UIDSet(allocatedTypeOld, allocatedTypeNew),
            if (allocatedTypeNew != allocatedTypeOld)
                allocatedTypeNew :&: allocatedTypeOld :&: Naught
            else
                allocatedTypeNew :&: Naught
        )
    }

    def apply(
        elements: LongLinkedTrieSet, types: UIDSet[ObjectType], orderedTypes: Chain[ObjectType]
    ): AllocationSitePointsToSet = {

        if (elements.isEmpty) {
            NoAllocationSites
        } else if (elements.size == 1) {
            new AllocationSitePointsToSet1(elements.head, orderedTypes.head)
        } else {
            new AllocationSitePointsToSetN(elements, types, orderedTypes)
        }
    }

    final val key: PropertyKey[AllocationSitePointsToSet] = {
        val name = "opalj.AllocationSitePointsToSet"
        PropertyKey.create(
            name,
            (_: PropertyStore, reason: FallbackReason, _: Entity) ⇒ reason match {
                case PropertyIsNotDerivedByPreviouslyExecutedAnalysis ⇒ NoAllocationSites
                case _ ⇒
                    throw new IllegalStateException(s"no analysis is scheduled for property: $name")
            }
        )
    }
}

case class AllocationSitePointsToSetN private[pointsto] (
        override val elements:    LongLinkedTrieSet,
        override val types:       UIDSet[ObjectType],
        private val orderedTypes: Chain[ObjectType]
) extends AllocationSitePointsToSet {

    override def numTypes: Int = types.size
    override def numElements: Int = elements.size

    override def included(other: AllocationSitePointsToSet): AllocationSitePointsToSet = {
        included(other, 0)
    }

    override def included(
        other: AllocationSitePointsToSet, seenElements: Int
    ): AllocationSitePointsToSet = {
        var newAllocationSites = elements

        other.forNewestNElements(other.numElements - seenElements)(newAllocationSites += _)

        if (elements eq newAllocationSites)
            return this;

        var newTypes = types
        var newOrderedTypes = orderedTypes
        other.types.foreach { newType ⇒
            val old = newTypes
            newTypes += newType
            if (newTypes ne old) {
                newOrderedTypes :&:= newType
            }
        }

        new AllocationSitePointsToSetN(newAllocationSites, newTypes, newOrderedTypes)
    }

    override def included(
        other: AllocationSitePointsToSet, allowedType: ObjectType
    ): AllocationSitePointsToSet = {
        val newAllocationSites = other.elements.foldLeft(elements) { (r, allocationSite) ⇒
            if (allocationSiteLongToTypeId(allocationSite) == allowedType.id) {
                r + allocationSite
            } else {
                r
            }
        }

        if (elements eq newAllocationSites)
            return this;

        // Here, there was at least one element added (that has the allowedType), so we can add
        // the allowedType to the set of types.
        val newTypes = types + allowedType
        val newOrderedTypes = if (newTypes ne types) allowedType :&: orderedTypes else orderedTypes

        new AllocationSitePointsToSetN(newAllocationSites, newTypes, newOrderedTypes)
    }

    override def included(
        other: AllocationSitePointsToSet, allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        val newAllocationSites = other.elements.foldLeft(elements) { (r, allocationSite) ⇒
            if (allowedTypes.containsId(allocationSiteLongToTypeId(allocationSite))) {
                r + allocationSite
            } else {
                r
            }
        }

        if (elements eq newAllocationSites)
            return this;

        var newTypes = types
        var newOrderedTypes = orderedTypes

        // iterate over the smaller set
        if (other.numTypes <= allowedTypes.size) {
            other.types.foreach { t ⇒
                if (allowedTypes.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        } else {
            allowedTypes.foreach { t ⇒
                if (other.types.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        }

        new AllocationSitePointsToSetN(newAllocationSites, newTypes, newOrderedTypes)
    }

    override def included(
        other: AllocationSitePointsToSet, seenElements: Int, allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        var newAllocationSites = elements
        other.forNewestNElements(other.numElements - seenElements) { allocationSite ⇒
            if (allowedTypes.containsId(allocationSiteLongToTypeId(allocationSite))) {
                newAllocationSites += allocationSite
            }
        }

        if (elements eq newAllocationSites)
            return this;

        var newTypes = types
        var newOrderedTypes = orderedTypes

        // iterate over the smaller set
        if (other.numTypes <= allowedTypes.size) {
            other.types.foreach { t ⇒
                if (allowedTypes.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        } else {
            allowedTypes.foreach { t ⇒
                if (other.types.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        }

        if (newTypes.size == types.size) {
            new AllocationSitePointsToSetN(newAllocationSites, types, orderedTypes)
        } else {
            new AllocationSitePointsToSetN(newAllocationSites, newTypes, newOrderedTypes)
        }
    }

    override def filter(
        allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        val newAllocationSites = elements.foldLeft(LongLinkedTrieSet.empty) { (r, allocationSite) ⇒
            if (allowedTypes.containsId(allocationSiteLongToTypeId(allocationSite))) {
                r + allocationSite
            } else {
                r
            }
        }

        if (newAllocationSites.size == elements.size)
            return this;

        var newTypes = UIDSet.empty[ObjectType]
        var newOrderedTypes = Chain.empty[ObjectType]

        // iterate over the smaller set
        if (numTypes <= allowedTypes.size) {
            types.foreach { t ⇒
                if (allowedTypes.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        } else {
            allowedTypes.foreach { t ⇒
                if (types.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        }

        if (newTypes.size == types.size) {
            AllocationSitePointsToSet(newAllocationSites, types, orderedTypes)
        } else {
            AllocationSitePointsToSet(newAllocationSites, newTypes, newOrderedTypes)
        }
    }

    override def forNewestNTypes[U](n: Int)(f: ObjectType ⇒ U): Unit = {
        orderedTypes.forFirstN(n)(f)
    }

    override def forNewestNElements[U](n: Int)(f: AllocationSite ⇒ U): Unit = {
        elements.forFirstN(n)(f)
    }

    // TODO: should the order matter?
    override def equals(obj: Any): Boolean = {
        obj match {
            case that: AllocationSitePointsToSetN ⇒
                (this eq that) || {
                    that.numTypes == this.numTypes &&
                        that.elements.size == this.elements.size &&
                        // TODO: we should assert that:
                        //  that.elements == this.elements => that.orderedTypes == this.orderedTypes
                        that.elements == this.elements
                }
            case _ ⇒ false
        }
    }

    override def hashCode: Int = elements.hashCode() * 31
}

object NoAllocationSites extends AllocationSitePointsToSet {
    override def included(other: AllocationSitePointsToSet): AllocationSitePointsToSet = {
        other
    }

    override def included(
        other: AllocationSitePointsToSet, seenElements: Int
    ): AllocationSitePointsToSet = {
        assert(seenElements == 0)
        other
    }

    def included(
        other: AllocationSitePointsToSet, allowedType: ObjectType
    ): AllocationSitePointsToSet = {
        val newAllocationSites = other.elements.foldLeft(LongLinkedTrieSet.empty) {
            (r, allocationSite) ⇒
                if (allocationSiteLongToTypeId(allocationSite) == allowedType.id) {
                    r + allocationSite
                } else {
                    r
                }
        }

        if (newAllocationSites.isEmpty)
            return this;

        // Here, there was at least one element added (that has the allowedType), so we can add
        // the allowedType to the set of types.
        AllocationSitePointsToSet(
            newAllocationSites, UIDSet(allowedType), Chain(allowedType)
        )
    }

    override def included(
        other: AllocationSitePointsToSet, allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        other.filter(allowedTypes)
    }

    override def included(
        other: AllocationSitePointsToSet, seenElements: Int, allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        var newAllocationSites = LongLinkedTrieSet.empty
        other.forNewestNElements(other.numElements - seenElements) { allocationSite ⇒
            if (allowedTypes.containsId(allocationSiteLongToTypeId(allocationSite))) {
                newAllocationSites += allocationSite
            }
        }

        if (newAllocationSites.isEmpty)
            return this;

        var newTypes = UIDSet.empty[ObjectType]
        var newOrderedTypes = Chain.empty[ObjectType]

        // iterate over the smaller set
        if (numTypes <= allowedTypes.size) {
            types.foreach { t ⇒
                if (allowedTypes.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        } else {
            allowedTypes.foreach { t ⇒
                if (types.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        }

        AllocationSitePointsToSet(newAllocationSites, newTypes, newOrderedTypes)
    }

    override def filter(allowedTypes: UIDSet[ObjectType]): AllocationSitePointsToSet = {
        this
    }

    override def numTypes: Int = 0

    override def types: UIDSet[ObjectType] = UIDSet.empty

    override def numElements: Int = 0

    override def elements: LongLinkedTrieSet = LongLinkedTrieSet.empty

    override def forNewestNTypes[U](n: Int)(f: ObjectType ⇒ U): Unit = {
        assert(n == 0)
    }

    override def forNewestNElements[U](n: Int)(f: AllocationSite ⇒ U): Unit = {
        assert(n == 0)
    }
}

case class AllocationSitePointsToSet1(
        allocationSite: AllocationSite, allocatedType: ObjectType
) extends AllocationSitePointsToSet {

    override def numTypes: Int = 1

    override def types: UIDSet[ObjectType] = UIDSet(allocatedType)

    override def numElements: Int = 1

    override def elements: LongLinkedTrieSet = LongLinkedTrieSet1(allocationSite)

    override def included(other: AllocationSitePointsToSet): AllocationSitePointsToSet = {
        other match {
            case AllocationSitePointsToSet1(`allocationSite`, `allocatedType`) ⇒
                this

            case AllocationSitePointsToSet1(otherAllocationSite, otherAllocatedType) ⇒
                AllocationSitePointsToSet(
                    otherAllocationSite, otherAllocatedType, allocationSite, allocatedType
                )

            case NoAllocationSites ⇒
                this

            case AllocationSitePointsToSetN(otherAllocationSites, otherTypes, otherOrderedTypes) ⇒
                val newAllocations = otherAllocationSites.foldLeft(elements) { (l, as) ⇒
                    if (as != allocationSite) {
                        l + as
                    } else {
                        l
                    }
                }

                val newOrderedTypes = otherOrderedTypes.foldLeft(allocatedType :&: Naught) { (l, at) ⇒
                    if (at != allocatedType) at :&: l else l
                }

                new AllocationSitePointsToSetN(
                    newAllocations,
                    otherTypes + allocatedType,
                    newOrderedTypes
                )
            case _ ⇒
                throw new IllegalArgumentException(s"unexpected list $other")
        }
    }

    override def included(
        other: AllocationSitePointsToSet, seenElements: Int
    ): AllocationSitePointsToSet = {
        assert(seenElements >= 0 && seenElements <= other.numElements)
        // Note, that we can not assert, that seenElements is between 0 and 1, as this can
        // happen by unordered partial results.
        included(other)
    }

    def included(
        other: AllocationSitePointsToSet, allowedType: ObjectType
    ): AllocationSitePointsToSet = {
        other match {
            case AllocationSitePointsToSet1(`allocationSite`, `allocatedType`) ⇒
                this

            case AllocationSitePointsToSet1(otherAllocationSite, otherAllocatedType) ⇒
                if (otherAllocatedType eq allowedType)
                    AllocationSitePointsToSet(
                        otherAllocationSite, otherAllocatedType, allocationSite, allocatedType
                    )
                else
                    this

            case NoAllocationSites ⇒
                this

            case AllocationSitePointsToSetN(otherAllocationSites, _, _) ⇒
                val newAllocationSites = otherAllocationSites.foldLeft(elements) { (l, as) ⇒
                    if (as != allocationSite && allocationSiteLongToTypeId(as) == allowedType.id) {
                        l + as
                    } else {
                        l
                    }
                }

                if (elements eq newAllocationSites)
                    return this;

                val newOrderedTypes =
                    if (allowedType ne allocatedType) allowedType :&: allocatedType :&: Naught
                    else allocatedType :&: Naught

                AllocationSitePointsToSet(
                    newAllocationSites,
                    types + allowedType,
                    newOrderedTypes
                )
            case _ ⇒
                throw new IllegalArgumentException(s"unexpected list $other")
        }
    }

    override def included(
        other: AllocationSitePointsToSet, allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        val newAllocationSites = other.elements.foldLeft(LongLinkedTrieSet(allocationSite)) {
            (r, as) ⇒
                if (allowedTypes.containsId(allocationSiteLongToTypeId(as))) {
                    r + as
                } else {
                    r
                }
        }

        if (newAllocationSites.size == 1)
            return this;

        var newTypes = UIDSet(allocatedType)
        var newOrderedTypes = allocatedType :&: Naught

        // iterate over the smaller set
        if (numTypes <= allowedTypes.size) {
            types.foreach { t ⇒
                if (allowedTypes.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        } else {
            allowedTypes.foreach { t ⇒
                if (types.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        }

        AllocationSitePointsToSet(
            newAllocationSites, newTypes, newOrderedTypes
        )
    }

    override def included(
        other: AllocationSitePointsToSet, seenElements: Int, allowedTypes: UIDSet[ObjectType]
    ): AllocationSitePointsToSet = {
        var newAllocationSites = LongLinkedTrieSet(allocationSite)

        other.forNewestNElements(other.numElements - seenElements) { as ⇒
            if (allowedTypes.containsId(allocationSiteLongToTypeId(as))) {
                newAllocationSites += as
            }
        }

        if (newAllocationSites.size == 1)
            return this;

        var newTypes = UIDSet(allocatedType)
        var newOrderedTypes = allocatedType :&: Naught

        // iterate over the smaller set
        if (numTypes <= allowedTypes.size) {
            types.foreach { t ⇒
                if (allowedTypes.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        } else {
            allowedTypes.foreach { t ⇒
                if (types.contains(t)) {
                    val oldNewTypes = newTypes
                    newTypes += t
                    if (oldNewTypes ne newTypes) {
                        newOrderedTypes :&:= t
                    }
                }
            }
        }

        AllocationSitePointsToSet(newAllocationSites, newTypes, newOrderedTypes)
    }

    override def filter(allowedTypes: UIDSet[ObjectType]): AllocationSitePointsToSet = {
        if (allowedTypes.contains(allocatedType)) {
            this
        } else {
            NoAllocationSites
        }
    }

    override def forNewestNTypes[U](n: Int)(f: ObjectType ⇒ U): Unit = {
        assert(n == 0 || n == 1)
        if (n == 1)
            f(allocatedType)
    }

    override def forNewestNElements[U](n: Int)(f: AllocationSite ⇒ U): Unit = {
        assert(n == 0 || n == 1)
        if (n == 1)
            f(allocationSite)
    }
}