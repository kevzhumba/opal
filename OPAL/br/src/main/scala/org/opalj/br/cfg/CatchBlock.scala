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
package org.opalj.br.cfg

import org.opalj.br.Code
import org.opalj.br.PC
import org.opalj.br.ExceptionHandler

/**
 *
 * @author Erich Wittenbeck
 */

/**
 * Represents the entry-point for a exception-handlers handling-code in a
 * control flow graph
 *
 * ==Thread-Safety==
 * This class is thread-safe
 */
class CatchBlock(val handler: ExceptionHandler) extends CFGBlock {

    final def startPC: PC = handler.startPC
    final def endPC: PC = handler.endPC
    final def handlerPC: PC = handler.handlerPC

    override def id: Int = handlerPC * (-1)

    override def toHRR: Option[String] = {
        Some("cb"+handlerPC)
    }

    def toDot(code: Code): String = {
        var res: String = this.toHRR.get+" [shape=box, label=\""+this.toHRR.get+"\"];\n"

        for (succ ← successors) {
            res = res + this.toHRR.get+" -> "+succ.toHRR.get+";\n"
        }

        res
    }

}