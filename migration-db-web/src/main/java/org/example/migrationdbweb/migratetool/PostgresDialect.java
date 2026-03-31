package org.example.migrationdbweb.migratetool;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PostgresDialect implements SqlDialect {
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
        appendIndexColumns(sb, idx);
        stmts.add(sb.toString());
        return stmts;
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
        return stmts;
    }

    // ─── FUNCTION / PROCEDURE DDL ──────────────────────────────

    @Override
    public List<String> buildCreateFunctionSql(FunctionDefinition fn, OracleToPgsqlTransformer transformer) {
        List<String> stmts = new ArrayList<>();
        if (fn == null) return stmts;

        String functionBody;
        if (transformer != null && fn.getSourceDialect() == DatabaseType.ORACLE) {
            functionBody = transformer.transform(fn.getFunctionBody());
        } else {
            functionBody = fn.getFunctionBody();
        }

        StringBuilder sb = new StringBuilder();

        if (fn.isProcedure()) {
            sb.append("CREATE OR REPLACE PROCEDURE ");
        } else {
            sb.append("CREATE OR REPLACE FUNCTION ");
        }

        sb.append(quoteIdentifier(fn.getFunctionName()));
        sb.append("(");

        // Arguments
        List<FunctionDefinition.FunctionArgument> args = fn.getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            FunctionDefinition.FunctionArgument arg = args.get(i);
            if (arg.getName() != null && !arg.getName().isBlank()) {
                sb.append(arg.getName()).append(" ");
            }
            sb.append(arg.getDataType());
        }
        sb.append(")");

        if (fn.isFunction() && fn.getReturnType() != null) {
            sb.append(" RETURNS ").append(fn.getReturnType());
        }

        sb.append(" AS $$\n");
        sb.append(functionBody != null ? functionBody : "BEGIN NULL;\n");
        sb.append("\n$$ LANGUAGE plpgsql;");

        stmts.add(sb.toString());
        return stmts;
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
}
