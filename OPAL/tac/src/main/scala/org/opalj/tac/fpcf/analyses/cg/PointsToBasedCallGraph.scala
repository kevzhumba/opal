/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package cg

import scala.collection.mutable

import org.opalj.log.Error
import org.opalj.log.LogContext
import org.opalj.log.OPALLogger.logOnce
import org.opalj.log.Warn
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.collection.ForeachRefIterator
import org.opalj.collection.immutable.UIDSet
import org.opalj.fpcf.Entity
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.EPK
import org.opalj.fpcf.EPS
import org.opalj.fpcf.EUBPS
import org.opalj.fpcf.FinalP
import org.opalj.fpcf.InterimEUBP
import org.opalj.fpcf.InterimPartialResult
import org.opalj.fpcf.InterimUBP
import org.opalj.fpcf.NoResult
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyComputationResult
import org.opalj.fpcf.PropertyKind
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.Results
import org.opalj.fpcf.SomeEOptionP
import org.opalj.fpcf.SomeEPS
import org.opalj.fpcf.UBP
import org.opalj.fpcf.UBPS
import org.opalj.value.IsMObjectValue
import org.opalj.value.IsNullValue
import org.opalj.value.IsSArrayValue
import org.opalj.value.IsSObjectValue
import org.opalj.value.ValueInformation
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.br.fpcf.FPCFTriggeredAnalysisScheduler
import org.opalj.br.fpcf.cg.properties.Callees
import org.opalj.br.fpcf.cg.properties.CallersProperty
import org.opalj.br.fpcf.pointsto.properties.PointsTo
import org.opalj.br.DeclaredMethod
import org.opalj.br.analyses.cg.InitialEntryPointsKey
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.br.fpcf.cg.properties.NoCallers
import org.opalj.br.fpcf.cg.properties.OnlyCallersWithUnknownContext
import org.opalj.br.DefinedMethod
import org.opalj.br.Method
import org.opalj.br.MethodDescriptor
import org.opalj.br.ObjectType
import org.opalj.br.ReferenceType
import org.opalj.br.analyses.VirtualFormalParametersKey
import org.opalj.tac.common.DefinitionSitesKey
import org.opalj.tac.fpcf.properties.TACAI

/**
 * Represents the state of a points-to based call graph analysis, while analyzing a certain method.
 */
