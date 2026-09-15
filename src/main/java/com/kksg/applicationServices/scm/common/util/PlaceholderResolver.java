package com.kksg.applicationServices.scm.common.util;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{placeholder}}} tokens in declarative provider configuration.
 *
 * <p>This is the whole of the module's "templating". It is intentionally not a template
 * <i>engine</i>: there are no conditionals, loops, function calls or expressions. The only
 * operation is "look this key up in the parameter map and paste its value". That restriction is
 * what keeps database-held configuration safe to load - a malicious or careless configuration row
 * can produce a wrong URL, but it can never execute logic.
 *
 * <p>Two resolution modes exist because the correct behaviour for a missing value differs by
 * position:
 * <ul>
 *   <li>An endpoint path with an unresolved token would produce a request to a literally wrong URL,
 *       so {@link #resolveRequired} fails loudly.</li>
 *   <li>A query parameter such as {@code page} is frequently absent, so {@link #resolveOptional}
 *       reports absence and the caller omits the parameter entirely.</li>
 * </ul>
 */
public final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\[\\]-]+)\\s*}}");

    private PlaceholderResolver() {
    }

    public static boolean containsPlaceholder(String template) {
        return template != null && PLACEHOLDER.matcher(template).find();
    }

    /**
     * Substitutes every token, failing when any is unresolved.
     *
     * @param context short description of what is being resolved (operation code, field name) used
     *                only to make the error actionable; never include secret material here.
     * @throws ScmException with {@link ScmErrorCode#SCM_OPERATION_PARAMETER_MISSING} if a token has
     *                      no corresponding parameter.
     */
    public static String resolveRequired(String template, Map<String, Object> parameters, String context) {
        if (template == null) {
            return null;
        }
        Set<String> missing = new LinkedHashSet<>();
        String result = substitute(template, parameters, missing);
        if (!missing.isEmpty()) {
            throw new ScmException(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING,
                    "%s requires parameter(s) %s".formatted(context, missing));
        }
        return result;
    }

    /**
     * Substitutes every token, returning {@link Optional#empty()} if any is unresolved so the
     * caller can drop the whole value.
     */
    public static Optional<String> resolveOptional(String template, Map<String, Object> parameters) {
        if (template == null) {
            return Optional.empty();
        }
        Set<String> missing = new LinkedHashSet<>();
        String result = substitute(template, parameters, missing);
        return missing.isEmpty() ? Optional.of(result) : Optional.empty();
    }

    private static String substitute(String template, Map<String, Object> parameters, Set<String> missing) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int cursor = 0;

        while (matcher.find()) {
            out.append(template, cursor, matcher.start());
            String key = matcher.group(1);
            Object value = ParameterPaths.get(parameters, key);
            if (value == null) {
                missing.add(key);
            } else {
                out.append(value);
            }
            cursor = matcher.end();
        }
        out.append(template.substring(cursor));
        return out.toString();
    }
}
