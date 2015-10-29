package org.opalj
package fpcf
package analysis

import java.net.URL
import net.ceedubs.ficus.Ficus._
import org.opalj.br.analyses.Project
import org.opalj.br.SourceElement
import org.opalj.br.analyses.SourceElementsPropertyStoreKey
import org.opalj.AnalysisModes._
import org.opalj.br.analyses.SomeProject
import scala.reflect.ClassTag

//abstract class FPCFAnalysis[E <: Entity](val project: SomeProject) {
//
//    def determineProperty(entity: E): PropertyComputationResult
//
//}
//abstract class DefaultFPCFAnalysis[T <: Entity: ClassTag](
//        project: SomeProject,
//        val entitySelector: PartialFunction[Entity, T] = DefaultFPCFAnalysis.entitySelector()) extends FPCFAnalysis(project) {
//
//    implicit val propertyStore = project.get(SourceElementsPropertyStoreKey)
//
//    //implicit val analysisMode = project....
//
//    propertyStore <||< (entitySelector, determineProperty)
//
//}
//
//private[analysis] object DefaultFPCFAnalysis {
//    def entitySelector[T <: Entity: ClassTag](): PartialFunction[Entity, T] = new PartialFunction[Entity, T] {
//        def apply(v1: Entity): T = {
//            if (isDefinedAt(v1))
//                v1.asInstanceOf[T]
//            else
//                throw new IllegalArgumentException
//        }
//
//        def isDefinedAt(x: Entity): Boolean = {
//            val ct = implicitly[ClassTag[T]]
//            x.getClass.isInstance(ct.runtimeClass)
//        }
//    }
//}
//def entitySelector[T : reflect.ClassTag](): String = {val ct = implicitly[reflect.ClassTag[T]]; ct.runtimeClass.toString  }

//new DefaultFPCFAnalysis[Method](project) {
//    
//    def determineProperty(...)
//    
//}

/**
 * The fixpoint analysis trait is a common supertrait for all analyses that leverage the
 * fixpoint framework. These analyses compute specific properties to a given set of entities.
 *
 * Each fixpoint analysis requires it's own [[AnalysisEngine]] to specify the analysis. The
 * entities which should be processed and the actual property computation are specified in the
 * analysis engine.
 *
 * @note Fixpoint analyses often depend on other analyses, if you want to model these, have a look
 * to the substrait //TODO: create subtrait to model analyses with dependencies.
 *
 * @author Michael Reif
 */
trait FixpointAnalysis {
    this: AnalysisEngine[_] ⇒

    // TODO REMOVE
    /**
     * Returns the unique [[org.opalj.fp.PropertyKey]] of the computed [[org.opalj.fp.Property]] which is shared between
     * all properties of the same kind.
     */
    val propertyKey: PropertyKey

    /**
     * An initialization method that can be used by subclasses to introduce new state into a
     * concrete analysis. This method should be used by subclasses that need to create state
     * depending on the analyzed project.
     *
     * @note Since multiple different subclasses could be mixed in, make sure that
     *  `super.initializeAssumptions` is called somewhere in the overridden method.
     *
     * @note E.g. the configuration file is available over the implicit project parameter.
     *       If you want to check some configured value, you can do this within this method.
     *
     */
    def initializeAssumptions(implicit project: SomeProject): Unit = {
        /* do nothing */
    }

    /**
     * This method triggers the analysis which is composed by the [[AnalysisEngine]].
     */
    final def analyze(implicit project: SomeProject): Unit = {
        implicit val propertyStore = project.get(SourceElementsPropertyStoreKey)
        initializeAssumptions
        triggerPropertyCalculation
    }
}

/**
 * The analysis engine trait is a common supertrait for all different computation kinds that
 * are supported by the [[org.opalj.fp.PropertyStore]].
 *
 * It is composed by a ´triggerPropertyCalculation´ which refers to one of the functions defined by
 * the property store and by a ´determineProperty´ function which has to be overridden in subclasses
 * to implement the property calculation.
 */
trait AnalysisEngine[E <: Entity] {

    /**
     * This function triggers the actual computation of the properties.
     *
     * All subclasses have to override this function. Since the correct function
     * of the property store is invoked here, the ´determineProperty´ function has
     * to be passed here to chosen calculation function.
     */
    def triggerPropertyCalculation(implicit project: SomeProject, propertyStore: PropertyStore): Unit

    /**
     * This function takes an entity as input and calculates the property of this analysis
     * associated with the entity.
     *
     * @note Subclasses had to pass this method in ´triggerPropertyCalculation´ to the called
     * property store function.
     */
    def determineProperty(
        entity: E
    )(
        implicit
        project: SomeProject,
        store:   PropertyStore
    ): PropertyComputationResult

}

/**
 * The all entities analysis engine will process all matching entities in the propertyStore which
 * can be passed to the ´determineProperty´ function.
 */
trait AllEntities[E <: Entity] extends AnalysisEngine[E] {

    /**
     * Triggers the property computation on all entities of the property store which can be passed
     * to the ´determineProperty´ function.
     *
     * @note see [org.opalj.fp.PropertyStore#<<]
     */
    def triggerPropertyCalculation(
        implicit
        project:       SomeProject,
        propertyStore: PropertyStore
    ) =
        propertyStore << (determineProperty _).
            asInstanceOf[Object ⇒ PropertyComputationResult]
}

trait AssumptionBasedFixpointAnalysis extends FixpointAnalysis {
    this: AnalysisEngine[_] ⇒

    private[this] var _analysisMode: AnalysisMode = null

    private[this] def analysisMode_=(analysisMode: AnalysisMode): Unit = { _analysisMode = analysisMode }

    def analysisMode = _analysisMode

    /**
     * The project is a library which may be extended by adding classes to the project's packages.
     */
    def isOpenLibrary = analysisMode eq OPA

    def isClosedLibrary = analysisMode eq CPA

    def isApplication = analysisMode eq APP

    abstract override def initializeAssumptions(implicit project: SomeProject): Unit = {
        super.initializeAssumptions
        assert(this._analysisMode eq null)
        analysisMode = AnalysisModes.withName(project.config.as[String]("org.opalj.analysisMode"))
    }

}

/**
 * The filter entities analysis engine will process all entities in the propertyStore which
 * that match the given predicate.
 */
trait FilterEntities[E <: Entity] extends AnalysisEngine[E] {

    /**
     * This has to be set to the partial function that selects the entities for which
     * the analysis shall compute the specific properties.
     *
     * example to select all classFiles:
     * {{{
     *  val pf = { case cf: ClassFile => cf }
     *  projectStore <||< (pf, determineProperty)
     * }}}
     *
     * @note This function is passed to the chosen calculation function of the property store.
     */
    protected[this] val entitySelector: PartialFunction[Entity, E]

    /**
     * Triggers the property computation on the propertyStore.
     *
     * @note see [[org.opalj.fp.PropertyStore#<||<]]
     */
    def triggerPropertyCalculation(implicit project: SomeProject, propertyStore: PropertyStore) = {
        val propertyCalculation = (determineProperty _).
            asInstanceOf[Object ⇒ PropertyComputationResult]
        propertyStore <||< (entitySelector, propertyCalculation)
    }
}
