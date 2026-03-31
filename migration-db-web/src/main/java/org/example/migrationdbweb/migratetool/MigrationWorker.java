package org.example.migrationdbweb.migratetool;





import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingWorker;

// SwingWorker<Kết Quả Cuối Cùng, Kiểu Dữ Liệu Cập Nhật Giữa Chừng>
public class MigrationWorker extends SwingWorker<Void, String> {

    private final MigrationAppUI ui;
    private final boolean isStructureOnly;
    private final boolean isDataOnly;
    private final DatabaseConfig sourceConfig;
    private final DatabaseConfig targetConfig;
    private final String sourceSchema;
    private final String targetSchema;
    private final int batchSize;
    private final boolean truncateTarget;
    private final boolean copyNewOnly;
    private final Integer limitRows;
    private final Set<String> includeTables;
    private final Set<String> excludeTables;
    private final MigrationRetryPolicy retryPolicy;

    // Sequences và Indexes được extract trước khi migration
    private List<SequenceDefinition> allSequences = new ArrayList<>();
    private List<IndexDefinition> allIndexes = new ArrayList<>();
    private List<FunctionDefinition> allFunctions = new ArrayList<>();
    private List<TriggerDefinition> allTriggers = new ArrayList<>();
    private List<ViewDefinition> allViews = new ArrayList<>();

    // Flags điều khiển migration các object nâng cao
    private final boolean migrateSequences;
    private final boolean migrateIndexes;
    private final boolean migrateFunctions;
    private final boolean migrateTriggers;
    private final Set<String> includeViews;
    private final Set<String> excludeViews;

    public MigrationWorker(
            MigrationAppUI ui,
            boolean isStructureOnly,
            boolean isDataOnly,
            DatabaseConfig sourceConfig,
            DatabaseConfig targetConfig,
            String sourceSchema,
            String targetSchema,
            int batchSize,
            boolean truncateTarget,
            boolean copyNewOnly,
            Integer limitRows,
            Set<String> includeTables,
            Set<String> excludeTables,
            boolean migrateSequences,
            boolean migrateIndexes,
            boolean migrateFunctions,
            boolean migrateTriggers,
            Set<String> includeViews,
            Set<String> excludeViews
    ) {
        this.ui = ui;
        this.isStructureOnly = isStructureOnly;
        this.isDataOnly = isDataOnly;
        this.sourceConfig = sourceConfig;
        this.targetConfig = targetConfig;
        this.sourceSchema = sourceSchema;
        this.targetSchema = targetSchema;
        this.batchSize = Math.max(1, batchSize);
        this.truncateTarget = truncateTarget;
        this.copyNewOnly = copyNewOnly;
        this.limitRows = (limitRows != null && limitRows > 0) ? limitRows : null;
        this.includeTables = normalizeTableFilter(includeTables);
        this.excludeTables = normalizeTableFilter(excludeTables);
        this.retryPolicy = MigrationRetryPolicy.fromEnvironment();

        // Advanced migration flags (migrate X when selected)
        this.migrateSequences = migrateSequences;
        this.migrateIndexes   = migrateIndexes;
        this.migrateFunctions = migrateFunctions;
        this.migrateTriggers  = migrateTriggers;
        this.includeViews     = normalizeTableFilter(includeViews);
        this.excludeViews     = normalizeTableFilter(excludeViews);
    }