class P2CGState private[cg] (
        private[cg] val method:         DefinedMethod,
        private[this] var _tacDependee: EOptionP[Method, TACAI]
) {
    // maps a definition site to the ids of the potential (not yet resolved) objecttypes
    private[this] val _virtualCallSites: mutable.Map[CallSiteT, IntTrieSet] = mutable.Map.empty

    // maps a defsite to the callsites for which it is being used
    // this is only done if the defsite is used within a call and its points-to set is not final
    private[this] val _defSitesToCallSites: mutable.Map[Entity, Set[CallSiteT]] = mutable.Map.empty
    private[this] val _callSiteToDefSites: mutable.Map[CallSiteT, Set[Entity]] = mutable.Map.empty

    // maps a defsite to its result in the property store for the points-to set
    // todo: add accessor methods
    private[this] val _pointsToDependees: mutable.Map[Entity, EOptionP[Entity, PointsTo]] = mutable.Map.empty

    private[cg] def tac: TACode[TACMethodParameter, DUVar[ValueInformation]] = {
        assert(_tacDependee.ub.tac.isDefined)
        _tacDependee.ub.tac.get
    }

    private[cg] def updateTACDependee(tacDependee: EOptionP[Method, TACAI]): Unit = {
        _tacDependee = tacDependee
    }

    private[cg] def virtualCallSites: mutable.Map[CallSiteT, IntTrieSet] = {
        _virtualCallSites
    }

    private[cg] def typesForCallSite(callSite: CallSiteT): IntTrieSet = {
        _virtualCallSites(callSite) //, IntTrieSet.empty) // todo: should use apply
    }

    private[cg] def initialPotentialTypesOfCallSite(
        callSite: CallSiteT, potentialTypes: ForeachRefIterator[ObjectType]
    ): Unit = {
        assert(!_virtualCallSites.contains(callSite))
        var potentialTypeIDs = IntTrieSet.empty
        potentialTypes.foreach(pt ⇒ potentialTypeIDs += pt.id)
        _virtualCallSites(callSite) = potentialTypeIDs
    }

    private[cg] def removeTypeForCallSite(callSite: CallSiteT, instantiatedType: ObjectType): Unit = {
        val typesLeft = _virtualCallSites(callSite) - instantiatedType.id
        if (typesLeft.isEmpty) {
            _virtualCallSites -= callSite
            for (defSite ← _callSiteToDefSites(callSite)) {
                val newCallSites = _defSitesToCallSites(defSite) - callSite
                if (newCallSites.isEmpty)
                    removePointsToDependency(defSite)
                else
                    _defSitesToCallSites(defSite) = newCallSites
            }
            _callSiteToDefSites.remove(callSite)
            // todo here we shold also remove all dependencies for this call-site
        } else {
            _virtualCallSites(callSite) = typesLeft
        }
    }

    private[cg] def getOrRetrievePointsToEPS(
        dependee: Entity, ps: PropertyStore
    ): EOptionP[Entity, PointsTo] = {
        _pointsToDependees.getOrElse(dependee, ps(dependee, PointsTo.key))
    }

    private[cg] def getPointsToEPS(dependee: Entity): EOptionP[Entity, PointsTo] = {
        _pointsToDependees(dependee)
    }

    private[cg] def updatePointsToDependency(eps: EPS[Entity, PointsTo]): Unit = {
        assert(_pointsToDependees.contains(eps.e))
        _pointsToDependees(eps.e) = eps
    }

    private[cg] def addPointsToDependency(
        callSite: CallSiteT, pointsToSetEOptP: EOptionP[Entity, PointsTo]
    ): Unit = {
        val defSite = pointsToSetEOptP.e
        assert((!_defSitesToCallSites.contains(defSite) && !_callSiteToDefSites.contains(callSite) && !_pointsToDependees.contains(defSite)) ||
            (!_defSitesToCallSites(defSite).contains(callSite) && !_callSiteToDefSites(callSite).contains(defSite)))
        _pointsToDependees(defSite) = pointsToSetEOptP
        val oldCallSites = _defSitesToCallSites.getOrElse(defSite, Set.empty)
        _defSitesToCallSites(defSite) = oldCallSites + callSite

        val oldDefSites = _callSiteToDefSites.getOrElse(callSite, Set.empty)
        _callSiteToDefSites(callSite) = oldDefSites + defSite
    }

    private[cg] def removePointsToDependency(defSite: Entity): Unit = {
        assert(_pointsToDependees.contains(defSite))
        assert(_defSitesToCallSites.contains(defSite))
        _pointsToDependees.remove(defSite)
        for (callSite ← _defSitesToCallSites(defSite)) {
            val newDefSites = _callSiteToDefSites(callSite) - defSite
            if (newDefSites.isEmpty) {
                _callSiteToDefSites.remove(callSite)
            } else {
                _callSiteToDefSites(callSite) = newDefSites
            }
        }
        _defSitesToCallSites.remove(defSite)
    }

    private[cg] def hasOpenDependencies: Boolean = {
        _pointsToDependees.nonEmpty || _tacDependee.isRefinable
    }

    private[cg] def dependees: Traversable[SomeEOptionP] = {
        if (_tacDependee.isFinal)
            _pointsToDependees.values
        else
            Some(_tacDependee) ++ _pointsToDependees.values
    }

    private[cg] def callSitesForDefSite(defSite: Entity): Traversable[CallSiteT] = {
        _defSitesToCallSites.getOrElse(defSite, Traversable.empty) // todo: ensure this is required
    }

}

/**
 * Uses the [[PointsTo]] of [[org.opalj.tac.common.DefinitionSite]] and
 * [[org.opalj.br.analyses.VirtualFormalParameter]]s in order to determine the targets of virtual
 * method calls.
 *
 * @author Florian Kuebler
 */
