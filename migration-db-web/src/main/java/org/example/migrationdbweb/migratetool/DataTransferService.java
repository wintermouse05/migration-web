package org.example.migrationdbweb.migratetool;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DataTransferService {

    @FunctionalInterface
    public interface TransferProgressListener {
        void onBatchCommitted(String tableName, int justTransferred, int totalTransferred, int totalSkipped);
    }

    public static final class TransferResult {
        private final int startOffset;
        private final int transferredRows;
        private final int skippedRows;
        private final boolean limitReached;

        public TransferResult(int startOffset, int transferredRows, int skippedRows, boolean limitReached) {
            this.startOffset = startOffset;
            this.transferredRows = transferredRows;
            this.skippedRows = skippedRows;
            this.limitReached = limitReached;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getTransferredRows() {
            return transferredRows;
        }

        public int getSkippedRows() {
            return skippedRows;
        }

        public boolean isLimitReached() {
            return limitReached;
        }
    }

    private final SqlGenerator sqlGenerator;
    private final MigrationRetryPolicy retryPolicy;

    public DataTransferService(SqlGenerator sqlGenerator) {
        this.sqlGenerator = sqlGenerator;
        this.retryPolicy = MigrationRetryPolicy.fromEnvironment();
    }

    /**
     * Constructor that accepts a custom retry policy (used by web service flow).
     */
    public DataTransferService(SqlGenerator sqlGenerator, MigrationRetryPolicy retryPolicy) {
        this.sqlGenerator = sqlGenerator;
        this.retryPolicy = retryPolicy != null ? retryPolicy
                : MigrationRetryPolicy.fromEnvironment();
    }

    /**
     * Transfer table data from source DB to target DB.
     * @param sourceConn source DB connection
     * @param targetConn target DB connection
     * @param table table definition
     * @param batchSize batch size (for example: 1000, 5000)
     */
    public void transferTableData(Connection sourceConn, Connection targetConn, TableDefinition table, int batchSize) throws SQLException {
        transferTableData(sourceConn, targetConn, table, batchSize, null, false, 0, null);
    }

    public TransferResult transferTableData(
            Connection sourceConn,
            Connection targetConn,
            TableDefinition table,
            int batchSize,
            Integer limitRows,
            boolean copyNewOnly,
            TransferProgressListener progressListener
    ) throws SQLException {
        return transferTableData(
                sourceConn,
                targetConn,
                table,
                batchSize,
                limitRows,
                copyNewOnly,
                0,
                progressListener
        );
    }

    public TransferResult transferTableData(
            Connection sourceConn,
            Connection targetConn,
            TableDefinition table,
            int batchSize,
            Integer limitRows,
            boolean copyNewOnly,
            int startOffset,
            TransferProgressListener progressListener
    ) throws SQLException {
            return transferTableDataInternal(
                sourceConn,
                targetConn,
                table,
                batchSize,
                limitRows,
                copyNewOnly,
                startOffset,
                progressListener,
                true
            );
            }

            private TransferResult transferTableDataInternal(
                Connection sourceConn,
                Connection targetConn,
                TableDefinition table,
                int batchSize,
                Integer limitRows,
                boolean copyNewOnly,
                int startOffset,
                TransferProgressListener progressListener,
                boolean allowDuplicateFallback
            ) throws SQLException {

        int safeStartOffset = Math.max(0, startOffset);
        boolean hasPrimaryKey = !table.getPrimaryKeys().isEmpty();
        if (safeStartOffset > 0 && !hasPrimaryKey) {
            System.out.println("Table " + table.getTableName()
                + " has no PK; cannot safely resume by offset. Restarting from row 0.");
            safeStartOffset = 0;
        }

        // Luon ORDER BY PK neu bang co PK de thu tu doc on dinh giua cac lan retry/resume.
        String selectSql = sqlGenerator.buildSelectSql(table, hasPrimaryKey);
        String insertSql = sqlGenerator.buildInsertSql(table);
        String existsByPkSql = null;
        int[] pkColumnIndexes = new int[0];

        if (copyNewOnly && !table.getPrimaryKeys().isEmpty()) {
            existsByPkSql = sqlGenerator.buildExistsByPrimaryKeySql(table);
            pkColumnIndexes = resolvePkIndexes(table);
        }

        int columnCount = table.getColumns().size();
        int safeBatchSize = Math.max(1, batchSize);
        Integer safeLimitRows = (limitRows != null && limitRows > 0) ? limitRows : null;

        // Tắt AutoCommit ềEcả hai phía đềEtối ưu hiệu suất và bật Streaming
        boolean originalSourceAutoCommit = sourceConn.getAutoCommit();
        boolean originalTargetAutoCommit = targetConn.getAutoCommit();
        sourceConn.setAutoCommit(false);
        targetConn.setAutoCommit(false);

        System.out.println("Starting data transfer for table: " + table.getTableName());

        // Sử dụng TYPE_FORWARD_ONLY và CONCUR_READ_ONLY đềEtối ưu hóa bềEnhềEkhi đọc
        try (Statement sourceStmt = sourceConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement targetPstmt = targetConn.prepareStatement(insertSql);
             PreparedStatement existsByPkStmt = existsByPkSql == null ? null : targetConn.prepareStatement(existsByPkSql)) {

            // Cấu hình Fetch Size: Số lượng row tải về RAM mỗi lần (Tránh OOM).
            // Fetch Size NHỎ HƠN batch size — fetch về ít rows để giảm memory Oracle server,
            // trong khi batch INSERT vẫn giữ batchSize lớn để tối ưu throughput.
            // Oracle JDBC mặc định fetchSize = 10, giá trị hợp lý: 100–500.
            int fetchSize = Math.min(500, Math.max(100, safeBatchSize / 10));
            sourceStmt.setFetchSize(fetchSize);

            try (ResultSet rs = sourceStmt.executeQuery(selectSql)) {
                int totalTransferred = safeStartOffset;
                int totalSkipped = 0;
                int currentBatchCount = 0;
                boolean limitReached = false;
                int sourceRowsRead = 0;
                List<Object[]> pendingBatchRows = new ArrayList<>(safeBatchSize);

                int skippedByOffset = 0;
                while (skippedByOffset < safeStartOffset && rs.next()) {
                    skippedByOffset++;
                }
                if (skippedByOffset > 0) {
                    System.out.println("Resuming table " + table.getTableName() + ": skipped " + skippedByOffset + " already-committed rows.");
                }

                while (rs.next()) {
                    sourceRowsRead++;
                    if (safeLimitRows != null && totalTransferred >= safeLimitRows) {
                        limitReached = true;
                        break;
                    }

                    if (existsByPkStmt != null && rowExistsByPrimaryKey(rs, table, existsByPkStmt, pkColumnIndexes)) {
                        // Resume offset is persisted from totalTransferred, so skipped rows
                        // must also advance this counter to keep offset aligned.
                        totalTransferred++;
                        totalSkipped++;
                        continue;
                    }

                    // Đọc từng cột và gán vào tham sềEcủa INSERT
                    Object[] rowValues = new Object[columnCount];
                    for (int i = 1; i <= columnCount; i++) {
                        ColumnDefinition column = table.getColumns().get(i - 1);
                        int jdbcType = normalizeJdbcTypeForTarget(column.getJdbcType());

                        Object value = rs.getObject(i);
                        rowValues[i - 1] = value;
                        if (value == null) {
                            setNullSafely(targetPstmt, i, jdbcType);
                            continue;
                        }

                        bindValueSafely(targetPstmt, i, value, jdbcType, column, table.getTableName());
                    }

                    // Đưa lệnh INSERT đã được gán giá trềEvào danh sách chềE(Batch)
                    targetPstmt.addBatch();
                    pendingBatchRows.add(rowValues);
                    currentBatchCount++;
                    totalTransferred++;

                    // Khi Batch đầy, tiến hành gửi sang DB đích và Commit
                    if (currentBatchCount % safeBatchSize == 0) {
                        executeBatchWithRetry(targetPstmt, targetConn, table.getTableName(), pendingBatchRows, table);
                        if (progressListener != null) {
                            progressListener.onBatchCommitted(table.getTableName(), currentBatchCount, totalTransferred, totalSkipped);
                        }
                        currentBatchCount = 0;
                        pendingBatchRows.clear();
                        System.out.println("  -> Copied " + totalTransferred + " rows...");
                    }
                }

                // Thực thi nốt những dòng còn dư cuối cùng (nếu sềEdòng không chia hết cho batchSize)
                if (currentBatchCount > 0) {
                    executeBatchWithRetry(targetPstmt, targetConn, table.getTableName(), pendingBatchRows, table);
                    if (progressListener != null) {
                        progressListener.onBatchCommitted(table.getTableName(), currentBatchCount, totalTransferred, totalSkipped);
                    }
                    pendingBatchRows.clear();
                    System.out.println("  -> Copied " + totalTransferred + " rows...");
                }

                if (sourceRowsRead == 0) {
                    System.err.println("[DATA-WARN] Table " + table.getTableName()
                            + " returned no rows from source query."
                            + " Check schema/DB name and source data. SQL=" + selectSql);
                }

                System.out.println("Completed. Total rows copied: " + totalTransferred + " for table " + table.getTableName());
                return new TransferResult(safeStartOffset, totalTransferred, totalSkipped, limitReached);
            }

        } catch (SQLException e) {
            // Rollback nếu có lỗi xảy ra đềEđảm bảo tính toàn vẹn dữ liệu
            targetConn.rollback();

            if (allowDuplicateFallback
                && !copyNewOnly
                && !table.getPrimaryKeys().isEmpty()
                && isDuplicateKeyViolation(e)) {
            System.err.println("[DATA-WARN] Table " + table.getTableName()
                + " hit duplicate key errors (for example ORA-00001)."
                + " Retrying with copyNewOnly=true to skip existing PK rows.");
            return transferTableDataInternal(
                sourceConn,
                targetConn,
                table,
                batchSize,
                limitRows,
                true,
                startOffset,
                progressListener,
                false
            );
            }

            System.err.println("Error while transferring table " + table.getTableName()
                    + ". Rolled back transaction. SQLState=" + e.getSQLState()
                    + ", ErrorCode=" + e.getErrorCode()
                    + ", Message=" + e.getMessage()
                    + ", Detail=" + buildSqlExceptionDetail(e));
            throw e;
        } finally {
            // Khôi phục trạng thái ban đầu
            sourceConn.setAutoCommit(originalSourceAutoCommit);
            targetConn.setAutoCommit(originalTargetAutoCommit);
        }
    }

    private static boolean isDuplicateKeyViolation(SQLException e) {
        SQLException current = e;
        while (current != null) {
            String sqlState = current.getSQLState();
            int errorCode = current.getErrorCode();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();

            if ("23505".equals(sqlState)
                    || "23000".equals(sqlState)
                    || errorCode == 1
                    || message.contains("ora-00001")
                    || (message.contains("unique") && message.contains("constraint"))
                    || message.contains("duplicate key")) {
                return true;
            }

            SQLException next = current.getNextException();
            if (next == null && current.getCause() instanceof SQLException causeSql) {
                next = causeSql;
            }
            current = next;
        }
        return false;
    }

    private void executeBatchWithRetry(
            PreparedStatement targetPstmt,
            Connection targetConn,
            String tableName,
            List<Object[]> pendingBatchRows,
            TableDefinition table
    ) throws SQLException {
        int attempts = retryPolicy.resolveAttempts();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                targetPstmt.executeBatch();
                targetConn.commit();
                targetPstmt.clearBatch();
                return;
            } catch (SQLException e) {
                targetConn.rollback();
                if (!isRetryableException(e) || attempt == attempts) {
                    String detail = buildSqlExceptionDetail(e);
                    if (isValueTooLargeException(e)) {
                        detail += " | Hint: ORA-12899 usually means target column length is smaller than source value"
                                + " or Oracle BYTE/CHAR semantics mismatch."
                                + " Recreate/alter target columns to match source definition before re-run.";
                    }
                    throw new SQLException(
                        "Unable to execute batch for table " + tableName + " after " + attempt + " attempts. Details: " + detail,
                            e
                    );
                }

                // Rebuild this failed batch explicitly so retry executes the same rows.
                targetPstmt.clearBatch();
                rebindBatchRows(targetPstmt, pendingBatchRows, table);

                long delayMs = retryPolicy.delayForAttempt(attempt);
                System.err.println("Transient batch error on table " + tableName + " attempt " + attempt + "/" + attempts
                        + ", retry in " + delayMs + " ms. Reason: " + buildSqlExceptionDetail(e));
                sleep(delayMs);
            }
        }
    }

    private static void rebindBatchRows(
            PreparedStatement targetPstmt,
            List<Object[]> pendingBatchRows,
            TableDefinition table
    ) throws SQLException {
        int columnCount = table.getColumns().size();
        for (Object[] rowValues : pendingBatchRows) {
            if (rowValues == null || rowValues.length != columnCount) {
                throw new SQLException("Cannot rebuild failed batch for table " + table.getTableName()
                        + ": row buffer is invalid.");
            }

            for (int i = 1; i <= columnCount; i++) {
                ColumnDefinition column = table.getColumns().get(i - 1);
                int jdbcType = normalizeJdbcTypeForTarget(column.getJdbcType());
                Object value = rowValues[i - 1];

                if (value == null) {
                    setNullSafely(targetPstmt, i, jdbcType);
                } else {
                    bindValueSafely(targetPstmt, i, value, jdbcType, column, table.getTableName());
                }
            }

            targetPstmt.addBatch();
        }
    }

    private static String buildSqlExceptionDetail(SQLException e) {
        if (e == null) {
            return "(no error details)";
        }

        StringBuilder sb = new StringBuilder();
        SQLException current = e;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) {
                sb.append(" | ");
            }
            sb.append("[")
                    .append(current.getClass().getSimpleName())
                    .append("] SQLState=")
                    .append(current.getSQLState())
                    .append(", ErrorCode=")
                    .append(current.getErrorCode())
                    .append(", Message=")
                    .append(current.getMessage());

            if (current instanceof BatchUpdateException bue) {
                int[] counts = bue.getUpdateCounts();
                sb.append(", UpdateCounts=");
                if (counts == null) {
                    sb.append("null");
                } else {
                    sb.append("[");
                    for (int i = 0; i < counts.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(counts[i]);
                    }
                    sb.append("]");
                }
            }

            SQLException next = current.getNextException();
            if (next == null && current.getCause() instanceof SQLException causeSql) {
                next = causeSql;
            }
            current = next;
            depth++;
        }

        return sb.toString();
    }

    private static boolean isRetryableException(SQLException e) {
        if (e == null) {
            return false;
        }

        String sqlState = e.getSQLState();
        if (sqlState != null) {
            if (sqlState.startsWith("08")
                    || "40001".equals(sqlState)
                    || "40P01".equals(sqlState)
                    || "HYT00".equals(sqlState)
                    || "HYT01".equals(sqlState)) {
                return true;
            }
        }

        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        String lowered = message.toLowerCase();
        return lowered.contains("connection")
                || lowered.contains("socket")
                || lowered.contains("timed out")
                || lowered.contains("timeout")
                || lowered.contains("broken pipe")
                || lowered.contains("i/o error")
                || lowered.contains("communications link failure");
    }

    private static boolean isValueTooLargeException(SQLException e) {
        SQLException current = e;
        while (current != null) {
            if (current.getErrorCode() == 12899) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("ora-12899")) {
                return true;
            }

            SQLException next = current.getNextException();
            if (next == null && current.getCause() instanceof SQLException causeSql) {
                next = causeSql;
            }
            current = next;
        }
        return false;
    }

    private static void sleep(long millis) throws SQLException {
        if (millis <= 0) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Batch retry process was interrupted.", e);
        }
    }

    private static boolean rowExistsByPrimaryKey(
            ResultSet sourceRs,
            TableDefinition table,
            PreparedStatement existsByPkStmt,
            int[] pkColumnIndexes
    ) throws SQLException {
        List<String> primaryKeys = table.getPrimaryKeys();
        for (int i = 0; i < primaryKeys.size(); i++) {
            int sourceColumnIndex = pkColumnIndexes[i];
            ColumnDefinition sourceColumn = table.getColumns().get(sourceColumnIndex - 1);
            int jdbcType = normalizeJdbcTypeForTarget(sourceColumn.getJdbcType());

            Object value = sourceRs.getObject(sourceColumnIndex);
            if (value == null) {
                existsByPkStmt.setNull(i + 1, jdbcType);
            } else {
                existsByPkStmt.setObject(i + 1, value, jdbcType);
            }
        }

        try (ResultSet checkRs = existsByPkStmt.executeQuery()) {
            return checkRs.next();
        }
    }

    private static int[] resolvePkIndexes(TableDefinition table) {
        List<String> primaryKeys = table.getPrimaryKeys();
        List<ColumnDefinition> columns = table.getColumns();
        int[] indexes = new int[primaryKeys.size()];

        for (int i = 0; i < primaryKeys.size(); i++) {
            String pkName = primaryKeys.get(i);
            int foundIndex = -1;
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(pkName)) {
                    foundIndex = j + 1;
                    break;
                }
            }

            if (foundIndex == -1) {
                throw new IllegalArgumentException(
                        "Cannot find PK column " + pkName + " in table " + table.getTableName()
                );
            }

            indexes[i] = foundIndex;
        }

        return indexes;
    }

    private static int normalizeJdbcTypeForTarget(int jdbcType) {
        if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
            return Types.TIMESTAMP;
        }
        if (jdbcType == Types.TIME_WITH_TIMEZONE) {
            return Types.TIME;
        }
        return jdbcType;
    }

    private static void setNullSafely(PreparedStatement pstmt, int index, int jdbcType) throws SQLException {
        try {
            pstmt.setNull(index, jdbcType);
        } catch (SQLException ex) {
            // Oracle thường không chấp nhận setNull với kiểu OTHER/JAVA_OBJECT.
            if (jdbcType == Types.OTHER || jdbcType == Types.JAVA_OBJECT || jdbcType == Types.SQLXML) {
                pstmt.setNull(index, Types.VARCHAR);
                return;
            }
            throw ex;
        }
    }

    private static void bindValueSafely(
            PreparedStatement pstmt,
            int index,
            Object value,
            int jdbcType,
            ColumnDefinition column,
            String tableName
    ) throws SQLException {
        switch (jdbcType) {
            case Types.TIMESTAMP -> pstmt.setTimestamp(index, toTimestamp(value));
            case Types.DATE -> pstmt.setDate(index, toSqlDate(value));
            case Types.TIME -> pstmt.setTime(index, toSqlTime(value));
            case Types.BOOLEAN, Types.BIT -> {
                if (value instanceof Boolean b) {
                    // Oracle target thường map BOOLEAN sang NUMBER(1)
                    pstmt.setInt(index, b ? 1 : 0);
                } else {
                    pstmt.setObject(index, value, jdbcType);
                }
            }
            default -> {
                try {
                    // Ưu tiên bind với JDBC type để tránh Oracle driver object mismatch.
                    pstmt.setObject(index, value, jdbcType);
                } catch (SQLException ex) {
                    bindWithFallback(pstmt, index, value, jdbcType, column, tableName, ex);
                }
            }
        }
    }

    private static void bindWithFallback(
            PreparedStatement pstmt,
            int index,
            Object value,
            int jdbcType,
            ColumnDefinition column,
            String tableName,
            SQLException cause
    ) throws SQLException {
        // PostgreSQL-specific object types (json/jsonb/uuid/array/domain) thường đi qua JDBC OTHER.
        if (jdbcType == Types.OTHER || jdbcType == Types.JAVA_OBJECT || isPgObject(value) || value instanceof UUID) {
            pstmt.setString(index, value.toString());
            return;
        }

        if (value instanceof OffsetDateTime odt) {
            pstmt.setTimestamp(index, Timestamp.from(odt.toInstant()));
            return;
        }
        if (value instanceof ZonedDateTime zdt) {
            pstmt.setTimestamp(index, Timestamp.from(zdt.toInstant()));
            return;
        }
        if (value instanceof Instant instant) {
            pstmt.setTimestamp(index, Timestamp.from(instant));
            return;
        }
        if (value instanceof LocalDateTime ldt) {
            pstmt.setTimestamp(index, Timestamp.valueOf(ldt));
            return;
        }
        if (value instanceof LocalDate ld) {
            pstmt.setDate(index, Date.valueOf(ld));
            return;
        }
        if (value instanceof LocalTime lt) {
            pstmt.setTime(index, Time.valueOf(lt));
            return;
        }
        if (value instanceof Boolean b) {
            pstmt.setInt(index, b ? 1 : 0);
            return;
        }

        // Last resort: let driver infer type.
        if (isUnsupportedTypedBinding(cause)) {
            pstmt.setObject(index, value);
            return;
        }

        throw new SQLException(
            "Unable to bind value for table " + tableName
                + ", column " + column.getName()
                + ", jdbcType=" + jdbcType
                + ", valueClass=" + (value == null ? "null" : value.getClass().getName())
                + ". Error: " + cause.getMessage(),
            cause
        );
    }

    private static boolean isUnsupportedTypedBinding(SQLException e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("invalid column type")
                || message.contains("unsupported feature")
                || message.contains("ora-17004");
    }

    private static boolean isPgObject(Object value) {
        return value != null && "org.postgresql.util.PGobject".equals(value.getClass().getName());
    }

    private static Timestamp toTimestamp(Object value) throws SQLException {
        if (value instanceof Timestamp ts) return ts;
        if (value instanceof java.util.Date d) return new Timestamp(d.getTime());
        if (value instanceof OffsetDateTime odt) return Timestamp.from(odt.toInstant());
        if (value instanceof ZonedDateTime zdt) return Timestamp.from(zdt.toInstant());
        if (value instanceof Instant instant) return Timestamp.from(instant);
        if (value instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
        if (isOracleSqlType(value)) {
            Timestamp ts = invokeTemporalGetter(value, "timestampValue", Timestamp.class);
            if (ts != null) return ts;

            Date d = invokeTemporalGetter(value, "dateValue", Date.class);
            if (d != null) return new Timestamp(d.getTime());

            Time t = invokeTemporalGetter(value, "timeValue", Time.class);
            if (t != null) return new Timestamp(t.getTime());
        }

        if (value instanceof CharSequence cs) {
            String raw = cs.toString().trim();
            String normalized = raw.replace('T', ' ');
            try {
                return Timestamp.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                // keep falling through to detailed error below
            }
        }
        throw new SQLException("Cannot convert to TIMESTAMP: " + (value == null ? "null" : value.getClass().getName()));
    }

    private static Date toSqlDate(Object value) throws SQLException {
        if (value instanceof Date d) return d;
        if (value instanceof java.util.Date d) return new Date(d.getTime());
        if (value instanceof LocalDate ld) return Date.valueOf(ld);
        if (value instanceof Timestamp ts) return new Date(ts.getTime());
        if (isOracleSqlType(value)) {
            Date d = invokeTemporalGetter(value, "dateValue", Date.class);
            if (d != null) return d;

            Timestamp ts = invokeTemporalGetter(value, "timestampValue", Timestamp.class);
            if (ts != null) return new Date(ts.getTime());
        }
        throw new SQLException("Cannot convert to DATE: " + (value == null ? "null" : value.getClass().getName()));
    }

    private static Time toSqlTime(Object value) throws SQLException {
        if (value instanceof Time t) return t;
        if (value instanceof LocalTime lt) return Time.valueOf(lt);
        if (value instanceof java.util.Date d) return new Time(d.getTime());
        if (isOracleSqlType(value)) {
            Time t = invokeTemporalGetter(value, "timeValue", Time.class);
            if (t != null) return t;

            Timestamp ts = invokeTemporalGetter(value, "timestampValue", Timestamp.class);
            if (ts != null) return new Time(ts.getTime());
        }
        throw new SQLException("Cannot convert to TIME: " + (value == null ? "null" : value.getClass().getName()));
    }

    private static boolean isOracleSqlType(Object value) {
        if (value == null) {
            return false;
        }
        String className = value.getClass().getName();
        return className.startsWith("oracle.sql.") || className.startsWith("oracle.jdbc.");
    }

    private static <T> T invokeTemporalGetter(Object value, String methodName, Class<T> returnType) throws SQLException {
        try {
            Object result = value.getClass().getMethod(methodName).invoke(value);
            if (result == null) {
                return null;
            }
            if (returnType.isInstance(result)) {
                return returnType.cast(result);
            }
            return null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new SQLException(
                    "Cannot read temporal value via reflection method " + methodName
                            + " from " + value.getClass().getName(),
                    e
            );
        }
    }
}
