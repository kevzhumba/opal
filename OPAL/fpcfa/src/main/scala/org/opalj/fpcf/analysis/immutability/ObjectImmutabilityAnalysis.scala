/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2014
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package fpcf
package analysis
package immutability

import scala.collection.mutable

import org.opalj.br.analyses.SomeProject
import org.opalj.br.ClassFile
import org.opalj.br.ObjectType
import org.opalj.br.ClassHierarchy
import org.opalj.br.Field
import org.opalj.fpcf.analysis.fields.FieldMutability
import org.opalj.fpcf.analysis.fields.NonFinalField
import org.opalj.fpcf.analysis.fields.FinalField
import org.opalj.fpcf.analysis.fields.EffectivelyFinalField
import org.opalj.log.OPALLogger

/**
 * Determines the mutability of instances of a specific class. In case the class
 * is abstract the (implicit) assumption is made that all abstract methods (if any) are/can
 * be implemented without necessarily/always requiring additional state; i.e., only the currently
 * defined fields are take into consideration. An interfaces is always considered to be immutable;
 * if you need to know if all possible instances of an interface; i.e., all instances of the classes
 * that implement the respective interface are immutable, you can query the [[TypeImmutability]]
 * attribute.
 *
 * In case of incomplete class hierarchies or if the class hierarchy is complete, but some
 * class files are not found the sound approximation is done that the respective classes are
 * mutable.
 *
 * This analysis uses the "FieldMutability" property to determine those fields which could
 * be final, but which are not declared as final.
 *
 * @author Michael Eichberg
 */
