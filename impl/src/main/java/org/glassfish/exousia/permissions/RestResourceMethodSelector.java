/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.exousia.permissions;

import java.util.Optional;

/**
 * A RestPathTranslator translates an actual requested path (relative to the REST mount point/base), to the template
 * associated with the Resource that the target Jakarta REST implementation would actually select.
 *
 * <p>
 * In principle this means an implementation SHOULD come from the actual Jakarta REST implementation that processes the
 * REST request, and any compatible implementation MUST at least follow Jakarta REST $3.7.2
 * (https://jakarta.ee/specifications/restful-ws/4.0/jakarta-restful-ws-spec-4.0#request_matching). When the selection
 * is still unresolved after following Jakarta REST $3.7.2 (more than one resource candidate remains), the returned
 * result is implementation specific.
 *
 * <p>
 * EXPERIMENTAL API, SUBJECT TO CHANGE!
 *
 */
public interface RestResourceMethodSelector {
    Optional<SelectedResourceMethod> select(String encodedApplicationRelativePath, String httpMethod);

    record SelectedResourceMethod(String templatePath, String effectiveDesignator) {
    }
}