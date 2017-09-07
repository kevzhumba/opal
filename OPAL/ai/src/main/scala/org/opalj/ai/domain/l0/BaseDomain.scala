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
package ai
package domain
package l0

import org.opalj.br.Method
import org.opalj.br.analyses.Project

/**
 * A complete domain that performs all computations at the type level.
 *
 * @note    This domain is intended to be used for '''demo purposes only'''.
 *          '''Tests should create their own domains to make sure that
 *          the test results remain stable. The configuration of this
 *          domain just reflects a reasonable configuration that may
 *          change without further notice.'''
 *
 * @author Michael Eichberg
 */
class BaseDomain[Source](
        val project: Project[Source],
        val method:  Method
) extends TypeLevelDomain
    with ThrowAllPotentialExceptionsConfiguration
    with IgnoreSynchronization
    with DefaultTypeLevelHandlingOfMethodResults
    with TheProject
    with TheMethod

object BaseDomain {

    /**
     * @tparam Source The type of the underlying source files (e.g., java.net.URL)
     * @return A new instance of a `BaseDomain`.
     */
    def apply[Source](project: Project[Source], method: Method): BaseDomain[Source] = {
        new BaseDomain(project, method)
    }

}

/**
 * Configuration of a domain that uses the `l0` domains and
 * which also records the abstract-interpretation time control flow graph and def/use
 * information.
 * @tparam S The source file's type.
 */
class BaseDomainWithDefUse[Source](
        project: Project[Source],
        method:  Method
) extends BaseDomain[Source](project, method) with RecordDefUse