class ObjectImmutabilityAnalysis(
        val project:        SomeProject,
        val classHierarchy: ClassHierarchy
) extends FPCFAnalysis {
    /*
     * The analysis is implemented as an incremental analysis which starts with the analysis
     * of those types which directly inherit from java.lang.Object and then propagates the
     * mutability information down the class hierarchy.
     *
     * This propagation needs to be done eagerly to ensure that all types are associated with
     * some property when the initial computation finishes and fallback properties are associated.
     */

    /*
         * Create a result object that sets this type and all subclasses of if
         * to the given immutability rating.
         */
    @inline private[this] def createResultForAllSubtypes(
        t:            ObjectType,
        immutability: MutableObject
    ): MultiResult = {
        val allSubtypes = classHierarchy.allSubclassTypes(t, reflexive = true)
        val allSubclasses = allSubtypes.map(project.classFile(_))
        MultiResult(allSubclasses.collect { case Some(cf) ⇒ EP(cf, immutability) }.toTraversable)
    }

    @inline private[this] def createIncrementalResult(
        cf:           ClassFile,
        cfMutability: ObjectImmutability,
        result:       PropertyComputationResult
    ): IncrementalResult[ClassFile] = {
        var results: List[PropertyComputationResult] = List(result)
        var nextComputations: List[(PropertyComputation[ClassFile], ClassFile)] = Nil

        val directSubtypes = classHierarchy.directSubtypesOf(cf.thisType)
        directSubtypes.foreach { t ⇒
            project.classFile(t) match {
                case Some(scf) ⇒
                    nextComputations ::= ((determineObjectImmutability(cf, cfMutability) _, scf))
                case None ⇒
                    OPALLogger.warn(
                        "project configuration - object immutability analysis",
                        s"the class file of ${t.toJava} is not available"
                    )
                    results ::= createResultForAllSubtypes(t, MutableObjectDueToUnknownSupertypes)
            }
        }

        IncrementalResult(Results(results), nextComputations)
    }

    /**
     * The main method to determine the immutability of instances of given class file `cf`.
     *
     * @param superClassFile The direct super class (file) of the given class file `cf`.
     * 		Can be `null` if `superClassMutability` is `ImmutableObject`.
     * @param superClassMutability The mutability of the given super class. The mutability
     * 		must not be "MutableObject"; this case has to be handled explicitly.
     */
    def determineObjectImmutability(
        superClassFile:       ClassFile,
        superClassMutability: ObjectImmutability // either unknown, immutable or (at least) conditionally immutable
    )(
        cf: ClassFile
    ): PropertyComputationResult = {

        var dependees: List[EOptionP[Entity, Property]] =
            if (superClassMutability.isFinal)
                List.empty
            else
                List(EP(superClassFile, superClassMutability))

        // Collect all fields for which we need to determine the effective mutability!
        var hasFieldsWithUnknownMutability = false
        val nonFinalInstanceFields = cf.fields.collect { case f if !f.isStatic && !f.isFinal ⇒ f }
        dependees ++= propertyStore(nonFinalInstanceFields, FieldMutability) collect {
            case EP(e, p) if !p.isEffectivelyFinal ⇒
                // The class is definitively mutable and therefore also all subclasses.
                return createResultForAllSubtypes(cf.thisType, MutableObjectByAnalysis);

            case epk @ EPK(_, _) ⇒
                // We don't have the mutability information so far.
                hasFieldsWithUnknownMutability = true
                epk

            // case EP(e, p: EffectivelyFinalField) => we can ignore effectively final fields
        }
        // (IF WE DON'T WANT TO CONSIDER FINAL FIELDS THAT ARE DETECTED LATER ON!)
        //        if (hasFieldsWithUnknownMutability)
        //            return createResultForAllSubtypes(cf.thisType, MutableObjectByAnalysis);

        // NOTE: maxLocalImmutability does not take the super classes' mutability into account!
        var maxLocalImmutability: ObjectImmutability =
            if (cf.fields.exists(f ⇒ !f.isStatic && f.fieldType.isArrayType))
                // IMPROVE We could analyze if the array is effectively final.
                // I.e., it is only initialized once (at construction time) and no reference to it
                // is passed to another object.
                ConditionallyImmutableObject
            else
                ImmutableObject

        val fieldTypes: Set[ObjectType] =
            // IMPROVE Use the precise type of the field (if available)!
            cf.fields.collect {
                case f if !f.isStatic && f.fieldType.isObjectType ⇒ f.fieldType.asObjectType
            }.toSet

        val fieldTypesClassFiles: List[ClassFile] =
            if (maxLocalImmutability == ImmutableObject) {
                var fieldTypesClassFiles = List.empty[ClassFile]
                val hasUnresolvableDependencies =
                    fieldTypes.exists { t ⇒
                        project.classFile(t) match {
                            case Some(cf) ⇒ { fieldTypesClassFiles ::= cf; false }
                            case None     ⇒ true /* we have an unresolved dependency */
                        }
                    }
                if (hasUnresolvableDependencies) {
                    // => we do not need to determine the mutability of the fields!
                    maxLocalImmutability = ConditionallyImmutableObject
                    Nil
                } else {
                    fieldTypesClassFiles
                }
            } else {
                // The class is at most ConditionallyImmutable; hence, we don't care about
                // the immutability of the fields
                Nil
            }

        // For each dependent class file we have to determine the mutability
        // of instances of the respective type to determine this type's immutability.
        // Basically, we have to distinguish the following cases:
        // - A field's type is mutable or conditionally immutable=>
        //            This class is conditionally immutable.
        // - A field's type is immutable =>
        //            The field is ignored.
        //            (This class is as immutable as its superclass.)
        // - A field's type is at least conditionally mutable or
        //   The immutability of the field's type is not yet known =>
        //            This type is AtLeastConditionallyImmutable
        //            We have to declare a dependency on the respective type's immutability.
        //
        // Additional handling is required w.r.t. the supertype:
        // If the supertype is Immutable =>
        //            "nothing special to do"
        // If the supertype is AtLeastConditionallyImmutable =>
        //            "we have to declare a dependency on the supertype"
        //
        // We furthermore have to take the mutability of the fields into consideration:
        // If a field is not effectively final =>
        //            This type is mutable.

        val fieldTypesImmutability = propertyStore(fieldTypesClassFiles, TypeImmutability.key)
        val hasMutableOrConditionallyImmutableField =
            // IMPROVE Use the precise type of the field (if available)!
            fieldTypesImmutability.exists { eOptP ⇒
                eOptP.hasProperty && (eOptP.p.isMutable || eOptP.p.isConditionallyImmutable)
            }

        if (hasMutableOrConditionallyImmutableField) {
            maxLocalImmutability = ConditionallyImmutableObject
        } else {
            val fieldTypesWithUndecidedMutability: Traversable[EOptionP[Entity, Property]] =
                // Recall: we don't have fields which are mutable or conditionally immutable
                fieldTypesImmutability.filterNot { eOptP ⇒
                    eOptP.hasProperty && eOptP.p == ImmutableType
                }
            dependees ++= fieldTypesWithUndecidedMutability
        }

        if (dependees.isEmpty) {
            // <=> the super classes' immutability is final
            //     (i.e., ImmutableObject or ConditionallyImmutableObject)
            // <=> all fields are (effectively) final
            // <=> the type mutability of all fields is final
            //     (i.e., ImmutableType or ConditionallyImmutableType)
            val immutability: ObjectImmutability = {
                if (maxLocalImmutability == ConditionallyImmutableObject)
                    ConditionallyImmutableObject
                else
                    superClassMutability
            }
            assert(immutability.isFinal, s"immutability is not final $immutability")
            return createIncrementalResult(cf, immutability, ImmediateResult(cf, immutability));
        }

        var currentSuperClassMutability = superClassMutability

        val initialImmutability: ObjectImmutability =
            if (hasFieldsWithUnknownMutability || superClassMutability == UnknownObjectImmutability)
                UnknownObjectImmutability
            else
                AtLeastConditionallyImmutableObject

        def c(e: Entity, p: Property, ut: UserUpdateType): PropertyComputationResult = {
            val oldDependees = dependees
            p match {
                // Field Mutability related dependencies
                case _: NonFinalField ⇒ return Result(cf, MutableObjectByAnalysis);

                case _: FinalField    ⇒ dependees = dependees.filter(_.e ne e)

                // Superclass related dependencies
                case _: MutableObject ⇒ return Result(cf, MutableObjectByAnalysis);

                case ImmutableObject /* the super class */ ⇒
                    currentSuperClassMutability = ImmutableObject
                    dependees = dependees.filter(_.e ne e)

                case ConditionallyImmutableObject /* the super class */ ⇒
                    dependees = dependees.filterNot { d ⇒
                        val pk = d.pk
                        pk == TypeImmutability.key || pk == ObjectImmutability.key
                    }
                    currentSuperClassMutability = ConditionallyImmutableObject
                    maxLocalImmutability = ConditionallyImmutableObject

                case AtLeastConditionallyImmutableObject ⇒
                    currentSuperClassMutability = AtLeastConditionallyImmutableObject
                    dependees = EP(e, p) :: dependees.filter(_.e ne e)

                case ConditionallyImmutableType | MutableType ⇒
                    dependees = dependees.filterNot { d ⇒ d.pk == TypeImmutability.key }
                    maxLocalImmutability = ConditionallyImmutableObject

                case ImmutableType ⇒
                    dependees = dependees.filter(_.e ne e)

                case UnknownTypeImmutability | AtLeastConditionallyImmutableType ⇒
                    dependees = EP(e, p) :: dependees.filter(_.e ne e)

            }

            assert(
                oldDependees != dependees,
                s"dependees are not correctly updated\n:$oldDependees\n$dependees"
            )

            val result =
                if (dependees.isEmpty) {
                    assert(
                        maxLocalImmutability == ConditionallyImmutableObject ||
                            maxLocalImmutability == ImmutableObject
                    )
                    assert(
                        (
                            currentSuperClassMutability == AtLeastConditionallyImmutableObject &&
                            maxLocalImmutability == ConditionallyImmutableObject
                        ) ||
                            currentSuperClassMutability == ConditionallyImmutableObject ||
                            currentSuperClassMutability == ImmutableObject,
                        s"$e: $p resulted in no dependees with unexpected "+
                            s"currentSuperClassMutability=$currentSuperClassMutability/"+
                            s"maxLocalImmutability=$maxLocalImmutability - "+
                            s"(old dependees: ${oldDependees.mkString(",")}"
                    )
                    if (currentSuperClassMutability == ConditionallyImmutableObject ||
                        maxLocalImmutability == ConditionallyImmutableObject)
                        Result(cf, ConditionallyImmutableObject)
                    else
                        Result(cf, ImmutableObject)

                } else {
                    if (currentSuperClassMutability != UnknownObjectImmutability &&
                        !hasFieldsWithUnknownMutability) {
                        IntermediateResult(cf, AtLeastConditionallyImmutableObject, dependees, c)
                    } else {
                        IntermediateResult(cf, initialImmutability, dependees, c)
                    }
                }

            // println(s"${cf.thisType.toJava}(\n\tmaxLocalImmutability=$maxLocalImmutability,\n\tcurrentSuperClassMutability=$currentSuperClassMutability):\n$e($p) =>Dependees: $dependees =>Result: $result\n\n")

            result
        }

        assert(initialImmutability.isRefineable)
        val result = IntermediateResult(cf, initialImmutability, dependees, c)
        createIncrementalResult(cf, initialImmutability, result)
    }
}

