package org.example.migrationdbweb.migratetool;

import java.util.List;
import java.util.stream.Collectors;

public class SqlGenerator {

    private final SqlDialect sourceDialect;
    private final SqlDialect targetDialect;

    public SqlGenerator(SqlDialect sourceDialect, SqlDialect targetDialect) {
        this.sourceDialect = sourceDialect;
        this.targetDialect = targetDialect;
    }

    // Tạo lệnh SELECT từ Source
    public String buildSelectSql(TableDefinition table) {
        return buildSelectSql(table, false);
    }

    public String buildSelectSql(TableDefinition table, boolean orderByPrimaryKey) {
        String columns = table.getColumns().stream()
                .map(c -> sourceDialect.quoteIdentifier(c.getName()))
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ")
                .append(columns)
                .append(" FROM ")
                .append(sourceDialect.quoteIdentifier(table.getTableName()));

        if (orderByPrimaryKey && !table.getPrimaryKeys().isEmpty()) {
            String orderBy = table.getPrimaryKeys().stream()
                    .map(sourceDialect::quoteIdentifier)
                    .collect(Collectors.joining(", "));
            sql.append(" ORDER BY ").append(orderBy);
        }

        return sql.toString();
    }

    // Tạo lệnh INSERT dạng PreparedStatement cho Target
    public String buildInsertSql(TableDefinition table) {
        List<ColumnDefinition> columns = table.getColumns();

        String colNames = columns.stream()
                .map(c -> targetDialect.quoteIdentifier(c.getName()))
                .collect(Collectors.joining(", "));

        String placeholders = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ")
            .append(targetDialect.quoteIdentifier(table.getTableName()))
            .append(" (")
            .append(colNames)
            .append(") VALUES (")
            .append(placeholders)
            .append(")");

        // Chỉ thêm ON CONFLICT DO NOTHING khi KHÔNG có copyNewOnly.
        // Khi copyNewOnly=true: DataTransferService đã kiểm tra PK trước mỗi INSERT
        // → ON CONFLICT DO NOTHING không bao giờ trigger (vì row đã tồn tại bị skip).
        // Khi copyNewOnly=false: không kiểm tra trùng → ON CONFLICT DO NOTHING
        //   không cần thiết (vì bản ghi mới không thể conflict).
        // Do đó, ON CONFLICT DO NOTHING luôn luôn dư thừa ở đây.
        // Giữ lại chỉ khi cần đảm bảo idempotent trong trường hợp copyNewOnly=false
        // và user muốn re-run mà không lỗi trùng key.
        if (targetDialect instanceof PostgresDialect && !table.getPrimaryKeys().isEmpty()) {
            String conflictColumns = table.getPrimaryKeys().stream()
                .map(targetDialect::quoteIdentifier)
                .collect(Collectors.joining(", "));
            sql.append(" ON CONFLICT (")
                .append(conflictColumns)
                .append(") DO NOTHING");
        }

        return sql.toString();
    }

    public String buildExistsByPrimaryKeySql(TableDefinition table) {
        if (table.getPrimaryKeys().isEmpty()) {
            throw new IllegalArgumentException("Table " + table.getTableName() + " không có PK đềEkiểm tra tồn tại.");
        }

        String whereClause = table.getPrimaryKeys().stream()
                .map(pk -> targetDialect.quoteIdentifier(pk) + " = ?")
                .collect(Collectors.joining(" AND "));

        return "SELECT 1 FROM "
                + targetDialect.quoteIdentifier(table.getTableName())
                + " WHERE "
                + whereClause;
    }
}
