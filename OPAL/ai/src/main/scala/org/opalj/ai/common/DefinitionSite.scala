/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package ai
package common

import org.opalj.br.Method
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.tac.Assignment
import org.opalj.tac.DUVar
import org.opalj.tac.ExprStmt
import org.opalj.tac.TACMethodParameter
import org.opalj.tac.TACode

import org.opalj.value.ValueInformation

/**
 * Identifies a definition site object in a method in the bytecode using its program counter and
 * the corresponding use-sites.
 * It acts as entity for the [[org.opalj.fpcf.properties.EscapeProperty]] and the computing
 * analyses.
 * A definition-site can be for example an allocation, the result of a function call, or the result
 * of a field-retrieval.
 *
 * @author Dominik Helm
 * @author Florian Kuebler
 */
case class DefinitionSite(method: Method, pc: Int) extends DefinitionSiteLike {
    override def usedBy[V <: ValueInformation](
        tacode: TACode[TACMethodParameter, DUVar[V]]
    ): IntTrieSet = {
        val defSite = tacode.pcToIndex(pc)
        if (defSite == -1) {
            // the code is dead
            IntTrieSet.empty
        } else {
            tacode.stmts(defSite) match {
                case Assignment(_, dvar, _) ⇒ dvar.usedBy
                case _: ExprStmt[_]         ⇒ IntTrieSet.empty
                case stmt ⇒
                    throw new RuntimeException(s"unexpected stmt ($stmt) at definition site $this")
            }
        }
    }
}