class PointsToBasedCallGraph private[analyses] ( final val project: SomeProject) extends FPCFAnalysis {
    private[this] val definitionSites = p.get(DefinitionSitesKey)
    private[this] val formalParameters = p.get(VirtualFormalParametersKey)
    private[this] val declaredMethods = p.get(DeclaredMethodsKey)

    def analyze(declaredMethod: DeclaredMethod): PropertyComputationResult = {
        (propertyStore(declaredMethod, CallersProperty.key): @unchecked) match {
            case FinalP(NoCallers) ⇒
                // nothing to do, since there is no caller
                return NoResult;

            case eps: EPS[_, _] ⇒
                if (eps.ub eq NoCallers) {
                    // we can not create a dependency here, so the analysis is not allowed to create
                    // such a result
                    throw new IllegalStateException("illegal immediate result for callers")
                }
            // the method is reachable, so we analyze it!
        }

        // we only allow defined methods
        if (!declaredMethod.hasSingleDefinedMethod)
            return NoResult;

        val method = declaredMethod.definedMethod

        // we only allow defined methods with declared type eq. to the class of the method
        if (method.classFile.thisType != declaredMethod.declaringClassType)
            return NoResult;

        if (method.body.isEmpty)
            // happens in particular for native methods
            return NoResult;

        val tacEP = propertyStore(method, TACAI.key)

        val state = new P2CGState(declaredMethod.asDefinedMethod, tacEP)

        if (tacEP.hasUBP && tacEP.ub.tac.isDefined)
            processMethod(state, new DirectCalls())
        else {
            InterimPartialResult(Some(tacEP), c(state))
        }
    }

    private[this] def processMethod(
        state: P2CGState, calls: DirectCalls
    ): ProperPropertyComputationResult = {
        val tac = state.tac
        val method = state.method

        // iterate over all call stmts and add calls whenever possible.
        // for non-virtual calls, calls can be added directly.
        // for virtual calls, we first ask the AI result, for more precise information.
        // if the information  is not precise enough, we query the points-to set
        tac.stmts.foreach {
            case stmt @ StaticFunctionCallStatement(call) ⇒
                handleCall(
                    method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    stmt.pc,
                    call.resolveCallTarget,
                    calls
                )

            case call: StaticMethodCall[V] ⇒
                handleCall(
                    method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    call.pc,
                    call.resolveCallTarget,
                    calls
                )

            case stmt @ NonVirtualFunctionCallStatement(call) ⇒
                handleCall(
                    method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    stmt.pc,
                    call.resolveCallTarget(method.declaringClassType.asObjectType),
                    calls
                )

            case call: NonVirtualMethodCall[V] ⇒
                handleCall(
                    method,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    call.pc,
                    call.resolveCallTarget(method.declaringClassType.asObjectType),
                    calls
                )

            case VirtualFunctionCallStatement(call) ⇒
                handleVirtualCall(method, call, call.pc, calls)(state)

            case call: VirtualMethodCall[V] ⇒
                handleVirtualCall(method, call, call.pc, calls)(state)

            case Assignment(_, _, idc: InvokedynamicFunctionCall[V]) ⇒
                calls.addIncompleteCallSite(idc.pc)
                logOnce(
                    Warn("analysis - call graph construction", s"unresolved invokedynamic: $idc")
                )

            case ExprStmt(_, idc: InvokedynamicFunctionCall[V]) ⇒
                calls.addIncompleteCallSite(idc.pc)
                logOnce(
                    Warn("analysis - call graph construction", s"unresolved invokedynamic: $idc")
                )

            case idc: InvokedynamicMethodCall[_] ⇒
                calls.addIncompleteCallSite(idc.pc)
                logOnce(
                    Warn("analysis - call graph construction", s"unresolved invokedynamic: $idc")
                )

            case _ ⇒ //nothing to do
        }

        returnResult(calls)(state)
    }

