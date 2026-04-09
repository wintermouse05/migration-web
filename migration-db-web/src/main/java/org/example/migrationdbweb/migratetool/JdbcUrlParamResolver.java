package org.example.migrationdbweb.migratetool;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class JdbcUrlParamResolver {

    private JdbcUrlParamResolver() {
    }

    public static boolean hasExplicitPostgresSchemaParam(String jdbcUrl) {
        return resolvePostgresSchemaFromUrl(jdbcUrl).isPresent();
    }

    public static boolean hasExplicitPostgresTimezoneParam(String jdbcUrl) {
        return resolvePostgresTimezoneFromUrl(jdbcUrl).isPresent();
    }

    public static boolean hasExplicitOracleSchemaParam(String jdbcUrl) {
        return resolveOracleSchemaFromUrl(jdbcUrl).isPresent();
    }

    public static Optional<String> resolvePostgresSchemaFromUrl(String jdbcUrl) {
        if (!isPostgresJdbcUrl(jdbcUrl)) {
            return Optional.empty();
        }

        Map<String, List<String>> params = parseQueryParams(jdbcUrl);
        Optional<String> currentSchema = firstNonBlank(params, "currentschema");
        if (currentSchema.isPresent()) {
            return currentSchema;
        }

        Optional<String> searchPath = firstNonBlank(params, "search_path");
        if (searchPath.isPresent()) {
            return firstSchemaFromSearchPath(searchPath.get());
        }

        Optional<String> options = firstNonBlank(params, "options");
        if (options.isPresent()) {
            Optional<String> fromOptions = parseFromOptions(options.get(), "search_path");
            if (fromOptions.isPresent()) {
                return firstSchemaFromSearchPath(fromOptions.get());
            }
        }

        return Optional.empty();
    }

    public static Optional<String> resolvePostgresTimezoneFromUrl(String jdbcUrl) {
        if (!isPostgresJdbcUrl(jdbcUrl)) {
            return Optional.empty();
        }

        Map<String, List<String>> params = parseQueryParams(jdbcUrl);
        Optional<String> timezone = firstNonBlank(params, "timezone");
        if (timezone.isPresent()) {
            return timezone;
        }

        Optional<String> options = firstNonBlank(params, "options");
        if (options.isPresent()) {
            return parseFromOptions(options.get(), "timezone");
        }

        return Optional.empty();
    }

    public static Optional<String> resolveOracleSchemaFromUrl(String jdbcUrl) {
        if (!isOracleJdbcUrl(jdbcUrl)) {
            return Optional.empty();
        }

        Map<String, List<String>> params = parseQueryParams(jdbcUrl);

        Optional<String> oracleCurrentSchema = firstNonBlank(params, "oracle.jdbc.currentschema");
        if (oracleCurrentSchema.isPresent()) {
            return Optional.of(unquote(oracleCurrentSchema.get()));
        }

        Optional<String> oracleCurrentSchemaSnakeCase = firstNonBlank(params, "oracle.jdbc.current_schema");
        if (oracleCurrentSchemaSnakeCase.isPresent()) {
            return Optional.of(unquote(oracleCurrentSchemaSnakeCase.get()));
        }

        Optional<String> currentSchema = firstNonBlank(params, "currentschema");
        if (currentSchema.isPresent()) {
            return Optional.of(unquote(currentSchema.get()));
        }

        Optional<String> currentSchemaSnakeCase = firstNonBlank(params, "current_schema");
        if (currentSchemaSnakeCase.isPresent()) {
            return Optional.of(unquote(currentSchemaSnakeCase.get()));
        }

        Optional<String> schema = firstNonBlank(params, "schema");
        if (schema.isPresent()) {
            return Optional.of(unquote(schema.get()));
        }

        return Optional.empty();
    }

    private static boolean isPostgresJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String normalized = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("jdbc:postgresql:");
    }

    private static boolean isOracleJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String normalized = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("jdbc:oracle:");
    }

    private static Map<String, List<String>> parseQueryParams(String jdbcUrl) {
        Map<String, List<String>> params = new HashMap<>();
        if (jdbcUrl == null) {
            return params;
        }

        int queryStart = jdbcUrl.indexOf('?');
        if (queryStart < 0 || queryStart >= jdbcUrl.length() - 1) {
            return params;
        }

        String query = jdbcUrl.substring(queryStart + 1);
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            query = query.substring(0, fragmentStart);
        }

        for (String pair : query.split("[&;]")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            String key = decode(keyValue[0]).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            String value = keyValue.length > 1 ? decode(keyValue[1]).trim() : "";
            params.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }

        return params;
    }

    private static Optional<String> firstNonBlank(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }

        for (int i = values.size() - 1; i >= 0; i--) {
            String value = values.get(i);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> parseFromOptions(String optionsValue, String optionKey) {
        if (optionsValue == null || optionsValue.isBlank()) {
            return Optional.empty();
        }

        String normalizedNeedle = optionKey.toLowerCase(Locale.ROOT) + "=";
        String[] tokens = optionsValue.trim().split("\\s+");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = token.toLowerCase(Locale.ROOT);
            if (normalizedToken.startsWith(normalizedNeedle)) {
                return Optional.of(token.substring(token.indexOf('=') + 1).trim());
            }
            if ("-c".equals(normalizedToken)) {
                continue;
            }
            if (normalizedToken.startsWith("-c" + normalizedNeedle)) {
                return Optional.of(token.substring(token.indexOf('=') + 1).trim());
            }
        }

        String normalizedOptions = optionsValue.toLowerCase(Locale.ROOT);
        int idx = normalizedOptions.indexOf(normalizedNeedle);
        if (idx < 0) {
            return Optional.empty();
        }

        String tail = optionsValue.substring(idx + normalizedNeedle.length());
        int end = tail.indexOf(' ');
        String value = end >= 0 ? tail.substring(0, end) : tail;
        value = value.trim();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<String> firstSchemaFromSearchPath(String searchPathValue) {
        if (searchPathValue == null || searchPathValue.isBlank()) {
            return Optional.empty();
        }

        String[] items = searchPathValue.split(",");
        for (String item : items) {
            if (item == null) {
                continue;
            }
            String schema = item.trim();
            if (schema.isEmpty() || "\"$user\"".equalsIgnoreCase(schema) || "$user".equalsIgnoreCase(schema)) {
                continue;
            }
            return Optional.of(unquote(schema));
        }

        return Optional.empty();
    }

    private static String unquote(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
