package org.example.migrationdbweb.migratetool;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MetadataExtractor {
    public List<String> getTableNames(Connection conn, String schema) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tableNames.add(rs.getString("TABLE_NAME"));
            }
        }
        return tableNames;

    }

    public TableDefinition extractTableDefinition(Connection conn, String schema, String tableName) throws SQLException {
        TableDefinition tableDef = new TableDefinition(tableName);
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rsColumns = metaData.getColumns(null, schema, tableName, "%")) {
            while (rsColumns.next()) {
                String colName = rsColumns.getString("COLUMN_NAME");
                int dataType = rsColumns.getInt("DATA_TYPE");
                String typeName = rsColumns.getString("TYPE_NAME");
                int size = rsColumns.getInt("COLUMN_SIZE");
                int scale = rsColumns.getInt("DECIMAL_DIGITS");
                int nullable = rsColumns.getInt("NULLABLE");
                String isAutoIncStr = rsColumns.getString("IS_AUTOINCREMENT");
                boolean isNullable = (nullable == 1);
                boolean isAutoIncrement = "YES".equalsIgnoreCase(isAutoIncStr);
                ColumnDefinition colDef = new ColumnDefinition(
                        colName,
                        dataType,
                        typeName,
                        size,
                        scale,
                        isNullable,
                        isAutoIncrement
                );
                tableDef.addColumn(colDef);
            }
        }

        try (ResultSet rsPK = metaData.getPrimaryKeys(null, schema, tableName)) {
            while (rsPK.next()) {
                String pkColumnName = rsPK.getString("COLUMN_NAME");
                tableDef.addPrimaryKey(pkColumnName);
            }
        }

        try (ResultSet rsFK =  metaData.getImportedKeys(null, schema, tableName)) {
            while (rsFK.next()) {
                String fkName = rsFK.getString("FK_NAME");
                String fkColumnName = rsFK.getString("FKCOLUMN_NAME");
                String targetTableName = rsFK.getString("PKTABLE_NAME");
                String targetColumnName = rsFK.getString("PKCOLUMN_NAME");
                ForeignKeyDefinition fkDef = new ForeignKeyDefinition(fkName, fkColumnName, targetTableName, targetColumnName);
                tableDef.addForeignKey(fkDef);

            }
        }
        return tableDef;
    }

    // ─────────────────────────────────────────────────────────────
    // VIEW METADATA METHODS
    // ─────────────────────────────────────────────────────────────

    /**
     * Lấy danh sách tên tất cả VIEW trong schema.
     * Giống getTableNames() nhưng filter "VIEW" thay vì "TABLE".
     */
    public List<String> getViewNames(Connection conn, String schema) throws SQLException {
        List<String> viewNames = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"VIEW"})) {
            while (rs.next()) {
                viewNames.add(rs.getString("TABLE_NAME"));
            }
        }
        return viewNames;
    }

    /**
     * Trích xuất định nghĩa một VIEW — tạo ViewDefinition.
     * Giống extractTableDefinition() — đọc metadata + build POJO.
     *
     * <p>Postgres:  lấy SELECT clause từ pg_get_viewdef(oid, true)
     * <p>Oracle:    lấy TEXT từ USER_VIEWS (đã có CREATE OR REPLACE VIEW ...)
     */
    public ViewDefinition extractViewDefinition(
            Connection conn,
            String schema,
            String viewName,
            DatabaseType dbType
    ) throws SQLException {
        String selectClause = null;
        String checkOption = null;

        if (dbType == DatabaseType.ORACLE) {
            // Oracle USER_VIEWS.TEXT chứa "CREATE [OR REPLACE] ... VIEW ... AS SELECT ..."
            String sql = "SELECT TEXT, TEXT_LENGTH FROM USER_VIEWS WHERE VIEW_NAME = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, viewName.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        selectClause = rs.getString("TEXT");
                    }
                }
            }
        } else if (dbType == DatabaseType.POSTGRESQL) {
            // Postgres: pg_get_viewdef trả về SELECT clause thuần (không có CREATE VIEW)
            String sql = """
                SELECT pg_get_viewdef(v.oid, true) AS viewdef
                FROM pg_views v
                WHERE v.viewname = ? AND v.schemaname = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, viewName);
                ps.setString(2, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        selectClause = rs.getString("viewdef");
                    }
                }
            }
            // Postgres hỗ trợ check option — lấy từ pg_views
            String checkSql = """
                SELECT check_option
                FROM pg_views
                WHERE viewname = ? AND schemaname = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, viewName);
                ps.setString(2, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String co = rs.getString("check_option");
                        if (co != null && !co.equalsIgnoreCase("NONE")) {
                            checkOption = co;
                        }
                    }
                }
            }
        }

        if (selectClause == null || selectClause.isBlank()) {
            return null;
        }

        return ViewDefinition.builder()
                .viewName(viewName)
                .schema(schema)
                .sourceDialect(dbType)
                .selectClause(selectClause.trim())
                .checkOption(checkOption)
                .sourceSchema(schema)
                .build();
    }

    /**
     * Lấy dependency map của views — view nào phụ thuộc view nào.
     * Dùng cho topological sort.
     *
     * @return Map: viewName -> Set các view mà nó phụ thuộc
     */
    public Map<String, Set<String>> getViewDependencyMap(Connection conn, String schema, DatabaseType dbType)
            throws SQLException {
        if (dbType == DatabaseType.POSTGRESQL) {
            return getViewDependencyMapPg(conn, schema);
        } else if (dbType == DatabaseType.ORACLE) {
            return getViewDependencyMapOracle(conn, schema);
        }
        return Collections.emptyMap();
    }

    /**
     * PostgreSQL: lấy view dependencies từ pg_depends.
     * pg_depends lưu: object A phụ thuộc object B (refobjid).
     * Lọc chỉ giữ lại dependency là VIEW.
     */
    private Map<String, Set<String>> getViewDependencyMapPg(Connection conn, String schema) throws SQLException {
        Map<String, Set<String>> dependencyMap = new HashMap<>();

        String sql = """
            SELECT DISTINCT
                dependent.relname    AS view_name,
                dependency.relname   AS depends_on
            FROM pg_depends d
            JOIN pg_class dependent ON d.refobjid = dependent.oid
            JOIN pg_class dependency ON d.depobjid = dependency.oid
            JOIN pg_namespace dep_ns ON dependent.relnamespace = dep_ns.oid
            JOIN pg_namespace ns    ON dependency.relnamespace = ns.oid
            WHERE dep_ns.nspname = ?
              AND dependent.relkind = 'v'
              AND dependency.relkind = 'v'
              AND dependent.relname != dependency.relname
            ORDER BY dependent.relname, dependency.relname
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String viewName = rs.getString("view_name");
                    String dependsOn = rs.getString("depends_on");
                    dependencyMap
                            .computeIfAbsent(viewName, k -> new HashSet<>())
                            .add(dependsOn);
                }
            }
        }
        return dependencyMap;
    }

    /**
     * Oracle: lấy view dependencies từ USER_DEPENDENCIES.
     * Lọc chỉ giữ lại dependency là VIEW (bỏ qua TABLE).
     */
    private Map<String, Set<String>> getViewDependencyMapOracle(Connection conn, String schema) throws SQLException {
        Map<String, Set<String>> dependencyMap = new HashMap<>();

        // Lấy tất cả view name trong schema trước để filter
        Set<String> viewNames = new HashSet<>(getViewNames(conn, schema));

        String sql = """
            SELECT NAME, REFERENCED_NAME
            FROM USER_DEPENDENCIES
            WHERE TYPE IN ('VIEW', 'SYNONYM')
              AND REFERENCED_NAME IS NOT NULL
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String viewName = rs.getString("NAME");
                    String referenced = rs.getString("REFERENCED_NAME");

                    // Chỉ giữ dependency nào referenced cũng là VIEW trong schema
                    if (viewNames.contains(referenced.toUpperCase())
                            && !viewName.equalsIgnoreCase(referenced)) {
                        dependencyMap
                                .computeIfAbsent(viewName.toUpperCase(), k -> new HashSet<>())
                                .add(referenced.toUpperCase());
                    }
                }
            }
        }
        return dependencyMap;
    }

    /**
     * Trích xuất toàn bộ ViewDefinition + topological sort theo dependency.
     * Gọi method này thay vì gọi riêng lẻ extractViewDefinition + getViewDependencyMap.
     *
     * View không phụ thuộc view nào (in-degree = 0) sẽ đứng trước.
     */
    public List<ViewDefinition> extractViewDefinitionsWithDependencySort(
            Connection conn,
            String schema,
            DatabaseType dbType
    ) throws SQLException {
        // 1. Lấy danh sách view
        List<String> viewNames = getViewNames(conn, schema);
        if (viewNames.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Extract definition cho từng view
        List<ViewDefinition> allViews = new ArrayList<>();
        for (String viewName : viewNames) {
            ViewDefinition vd = extractViewDefinition(conn, schema, viewName, dbType);
            if (vd != null) {
                allViews.add(vd);
            }
        }

        // 3. Lấy dependency map
        Map<String, Set<String>> dependencyMap = getViewDependencyMap(conn, schema, dbType);

        // 4. Topological sort
        return TopologicalSortUtil.sortByDependency(allViews, dependencyMap);
    }

}