    /**
     * CHẠY DƯỚI BACKGROUND THREAD (Không được thao tác UI ở đây)
     */
    @Override
    protected Void doInBackground() throws Exception {
        ConnectionManager manager = ConnectionManager.getInstance();
        String sourcePoolId = "UI_SOURCE_" + System.currentTimeMillis();
        String targetPoolId = "UI_TARGET_" + System.currentTimeMillis();
        MigrationCheckpointStore checkpointStore = retryPolicy.isResumeEnabled()
                ? new MigrationCheckpointStore(retryPolicy.getResumeStateFile())
                : null;

        try {
            setProgress(0);
            publish("Đang thiết lập kết nối tới cơ sở dữ liệu...");
            manager.createPool(sourcePoolId, sourceConfig);
            manager.createPool(targetPoolId, targetConfig);

            if (!manager.testConnection(sourcePoolId) || !manager.testConnection(targetPoolId)) {
                throw new SQLException("Không thể thiết lập kết nối tới Source/Target DB.");
            }

            try (Connection sourceConn = manager.getConnection(sourcePoolId);
                 Connection targetConn = manager.getConnection(targetPoolId)) {

                setProgress(5);
                publish("Source URL: " + sourceConn.getMetaData().getURL());
                publish("Target URL: " + targetConn.getMetaData().getURL());
                publish("Schema nguồn: " + sourceSchema + " | Schema đích: " + targetSchema);
                publish("Tùy chọn: truncate=" + truncateTarget
                    + ", copyNewOnly=" + copyNewOnly
                    + ", limit=" + (limitRows == null ? "ALL" : limitRows));
                publish("Filter bảng: include="
                        + (includeTables.isEmpty() ? "ALL" : String.join(",", includeTables))
                        + " | exclude="
                        + (excludeTables.isEmpty() ? "NONE" : String.join(",", excludeTables)));
                publish("Retry policy: enabled=" + retryPolicy.isRetryEnabled()
                    + ", maxAttempts=" + retryPolicy.getMaxAttempts()
                    + ", delayMs=" + retryPolicy.getInitialDelayMs()
                    + ", backoff=" + retryPolicy.getBackoffMultiplier());
                publish("Resume policy: enabled=" + retryPolicy.isResumeEnabled()
                    + " (resume theo offset + table-done state)");
                if (checkpointStore != null) {
                    publish("Resume state file: " + checkpointStore.getStateFilePath());
                    if (retryPolicy.isResetResumeState()) {
                        checkpointStore.clear();
                        publish("Resume state da duoc reset do MIGRATION_RESET_RESUME_STATE=true.");
                    }
                }

                MetadataExtractor metadataExtractor = new MetadataExtractor();
                publish("Đang đọc metadata từ schema nguồn: " + sourceSchema + "...");
                List<String> discoveredTables = metadataExtractor.getTableNames(sourceConn, sourceSchema);
                List<String> tableNames = applyTableFilters(discoveredTables);

                if (tableNames.isEmpty()) {
                    publish("Không có bảng nào phù hợp filter trong schema nguồn " + sourceSchema + ".");
                    setProgress(100);
                    return null;
                }

                publish("Tìm thấy " + tableNames.size() + " bảng. Đang trích xuất cấu trúc chi tiết...");
                List<TableDefinition> allTables = new ArrayList<>();
                int totalTables = tableNames.size();
                for (int i = 0; i < totalTables; i++) {
                    ensureNotCancelled();
                    String tableName = tableNames.get(i);
                    allTables.add(metadataExtractor.extractTableDefinition(sourceConn, sourceSchema, tableName));
                    publish("  -> Đã đọc metadata bảng " + tableName);
                    setProgress(5 + (int) (((i + 1) / (float) totalTables) * 20));
                }

                // ── Trích xuất SEQUENCES ────────────────────────────────
                if (migrateSequences) {
                    publish("Đang trích xuất SEQUENCES từ schema nguồn...");
                    allSequences = extractAllSequences(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType());
                }

                // ── Trích xuất INDEXES (cho các bảng đã chọn) ─────────
                if (migrateIndexes) {
                    publish("Đang trích xuất INDEXES từ schema nguồn...");
                    allIndexes = extractAllIndexes(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType(), tableNames);
                }

                // ── Trích xuất FUNCTIONS / PROCEDURES ─────────────────
                if (migrateFunctions) {
                    publish("Đang trích xuất FUNCTIONS/PROCEDURES từ schema nguồn...");
                    allFunctions = extractAllFunctions(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType());
                }

                // ── Trích xuất TRIGGERS (cho các bảng đã chọn) ──────
                if (migrateTriggers) {
                    publish("Đang trích xuất TRIGGERS từ schema nguồn...");
                    allTriggers = extractAllTriggers(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType(), tableNames);
                }

                // ── Trích xuất VIEWS (với topological sort) ────────
                publish("Đang trích xuất VIEWS từ schema nguồn...");
                allViews = extractAllViews(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType());

                SqlDialect sourceDialect = DialectFactory.getDialect(sourceConfig.getType());
                SqlDialect targetDialect = DialectFactory.getDialect(targetConfig.getType());
                allTables = deduplicateTablesForTarget(allTables, targetDialect);
                totalTables = allTables.size();

                if (totalTables == 0) {
                    publish("Không còn bảng hợp lệ để migrate sau khi đối chiếu tên bảng trên target.");
                    setProgress(100);
                    return null;
                }

                if (!isDataOnly) {
                    publish("--- BẮT ĐẦU TẠO CẤU TRÚC BẢNG (DDL) TRÊN TARGET ---");
                    runCreateTablesPhase(targetConn, allTables, targetDialect);
                    setProgress(45);

                    // Phase 2: TẠO SEQUENCES (sau table, trước data — vì trigger dùng sequence)
                    if (migrateSequences) {
                        publish("--- TẠO SEQUENCES TRÊN TARGET ---");
                        runCreateSequencesPhase(targetConn, allSequences, targetDialect);
                    }

                    // Phase 3: TẠO FUNCTIONS/PROCEDURES (trước trigger — trigger có thể gọi function)
                    if (migrateFunctions) {
                        publish("--- TẠO FUNCTIONS/PROCEDURES TRÊN TARGET ---");
                        runCreateFunctionsPhase(targetConn, allFunctions, sourceConfig.getType(), targetDialect);
                    }
                    setProgress(50);
                } else {
                    publish("Bỏ qua bước tạo cấu trúc do chọn chế độ Data Only.");
                    setProgress(60);
                }

                if (!isStructureOnly) {
                    publish("--- BẮT ĐẦU CHUYỂN DỮ LIỆU (DML) ---");
                    SqlGenerator sqlGenerator = new SqlGenerator(sourceDialect, targetDialect);
                    DataTransferService transferService = new DataTransferService(sqlGenerator);

                    if (truncateTarget) {
                        publish("--- TRUNCATE DỮ LIỆU CŨ TRÊN TARGET ---");
                        runTruncatePhase(targetConn, allTables, targetDialect);
                        if (checkpointStore != null) {
                            checkpointStore.clear();
                            publish("Resume state da duoc reset do chon truncate target.");
                        }
                    }

                    for (int i = 0; i < totalTables; i++) {
                        ensureNotCancelled();
                        TableDefinition table = allTables.get(i);

                        boolean effectiveCopyNewOnly = copyNewOnly
                            || (retryPolicy.isResumeEnabled() && !table.getPrimaryKeys().isEmpty());

                        if (checkpointStore != null && checkpointStore.isTableCompleted(table.getTableName())) {
                            publish("  -> Bo qua bang da migrate truoc do (resume): " + table.getTableName());
                            setProgress(60 + (int) (((i + 1) / (float) totalTables) * 30));
                            continue;
                        }

                        int startOffset = checkpointStore == null ? 0 : checkpointStore.getTableOffset(table.getTableName());
                        if (startOffset > 0) {
                            publish("  -> Resume bang " + table.getTableName() + " tu offset " + startOffset + ".");
                        }

                        if (effectiveCopyNewOnly && table.getPrimaryKeys().isEmpty()) {
                            publish("  -> Bảng " + table.getTableName() + " không có PK, copyNewOnly không thể lọc trùng theo PK.");
                        }

                        publish("Đang sao chép dữ liệu bảng " + table.getTableName() + "...");
                        DataTransferService.TransferResult result = transferTableWithRetry(
                            manager,
                            sourcePoolId,
                            targetPoolId,
                            transferService,
                            table,
                            effectiveCopyNewOnly,
                            checkpointStore,
                            startOffset
                        );
                        if (checkpointStore != null) {
                            checkpointStore.markTableCompleted(table.getTableName(), result.getTransferredRows());
                        }
                        publish("  -> Hoàn tất bảng " + table.getTableName()
                                + " | copied=" + result.getTransferredRows()
                                + " | skipped=" + result.getSkippedRows()
                                + (result.isLimitReached() ? " | đạt ngưỡng limit" : ""));
                        setProgress(60 + (int) (((i + 1) / (float) totalTables) * 30));
                    }
                } else {
                    publish("Bỏ qua bước chuyển dữ liệu do chọn chế độ Structure Only.");
                    setProgress(90);
                }

                if (!isDataOnly) {
                    publish("--- BẮT ĐẦU THÊM KHÓA NGOẠI (FOREIGN KEYS) ---");
                    runAddForeignKeysPhase(targetConn, allTables, targetDialect);

                    // Indexes: sau FK + sau data — index cần data để build
                    if (migrateIndexes) {
                        publish("--- TẠO INDEXES TRÊN TARGET ---");
                        runCreateIndexesPhase(targetConn, allIndexes, targetDialect);
                    }

                    // Triggers: sau functions/data
                    if (migrateTriggers) {
                        publish("--- TẠO TRIGGERS TRÊN TARGET ---");
                        runCreateTriggersPhase(targetConn, allTriggers, sourceConfig.getType(), targetDialect);
                    }
                } else {
                    publish("Bỏ qua bước thêm khóa ngoại do chọn chế độ Data Only.");
                }

                // ── VIEWS: luôn chạy cuối cùng (phụ thuộc tables đã tạo) ──
                publish("--- TẠO VIEWS TRÊN TARGET ---");
                runCreateViewsPhase(
                        targetConn,
                        allViews,
                        sourceSchema,
                        targetSchema,
                        sourceConfig.getType(),
                        targetDialect
                );
            }

            setProgress(100);
            publish("Toàn bộ tiến trình Migration đã hoàn tất thành công!");

        } catch (SQLException | RuntimeException e) {
            publish("[LỖI NGHIÊM TRỌNG]: " + e.getMessage());
            throw e;
        } finally {
            manager.closePool(sourcePoolId);
            manager.closePool(targetPoolId);
        }

        return null;
    }