    private[this] def handleCall(
        caller:             DefinedMethod,
        callName:           String,
        callDescriptor:     MethodDescriptor,
        callDeclaringClass: ReferenceType,
        pc:                 Int,
        target:             org.opalj.Result[Method],
        calleesAndCallers:  DirectCalls
    ): Unit = {
        if (target.hasValue) {
            val tgtDM = declaredMethods(target.value)
            calleesAndCallers.addCall(caller, tgtDM, pc)
        } else {
            val packageName = caller.definedMethod.classFile.thisType.packageName
            unknownLibraryCall(
                caller,
                callName,
                callDescriptor,
                callDeclaringClass,
                callDeclaringClass,
                packageName,
                pc,
                calleesAndCallers
            )
        }
    }

    private[this] def unknownLibraryCall(
        caller:              DefinedMethod,
        callName:            String,
        callDescriptor:      MethodDescriptor,
        callDeclaringClass:  ReferenceType,
        runtimeReceiverType: ReferenceType,
        packageName:         String,
        pc:                  Int,
        calleesAndCallers:   DirectCalls
    ): Unit = {
        val declaringClassType = callDeclaringClass.mostPreciseObjectType
        val runtimeType = runtimeReceiverType.mostPreciseObjectType

        val declTgt = declaredMethods.apply(
            declaringClassType,
            packageName,
            runtimeType,
            callName,
            callDescriptor
        )

        if (declTgt.isVirtualOrHasSingleDefinedMethod) {
            calleesAndCallers.addCall(caller, declTgt, pc)
        } else {
            declTgt.definedMethods foreach { m ⇒
                val dm = declaredMethods(m)
                calleesAndCallers.addCall(caller, dm, pc)
            }
        }

        calleesAndCallers.addIncompleteCallSite(pc)
    }

    /**
     * Computes the calles of the given `method` including the known effect of the `call` and
     * the call sites associated ith this call (in order to process updates of instantiated types).
     * There can be multiple "call sites", in case the three-address code has computed multiple
     * type bounds for the receiver.
     */
    private[this] def handleVirtualCall(
        caller:            DefinedMethod,
        call:              Call[V] with VirtualCall[V],
        pc:                Int,
        calleesAndCallers: DirectCalls
    )(implicit state: P2CGState): Unit = {
        val callerType = caller.definedMethod.classFile.thisType

        val rvs = call.receiver.asVar.value.asReferenceValue.allValues
        for (rv ← rvs) rv match {
            case _: IsSArrayValue ⇒
                val tgtR = project.instanceCall(
                    caller.declaringClassType.asObjectType,
                    ObjectType.Object,
                    call.name,
                    call.descriptor
                )

                handleCall(
                    caller,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    pc,
                    tgtR,
                    calleesAndCallers
                )

            case ov: IsSObjectValue ⇒
                if (ov.isPrecise) {
                    val tgt = project.instanceCall(
                        callerType,
                        rv.leastUpperType.get,
                        call.name,
                        call.descriptor
                    )

                    handleCall(
                        caller,
                        call.name,
                        call.descriptor,
                        call.declaringClass,
                        pc,
                        tgt,
                        calleesAndCallers
                    )
                } else {

                    def potentialTypes(ov: IsSObjectValue): ForeachRefIterator[ObjectType] = {
                        classHierarchy.allSubtypesForeachIterator(
                            ov.theUpperTypeBound, reflexive = true
                        ).filter { subtype ⇒
                            val cfOption = project.classFile(subtype)
                            cfOption.isDefined && {
                                val cf = cfOption.get
                                !cf.isInterfaceDeclaration && !cf.isAbstract
                            }
                        }
                    }

                    val callSite = (pc, call.name, call.descriptor, call.declaringClass)
                    val pointsToSet = handleDefSites(callSite, call.receiver.asVar.definedBy)

                    // for each type that is possible and part of the points to set, we add the call
                    for (newType ← potentialTypes(ov).filter(pointsToSet.contains)) {
                        val tgtR = project.instanceCall(
                            callerType,
                            newType,
                            call.name,
                            call.descriptor
                        )
                        handleCall(
                            caller,
                            call.name,
                            call.descriptor,
                            call.declaringClass,
                            pc,
                            tgtR,
                            calleesAndCallers
                        )
                    }

                    // add all the types that are not yet available at this call site to the state
                    state.initialPotentialTypesOfCallSite(callSite, potentialTypes(ov).filter(t ⇒ !pointsToSet.contains(t)))
                }

            case mv: IsMObjectValue ⇒
                def potentialTypes(mv: IsMObjectValue): ForeachRefIterator[ObjectType] = {
                    val typeBounds = mv.upperTypeBound
                    val remainingTypeBounds = typeBounds.tail
                    val firstTypeBound = typeBounds.head
                    val potentialTypes = ch.allSubtypesForeachIterator(
                        firstTypeBound, reflexive = true
                    ).filter { subtype ⇒
                        val cfOption = project.classFile(subtype)
                        cfOption.isDefined && {
                            val cf = cfOption.get
                            !cf.isInterfaceDeclaration && !cf.isAbstract &&
                                remainingTypeBounds.forall { supertype ⇒
                                    ch.isSubtypeOf(subtype, supertype)
                                }
                        }
                    }
                    potentialTypes
                }

                val callSite = (pc, call.name, call.descriptor, call.declaringClass)
                val pointsToSet = handleDefSites(callSite, call.receiver.asVar.definedBy)

                for (newType ← potentialTypes(mv).filter(pointsToSet.contains)) {
                    val tgtR = project.instanceCall(
                        callerType,
                        newType,
                        call.name,
                        call.descriptor
                    )
                    handleCall(
                        caller,
                        call.name,
                        call.descriptor,
                        call.declaringClass,
                        pc,
                        tgtR,
                        calleesAndCallers
                    )
                }

                // add all the types that are not yet available at this call site to the state
                state.initialPotentialTypesOfCallSite(callSite, potentialTypes(mv).filter(t ⇒ !pointsToSet.contains(t)))

            case _: IsNullValue ⇒
            // for now, we ignore the implicit calls to NullPointerException.<init>
        }
    }

