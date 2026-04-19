package org.example.migrationdbweb.migratetool;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class DataTransferService {

    @FunctionalInterface
    public interface TransferProgressListener {
        void onBatchCommitted(String tableName, int justTransferred, int totalTransferred, int totalSkipped);
    }

    public static final class InsertSqlExportOptions {
        private final Path outputDirectory;
        private final int rowsPerFile;
        private final int commitEveryRows;
        private final boolean exportOnlyToFile;

        public InsertSqlExportOptions(Path outputDirectory, int rowsPerFile, int commitEveryRows) {
            this(outputDirectory, rowsPerFile, commitEveryRows, false);
        }

        public InsertSqlExportOptions(
                Path outputDirectory,
                int rowsPerFile,
                int commitEveryRows,
                boolean exportOnlyToFile
        ) {
            if (outputDirectory == null) {
                throw new IllegalArgumentException("Output directory must not be null.");
            }
            this.outputDirectory = outputDirectory;
            this.rowsPerFile = Math.max(1, rowsPerFile);
            this.commitEveryRows = Math.max(1, commitEveryRows);
            this.exportOnlyToFile = exportOnlyToFile;
        }

        public Path outputDirectory() {
            return outputDirectory;
        }

        public int rowsPerFile() {
            return rowsPerFile;
        }

        public int commitEveryRows() {
            return commitEveryRows;
        }

        public boolean exportOnlyToFile() {
            return exportOnlyToFile;
        }
    }

    public static final class TransferResult {
        private final int startOffset;
        private final int transferredRows;
        private final int skippedRows;
        private final boolean limitReached;
        private final List<String> exportedSqlFiles;

        public TransferResult(int startOffset, int transferredRows, int skippedRows, boolean limitReached) {
            this(startOffset, transferredRows, skippedRows, limitReached, List.of());
        }

        public TransferResult(
                int startOffset,
                int transferredRows,
                int skippedRows,
                boolean limitReached,
                List<String> exportedSqlFiles
        ) {
            this.startOffset = startOffset;
            this.transferredRows = transferredRows;
            this.skippedRows = skippedRows;
            this.limitReached = limitReached;
            this.exportedSqlFiles = exportedSqlFiles == null ? List.of() : List.copyOf(exportedSqlFiles);
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

        public List<String> getExportedSqlFiles() {
            return exportedSqlFiles;
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
        return transferTableData(
            sourceConn,
            targetConn,
            table,
            batchSize,
            limitRows,
            copyNewOnly,
            startOffset,
            null,
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
            InsertSqlExportOptions insertSqlExportOptions,
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
            insertSqlExportOptions,
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
                InsertSqlExportOptions insertSqlExportOptions,
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

        int safeBatchSize = Math.max(1, batchSize);
        Integer safeLimitRows = (limitRows != null && limitRows > 0) ? limitRows : null;
        boolean exportOnlyToFile = insertSqlExportOptions != null && insertSqlExportOptions.exportOnlyToFile();

        if (exportOnlyToFile) {
            return transferTableDataExportOnly(
                    sourceConn,
                    table,
                    safeBatchSize,
                    safeLimitRows,
                    safeStartOffset,
                    insertSqlExportOptions,
                    progressListener
            );
        }

        // Luon ORDER BY PK neu bang co PK de thu tu doc on dinh giua cac lan retry/resume.
        String selectSql = sqlGenerator.buildSelectSql(table, hasPrimaryKey);

        // MERGE/UPSERT optimization: khi copyNewOnly=true và target hỗ trợ MERGE (Oracle)
        // hoặc ON CONFLICT (PostgreSQL), dùng DB-native dedup thay vì per-row PK check.
        // Điều này loại bỏ N+1 SELECT queries (1 query/row → 0 queries).
        boolean useMergeOrUpsert = copyNewOnly && sqlGenerator.canUseMergeOrUpsert(table);
        String insertSql;
        String existsByPkSql = null;
        int[] pkColumnIndexes = new int[0];

        if (useMergeOrUpsert && sqlGenerator.getTargetDialect() instanceof OracleDialect) {
            // Oracle: dùng MERGE INTO ... WHEN NOT MATCHED THEN INSERT
            insertSql = sqlGenerator.buildMergeInsertSql(table);
        } else {
            // PostgreSQL: buildInsertSql() đã có ON CONFLICT DO NOTHING
            // Hoặc: copyNewOnly=false → INSERT bình thường
            insertSql = sqlGenerator.buildInsertSql(table);
        }

        // Fallback: nếu target không hỗ trợ MERGE/UPSERT, vẫn dùng per-row PK check
        if (copyNewOnly && !useMergeOrUpsert && !table.getPrimaryKeys().isEmpty()) {
            existsByPkSql = sqlGenerator.buildExistsByPrimaryKeySql(table);
            pkColumnIndexes = resolvePkIndexes(table);
        }

        int columnCount = table.getColumns().size();

        // Tắt AutoCommit ềEcả hai phía đềEtối ưu hiệu suất và bật Streaming
        boolean originalSourceAutoCommit = sourceConn.getAutoCommit();
        boolean originalTargetAutoCommit = targetConn.getAutoCommit();
        sourceConn.setAutoCommit(false);
        targetConn.setAutoCommit(false);

        System.out.println("Starting data transfer for table: " + table.getTableName()
                + (useMergeOrUpsert ? " [MERGE/UPSERT mode]" : ""));

        // Sử dụng TYPE_FORWARD_ONLY và CONCUR_READ_ONLY đềEtối ưu hóa bềEnhềEkhi đọc
           try (OracleInsertScriptWriter insertScriptWriter = createOracleInsertScriptWriter(
                    table,
                    safeStartOffset,
                    insertSqlExportOptions
               );
               Statement sourceStmt = sourceConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement targetPstmt = targetConn.prepareStatement(insertSql);
             PreparedStatement existsByPkStmt = existsByPkSql == null ? null : targetConn.prepareStatement(existsByPkSql)) {

            // Cấu hình Fetch Size = batchSize để 1 lần fetch đủ data cho 1 batch INSERT.
            // Giảm roundtrip đọc source: fetchSize cũ = batchSize/10 → 10 roundtrip/batch.
            // Cap tại 5000 để tránh memory pressure trên Oracle server.
            int fetchSize = Math.min(5000, Math.max(100, safeBatchSize));
            sourceStmt.setFetchSize(fetchSize);

            try (ResultSet rs = sourceStmt.executeQuery(selectSql)) {
                int totalTransferred = safeStartOffset;
                int totalSkipped = 0;
                int currentBatchCount = 0;
                boolean limitReached = false;
                int sourceRowsRead = 0;
                List<Object[]> pendingBatchRows = new ArrayList<>(safeBatchSize);
                List<String> pendingBatchSql = insertScriptWriter == null
                    ? Collections.emptyList()
                    : new ArrayList<>(safeBatchSize);

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

                    // Per-row PK check: chỉ dùng khi target không hỗ trợ MERGE/UPSERT.
                    // Khi useMergeOrUpsert=true, existsByPkStmt luôn null → skip block này.
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
                    if (insertScriptWriter != null) {
                        pendingBatchSql.add(buildOracleInsertSql(table, rowValues, useMergeOrUpsert));
                    }
                    currentBatchCount++;
                    totalTransferred++;

                    // Khi Batch đầy, tiến hành gửi sang DB đích và Commit
                    if (currentBatchCount % safeBatchSize == 0) {
                        executeBatchWithRetry(targetPstmt, targetConn, table.getTableName(), pendingBatchRows, table);
                        if (insertScriptWriter != null && !pendingBatchSql.isEmpty()) {
                            insertScriptWriter.appendCommittedInserts(pendingBatchSql);
                            pendingBatchSql.clear();
                        }
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
                    if (insertScriptWriter != null && !pendingBatchSql.isEmpty()) {
                        insertScriptWriter.appendCommittedInserts(pendingBatchSql);
                        pendingBatchSql.clear();
                    }
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
        List<String> exportedSqlFiles = insertScriptWriter == null
            ? List.of()
            : insertScriptWriter.getGeneratedFileNames();
        return new TransferResult(safeStartOffset, totalTransferred, totalSkipped, limitReached, exportedSqlFiles);
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
                insertSqlExportOptions,
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

    private TransferResult transferTableDataExportOnly(
            Connection sourceConn,
            TableDefinition table,
            int safeBatchSize,
            Integer safeLimitRows,
            int safeStartOffset,
            InsertSqlExportOptions insertSqlExportOptions,
            TransferProgressListener progressListener
    ) throws SQLException {
        boolean hasPrimaryKey = !table.getPrimaryKeys().isEmpty();
        String selectSql = sqlGenerator.buildSelectSql(table, hasPrimaryKey);
        int columnCount = table.getColumns().size();

        boolean originalSourceAutoCommit = sourceConn.getAutoCommit();
        sourceConn.setAutoCommit(false);

        System.out.println("Starting export-only SQL generation for table: " + table.getTableName());

        try (OracleInsertScriptWriter insertScriptWriter = createOracleInsertScriptWriter(
                    table,
                    safeStartOffset,
                    insertSqlExportOptions
             );
             Statement sourceStmt = sourceConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            int fetchSize = Math.min(5000, Math.max(100, safeBatchSize));
            sourceStmt.setFetchSize(fetchSize);

            try (ResultSet rs = sourceStmt.executeQuery(selectSql)) {
                int totalTransferred = safeStartOffset;
                int totalSkipped = 0;
                int currentBatchCount = 0;
                boolean limitReached = false;
                int sourceRowsRead = 0;
                List<String> pendingBatchSql = new ArrayList<>(safeBatchSize);

                int skippedByOffset = 0;
                while (skippedByOffset < safeStartOffset && rs.next()) {
                    skippedByOffset++;
                }
                if (skippedByOffset > 0) {
                    System.out.println("Resuming table " + table.getTableName() + ": skipped " + skippedByOffset + " already-exported rows.");
                }

                while (rs.next()) {
                    sourceRowsRead++;
                    if (safeLimitRows != null && totalTransferred >= safeLimitRows) {
                        limitReached = true;
                        break;
                    }

                    Object[] rowValues = new Object[columnCount];
                    for (int i = 1; i <= columnCount; i++) {
                        rowValues[i - 1] = rs.getObject(i);
                    }

                    pendingBatchSql.add(buildOracleInsertSql(table, rowValues, false));
                    currentBatchCount++;
                    totalTransferred++;

                    if (currentBatchCount % safeBatchSize == 0) {
                        insertScriptWriter.appendCommittedInserts(pendingBatchSql);
                        pendingBatchSql.clear();
                        if (progressListener != null) {
                            progressListener.onBatchCommitted(table.getTableName(), currentBatchCount, totalTransferred, totalSkipped);
                        }
                        currentBatchCount = 0;
                        System.out.println("  -> Exported " + totalTransferred + " rows...");
                    }
                }

                if (currentBatchCount > 0) {
                    insertScriptWriter.appendCommittedInserts(pendingBatchSql);
                    pendingBatchSql.clear();
                    if (progressListener != null) {
                        progressListener.onBatchCommitted(table.getTableName(), currentBatchCount, totalTransferred, totalSkipped);
                    }
                    System.out.println("  -> Exported " + totalTransferred + " rows...");
                }

                if (sourceRowsRead == 0) {
                    System.err.println("[DATA-WARN] Table " + table.getTableName()
                            + " returned no rows from source query."
                            + " Check schema/DB name and source data. SQL=" + selectSql);
                }

                System.out.println("Completed export-only SQL generation. Total rows exported: "
                        + totalTransferred + " for table " + table.getTableName());

                List<String> exportedSqlFiles = insertScriptWriter == null
                        ? List.of()
                        : insertScriptWriter.getGeneratedFileNames();
                return new TransferResult(safeStartOffset, totalTransferred, totalSkipped, limitReached, exportedSqlFiles);
            }
        } finally {
            sourceConn.setAutoCommit(originalSourceAutoCommit);
        }
    }

    private static OracleInsertScriptWriter createOracleInsertScriptWriter(
            TableDefinition table,
            int startOffset,
            InsertSqlExportOptions insertSqlExportOptions
    ) throws SQLException {
        if (insertSqlExportOptions == null) {
            return null;
        }

        try {
            Files.createDirectories(insertSqlExportOptions.outputDirectory());
            return new OracleInsertScriptWriter(
                    insertSqlExportOptions.outputDirectory(),
                    table.getTableName(),
                    insertSqlExportOptions.rowsPerFile(),
                    insertSqlExportOptions.commitEveryRows(),
                    startOffset
            );
        } catch (IOException e) {
            throw new SQLException(
                    "Unable to initialize Oracle INSERT SQL writer for table " + table.getTableName(),
                    e
            );
        }
    }

    private static String buildOracleInsertSql(TableDefinition table, Object[] rowValues, boolean useMerge) throws SQLException {
        List<ColumnDefinition> columns = table.getColumns();
        if (rowValues == null || rowValues.length != columns.size()) {
            throw new SQLException("Cannot build Oracle INSERT SQL due to mismatched row values.");
        }

        if (useMerge && !table.getPrimaryKeys().isEmpty()) {
            StringBuilder sql = new StringBuilder();
            sql.append("MERGE INTO ");
            sql.append(buildQualifiedOracleTableName(table));
            sql.append(" t USING (SELECT ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(toOracleSqlLiteral(rowValues[i], columns.get(i)));
                sql.append(" AS ");
                sql.append(quoteOracleIdentifier(columns.get(i).getName()));
            }
            sql.append(" FROM DUAL) s ON (");

            List<String> pks = table.getPrimaryKeys();
            for (int i = 0; i < pks.size(); i++) {
                if (i > 0) sql.append(" AND ");
                String quotedPk = quoteOracleIdentifier(pks.get(i));
                sql.append("t.").append(quotedPk).append(" = s.").append(quotedPk);
            }

            sql.append(") WHEN NOT MATCHED THEN INSERT (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(quoteOracleIdentifier(columns.get(i).getName()));
            }
            sql.append(") VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("s.").append(quoteOracleIdentifier(columns.get(i).getName()));
            }
            sql.append(");");
            return sql.toString();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ");
        sql.append(buildQualifiedOracleTableName(table));
        sql.append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quoteOracleIdentifier(columns.get(i).getName()));
        }
        sql.append(") VALUES (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(toOracleSqlLiteral(rowValues[i], columns.get(i)));
        }

        sql.append(");");
        return sql.toString();
    }

    private static String buildQualifiedOracleTableName(TableDefinition table) {
        String tableName = quoteOracleIdentifier(table.getTableName());
        String schema = table.getTargetSchema();
        if (schema == null || schema.isBlank()) {
            return tableName;
        }
        return quoteOracleIdentifier(schema) + "." + tableName;
    }

    private static String quoteOracleIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "\"\"";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String toOracleSqlLiteral(Object value, ColumnDefinition column) throws SQLException {
        if (value == null) {
            return "NULL";
        }

        if (value instanceof CharSequence || value instanceof Character) {
            return "'" + escapeSqlString(value.toString()) + "'";
        }

        if (value instanceof Boolean b) {
            return b ? "1" : "0";
        }

        if (value instanceof byte[] bytes) {
            return "HEXTORAW('" + toHex(bytes) + "')";
        }

        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }

        if (value instanceof BigInteger bi) {
            return bi.toString();
        }

        if (value instanceof Number number) {
            if (number instanceof Double d) {
                if (!Double.isFinite(d)) {
                    return "'" + escapeSqlString(String.valueOf(d)) + "'";
                }
            }
            if (number instanceof Float f) {
                if (!Float.isFinite(f)) {
                    return "'" + escapeSqlString(String.valueOf(f)) + "'";
                }
            }
            return String.valueOf(number);
        }

        if (value instanceof Timestamp ts) {
            return toOracleTimestampLiteral(ts.toLocalDateTime());
        }

        if (value instanceof Date d) {
            return "DATE '" + d.toLocalDate() + "'";
        }

        if (value instanceof Time t) {
            return toOracleTimeTimestampLiteral(t.toLocalTime());
        }

        if (value instanceof LocalDateTime ldt) {
            return toOracleTimestampLiteral(ldt);
        }

        if (value instanceof LocalDate ld) {
            return "DATE '" + ld + "'";
        }

        if (value instanceof LocalTime lt) {
            return toOracleTimeTimestampLiteral(lt);
        }

        if (value instanceof OffsetDateTime odt) {
            return toOracleTimestampLiteral(LocalDateTime.ofInstant(odt.toInstant(), ZoneOffset.UTC));
        }

        if (value instanceof ZonedDateTime zdt) {
            return toOracleTimestampLiteral(LocalDateTime.ofInstant(zdt.toInstant(), ZoneOffset.UTC));
        }

        if (value instanceof Instant instant) {
            return toOracleTimestampLiteral(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        }

        if (isOracleSqlType(value)) {
            try {
                Timestamp timestamp = toTimestamp(value);
                return toOracleTimestampLiteral(timestamp.toLocalDateTime());
            } catch (SQLException ignored) {
                try {
                    Date date = toSqlDate(value);
                    return "DATE '" + date.toLocalDate() + "'";
                } catch (SQLException ignoredAgain) {
                    // fall through to string literal fallback
                }
            }
        }

        if (column != null && column.getJdbcType() == Types.BOOLEAN && value instanceof Number number) {
            return number.intValue() != 0 ? "1" : "0";
        }

        return "'" + escapeSqlString(value.toString()) + "'";
    }

    private static String toOracleTimestampLiteral(LocalDateTime localDateTime) {
        String datePart = String.format(
                Locale.ROOT,
                "%04d-%02d-%02d %02d:%02d:%02d",
                localDateTime.getYear(),
                localDateTime.getMonthValue(),
                localDateTime.getDayOfMonth(),
                localDateTime.getHour(),
                localDateTime.getMinute(),
                localDateTime.getSecond()
        );
        String fractionPart = String.format(Locale.ROOT, "%09d", localDateTime.getNano());
        return "TO_TIMESTAMP('" + datePart + "." + fractionPart + "', 'YYYY-MM-DD HH24:MI:SS.FF9')";
    }

    private static String toOracleTimeTimestampLiteral(LocalTime localTime) {
        String timePart = String.format(
                Locale.ROOT,
                "%02d:%02d:%02d",
                localTime.getHour(),
                localTime.getMinute(),
                localTime.getSecond()
        );
        String fractionPart = String.format(Locale.ROOT, "%09d", localTime.getNano());
        return "TO_TIMESTAMP('1970-01-01 " + timePart + "." + fractionPart + "', 'YYYY-MM-DD HH24:MI:SS.FF9')";
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format(Locale.ROOT, "%02X", b));
        }
        return builder.toString();
    }

    private static String escapeSqlString(String text) {
        return text.replace("'", "''");
    }

    private static final class OracleInsertScriptWriter implements AutoCloseable {
        private final Path outputDirectory;
        private final String tablePrefix;
        private final int rowsPerFile;
        private final int commitEveryRows;
        private final List<String> generatedFileNames = new ArrayList<>();

        private int currentFileIndex;
        private int rowsWrittenInCurrentFile;
        private int rowsSinceCommit;
        private BufferedWriter writer;

        private OracleInsertScriptWriter(
                Path outputDirectory,
                String tableName,
                int rowsPerFile,
                int commitEveryRows,
                int startOffset
        ) throws IOException {
            this.outputDirectory = outputDirectory;
            this.tablePrefix = sanitizeFilePrefix(tableName);
            this.rowsPerFile = Math.max(1, rowsPerFile);
            this.commitEveryRows = Math.max(1, commitEveryRows);

            int safeOffset = Math.max(0, startOffset);
            this.currentFileIndex = (safeOffset / this.rowsPerFile) + 1;
            this.rowsWrittenInCurrentFile = safeOffset % this.rowsPerFile;
            this.rowsSinceCommit = safeOffset % this.commitEveryRows;

            int existingChunkCount = safeOffset == 0 ? 0 : ((safeOffset - 1) / this.rowsPerFile) + 1;
            for (int i = 1; i <= existingChunkCount; i++) {
                generatedFileNames.add(fileNameForIndex(i));
            }

            boolean append = safeOffset > 0 && rowsWrittenInCurrentFile > 0;
            openWriter(append);
        }

        private void appendCommittedInserts(List<String> insertStatements) throws SQLException {
            if (insertStatements == null || insertStatements.isEmpty()) {
                return;
            }

            try {
                for (String statement : insertStatements) {
                    ensureCapacityForNextInsert();
                    writer.write(statement);
                    writer.newLine();

                    rowsWrittenInCurrentFile++;
                    rowsSinceCommit++;

                    if (rowsSinceCommit >= commitEveryRows) {
                        writer.write("COMMIT;");
                        writer.newLine();
                        rowsSinceCommit = 0;
                    }
                }
            } catch (IOException e) {
                throw new SQLException("Unable to write Oracle INSERT SQL file for table " + tablePrefix, e);
            }
        }

        private void ensureCapacityForNextInsert() throws IOException {
            if (rowsWrittenInCurrentFile < rowsPerFile) {
                return;
            }

            closeCurrentFile(true);
            currentFileIndex++;
            rowsWrittenInCurrentFile = 0;
            openWriter(false);
        }

        private void openWriter(boolean append) throws IOException {
            Path filePath = outputDirectory.resolve(fileNameForIndex(currentFileIndex));
            String fileName = filePath.getFileName().toString();
            if (!generatedFileNames.contains(fileName)) {
                generatedFileNames.add(fileName);
            }

            boolean appendMode = append && Files.exists(filePath);
            writer = Files.newBufferedWriter(
                    filePath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    appendMode ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING
            );

            if (!appendMode || Files.size(filePath) == 0L) {
                writer.write("SET DEFINE OFF;");
                writer.newLine();
                writer.newLine();
            } else {
                writer.newLine();
            }
        }

        private void closeCurrentFile(boolean rotating) throws IOException {
            if (writer == null) {
                return;
            }

            if (rowsSinceCommit > 0) {
                writer.write("COMMIT;");
                writer.newLine();
                rowsSinceCommit = 0;
            }

            if (!rotating) {
                writer.newLine();
            }
            writer.flush();
            writer.close();
            writer = null;
        }

        private String fileNameForIndex(int index) {
            return tablePrefix + "_" + String.format(Locale.ROOT, "%02d", index) + ".sql";
        }

        private static String sanitizeFilePrefix(String tableName) {
            if (tableName == null || tableName.isBlank()) {
                return "table";
            }
            String sanitized = tableName.trim().replaceAll("[^A-Za-z0-9_]", "_");
            return sanitized.isBlank() ? "table" : sanitized;
        }

        private List<String> getGeneratedFileNames() {
            return List.copyOf(generatedFileNames);
        }

        @Override
        public void close() throws SQLException {
            try {
                closeCurrentFile(false);
            } catch (IOException e) {
                throw new SQLException("Unable to finalize Oracle INSERT SQL files for table " + tablePrefix, e);
            }
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
                    String detail = summarizePrimarySqlException(e);
                    if (isValueTooLargeException(e)) {
                        detail += " | Hint: ORA-12899 usually means target column length is smaller than source value"
                                + " or Oracle BYTE/CHAR semantics mismatch."
                                + " Recreate/alter target columns to match source definition before re-run."
                                + " In Data Only mode, migration does not alter target table structure.";
                    }
                    throw new SQLException(
                        "Unable to execute batch for table " + tableName + " after " + attempt + " attempts. " + detail,
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
                sb.append(summarizeBatchUpdateCounts(counts));
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

    private static String summarizePrimarySqlException(SQLException e) {
        if (e == null) {
            return "(no SQL error details)";
        }

        SQLException current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return "SQLState=" + current.getSQLState()
                        + ", ErrorCode=" + current.getErrorCode()
                        + ", Message=" + abbreviate(message, 600);
            }

            SQLException next = current.getNextException();
            if (next == null && current.getCause() instanceof SQLException causeSql) {
                next = causeSql;
            }
            current = next;
        }

        return "(empty SQL error message)";
    }

    private static String summarizeBatchUpdateCounts(int[] counts) {
        if (counts == null) {
            return "null";
        }

        int preview = Math.min(20, counts.length);
        StringBuilder sb = new StringBuilder();
        sb.append("len=").append(counts.length).append(", preview=[");
        for (int i = 0; i < preview; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts[i]);
        }
        if (counts.length > preview) {
            sb.append(",...");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
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