    private void runCreateTablesPhase(
            Connection targetConn,
            List<TableDefinition> allTables,
            SqlDialect targetDialect
    ) throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table : allTables) {
                ensureNotCancelled();
                String createSql = normalizeSqlForJdbc(targetDialect.buildCreateTableSql(table));
                try {
                    statement.execute(createSql);
                    publish("  -> Đã tạo bảng " + table.getTableName());
                } catch (SQLException e) {
                    if (isTableAlreadyExistsError(e)) {
                        publish("  -> Bỏ qua bảng đã tồn tại: " + table.getTableName());
                    } else {
                        throw e;
                    }
                }

                if (targetDialect instanceof OracleDialect) {
                    ensureOracleIdentityColumnsAllowExplicitInsert(statement, table, targetDialect);
                }
            }
        }
    }

    private void ensureOracleIdentityColumnsAllowExplicitInsert(
            Statement statement,
            TableDefinition table,
            SqlDialect targetDialect
    ) throws SQLException {
        for (ColumnDefinition column : table.getColumns()) {
            if (!column.isAutoIncrement()) {
                continue;
            }

            String alterIdentitySql = "ALTER TABLE "
                    + targetDialect.quoteIdentifier(table.getTableName())
                    + " MODIFY "
                    + targetDialect.quoteIdentifier(column.getName())
                    + " GENERATED BY DEFAULT AS IDENTITY";

            try {
                statement.execute(alterIdentitySql);
                publish("  -> Chuẩn hóa identity BY DEFAULT cho "
                        + table.getTableName() + "." + column.getName());
            } catch (SQLException e) {
                if (isIdentityAlterNotApplicableError(e)) {
                    continue;
                }
                throw e;
            }
        }
    }

    private void runAddForeignKeysPhase(
            Connection targetConn,
            List<TableDefinition> allTables,
            SqlDialect targetDialect
    ) throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            int totalTables = allTables.size();
            for (int i = 0; i < totalTables; i++) {
                ensureNotCancelled();
                TableDefinition table = allTables.get(i);
                for (String fkSql : targetDialect.buildAddForeignKeySql(table)) {
                    try {
                        statement.execute(normalizeSqlForJdbc(fkSql));
                    } catch (SQLException e) {
                        if (isConstraintAlreadyExistsError(e) || isIncompatibleForeignKeyError(e)) {
                            publish("  -> Bỏ qua FK không thể áp dụng cho bảng " + table.getTableName() + ": " + e.getMessage());
                            continue;
                        }
                        throw e;
                    }
                }
                setProgress(90 + (int) (((i + 1) / (float) totalTables) * 10));
            }
        }
    }

    private void runTruncatePhase(
            Connection targetConn,
            List<TableDefinition> allTables,
            SqlDialect targetDialect
    ) throws SQLException {
        if (targetDialect == null) {
            throw new IllegalArgumentException("Target dialect không hợp lệ");
        }

        List<TableDefinition> oracleDeleteFallbackTables = new ArrayList<>();

        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table : allTables) {
                ensureNotCancelled();
                String tableNameForSql = table.getTableName();
                String truncateSql;

                if (targetDialect instanceof PostgresDialect) {
                    truncateSql = "TRUNCATE TABLE "
                            + targetDialect.quoteIdentifier(tableNameForSql)
                            + " RESTART IDENTITY CASCADE";
                } else if (targetDialect instanceof OracleDialect) {
                    truncateSql = "TRUNCATE TABLE "
                            + targetDialect.quoteIdentifier(tableNameForSql.toUpperCase(Locale.ROOT));
                } else {
                    truncateSql = "TRUNCATE TABLE " + targetDialect.quoteIdentifier(tableNameForSql);
                }

                try {
                    statement.execute(truncateSql);
                    publish("  -> Đã truncate bảng " + table.getTableName());
                } catch (SQLException e) {
                    if (targetDialect instanceof OracleDialect && isTruncateBlockedByForeignKey(e)) {
                        oracleDeleteFallbackTables.add(table);
                        publish("  -> Bảng " + table.getTableName() + " bị chặn TRUNCATE bởi FK, sẽ fallback sang DELETE.");
                        continue;
                    }

                    if (isTableNotExistsError(e)) {
                        publish("  -> Bỏ qua truncate vì bảng chưa tồn tại: " + table.getTableName());
                        continue;
                    }
                    throw e;
                }
            }
        }

        if (targetDialect instanceof OracleDialect && !oracleDeleteFallbackTables.isEmpty()) {
            runOracleDeleteFallbackPhase(targetConn, oracleDeleteFallbackTables, targetDialect);
        }
    }

    private void runOracleDeleteFallbackPhase(
            Connection targetConn,
            List<TableDefinition> fallbackTables,
            SqlDialect targetDialect
    ) throws SQLException {
        List<TableDefinition> remaining = new ArrayList<>(fallbackTables);
        int pass = 1;

        while (!remaining.isEmpty()) {
            ensureNotCancelled();
            boolean deletedAnyInThisPass = false;
            List<TableDefinition> nextRemaining = new ArrayList<>();

            try (Statement statement = targetConn.createStatement()) {
                for (TableDefinition table : remaining) {
                    String deleteSql = "DELETE FROM "
                            + targetDialect.quoteIdentifier(table.getTableName().toUpperCase(Locale.ROOT));

                    try {
                        int deleted = statement.executeUpdate(deleteSql);
                        publish("  -> Fallback DELETE bảng " + table.getTableName() + ": " + deleted + " dòng.");
                        deletedAnyInThisPass = true;
                    } catch (SQLException e) {
                        if (isTableNotExistsError(e)) {
                            publish("  -> Bỏ qua DELETE vì bảng chưa tồn tại: " + table.getTableName());
                            deletedAnyInThisPass = true;
                            continue;
                        }

                        if (isDeleteBlockedByChildRows(e)) {
                            nextRemaining.add(table);
                            continue;
                        }

                        throw e;
                    }
                }
            }

            if (nextRemaining.isEmpty()) {
                return;
            }

            if (!deletedAnyInThisPass) {
                String blockedTables = nextRemaining.stream()
                        .map(TableDefinition::getTableName)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("unknown");
                throw new SQLException(
                        "Không thể dọn dữ liệu cho các bảng Oracle do ràng buộc FK vòng lặp hoặc dữ liệu tham chiếu còn tồn tại: "
                                + blockedTables
                );
            }

            publish("  -> Tiếp tục lượt DELETE fallback " + (++pass) + " cho các bảng còn phụ thuộc FK...");
            remaining = nextRemaining;
        }
    }

    private void ensureNotCancelled() {
        if (isCancelled()) {
            throw new RuntimeException("Tiến trình đã bị hủy.");
        }
    }

    private List<String> applyTableFilters(List<String> tableNames) {
        List<String> filtered = new ArrayList<>();
        for (String tableName : tableNames) {
            String normalized = tableName == null ? "" : tableName.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }

            if (!includeTables.isEmpty() && !includeTables.contains(normalized)) {
                continue;
            }

            if (excludeTables.contains(normalized)) {
                continue;
            }

            filtered.add(tableName);
        }
        return filtered;
    }

    private static Set<String> normalizeTableFilter(Set<String> rawFilter) {
        Set<String> normalized = new LinkedHashSet<>();
        if (rawFilter == null || rawFilter.isEmpty()) {
            return normalized;
        }

        for (String value : rawFilter) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toUpperCase(Locale.ROOT));
        }

        return normalized;
    }

    private DataTransferService.TransferResult transferTableWithRetry(
            ConnectionManager manager,
            String sourcePoolId,
            String targetPoolId,
            DataTransferService transferService,
            TableDefinition table,
            boolean effectiveCopyNewOnly,
            MigrationCheckpointStore checkpointStore,
            int startOffset
    ) throws SQLException {
        int attempts = retryPolicy.resolveAttempts();
        int currentOffset = Math.max(0, startOffset);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            ensureNotCancelled();

            try (Connection transferSourceConn = manager.getConnection(sourcePoolId);
                 Connection transferTargetConn = manager.getConnection(targetPoolId)) {

                if (attempt > 1) {
                    publish("  -> Retry bảng " + table.getTableName() + " lần " + attempt + "/" + attempts + "...");
                }

                return transferService.transferTableData(
                        transferSourceConn,
                        transferTargetConn,
                        table,
                        batchSize,
                        limitRows,
                        effectiveCopyNewOnly,
                        currentOffset,
                        (tableName, justTransferred, totalTransferred, totalSkipped) ->
                        {
                            if (checkpointStore != null) {
                                checkpointStore.updateTableOffset(tableName, totalTransferred);
                            }
                            publish("    -> Batch " + tableName
                                    + ": +" + justTransferred
                                    + " dòng, tổng=" + totalTransferred
                                    + ", bỏ qua=" + totalSkipped);
                        }
                );
            } catch (SQLException e) {
                if (!isRetryableException(e) || attempt == attempts) {
                    throw e;
                }

                if (checkpointStore != null) {
                    currentOffset = checkpointStore.getTableOffset(table.getTableName());
                }

                long delayMs = retryPolicy.delayForAttempt(attempt);
                publish("  -> Lỗi tạm thời bảng " + table.getTableName()
                        + " (" + e.getMessage() + "), sẽ retry sau " + delayMs + " ms.");
                sleep(delayMs);
            }
        }

        throw new SQLException("Không thể hoàn tất migrate cho bảng " + table.getTableName());
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

        String lowered = message.toLowerCase(Locale.ROOT);
        return lowered.contains("connection")
                || lowered.contains("socket")
                || lowered.contains("timed out")
                || lowered.contains("timeout")
                || lowered.contains("broken pipe")
                || lowered.contains("i/o error")
                || lowered.contains("communications link failure");
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tiến trình retry bị gián đoạn.", e);
        }
    }

    private List<TableDefinition> deduplicateTablesForTarget(
            List<TableDefinition> allTables,
            SqlDialect targetDialect
    ) {
        if (!(targetDialect instanceof OracleDialect)) {
            return allTables;
        }

        Map<String, TableDefinition> uniqueTables = new LinkedHashMap<>();
        for (TableDefinition table : allTables) {
            String targetKey = table.getTableName().toUpperCase(Locale.ROOT);
            TableDefinition existing = uniqueTables.get(targetKey);
            if (existing != null) {
                publish("  -> Bỏ qua bảng " + table.getTableName()
                        + " vì trùng tên vật lý trên Oracle với bảng "
                        + existing.getTableName() + " (" + targetKey + ").");
                continue;
            }
            uniqueTables.put(targetKey, table);
        }

        return new ArrayList<>(uniqueTables.values());
    }

    private static String normalizeSqlForJdbc(String sql) {
        String normalized = sql.trim();
        if (normalized.endsWith(";")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isTableAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || message.contains("already exists")
                || message.contains("ora-00955");
    }

    private static boolean isConstraintAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42710".equals(sqlState)
                || message.contains("constraint") && message.contains("already exists")
                || message.contains("ora-02275");
    }

    private static boolean isTableNotExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P01".equals(sqlState)
                || message.contains("does not exist")
                || message.contains("ora-00942");
    }

    private static boolean isTruncateBlockedByForeignKey(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ora-02266")
                || (message.contains("foreign key") && message.contains("truncate"));
    }

    private static boolean isDeleteBlockedByChildRows(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ora-02292")
                || (message.contains("child record") && message.contains("found"));
    }

    private static boolean isIdentityAlterNotApplicableError(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ora-30673")
                || message.contains("ora-32793")
                || message.contains("ora-32794")
                || message.contains("ora-32799");
    }

    private static boolean isIncompatibleForeignKeyError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42804".equals(sqlState)
                || message.contains("ora-02267")
                || (message.contains("foreign key") && message.contains("incompatible"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEQUENCE EXTRACTION HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trích xuất toàn bộ sequences từ schema nguồn.
     * Bỏ qua system sequences (ISEQ$$, BIN$, DR$).
     */
    private List<SequenceDefinition> extractAllSequences(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType
    ) throws SQLException {
        List<SequenceDefinition> result = new ArrayList<>();
        List<String> names = extractor.getSequenceNames(conn, schema);
        for (String name : names) {
            SequenceDefinition seq = extractor.extractSequenceDefinition(conn, schema, name, dbType);
            if (seq != null && !seq.isSystemSequence()) {
                seq.setSourceSchema(schema);
                seq.setTargetSchema(targetSchema);
                result.add(seq);
            }
        }
        publish("  -> Tim thay " + result.size() + " sequences hop le.");
        return result;
    }

    /**
     * Trích xuất toàn bộ indexes từ các bảng đã chọn.
     * Bỏ qua system indexes (pg_*, sql_*, PK indexes, implicit constraint indexes).
     */
    private List<IndexDefinition> extractAllIndexes(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType,
            List<String> tableNames
    ) throws SQLException {
        List<IndexDefinition> result = new ArrayList<>();
        for (String tableName : tableNames) {
            ensureNotCancelled();
            List<String> idxNames = extractor.getIndexNames(conn, schema, tableName);
            for (String idxName : idxNames) {
                IndexDefinition idx = extractor.extractIndexDefinition(conn, schema, idxName, dbType);
                if (idx != null && !idx.isSystemIndex() && idx.isMigratable()) {
                    idx.setSourceSchema(schema);
                    idx.setTargetSchema(targetSchema);
                    result.add(idx);
                    publish("  -> Index: " + idxName + " tren bang " + tableName
                            + " (unique=" + idx.isUnique()
                            + ", type=" + idx.getIndexType() + ")");
                } else if (idx != null && !idx.isMigratable()) {
                    publish("  -> Bo qua index " + idxName
                            + " (loai " + idx.getIndexType() + " khong ho tro migrate)");
                }
            }
        }
        publish("  -> Tim thay " + result.size() + " indexes hop le.");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FUNCTION / PROCEDURE EXTRACTION
    // ─────────────────────────────────────────────────────────────────────────

    private List<FunctionDefinition> extractAllFunctions(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType
    ) throws SQLException {
        List<FunctionDefinition> result = new ArrayList<>();
        List<String> names = extractor.getFunctionNames(conn, schema);
        for (String name : names) {
            ensureNotCancelled();
            FunctionDefinition fn = extractor.extractFunctionDefinition(conn, schema, name, dbType);
            if (fn != null) {
                fn.setSourceSchema(schema);
                fn.setTargetSchema(targetSchema);
                result.add(fn);
                publish("  -> Function/Procedure: " + name + " (" + fn.getFunctionType() + ")");
            }
        }
        publish("  -> Tim thay " + result.size() + " functions/procedures.");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRIGGER EXTRACTION
    // ─────────────────────────────────────────────────────────────────────────

    private List<TriggerDefinition> extractAllTriggers(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType,
            List<String> tableNames
    ) throws SQLException {
        List<TriggerDefinition> result = new ArrayList<>();
        for (String tableName : tableNames) {
            ensureNotCancelled();
            List<String> triggerNames = extractor.getTriggerNamesForTable(conn, schema, tableName);
            for (String trigName : triggerNames) {
                TriggerDefinition trig = extractor.extractTriggerDefinition(conn, schema, trigName, dbType);
                if (trig != null) {
                    trig.setSourceSchema(schema);
                    trig.setTargetSchema(targetSchema);
                    result.add(trig);
                    publish("  -> Trigger: " + trigName + " tren " + tableName);
                }
            }
        }
        publish("  -> Tim thay " + result.size() + " triggers.");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEQUENCE PHASE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tạo sequences trên target database.
     * Chạy SAU khi tạo tables (Phase 1) và TRƯỚC khi migrate data.
     * Lý do: Trigger có thể gọi sequence.NEXTVAL, nên sequence cần tồn tại trước.
     */
    private void runCreateSequencesPhase(
            Connection targetConn,
            List<SequenceDefinition> sequences,
            SqlDialect targetDialect
    ) throws SQLException {
        if (sequences.isEmpty()) {
            publish("  -> Khong co sequence nao de tao.");
            return;
        }

        try (Statement stmt = targetConn.createStatement()) {
            for (SequenceDefinition seq : sequences) {
                ensureNotCancelled();
                try {
                    String createSql = targetDialect.buildCreateSequenceSql(seq);
                    stmt.execute(normalizeSqlForJdbc(createSql));
                    publish("  -> Da tao sequence: " + seq.getSequenceName());
                } catch (SQLException e) {
                    if (isSequenceAlreadyExistsError(e)) {
                        publish("  -> Bo qua sequence da ton tai: " + seq.getSequenceName());
                    } else {
                        publish("  -> LOI tao sequence " + seq.getSequenceName()
                                + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FUNCTION / PROCEDURE PHASE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tạo functions và procedures trên target database.
     * Chạy SAU sequence, TRƯỚC trigger — vì trigger có thể gọi function.
     */
    private void runCreateFunctionsPhase(
            Connection targetConn,
            List<FunctionDefinition> functions,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        if (functions.isEmpty()) {
            publish("  -> Khong co function/procedure nao de tao.");
            return;
        }

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
            publish("  -> Oracle -> PostgreSQL: bat dau transform PL/SQL...");
        }

        try (Statement stmt = targetConn.createStatement()) {
            for (FunctionDefinition fn : functions) {
                ensureNotCancelled();

                // Detect unsupported features
                if (fn.getFunctionBody() != null) {
                    List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(fn.getFunctionBody());
                    for (String w : warnings) {
                        publish("  -> [WARN] " + fn.getFunctionName() + ": " + w);
                    }
                }

                List<String> stmts = targetDialect.buildCreateFunctionSql(fn, transformer);
                for (String sql : stmts) {
                    if (sql == null || sql.isBlank()) continue;
                    try {
                        stmt.execute(normalizeSqlForJdbc(sql));
                        publish("  -> Da tao " + fn.getFunctionType() + ": " + fn.getFunctionName());
                    } catch (SQLException e) {
                        if (isFunctionAlreadyExistsError(e)) {
                            publish("  -> Bo qua " + fn.getFunctionType() + " da ton tai: " + fn.getFunctionName());
                        } else {
                            publish("  -> LOI tao " + fn.getFunctionType() + " " + fn.getFunctionName()
                                    + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRIGGER PHASE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tạo triggers trên target database.
     * Chạy SAU function (vì trigger body có thể gọi function).
     */
    private void runCreateTriggersPhase(
            Connection targetConn,
            List<TriggerDefinition> triggers,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        if (triggers.isEmpty()) {
            publish("  -> Khong co trigger nao de tao.");
            return;
        }

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
            publish("  -> Oracle -> PostgreSQL: bat dau transform trigger body...");
        }

        try (Statement stmt = targetConn.createStatement()) {
            for (TriggerDefinition trig : triggers) {
                ensureNotCancelled();

                // Detect unsupported features
                if (trig.getTriggerBody() != null) {
                    List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(trig.getTriggerBody());
                    for (String w : warnings) {
                        publish("  -> [WARN] " + trig.getTriggerName() + ": " + w);
                    }
                }

                List<String> stmts = targetDialect.buildCreateTriggerSql(trig, transformer);
                for (String sql : stmts) {
                    if (sql == null || sql.isBlank()) continue;
                    try {
                        stmt.execute(normalizeSqlForJdbc(sql));
                        publish("  -> Da tao trigger: " + trig.getTriggerName()
                                + " tren " + trig.getTableName());
                    } catch (SQLException e) {
                        if (isTriggerAlreadyExistsError(e)) {
                            publish("  -> Bo qua trigger da ton tai: " + trig.getTriggerName());
                        } else {
                            publish("  -> LOI tao trigger " + trig.getTriggerName()
                                    + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INDEX PHASE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tạo indexes trên target database.
     * Chạy SAU khi migrate data (Phase 3) và SAU khi thêm FK (Phase 4).
     * Lý do: Index cần dữ liệu để build; tạo sau FK để tránh conflict.
     */
    private void runCreateIndexesPhase(
            Connection targetConn,
            List<IndexDefinition> indexes,
            SqlDialect targetDialect
    ) throws SQLException {
        if (indexes.isEmpty()) {
            publish("  -> Khong co index nao de tao.");
            return;
        }

        try (Statement stmt = targetConn.createStatement()) {
            int total = indexes.size();
            for (int i = 0; i < total; i++) {
                ensureNotCancelled();
                IndexDefinition idx = indexes.get(i);
                List<String> stmts = targetDialect.buildCreateIndexSql(idx);
                for (String idxSql : stmts) {
                    try {
                        stmt.execute(normalizeSqlForJdbc(idxSql));
                        publish("  -> Da tao index: " + idx.getIndexName()
                                + " tren " + idx.getTableName()
                                + " (" + (idx.isUnique() ? "UNIQUE" : "BTREE") + ")");
                    } catch (SQLException e) {
                        if (isIndexAlreadyExistsError(e)) {
                            publish("  -> Bo qua index da ton tai: " + idx.getIndexName());
                        } else {
                            publish("  -> LOI tao index " + idx.getIndexName()
                                    + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIEW EXTRACTION & CREATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trích xuất toàn bộ views từ schema nguồn với topological sort.
     */
    private List<ViewDefinition> extractAllViews(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType
    ) throws SQLException {
        try {
            List<ViewDefinition> views = extractor.extractViewDefinitionsWithDependencySort(conn, schema, dbType);
            publish("  -> Tim thay " + views.size() + " views (da sort theo dependency).");
            return views;
        } catch (IllegalStateException e) {
            publish("  -> [WARN] Circular view dependency: " + e.getMessage() + ". Bo qua views.");
            return new ArrayList<>();
        }
    }

    /**
     * Tạo views trên target database.
     * Chạy CUỐI CÙNG — views phụ thuộc vào tables đã tạo.
     * Áp dụng include/exclude filters và Oracle → PostgreSQL transformation nếu cần.
     */
    private void runCreateViewsPhase(
            Connection targetConn,
            List<ViewDefinition> views,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        if (views == null || views.isEmpty()) {
            publish("  -> Khong co view nao de tao.");
            return;
        }

        // Apply include/exclude filters
        List<ViewDefinition> filtered = applyViewFilters(views);
        if (filtered.isEmpty()) {
            publish("  -> Khong co view nao phu hop include/exclude filter.");
            return;
        }

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
            publish("  -> Oracle -> PostgreSQL: bat dau transform view body...");
        }

        try (Statement stmt = targetConn.createStatement()) {
            int total = filtered.size();
            for (int i = 0; i < total; i++) {
                ensureNotCancelled();
                ViewDefinition vd = filtered.get(i);
                publish("  -> Tao view: " + vd.getViewName() + " (" + (i + 1) + "/" + total + ")");

                try {
                    // 1. Transform view body
                    String transformedBody = transformViewBodySwing(
                            vd.getSelectClause(),
                            sourceSchema,
                            targetSchema,
                            sourceDbType,
                            transformer
                    );

                    // 2. Build ViewDefinition mới với transformed body
                    ViewDefinition transformedVd = ViewDefinition.builder()
                            .viewName(vd.getViewName())
                            .schema(vd.getSchema())
                            .sourceDialect(vd.getSourceDialect())
                            .selectClause(transformedBody)
                            .checkOption(vd.getCheckOption())
                            .sourceSchema(sourceSchema)
                            .targetSchema(targetSchema)
                            .materialized(vd.isMaterialized())
                            .build();

                    // 3. Build SQL
                    String createSql = targetDialect.buildCreateViewSql(transformedVd);
                    if (createSql == null || createSql.isBlank()) {
                        publish("  -> LOI: Khong the build CREATE VIEW SQL cho " + vd.getViewName());
                        continue;
                    }

                    // 4. Execute (replace existing)
                    String dropSql = "DROP VIEW IF EXISTS " + targetDialect.quoteIdentifier(vd.getViewName());
                    try {
                        stmt.execute(dropSql);
                    } catch (SQLException ignored) {
                        // ignore if not exists
                    }

                    stmt.execute(normalizeSqlForJdbc(createSql));
                    publish("  -> Da tao view: " + vd.getViewName());

                } catch (SQLException e) {
                    publish("  -> LOI tao view " + vd.getViewName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Transform view body — schema replacement thông minh + Oracle → PG transformation.
     */
    private String transformViewBodySwing(
            String clause,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            OracleToPgsqlTransformer transformer
    ) {
        if (clause == null) return clause;
        String result = clause;

        // 1. Schema replacement thông minh
        if (sourceSchema != null && targetSchema != null
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {
            // Unquoted: SCOTT.DEPT → PUBLIC.DEPT
            result = result.replaceAll(
                    "(?<![a-zA-Z0-9_'\"])" + java.util.regex.Pattern.quote(sourceSchema) + "\\.([a-zA-Z_][a-zA-Z0-9_]*)",
                    targetSchema + ".$1"
            );
            // Quoted: "SCOTT"."DEPT" → "PUBLIC"."DEPT"
            result = result.replaceAll(
                    "\"\\s*" + java.util.regex.Pattern.quote(sourceSchema) + "\\s*\"\\s*\\.",
                    "\"" + targetSchema + "\"."
            );
        }

        // 2. Oracle → PostgreSQL transformation
        if (transformer != null) {
            result = transformer.transform(result);
        }

        return result;
    }

    /**
     * Apply include/exclude CSV filters cho views.
     */
    private List<ViewDefinition> applyViewFilters(List<ViewDefinition> allViews) {
        List<ViewDefinition> filtered = new ArrayList<>();
        for (ViewDefinition vd : allViews) {
            String normalized = vd.getViewName().toUpperCase(Locale.ROOT);
            if (!includeViews.isEmpty() && !includeViews.contains(normalized)) {
                continue;
            }
            if (excludeViews.contains(normalized)) {
                continue;
            }
            filtered.add(vd);
        }
        return filtered;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ERROR DETECTION HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isSequenceAlreadyExistsError(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)  // PostgreSQL
                || message.contains("already exists");
    }

    private static boolean isFunctionAlreadyExistsError(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || "42723".equals(sqlState)
                || message.contains("already exists")
                || message.contains("duplicate function");
    }

    private static boolean isTriggerAlreadyExistsError(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || message.contains("already exists")
                || message.contains("trigger");
    }

    private static boolean isIndexAlreadyExistsError(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)   // PostgreSQL
                || message.contains("already exists")
                || message.contains("duplicate key");
    }

    /**
     * CHẠY TRÊN UI THREAD: Nhận dữ liệu từ publish() và cập nhật giao diện
     */
    @Override
    protected void process(List<String> chunks) {
        // Có thềEnhận nhiều message cùng lúc nếu loop chạy quá nhanh, nên ta dùng vòng lặp
        for (String message : chunks) {
            ui.appendLog(message);
        }
    }

    /**
     * CHẠY TRÊN UI THREAD: Gọi khi doInBackground kết thúc (thành công hoặc lỗi)
     */
    @Override
    protected void done() {
        try {
            get(); // Gọi get() đềEbắt các Exception nếu có bắn ra từ doInBackground
            ui.appendLog("\n=== HOÀN TẤT ===");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ui.appendLog("\n=== THẤT BẠI: Tiến trình bị gián đoạn ===");
        } catch (ExecutionException e) {
            ui.appendLog("\n=== THẤT BẠI: Quá trình bị gián đoạn ===");
        } finally {
            // MềEkhóa lại nút Start Migration khi xong việc
            ui.enableStartButton(true);
        }
    }
}
