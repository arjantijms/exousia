/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.exousia.permissions;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Minimal Jakarta REST URI path template compiler for authorization purposes.
 *
 * <p>This class intentionally does not depend on Jersey. It implements the
 * subset of Jakarta REST URI template matching needed by {@link RestResourcePermission}:
 *
 * <ul>
 *   <li>literal path characters are URI encoded and regex escaped;</li>
 *   <li>{@code {name}} matches one path segment;</li>
 *   <li>{@code {name: regex}} uses the supplied regex;</li>
 *   <li>template variable names are ignored for matching;</li>
 *   <li>right-hand-path matching can be open or closed.</li>
 * </ul>
 */
final class RestUriTemplate {

    private static final String DEFAULT_VARIABLE_REGEX = "[^/]+?";

    enum RightHandPath {
        /**
         * Matches zero or more remaining path segments. This mirrors the
         * Jakarta REST R(A) function used during staged request matching.
         */
        OPEN("(/.*)?"),

        /**
         * Matches no remaining path segment except an optional trailing slash.
         * This is the preferred mode for a full resource-method permission.
         */
        CLOSED("(/)?");

        private final String regex;

        RightHandPath(String regex) {
            this.regex = regex;
        }
    }

    private final String template;
    private final Pattern pattern;

    RestUriTemplate(String template, RightHandPath rightHandPath) {
        this.template = normalizeTemplate(template);
        this.pattern = Pattern.compile(toRegex(this.template, rightHandPath));
    }

    boolean matches(String path) {
        return pattern.matcher(normalizePath(path)).matches();
    }

    String template() {
        return template;
    }

    Pattern pattern() {
        return pattern;
    }

    private static String toRegex(String template, RightHandPath rightHandPath) {
        StringBuilder regex = new StringBuilder();

        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < template.length();) {
            char c = template.charAt(i);

            if (c == '{') {
                appendLiteralRegex(regex, literal);
                literal.setLength(0);

                Variable variable = parseVariable(template, i);
                regex.append('(')
                     .append(variable.regex())
                     .append(')');

                i = variable.endIndex() + 1;
            } else {
                literal.append(c);
                i++;
            }
        }

        appendLiteralRegex(regex, literal);

        if (!regex.isEmpty() && regex.charAt(regex.length() - 1) == '/') {
            regex.setLength(regex.length() - 1);
        }

        regex.append(rightHandPath.regex);

