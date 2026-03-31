package org.example.migrationdbweb.migratetool;

import java.util.List;

public interface SqlDialect {

    String mapDataType(ColumnDefinition columnDefinition);

    String buildCreateTableSql(TableDefinition tableDefinition);
    List<String> buildAddForeignKeySql(TableDefinition table);

    // ─── SEQUENCE DDL ───────────────────────────────────────────

    /**
     * Build câu lệnh CREATE SEQUENCE cho target database.
     * Chỉ tạo sequence definition, KHÔNG reset giá trị.
     *
     * @param seq SequenceDefinition từ source
     * @return câu lệnh SQL, ví dụ: CREATE SEQUENCE emp_seq START WITH 1 INCREMENT BY 1 ...
     */
    default String buildCreateSequenceSql(SequenceDefinition seq) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE SEQUENCE ");
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
        if (seq.getCacheSize() != null && seq.getCacheSize() > 1) {
            sb.append(" CACHE ").append(seq.getCacheSize());
        } else {
            sb.append(" NOCACHE");
        }
        sb.append(seq.isCycle() ? " CYCLE" : " NO CYCLE");
        return sb.toString();
    }

    /**
     * Build câu lệnh ALTER SEQUENCE ... RESTART để đặt lại giá trị sequence.
     * Dùng sau khi migrate data để tránh conflict.
     *
     * @param seq SequenceDefinition (source)
     * @param newStart giá trị bắt đầu mới (thường = giá trị MAX(pk) + increment)
     * @return câu lệnh SQL, ví dụ: ALTER SEQUENCE emp_seq RESTART WITH 1000
     */
    default String buildAlterSequenceRestartSql(SequenceDefinition seq, long newStart) {
        return "ALTER SEQUENCE "
                + quoteIdentifier(seq.getSequenceName())
                + " RESTART WITH " + newStart;
    }

    // ─── INDEX DDL ──────────────────────────────────────────────

    /**
     * Build câu lệnh CREATE INDEX cho target database.
     *
     * @param idx IndexDefinition từ source
     * @return câu lệnh SQL, ví dụ: CREATE INDEX idx ON tbl (col1, col2)
     */
    default List<String> buildCreateIndexSql(IndexDefinition idx) {
        List<String> stmts = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();

        if (idx.isExpressionIndex()) {
            // Expression index: chỉ build nếu expression có thể chuyển đổi được
            // (PostgreSQL expression index tương đương Oracle function-based index)
            sb.append("CREATE INDEX ");
            if (idx.isUnique()) sb.append("UNIQUE INDEX ");
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
        sb.append(" (");

        List<String> cols = idx.getColumns();
        List<String> descs = idx.getDescendings();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdentifier(cols.get(i)));
            if (descs != null && i < descs.size() && "DESC".equalsIgnoreCase(descs.get(i))) {
                sb.append(" DESC");
            } else {
                sb.append(" ASC");
            }
        }
        sb.append(")");
        stmts.add(sb.toString());
        return stmts;
    }

    /**
     * Kiểm tra index có hỗ trợ trên dialect hiện tại hay không.
     * Ví dụ: BITMAP index chỉ có Oracle, không có PostgreSQL.
     *
     * @param idx IndexDefinition
     * @return true nếu dialect target hỗ trợ loại index này
     */
    default boolean isIndexSupported(IndexDefinition idx) {
        return idx != null && idx.isMigratable();
    }

    // ─── FUNCTION / PROCEDURE DDL ─────────────────────────

    /**
     * Build câu lệnh CREATE FUNCTION hoặc CREATE PROCEDURE cho target database.
     *
     * @param fn FunctionDefinition
     * @param transformer OracleToPgsqlTransformer (null nếu không cần transform)
     * @return list DDL statements
     */
    default List<String> buildCreateFunctionSql(
            FunctionDefinition fn,
            OracleToPgsqlTransformer transformer
    ) {
        List<String> stmts = new java.util.ArrayList<>();
        if (fn == null) return stmts;
        return stmts;
    }

    // ─── TRIGGER DDL ──────────────────────────────────────────

    /**
     * Build câu lệnh CREATE TRIGGER (và function nếu cần) cho target database.
     *
     * Với Oracle → PostgreSQL: tách body thành FUNCTION + tạo TRIGGER.
     * Với Oracle → Oracle: giữ nguyên DDL gốc.
     * Với PostgreSQL → PostgreSQL: giữ nguyên.
     *
     * @param trig TriggerDefinition
     * @param transformer OracleToPgsqlTransformer (null nếu không cần transform)
     * @return list DDL statements cần execute, ví dụ:
     *         [CREATE OR REPLACE FUNCTION f() RETURNS trigger AS $BODY$...$BODY$ LANGUAGE plpgsql;,
     *          CREATE TRIGGER t1 BEFORE INSERT ON tbl FOR EACH ROW EXECUTE FUNCTION f();]
     */
    default List<String> buildCreateTriggerSql(
            TriggerDefinition trig,
            OracleToPgsqlTransformer transformer
    ) {
        List<String> stmts = new java.util.ArrayList<>();
        if (trig == null) return stmts;

        // Subclass sẽ override method này
        return stmts;
    }

    // ─── EXISTING VIEW METHOD ──────────────────────────────────

    /**
     * Build câu lệnh CREATE VIEW hoặc CREATE OR REPLACE VIEW.
     *
     * @param viewDef ViewDefinition chứa tên và selectClause
     * @return câu lệnh SQL đầy đủ, ví dụ:
     *         CREATE OR REPLACE VIEW "my_view" AS SELECT ...
     */
    default String buildCreateViewSql(ViewDefinition viewDef) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE OR REPLACE VIEW ");
        sql.append(quoteIdentifier(viewDef.getViewName()));
        sql.append(" AS\n");
        sql.append(viewDef.getSelectClause());

        if (viewDef.getCheckOption() != null && !viewDef.getCheckOption().isBlank()) {
            sql.append("\nWITH ").append(viewDef.getCheckOption()).append(" CHECK OPTION");
        }

        return sql.toString();
    }

    default String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";

    }
}
