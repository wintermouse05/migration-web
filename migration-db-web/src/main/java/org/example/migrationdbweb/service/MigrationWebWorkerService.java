package org.example.migrationdbweb.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.example.migrationdbweb.dto.MigrationRequest;
import org.example.migrationdbweb.dto.MigrationStatus;
import org.example.migrationdbweb.migratetool.ConnectionManager;
import org.example.migrationdbweb.migratetool.DataTransferService;
import org.example.migrationdbweb.migratetool.DatabaseConfig;
import org.example.migrationdbweb.migratetool.DatabaseType;
import org.example.migrationdbweb.migratetool.DialectFactory;
import org.example.migrationdbweb.migratetool.FunctionDefinition;
import org.example.migrationdbweb.migratetool.IndexDefinition;
import org.example.migrationdbweb.migratetool.MetadataExtractor;
import org.example.migrationdbweb.migratetool.MigrationCheckpointStore;
import org.example.migrationdbweb.migratetool.MigrationRetryPolicy;
import org.example.migrationdbweb.migratetool.OracleToPgsqlTransformer;
import org.example.migrationdbweb.migratetool.PostgresDialect;
import org.example.migrationdbweb.migratetool.SequenceDefinition;
import org.example.migrationdbweb.migratetool.SqlDialect;
import org.example.migrationdbweb.migratetool.SqlGenerator;
import org.example.migrationdbweb.migratetool.TableDefinition;
import org.example.migrationdbweb.migratetool.TriggerDefinition;
import org.example.migrationdbweb.migratetool.ViewDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class MigrationWebWorkerService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final AtomicReference<MigrationStatus> latestStatus =
            new AtomicReference<>(buildStatus(0, "HEALTHY", "He thong san sang nhan migration.", false));

    @Async
    public void startMigrationProcess(MigrationRequest request) {
        ConnectionManager manager = ConnectionManager.getInstance();
        String sourcePoolId = "WEB_SOURCE_" + System.nanoTime();
        String targetPoolId = "WEB_TARGET_" + System.nanoTime();

        try {
            MigrationRequest.MigrationOptions options = request.getOptions() == null
                    ? new MigrationRequest.MigrationOptions()
                    : request.getOptions();

            Set<String> includeTables = parseCsvTableSet(options.getIncludeTablesCsv());
            Set<String> excludeTables = parseCsvTableSet(options.getExcludeTablesCsv());
            Integer limitRows = options.getLimit() > 0 ? options.getLimit() : null;
            boolean structureOnly = request.isStructureOnly();
            boolean dataOnly = request.isDataOnly();

            if (request.getSource() == null || request.getTarget() == null) {
                throw new IllegalArgumentException("Thieu cau hinh source/target database.");
            }

            broadcastStatus(0, "RUNNING", "Bat dau khoi tao luong Migration...", true);
            broadcastStatus(3, "RUNNING", "Dang thiet lap connection pool cho source/target...", true);

            manager.createPool(sourcePoolId, request.getSource());
            manager.createPool(targetPoolId, request.getTarget());

            if (!manager.testConnection(sourcePoolId) || !manager.testConnection(targetPoolId)) {
                throw new SQLException("Khong the ket noi den source/target DB.");
            }

            try (Connection sourceConn = manager.getConnection(sourcePoolId);
                 Connection targetConn = manager.getConnection(targetPoolId)) {

                String sourceSchema = resolveDefaultSchema(request.getSource());
                String targetSchema = resolveDefaultSchema(request.getTarget());

                broadcastStatus(6, "RUNNING", "Da ket noi source/target thanh cong.", true);
                broadcastStatus(8, "RUNNING", "Schema source=" + sourceSchema + " | target=" + targetSchema, true);
                broadcastStatus(10, "RUNNING", "Filter bang: include="
                        + (includeTables.isEmpty() ? "ALL" : String.join(",", includeTables))
                        + " | exclude="
                        + (excludeTables.isEmpty() ? "NONE" : String.join(",", excludeTables)), true);

                if (limitRows != null) {
                    broadcastStatus(12, "RUNNING", "Limit du lieu moi bang: " + limitRows + " dong.", true);
                }

                MetadataExtractor metadataExtractor = new MetadataExtractor();
                broadcastStatus(15, "RUNNING", "Dang doc danh sach bang tu source schema...", true);

                List<String> discoveredTables = metadataExtractor.getTableNames(sourceConn, sourceSchema);
                List<String> selectedTables = applyTableFilters(discoveredTables, includeTables, excludeTables);

                if (selectedTables.isEmpty()) {
                    broadcastStatus(100, "COMPLETED", "Khong co bang nao phu hop include/exclude filter.", false);
                    return;
                }

                broadcastStatus(20, "RUNNING", "Tim thay " + selectedTables.size() + " bang hop le de migrate.", true);

                List<TableDefinition> tableDefinitions = new ArrayList<>();
                for (int i = 0; i < selectedTables.size(); i++) {
                    String tableName = selectedTables.get(i);
                    tableDefinitions.add(metadataExtractor.extractTableDefinition(sourceConn, sourceSchema, tableName));
                    int progress = 20 + (int) (((i + 1) * 20.0f) / selectedTables.size());
                    broadcastStatus(progress, "RUNNING", "Da doc metadata bang " + tableName + ".", true);
                }

                SqlDialect sourceDialect = DialectFactory.getDialect(request.getSource().getType());
                SqlDialect targetDialect = DialectFactory.getDialect(request.getTarget().getType());

                if (!dataOnly) {
                    broadcastStatus(42, "RUNNING", "Dang tao cau truc bang tren target...", true);
                    runCreateTablesPhase(targetConn, tableDefinitions, targetDialect);
                    broadcastStatus(55, "RUNNING", "Hoan tat tao cau truc bang.", true);
                } else {
                    broadcastStatus(55, "RUNNING", "Bo qua tao cau truc (DATA_ONLY).", true);
                }

                if (!structureOnly) {
                    if (options.isTruncate()) {
                        broadcastStatus(58, "RUNNING", "Dang xoa du lieu cu tren target (truncate)...", true);
                        runTruncatePhase(targetConn, tableDefinitions, targetDialect);
                    }

                    broadcastStatus(60, "RUNNING", "Dang chuyen du lieu bang...", true);
                    SqlGenerator sqlGenerator = new SqlGenerator(sourceDialect, targetDialect);

                    // ── Retry / Resume setup ──────────────────────────────────────────
                    MigrationRetryPolicy retryPolicy = buildRetryPolicy(request.getRetry());
                    MigrationCheckpointStore checkpointStore = null;

                    if (request.getResume().isEnabled()) {
                        checkpointStore = new MigrationCheckpointStore(request.getResume().getStateFile());
                        if (request.getResume().isReset()) {
                            checkpointStore.clear();
                            broadcastStatus(60, "RUNNING",
                                    "[RESUME] Reset checkpoint file: " + checkpointStore.getStateFilePath(), true);
                        }
                        broadcastStatus(60, "RUNNING",
                                "[RESUME] enabled, stateFile=" + checkpointStore.getStateFilePath(), true);
                    }

                    if (retryPolicy.isRetryEnabled()) {
                        broadcastStatus(60, "RUNNING",
                                "[RETRY] enabled, maxAttempts=" + retryPolicy.getMaxAttempts()
                                + ", delayMs=" + retryPolicy.getInitialDelayMs()
                                + ", backoff=" + retryPolicy.getBackoffMultiplier(), true);
                    }

                    // ── Migrate each table with retry / resume ────────────────────────
                    for (int i = 0; i < tableDefinitions.size(); i++) {
                        final int tableIndex = i;
                        TableDefinition table = tableDefinitions.get(i);

                        // Skip bảng đã hoàn thành (resume)
                        if (checkpointStore != null && checkpointStore.isTableCompleted(table.getTableName())) {
                            broadcastStatus(
                                    60 + (int) (((i + 1) * 25.0f) / tableDefinitions.size()),
                                    "RUNNING",
                                    "Bo qua bang da migrate (resume): " + table.getTableName(),
                                    true
                            );
                            continue;
                        }

                        broadcastStatus(
                                60 + (int) (((i + 1) * 25.0f) / tableDefinitions.size()),
                                "RUNNING",
                                "Dang migrate du lieu bang " + table.getTableName() + "...",
                                true
                        );

                        DataTransferService.TransferResult result = transferTableWithRetry(
                                sourceConn, targetConn,
                                sqlGenerator, retryPolicy,
                                table, 1000, limitRows,
                                options.isCopyNewOnly(),
                                checkpointStore,
                                tableDefinitions.size(), i,
                                (tableName, justTransferred, totalTransferred, totalSkipped) -> broadcastStatus(
                                        Math.min(84, 60 + (int) (((tableIndex + 1) * 25.0f) / tableDefinitions.size())),
                                        "RUNNING",
                                        "Bang " + tableName + ": copied=" + totalTransferred + ", skipped=" + totalSkipped,
                                        true
                                )
                        );

                        if (result != null) {
                            broadcastStatus(
                                    60 + (int) (((i + 1) * 25.0f) / tableDefinitions.size()),
                                    "RUNNING",
                                    "Hoan tat bang " + table.getTableName()
                                            + " | copied=" + result.getTransferredRows()
                                            + " | skipped=" + result.getSkippedRows()
                                            + (result.isLimitReached() ? " | dat nguong limit" : ""),
                                    true
                            );
                        }
                    }
                } else {
                    broadcastStatus(85, "RUNNING", "Bo qua migrate du lieu (STRUCTURE_ONLY).", true);
                }

                if (!dataOnly) {
                    broadcastStatus(88, "RUNNING", "Dang tao foreign keys...", true);
                    runAddForeignKeysPhase(targetConn, tableDefinitions, targetDialect);
                    broadcastStatus(90, "RUNNING", "Hoan tat tao foreign keys.", true);

                    // ─── PHASE 5: SEQUENCES ──────────────────────────────────────
                    if (options.isMigrateSequences()) {
                        runCreateSequencesPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                                request.getSource().getType(), targetDialect);
                    }

                    // ─── PHASE 6: INDEXES ──────────────────────────────────────
                    if (options.isMigrateIndexes()) {
                        runCreateIndexesPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                                tableDefinitions, request.getSource().getType(), targetDialect);
                    }

                    // ─── PHASE 7: FUNCTIONS / PROCEDURES ───────────────────────
                    if (options.isMigrateFunctions()) {
                        runCreateFunctionsPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                                request.getSource().getType(), targetDialect);
                    }

                    // ─── PHASE 8: TRIGGERS ────────────────────────────────────
                    if (options.isMigrateTriggers()) {
                        runCreateTriggersPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                                tableDefinitions, request.getSource().getType(), targetDialect);
                    }
                } else {
                    broadcastStatus(90, "RUNNING", "Bo qua cac buoc cau truc (DATA_ONLY).", true);
                }

                // ─── PHASE 9: CREATE VIEWS ─────────────────────────────────────────
                // View phu thuoc table nen phai tao SAU cac bang va FK.
                if (options.isMigrateViews() && !dataOnly) {
                    runCreateViewsPhase(
                            sourceConn, targetConn,
                            sourceSchema, targetSchema,
                            request.getSource().getType(),
                            request.getTarget().getType(),
                            targetDialect,
                            options
                    );
                }

                broadcastStatus(100, "COMPLETED", "[THANH CONG] Toan bo tien trinh Migration da hoan tat!", false);
            }

        } catch (Exception e) {
            broadcastStatus(-1, "FAILED", "[LOI NGHIEM TRONG]: " + e.getMessage(), false);
        } finally {
            manager.closePool(sourcePoolId);
            manager.closePool(targetPoolId);
        }
    }

    public MigrationStatus getCurrentStatus() {
        return latestStatus.get();
    }

    public Map<String, Object> testDatabaseConnection(DatabaseConfig config) {
        if (config == null) {
            return Map.of(
                    "success", false,
                    "message", "Thieu cau hinh ket noi database."
            );
        }

        String poolId = "WEB_TEST_" + System.nanoTime();
        ConnectionManager manager = ConnectionManager.getInstance();

        try {
            manager.createPool(poolId, config);
            boolean connected = manager.testConnection(poolId);
            if (connected) {
                return Map.of(
                        "success", true,
                        "message", "Ket noi thanh cong toi " + config.getType() + " @ " + config.getHost() + ":" + config.getPort()
                );
            }

            return Map.of(
                    "success", false,
                    "message", "Khong the ket noi toi database voi cau hinh hien tai."
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "Loi test connection: " + e.getMessage()
            );
        } finally {
            manager.closePool(poolId);
        }
    }

    /**
     * Hàm tiện ích để đóng gói và gửi tin nhắn qua WebSocket
     */
    private void broadcastStatus(int progress, String status, String message, boolean inProgress) {
        MigrationStatus payload = buildStatus(progress, status, message, inProgress);
        latestStatus.set(payload);

        // Gửi vào kênh /topic/status
        messagingTemplate.convertAndSend("/topic/status", payload);
    }

    private MigrationStatus buildStatus(int progress, String status, String message, boolean inProgress) {
        return new MigrationStatus(progress, status, message, inProgress, System.currentTimeMillis());
    }

    private String resolveDefaultSchema(DatabaseConfig config) {
        if (config.getType() == DatabaseType.ORACLE) {
            return config.getUsername().toUpperCase(Locale.ROOT);
        }
        return "public";
    }

    private void runCreateTablesPhase(Connection targetConn, List<TableDefinition> tables, SqlDialect targetDialect)
            throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table : tables) {
                String createSql = normalizeSqlForJdbc(targetDialect.buildCreateTableSql(table));
                try {
                    statement.execute(createSql);
                } catch (SQLException e) {
                    if (!isTableAlreadyExistsError(e)) {
                        throw e;
                    }
                }
            }
        }
    }

    private void runTruncatePhase(Connection targetConn, List<TableDefinition> tables, SqlDialect targetDialect)
            throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table : tables) {
                String truncateSql = "TRUNCATE TABLE " + targetDialect.quoteIdentifier(table.getTableName());
                if ("PostgresDialect".equals(targetDialect.getClass().getSimpleName())) {
                    truncateSql += " RESTART IDENTITY CASCADE";
                }

                try {
                    statement.execute(truncateSql);
                } catch (SQLException e) {
                    if (!isTableNotExistsError(e)) {
                        throw e;
                    }
                }
            }
        }
    }

    private void runAddForeignKeysPhase(Connection targetConn, List<TableDefinition> tables, SqlDialect targetDialect)
            throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table : tables) {
                for (String fkSql : targetDialect.buildAddForeignKeySql(table)) {
                    try {
                        statement.execute(normalizeSqlForJdbc(fkSql));
                    } catch (SQLException e) {
                        if (!isConstraintAlreadyExistsError(e) && !isIncompatibleForeignKeyError(e)) {
                            throw e;
                        }
                    }
                }
            }
        }
    }

    private Set<String> parseCsvTableSet(String raw) {
        Set<String> parsed = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }

        String[] parts = raw.split(",");
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            parsed.add(value.toUpperCase(Locale.ROOT));
        }
        return parsed;
    }

    private List<String> applyTableFilters(List<String> discoveredTables, Set<String> includeTables, Set<String> excludeTables) {
        List<String> selected = new ArrayList<>();
        for (String tableName : discoveredTables) {
            String normalized = tableName.toUpperCase(Locale.ROOT);
            if (!includeTables.isEmpty() && !includeTables.contains(normalized)) {
                continue;
            }
            if (excludeTables.contains(normalized)) {
                continue;
            }
            selected.add(tableName);
        }
        return selected;
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
                || (message.contains("constraint") && message.contains("already exists"))
                || message.contains("ora-02275");
    }

    private static boolean isTableNotExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P01".equals(sqlState)
                || message.contains("does not exist")
                || message.contains("ora-00942");
    }

    private static boolean isIncompatibleForeignKeyError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42804".equals(sqlState)
                || message.contains("ora-02267")
                || (message.contains("foreign key") && message.contains("incompatible"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIEW MIGRATION METHODS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Phase CREATE VIEWS — chạy SAU khi tất cả tables đã được tạo.
     * Table luôn luôn được migrate trước view.
     */
    private void runCreateViewsPhase(
            Connection sourceConn,
            Connection targetConn,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            DatabaseType targetDbType,
            SqlDialect targetDialect,
            MigrationRequest.MigrationOptions options
    ) throws SQLException {
        broadcastStatus(92, "RUNNING", "Bat dau phase migrate views...", true);

        MetadataExtractor extractor = new MetadataExtractor();

        // 1. Lấy toàn bộ view definitions + topological sort
        List<ViewDefinition> sortedViews;
        try {
            sortedViews = extractor.extractViewDefinitionsWithDependencySort(
                    sourceConn, sourceSchema, sourceDbType
            );
        } catch (IllegalStateException e) {
            // Circular dependency → broadcast warning nhưng không fail job
            System.err.println("WARN: " + e.getMessage());
            broadcastStatus(92, "RUNNING",
                    "[WARN] Circular view dependency: " + e.getMessage() + ". Bo qua migrate views.", true);
            return;
        }

        if (sortedViews.isEmpty()) {
            broadcastStatus(92, "RUNNING", "Khong co view nao trong source schema de migrate.", true);
            return;
        }

        // 2. Apply include/exclude filter
        Set<String> includeViews = parseCsvTableSet(options.getIncludeViewsCsv());
        Set<String> excludeViews = parseCsvTableSet(options.getExcludeViewsCsv());
        List<ViewDefinition> filteredViews = applyViewFilters(sortedViews, includeViews, excludeViews);

        if (filteredViews.isEmpty()) {
            broadcastStatus(92, "RUNNING",
                    "Khong co view nao phu hop include/exclude filter.", true);
            return;
        }

        broadcastStatus(93, "RUNNING",
                "Tim thay " + filteredViews.size() + " views (da sort theo dependency).", true);

        // 3. Tạo views tuần tự
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < filteredViews.size(); i++) {
            ViewDefinition vd = filteredViews.get(i);
            String viewName = vd.getViewName();

            int baseProgress = 93;
            int progressRange = 7; // từ 93 -> 100
            broadcastStatus(
                    baseProgress + (int) ((i * progressRange * 1.0f) / filteredViews.size()),
                    "RUNNING",
                    "Dang tao view: " + viewName + " (" + (i + 1) + "/" + filteredViews.size() + ")",
                    true
            );

            try {
                boolean created = createSingleView(targetConn, targetDialect, vd,
                        sourceSchema, targetSchema, sourceDbType, targetDbType,
                        options.isReplaceExistingViews());

                if (created) {
                    successCount++;
                    System.out.println("  [VIEW] Created: " + viewName);
                } else {
                    skipCount++;
                    System.out.println("  [VIEW] Skipped (already exists): " + viewName);
                }
            } catch (SQLException e) {
                errorCount++;
                String errorMsg = viewName + ": " + e.getMessage();
                errors.add(errorMsg);
                System.err.println("  [VIEW] Error creating " + viewName + ": " + e.getMessage());
                // Tiếp tục với view tiếp theo — không fail cả job
            }
        }

        // 4. Tổng kết
        String summary = String.format(
                "Hoan tat migrate views: %d tao moi, %d skip, %d loi. %s",
                successCount, skipCount, errorCount,
                errorCount > 0 ? "Loi: " + errors : ""
        );
        broadcastStatus(100, errorCount > 0 ? "COMPLETED_WITH_ERRORS" : "RUNNING", summary, false);
    }

    /**
     * Tạo một view duy nhất. Trả về true nếu tạo mới, false nếu skip.
     */
    private boolean createSingleView(
            Connection targetConn,
            SqlDialect targetDialect,
            ViewDefinition vd,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            DatabaseType targetDbType,
            boolean replaceExisting
    ) throws SQLException {
        // 1. Transform view body: schema replacement + Oracle→PG regex
        String transformedBody = transformViewBody(
                vd.getSelectClause(), sourceSchema, targetSchema, sourceDbType, targetDbType
        );
        vd = ViewDefinition.builder()
                .viewName(vd.getViewName())
                .schema(vd.getSchema())
                .sourceDialect(vd.getSourceDialect())
                .selectClause(transformedBody)
                .checkOption(vd.getCheckOption())
                .sourceSchema(sourceSchema)
                .targetSchema(targetSchema)
                .build();

        // 2. Build SQL — pass targetSchema to ensure schema qualification via tokenizer
        String createSql = targetDialect.buildCreateViewSql(vd, targetSchema);
        if (createSql == null || createSql.isBlank()) {
            throw new SQLException("Cannot build CREATE VIEW SQL for: " + vd.getViewName());
        }
        createSql = normalizeSqlForJdbc(createSql);

        // 3. Execute
        try (Statement stmt = targetConn.createStatement()) {
            if (replaceExisting) {
                // DROP trước (nếu tồn tại)
                try {
                    String dropSql = "DROP VIEW IF EXISTS " + targetDialect.quoteIdentifier(vd.getViewName());
                    stmt.execute(dropSql);
                } catch (SQLException ignored) {
                    // ignore if not exists
                }
            }

            stmt.execute(createSql);
            return true;

        } catch (SQLException e) {
            if (isViewAlreadyExistsError(e)) {
                // replaceExisting = false → skip
                return false;
            }
            throw e;
        }
    }

    /**
     * Replace source schema prefix trong SELECT clause bằng regex an toàn.
     *
     * Quy tắc:
     * - Chỉ thay thế khi schema đứng trước dấu `.` ở vị trí hợp lệ (không phải trong string literals)
     * - Khớp cả identifier không có quotes và có quotes: SCOTT.DEPT, "SCOTT"."DEPT", SCOTT.DEPT
     * - Không thay thế trong nội dung string literals (ví dụ: 'SCOTT.TABLE_NAME')
     * - Không thay thế nếu schema đích đã đúng (tránh double-replace)
     *
     * Ví dụ:
     *   "SELECT * FROM SCOTT.DEPT"            → "SELECT * FROM PUBLIC.DEPT"
     *   "SELECT * FROM \"SCOTT\".\"DEPT\""      → "SELECT * FROM \"PUBLIC\".\"DEPT\""
     *   "SELECT 'scott.data'"                  → (không đổi — trong string literal)
     *   "WHERE COL = 'SCOTT.XYZ'"              → (không đổi — trong string literal)
     */
    private String transformViewBody(String clause, String sourceSchema, String targetSchema,
            DatabaseType sourceDbType, DatabaseType targetDbType) {
        if (clause == null) return clause;

        String result = clause;

        // 1. Schema replacement — an toàn cho Oracle → PostgreSQL
        //    PostgreSQL dùng lowercase identifiers, Oracle dùng UPPERCASE (quoted hoặc không)
        if (sourceSchema != null && targetSchema != null
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {

            // a) Quoted UPPERCASE: "SCOTT"."PRODUCTS" → "public"."products"
            //    Oracle giữ UPPERCASE trong dấu ngoặc kép. PostgreSQL dùng lowercase.
            result = result.replaceAll(
                    "\"\\s*" + Pattern.quote(sourceSchema) + "\\s*\"\\s*\\.\"",
                    "\"" + targetSchema.toLowerCase() + "\".\""
            );
            // Giữ nguyên quoted schema phía sau dấu . : "SCOTT"."PRODUCTS" → "public"."products"
            // Pattern trên chỉ match "schema." → thay bằng "target."
            // Tên bảng (identifier) sau dấu . giữ nguyên — sẽ lowercase ở bước tiếp theo

            // b) Unquoted identifier: SCOTT.PRODUCTS → public.products
            //    Oracle UPPERCASE không quoted, PostgreSQL lowercase
            result = result.replaceAll(
                    "(?<![a-zA-Z0-9_'\"])"                                    // không có identifier/quote trước
                            + Pattern.quote(sourceSchema) + "\\.([a-zA-Z_][a-zA-Z0-9_]*)",  // schema. identifier
                    targetSchema.toLowerCase() + ".$1"
            );

            // c) Identifier sau "schema." cần UPPERCASE → lowercase
            //    "public"."PRODUCTS" → "public"."products"
            //    PostgreSQL tất cả lowercase, kể cả quoted
            result = result.replaceAll(
                    "\"\\s*" + Pattern.quote(targetSchema.toLowerCase()) + "\\s*\"\\s*\\.\\s*\"([^\"]+)\"\\s*",
                    "\"" + targetSchema.toLowerCase() + "\".\"$1\""
            );

            // d) Lowercase source schema trong view body (Oracle lowercase schema)
            //    Lỗi: "scott.products" không được replace vì sourceSchema = "SCOTT" (UPPERCASE)
            //    View từ Oracle có thể dùng lowercase "scott.products"
            //    Replace: scott.products → public.products (case-insensitive)
            result = result.replaceAll(
                    "(?i)" + Pattern.quote(sourceSchema.toLowerCase()) + "\\.([a-zA-Z_][a-zA-Z0-9_]*)",
                    targetSchema.toLowerCase() + ".$1"
            );
        }

        // 2. Oracle → PostgreSQL transformation
        if (sourceDbType == DatabaseType.ORACLE && targetDbType == DatabaseType.POSTGRESQL) {
            OracleToPgsqlTransformer transformer = new OracleToPgsqlTransformer();
            result = transformer.transform(result);

            // Detect unsupported features và broadcast warning
            List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(result);
            for (String warning : warnings) {
                System.err.println("  [VIEW-TRANSFORM-WARN] " + warning);
            }

            // 3. Tokenizer-based schema qualification — catch-all cho bare table names
            //    không có schema prefix và chưa được xử lý bởi regex ở trên.
            //    Chỉ áp dụng khi Oracle → PostgreSQL và có targetSchema.
            if (targetSchema != null && !targetSchema.isBlank()) {
                result = PostgresDialect.qualifyUnqualifiedTableReferences(result, targetSchema.toLowerCase());
            }
        }

        return result;
    }

    private List<ViewDefinition> applyViewFilters(
            List<ViewDefinition> allViews,
            Set<String> includeViews,
            Set<String> excludeViews
    ) {
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

    private static boolean isViewAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P06".equals(sqlState)
                || (message.contains("already exists") && message.contains("view"))
                || message.contains("ora-00955");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RETRY / RESUME IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build MigrationRetryPolicy từ RetryOptions trong request.
     */
    private MigrationRetryPolicy buildRetryPolicy(MigrationRequest.RetryOptions retryOptions) {
        if (retryOptions == null) {
            return new MigrationRetryPolicy(
                    false, 3, 2000, 2.0, false, null, false
            );
        }
        return new MigrationRetryPolicy(
                retryOptions.isEnabled(),
                retryOptions.getMaxAttempts(),
                retryOptions.getInitialDelayMs(),
                retryOptions.getBackoffMultiplier(),
                false,  // resumeEnabled — do checkpointStore kiểm soát riêng
                null,
                false
        );
    }

    /**
     * Migrate một bảng với retry + resume.
     * Retry: thử lại toàn bộ bảng khi gặp lỗi tạm thời (mạng, timeout, deadlock).
     * Resume: đọc offset từ checkpoint, tiếp tục từ điểm đã dừng.
     *
     * @return TransferResult nếu thành công, null nếu thất bại sau tất cả retry
     */
    private DataTransferService.TransferResult transferTableWithRetry(
            Connection sourceConn,
            Connection targetConn,
            SqlGenerator sqlGenerator,
            MigrationRetryPolicy retryPolicy,
            TableDefinition table,
            int batchSize,
            Integer limitRows,
            boolean copyNewOnly,
            MigrationCheckpointStore checkpointStore,
            int totalTables,
            int tableIndex,
            DataTransferService.TransferProgressListener progressListener
    ) {
        int attempts = retryPolicy.resolveAttempts();
        boolean hasPrimaryKey = !table.getPrimaryKeys().isEmpty();
        int startOffset = 0;

        // Lấy offset từ checkpoint (resume)
        if (checkpointStore != null) {
            startOffset = checkpointStore.getTableOffset(table.getTableName());
            if (startOffset > 0) {
                broadcastStatus(
                        60 + (int) ((tableIndex * 25.0f) / totalTables),
                        "RUNNING",
                        "Resume bang " + table.getTableName() + " tu offset " + startOffset + "...",
                        true
                );
            }
        }

        for (int attempt = 1; attempt <= attempts; attempt++) {
            // Tạo DataTransferService mới mỗi attempt để đảm bảo connection sạch.
            // Connection được cung cấp từ outer try-with-resources (sourceConn / targetConn),
            // KHÔNG đóng ở đây — chỉ reuse.
            DataTransferService transferService = new DataTransferService(sqlGenerator, retryPolicy);

            try {
                if (attempt > 1) {
                    broadcastStatus(
                            60 + (int) ((tableIndex * 25.0f) / totalTables),
                            "RUNNING",
                            "Retry bang " + table.getTableName() + " lan " + attempt + "/" + attempts + "...",
                            true
                    );
                }

                final int currentStartOffset = startOffset;
                DataTransferService.TransferResult result = transferService.transferTableData(
                        sourceConn,
                        targetConn,
                        table,
                        batchSize,
                        limitRows,
                        copyNewOnly,
                        currentStartOffset,
                        (tableName, justTransferred, totalTransferred, totalSkipped) -> {
                            // Cập nhật checkpoint sau mỗi batch
                            if (checkpointStore != null) {
                                checkpointStore.updateTableOffset(tableName, totalTransferred);
                            }
                            if (progressListener != null) {
                                progressListener.onBatchCommitted(tableName, justTransferred, totalTransferred, totalSkipped);
                            }
                        }
                );

                // Thành công → đánh dấu bảng hoàn tất trong checkpoint
                if (checkpointStore != null) {
                    checkpointStore.markTableCompleted(table.getTableName(), result.getTransferredRows());
                }

                return result;

            } catch (SQLException e) {
                boolean isRetryable = isRetryableException(e);
                boolean lastAttempt = (attempt == attempts);

                if (!isRetryable || lastAttempt) {
                    // Không retry được hoặc hết số lần → thất bại
                    broadcastStatus(
                            60 + (int) ((tableIndex * 25.0f) / totalTables),
                            "FAILED",
                            "[LOI] Bang " + table.getTableName()
                                    + ": " + e.getMessage()
                                    + (lastAttempt ? " (het retry)" : " (khong retry duoc)"),
                            false
                    );
                    return null;
                }

                // Đọc lại offset từ checkpoint sau khi lỗi (đảm bảo offset mới nhất)
                if (checkpointStore != null) {
                    startOffset = checkpointStore.getTableOffset(table.getTableName());
                }

                long delayMs = retryPolicy.delayForAttempt(attempt);
                broadcastStatus(
                        60 + (int) ((tableIndex * 25.0f) / totalTables),
                        "RUNNING",
                        "[RETRY] Loi tam thoi bang " + table.getTableName()
                                + " (lan " + attempt + "/" + attempts + ")"
                                + ", cho " + delayMs + "ms. Ly do: " + e.getMessage(),
                        true
                );
                sleep(delayMs);
            }
        }

        return null;
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
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 5: SEQUENCES
    // ─────────────────────────────────────────────────────────────────────────

    private void runCreateSequencesPhaseWeb(
            Connection targetConn,
            Connection sourceConn,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        broadcastStatus(90, "RUNNING", "Bat dau migrate sequences...", true);
        MetadataExtractor extractor = new MetadataExtractor();

        List<String> seqNames = extractor.getSequenceNames(sourceConn, sourceSchema);
        if (seqNames.isEmpty()) {
            broadcastStatus(90, "RUNNING", "Khong co sequence nao trong schema nguon.", true);
            return;
        }

        int successCount = 0;
        int skipCount = 0;
        try (Statement stmt = targetConn.createStatement()) {
            for (String seqName : seqNames) {
                SequenceDefinition seq = extractor.extractSequenceDefinition(
                        sourceConn, sourceSchema, seqName, sourceDbType);
                if (seq == null || seq.isSystemSequence()) continue;

                seq.setSourceSchema(sourceSchema);
                seq.setTargetSchema(targetSchema);

                try {
                    String sql = normalizeSqlForJdbc(targetDialect.buildCreateSequenceSql(seq));
                    stmt.execute(sql);
                    successCount++;
                } catch (SQLException e) {
                    if (isSequenceAlreadyExistsError(e)) {
                        skipCount++;
                    } else {
                        System.err.println("Loi tao sequence " + seqName + ": " + e.getMessage());
                    }
                }
            }
        }
        broadcastStatus(91, "RUNNING",
                "Hoan tat sequences: " + successCount + " tao, " + skipCount + " skip.", true);
    }

    private static boolean isSequenceAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState) || message.contains("already exists");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 6: INDEXES
    // ─────────────────────────────────────────────────────────────────────────

    private void runCreateIndexesPhaseWeb(
            Connection targetConn,
            Connection sourceConn,
            String sourceSchema,
            String targetSchema,
            List<TableDefinition> tableDefinitions,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        broadcastStatus(91, "RUNNING", "Bat dau migrate indexes...", true);
        MetadataExtractor extractor = new MetadataExtractor();

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        try (Statement stmt = targetConn.createStatement()) {
            for (TableDefinition table : tableDefinitions) {
                List<String> idxNames = extractor.getIndexNames(sourceConn, sourceSchema, table.getTableName());
                for (String idxName : idxNames) {
                    IndexDefinition idx = extractor.extractIndexDefinition(
                            sourceConn, sourceSchema, idxName, sourceDbType);
                    if (idx == null || !idx.isMigratable() || idx.isSystemIndex()) continue;

                    idx.setSourceSchema(sourceSchema);
                    idx.setTargetSchema(targetSchema);

                    List<String> idxSqls = targetDialect.buildCreateIndexSql(idx);
                    for (String idxSql : idxSqls) {
                        if (idxSql == null || idxSql.isBlank()) continue;
                        try {
                            stmt.execute(normalizeSqlForJdbc(idxSql));
                            successCount++;
                        } catch (SQLException e) {
                            if (isIndexAlreadyExistsError(e)) {
                                skipCount++;
                            } else {
                                errorCount++;
                                System.err.println("Loi tao index " + idxName + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        broadcastStatus(91, "RUNNING",
                "Hoan tat indexes: " + successCount + " tao, " + skipCount + " skip, " + errorCount + " loi.", true);
    }

    private static boolean isIndexAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState) || message.contains("already exists");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 7: FUNCTIONS / PROCEDURES
    // ─────────────────────────────────────────────────────────────────────────

    private void runCreateFunctionsPhaseWeb(
            Connection targetConn,
            Connection sourceConn,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        broadcastStatus(91, "RUNNING", "Bat dau migrate functions/procedures...", true);

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
        }

        MetadataExtractor extractor = new MetadataExtractor();
        List<String> fnNames = extractor.getFunctionNames(sourceConn, sourceSchema);
        if (fnNames.isEmpty()) {
            broadcastStatus(91, "RUNNING", "Khong co function/procedure nao trong schema nguon.", true);
            return;
        }

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        try (Statement stmt = targetConn.createStatement()) {
            for (String fnName : fnNames) {
                FunctionDefinition fn = extractor.extractFunctionDefinition(
                        sourceConn, sourceSchema, fnName, sourceDbType);
                if (fn == null) continue;

                fn.setSourceSchema(sourceSchema);
                fn.setTargetSchema(targetSchema);

                // Detect unsupported features
                if (fn.getFunctionBody() != null) {
                    List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(fn.getFunctionBody());
                    for (String w : warnings) {
                        System.err.println("  [VIEW-TRANSFORM-WARN] " + fnName + ": " + w);
                    }
                }

                List<String> fnSqls = targetDialect.buildCreateFunctionSql(fn, transformer);
                for (String fnSql : fnSqls) {
                    if (fnSql == null || fnSql.isBlank()) continue;
                    String normalized = normalizeSqlForJdbc(fnSql);
                    try {
                        stmt.execute(normalized);
                        successCount++;
                    } catch (SQLException e) {
                        if (isFunctionAlreadyExistsError(e)) {
                            skipCount++;
                        } else {
                            errorCount++;
                            String fnError = "Loi tao function/procedure " + fnName + ": " + e.getMessage();
                            System.err.println(fnError);
                            System.err.println("  [DEBUG] " + getSqlContextAt(normalized, e));
                            broadcastStatus(92, "RUNNING", fnError, true);
                        }
                    }
                }
            }
        }
        broadcastStatus(92, "RUNNING",
                "Hoan tat functions: " + successCount + " tao, " + skipCount + " skip, " + errorCount + " loi.", true);
    }

    private static boolean isFunctionAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || "42723".equals(sqlState)
                || message.contains("already exists")
                || message.contains("duplicate function");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 8: TRIGGERS
    // ─────────────────────────────────────────────────────────────────────────

    private void runCreateTriggersPhaseWeb(
            Connection targetConn,
            Connection sourceConn,
            String sourceSchema,
            String targetSchema,
            List<TableDefinition> tableDefinitions,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        broadcastStatus(92, "RUNNING", "Bat dau migrate triggers...", true);

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
        }

        MetadataExtractor extractor = new MetadataExtractor();
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        try (Statement stmt = targetConn.createStatement()) {
            for (TableDefinition table : tableDefinitions) {
                List<String> trigNames = extractor.getTriggerNamesForTable(
                        sourceConn, sourceSchema, table.getTableName());
                for (String trigName : trigNames) {
                    TriggerDefinition trig = extractor.extractTriggerDefinition(
                            sourceConn, sourceSchema, trigName, sourceDbType);
                    if (trig == null) continue;

                    trig.setSourceSchema(sourceSchema);
                    trig.setTargetSchema(targetSchema);

                    // Detect unsupported features
                    if (trig.getTriggerBody() != null) {
                        List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(trig.getTriggerBody());
                        for (String w : warnings) {
                            System.err.println("  [VIEW-TRANSFORM-WARN] " + trigName + ": " + w);
                        }
                    }

                    List<String> trigSqls = targetDialect.buildCreateTriggerSql(trig, transformer);
                    for (String trigSql : trigSqls) {
                        if (trigSql == null || trigSql.isBlank()) continue;
                        try {
                            stmt.execute(normalizeSqlForJdbc(trigSql));
                            successCount++;
                        } catch (SQLException e) {
                            if (isTriggerAlreadyExistsError(e)) {
                                skipCount++;
                            } else {
                                errorCount++;
                                System.err.println("Loi tao trigger " + trigName + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        broadcastStatus(92, "RUNNING",
                "Hoan tat triggers: " + successCount + " tao, " + skipCount + " skip, " + errorCount + " loi.", true);
    }

    private static boolean isTriggerAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P06".equals(sqlState)
                || (message.contains("already exists") && message.contains("trigger"))
                || message.contains("ora-00955");
    }

    private static String getSqlContextAt(String sql, SQLException e) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Position: (\\d+)").matcher(e.getMessage());
            if (!m.find()) return sql.substring(0, Math.min(300, sql.length()));
            int pos = Integer.parseInt(m.group(1));
            int start = Math.max(0, pos - 40);
            int end = Math.min(sql.length(), pos + 40);
            return "pos " + pos + ": ..." + sql.substring(start, end) + "...";
        } catch (Exception ex) {
            return sql.substring(0, Math.min(300, sql.length()));
        }
    }
}