/**
 * Runs an immutability analysis to determine the mutability of objects.
 *
 * @author Michael Eichberg
 */
object ObjectImmutabilityAnalysis extends FPCFAnalysisRunner {

    override def recommendations: Set[FPCFAnalysisRunner] = Set.empty

    override def derivedProperties: Set[PropertyKind] = Set(ObjectImmutability)

    override def usedProperties: Set[PropertyKind] = Set(TypeImmutability, FieldMutability)

    def start(project: SomeProject, propertyStore: PropertyStore): FPCFAnalysis = {
        val classHierarchy = project.classHierarchy
        import propertyStore.handleResult
        import classHierarchy.allSubtypes
        import classHierarchy.rootTypes
        import classHierarchy.isInterface
        implicit val logContext = project.logContext

        val analysis = new ObjectImmutabilityAnalysis(project, classHierarchy)

        // 1.1
        // java.lang.Object is by definition immutable
        val objectClassFileOption = project.classFile(ObjectType.Object)
        objectClassFileOption.foreach(cf ⇒ handleResult(ImmediateResult(cf, ImmutableObject)))

        // 1.2
        // all (instances of) interfaces are (by their very definition) also immutable
        val allInterfaces = project.allClassFiles.filter(cf ⇒ cf.isInterfaceDeclaration)
        handleResult(ImmediateMultiResult(allInterfaces.map(cf ⇒ EP(cf, ImmutableObject))))

        // 2.
        // all classes that do not have complete superclass information are mutable
        // due to the lack of knowledge
        val typesForWhichItMayBePossibleToComputeTheMutability = allSubtypes(ObjectType.Object, reflexive = true)
        val unexpectedRootTypes = rootTypes.filter(rt ⇒ (rt ne ObjectType.Object) && !isInterface(rt))
        unexpectedRootTypes.map(rt ⇒ allSubtypes(rt, reflexive = true)).flatten.view.
            filter(ot ⇒ !typesForWhichItMayBePossibleToComputeTheMutability.contains(ot)).
            foreach(ot ⇒ project.classFile(ot) foreach { cf ⇒
                handleResult(ImmediateResult(cf, MutableObjectDueToUnknownSupertypes))
            })

        // 3.
        // the initial set of classes for which we want to determine the mutability
        var cfs: List[ClassFile] = Nil
        classHierarchy.directSubclassesOf(ObjectType.Object).view.
            map(ot ⇒ (ot, project.classFile(ot))).
            foreach {
                case (_, Some(cf)) ⇒ cfs ::= cf
                case (t, None) ⇒
                    // This handles the case where the class hierarchy is at least partially
                    // based on a pre-configured class hierarchy (*.ths file).
                    // E.g., imagine that you analyze a lib which contains a class that inherits
                    // from java.lang.Exception, but you have no knowledge about this respective
                    // class...
                    OPALLogger.warn(
                        "project configuration - object immutability analysis",
                        s"the class file of ${t.toJava}, which extends java.lang.Object, is not available"
                    )
                    allSubtypes(t, reflexive = true).foreach(project.classFile(_).foreach { cf ⇒
                        handleResult(ImmediateResult(cf, MutableObjectDueToUnknownSupertypes))
                    })
            }

        propertyStore <|<< (cfs, analysis.determineObjectImmutability(null, ImmutableObject))

        analysis
    }

}
