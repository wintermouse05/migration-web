package org.example.migrationdbweb.migratetool;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PostgresDialect implements SqlDialect {
    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return "\"\"";
        }
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String mapDataType(ColumnDefinition columnDefinition) {
        if (columnDefinition.isAutoIncrement()) {
            if (columnDefinition.getJdbcType() == Types.BIGINT || isOracleNumberType(columnDefinition)) {
                return "BIGSERIAL";
            }
            return "SERIAL";

        }

        if (isOracleNumberType(columnDefinition)) {
            return mapOracleNumberForPostgres(columnDefinition);
        }

        switch (columnDefinition.getJdbcType()) {
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.CHAR:
                return columnDefinition.getSize() > 0 ? "VARCHAR(" + columnDefinition.getSize() + ")" : "TEXT";
            case Types.INTEGER:
            case Types.TINYINT:
            case Types.SMALLINT:
                return "INTEGER";
            case Types.BIGINT:
                return "BIGINT";
            case Types.DECIMAL:
            case Types.NUMERIC:
                if (columnDefinition.getScale() > 0 && columnDefinition.getSize() > 0) {
                    return "NUMERIC(" + columnDefinition.getSize() + ", " + columnDefinition.getScale() + ")";
                }
                return "NUMERIC";
            case Types.DOUBLE:
            case Types.FLOAT:
                return "DOUBLE PRECISION";
            case Types.BOOLEAN:
            case Types.BIT:
                return "BOOLEAN";
            case Types.DATE:
                return "DATE";
            case Types.TIMESTAMP:
                return "TIMESTAMP";
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
                return "BYTEA";
            case Types.CLOB:
                return "TEXT";
            default:
                // Fallback tạm thời
                return "VARCHAR(255)";
        }
    }

    private static boolean isOracleNumberType(ColumnDefinition columnDefinition) {
        String typeName = columnDefinition.getTypeName();
        return typeName != null && typeName.toUpperCase(Locale.ROOT).startsWith("NUMBER");
    }

    private static String mapOracleNumberForPostgres(ColumnDefinition columnDefinition) {
        int precision = columnDefinition.getSize();
        int scale = columnDefinition.getScale();

        if (scale > 0) {
            if (precision > 0) {
                return "NUMERIC(" + precision + ", " + scale + ")";
            }
            return "NUMERIC";
        }

        if (precision <= 0) {
            return "BIGINT";
        }

        if (precision > 0 && precision <= 9) {
            return "INTEGER";
        }

        // Oracle NUMBER không khai báo precision thường được JDBC report là 22.
        // Với cột khóa/ID thực tế thường nằm trong phạm vi BIGINT nên ưu tiên BIGINT đềEđồng bềEFK.
        if ((precision > 9 && precision <= 18) || precision == 22) {
            return "BIGINT";
        }

        if (precision > 0) {
            return "NUMERIC(" + precision + ")";
        }

        return "NUMERIC";
    }

    @Override
    public String buildCreateTableSql(TableDefinition table) {
        if (table != null
                && table.getSourceDialect() == DatabaseType.POSTGRESQL
                && table.getDdlText() != null
                && !table.getDdlText().isBlank()) {
            String ddl = remapSchemaPrefix(table.getDdlText(), table.getSourceSchema(), table.getTargetSchema());
            return stripTrailingSemicolon(ddl);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(quoteIdentifier(table.getTableName())).append(" (\n");

        List<ColumnDefinition> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition col = columns.get(i);

            sql.append("    ").append(quoteIdentifier(col.getName())).append(" ");
            sql.append(mapDataType(col));

            if (!col.isNullable()) {
                sql.append(" NOT NULL");
            }

            if (i < columns.size() - 1) {
                sql.append(",\n");
            }
        }

        // Thêm Primary Key nếu có
        List<String> pks = table.getPrimaryKeys();
        if (!pks.isEmpty()) {
            sql.append(",\n    PRIMARY KEY (");
            for (int i = 0; i < pks.size(); i++) {
                sql.append(quoteIdentifier(pks.get(i)));
                if (i < pks.size() - 1) sql.append(", ");
            }
            sql.append(")");
        }

        sql.append("\n);");
        return sql.toString();
    }
    @Override
    public List<String> buildAddForeignKeySql(TableDefinition table) {
        List<String> alterStatements = new ArrayList<>();

        if (table != null
                && table.getSourceDialect() == DatabaseType.POSTGRESQL
                && table.getForeignKeyDdls() != null
                && !table.getForeignKeyDdls().isEmpty()) {
            for (String fkDdl : table.getForeignKeyDdls()) {
                if (fkDdl == null || fkDdl.isBlank()) {
                    continue;
                }
                String remapped = remapSchemaPrefix(fkDdl, table.getSourceSchema(), table.getTargetSchema());
                if (remapped != null && !remapped.isBlank()) {
                    alterStatements.add(stripTrailingSemicolon(remapped));
                }
            }
            if (!alterStatements.isEmpty()) {
                return alterStatements;
            }
        }

        for (ForeignKeyDefinition fk : table.getForeignKeys()) {
            StringBuilder sql = new StringBuilder();

            // Xử lý trường hợp tên constraint bềEtrùng hoặc null
            String constraintName = fk.getFkName();
            if (constraintName == null || constraintName.isEmpty()) {
                constraintName = "fk_" + table.getTableName() + "_" + fk.getFkColumnName();
            }

            sql.append("ALTER TABLE ").append(quoteIdentifier(table.getTableName())).append("\n");
            sql.append("    ADD CONSTRAINT ").append(quoteIdentifier(constraintName)).append("\n");
            sql.append("    FOREIGN KEY (").append(quoteIdentifier(fk.getFkColumnName())).append(")\n");
            sql.append("    REFERENCES ").append(quoteIdentifier(fk.getTargetTableName()))
                    .append(" (").append(quoteIdentifier(fk.getTargetColumnName())).append(");");

            alterStatements.add(sql.toString());
        }

        return alterStatements;
    }

    // ─── SEQUENCE DDL ───────────────────────────────────────────────

    @Override
    public String buildCreateSequenceSql(SequenceDefinition seq) {
        if (seq != null
                && seq.getSourceDialect() == DatabaseType.POSTGRESQL
                && seq.getDdlText() != null
                && !seq.getDdlText().isBlank()) {
            String ddl = remapSchemaPrefix(seq.getDdlText(), seq.getSourceSchema(), seq.getTargetSchema());
            return stripTrailingSemicolon(ddl);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE SEQUENCE IF NOT EXISTS ");
        sb.append(quoteIdentifier(seq.getSequenceName()));

        if (seq.getStartValue() > 0) {
            sb.append(" START WITH ").append(seq.getStartValue());
        }
        if (seq.getIncrementBy() != 0) {
            sb.append(" INCREMENT BY ").append(seq.getIncrementBy());
        }
        if (seq.getMinValue() != null) {
            sb.append(" MINVALUE ").append(seq.getMinValue());
        }
        if (seq.getMaxValue() != null) {
            sb.append(" MAXVALUE ").append(seq.getMaxValue());
        }
        // PostgreSQL: CACHE >= 1, nếu null → dùng 1
        sb.append(" CACHE ").append(seq.getCacheSize() != null ? seq.getCacheSize() : 1);
        sb.append(seq.isCycle() ? " CYCLE" : " NO CYCLE");
        sb.append(";");
        return sb.toString();
    }

    @Override
    public List<String> buildCreateIndexSql(IndexDefinition idx) {
        List<String> stmts = new ArrayList<>();

        if (idx != null
                && idx.getSourceDialect() == DatabaseType.POSTGRESQL
                && idx.getDdlText() != null
                && !idx.getDdlText().isBlank()) {
            String ddl = remapSchemaPrefix(idx.getDdlText(), idx.getSourceSchema(), idx.getTargetSchema());
            stmts.add(stripTrailingSemicolon(ddl));
            return stmts;
        }

        StringBuilder sb = new StringBuilder();

        // PostgreSQL không hỗ trợ BITMAP index
        if (idx.getIndexType() == IndexDefinition.IndexType.BITMAP) {
            return stmts; // empty — không migrate được
        }

        // Partial index (WHERE clause)
        if (idx.isPartialIndex()) {
            // PostgreSQL hỗ trợ partial index với WHERE
            sb.append(idx.isUnique() ? "CREATE UNIQUE INDEX " : "CREATE INDEX ");
            if (idx.getIndexName() != null) {
                sb.append(quoteIdentifier(idx.getIndexName())).append(" ");
            }
            sb.append("ON ").append(quoteIdentifier(idx.getTableName()));
            appendIndexUsingClause(sb, idx);
            appendIndexColumns(sb, idx);
            sb.append(" WHERE ").append(idx.getWhereClause());
            stmts.add(sb.toString());
            return stmts;
        }

        // Expression index
        if (idx.isExpressionIndex()) {
            sb.append(idx.isUnique() ? "CREATE UNIQUE INDEX " : "CREATE INDEX ");
            if (idx.getIndexName() != null) {
                sb.append(quoteIdentifier(idx.getIndexName())).append(" ");
            }
            sb.append("ON ").append(quoteIdentifier(idx.getTableName()));
            appendIndexUsingClause(sb, idx);
            if (idx.getExpression() != null) {
                sb.append(" (").append(idx.getExpression()).append(")");
            }
            stmts.add(sb.toString());
            return stmts;
        }

        // Index thường
        sb.append(idx.isUnique() ? "CREATE UNIQUE INDEX " : "CREATE INDEX ");
        if (idx.getIndexName() != null) {
            sb.append(quoteIdentifier(idx.getIndexName())).append(" ");
        }
        sb.append("ON ").append(quoteIdentifier(idx.getTableName()));
        appendIndexUsingClause(sb, idx);
        appendIndexColumns(sb, idx);
        stmts.add(sb.toString());
        return stmts;
    }

    private void appendIndexUsingClause(StringBuilder sb, IndexDefinition idx) {
        String method = resolveIndexMethod(idx);
        if (method == null || method.isBlank() || "BTREE".equalsIgnoreCase(method)) {
            return;
        }
        sb.append(" USING ").append(method.toLowerCase(Locale.ROOT));
    }

    private String resolveIndexMethod(IndexDefinition idx) {
        if (idx.getIndexTypeName() != null && !idx.getIndexTypeName().isBlank()) {
            String normalized = idx.getIndexTypeName().trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("BTREE")
                    || normalized.equals("HASH")
                    || normalized.equals("GIN")
                    || normalized.equals("GIST")
                    || normalized.equals("BRIN")) {
                return normalized;
            }
        }
        if (idx.getIndexType() == null) {
            return "BTREE";
        }
        switch (idx.getIndexType()) {
            case HASH:
                return "HASH";
            case GIN:
                return "GIN";
            case GIST:
                return "GIST";
            case BRIN:
                return "BRIN";
            default:
                return "BTREE";
        }
    }

    private void appendIndexColumns(StringBuilder sb, IndexDefinition idx) {
        sb.append(" (");
        List<String> cols = idx.getColumns();
        List<String> descs = idx.getDescendings();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            String col = cols.get(i);
            // Nếu là expression
            if (col.contains("(") || idx.getExpression() != null && i == 0 && !cols.isEmpty()) {
                sb.append(col);
            } else {
                sb.append(quoteIdentifier(col));
            }
            if (descs != null && i < descs.size() && "DESC".equalsIgnoreCase(descs.get(i))) {
                sb.append(" DESC");
            }
        }
        sb.append(")");
    }

    // ─── VIEW DDL ────────────────────────────────────────────────

    @Override
    public List<String> buildCreateTriggerSql(TriggerDefinition trig, OracleToPgsqlTransformer transformer) {
        List<String> stmts = new ArrayList<>();
        if (trig == null) return stmts;

        if (trig.getSourceDialect() == DatabaseType.POSTGRESQL
                && trig.getFunctionDdl() != null && !trig.getFunctionDdl().isBlank()
                && trig.getDdlText() != null && !trig.getDdlText().isBlank()) {
            String functionDdl = remapSchemaPrefix(trig.getFunctionDdl(), trig.getSourceSchema(), trig.getTargetSchema());
            String triggerDdl = remapSchemaPrefix(trig.getDdlText(), trig.getSourceSchema(), trig.getTargetSchema());
            stmts.add(functionDdl.trim());
            stmts.add(triggerDdl.trim());
            if (!trig.isEnabled()) {
                stmts.add(buildDisableTriggerSql(trig));
            }
            return stmts;
        }

        String functionBody;
        if (transformer != null && trig.getSourceDialect() == DatabaseType.ORACLE) {
            // Transform Oracle → PostgreSQL
            functionBody = transformer.transformTriggerBody(trig.getTriggerBody());
        } else {
            functionBody = trig.getTriggerBody();
        }

        String functionName = trig.resolveFunctionName();
        String tableName = trig.getTableName();

        // 1. Tạo FUNCTION
        StringBuilder func = new StringBuilder();
        func.append("CREATE OR REPLACE FUNCTION ");
        func.append(quoteIdentifier(functionName));
        func.append("() RETURNS trigger AS $$\n");
        func.append("BEGIN\n");
        // Thêm RETURN NEW/OLD nếu body không có
        if (functionBody != null && !functionBody.trim().isEmpty()) {
            // Kiểm tra xem body đã có RETURN NEW/RETURN OLD chưa
            String upperBody = functionBody.toUpperCase();
            if (!upperBody.contains("RETURN NEW") && !upperBody.contains("RETURN OLD")) {
                // Nếu trigger level = ROW, thêm RETURN NEW
                func.append("  ");
                func.append(functionBody.trim());
                if (!functionBody.trim().endsWith(";")) {
                    func.append(";");
                }
                func.append("\n");
                func.append("  RETURN NEW;\n");
            } else {
                func.append("  ");
                func.append(functionBody.trim());
                if (!functionBody.trim().endsWith(";")) {
                    func.append(";");
                }
                func.append("\n");
            }
        } else {
            func.append("  RETURN NEW;\n");
        }
        func.append("END;\n");
        func.append("$$ LANGUAGE plpgsql;");
        stmts.add(func.toString());

        // 2. Tạo TRIGGER gắn function vào bảng
        StringBuilder trigStmt = new StringBuilder();
        trigStmt.append("CREATE TRIGGER ");
        trigStmt.append(quoteIdentifier(trig.getTriggerName()));

        // Timing + Event
        String timingEvent = buildTriggerTimingEvent(trig);
        trigStmt.append(" ").append(timingEvent);
        trigStmt.append(" ON ").append(quoteIdentifier(tableName));

        // FOR EACH ROW
        if (trig.isRowLevel()) {
            trigStmt.append(" FOR EACH ROW");
        }

        // WHEN clause
        String whenClause = trig.getTransformedWhenClause();
        if (whenClause != null && !whenClause.isBlank()) {
            trigStmt.append(" WHEN (").append(whenClause).append(")");
        }

        // EXECUTE FUNCTION
        trigStmt.append(" EXECUTE FUNCTION ").append(quoteIdentifier(functionName)).append("();");

        stmts.add(trigStmt.toString());
        if (!trig.isEnabled()) {
            stmts.add(buildDisableTriggerSql(trig));
        }
        return stmts;
    }

    private String buildDisableTriggerSql(TriggerDefinition trig) {
        String tableName = quoteIdentifier(trig.getTableName());
        String targetSchema = trig.getTargetSchema();
        if (targetSchema != null && !targetSchema.isBlank()) {
            tableName = quoteIdentifier(targetSchema) + "." + tableName;
        }
        return "ALTER TABLE " + tableName + " DISABLE TRIGGER "
                + quoteIdentifier(trig.getTriggerName()) + ";";
    }

    // ─── FUNCTION / PROCEDURE DDL ──────────────────────────────

    @Override
    public List<String> buildCreateFunctionSql(FunctionDefinition fn, OracleToPgsqlTransformer transformer) {
        List<String> stmts = new ArrayList<>();
        if (fn == null) return stmts;

        if (fn.getSourceDialect() == DatabaseType.POSTGRESQL
                && fn.getDdlText() != null && !fn.getDdlText().isBlank()) {
            String ddl = remapSchemaPrefix(fn.getDdlText(), fn.getSourceSchema(), fn.getTargetSchema());
            stmts.add(ddl.trim());
            return stmts;
        }

        String functionBody;
        if (transformer != null && fn.getSourceDialect() == DatabaseType.ORACLE) {
            functionBody = transformer.transform(stripOracleCreateLine(fn.getFunctionBody()));
        } else {
            functionBody = fn.getFunctionBody();
        }

        // Strip any existing $$...$$ dollar-quoting from the body.
        // Oracle function bodies from USER_SOURCE may use $$ delimiters.
        // Since we wrap the body in our own $$...$$ below, keeping the
        // original delimiters would produce malformed SQL like:
        //   AS $$ <body containing $$...$$> $$ LANGUAGE plpgsql;
        if (functionBody != null) {
            functionBody = stripDollarQuoting(functionBody);
            if (fn.getSourceDialect() == DatabaseType.ORACLE) {
                functionBody = normalizeOracleRoutineBodyForPostgres(functionBody);
            }
        }

        StringBuilder sb = new StringBuilder();

        if (fn.isProcedure()) {
            sb.append("CREATE OR REPLACE PROCEDURE ");
        } else {
            sb.append("CREATE OR REPLACE FUNCTION ");
        }

        sb.append(quoteIdentifier(fn.getFunctionName()));
        sb.append("(");

        // Arguments — transform Oracle types to PostgreSQL types if needed
        List<FunctionDefinition.FunctionArgument> args = fn.getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            FunctionDefinition.FunctionArgument arg = args.get(i);
            if (arg.getMode() != null) {
                switch (arg.getMode()) {
                    case OUT -> sb.append("OUT ");
                    case INOUT -> sb.append("INOUT ");
                    default -> sb.append("IN ");
                }
            }
            if (arg.getName() != null && !arg.getName().isBlank()) {
                sb.append(arg.getName().toLowerCase(Locale.ROOT)).append(" ");
            }
            sb.append(mapOracleTypeToPostgres(arg.getDataType()));
        }
        sb.append(")");

        if (fn.isFunction() && fn.getReturnType() != null) {
            sb.append(" RETURNS ").append(mapOracleTypeToPostgres(fn.getReturnType()));
        }

        sb.append(" AS $$\n");
        sb.append(functionBody != null ? functionBody : "BEGIN NULL;\n");
        sb.append("\n$$ LANGUAGE plpgsql;");

        stmts.add(sb.toString());
        return stmts;
    }

    private static String remapSchemaPrefix(String ddl, String sourceSchema, String targetSchema) {
        return OracleDialect.remapSchemaPrefixSafely(ddl, sourceSchema, targetSchema);
    }

    private static String stripTrailingSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Build phần timing + event cho CREATE TRIGGER.
     * Ví dụ: "BEFORE INSERT OR UPDATE ON"
     */
    private String buildTriggerTimingEvent(TriggerDefinition trig) {
        StringBuilder sb = new StringBuilder();

        // Timing
        if (trig.getTiming() == TriggerDefinition.TriggerTiming.INSTEAD_OF) {
            sb.append("INSTEAD OF");
        } else if (trig.getTiming() == TriggerDefinition.TriggerTiming.BEFORE) {
            sb.append("BEFORE");
        } else {
            sb.append("AFTER");
        }

        // Events
        String events = trig.getTriggeringEvent();
        if (events != null && !events.isBlank()) {
            sb.append(" ").append(events.toUpperCase());
        } else {
            sb.append(" INSERT"); // default
        }

        return sb.toString();
    }

    @Override
    public String buildCreateViewSql(ViewDefinition viewDef) {
        // Always apply schema qualification for Oracle sources (both CLI and web service use this)
        String schema = viewDef.getTargetSchema();
        if (schema == null || schema.isBlank()) {
            schema = viewDef.getSchema();
        }
        return buildCreateViewSql(viewDef, schema);
    }

    @Override
    public String buildCreateViewSql(ViewDefinition viewDef, String targetSchema) {
        if (viewDef.getSourceDialect() == DatabaseType.POSTGRESQL
                && viewDef.getDdlText() != null
                && !viewDef.getDdlText().isBlank()) {
            String ddl = remapSchemaPrefix(viewDef.getDdlText(), viewDef.getSourceSchema(), viewDef.getTargetSchema());
            return stripTrailingSemicolon(ddl);
        }

        // Postgres: pg_get_viewdef trả về SELECT clause thuần (không có CREATE VIEW)
        // Cần gắn CREATE OR REPLACE VIEW ... AS
        String select = viewDef.getSelectClause();
        if (select == null || select.isBlank()) {
            return null;
        }
        select = select.trim();
        // Bỏ dấu ; cuối nếu có (pg_get_viewdef có thể trả về)
        if (select.endsWith(";")) {
            select = select.substring(0, select.length() - 1);
        }

        // Chỉ qualify schema khi migrate từ Oracle → PostgreSQL
        // (PostgreSQL → PostgreSQL: giữ nguyên vì table đã đúng schema)
        if (viewDef.getSourceDialect() == DatabaseType.ORACLE
                && targetSchema != null && !targetSchema.isBlank()) {
            select = qualifyUnqualifiedTableReferences(select, targetSchema);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE OR REPLACE VIEW ");
        sql.append(quoteIdentifier(viewDef.getViewName()));
        sql.append(" AS\n");
        sql.append(select);

        if (viewDef.getCheckOption() != null && !viewDef.getCheckOption().isBlank()) {
            sql.append("\nWITH ").append(viewDef.getCheckOption()).append(" CHECK OPTION");
        }

        return sql.toString();
    }

    /**
     * Qualify các table reference không có schema prefix trong SELECT clause.
     * Ví dụ: FROM customers c → FROM SCHEMA.customers c
     *         JOIN orders o ON ... → JOIN SCHEMA.orders o ON ...
     *         LEFT OUTER JOIN products p → LEFT OUTER JOIN SCHEMA.products p
     *
     * Không qualify:
     * - Table đã có schema prefix (schema.table)
     * - Subquery (FROM (SELECT ...))
     * - Function calls (FROM func(...))
     * - PostgreSQL system catalogs (pg_catalog, information_schema)
     *
     * @param select SELECT clause thuần
     * @param schema schema để qualify
     * @return SELECT đã được qualify
     */
    public static String qualifyUnqualifiedTableReferences(String select, String schema) {
        // Token-based parser: scan for FROM/JOIN keywords, qualify the bare table name that follows.
        // Tables with explicit schema (owner.table), subqueries, function calls, and pg_* schemas are
        // left as-is.

        StringBuilder result = new StringBuilder();
        int pos = 0;
        int len = select.length();

        while (pos < len) {
            // Tìm từ khóa FROM / JOIN tiếp theo (case-insensitive, bounded by non-identifier chars)
            int nextFrom = indexOfWord(select, "from", pos);
            int nextJoin = indexOfWord(select, "join", pos);

            int kwPos;
            String kwType;
            if (nextFrom >= 0 && (nextJoin < 0 || nextFrom <= nextJoin)) {
                kwPos = nextFrom;
                kwType = "from";
            } else {
                kwPos = nextJoin;
                kwType = "join";
            }

            if (kwPos < 0) {
                result.append(select.substring(pos));
                break;
            }

            // Append phần trước keyword (có thể rỗng)
            result.append(select, pos, kwPos);

            // Xác định ranh giới cuối của keyword
            int kwEnd = findKeywordEnd(select, kwPos, kwType);
            result.append(select, kwPos, kwEnd);
            pos = kwEnd;

            // Skip whitespace
            while (pos < len && Character.isWhitespace(select.charAt(pos))) pos++;
            if (pos >= len) break;

            // Xác định table reference tiếp theo
            int tableEnd = findTableReferenceEnd(select, pos);

            // Lấy table reference (phần trước ranh giới)
            String tableRef = select.substring(pos, tableEnd).trim();

            // Phân tích table reference
            String resolvedRef = resolveTableRef(tableRef, schema);
            result.append(resolvedRef);

            pos = tableEnd;
        }

        return result.toString();
    }

    /**
     * Tìm vị trí kết thúc của một keyword (FROM/JOIN và các biến thể).
     * Ví dụ: "LEFT OUTER JOIN" → kết thúc sau "JOIN"
     */
    private static int findKeywordEnd(String s, int start, String kwType) {
        if (kwType.equals("from")) {
            int end = start + 4; // skip "from" (4 chars)
            // Skip trailing whitespace so the space before the table name is preserved in the result
            while (end < s.length() && Character.isWhitespace(s.charAt(end))) end++;
            return end;
        }
        // JOIN variants: INNER JOIN, LEFT JOIN, LEFT OUTER JOIN, CROSS JOIN, NATURAL JOIN ...
        int pos = start + 4; // skip "join" (4 chars), pos now at the space after "join"
        int len = s.length();

        while (pos < len) {
            // Skip whitespace before next word
            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos >= len) break;

            String rest = s.substring(pos).toLowerCase();
            if (rest.startsWith("inner ")) {
                // "INNER" = 5 chars + 1 space = 6
                pos += 6;
            } else if (rest.startsWith("outer ")) {
                pos += 6;
            } else if (rest.startsWith("cross ")) {
                pos += 6;
            } else if (rest.startsWith("natural ")) {
                pos += 8;
            } else {
                // Next word is not a join-type keyword (e.g., "JOIN" again, or start of table ref)
                break;
            }
        }
        return pos;
    }

    /**
     * Tìm vị trí kết thúc của table reference trong FROM/JOIN clause.
     * Table reference kết thúc khi gặp: ON, WHERE, GROUP, ORDER, HAVING, LIMIT,
     * OFFSET, UNION, INTERSECT, EXCEPT, AND, OR, COMMA, end of string.
     */
    private static int findTableReferenceEnd(String s, int start) {
        int len = s.length();
        int pos = start;

        // Skip whitespace
        while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;

        // Nếu bắt đầu bằng '(' → subquery
        if (pos < len && s.charAt(pos) == '(') {
            int depth = 0;
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) { pos++; break; }
                }
                pos++;
            }
            return pos;
        }

        // Đọc từ đầu tiên (schema.table hoặc table)
        while (pos < len && isIdentChar(s.charAt(pos))) pos++;
        // firstWord is s.substring(start, pos).toLowerCase() — used below

        // Xử lý schema.table (đã có schema prefix)
        if (pos < len && s.charAt(pos) == '.') {
            pos++; // skip '.'
            while (pos < len && isIdentChar(s.charAt(pos))) pos++;
        }

        // Skip optional alias (AS alias hoặc alias thuần)
        pos = skipOptionalAlias(s, pos);

        // NOW stop at SQL clause keywords that mark the end of the table reference:
        // ON, WHERE, GROUP, ORDER, HAVING, LIMIT, OFFSET, UNION, INTERSECT,
        // EXCEPT, AND, OR, COMMA, '(', end-of-string
        // These are the keywords that FOLLOW the table reference, not part of it.
        while (pos < len) {
            // Skip whitespace before checking keyword
            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos >= len) break;

            // Read the next word
            int wordStart = pos;
            while (pos < len && isIdentChar(s.charAt(pos))) pos++;
            if (pos <= wordStart) break; // no word found
            String word = s.substring(wordStart, pos).toLowerCase();

            // If this is a clause keyword, backtrack and stop BEFORE it
            if (isClauseKeyword(word)) {
                // Stop at the start of this keyword
                break;
            }
            // Otherwise this word is part of the table reference (e.g., comma-separated list)
            // Continue scanning
        }

        return pos;
    }

    /** Clause keywords that always terminate a table reference in a FROM/JOIN clause. */
    private static boolean isClauseKeyword(String word) {
        return word.equals("on") || word.equals("where") || word.equals("group") || word.equals("order")
                || word.equals("having") || word.equals("limit") || word.equals("offset")
                || word.equals("union") || word.equals("intersect") || word.equals("except")
                || word.equals("and") || word.equals("or")
                || word.equals("inner") || word.equals("outer") || word.equals("cross")
                || word.equals("left") || word.equals("right") || word.equals("full")
                || word.equals("join") || word.equals("from") || word.equals("natural")
                || word.equals("using") || word.equals("select");
    }

    /**
     * Phân tích table reference và qualify nếu cần.
     * @param tableRef chuỗi table reference thuần (đã trim)
     * @param schema schema mặc định để qualify
     * @return chuỗi đã qualify hoặc giữ nguyên
     */
    private static String resolveTableRef(String tableRef, String schema) {
        if (tableRef.isEmpty()) return tableRef;

        String ref = tableRef.trim();
        int len = ref.length();

        // Skip leading whitespace
        int pos = 0;
        while (pos < len && Character.isWhitespace(ref.charAt(pos))) pos++;

        // Đọc từ đầu tiên
        int nameStart = pos;
        while (pos < len && isIdentChar(ref.charAt(pos))) pos++;
        String firstWordRaw = ref.substring(nameStart, pos);
        String firstWord = firstWordRaw.toLowerCase(Locale.ROOT);

        // Nếu có dấu . ngay sau → schema.table đã qualified
        if (pos < len && ref.charAt(pos) == '.') {
            // Đã có schema prefix → giữ nguyên
            return ref;
        }

        // pg_catalog / information_schema → không qualify
        if (firstWord.equals("pg_catalog") || firstWord.equals("information_schema")) {
            return ref;
        }

        // Kiểm tra function call: theo sau '(' mà không có AS giữa
        if (pos < len && ref.charAt(pos) == '(') {
            // Function call → không qualify
            return ref;
        }

        // Skip optional alias (AS alias hoặc alias thuần)
        int aliasEnd = skipOptionalAlias(ref, pos);
        String aliasSuffix = aliasEnd < len ? ref.substring(aliasEnd) : "";

        // Đây là bare table name → qualify
        return schema + "." + firstWordRaw + aliasSuffix;
    }

    /**
     * Tìm vị trí của một word trong string (case-insensitive), bắt đầu tìm từ startPos.
     * Word phải được bao bởi non-identifier characters.
     */
    private static int indexOfWord(String s, String word, int startPos) {
        String lower = s.toLowerCase();
        int pos = startPos;
        while (true) {
            int idx = lower.indexOf(word, pos);
            if (idx < 0) return -1;
            int before = idx - 1;
            int after = idx + word.length();
            boolean validBefore = before < 0 || !isIdentChar(s.charAt(before));
            boolean validAfter = after >= s.length() || !isIdentChar(s.charAt(after));
            if (validBefore && validAfter) return idx;
            pos = idx + 1;
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isSqlKeyword(String word) {
        return word.equals("select") || word.equals("where") || word.equals("null")
                || word.equals("true") || word.equals("false")
                || word.equals("as") || word.equals("on") || word.equals("using")
                || word.equals("and") || word.equals("or") || word.equals("not")
                || word.equals("in") || word.equals("exists") || word.equals("between")
                || word.equals("case") || word.equals("when") || word.equals("then")
                || word.equals("else") || word.equals("end") || word.equals("like")
                || word.equals("ilike") || word.equals("is")
                || word.equals("distinct") || word.equals("all") || word.equals("any")
                || word.equals("some") || word.equals("union") || word.equals("intersect")
                || word.equals("except") || word.equals("offset") || word.equals("limit")
                || word.equals("group") || word.equals("order") || word.equals("having")
                || word.equals("by") || word.equals("asc") || word.equals("desc")
                || word.equals("nulls") || word.equals("first") || word.equals("last")
                || word.equals("with") || word.equals("recursive") || word.equals("lateral")
                || word.equals("over") || word.equals("partition") || word.equals("window")
                || word.equals("inner") || word.equals("outer") || word.equals("cross")
                || word.equals("full") || word.equals("natural") || word.equals("right")
                || word.equals("left") || word.equals("tablesample") || word.equals("system")
                || word.equals("insert") || word.equals("update") || word.equals("delete")
                || word.equals("set") || word.equals("into") || word.equals("values")
                || word.equals("returning");
    }

    /**
     * Strip the CREATE... line from an Oracle function/procedure body retrieved from USER_SOURCE.
     *
     * Oracle stores the full line including "CREATE OR REPLACE FUNCTION/PROCEDURE name..."
     * in USER_SOURCE.TEXT. When we build a PostgreSQL CREATE...AS $$...$$ block,
     * we need only the body between AS/IS and END, not the CREATE line itself.
     *
     * Handles these patterns:
     *   CREATE OR REPLACE FUNCTION name...AS...BEGIN...END;
     *   CREATE OR REPLACE PROCEDURE name...IS...BEGIN...END;
     *   FUNCTION name...AS...BEGIN...END;  (no CREATE)
     *
     * The AS/IS delimiter must appear OUTSIDE string literals to be valid.
     * For example, VARCHAR2(10 AS counter) contains AS in a string literal
     * and must NOT be treated as the header/body delimiter.
     */
    private static String stripOracleCreateLine(String body) {
        if (body == null || body.isBlank()) return body;

        // Find AS/IS that is the actual header/body delimiter (not inside a string literal).
        // In Oracle, this delimiter is always followed by whitespace and then the PL/SQL body.
        int delimPos = findHeaderBodyDelimiter(body);
        if (delimPos < 0) {
            // No AS/IS found outside string literals — fall back to stripping CREATE prefix
            return stripCreatePrefix(body);
        }

        String delim = body.substring(delimPos, delimPos + 2).trim();
        // Return everything after AS/IS (skip the delimiter itself and any whitespace)
        String after = body.substring(delimPos + delim.length()).replaceFirst("^\\s+", "");
        // Strip leading semicolon if present (Oracle sometimes puts ; after AS keyword)
        if (after.startsWith(";")) {
            after = after.substring(1).replaceFirst("^\\s+", "");
        }
        return after;
    }

    /**
     * Find the position of the AS or IS keyword that separates the Oracle function/procedure
     * header from its body. This must be OUTSIDE string literals.
     *
     * Strategy: scan for the first " AS " or " IS " (word-bounded) that is NOT inside
     * a single-quoted string literal. Oracle uses single quotes for strings, and '' is
     * an escaped single quote within a string.
     */
    private static int findHeaderBodyDelimiter(String body) {
        int len = body.length();

        for (int i = 0; i < len; i++) {
            char c = body.charAt(i);

            // Single-quote: skip the entire string literal
            if (c == '\'') {
                i++;
                while (i < len) {
                    if (body.charAt(i) == '\'') {
                        // '' = escaped quote, skip both
                        if (i + 1 < len && body.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++; // end of string
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Check for "AS" or "IS" at current position
            int remaining = len - i;
            if (remaining >= 2) {
                // Check for "AS" — must be word-bounded
                if (body.charAt(i) == 'A' && body.charAt(i + 1) == 'S') {
                    boolean validBefore = (i == 0) || !isIdentChar(body.charAt(i - 1));
                    boolean validAfter  = (i + 2 >= len) || !isIdentChar(body.charAt(i + 2));
                    if (validBefore && validAfter) {
                        return i; // "AS" found — this is the header/body delimiter
                    }
                }
                // Check for "IS" — must be word-bounded
                if (body.charAt(i) == 'I' && body.charAt(i + 1) == 'S') {
                    // Guard against matching the "IS" inside "RETURN ... IS":
                    //   "RETURN VARCHAR2 IS" — the IS here is inside the return-type line,
                    //   not the header/body delimiter. Skip it if preceded by RETURN/RETURNING.
                    //   VARCHAR2 can be up to 4000 chars, so look back 30 chars to safely
                    //   capture "RETURN" regardless of how long the type name is.
                    if (i >= 30) {
                        String prefix = body.substring(i - 30, i).toUpperCase(Locale.ROOT);
                        // Strip anything that looks like type declarations (identifiers, parens,
                        // commas, digits) to isolate the SQL keyword at the end.
                        String stripped = prefix.replaceAll("[A-Z0-9_().,\"\\s]+$", "");
                        if ("RETURN".equals(stripped) || "RETURNING".equals(stripped)) {
                            continue; // skip — this IS is part of RETURN type, not the delimiter
                        }
                    }
                    boolean validBefore = (i == 0) || !isIdentChar(body.charAt(i - 1));
                    boolean validAfter  = (i + 2 >= len) || !isIdentChar(body.charAt(i + 2));
                    if (validBefore && validAfter) {
                        return i; // "IS" found — this is the header/body delimiter
                    }
                }
            }
        }
        return -1; // not found
    }

    /**
     * Remove dollar-quote delimiters ($$...$$ or $tag$...$tag$) from a string.
     * Oracle function bodies from USER_SOURCE may use $$ delimiters.
     * Since we wrap the body in our own $$...$$ below, we must strip any
     * existing delimiters to avoid producing:
     *   AS $$ ... $$ ... $$ LANGUAGE plpgsql;
     */
    private static String stripDollarQuoting(String body) {
        if (body == null || body.isBlank()) return body;

        // Simple $$...$$  (no tag)
        if (body.startsWith("$$") && body.endsWith("$$") && body.length() >= 4) {
            return body.substring(2, body.length() - 2).trim();
        }

        // Dollar-quoted with a tag: $tag$...$tag$
        // Extract the tag, then strip matching delimiters
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '$') continue;
            int tagEnd = body.indexOf('$', i + 1);
            if (tagEnd <= i + 1) continue;
            String tag = body.substring(i + 1, tagEnd);
            if (tag.isEmpty()) continue; // empty tag not valid
            String closing = "$" + tag + "$";
            int bodyEnd = body.lastIndexOf(closing);
            if (bodyEnd > tagEnd) {
                // Found matching closing delimiter
                String inner = body.substring(tagEnd + 1, bodyEnd);
                // Guard against $$ appearing inside the body (unrelated)
                if (inner.contains("$$")) {
                    // Body contains bare $$ — strip only the outer delimiters
                    // This is the problematic case: body starts with $$ but also has $$ inside
                    // We use a simple approach: strip first and last occurrence
                    return stripFirstAndLastDollarPair(body);
                }
                return inner.trim();
            }
        }

        return body;
    }

    /**
     * Fallback: strip the first $$ and the last $$ from the body.
     * Used when the body contains bare $$ that can't be reliably paired.
     */
    private static String stripFirstAndLastDollarPair(String body) {
        int first = body.indexOf("$$");
        int last = body.lastIndexOf("$$");
        if (first >= 0 && last > first) {
            return body.substring(first + 2, last).trim();
        }
        return body;
    }

    private static String stripCreatePrefix(String body) {
        // Strip "CREATE [OR REPLACE] FUNCTION name..." or "CREATE [OR REPLACE] PROCEDURE name..."
        // Find the first newline or semicolon after the CREATE line
        int endOfFirstLine = body.indexOf('\n');
        if (endOfFirstLine < 0) endOfFirstLine = body.indexOf(';');
        if (endOfFirstLine < 0) return body;

        String firstLine = body.substring(0, endOfFirstLine).trim();
        if (firstLine.matches("(?i).*\\bCREATE\\b.*")) {
            return body.substring(endOfFirstLine).replaceFirst("^\\s+", "");
        }
        return body;
    }

    /**
     * Oracle routine body có thể chứa declaration block trước BEGIN mà không có DECLARE.
     * PostgreSQL yêu cầu declaration phải nằm dưới từ khóa DECLARE và dùng type hợp lệ của PG.
     */
    private static String normalizeOracleRoutineBodyForPostgres(String body) {
        if (body == null || body.isBlank()) return body;

        String normalized = body
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("(?m)^\\s*/\\s*$", "")
                .trim();

        int beginIdx = indexOfWordOutsideSingleQuotes(normalized, "BEGIN");
        if (beginIdx < 0) {
            return convertOracleDeclarationTypes(normalized);
        }

        String beforeBegin = normalized.substring(0, beginIdx).trim();
        String fromBegin = normalized.substring(beginIdx).trim();
        if (beforeBegin.isBlank()) {
            return fromBegin;
        }

        String declarations = beforeBegin;
        if (declarations.toUpperCase(Locale.ROOT).startsWith("DECLARE")) {
            declarations = declarations.substring("DECLARE".length()).trim();
        }

        declarations = convertOracleDeclarationTypes(declarations).trim();
        if (declarations.isBlank()) {
            return fromBegin;
        }

        return "DECLARE\n" + declarations + "\n" + fromBegin;
    }

    private static String convertOracleDeclarationTypes(String declarations) {
        if (declarations == null || declarations.isBlank()) return declarations;

        String converted = declarations;
        converted = converted.replaceAll("(?im)\\bNVARCHAR2\\s*\\([^)]*\\)", "TEXT");
        converted = converted.replaceAll("(?im)\\bVARCHAR2\\s*\\([^)]*\\)", "TEXT");
        converted = converted.replaceAll("(?im)\\bNVARCHAR2\\b", "TEXT");
        converted = converted.replaceAll("(?im)\\bVARCHAR2\\b", "TEXT");
        converted = converted.replaceAll("(?im)\\bNUMBER\\s*\\([^)]*\\)", "NUMERIC");
        converted = converted.replaceAll("(?im)\\bNUMBER\\b", "NUMERIC");
        converted = converted.replaceAll("(?im)\\bPLS_INTEGER\\b", "INTEGER");
        converted = converted.replaceAll("(?im)\\bBINARY_INTEGER\\b", "INTEGER");
        converted = converted.replaceAll("(?im)\\bCLOB\\b", "TEXT");
        converted = converted.replaceAll("(?im)\\bNCLOB\\b", "TEXT");
        converted = converted.replaceAll("(?im)\\bBLOB\\b", "BYTEA");
        converted = converted.replaceAll("(?im)\\bRAW\\b", "BYTEA");
        converted = converted.replaceAll("(?im)\\bDATE\\b", "TIMESTAMP");
        return converted;
    }

    private static int indexOfWordOutsideSingleQuotes(String text, String word) {
        if (text == null || text.isEmpty() || word == null || word.isEmpty()) {
            return -1;
        }

        int len = text.length();
        int wLen = word.length();
        boolean inString = false;

        for (int i = 0; i <= len - wLen; i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < len && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (text.regionMatches(true, i, word, 0, wLen)) {
                int before = i - 1;
                int after = i + wLen;
                boolean validBefore = before < 0 || !isIdentChar(text.charAt(before));
                boolean validAfter = after >= len || !isIdentChar(text.charAt(after));
                if (validBefore && validAfter) {
                    return i;
                }
            }
        }

        return -1;
    }

    private static int skipOptionalAlias(String s, int pos) {
        int len = s.length();
        // Skip whitespace
        while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
        if (pos >= len) return pos;

        // Check for AS keyword
        if (pos + 2 < len && s.substring(pos, pos + 2).equalsIgnoreCase("as")) {
            pos += 2;
            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        // Skip alias identifier
        if (pos < len && isIdentChar(s.charAt(pos))) {
            while (pos < len && isIdentChar(s.charAt(pos))) pos++;
            // Handle quoted identifiers
            if (pos < len && s.charAt(pos) == '"') {
                pos++;
                while (pos < len && s.charAt(pos) != '"') pos++;
                if (pos < len) pos++; // skip closing "
            }
        }
        return pos;
    }

    /**
     * Chuyển Oracle type declarations (trong function/procedure arguments & return types)
     * sang PostgreSQL tương đương.
     */
    private static String mapOracleTypeToPostgres(String oracleType) {
        if (oracleType == null) return null;
        String upper = oracleType.toUpperCase(Locale.ROOT).trim();

        if (upper.matches("(?i)^NUMBER(\\(\\d+(,\\d+)?\\))?$")) {
            // NUMBER → NUMERIC
            return "NUMERIC";
        }
        if (upper.matches("(?i)^NUMBER\\(\\d+,$")) {
            return "NUMERIC";
        }
        if (upper.equals("FLOAT") || upper.equals("FLOAT(63)")) return "DOUBLE PRECISION";
        if (upper.equals("BINARY_FLOAT")) return "REAL";
        if (upper.equals("BINARY_DOUBLE")) return "DOUBLE PRECISION";
        if (upper.equals("VARCHAR2") || upper.equals("VARCHAR")) return "TEXT";
        if (upper.matches("(?i)^VARCHAR2?\\(.*")) return "TEXT";
        if (upper.equals("CHAR") || upper.equals("CHAR(1)")) return "CHAR(1)";
        if (upper.equals("CLOB") || upper.equals("NCLOB")) return "TEXT";
        if (upper.equals("BLOB") || upper.equals("RAW")) return "BYTEA";
        if (upper.equals("DATE")) return "TIMESTAMP";
        if (upper.equals("TIMESTAMP(6)")) return "TIMESTAMP";
        if (upper.startsWith("TIMESTAMP")) return "TIMESTAMP";
        if (upper.equals("BOOLEAN")) return "BOOLEAN";

        // Không rõ → giữ nguyên (PostgreSQL có thể hiểu nếu là built-in type)
        return oracleType;
    }
}
