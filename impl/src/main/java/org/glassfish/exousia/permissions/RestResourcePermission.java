/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.exousia.permissions;

import java.security.Permission;
import java.util.Locale;
import java.util.Objects;

import static org.glassfish.exousia.permissions.RestUriTemplate.RightHandPath.CLOSED;

/**
 * Permission for Jakarta REST resource paths.
 *
 * <p>The permission name is a context-relative REST URI path template, for example:
 *
 * <pre>
 * /rest/users/{username: [A-Z][a-zA-Z_0-9]*}
 * </pre>
 *
 * <p>The action is the HTTP method, for example {@code GET}, {@code POST}, or {@code DELETE}.
 *
 * <p>A stored permission implies a requested permission when:
 *
 * <ul>
 *   <li>the HTTP method matches; and</li>
 *   <li>the stored URI template matches the requested actual path.</li>
 * </ul>
 */
public final class RestResourcePermission extends Permission {

    private static final long serialVersionUID = 1L;

    private final String path;
    private final String httpMethod;
    private final String actions;
    private final RestUriTemplate template;

    public RestResourcePermission(String path, String httpMethod) {
        super(RestUriTemplate.normalizePath(path));

        this.path = RestUriTemplate.normalizePath(path);
        this.httpMethod = normalizeHttpMethod(httpMethod);
        this.actions = this.httpMethod == null ? "" : this.httpMethod;

        /*
         * RestResourcePermission is intended for full discovered resource method
         * paths, so use closed matching:
         *
         *   /rest/foo/{id}  matches  /rest/foo/1
         *   /rest/foo/{id}  matches  /rest/foo/1/
         *   /rest/foo/{id}  rejects   /rest/foo/1/extra
         *
         * If we later need staged class/sub-resource matching, we can add a
         * factory or constructor variant that uses RightHandPath.OPEN.
         */
        this.template = new RestUriTemplate(this.path, CLOSED);
    }

    @Override
    public boolean implies(Permission permission) {
        if (!(permission instanceof RestResourcePermission other)) {
            return false;
        }

        return methodImplies(other.httpMethod)
            && template.matches(other.path);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RestResourcePermission permission)) {
            return false;
        }

        return path.equals(permission.path)
            && Objects.equals(httpMethod, permission.httpMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, httpMethod);
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
}