package org.example.migrationdbweb.migratetool;

import java.util.List;

public interface SqlDialect {

    String mapDataType(ColumnDefinition columnDefinition);

    String buildCreateTableSql(TableDefinition tableDefinition);
    List<String> buildAddForeignKeySql(TableDefinition table);

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
