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

import org.example.migrationdbweb.dto.MigrationRequest;
import org.example.migrationdbweb.dto.MigrationStatus;
import org.example.migrationdbweb.migratetool.ConnectionManager;
import org.example.migrationdbweb.migratetool.DataTransferService;
import org.example.migrationdbweb.migratetool.DatabaseConfig;
import org.example.migrationdbweb.migratetool.DatabaseType;
import org.example.migrationdbweb.migratetool.DialectFactory;
import org.example.migrationdbweb.migratetool.MetadataExtractor;
import org.example.migrationdbweb.migratetool.SqlDialect;
import org.example.migrationdbweb.migratetool.SqlGenerator;
import org.example.migrationdbweb.migratetool.TableDefinition;
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
                    DataTransferService transferService = new DataTransferService(sqlGenerator);

                    for (int i = 0; i < tableDefinitions.size(); i++) {
                        final int tableIndex = i;
                        TableDefinition table = tableDefinitions.get(i);
                        broadcastStatus(60 + (int) ((i * 25.0f) / tableDefinitions.size()), "RUNNING",
                                "Dang migrate du lieu bang " + table.getTableName() + "...", true);

                        DataTransferService.TransferResult result = transferService.transferTableData(
                                sourceConn,
                                targetConn,
                                table,
                                1000,
                                limitRows,
                                options.isCopyNewOnly(),
                                (tableName, justTransferred, totalTransferred, totalSkipped) -> broadcastStatus(
                                    Math.min(85, 60 + (int) (((tableIndex + 1) * 25.0f) / tableDefinitions.size())),
                                        "RUNNING",
                                        "Bang " + tableName + ": copied=" + totalTransferred + ", skipped=" + totalSkipped,
                                        true
                                )
                        );

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
                } else {
                    broadcastStatus(85, "RUNNING", "Bo qua migrate du lieu (STRUCTURE_ONLY).", true);
                }

                if (!dataOnly) {
                    broadcastStatus(88, "RUNNING", "Dang tao foreign keys...", true);
                    runAddForeignKeysPhase(targetConn, tableDefinitions, targetDialect);
                    broadcastStatus(90, "RUNNING", "Hoan tat tao foreign keys.", true);
                } else {
                    broadcastStatus(90, "RUNNING", "Bo qua tao foreign keys (DATA_ONLY).", true);
                }

                // ─── PHASE: CREATE VIEWS ─────────────────────────────────────────
                // NOTE: View phu thuoc table nen phai tao SAU cac bang va FK.
                // Neu target la Oracle, bo qua view vi Oracle->Oracle thi view
                // da nam trong table definition.
                if (options.isMigrateViews() && !dataOnly) {
                    runCreateViewsPhase(
                            sourceConn, targetConn,
                            sourceSchema, targetSchema,
                            request.getSource().getType(),
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
                        sourceSchema, targetSchema, options.isReplaceExistingViews());

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
            boolean replaceExisting
    ) throws SQLException {
        // 1. Replace schema trong selectClause nếu source != target
        if (!sourceSchema.equalsIgnoreCase(targetSchema)) {
            String replaced = replaceSchemaInClause(vd.getSelectClause(), sourceSchema, targetSchema);
            vd = ViewDefinition.builder()
                    .viewName(vd.getViewName())
                    .schema(vd.getSchema())
                    .sourceDialect(vd.getSourceDialect())
                    .selectClause(replaced)
                    .checkOption(vd.getCheckOption())
                    .sourceSchema(sourceSchema)
                    .targetSchema(targetSchema)
                    .build();
        } else {
            vd.setSourceSchema(sourceSchema);
            vd.setTargetSchema(targetSchema);
        }

        // 2. Build SQL
        String createSql = targetDialect.buildCreateViewSql(vd);
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
     * Replace source schema prefix trong SELECT clause.
     * Ví dụ: "SELECT * FROM SCOTT.DEPT" → "SELECT * FROM PUBLIC.DEPT"
     */
    private String replaceSchemaInClause(String clause, String sourceSchema, String targetSchema) {
        if (clause == null || sourceSchema == null || targetSchema == null) {
            return clause;
        }
        // Case-insensitive replace "SOURCE_SCHEMA." thành "TARGET_SCHEMA."
        return clause.replaceAll(
                "(?i)" + sourceSchema + "\\.",
                targetSchema + "."
        );
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
        return "42P06".equals(sqlState)               // Postgres view exists
                || message.contains("already exists")
                && message.contains("view")
                || message.contains("ora-00955");    // Oracle name is already used by an existing object
    }
}