    // todo: the next four methods are basically copy&paste of the points-to analysis -> refactor

    @inline private[this] def toEntity(
        defSite: Int
    )(implicit state: P2CGState): Entity = {
        if (defSite < 0) {
            formalParameters.apply(state.method)(-1 - defSite)
        } else {
            definitionSites(state.method.definedMethod, state.tac.stmts(defSite).pc)
        }
    }

    @inline private[this] def handleEOptP(
        callSite: CallSiteT, dependeeDefSite: Int
    )(implicit state: P2CGState): UIDSet[ObjectType] = {
        if (ai.isImplicitOrExternalException(dependeeDefSite)) {
            // todo -  we need to get the actual exception type here
            UIDSet(ObjectType.Exception)
        } else {
            handleEOptP(callSite, toEntity(dependeeDefSite))
        }
    }

    // todo: rename
    @inline private[this] def handleEOptP(
        callSite: CallSiteT, dependee: Entity
    )(implicit state: P2CGState): UIDSet[ObjectType] = {
        val pointsToSetEOptP = state.getOrRetrievePointsToEPS(dependee, ps)
        pointsToSetEOptP match {
            case UBPS(pointsTo, isFinal) ⇒
                if (!isFinal) state.addPointsToDependency(callSite, pointsToSetEOptP)
                pointsTo.types

            case _: EPK[Entity, PointsTo] ⇒
                state.addPointsToDependency(callSite, pointsToSetEOptP)
                UIDSet.empty
        }
    }

    // todo: rename
    @inline private[this] def handleDefSites(
        callSite: CallSiteT, defSites: IntTrieSet
    )(implicit state: P2CGState): UIDSet[ObjectType] = {
        var pointsToSet = UIDSet.empty[ObjectType]
        for (defSite ← defSites) {
            pointsToSet ++=
                handleEOptP(callSite, defSite)

        }
        pointsToSet
    }

    private[this] def returnResult(
        calleesAndCallers: DirectCalls
    )(implicit state: P2CGState): ProperPropertyComputationResult = {
        val results = calleesAndCallers.partialResults(state.method)

        // if there are no virtual call-sites left, we can simply return the result
        if (state.virtualCallSites.isEmpty || !state.hasOpenDependencies)
            Results(results)
        else Results(
            InterimPartialResult(state.dependees, c(state)),
            results
        )
    }

