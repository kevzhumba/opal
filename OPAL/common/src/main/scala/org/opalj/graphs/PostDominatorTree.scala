/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
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
package graphs

import org.opalj.collection.immutable.IntArraySet

/**
 * The post-dominator tree (see [[PostDominatorTree$#apply*]] for details regarding
 * the properties).
 *
 * For information regarding issues related to using post-dominator trees for computing
 * control dependence information see "A New Foundation for Control Dependence and Slicing for
 * Modern Program Structures" (2007, Journal Version appeared in TOPLAS)
 *
 * @param  startNode The (unique) exit node of the underlying CFG or the PDT's (artificial)
 *         start node.
 * @param  hasVirtualStartNode `true` if an artificial end node (w.r.t. the underlying CFG) was
 *         created, because the underlying CFG had multiple exits.
 * @param  additionalExitNodes Nodes in the original, underyling CFG that are treated as additional
 *         exit nodes; e.g., to handle infinite loops.
 * @param  foreachSuccessorOf The original successor information.
 */
final class PostDominatorTree private[graphs] (
        final val startNode:            Int,
        final val hasVirtualStartNode:  Boolean,
        final val additionalExitNodes:  IntArraySet,
        final val foreachSuccessorOf:   Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
        private[graphs] final val idom: Array[Int] // the (post)dominator information
) extends AbstractDominatorTree {

    /**
     * `true` if the graph has a virtual start node; always implicitly `true` if
     * we have additional exit nodes. I.e., only methods with a unique end node without
     * artificial exit nodes are not augmented.
     */
    final def isAugmented: Boolean = hasVirtualStartNode

}

/**
 * Factory for post dominator trees.
 */
object PostDominatorTree {

