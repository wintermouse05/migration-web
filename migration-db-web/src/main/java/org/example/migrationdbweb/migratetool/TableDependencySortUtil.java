package org.example.migrationdbweb.migratetool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Sort table definitions by FK dependencies so parent tables are migrated first.
 */
public final class TableDependencySortUtil {

    private TableDependencySortUtil() {
        // utility class
    }

    public static List<TableDefinition> sortByForeignKeyDependency(List<TableDefinition> tables) {
        if (tables == null || tables.isEmpty()) {
            return tables == null ? new ArrayList<>() : new ArrayList<>(tables);
        }

        Map<String, TableDefinition> byName = new LinkedHashMap<>();
        Map<String, String> canonicalToActualKey = new HashMap<>();
        for (TableDefinition table : tables) {
            String normalizedTableName = normalize(table.getTableName());
            byName.put(normalizedTableName, table);
            canonicalToActualKey.putIfAbsent(toCanonicalKey(normalizedTableName), normalizedTableName);
        }

        Map<String, Set<String>> childrenByParent = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String tableKey : byName.keySet()) {
            childrenByParent.put(tableKey, new LinkedHashSet<>());
            inDegree.put(tableKey, 0);
        }

        for (TableDefinition table : tables) {
            String childKey = normalize(table.getTableName());
            for (ForeignKeyDefinition fk : table.getForeignKeys()) {
                String parentKey = resolveParentKey(fk.getTargetTableName(), byName, canonicalToActualKey);
                if (parentKey.isEmpty() || parentKey.equals(childKey) || !byName.containsKey(parentKey)) {
                    continue;
                }

                Set<String> children = childrenByParent.computeIfAbsent(parentKey, k -> new HashSet<>());
                if (children.add(childKey)) {
                    inDegree.put(childKey, inDegree.getOrDefault(childKey, 0) + 1);
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (TableDefinition table : tables) {
            String key = normalize(table.getTableName());
            if (inDegree.getOrDefault(key, 0) == 0) {
                queue.offer(key);
            }
        }

        List<TableDefinition> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }

            TableDefinition currentTable = byName.get(current);
            if (currentTable != null) {
                sorted.add(currentTable);
            }

            for (String child : childrenByParent.getOrDefault(current, Set.of())) {
                int next = inDegree.getOrDefault(child, 0) - 1;
                inDegree.put(child, next);
                if (next == 0) {
                    queue.offer(child);
                }
            }
        }

        // Circular dependencies fallback: append remaining tables in original order.
        if (sorted.size() < tables.size()) {
            for (TableDefinition table : tables) {
                String key = normalize(table.getTableName());
                if (visited.add(key)) {
                    sorted.add(table);
                }
            }
        }

        return sorted;
    }

    private static String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }

        String normalized = tableName.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        // Unify quoted identifiers from different dialects.
        normalized = normalized
                .replace("\"", "")
                .replace("`", "")
                .replace("[", "")
                .replace("]", "")
                .trim();

        return normalized.toUpperCase(Locale.ROOT);
    }

    private static String toCanonicalKey(String tableName) {
        String normalized = normalize(tableName);
        if (normalized.isEmpty()) {
            return "";
        }

        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static String resolveParentKey(
            String rawParentTableName,
            Map<String, TableDefinition> byName,
            Map<String, String> canonicalToActualKey
    ) {
        String normalizedParent = normalize(rawParentTableName);
        if (normalizedParent.isEmpty()) {
            return "";
        }

        if (byName.containsKey(normalizedParent)) {
            return normalizedParent;
        }

        String canonicalParent = toCanonicalKey(normalizedParent);
        return canonicalToActualKey.getOrDefault(canonicalParent, "");
    }
}