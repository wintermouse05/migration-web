package org.example.migrationdbweb.migratetool;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MetadataExtractor {
    public List<String> getTableNames(Connection conn, String schema) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();

        if (schema == null || schema.isBlank()) {
            System.err.println(
                    "WARNING: schema is null/blank in getTableNames(). " +
                    "This will return ALL tables from ALL schemas, " +
                    "including system tables. " +
                    "Caller should provide a valid schema name."
            );
        }

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
            // Oracle USER_VIEWS.TEXT chứa "CREATE [OR REPLACE] [NO] FORCE] [EDITIONING|...] VIEW ... AS subquery [WITH {CASCADED|LOCAL} CHECK OPTION] [WITH READ ONLY]"
            // Cần parse để tách SELECT clause và checkOption ra khỏi prefix/suffix
            String sql = "SELECT TEXT, TEXT_LENGTH FROM USER_VIEWS WHERE VIEW_NAME = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, viewName.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String fullText = rs.getString("TEXT");
                        ParseOracleViewResult parsed = parseOracleViewText(fullText);
                        selectClause = parsed.selectClause;
                        checkOption = parsed.checkOption;
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

    // ─────────────────────────────────────────────────────────────
    // SEQUENCE METADATA METHODS
    // ─────────────────────────────────────────────────────────────

    /**
     * Lấy danh sách tên tất cả SEQUENCE trong schema.
     */
    public List<String> getSequenceNames(Connection conn, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            String sql = """
                SELECT sequence_name
                FROM information_schema.sequences
                WHERE sequence_schema = ?
                ORDER BY sequence_name
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("sequence_name"));
                    }
                }
            }
        } else {
            // Oracle
            String sql = """
                SELECT SEQUENCE_NAME
                FROM USER_SEQUENCES
                ORDER BY SEQUENCE_NAME
                """;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    names.add(rs.getString("SEQUENCE_NAME"));
                }
            }
        }
        return names;
    }

    /**
     * Trích xuất định nghĩa một SEQUENCE.
     */
    public SequenceDefinition extractSequenceDefinition(
            Connection conn, String schema, String sequenceName, DatabaseType dbType
    ) throws SQLException {
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return extractSequenceDefinitionPg(conn, schema, sequenceName);
        } else {
            return extractSequenceDefinitionOracle(conn, schema, sequenceName);
        }
    }

    private SequenceDefinition extractSequenceDefinitionPg(
            Connection conn, String schema, String sequenceName
    ) throws SQLException {
        String sql = """
            SELECT
                sequence_name,
                start_value,
                minimum_value,
                maximum_value,
                increment,
                cycle_option,
                cache_size
            FROM information_schema.sequences
            WHERE sequence_schema = ? AND sequence_name = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, sequenceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return SequenceDefinition.builder()
                            .sequenceName(rs.getString("sequence_name"))
                            .schema(schema)
                            .sourceDialect(DatabaseType.POSTGRESQL)
                            .startValue(rs.getLong("start_value"))
                            .incrementBy(rs.getLong("increment"))
                            .minValue(toNonNullLong(rs, "minimum_value"))
                            .maxValue(toNonNullLong(rs, "maximum_value"))
                            .cacheSize(toNonNullLong(rs, "cache_size"))
                            .cycle("YES".equalsIgnoreCase(rs.getString("cycle_option")))
                            .sourceSchema(schema)
                            .build();
                }
            }
        }
        return null;
    }

    private SequenceDefinition extractSequenceDefinitionOracle(
            Connection conn, String schema, String sequenceName
    ) throws SQLException {
        // USER_SEQUENCES không có START_VALUE trực tiếp — lấy từ LAST_NUMBER
        String sql = """
            SELECT
                SEQUENCE_NAME,
                MIN_VALUE,
                MAX_VALUE,
                INCREMENT_BY,
                CYCLE_FLAG,
                CACHE_SIZE,
                LAST_NUMBER
            FROM USER_SEQUENCES
            WHERE SEQUENCE_NAME = UPPER(?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sequenceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lastNumber = rs.getLong("LAST_NUMBER");
                    long incrementBy = rs.getLong("INCREMENT_BY");
                    // Oracle START WITH = LAST_NUMBER (giá trị cuối cùng đã generate)
                    long startValue = Math.max(1, lastNumber - incrementBy + 1);
                    return SequenceDefinition.builder()
                            .sequenceName(rs.getString("SEQUENCE_NAME"))
                            .schema(schema)
                            .sourceDialect(DatabaseType.ORACLE)
                            .startValue(startValue)
                            .incrementBy(incrementBy)
                            .minValue(toNonNullLong(rs, "MIN_VALUE"))
                            .maxValue(toNonNullLong(rs, "MAX_VALUE"))
                            .cacheSize(toNonNullLong(rs, "CACHE_SIZE"))
                            .cycle("Y".equalsIgnoreCase(rs.getString("CYCLE_FLAG")))
                            .lastNumber(lastNumber)
                            .sourceSchema(schema)
                            .build();
                }
            }
        }
        return null;
    }

    private static Long toNonNullLong(ResultSet rs, String column) throws SQLException {
        long val = rs.getLong(column);
        return rs.wasNull() ? null : val;
    }

    // ─────────────────────────────────────────────────────────────
    // INDEX METADATA METHODS
    // ─────────────────────────────────────────────────────────────

    /**
     * Lấy danh sách tên INDEX cho một bảng (hoặc tất cả nếu tableName = null).
     */
    public List<String> getIndexNames(Connection conn, String schema, String tableName) throws SQLException {
        List<String> names = new ArrayList<>();
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            String sql = tableName == null
                    ? "SELECT indexname FROM pg_indexes WHERE schemaname = ? ORDER BY indexname"
                    : "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = ? ORDER BY indexname";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                if (tableName != null) ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String idxName = rs.getString("indexname");
                        // Bỏ qua index hệ thống của PostgreSQL (auto-created by pg_constraint)
                        if (!idxName.startsWith("pg_") && !idxName.startsWith("sql_")) {
                            names.add(idxName);
                        }
                    }
                }
            }
        } else {
            // Oracle: USER_INDEXES
            String sql = tableName == null
                    ? "SELECT INDEX_NAME FROM USER_INDEXES ORDER BY INDEX_NAME"
                    : "SELECT INDEX_NAME FROM USER_INDEXES WHERE TABLE_NAME = UPPER(?) ORDER BY INDEX_NAME";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (tableName != null) ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("INDEX_NAME"));
                    }
                }
            }
        }
        return names;
    }

    /**
     * Trích xuất định nghĩa một INDEX.
     */
    public IndexDefinition extractIndexDefinition(
            Connection conn, String schema, String indexName, DatabaseType dbType
    ) throws SQLException {
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return extractIndexDefinitionPg(conn, schema, indexName);
        } else {
            return extractIndexDefinitionOracle(conn, schema, indexName);
        }
    }

    private IndexDefinition extractIndexDefinitionPg(
            Connection conn, String schema, String indexName
    ) throws SQLException {
        // PostgreSQL: lấy index definition từ pg_indexes và phân tích
        // FIX: pg_index.indrelid là OID FK tới pg_class, KHÔNG phải text.
        // JOIN đúng: pg_class.relname = pg_indexes.tablename, rồi dùng pg_index.indrelid = pg_class.oid
        String sql = """
            SELECT
                i.indexname,
                i.tablename,
                i.indexdef,
                x.indisunique,
                x.indisprimary
            FROM pg_indexes i
            JOIN pg_class c ON c.relname = i.tablename
            JOIN pg_namespace n ON n.nspname = i.schemaname AND n.oid = c.relnamespace
            JOIN pg_index x ON x.indrelid = c.oid AND x.indexname = i.indexname
            WHERE i.schemaname = ? AND i.indexname = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String indexDef = rs.getString("indexdef"); // ví dụ: "CREATE UNIQUE INDEX ..."
                    String tableName = rs.getString("tablename");
                    boolean unique = rs.getBoolean("indisunique");
                    boolean primary = rs.getBoolean("indisprimary");

                    // Phân tích indexdef để lấy columns và type
                    List<String> columns = parseColumnsFromPgIndexDef(indexDef);
                    String expr = parseExpressionFromPgIndexDef(indexDef);
                    String whereClause = parseWhereFromPgIndexDef(indexDef);
                    IndexDefinition.IndexType type = parseIndexTypeFromPgIndexDef(indexDef);

                    // System index nếu là PK index (pg_toast, v.v.)
                    boolean system = indexName.startsWith("pg_")
                            || indexName.startsWith("sql_")
                            || primary;

                    return IndexDefinition.builder()
                            .indexName(indexName)
                            .schema(schema)
                            .tableName(tableName)
                            .columns(columns)
                            .unique(unique)
                            .indexType(type)
                            .expression(expr)
                            .whereClause(whereClause)
                            .indexTypeName(type.name())
                            .sourceDialect(DatabaseType.POSTGRESQL)
                            .sourceSchema(schema)
                            .systemIndex(system)
                            .build();
                }
            }
        }
        return null;
    }

    private IndexDefinition extractIndexDefinitionOracle(
            Connection conn, String schema, String indexName
    ) throws SQLException {
        // Oracle: lấy từ USER_INDEXES + USER_IND_COLUMNS
        String idxSql = """
            SELECT
                i.INDEX_NAME,
                i.TABLE_NAME,
                i.UNIQUENESS,
                i.INDEX_TYPE,
                i.TABLESPACE_NAME,
                i.VISIBILITY,
                i.ITYP_NAME
            FROM USER_INDEXES i
            WHERE i.INDEX_NAME = UPPER(?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(idxSql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                String tableName = rs.getString("TABLE_NAME");
                String uniqueness = rs.getString("UNIQUENESS");
                String idxType = rs.getString("INDEX_TYPE");
                String tablespace = rs.getString("TABLESPACE_NAME");
                String itypName = rs.getString("ITYP_NAME");

                // Lấy columns
                List<String> columns = new ArrayList<>();
                List<String> descendings = new ArrayList<>();
                String colSql = """
                    SELECT COLUMN_NAME, DESCEND
                    FROM USER_IND_COLUMNS
                    WHERE INDEX_NAME = UPPER(?) AND TABLE_NAME = UPPER(?)
                    ORDER BY COLUMN_POSITION
                    """;
                try (PreparedStatement psCol = conn.prepareStatement(colSql)) {
                    psCol.setString(1, indexName);
                    psCol.setString(2, tableName);
                    try (ResultSet rsCol = psCol.executeQuery()) {
                        while (rsCol.next()) {
                            columns.add(rsCol.getString("COLUMN_NAME"));
                            descendings.add(rsCol.getString("DESCEND"));
                        }
                    }
                }

                // Lấy index expression (cho function-based index)
                String expression = null;
                String whereClause = null;
                String exprSql = """
                    SELECT COLUMN_EXPRESSION
                    FROM USER_USED_PARTITIONS
                    WHERE INDEX_NAME = UPPER(?) AND TABLE_NAME = UPPER(?)
                    """;
                // Function-based index: expression nằm trong USER_EXpressions hoặc phân tích index_type
                if ("FUNCTION-BASED NORMAL".equals(idxType) || idxType != null && idxType.contains("FUNCTION")) {
                    // Try to get expression from a different approach
                    expression = extractFunctionBasedIndexExpression(conn, indexName);
                }

                IndexDefinition.IndexType type = mapOracleIndexType(idxType, itypName);

                // System index: thường do FK hoặc unique constraint tạo ra
                boolean system = uniqueness != null && !"UNIQUE".equalsIgnoreCase(uniqueness)
                        && tableName != null && !columns.isEmpty()
                        && (indexName.startsWith("BIN$") || indexName.startsWith("DR$")
                            || indexName.contains("$"));

                return IndexDefinition.builder()
                        .indexName(rs.getString("INDEX_NAME"))
                        .schema(schema)
                        .tableName(tableName)
                        .columns(columns)
                        .descendings(descendings)
                        .unique("UNIQUE".equalsIgnoreCase(uniqueness))
                        .indexType(type)
                        .tablespace(tablespace)
                        .expression(expression)
                        .whereClause(whereClause)
                        .indexTypeName(idxType)
                        .sourceDialect(DatabaseType.ORACLE)
                        .sourceSchema(schema)
                        .systemIndex(system)
                        .build();
            }
        }
    }

    private String extractFunctionBasedIndexExpression(
            Connection conn, String indexName
    ) throws SQLException {
        // Oracle function-based index: lấy expression từ USER_EXpressions
        String sql = """
            SELECT COLUMN_EXPRESSION
            FROM USER_EXPRESSIONS
            WHERE INDEX_NAME = UPPER(?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("COLUMN_EXPRESSION");
                }
            }
        }
        return null;
    }

    private List<String> parseColumnsFromPgIndexDef(String indexDef) {
        List<String> cols = new ArrayList<>();
        if (indexDef == null) return cols;

        // indexdef format: "CREATE [UNIQUE] INDEX idx ON tbl USING type (col [, col] [ASC|DESC] [, ...]) [WHERE ...]"
        // Tìm phần trong ngoặc đơn
        int parenStart = indexDef.indexOf('(');
        int parenEnd = indexDef.lastIndexOf(')');
        if (parenStart < 0 || parenEnd < 0) return cols;

        String inner = indexDef.substring(parenStart + 1, parenEnd);
        // Tách các phần tử (bỏ ASC/DESC và USING type)
        String[] parts = inner.split(",");
        for (String p : parts) {
            p = p.trim();
            // Bỏ qua các từ khóa
            if (p.equalsIgnoreCase("USING")
                    || p.equalsIgnoreCase("ASC") || p.equalsIgnoreCase("DESC")
                    || p.isEmpty()) continue;
            // Bỏ expression trong parentheses (function-based)
            if (p.startsWith("(") && p.endsWith(")")) {
                // Đây là expression, lưu lại ở field riêng
                continue;
            }
            // Bỏ qua các qualifier (class name cho operator class)
            if (p.contains("(")) {
                int lp = p.indexOf('(');
                cols.add(p.substring(0, lp).trim());
            } else {
                cols.add(p.split("\\s")[0].trim());
            }
        }
        return cols;
    }

    private String parseExpressionFromPgIndexDef(String indexDef) {
        if (indexDef == null) return null;
        // Function-based: expression trong ngoặc đơn với dấu ngoặc đơn
        int parenStart = indexDef.indexOf('(');
        int parenEnd = indexDef.lastIndexOf(')');
        if (parenStart < 0 || parenEnd < 0) return null;
        String inner = indexDef.substring(parenStart + 1, parenEnd);
        // Nếu inner chứa dấu ngoặc → là expression
        if (inner.contains("(") && !inner.contains(",")) {
            return inner;
        }
        return null;
    }

    private String parseWhereFromPgIndexDef(String indexDef) {
        if (indexDef == null || !indexDef.contains("WHERE")) return null;
        int whereIdx = indexDef.indexOf("WHERE");
        return indexDef.substring(whereIdx + 5).trim();
    }

    private IndexDefinition.IndexType parseIndexTypeFromPgIndexDef(String indexDef) {
        if (indexDef == null) return IndexDefinition.IndexType.UNKNOWN;
        String upper = indexDef.toUpperCase(Locale.ROOT);
        if (upper.contains("USING GIN")) return IndexDefinition.IndexType.GIN;
        if (upper.contains("USING GIST")) return IndexDefinition.IndexType.GIST;
        if (upper.contains("USING BRIN")) return IndexDefinition.IndexType.BRIN;
        if (upper.contains("USING HASH")) return IndexDefinition.IndexType.HASH;
        if (upper.contains("USING BTREE") || upper.contains("CREATE INDEX")) {
            return IndexDefinition.IndexType.BTREE;
        }
        return IndexDefinition.IndexType.UNKNOWN;
    }

    private IndexDefinition.IndexType mapOracleIndexType(String indexType, String itypName) {
        if (indexType == null) return IndexDefinition.IndexType.UNKNOWN;
        String upper = indexType.toUpperCase(Locale.ROOT);
        if (upper.contains("BITMAP")) return IndexDefinition.IndexType.BITMAP;
        if (upper.contains("FUNCTION")) return IndexDefinition.IndexType.FUNCTION;
        if (upper.contains("DOMAIN")) return IndexDefinition.IndexType.DOMAIN;
        if (upper.contains("CLUSTER")) return IndexDefinition.IndexType.BTREE;
        if (upper.contains("NORMAL")) {
            // itypName cho biết loại cụ thể
            if (itypName != null) {
                if ("CTXRULE".equalsIgnoreCase(itypName)) return IndexDefinition.IndexType.DOMAIN;
                if ("CONTEXT".equalsIgnoreCase(itypName)) return IndexDefinition.IndexType.DOMAIN;
            }
            return IndexDefinition.IndexType.BTREE;
        }
        return IndexDefinition.IndexType.UNKNOWN;
    }

    // ─────────────────────────────────────────────────────────────
    // TRIGGER METADATA METHODS
    // ─────────────────────────────────────────────────────────────

    /**
     * Lấy danh sách tên tất cả TRIGGER trong schema.
     */
    public List<String> getTriggerNames(Connection conn, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            String sql = """
                SELECT t.tgname AS trigger_name
                FROM pg_trigger t
                JOIN pg_proc p ON t.tgfoid = p.oid
                JOIN pg_namespace n ON t.tgnamespace = n.oid
                WHERE n.nspname = ?
                  AND NOT t.tgisinternal
                  AND NOT t.tginternal
                ORDER BY t.tgname
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("trigger_name"));
                    }
                }
            }
        } else {
            // Oracle: USER_TRIGGERS
            String sql = """
                SELECT TRIGGER_NAME
                FROM USER_TRIGGERS
                WHERE STATUS = 'ENABLED'
                ORDER BY TRIGGER_NAME
                """;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    names.add(rs.getString("TRIGGER_NAME"));
                }
            }
        }
        return names;
    }

    /**
     * Trích xuất định nghĩa một TRIGGER.
     */
    public TriggerDefinition extractTriggerDefinition(
            Connection conn, String schema, String triggerName, DatabaseType dbType
    ) throws SQLException {
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return extractTriggerDefinitionPg(conn, schema, triggerName);
        } else {
            return extractTriggerDefinitionOracle(conn, schema, triggerName);
        }
    }

    private TriggerDefinition extractTriggerDefinitionPg(
            Connection conn, String schema, String triggerName
    ) throws SQLException {
        String sql = """
            SELECT
                t.tgname AS trigger_name,
                c.relname AS table_name,
                n.nspname AS schema_name,
                p.proname AS function_name,
                p.prosrc AS function_body,
                t.tgtype,
                CASE WHEN (t.tgtype & 1) = 1 THEN 'ROW' ELSE 'STATEMENT' END AS level,
                CASE WHEN (t.tgtype & 2) = 2 THEN 'BEFORE' ELSE 'AFTER' END AS timing,
                CASE
                    WHEN (t.tgtype & 4) = 4 AND (t.tgtype & 8) = 8 AND (t.tgtype & 16) = 16
                        THEN 'INSERT OR UPDATE OR DELETE'
                    WHEN (t.tgtype & 4) = 4 AND (t.tgtype & 8) = 8
                        THEN 'INSERT OR UPDATE'
                    WHEN (t.tgtype & 4) = 4 AND (t.tgtype & 16) = 16
                        THEN 'INSERT OR DELETE'
                    WHEN (t.tgtype & 8) = 8 AND (t.tgtype & 16) = 16
                        THEN 'UPDATE OR DELETE'
                    WHEN (t.tgtype & 4) = 4 THEN 'INSERT'
                    WHEN (t.tgtype & 8) = 8 THEN 'UPDATE'
                    WHEN (t.tgtype & 16) = 16 THEN 'DELETE'
                    ELSE 'UNKNOWN'
                END AS events,
                t.tgattr,
                CASE WHEN (t.tgtype & 32) = 32 THEN 'INSTEAD OF' ELSE 'STANDARD' END AS timing2
            FROM pg_trigger t
            JOIN pg_proc p ON t.tgfoid = p.oid
            JOIN pg_class c ON t.tgrelid = c.oid
            JOIN pg_namespace n ON t.tgnamespace = n.oid
            WHERE n.nspname = ? AND t.tgname = ?
              AND NOT t.tgisinternal
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, triggerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String functionName = rs.getString("function_name");
                    String functionBody = rs.getString("function_body");
                    String level = rs.getString("level");
                    String timing = rs.getString("timing");
                    // Nếu timing2 = 'INSTEAD OF' thì trigger là INSTEAD OF (trên VIEW),
                    // ngược lại timing lấy từ BEFORE/AFTER
                    String timing2 = rs.getString("timing2");
                    String events = rs.getString("events");

                    TriggerDefinition.TriggerTiming triggerTiming =
                            "INSTEAD OF".equalsIgnoreCase(timing2)
                                    ? TriggerDefinition.TriggerTiming.INSTEAD_OF
                                    : TriggerDefinition.TriggerTiming.valueOf(timing.toUpperCase());
                    TriggerDefinition.TriggerLevel triggerLevel =
                            TriggerDefinition.TriggerLevel.valueOf(level.toUpperCase());

                    return TriggerDefinition.builder()
                            .triggerName(triggerName)
                            .schema(schema)
                            .sourceDialect(DatabaseType.POSTGRESQL)
                            .tableName(tableName)
                            .timing(triggerTiming)
                            .triggeringEvent(events)
                            .level(triggerLevel)
                            .triggerBody(functionBody)
                            .functionName(functionName)
                            .enabled(true)
                            .sourceSchema(schema)
                            .build();
                }
            }
        }
        return null;
    }

    private TriggerDefinition extractTriggerDefinitionOracle(
            Connection conn, String schema, String triggerName
    ) throws SQLException {
        String sql = """
            SELECT
                TRIGGER_NAME,
                TRIGGER_TYPE,
                TRIGGERING_EVENT,
                TABLE_NAME,
                TRIGGER_BODY,
                WHEN_CLAUSE,
                DESCRIPTION,
                STATUS,
                ACTION_TYPE,
                REFERENCING_NAMES
            FROM USER_TRIGGERS
            WHERE TRIGGER_NAME = UPPER(?)
              AND STATUS = 'ENABLED'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, triggerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String triggerType = rs.getString("TRIGGER_TYPE");   // BEFORE / AFTER / INSTEAD OF
                    String triggeringEvent = rs.getString("TRIGGERING_EVENT"); // INSERT OR UPDATE ...
                    String tableName = rs.getString("TABLE_NAME");
                    String body = rs.getString("TRIGGER_BODY");
                    String whenClause = rs.getString("WHEN_CLAUSE");
                    String description = rs.getString("DESCRIPTION");
                    String status = rs.getString("STATUS");
                    String actionType = rs.getString("ACTION_TYPE");

                    // Thử lấy full DDL qua DBMS_METADATA (ưu tiên cao nhất)
                    String ddlText = null;
                    try {
                        ddlText = extractOracleTriggerDdlViaDbmsMetadata(conn, schema, triggerName);
                    } catch (Exception e) {
                        System.err.println("WARN: DBMS_METADATA unavailable for trigger "
                                + schema + "." + triggerName + ": " + e.getMessage());
                    }

                    TriggerDefinition.TriggerTiming timing;
                    if ("INSTEAD OF".equalsIgnoreCase(triggerType)) {
                        timing = TriggerDefinition.TriggerTiming.INSTEAD_OF;
                    } else {
                        timing = TriggerDefinition.TriggerTiming.valueOf(triggerType.toUpperCase().split("\\s")[0]);
                    }

                    TriggerDefinition.TriggerLevel level = TriggerDefinition.TriggerLevel.ROW; // Oracle triggers mặc định ROW

                    return TriggerDefinition.builder()
                            .triggerName(rs.getString("TRIGGER_NAME"))
                            .schema(schema)
                            .sourceDialect(DatabaseType.ORACLE)
                            .tableName(tableName)
                            .timing(timing)
                            .triggeringEvent(triggeringEvent)
                            .level(level)
                            .whenClause(whenClause)
                            .triggerBody(body)
                            .description(description)
                            .enabled("ENABLED".equalsIgnoreCase(status))
                            .actionType(actionType)
                            .functionName(triggerName + "_func")
                            .sourceSchema(schema)
                            .ddlText(ddlText)
                            .build();
                }
            }
        }
        return null;
    }

    /**
     * Lấy danh sách trigger cho một bảng cụ thể.
     */
    public List<String> getTriggerNamesForTable(Connection conn, String schema, String tableName) throws SQLException {
        List<String> names = new ArrayList<>();
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            String sql = """
                SELECT t.tgname
                FROM pg_trigger t
                JOIN pg_class c ON t.tgrelid = c.oid
                JOIN pg_namespace n ON t.tgnamespace = n.oid
                WHERE n.nspname = ? AND c.relname = ?
                  AND NOT t.tgisinternal
                ORDER BY t.tgname
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("tgname"));
                    }
                }
            }
        } else {
            String sql = """
                SELECT TRIGGER_NAME
                FROM USER_TRIGGERS
                WHERE TABLE_NAME = UPPER(?) AND STATUS = 'ENABLED'
                ORDER BY TRIGGER_NAME
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("TRIGGER_NAME"));
                    }
                }
            }
        }
        return names;
    }

    // ─────────────────────────────────────────────────────────────
    // FUNCTION / PROCEDURE METADATA METHODS
    // ─────────────────────────────────────────────────────────────

    public List<String> getFunctionNames(Connection conn, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            String sql = """
                SELECT p.proname AS name
                FROM pg_proc p
                JOIN pg_namespace n ON p.pronamespace = n.oid
                WHERE n.nspname = ?
                  AND p.prokind IN ('f', 'p')
                ORDER BY p.proname
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("name"));
                    }
                }
            }
        } else {
            String sql = """
                SELECT DISTINCT OBJECT_NAME AS name
                FROM USER_OBJECTS
                WHERE OBJECT_TYPE IN ('FUNCTION', 'PROCEDURE')
                  AND STATUS = 'VALID'
                ORDER BY OBJECT_NAME
                """;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    names.add(rs.getString("NAME"));
                }
            }
        }
        return names;
    }

    public FunctionDefinition extractFunctionDefinition(
            Connection conn, String schema, String functionName, DatabaseType dbType
    ) throws SQLException {
        if (conn.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return extractFunctionDefinitionPg(conn, schema, functionName);
        } else {
            return extractFunctionDefinitionOracle(conn, schema, functionName);
        }
    }

    private FunctionDefinition extractFunctionDefinitionPg(
            Connection conn, String schema, String functionName
    ) throws SQLException {
        String sql = """
            SELECT p.proname, n.nspname, p.prosrc AS body,
                   p.prokind, l.lanname AS language,
                   CASE WHEN p.prokind = 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END AS obj_type
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            JOIN pg_language l ON p.prolang = l.oid
            WHERE n.nspname = ? AND p.proname = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, functionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String objType = rs.getString("obj_type");
                    return FunctionDefinition.builder()
                            .functionName(rs.getString("proname"))
                            .schema(schema)
                            .sourceDialect(DatabaseType.POSTGRESQL)
                            .functionType(FunctionDefinition.FunctionType.valueOf(objType))
                            .language(rs.getString("language"))
                            .functionBody(rs.getString("body"))
                            .sourceSchema(schema)
                            .build();
                }
            }
        }
        return null;
    }

    private FunctionDefinition extractFunctionDefinitionOracle(
            Connection conn, String schema, String functionName
    ) throws SQLException {
        String typeSql = """
            SELECT OBJECT_TYPE FROM USER_OBJECTS
            WHERE OBJECT_NAME = UPPER(?) AND OBJECT_TYPE IN ('FUNCTION', 'PROCEDURE')
            """;
        String objectType = null;
        try (PreparedStatement ps = conn.prepareStatement(typeSql)) {
            ps.setString(1, functionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    objectType = rs.getString("OBJECT_TYPE");
                }
            }
        }
        if (objectType == null) return null;

        // Lấy body từ USER_SOURCE
        StringBuilder bodyBuilder = new StringBuilder();
        String sourceSql = "SELECT TEXT FROM USER_SOURCE WHERE NAME = UPPER(?) AND TYPE = ? ORDER BY LINE";
        try (PreparedStatement ps = conn.prepareStatement(sourceSql)) {
            ps.setString(1, functionName);
            ps.setString(2, objectType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bodyBuilder.append(rs.getString("TEXT"));
                }
            }
        }

        // Lấy arguments và return type từ USER_ARGUMENTS
        ArgumentsAndReturnType argsAndReturn = extractOracleFunctionArgumentsAndReturn(conn, functionName);

        FunctionDefinition.FunctionType fType =
                "FUNCTION".equalsIgnoreCase(objectType)
                        ? FunctionDefinition.FunctionType.FUNCTION
                        : FunctionDefinition.FunctionType.PROCEDURE;

        return FunctionDefinition.builder()
                .functionName(functionName)
                .schema(schema)
                .sourceDialect(DatabaseType.ORACLE)
                .functionType(fType)
                .language("PL/SQL")
                .functionBody(bodyBuilder.toString())
                .sourceSchema(schema)
                .arguments(argsAndReturn.arguments)
                .returnType(argsAndReturn.returnType)
                .build();
    }

    /**
     * Trích xuất danh sách tham số VÀ return type của một function Oracle từ USER_ARGUMENTS.
     *
     * Trong USER_ARGUMENTS, khi ARGUMENT_NAME là NULL → đó là row chứa return type.
     * Khi DATA_TYPE là NULL → đó là PROCEDURE (không có return).
     */
    private ArgumentsAndReturnType extractOracleFunctionArgumentsAndReturn(
            Connection conn, String functionName
    ) throws SQLException {
        List<FunctionDefinition.FunctionArgument> args = new ArrayList<>();
        String returnType = null;
        String sql = """
            SELECT
                ARGUMENT_NAME,
                DATA_TYPE,
                IN_OUT,
                DATA_LENGTH,
                DATA_PRECISION,
                DATA_SCALE,
                DEFAULTED,
                SEQUENCE
            FROM USER_ARGUMENTS
            WHERE OBJECT_NAME = UPPER(?)
            ORDER BY SEQUENCE
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, functionName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String argName = rs.getString("ARGUMENT_NAME");
                    String dataType = rs.getString("DATA_TYPE");

                    // ARGUMENT_NAME IS NULL → đây là return type row (FUNCTION, không phải PROCEDURE)
                    if (argName == null) {
                        returnType = dataType; // DATA_TYPE của return row chính là kiểu trả về
                        continue;
                    }

                    String inOut = rs.getString("IN_OUT");
                    FunctionDefinition.FunctionArgument.ArgumentMode mode;
                    if ("OUT".equalsIgnoreCase(inOut)) {
                        mode = FunctionDefinition.FunctionArgument.ArgumentMode.OUT;
                    } else if ("IN OUT".equalsIgnoreCase(inOut)) {
                        mode = FunctionDefinition.FunctionArgument.ArgumentMode.INOUT;
                    } else {
                        mode = FunctionDefinition.FunctionArgument.ArgumentMode.IN;
                    }

                    FunctionDefinition.FunctionArgument arg = FunctionDefinition.FunctionArgument.builder()
                            .name(argName)
                            .dataType(dataType)
                            .mode(mode)
                            .build();
                    args.add(arg);
                }
            }
        }
        return new ArgumentsAndReturnType(args, returnType);
    }

    /** Container cho arguments + return type */
    private record ArgumentsAndReturnType(
            List<FunctionDefinition.FunctionArgument> arguments,
            String returnType
    ) {}

    // ─────────────────────────────────────────────────────────────
    // ORACLE TRIGGER DDL EXTRACTION (DBMS_METADATA)
    // ─────────────────────────────────────────────────────────────

    /**
     * Lấy full DDL của một Oracle trigger bằng DBMS_METADATA.GET_DDL.
     *
     * Đây là cách đáng tin cậy nhất để lấy câu lệnh CREATE TRIGGER hoàn chỉnh,
     * giữ nguyên mọi chi tiết cú pháp gốc (WHENEVER, REFERENCING, ...)
     *
     * @param conn        JDBC connection Oracle đang mở
     * @param schema      schema chứa trigger
     * @param triggerName tên trigger
     * @return full CREATE TRIGGER DDL, hoặc null nếu không lấy được
     */
    private String extractOracleTriggerDdlViaDbmsMetadata(
            Connection conn, String schema, String triggerName
    ) throws SQLException {
        String sql = "SELECT DBMS_METADATA.GET_DDL('TRIGGER', ?, ?) AS DDL_TEXT FROM DUAL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, triggerName);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.Clob clob = rs.getClob("DDL_TEXT");
                    if (clob != null) {
                        long length = clob.length();
                        if (length > 0 && length < 1_000_000) { // giới hạn 1MB
                            return clob.getSubString(1, (int) length).trim();
                        }
                    }
                }
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // ORACLE VIEW TEXT PARSER
    // ─────────────────────────────────────────────────────────────

    /**
     * Tách SELECT clause và WITH CHECK OPTION từ Oracle USER_VIEWS.TEXT.
     *
     * Oracle TEXT format đầy đủ:
     *   CREATE [OR REPLACE] [NO FORCE] [EDITIONING|...] VIEW name
     *   [(alias [, alias]...)]
     *   AS subquery
     *   [WITH {CASCADED|LOCAL} CHECK OPTION]
     *   [WITH READ ONLY]
     *
     * Strategy: tìm " AS " (phân cách VIEW name và SELECT), rồi tách
     * subquery và parse WITH CHECK OPTION từ cuối chuỗi.
     */
    private static ParseOracleViewResult parseOracleViewText(String fullText) {
        String checkOption = null;
        String selectClause = fullText;

        if (fullText == null || fullText.isBlank()) {
            return new ParseOracleViewResult(null, null);
        }

        String upper = fullText.toUpperCase(Locale.ROOT);

        // 1. Tìm WITH CHECK OPTION từ cuối chuỗi (chỉ có thể ở cuối)
        int checkOptIdx = -1;
        if (upper.contains("WITH CHECK OPTION")) {
            checkOptIdx = upper.lastIndexOf("WITH CHECK OPTION");
            // Có thể là WITH CASCADED CHECK OPTION hoặc WITH LOCAL CHECK OPTION
        } else if (upper.contains("WITH CASCADED CHECK OPTION")) {
            checkOptIdx = upper.lastIndexOf("WITH CASCADED CHECK OPTION");
            checkOption = "CASCADED";
        } else if (upper.contains("WITH LOCAL CHECK OPTION")) {
            checkOptIdx = upper.lastIndexOf("WITH LOCAL CHECK OPTION");
            checkOption = "LOCAL";
        }

        // Nếu có WITH CHECK OPTION đơn giản (không CASCADED/LOCAL)
        if (checkOptIdx >= 0 && checkOption == null) {
            // Lấy text trước "WITH CHECK OPTION" để xác định CASCADED/LOCAL
            String before = fullText.substring(0, checkOptIdx).toUpperCase(Locale.ROOT);
            if (before.contains("CASCADED")) {
                checkOption = "CASCADED";
            } else if (before.contains("LOCAL")) {
                checkOption = "LOCAL";
            } else {
                checkOption = "CASCADED"; // Oracle mặc định là CASCADED
            }
        }

        // 2. Cắt chuỗi con trước WITH CHECK OPTION
        if (checkOptIdx >= 0) {
            selectClause = fullText.substring(0, checkOptIdx).trim();
        }

        // 3. Bỏ WITH READ ONLY (nếu có)
        int readOnlyIdx = selectClause.toUpperCase(Locale.ROOT).lastIndexOf("WITH READ ONLY");
        if (readOnlyIdx >= 0) {
            selectClause = selectClause.substring(0, readOnlyIdx).trim();
        }

        // 4. Tìm " AS " — phân cách VIEW (...) AS SELECT
        // "AS" có thể nằm trong subquery nên dùng lastIndexOf với " VIEW " làm anchor
        int viewIdx = selectClause.toUpperCase(Locale.ROOT).indexOf(" VIEW ");
        if (viewIdx < 0) {
            viewIdx = selectClause.toUpperCase(Locale.ROOT).indexOf(" VIEW\n");
        }
        if (viewIdx < 0) {
            viewIdx = selectClause.toUpperCase(Locale.ROOT).indexOf("\" VIEW ");
        }

        int asIdx = -1;
        if (viewIdx >= 0) {
            // Tìm " AS " sau vị trí "VIEW ..."
            String afterView = selectClause.substring(viewIdx + 5); // bỏ " VIEW"
            asIdx = afterView.toUpperCase(Locale.ROOT).indexOf(" AS ");
            if (asIdx >= 0) {
                asIdx = (viewIdx + 5) + asIdx;
            }
        }

        // Fallback: tìm " AS " an toàn — bỏ qua " AS " nằm trong string literals.
        // Ví dụ: WHERE col = 'SCOTT AS SMITH' không phải là " AS " của CREATE VIEW.
        if (asIdx < 0) {
            asIdx = findAsOutsideStringLiterals(selectClause);
        }

        if (asIdx >= 0) {
            selectClause = selectClause.substring(asIdx + 4).trim();
        }

        return new ParseOracleViewResult(selectClause, checkOption);
    }

    /**
     * Tìm vị trí cuối cùng của " AS " nằm NGOÀI string literals (dấu nháy đơn).
     *
     * Ví dụ:
     *   "SELECT * FROM T WHERE name = 'SCOTT AS SMITH'" → tìm đúng " AS " của VIEW, bỏ qua trong string.
     *
     * @param text chuỗi cần tìm (ví dụ: "CREATE VIEW V1 AS SELECT ...")
     * @return vị trí index của " AS " hợp lệ, hoặc -1 nếu không tìm thấy
     */
    private static int findAsOutsideStringLiterals(String text) {
        boolean inString = false;
        int lastFound = -1;
        String upper = text.toUpperCase(Locale.ROOT);

        for (int i = 0; i <= upper.length() - 4; i++) {
            char c = text.charAt(i);

            // Toggle string literal state
            if (c == '\'') {
                // Xử lý escape: '' trong SQL = literal single quote
                if (inString && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++; // skip both single quotes (escaped)
                    continue;
                }
                inString = !inString;
                continue;
            }

            // Bỏ qua khi đang ở trong string literal
            if (inString) continue;

            // Kiểm tra " AS " (case-insensitive)
            if (upper.charAt(i) == 'A' && upper.charAt(i + 1) == 'S' && upper.charAt(i + 2) == ' '
                    && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))
                    && (i + 3 >= text.length() || !Character.isLetterOrDigit(text.charAt(i + 3)))) {
                lastFound = i;
            }
        }
        return lastFound;
    }

    /**
     * Immutable holder cho kết quả parse Oracle view text.
     */
    private static class ParseOracleViewResult {
        final String selectClause;
        final String checkOption;

        ParseOracleViewResult(String selectClause, String checkOption) {
            this.selectClause = selectClause;
            this.checkOption = checkOption;
        }
    }
}