        try {
            Pattern.compile(regex.toString());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                "Invalid REST URI template regex for template " + template + ": " + regex, e);
        }

        return regex.toString();
    }

    private static Variable parseVariable(String template, int openIndex) {
        StringBuilder name = new StringBuilder();
        StringBuilder explicitRegex = new StringBuilder();

        boolean inRegex = false;
        boolean escaped = false;
        int characterClassDepth = 0;
        int regexBraceDepth = 0;
        int regexParenthesisDepth = 0;

        for (int i = openIndex + 1; i < template.length(); i++) {
            char c = template.charAt(i);

            if (!inRegex) {
                if (c == ':') {
                    inRegex = true;
                    continue;
                }

                if (c == '}') {
                    String variableName = name.toString().trim();
                    validateVariableName(variableName, template);
                    return new Variable(variableName, DEFAULT_VARIABLE_REGEX, i);
                }

                name.append(c);
                continue;
            }

            if (escaped) {
                explicitRegex.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                explicitRegex.append(c);
                escaped = true;
                continue;
            }

            if (c == '[') {
                characterClassDepth++;
                explicitRegex.append(c);
                continue;
            }

            if (c == ']' && characterClassDepth > 0) {
                characterClassDepth--;
                explicitRegex.append(c);
                continue;
            }

            if (characterClassDepth == 0) {
                if (c == '(') {
                    regexParenthesisDepth++;
                    explicitRegex.append(c);
                    continue;
                }

                if (c == ')' && regexParenthesisDepth > 0) {
                    regexParenthesisDepth--;
                    explicitRegex.append(c);
                    continue;
                }

                if (c == '{') {
                    regexBraceDepth++;
                    explicitRegex.append(c);
                    continue;
                }

                if (c == '}' && regexBraceDepth > 0) {
                    regexBraceDepth--;
                    explicitRegex.append(c);
                    continue;
                }

                if (c == '}' && regexParenthesisDepth == 0) {
                    String variableName = name.toString().trim();
                    String regex = explicitRegex.toString().trim();

                    validateVariableName(variableName, template);

                    return new Variable(
                        variableName,
                        regex.isEmpty() ? "" : regex,
                        i);
                }
            }

            explicitRegex.append(c);
        }

        throw new IllegalArgumentException("Unterminated REST URI template variable in: " + template);
    }

    private static void validateVariableName(String name, String template) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Empty REST URI template variable name in: " + template);
        }

        char first = name.charAt(0);
        if (!Character.isLetterOrDigit(first) && first != '_') {
            throw new IllegalArgumentException(
                "Illegal REST URI template variable name '" + name + "' in: " + template);
        }

        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);

            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                throw new IllegalArgumentException(
                    "Illegal REST URI template variable name '" + name + "' in: " + template);
            }
        }
    }

    private static void appendLiteralRegex(StringBuilder regex, StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }

        appendEncodedLiteralRegex(regex, literal.toString());
    }

    /**
     * URI-encodes literal path characters and escapes regex metacharacters.
     *
     * <p>Existing percent-encoded octets are preserved and matched
     * case-insensitively, following Jersey's useful behavior for hex digits.
     */
    private static void appendEncodedLiteralRegex(StringBuilder regex, String literal) {
        for (int i = 0; i < literal.length();) {
            char c = literal.charAt(i);

            if (c == '%' && i + 2 < literal.length()
                    && isHex(literal.charAt(i + 1))
                    && isHex(literal.charAt(i + 2))) {

                regex.append('%');
                appendHexRegex(regex, literal.charAt(i + 1));
                appendHexRegex(regex, literal.charAt(i + 2));
                i += 3;
                continue;
            }

            int codePoint = literal.codePointAt(i);

            if (isAllowedPathLiteral(codePoint)) {
                appendRegexEscaped(regex, (char) codePoint);
            } else {
                byte[] bytes = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8);

                for (byte b : bytes) {
                    appendPercentEncodedByteRegex(regex, b);
                }
            }

            i += Character.charCount(codePoint);
        }
    }

    /**
     * Allows RFC 3986 pchar plus '/' because this is a path template, not a
     * single path segment.
     */
    private static boolean isAllowedPathLiteral(int codePoint) {
        if (codePoint > 0x7F) {
            return false;
        }

        char c = (char) codePoint;

        return isUnreserved(c)
            || isSubDelimiter(c)
            || c == ':'
            || c == '@'
            || c == '/';
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9')
            || c == '-'
            || c == '.'
            || c == '_'
            || c == '~';
    }

    private static boolean isSubDelimiter(char c) {
        return c == '!'
            || c == '$'
            || c == '&'
            || c == '\''
            || c == '('
            || c == ')'
            || c == '*'
            || c == '+'
            || c == ','
            || c == ';'
            || c == '=';
    }

    private static void appendRegexEscaped(StringBuilder regex, char c) {
        if (isRegexMetaCharacter(c)) {
            regex.append('\\');
        }

        regex.append(c);
    }

    private static boolean isRegexMetaCharacter(char c) {
        return c == '\\'
            || c == '.'
            || c == '^'
            || c == '$'
            || c == '|'
            || c == '?'
            || c == '*'
            || c == '+'
            || c == '('
            || c == ')'
            || c == '['
            || c == ']'
            || c == '{'
            || c == '}';
    }

    private static void appendPercentEncodedByteRegex(StringBuilder regex, byte b) {
        int value = b & 0xff;
        regex.append('%');
        appendHexRegex(regex, Character.toUpperCase(Character.forDigit((value >>> 4) & 0x0f, 16)));
        appendHexRegex(regex, Character.toUpperCase(Character.forDigit(value & 0x0f, 16)));
    }

    private static void appendHexRegex(StringBuilder regex, char c) {
        char upper = Character.toUpperCase(c);
        char lower = Character.toLowerCase(c);

        if (upper >= 'A' && upper <= 'F') {
            regex.append('[').append(lower).append(upper).append(']');
        } else {
            regex.append(upper);
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f')
            || (c >= 'A' && c <= 'F');
    }

    private static String normalizeTemplate(String template) {
        return normalizePath(template);
    }

    static String normalizePath(String path) {
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

    private record Variable(String name, String regex, int endIndex) {
    }
}