    private[this] def c(state: P2CGState)(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(tacai: TACAI) if tacai.tac.isDefined ⇒
                state.updateTACDependee(eps.asInstanceOf[EPS[Method, TACAI]])
                processMethod(state, new DirectCalls())

            case UBP(_: TACAI) ⇒
                InterimPartialResult(Some(eps), c(state))

            case EUBPS(e, ub: PointsTo, isFinal) ⇒
                val relevantCallSites = state.callSitesForDefSite(e)

                // ensures, that we only add new calls
                val calls = new DirectCalls()

                for (callSite ← relevantCallSites) {
                    val oldEOptP = state.getPointsToEPS(eps.e)
                    val seenTypes = if (oldEOptP.hasUBP) oldEOptP.ub.numElements else 0
                    val typesLeft = state.typesForCallSite(callSite)
                    for (newType ← ub.getNewTypes(seenTypes)) {
                        if (typesLeft.contains(newType.id)) {
                            state.removeTypeForCallSite(callSite, newType)
                            val (pc, name, descriptor, declaredType) = callSite
                            val tgtR = project.instanceCall(
                                state.method.declaringClassType.asObjectType,
                                newType,
                                name,
                                descriptor
                            )
                            handleCall(
                                state.method, name, descriptor, declaredType, pc, tgtR, calls
                            )
                        }

                    }
                }

                if (isFinal) {
                    state.removePointsToDependency(e)
                } else {
                    state.updatePointsToDependency(eps.asInstanceOf[EPS[Entity, PointsTo]])
                }
                returnResult(calls)(state)
        }
    }
}

object PointsToBasedCallGraphScheduler extends FPCFTriggeredAnalysisScheduler {
    override type InitializationData = Null

    override def uses: Set[PropertyBounds] = PropertyBounds.ubs(
        PointsTo, Callees, CallersProperty, TACAI
    )

    override def derivesEagerly: Set[PropertyBounds] = Set.empty

    override def derivesCollaboratively: Set[PropertyBounds] = PropertyBounds.ubs(
        Callees, CallersProperty
    )

    override def register(p: SomeProject, ps: PropertyStore, i: Null): PointsToBasedCallGraph = {
        val analysis = new PointsToBasedCallGraph(p)
        ps.registerTriggeredComputation(CallersProperty.key, analysis.analyze)
        analysis
    }

    /**
     * Updates the caller properties of the initial entry points
     * ([[org.opalj.br.analyses.cg.InitialEntryPointsKey]]) to be called from an unknown context.
     * This will trigger the computation of the callees for these methods (see `processMethod`).
     */
    def processEntryPoints(p: SomeProject, ps: PropertyStore): Unit = {
        implicit val logContext: LogContext = p.logContext
        val declaredMethods = p.get(DeclaredMethodsKey)
        val entryPoints = p.get(InitialEntryPointsKey).map(declaredMethods.apply)

        if (entryPoints.isEmpty)
            logOnce(Error("project configuration", "the project has no entry points"))

        entryPoints.foreach { ep ⇒
            ps.preInitialize(ep, CallersProperty.key) {
                case _: EPK[_, _] ⇒
                    InterimEUBP(ep, OnlyCallersWithUnknownContext)
                case InterimUBP(ub: CallersProperty) ⇒
                    InterimEUBP(ep, ub.updatedWithUnknownContext())
                case r ⇒
                    throw new IllegalStateException(s"unexpected eps $r")
            }
        }
    }

    override def init(p: SomeProject, ps: PropertyStore): Null = {
        processEntryPoints(p, ps)
        null
    }

    override def beforeSchedule(p: SomeProject, ps: PropertyStore): Unit = {}

    override def afterPhaseCompletion(
        p: SomeProject, ps: PropertyStore, analysis: FPCFAnalysis
    ): Unit = {}

    override def afterPhaseScheduling(ps: PropertyStore, analysis: FPCFAnalysis): Unit = {}

    override def triggeredBy: PropertyKind = CallersProperty
}