    /**
     * Computes the post dominator tree for the given control flow graph. (The reverse
     * control flow graph will computed on demand by this method.)
     * If necessary, an artificial start node will be created to ensure that we have a unique
     * start node for the post dominator tree; if created the node will have the
     * `id = (maxNodeId+1)`; additionally, all edges are automatically reversed.
     *
     * If this post-dominator tree is used to compute control-dependence information, the
     * control-dependence  information is generally non-termination ''in''sensitive; i.e.,
     * conceptually every loop is expected to eventually terminate.
     * Hence, an instruction following the loop will not depend on the `if` related
     * to evaluating the loop condition. However, non-handled exceptions (i.e., if we have
     * multiple exit nodes, may destroy this illusion!) For details see:
     * <pre>
     * A New Foundation for Control Dependence and Slicing for Modern Program Structures
     * 2007, Journal Version appeared in ACM TOPLAS
     * </pre>
     *
     * @example
     *      Computing the post dominator tree:
     *      {{{
     *      scala>//Graph: 0 -> 1->E;  1 -> 2->E
     *      scala>def isExitNode(i: Int) = i == 1 || i == 2
     *      isExitNode: (i: Int)Boolean
     *
     *       scala>def foreachExitNode(f: Int ⇒ Unit) = { f(1); f(2) }
     *      foreachExitNode: (f: Int => Unit)Unit
     *
     *      scala>def foreachPredecessorOf(i: Int)(f: Int ⇒ Unit) = i match {
     *           |    case 0 ⇒
     *           |    case 1 ⇒ f(0)
     *           |    case 2 ⇒ f(1)
     *           |}
     *      foreachPredecessorOf: (i: Int)(f: Int => Unit)Unit
     *      scala>def foreachSuccessorOf(i: Int)(f: Int ⇒ Unit) = i match {
     *            |    case 0 ⇒ f(1)
     *            |    case 1 ⇒ f(2)
     *            |    case 2 ⇒
     *            |}
     *      foreachSuccessorOf: (i: Int)(f: Int => Unit)Unit
     *      scala>val pdt = org.opalj.graphs.PostDominatorTree.apply(
     *           |    uniqueExitNode = None,
     *           |    isExitNode,
     *           |    org.opalj.collection.immutable.IntArraySet.empty,
     *           |    foreachExitNode,
     *           |    foreachSuccessorOf,
     *           |    foreachPredecessorOf,
     *           |    2
     *           |)
     *      pdt: org.opalj.graphs.PostDominatorTree = org.opalj.graphs.PostDominatorTree@3a82ac80
     *      scala>pdt.toDot()
     *      scala>org.opalj.io.writeAndOpen(pdt.toDot(i => true),"PDT",".gv")
     *      }}}
     *
     * @param   uniqueExitNode `true` if and only if the underlying CFG has a a unique exit node.
     *          (This property is independent of the `additionalExitNodes` property which
     *          is not a statement about the underlying CFG, but a directive how to computed
     *          the post-dominator tree.)
     * @param   isExitNode A function that returns `true` if the given node – in the underlying
     *          (control-flow) graph – is an exit node; that is the node has no successors.
     * @param   foreachExitNode A function f that takes a function g with an int parameter which
     *          identifies a node and which executes g for each exit node.
     *          '''Note that _all nodes_ except those belonging to those transitively
     *          reachable from a start node of an infinite loop  have to be reachable from the
     *          exit nodes; otherwise the PostDominatorTree will be a forest and will be generally
     *          useless.'''
     * @param   maxNode The largest id used by the underlying (control-flow) graph; required to
     *          assign the virtual start node of the pdt - if required - a unique id.
     */
    def apply(
        uniqueExitNode:       Option[Int],
        isExitNode:           Int ⇒ Boolean,
        additionalExitNodes:  IntArraySet,
        foreachExitNode:      (Int ⇒ Unit) ⇒ Unit,
        foreachSuccessorOf:   Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
        foreachPredecessorOf: Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
        maxNode:              Int
    ): PostDominatorTree = {
        if (uniqueExitNode.isDefined && additionalExitNodes.isEmpty) {
            val startNode = uniqueExitNode.get

            DominatorTree.create(
                startNode,
                hasVirtualStartNode = false,
                foreachPredecessorOf, foreachSuccessorOf,
                maxNode, // unchanged, the graph is not augmented
                (
                    startNode: Int,
                    hasVirtualStartNode: Boolean,
                    foreachSuccessorOf: Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
                    idom: Array[Int]
                ) ⇒ {
                    new PostDominatorTree(
                        startNode,
                        hasVirtualStartNode,
                        additionalExitNodes,
                        foreachSuccessorOf,
                        idom
                    )
                }
            )

        } else {
            // create a new artificial start node
            val startNode = maxNode + 1

            // reverse flowgraph
            val revFGForeachSuccessorOf: Int ⇒ ((Int ⇒ Unit) ⇒ Unit) = (n: Int) ⇒ {
                if (n == startNode) {
                    (f: Int ⇒ Unit) ⇒ { foreachExitNode(f); additionalExitNodes.foreach(f) }
                } else {
                    foreachPredecessorOf(n)
                }
            }

            val revFGForeachPredecessorOf: Int ⇒ ((Int ⇒ Unit) ⇒ Unit) = (n: Int) ⇒ {
                if (n == startNode) {
                    DominatorTree.fornone
                } else if (isExitNode(n) || additionalExitNodes.contains(n)) {
                    // a function that expects a function that will be called for all successors
                    (f: Int ⇒ Unit) ⇒ { f(startNode); foreachSuccessorOf(n)(f) }
                } else {
                    foreachSuccessorOf(n)
                }
            }

            DominatorTree.create(
                startNode,
                hasVirtualStartNode = true,
                revFGForeachSuccessorOf, revFGForeachPredecessorOf,
                maxNode = startNode /* we have an additional node */ ,
                (
                    startNode: Int,
                    hasVirtualStartNode: Boolean,
                    foreachSuccessorOf: Int ⇒ ((Int ⇒ Unit) ⇒ Unit),
                    idom: Array[Int]
                ) ⇒ {
                    new PostDominatorTree(
                        startNode,
                        hasVirtualStartNode,
                        additionalExitNodes,
                        foreachSuccessorOf,
                        idom
                    )
                }
            )
        }
    }
}
