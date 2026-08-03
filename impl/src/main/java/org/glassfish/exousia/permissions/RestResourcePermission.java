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

import java.security.Permission;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.glassfish.exousia.permissions.RestResourceMethodSelector.SelectedResourceMethod;

import static java.util.Objects.requireNonNull;
import static org.glassfish.exousia.permissions.RestResourcePermission.Kind.REQUESTED;
import static org.glassfish.exousia.permissions.RestResourcePermission.Kind.STORED;

/**
 * Permission for Jakarta REST resource template paths.
 *
 * <p>
 * The permission name is a context-relative REST URI path template in the case of a <em>stored</em>
 * permission, while for a <em>requested</em> permission it is a context relative URI request path.
 *
 * <p>
 * A <em>stored</em> permission is the permission that's stored in a Policy and is created by the 4 argument
 * constructor.
 *
 * <p>
 * A <em>requested</em> permission is the permission representing a request to be checked for authorization and
 * is created by the 2 argument constructor.
 *
 * The stored permission needs a separate base, because the base effectively selects the REST
 * "application" within a single war (there can be multiple), while the remainder of the (template) path is
 * what a RestResourceMethodSelector uses.
 *
 * <p>
 * E.g. a stored permission:
 * <pre>
 * Base /rest
 * Path /users/{username: [A-Z][a-zA-Z_0-9]*}
 * </pre>
 *
 * A requested permission:
 * <pre>
 * Base [null]
 * Path /rest/users/Reza
 * </pre>
 *
 * <p>The action is the HTTP method, for example {@code GET}, {@code POST}, or {@code DELETE}.
 *
 * <p>A stored permission implies a requested permission when:
 *
 * <ul>
 *   <li>the HTTP method matches; and</li>
 *   <li>the stored path matches the template path the RestResourceMethodSelector selected for the requested path minus the
 *       stored base</li>
 * </ul>
 *
 * <p>
 * Note that the implies relation (for now) is only valid for a stored permission implying a requested permission.
 *
 * <p>
 * EXPERIMENTAL API, SUBJECT TO CHANGE!
 *
 */
public final class RestResourcePermission extends Permission {

    private static final long serialVersionUID = 1L;

    public enum Kind { STORED, REQUESTED }

    private final Kind kind;

    private final String base; // the REST servlet path, the path on which REST is mounted e.g. /rest
    private final String path; // the resource template path, e.g. /users/{username: [A-Z][a-zA-Z_0-9]*} for stored permission, /users/reza for requested permission
    private final String httpMethod;

    // For now transient; look into making RestResourceMethodSelector serialisable later
    private final transient RestResourceMethodSelector restResourceMethodSelector;

    private final String actions;

    public RestResourcePermission(String path, String httpMethod) {
        this(REQUESTED, null, path, httpMethod, null);
    }

    public RestResourcePermission(String base, String path, String httpMethod, RestResourceMethodSelector restResourceMethodSelector) {
        this(STORED, normalizeBase(base), path, httpMethod, requireNonNull(restResourceMethodSelector));
    }

    private RestResourcePermission(Kind kind, String base, String path, String httpMethod, RestResourceMethodSelector restResourceMethodSelector) {
        super(normalizePath(path));

        this.kind = kind;
        this.base = base;
        this.path = normalizePath(path);
        this.httpMethod = normalizeHttpMethod(httpMethod);
        this.restResourceMethodSelector = restResourceMethodSelector;

        this.actions = this.httpMethod == null ? "" : this.httpMethod;
    }

    @Override
    public boolean implies(Permission permission) {
        if (!(permission instanceof RestResourcePermission requestedPermission)) {
            return false;
        }

        if (restResourceMethodSelector == null) {
            return false;
        }

        // Only a stored permission (which carries the base and the selector) can imply a
        // requested one (base null, path carries the full context-relative path).
        if (kind != STORED || requestedPermission.kind != REQUESTED) {
            return false;
        }

        if (!(
             // Test for special case where we are requesting the root resource, e.g the full context path is /rest
            requestedPermission.path.equals(base) ||

            // Test for the general case where the context path starts with /rest, but make a context path
            // like /rest2/foo/bar doesn't match /rest. /rest should only match for /rest/**
            requestedPermission.path.startsWith(base + "/"))) {
            return false;
        }

        // other.path can be e.g. /rest/foo/bar here
        // otherPath will become /foo/bar
        String otherPath = requestedPermission.path.substring(base.length());

        // Select the resource method that corresponds to e.g. /foo/bar for all resource methods for which the REST
        // application is mapped to e.g. /rest here.
        Optional<SelectedResourceMethod> optionalResourceMethod = restResourceMethodSelector.select(otherPath, requestedPermission.httpMethod);
        if (optionalResourceMethod.isEmpty()) {
            return false;
        }

        return
            methodImplies(optionalResourceMethod.get().effectiveDesignator()) &&

            // this.path is the original full template associated with the resource method for which this permission applies
            // e.g. /foo/{id}
            // We now compare this verbatim to the full template associated with the resource method we selected for /foo/bar
            // (we do a simple char for char string compare, not any kind of regexp match here)
            path.equals(optionalResourceMethod.get().templatePath());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RestResourcePermission permission)) {
            return false;
        }

        return kind == permission.kind
            && Objects.equals(base, permission.base)
            && path.equals(permission.path)
            && Objects.equals(httpMethod, permission.httpMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, base, path, httpMethod);
    }

    @Override
    public String getActions() {
        return actions;
    }

    public String getPath() {
        return path;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    private boolean methodImplies(String requestedMethod) {
        return httpMethod == null
            || "*".equals(httpMethod)
            || httpMethod.equals(requestedMethod);
    }

    private static String normalizeHttpMethod(String method) {
        if (method == null || method.isBlank() || "*".equals(method.trim())) {
            return null;
        }

        return method.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeBase(String base) {
        requireNonNull(base);
        String result = base.trim();

        if (result.isEmpty() || result.equals("/") || result.equals("/*")) {
            return "";
        }

        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }

        return result.startsWith("/") ? result : "/" + result;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "/";
        }

        String result = path.trim();

        if (!result.startsWith("/")) {
            result = "/" + result;
        }

        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }
}