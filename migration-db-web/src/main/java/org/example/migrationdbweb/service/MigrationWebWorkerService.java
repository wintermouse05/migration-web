package org.example.migrationdbweb.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.example.migrationdbweb.migratetool.JdbcUrlParamResolver;
import org.example.migrationdbweb.migratetool.MetadataExtractor;
import org.example.migrationdbweb.migratetool.MigrationCheckpointStore;
import org.example.migrationdbweb.migratetool.MigrationRetryPolicy;
import org.example.migrationdbweb.migratetool.OracleDialect;
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
            new AtomicReference<>(buildStatus(0, "HEALTHY", "System is ready to accept migrations.", false));
    private final AtomicBoolean migrationRunning = new AtomicBoolean(false);

    @Async
    public void startMigrationProcess(MigrationRequest request) {
        if (!migrationRunning.compareAndSet(false, true)) {
            broadcastStatus(0, "FAILED",
                    "Another migration is currently running. Please wait for the current process to finish.",
                    false);
            return;
        }

        ConnectionManager manager = ConnectionManager.getInstance();
        String sourcePoolId = "WEB_SOURCE_" + System.nanoTime();
        String targetPoolId = "WEB_TARGET_" + System.nanoTime();

        try {
            MigrationRequest.MigrationOptions options = request.getOptions() == null
                    ? new MigrationRequest.MigrationOptions()
                     : request.getOptions();
            MigrationRequest.ResumeOptions resumeOptions = request.getResume() == null
                ? new MigrationRequest.ResumeOptions()
                 : request.getResume();

            Set<String> includeTables = parseCsvTableSet(options.getIncludeTablesCsv());
            Set<String> excludeTables = parseCsvTableSet(options.getExcludeTablesCsv());
            Integer limitRows = options.getLimit() > 0 ? options.getLimit()  : null;
            boolean structureOnly = request.isStructureOnly();
            boolean dataOnly = request.isDataOnly();

            if (request.getSource() == null || request.getTarget() == null) {
                throw new IllegalArgumentException("Missing source/target database configuration.");
            }

            if (isSameEndpoint(request.getSource(), request.getTarget())) {
                throw new IllegalArgumentException(
                    "Source and target point to the same endpoint (type/host/port/database/user). "
                        + "Choose a different target to avoid data loss or zero-row copy."
                );
            }

            broadcastStatus(0, "RUNNING", "Initializing migration worker...", true);
            broadcastStatus(3, "RUNNING", "Setting up source/target connection pools...", true);

            manager.createPool(sourcePoolId, request.getSource());
            manager.createPool(targetPoolId, request.getTarget());

            if (!manager.testConnection(sourcePoolId) || !manager.testConnection(targetPoolId)) {
                throw new SQLException("Unable to connect to source/target database.");
            }

            try (Connection sourceConn = manager.getConnection(sourcePoolId);
                 Connection targetConn = manager.getConnection(targetPoolId)) {

                String sourceSchema = resolveDefaultSchema(request.getSource(), sourceConn);
                String targetSchema = resolveDefaultSchema(request.getTarget(), targetConn);

                configurePostgresSchema(sourceConn, request.getSource(), sourceSchema, false);
                configurePostgresSchema(targetConn, request.getTarget(), targetSchema, true);

                broadcastStatus(6, "RUNNING", "Connected to source/target successfully.", true);
                broadcastStatus(8, "RUNNING", "Source schema=" + sourceSchema + " | target=" + targetSchema, true);
                broadcastStatus(10, "RUNNING", "Table filter: include="
                        + (includeTables.isEmpty() ? "ALL"  : String.join(",", includeTables))
                        + " | exclude="
                        + (excludeTables.isEmpty() ? "NONE"  : String.join(",", excludeTables)), true);

                if (limitRows != null) {
                    broadcastStatus(12, "RUNNING", "Row limit per table: " + limitRows + " rows.", true);
                }

                MetadataExtractor metadataExtractor = new MetadataExtractor();
                broadcastStatus(15, "RUNNING", "Reading table list from source schema...", true);

                List<String> discoveredTables = metadataExtractor.getTableNames(sourceConn, sourceSchema);
                List<String> selectedTables = applyTableFilters(discoveredTables, includeTables, excludeTables);

                if (selectedTables.isEmpty()) {
                    broadcastStatus(100, "COMPLETED", "No tables match include/exclude filters.", false);
                    return;
                }

                broadcastStatus(20, "RUNNING", "Found " + selectedTables.size() + " eligible tables to migrate.", true);

                List<TableDefinition> tableDefinitions = new ArrayList<>();
                for (int i = 0; i < selectedTables.size(); i++) {
                    String tableName = selectedTables.get(i);
                    tableDefinitions.add(metadataExtractor.extractTableDefinition(sourceConn, sourceSchema, tableName));
                    int progress = 20 + (int) (((i + 1) * 20.0f) / selectedTables.size());
                    broadcastStatus(progress, "RUNNING", "Loaded metadata for table " + tableName + ".", true);
                }

                final List<TableDefinition> orderedTableDefinitions = org.example.migrationdbweb.migratetool.TableDependencySortUtil
                    .sortByForeignKeyDependency(tableDefinitions);
                broadcastStatus(40, "RUNNING", "Migration order sorted by FK dependency.", true);

                SqlDialect sourceDialect = DialectFactory.getDialect(request.getSource().getType());
                SqlDialect targetDialect = DialectFactory.getDialect(request.getTarget().getType());

                if (!dataOnly) {
                    broadcastStatus(42, "RUNNING", "Creating table structures on target...", true);
                    runCreateTablesPhase(targetConn, orderedTableDefinitions, targetDialect);
                    broadcastStatus(55, "RUNNING", "Finished creating table structures.", true);
                } else {
                    broadcastStatus(55, "RUNNING", "Skipping structure creation (DATA_ONLY).", true);
                }

                if (!structureOnly) {
                    // ── Retry / Resume setup ──────────────────────────────────────────
                    MigrationRetryPolicy retryPolicy = buildRetryPolicy(request.getRetry());
                    MigrationCheckpointStore checkpointStore = null;

                    if (resumeOptions.isEnabled()) {
                        String checkpointNamespace = buildCheckpointNamespace(
                            request.getSource(),
                            request.getTarget(),
                            sourceSchema,
                            targetSchema
                        );
                        checkpointStore = new MigrationCheckpointStore(
                            resumeOptions.getStateFile(),
                            checkpointNamespace
                        );
                        if (resumeOptions.isReset()) {
                            checkpointStore.clear();
                            broadcastStatus(60, "RUNNING",
                                    "[RESUME] Reset checkpoint file: " + checkpointStore.getStateFilePath(), true);
                        }
                        broadcastStatus(60, "RUNNING",
                            "[RESUME] enabled, stateFile=" + checkpointStore.getStateFilePath()
                                + ", namespace=" + checkpointNamespace,
                            true);
                    }

                    if (options.isTruncate()) {
                        broadcastStatus(58, "RUNNING", "Clearing existing target data (truncate)...", true);
                        runTruncatePhase(targetConn, orderedTableDefinitions, targetDialect);
                        if (checkpointStore != null) {
                            checkpointStore.clear();
                            broadcastStatus(58, "RUNNING",
                                    "[RESUME] Cleared checkpoint after truncate to avoid skipping already completed tables.", true);
                        }
                    }

                    broadcastStatus(60, "RUNNING", "Migrating table data...", true);
                    SqlGenerator sqlGenerator = new SqlGenerator(sourceDialect, targetDialect);

                    if (retryPolicy.isRetryEnabled()) {
                        broadcastStatus(60, "RUNNING",
                                "[RETRY] enabled, maxAttempts=" + retryPolicy.getMaxAttempts()
                                + ", delayMs=" + retryPolicy.getInitialDelayMs()
                                + ", backoff=" + retryPolicy.getBackoffMultiplier(), true);
                    }

                    runDataMigrationPhaseMultiThread(
                            manager,
                            sourcePoolId,
                            targetPoolId,
                            request.getSource(),
                            request.getTarget(),
                            sourceSchema,
                            targetSchema,
                            sourceConn,
                            orderedTableDefinitions,
                            sourceDialect,
                            sqlGenerator,
                            retryPolicy,
                            checkpointStore,
                            limitRows,
                                options.isCopyNewOnly(),
                                options.isCopyOnlyTargetEmptyTables()
                    );
                } else {
                    broadcastStatus(85, "RUNNING", "Skipping data migration (STRUCTURE_ONLY).", true);
                }

                if (!dataOnly) {
                    broadcastStatus(88, "RUNNING", "Creating foreign keys...", true);
                    runAddForeignKeysPhase(targetConn, orderedTableDefinitions, targetDialect);
                    broadcastStatus(90, "RUNNING", "Finished creating foreign keys.", true);

                    // ─── PHASE 5 : SEQUENCES ──────────────────────────────────────
                    if (options.isMigrateSequences()) {
                        runCreateSequencesPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                                request.getSource().getType(), targetDialect);
                    }

                    // ─── PHASE 6 : INDEXES ──────────────────────────────────────
                    if (options.isMigrateIndexes()) {
                        runCreateIndexesPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                            orderedTableDefinitions, request.getSource().getType(), targetDialect);
                    }

                    // ─── PHASE 7 : FUNCTIONS / PROCEDURES ───────────────────────
                    if (options.isMigrateFunctions()) {
                        runCreateFunctionsPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                                request.getSource().getType(), targetDialect);
                    }

                    // ─── PHASE 8 : TRIGGERS ────────────────────────────────────
                    if (options.isMigrateTriggers()) {
                        runCreateTriggersPhaseWeb(targetConn, sourceConn, sourceSchema, targetSchema,
                            orderedTableDefinitions, request.getSource().getType(), targetDialect);
                    }
                } else {
                    broadcastStatus(90, "RUNNING", "Skipping structure phases (DATA_ONLY).", true);
                }

                // ─── PHASE 9 : CREATE VIEWS ─────────────────────────────────────────
                // View phu thuoc table nen phai tao SAU cac bang va FK.
                boolean viewPhaseHasErrors = false;
                if (options.isMigrateViews() && !dataOnly) {
                    viewPhaseHasErrors = runCreateViewsPhase(
                            sourceConn, targetConn,
                            sourceSchema, targetSchema,
                            request.getSource().getType(),
                            request.getTarget().getType(),
                            targetDialect,
                            options
                    );
                } else if (!options.isMigrateViews()) {
                    broadcastStatus(92, "RUNNING",
                            "Skipping view migration (migrateViews=false).", true);
                } else {
                    broadcastStatus(92, "RUNNING",
                            "Skipping view migration (DATA_ONLY).", true);
                }

                if (viewPhaseHasErrors) {
                    broadcastStatus(100, "COMPLETED_WITH_ERRORS",
                            "[WARNING] Migration completed with errors in the view phase. Check logs for details.",
                            false);
                } else {
                    broadcastStatus(100, "COMPLETED", "[SUCCESS] Migration completed successfully.", false);
                }
            }

        } catch (Exception e) {
            broadcastStatus(-1, "FAILED", "[FATAL ERROR]: " + e.getMessage(), false);
        } finally {
            migrationRunning.set(false);
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
                    "message", "Missing database connection configuration."
            );
        }

        String poolId = "WEB_TEST_" + System.nanoTime();
        ConnectionManager manager = ConnectionManager.getInstance();

        try {
            manager.createPool(poolId, config);
            boolean connected = manager.testConnection(poolId);
            if (connected) {
                String endpoint = hasExplicitJdbcUrl(config)
                        ? config.getJdbcUrlValue().trim()
                         : config.getHost() + ":" + config.getPort();
                return Map.of(
                        "success", true,
                        "message", "Connected successfully to " + config.getType() + " @ " + endpoint
                );
            }

            return Map.of(
                    "success", false,
                    "message", "Unable to connect using the current database configuration."
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "Connection test error: " + e.getMessage()
            );
        } finally {
            manager.closePool(poolId);
        }
    }

    /**
     * Hàm tiện ích đềEđóng gói và gửi tin nhắn qua WebSocket
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

    private String resolveDefaultSchema(DatabaseConfig config, Connection conn) {
        if (config == null) {
            return "public";
        }

        // Keep behavior aligned with Swing app : JDBC URL param has highest priority.
        String jdbcUrl = config.getJdbcUrlValue();
        Optional<String> schemaFromUrl = config.getType() == DatabaseType.ORACLE
                ? JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl)
                 : JdbcUrlParamResolver.resolvePostgresSchemaFromUrl(jdbcUrl);
        if (schemaFromUrl.isPresent()) {
            return schemaFromUrl.get();
        }

        if (config.getSchemaName() != null && !config.getSchemaName().isBlank()) {
            return config.getSchemaName().trim();
        }

        if (config.getType() == DatabaseType.ORACLE) {
            return config.getUsername().toUpperCase(Locale.ROOT);
        }

        if (config.getType() == DatabaseType.POSTGRESQL && conn != null) {
            try (Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT current_schema()")) {
                if (rs.next()) {
                    String schema = rs.getString(1);
                    if (schema != null && !schema.isBlank()) {
                        return schema;
                    }
                }
            } catch (SQLException ignored) {
                // fallback to public
            }
        }

        return "public";
    }

    private void configurePostgresSchema(
            Connection conn,
            DatabaseConfig config,
            String schema,
            boolean createIfMissing
    ) throws SQLException {
        if (conn == null || config == null || config.getType() != DatabaseType.POSTGRESQL) {
            return;
        }

        String effectiveSchema = schema == null ? ""  : schema.trim();
        if (effectiveSchema.isBlank()) {
            return;
        }

        String quotedSchema = quotePostgresIdentifier(effectiveSchema);

        try (Statement stmt = conn.createStatement()) {
            if (createIfMissing) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + quotedSchema);
            }
            stmt.execute("SET search_path TO " + quotedSchema + ", public");
        }
    }

    private static String quotePostgresIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void runCreateTablesPhase(Connection targetConn, List<TableDefinition> tables, SqlDialect targetDialect)
            throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table  : tables) {
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
        boolean isOracleTarget = "OracleDialect".equals(targetDialect.getClass().getSimpleName());
        List<TableDefinition> oracleDeleteFallbackTables = new ArrayList<>();

        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table  : tables) {
                String truncateSql = "TRUNCATE TABLE " + targetDialect.quoteIdentifier(table.getTableName());
                if ("PostgresDialect".equals(targetDialect.getClass().getSimpleName())) {
                    truncateSql += " RESTART IDENTITY CASCADE";
                }

                try {
                    statement.execute(truncateSql);
                } catch (SQLException e) {
                    if (isOracleTarget && isTruncateBlockedByForeignKey(e)) {
                        oracleDeleteFallbackTables.add(table);
                        continue;
                    }

                    if (!isTableNotExistsError(e)) {
                        throw e;
                    }
                }
            }
        }

        if (isOracleTarget && !oracleDeleteFallbackTables.isEmpty()) {
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
            boolean deletedAnyInThisPass = false;
            List<TableDefinition> nextRemaining = new ArrayList<>();

            try (Statement statement = targetConn.createStatement()) {
                for (TableDefinition table  : remaining) {
                    String deleteSql = "DELETE FROM " + targetDialect.quoteIdentifier(table.getTableName());
                    try {
                        int deleted = statement.executeUpdate(deleteSql);
                        broadcastStatus(58, "RUNNING",
                                "Fallback DELETE for table " + table.getTableName() + " : " + deleted + " rows.",
                                true);
                        deletedAnyInThisPass = true;
                    } catch (SQLException e) {
                        if (isTableNotExistsError(e)) {
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
                        "Unable to clean Oracle tables due to FK constraints: " + blockedTables
                );
            }

            pass++;
            broadcastStatus(58, "RUNNING",
                    "Continuing fallback DELETE pass " + pass + " for FK-dependent child tables...",
                    true);
            remaining = nextRemaining;
        }
    }

    private void runAddForeignKeysPhase(Connection targetConn, List<TableDefinition> tables, SqlDialect targetDialect)
            throws SQLException {
        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table  : tables) {
                for (String fkSql  : targetDialect.buildAddForeignKeySql(table)) {
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
        for (String part  : parts) {
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
        for (String tableName  : discoveredTables) {
            String normalized = tableName.toUpperCase(Locale.ROOT);
            if (!includeTables.isEmpty() && !matchesAnyWildcardPattern(normalized, includeTables)) {
                continue;
            }
            if (matchesAnyWildcardPattern(normalized, excludeTables)) {
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

    private static String normalizeRoutineSqlForJdbc(String sql, SqlDialect targetDialect) {
        String normalized = sql == null ? ""  : sql.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }

        if (targetDialect != null && "OracleDialect".equals(targetDialect.getClass().getSimpleName())) {
            // Keep trailing ';' for Oracle routines, but remove SQL*Plus delimiter '/'.
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            return normalized;
        }

        return normalizeSqlForJdbc(normalized);
    }

    private static boolean isTableAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || message.contains("already exists")
                || message.contains("ora-00955");
    }

    private static boolean isConstraintAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42710".equals(sqlState)
                || (message.contains("constraint") && message.contains("already exists"))
                || message.contains("ora-02275");
    }

    private static boolean isTableNotExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P01".equals(sqlState)
                || message.contains("does not exist")
                || message.contains("ora-00942");
    }

    private static boolean isIncompatibleForeignKeyError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42804".equals(sqlState)
                || message.contains("ora-02267")
                || (message.contains("foreign key") && message.contains("incompatible"));
    }

    private static boolean isTruncateBlockedByForeignKey(SQLException e) {
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ora-02266")
                || (message.contains("foreign key") && message.contains("truncate"));
    }

    private static boolean isDeleteBlockedByChildRows(SQLException e) {
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ora-02292")
                || (message.contains("child record") && message.contains("found"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIEW MIGRATION METHODS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Phase CREATE VIEWS  Echạy SAU khi tất cả tables đã được tạo.
     * Table luôn luôn được migrate trước view.
     */
    private boolean runCreateViewsPhase(
            Connection sourceConn,
            Connection targetConn,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            DatabaseType targetDbType,
            SqlDialect targetDialect,
            MigrationRequest.MigrationOptions options
    ) throws SQLException {
        broadcastStatus(92, "RUNNING", "Starting view migration phase...", true);

        MetadataExtractor extractor = new MetadataExtractor();

        // 1. Lấy toàn bềEview definitions + topological sort
        List<ViewDefinition> sortedViews;
        try {
            sortedViews = extractor.extractViewDefinitionsWithDependencySort(
                    sourceConn, sourceSchema, sourceDbType
            );
        } catch (IllegalStateException e) {
            // Circular dependency ↁEbroadcast warning nhưng không fail job
            System.err.println("WARN : " + e.getMessage());
            broadcastStatus(92, "RUNNING",
                    "[WARN] Circular view dependency : " + e.getMessage() + ". Skipping view migration.", true);
            return true;
        }

        if (sortedViews.isEmpty()) {
            broadcastStatus(92, "RUNNING", "No views found in source schema to migrate.", true);
            return false;
        }

        // 2. Apply include/exclude filter
        Set<String> includeViews = parseCsvTableSet(options.getIncludeViewsCsv());
        Set<String> excludeViews = parseCsvTableSet(options.getExcludeViewsCsv());
        List<ViewDefinition> filteredViews = applyViewFilters(sortedViews, includeViews, excludeViews);

        if (filteredViews.isEmpty()) {
            broadcastStatus(92, "RUNNING",
                    "No views match include/exclude filters.", true);
            return false;
        }

        broadcastStatus(93, "RUNNING",
                "Found " + filteredViews.size() + " views (sorted by dependency).", true);

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
                    "Creating view: " + viewName + " (" + (i + 1) + "/" + filteredViews.size() + ")",
                    true
            );

            try {
                boolean created = createSingleView(targetConn, targetDialect, vd,
                        sourceSchema, targetSchema, sourceDbType, targetDbType,
                        options.isReplaceExistingViews());

                if (created) {
                    successCount++;
                    System.out.println("  [VIEW] Created : " + viewName);
                } else {
                    skipCount++;
                    System.out.println("  [VIEW] Skipped (already exists) : " + viewName);
                }
            } catch (SQLException e) {
                errorCount++;
                String errorMsg = viewName + " : " + e.getMessage();
                errors.add(errorMsg);
                System.err.println("  [VIEW] Error creating " + viewName + " : " + e.getMessage());
                // Tiếp tục với view tiếp theo  Ekhông fail cả job
            }
        }

        // 4. Tổng kết
        String summary = String.format(
                "View migration completed: %d created, %d skipped, %d failed. %s",
                successCount, skipCount, errorCount,
                errorCount > 0 ? "Errors: " + errors  : ""
        );
        broadcastStatus(100, errorCount > 0 ? "COMPLETED_WITH_ERRORS"  : "RUNNING", summary, false);
        return errorCount > 0;
    }

    /**
     * Tạo một view duy nhất. Trả vềEtrue nếu tạo mới, false nếu skipped.
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
            boolean oracleToOracleDirectDdlMode = sourceDbType == DatabaseType.ORACLE
                && targetDbType == DatabaseType.ORACLE
                && vd.getDdlText() != null
                && !vd.getDdlText().isBlank();

        // 1. Transform view body : schema replacement + Oracle→PG regex
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
                .ddlText(vd.getDdlText())
                .build();

        // 2. Execute
        try (Statement stmt = targetConn.createStatement()) {
            String qualifiedViewName = buildQualifiedViewName(targetDialect, targetSchema, vd.getViewName());

            if (replaceExisting) {
                // DROP trước (nếu tồn tại)
                try {
                    String dropSql;
                    if ("OracleDialect".equals(targetDialect.getClass().getSimpleName())) {
                        dropSql = "DROP VIEW " + qualifiedViewName;
                    } else {
                        dropSql = "DROP VIEW IF EXISTS " + qualifiedViewName;
                    }
                    stmt.execute(dropSql);
                } catch (SQLException dropEx) {
                    if (!isViewNotExistsError(dropEx)) {
                        throw dropEx;
                    }
                }
            }

            if (oracleToOracleDirectDdlMode) {
                String directDdl = prepareOracleRawDdl(vd.getDdlText(), sourceSchema, targetSchema);
                stmt.execute(directDdl);
                return true;
            }

            // Build SQL  Epass targetSchema to ensure schema qualification via tokenizer
            String createSql = targetDialect.buildCreateViewSql(vd, targetSchema);
            if (createSql == null || createSql.isBlank()) {
                throw new SQLException("Cannot build CREATE VIEW SQL for: " + vd.getViewName());
            }
            createSql = normalizeSqlForJdbc(createSql);

            stmt.execute(createSql);
            return true;

        } catch (SQLException e) {
            if (isViewAlreadyExistsError(e)) {
                // replaceExisting = false ↁEskip
                return false;
            }
            throw e;
        }
    }

    private static String buildQualifiedViewName(SqlDialect targetDialect, String targetSchema, String viewName) {
        String quotedView = targetDialect.quoteIdentifier(viewName);
        if (targetSchema == null || targetSchema.isBlank()) {
            return quotedView;
        }
        return targetDialect.quoteIdentifier(targetSchema) + "." + quotedView;
    }

    private static String prepareOracleRawDdl(String ddl, String sourceSchema, String targetSchema) {
        String remapped = OracleDialect.remapSchemaPrefixSafely(ddl, sourceSchema, targetSchema);
        return normalizeOracleRawDdlForJdbc(remapped);
    }

    private static String normalizeOracleRawDdlForJdbc(String ddl) {
        String normalized = ddl == null ? "" : ddl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    /**
     * Replace source schema prefix trong SELECT clause bằng regex an toàn.
     *
     * Quy tắc:
     * - ChềEthay thế khi schema đứng trước dấu `.` ềEvềEtrí hợp lềE(không phải trong string literals)
     * - Khớp cả identifier không có quotes và có quotes : SCOTT.DEPT, "SCOTT"."DEPT", SCOTT.DEPT
     * - Không thay thế trong nội dung string literals (ví dụ : 'SCOTT.TABLE_NAME')
     * - Không thay thế nếu schema đích đã đúng (tránh double-replace)
     *
     * Ví dụ:
     *   "SELECT * FROM SCOTT.DEPT"            ↁE"SELECT * FROM PUBLIC.DEPT"
     *   "SELECT * FROM \"SCOTT\".\"DEPT\""      ↁE"SELECT * FROM \"PUBLIC\".\"DEPT\""
     *   "SELECT 'scott.data'"                  ↁE(không đổi  Etrong string literal)
     *   "WHERE COL = 'SCOTT.XYZ'"              ↁE(không đổi  Etrong string literal)
     */
    private String transformViewBody(String clause, String sourceSchema, String targetSchema,
            DatabaseType sourceDbType, DatabaseType targetDbType) {
        if (clause == null) return clause;

        String result = clause;

        // 1. Schema replacement  Ean toàn cho Oracle ↁEPostgreSQL
        //    PostgreSQL dùng lowercase identifiers, Oracle dùng UPPERCASE (quoted hoặc không)
        if (sourceSchema != null && targetSchema != null
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {
            result = OracleDialect.remapSchemaPrefixSafely(result, sourceSchema, targetSchema);
        }

        // 2. Oracle ↁEPostgreSQL transformation
        if (sourceDbType == DatabaseType.ORACLE && targetDbType == DatabaseType.POSTGRESQL) {
            OracleToPgsqlTransformer transformer = new OracleToPgsqlTransformer();
            result = transformer.transform(result);

            // Detect unsupported features và broadcast warning
            List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(result);
            for (String warning  : warnings) {
                System.err.println("  [VIEW-TRANSFORM-WARN] " + warning);
            }

            // 3. Tokenizer-based schema qualification  Ecatch-all cho bare table names
            //    không có schema prefix và chưa được xử lý bởi regex ềEtrên.
            //    ChềEáp dụng khi Oracle ↁEPostgreSQL và có targetSchema.
            if (targetSchema != null && !targetSchema.isBlank()) {
                result = PostgresDialect.qualifyUnqualifiedTableReferences(result, targetSchema);
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
        for (ViewDefinition vd  : allViews) {
            String normalized = vd.getViewName().toUpperCase(Locale.ROOT);
            if (!includeViews.isEmpty() && !matchesAnyWildcardPattern(normalized, includeViews)) {
                continue;
            }
            if (matchesAnyWildcardPattern(normalized, excludeViews)) {
                continue;
            }
            filtered.add(vd);
        }
        return filtered;
    }

    private boolean matchesAnyWildcardPattern(String normalizedObjectName, Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }

        for (String pattern  : patterns) {
            if (matchesWildcardPattern(normalizedObjectName, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesWildcardPattern(String normalizedObjectName, String rawPattern) {
        if (normalizedObjectName == null || rawPattern == null) {
            return false;
        }

        String normalizedPattern = rawPattern.trim().toUpperCase(Locale.ROOT);
        if (normalizedPattern.isEmpty()) {
            return false;
        }

        // Backward compatible : token không chứa '*' thì match tuyệt đối như trước.
        if (!normalizedPattern.contains("*")) {
            return normalizedObjectName.equals(normalizedPattern);
        }

        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char ch = normalizedPattern.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        regex.append("$");

        return Pattern.compile(regex.toString()).matcher(normalizedObjectName).matches();
    }

    private static boolean isViewAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P06".equals(sqlState)
                || (message.contains("already exists") && message.contains("view"))
                || message.contains("ora-00955");
    }

    private static boolean isViewNotExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P01".equals(sqlState)
                || (message.contains("does not exist") && message.contains("view"))
                || message.contains("ora-00942")
                || message.contains("ora-04043");
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
                false,  // resumeEnabled  Edo checkpointStore kiểm soát riêng
                null,
                false
        );
    }

    private void runDataMigrationPhaseMultiThread(
            ConnectionManager manager,
            String sourcePoolId,
            String targetPoolId,
            DatabaseConfig sourceConfig,
            DatabaseConfig targetConfig,
            String sourceSchema,
            String targetSchema,
            Connection sourceConn,
            List<TableDefinition> orderedTableDefinitions,
            SqlDialect sourceDialect,
            SqlGenerator sqlGenerator,
            MigrationRetryPolicy retryPolicy,
            MigrationCheckpointStore checkpointStore,
            Integer limitRows,
                boolean copyNewOnly,
                boolean copyOnlyTargetEmptyTables
    ) throws SQLException {
        List<TableMigrationPlan> plans = buildTableMigrationPlans(sourceConn, orderedTableDefinitions, sourceDialect);
        if (plans.isEmpty()) {
            return;
        }

        broadcastStatus(60, "RUNNING",
            "Counted rows for " + plans.size()
                + " tables. Migration runs by FK level first, then by increasing row volume inside each level.",
            true);

        int threadCount = resolveDataMigrationThreadCount(plans.size(), sourceConfig, targetConfig);
        broadcastStatus(60, "RUNNING",
                "Starting multithreaded data migration with " + threadCount + " thread(s).", true);

        if (targetConfig == null || targetConfig.getType() == null) {
            throw new SQLException("Unable to resolve target dialect for target data checks.");
        }
        SqlDialect targetDialect = DialectFactory.getDialect(targetConfig.getType());
        List<TableMigrationPlan> runnablePlans = new ArrayList<>();
        try (Connection targetPlanConn = manager.getConnection(targetPoolId)) {
            configurePostgresSchema(targetPlanConn, targetConfig, targetSchema, true);

            for (TableMigrationPlan plan  : plans) {
                String tableName = plan.table().getTableName();
                if (checkpointStore != null && checkpointStore.isTableCompleted(tableName)) {
                    broadcastStatus(60, "RUNNING",
                            "Skipping already migrated table (resume): " + tableName, true);
                    continue;
                }

                if (copyOnlyTargetEmptyTables
                        && !isTargetTableEmpty(targetPlanConn, plan.table(), targetDialect)) {
                    broadcastStatus(60, "RUNNING",
                            "Skipping table " + tableName
                                    + " because target already has data (copy-only-target-empty).",
                            true);
                    continue;
                }

                runnablePlans.add(plan);
            }
        }

        if (runnablePlans.isEmpty()) {
            broadcastStatus(85, "RUNNING", "All tables are already completed in checkpoint. Skipping data phase.", true);
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CompletionService<TableTransferOutcome> completionService = new ExecutorCompletionService<>(executor);
        int submitted = 0;

        for (int i = 0; i < runnablePlans.size(); i++) {
            final int tableOrder = i;
            final TableMigrationPlan plan = runnablePlans.get(i);

            completionService.submit(() -> migrateSingleTableTask(
                    manager,
                    sourcePoolId,
                    targetPoolId,
                    sourceConfig,
                    targetConfig,
                    sourceSchema,
                    targetSchema,
                    sqlGenerator,
                    retryPolicy,
                    checkpointStore,
                    plan,
                    tableOrder,
                    runnablePlans.size(),
                    limitRows,
                    copyNewOnly
            ));
            submitted++;
        }

        AtomicInteger completed = new AtomicInteger(0);
        SQLException firstFailure = null;

        try {
            for (int i = 0; i < submitted; i++) {
                Future<TableTransferOutcome> future = completionService.take();
                TableTransferOutcome outcome = future.get();

                if (!outcome.success()) {
                    if (firstFailure == null) {
                        firstFailure = new SQLException(
                                "Unable to migrate table " + outcome.tableName() + " : " + outcome.errorMessage()
                        );
                    }
                    continue;
                }

                int done = completed.incrementAndGet();
                int progress = Math.min(85, 60 + (int) ((done * 25.0f) / Math.max(1, submitted)));
                broadcastStatus(progress, "RUNNING",
                        "Completed table " + outcome.tableName()
                                + " (rows=" + outcome.rowCount() + ")"
                                + " | copied=" + outcome.transferredRows()
                                + " | skipped=" + outcome.skippedRows()
                                + (outcome.limitReached() ? " | limit reached"  : ""),
                        true
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for data migration tasks to complete.", ex);
        } catch (ExecutionException ex) {
            throw new SQLException("Data migration task execution failed: " + ex.getMessage(), ex);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private TableTransferOutcome migrateSingleTableTask(
            ConnectionManager manager,
            String sourcePoolId,
            String targetPoolId,
            DatabaseConfig sourceConfig,
            DatabaseConfig targetConfig,
            String sourceSchema,
            String targetSchema,
            SqlGenerator sqlGenerator,
            MigrationRetryPolicy retryPolicy,
            MigrationCheckpointStore checkpointStore,
            TableMigrationPlan plan,
            int tableOrder,
            int totalTables,
            Integer limitRows,
            boolean copyNewOnly
    ) {
        TableDefinition table = plan.table();
        String tableName = table.getTableName();

        try (Connection sourceConn = manager.getConnection(sourcePoolId);
             Connection targetConn = manager.getConnection(targetPoolId)) {

            configurePostgresSchema(sourceConn, sourceConfig, sourceSchema, false);
            configurePostgresSchema(targetConn, targetConfig, targetSchema, true);

            broadcastStatus(
                    60 + (int) ((tableOrder * 25.0f) / Math.max(1, totalTables)),
                    "RUNNING",
                    "[THREAD] Starting table " + tableName + " (rows=" + plan.rowCount() + ")",
                    true
            );

            DataTransferService.TransferResult result = transferTableWithRetry(
                    sourceConn,
                    targetConn,
                    sqlGenerator,
                    retryPolicy,
                    table,
                    1000,
                    limitRows,
                    copyNewOnly,
                    checkpointStore,
                    totalTables,
                    tableOrder,
                    (name, justTransferred, totalTransferred, totalSkipped) -> broadcastStatus(
                            Math.min(84, 60 + (int) ((tableOrder * 25.0f) / Math.max(1, totalTables))),
                            "RUNNING",
                            "Table " + name + " (rows=" + plan.rowCount() + ") : copied="
                                    + totalTransferred + ", skipped=" + totalSkipped,
                            true
                    )
            );

            if (result == null) {
                return TableTransferOutcome.failed(tableName, plan.rowCount(), "Retry policy exhausted");
            }

            return TableTransferOutcome.success(
                    tableName,
                    plan.rowCount(),
                    result.getTransferredRows(),
                    result.getSkippedRows(),
                    result.isLimitReached()
            );
        } catch (Exception ex) {
            return TableTransferOutcome.failed(tableName, plan.rowCount(), ex.getMessage());
        }
    }

    private List<TableMigrationPlan> buildTableMigrationPlans(
            Connection sourceConn,
            List<TableDefinition> tables,
            SqlDialect sourceDialect
    ) {
        List<TableMigrationPlan> plans = new ArrayList<>();
        Map<TableDefinition, Integer> fkLevels = org.example.migrationdbweb.migratetool.TableDependencySortUtil
            .computeForeignKeyLevels(tables);
        for (int i = 0; i < tables.size(); i++) {
            TableDefinition table = tables.get(i);
            long rowCount = countRowsForTable(sourceConn, table, sourceDialect);
            int fkLevel = fkLevels.getOrDefault(table, 0);
            plans.add(new TableMigrationPlan(table, rowCount, i, fkLevel));
        }

        plans.sort(
                Comparator.comparingInt(TableMigrationPlan::fkLevel)
                        .thenComparingLong(TableMigrationPlan::rowCount)
                        .thenComparingInt(TableMigrationPlan::originalOrder)
        );
        return plans;
    }

    private long countRowsForTable(Connection sourceConn, TableDefinition table, SqlDialect sourceDialect) {
        String countSql = "SELECT COUNT(*) FROM " + sourceDialect.quoteIdentifier(table.getTableName());
        try (Statement statement = sourceConn.createStatement();
             ResultSet rs = statement.executeQuery(countSql)) {
            if (rs.next()) {
                return Math.max(0L, rs.getLong(1));
            }
        } catch (SQLException ex) {
            System.err.println("WARN: Unable to count rows for table " + table.getTableName()
                    + ", it will be scheduled at the end. Reason: " + ex.getMessage());
        }
        return Long.MAX_VALUE;
    }

    private boolean isTargetTableEmpty(Connection targetConn, TableDefinition table, SqlDialect targetDialect) {
        String sql = buildTargetHasDataSql(table, targetDialect);
        try (Statement statement = targetConn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return !rs.next();
        } catch (SQLException ex) {
            System.err.println("WARN: Unable to check whether target table has data for " + table.getTableName()
                    + ". Treating it as non-empty. Reason: " + ex.getMessage());
            return false;
        }
    }

    private String buildTargetHasDataSql(TableDefinition table, SqlDialect targetDialect) {
        if (targetDialect == null) {
            throw new IllegalArgumentException("Invalid target dialect");
        }

        String targetTableName = table.getTableName();
        if (targetDialect instanceof OracleDialect) {
            targetTableName = targetTableName.toUpperCase(Locale.ROOT);
        }

        String quotedTable = targetDialect.quoteIdentifier(targetTableName);
        if (targetDialect instanceof OracleDialect) {
            return "SELECT 1 FROM " + quotedTable + " WHERE ROWNUM = 1";
        }
        return "SELECT 1 FROM " + quotedTable + " LIMIT 1";
    }

    private int resolveDataMigrationThreadCount(int tableCount, DatabaseConfig sourceConfig, DatabaseConfig targetConfig) {
        int sourcePool = sourceConfig == null ? 10  : Math.max(1, sourceConfig.getMaximumPoolSize());
        int targetPool = targetConfig == null ? 10  : Math.max(1, targetConfig.getMaximumPoolSize());
        int maxByPool = Math.max(1, Math.min(sourcePool, targetPool) - 2);
        int maxByTable = Math.max(1, tableCount);
        int suggested = Math.min(4, maxByTable);
        return Math.max(1, Math.min(suggested, maxByPool));
    }

    private record TableMigrationPlan(TableDefinition table, long rowCount, int originalOrder, int fkLevel) {}

    private record TableTransferOutcome(
            String tableName,
            long rowCount,
            int transferredRows,
            int skippedRows,
            boolean limitReached,
            boolean success,
            String errorMessage
    ) {
        private static TableTransferOutcome success(
                String tableName,
                long rowCount,
                int transferredRows,
                int skippedRows,
                boolean limitReached
        ) {
            return new TableTransferOutcome(
                    tableName,
                    rowCount,
                    transferredRows,
                    skippedRows,
                    limitReached,
                    true,
                    null
            );
        }

        private static TableTransferOutcome failed(String tableName, long rowCount, String errorMessage) {
            return new TableTransferOutcome(
                    tableName,
                    rowCount,
                    0,
                    0,
                    false,
                    false,
                    errorMessage == null ? "unknown error"  : errorMessage
            );
        }
    }

    /**
     * Migrate một bảng với retry + resume.
     * Retry : thử lại toàn bềEbảng khi gặp lỗi tạm thời (mạng, timeout, deadlock).
     * Resume : đọc offset từ checkpoint, tiếp tục từ điểm đã dừng.
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
        int startOffset = 0;

        // Lấy offset từ checkpoint (resume)
        if (checkpointStore != null) {
            startOffset = checkpointStore.getTableOffset(table.getTableName());
            if (startOffset > 0) {
                broadcastStatus(
                        60 + (int) ((tableIndex * 25.0f) / totalTables),
                        "RUNNING",
                        "Resuming table " + table.getTableName() + " from offset " + startOffset + "...",
                        true
                );
            }
        }

        for (int attempt = 1; attempt <= attempts; attempt++) {
            // Tạo DataTransferService mới mỗi attempt đềEđảm bảo connection sạch.
            // Connection được cung cấp từ outer try-with-resources (sourceConn / targetConn),
            // KHÔNG đóng ềEđây  EchềEreuse.
            DataTransferService transferService = new DataTransferService(sqlGenerator, retryPolicy);

            try {
                if (attempt > 1) {
                    broadcastStatus(
                            60 + (int) ((tableIndex * 25.0f) / totalTables),
                            "RUNNING",
                            "Retrying table " + table.getTableName() + " attempt " + attempt + "/" + attempts + "...",
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

                // Thành công ↁEđánh dấu bảng hoàn tất trong checkpoint
                if (checkpointStore != null) {
                    if (result.isLimitReached()) {
                        checkpointStore.updateTableOffset(table.getTableName(), result.getTransferredRows());
                    } else {
                        checkpointStore.markTableCompleted(table.getTableName(), result.getTransferredRows());
                    }
                }

                return result;

            } catch (SQLException e) {
                boolean isRetryable = isRetryableException(e);
                boolean lastAttempt = (attempt == attempts);

                if (!isRetryable || lastAttempt) {
                    // Không retry được hoặc hết sềElần ↁEthất bại
                    broadcastStatus(
                            60 + (int) ((tableIndex * 25.0f) / totalTables),
                            "FAILED",
                            "[ERROR] Table " + table.getTableName()
                                    + " : " + e.getMessage()
                                    + (lastAttempt ? " (retry exhausted)"  : " (not retryable)"),
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
                        "[RETRY] Transient error on table " + table.getTableName()
                                + " (attempt " + attempt + "/" + attempts + ")"
                                + ", waiting " + delayMs + "ms. Reason: " + e.getMessage(),
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
    // WEB PHASE 5 : SEQUENCES
    // ─────────────────────────────────────────────────────────────────────────

    private void runCreateSequencesPhaseWeb(
            Connection targetConn,
            Connection sourceConn,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        broadcastStatus(90, "RUNNING", "Starting sequence migration...", true);
        MetadataExtractor extractor = new MetadataExtractor();

        List<String> seqNames = extractor.getSequenceNames(sourceConn, sourceSchema);
        if (seqNames.isEmpty()) {
            broadcastStatus(90, "RUNNING", "No sequences found in source schema.", true);
            return;
        }

        int successCount = 0;
        int skipCount = 0;
        try (Statement stmt = targetConn.createStatement()) {
            for (String seqName  : seqNames) {
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
                        System.err.println("Error creating sequence " + seqName + " : " + e.getMessage());
                    }
                }
            }
        }
        broadcastStatus(91, "RUNNING",
                "Sequence migration completed: " + successCount + " created, " + skipCount + " skipped.", true);
    }

    private static boolean isSequenceAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState) || message.contains("already exists");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 6 : INDEXES
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
        broadcastStatus(91, "RUNNING", "Starting index migration...", true);
        MetadataExtractor extractor = new MetadataExtractor();

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        try (Statement stmt = targetConn.createStatement()) {
            for (TableDefinition table  : tableDefinitions) {
                List<String> idxNames = extractor.getIndexNames(sourceConn, sourceSchema, table.getTableName());
                for (String idxName  : idxNames) {
                    IndexDefinition idx = extractor.extractIndexDefinition(
                            sourceConn, sourceSchema, idxName, sourceDbType);
                    if (idx == null || !idx.isMigratable() || idx.isSystemIndex()) continue;

                    idx.setSourceSchema(sourceSchema);
                    idx.setTargetSchema(targetSchema);

                    List<String> idxSqls = targetDialect.buildCreateIndexSql(idx);
                    for (String idxSql  : idxSqls) {
                        if (idxSql == null || idxSql.isBlank()) continue;
                        try {
                            stmt.execute(normalizeSqlForJdbc(idxSql));
                            successCount++;
                        } catch (SQLException e) {
                            if (isIndexAlreadyExistsError(e)) {
                                skipCount++;
                            } else {
                                errorCount++;
                                System.err.println("Error creating index " + idxName + " : " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        broadcastStatus(91, "RUNNING",
                "Index migration completed: " + successCount + " created, " + skipCount + " skipped, " + errorCount + " failed.", true);
    }

    private static boolean isIndexAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || message.contains("already exists")
                || message.contains("already indexed")
                || message.contains("ora-01408");
    }

    private static boolean isSameEndpoint(DatabaseConfig source, DatabaseConfig target) {
        if (source == null || target == null) {
            return false;
        }

        boolean samePhysicalEndpoint = source.getType() == target.getType()
                && effectiveEndpointIdentity(source).equals(effectiveEndpointIdentity(target))
                && normalize(source.getUsername()).equals(normalize(target.getUsername()));

        if (!samePhysicalEndpoint) {
            return false;
        }

        String sourceSchema = normalize(source.getSchemaName());
        String targetSchema = normalize(target.getSchemaName());

        // Allow same DB/user endpoint when user intentionally picks different explicit schemas.
        if (!sourceSchema.isBlank() && !targetSchema.isBlank() && !sourceSchema.equals(targetSchema)) {
            return false;
        }

        if (source.getType() == DatabaseType.POSTGRESQL && (sourceSchema.isBlank() ^ targetSchema.isBlank())) {
            return false;
        }

        return true;
    }

    private static String normalize(String value) {
        return value == null ? ""  : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeJdbcUrl(String value) {
        return normalize(value);
    }

    private static String normalizeJdbcEndpoint(String value) {
        String normalized = normalizeJdbcUrl(value);
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }

        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }

        return normalized;
    }

    private static boolean hasExplicitJdbcUrl(DatabaseConfig config) {
        return config != null
                && config.getJdbcUrlValue() != null
                && !config.getJdbcUrlValue().isBlank();
    }

    private static String effectiveEndpointIdentity(DatabaseConfig config) {
        if (config == null) {
            return "";
        }

        if (hasExplicitJdbcUrl(config)) {
            return normalizeJdbcEndpoint(config.getJdbcUrlValue());
        }

        return String.join("|",
                normalize(config.getHost()),
                String.valueOf(config.getPort()),
                normalize(config.getDatabaseName())
        );
    }

    private static String endpointScope(DatabaseConfig config) {
        if (config == null) {
            return "";
        }

        return "jdbc:" + effectiveEndpointIdentity(config);
    }

    private static String buildCheckpointNamespace(
            DatabaseConfig source,
            DatabaseConfig target,
            String sourceSchema,
            String targetSchema
    ) {
        String sourceScope = String.join("|",
                source == null || source.getType() == null ? "UNKNOWN"  : source.getType().name(),
            endpointScope(source),
                source == null ? ""  : normalize(source.getUsername()),
                normalize(sourceSchema)
        );

        String targetScope = String.join("|",
                target == null || target.getType() == null ? "UNKNOWN"  : target.getType().name(),
            endpointScope(target),
                target == null ? ""  : normalize(target.getUsername()),
                normalize(targetSchema)
        );

        return sourceScope + "=>" + targetScope;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 7 : FUNCTIONS / PROCEDURES
    // ─────────────────────────────────────────────────────────────────────────

    private void runCreateFunctionsPhaseWeb(
            Connection targetConn,
            Connection sourceConn,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        broadcastStatus(91, "RUNNING", "Starting function/procedure migration...", true);

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
        }

        MetadataExtractor extractor = new MetadataExtractor();
        List<String> fnNames = extractor.getFunctionNames(sourceConn, sourceSchema);
        if (fnNames.isEmpty()) {
            broadcastStatus(91, "RUNNING", "No functions/procedures found in source schema.", true);
            return;
        }

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        try (Statement stmt = targetConn.createStatement()) {
            for (String fnName  : fnNames) {
                FunctionDefinition fn = extractor.extractFunctionDefinition(
                        sourceConn, sourceSchema, fnName, sourceDbType);
                if (fn == null) continue;

                fn.setSourceSchema(sourceSchema);
                fn.setTargetSchema(targetSchema);

                // Detect unsupported features
                if (fn.getFunctionBody() != null) {
                    List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(fn.getFunctionBody());
                    for (String w  : warnings) {
                        System.err.println("  [VIEW-TRANSFORM-WARN] " + fnName + " : " + w);
                    }
                }

                List<String> fnSqls = targetDialect.buildCreateFunctionSql(fn, transformer);
                for (String fnSql  : fnSqls) {
                    if (fnSql == null || fnSql.isBlank()) continue;
                    String normalized = normalizeRoutineSqlForJdbc(fnSql, targetDialect);
                    try {
                        stmt.execute(normalized);
                        successCount++;
                    } catch (SQLException e) {
                        if (isFunctionAlreadyExistsError(e)) {
                            skipCount++;
                        } else {
                            errorCount++;
                            String fnError = "Error creating function/procedure " + fnName + " : " + e.getMessage();
                            System.err.println(fnError);
                            System.err.println("  [DEBUG] " + getSqlContextAt(normalized, e));
                            broadcastStatus(92, "RUNNING", fnError, true);
                        }
                    }
                }
            }
        }
        broadcastStatus(92, "RUNNING",
                "Function migration completed: " + successCount + " created, " + skipCount + " skipped, " + errorCount + " failed.", true);
    }

    private static boolean isFunctionAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || "42723".equals(sqlState)
                || message.contains("already exists")
                || message.contains("duplicate function");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB PHASE 8 : TRIGGERS
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
        broadcastStatus(92, "RUNNING", "Starting trigger migration...", true);

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
        }

        MetadataExtractor extractor = new MetadataExtractor();
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        try (Statement stmt = targetConn.createStatement()) {
            for (TableDefinition table  : tableDefinitions) {
                List<String> trigNames = extractor.getTriggerNamesForTable(
                        sourceConn, sourceSchema, table.getTableName());
                for (String trigName  : trigNames) {
                    TriggerDefinition trig = extractor.extractTriggerDefinition(
                            sourceConn, sourceSchema, trigName, sourceDbType);
                    if (trig == null) continue;

                    trig.setSourceSchema(sourceSchema);
                    trig.setTargetSchema(targetSchema);

                    // Detect unsupported features
                    if (trig.getTriggerBody() != null) {
                        List<String> warnings = OracleToPgsqlTransformer.detectUnsupportedFeatures(trig.getTriggerBody());
                        for (String w  : warnings) {
                            System.err.println("  [VIEW-TRANSFORM-WARN] " + trigName + " : " + w);
                        }
                    }

                    List<String> trigSqls = targetDialect.buildCreateTriggerSql(trig, transformer);
                    for (String trigSql  : trigSqls) {
                        if (trigSql == null || trigSql.isBlank()) continue;
                        try {
                            stmt.execute(normalizeSqlForJdbc(trigSql));
                            successCount++;
                        } catch (SQLException e) {
                            if (isTriggerAlreadyExistsError(e)) {
                                skipCount++;
                            } else {
                                errorCount++;
                                System.err.println("Error creating trigger " + trigName + " : " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        broadcastStatus(92, "RUNNING",
                "Trigger migration completed: " + successCount + " created, " + skipCount + " skipped, " + errorCount + " failed.", true);
    }

    private static boolean isTriggerAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? ""  : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P06".equals(sqlState)
                || (message.contains("already exists") && message.contains("trigger"))
                || message.contains("ora-00955");
    }

    private static String getSqlContextAt(String sql, SQLException e) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)position\\s*:\\s*(\\d+)")
                    .matcher(e.getMessage());
            if (!m.find()) return sql.substring(0, Math.min(300, sql.length()));
            int pos = Integer.parseInt(m.group(1));
            int start = Math.max(0, pos - 40);
            int end = Math.min(sql.length(), pos + 40);
            return "position " + pos + ": ..." + sql.substring(start, end) + "...";
        } catch (Exception ex) {
            return sql.substring(0, Math.min(300, sql.length()));
        }
    }
}

