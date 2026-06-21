package org.example.migrationdbweb.migratetool;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private final int dataThreadCount;
    private final boolean truncateTarget;
    private final boolean copyNewOnly;
    private final boolean copyOnlyTargetEmptyTables;
    private final boolean exportInsertSqlFiles;
    private final boolean onlyExportInsertSqlFiles;
    private final String exportInsertSqlDir;
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
    private final boolean migrateViews;
    private final boolean replaceExistingViews;
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
            int dataThreadCount,
            boolean truncateTarget,
            boolean copyNewOnly,
            boolean copyOnlyTargetEmptyTables,
            boolean exportInsertSqlFiles,
            boolean onlyExportInsertSqlFiles,
            String exportInsertSqlDir,
            Integer limitRows,
            Set<String> includeTables,
            Set<String> excludeTables,
            boolean migrateSequences,
            boolean migrateIndexes,
            boolean migrateFunctions,
            boolean migrateTriggers,
            boolean migrateViews,
            boolean replaceExistingViews,
            Set<String> includeViews,
            Set<String> excludeViews,
            MigrationRetryPolicy retryPolicy
    ) {
        this.ui = ui;
        this.isStructureOnly = isStructureOnly;
        this.isDataOnly = isDataOnly;
        this.sourceConfig = sourceConfig;
        this.targetConfig = targetConfig;
        this.sourceSchema = sourceSchema;
        this.targetSchema = targetSchema;
        this.batchSize = Math.max(1, batchSize);
        this.dataThreadCount = Math.max(0, dataThreadCount);
        this.truncateTarget = truncateTarget;
        this.copyNewOnly = copyNewOnly;
        this.copyOnlyTargetEmptyTables = copyOnlyTargetEmptyTables;
        this.exportInsertSqlFiles = exportInsertSqlFiles;
        this.onlyExportInsertSqlFiles = onlyExportInsertSqlFiles;
        this.exportInsertSqlDir = exportInsertSqlDir == null ? "migration-sql-export" : exportInsertSqlDir.trim();
        this.limitRows = (limitRows != null && limitRows > 0) ? limitRows : null;
        this.includeTables = normalizeTableFilter(includeTables);
        this.excludeTables = normalizeTableFilter(excludeTables);
        // RetryPolicy từ UI  EKHÔNG dùng fromEnvironment() nữa
        this.retryPolicy = retryPolicy != null ? retryPolicy
                : MigrationRetryPolicy.fromEnvironment();

        // Advanced migration flags (migrate X when selected)
        this.migrateSequences = migrateSequences;
        this.migrateIndexes   = migrateIndexes;
        this.migrateFunctions = migrateFunctions;
        this.migrateTriggers  = migrateTriggers;
        this.migrateViews     = migrateViews;
        this.replaceExistingViews = replaceExistingViews;
        this.includeViews     = normalizeTableFilter(includeViews);
        this.excludeViews     = normalizeTableFilter(excludeViews);
    }

    /**
     * CHẠY DƯỚI BACKGROUND THREAD (Không được thao tác UI ềEđây)
     */
    @Override
    protected Void doInBackground() throws Exception {
        ConnectionManager manager = ConnectionManager.getInstance();
        String sourcePoolId = "UI_SOURCE_" + System.currentTimeMillis();
        String targetPoolId = "UI_TARGET_" + System.currentTimeMillis();
        String checkpointNamespace = retryPolicy.isResumeEnabled()
            ? buildCheckpointNamespace(sourceConfig, targetConfig, sourceSchema, targetSchema)
            : "";
        MigrationCheckpointStore checkpointStore = retryPolicy.isResumeEnabled()
            ? new MigrationCheckpointStore(retryPolicy.getResumeStateFile(), checkpointNamespace)
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
                configurePostgresSchema(sourceConn, sourceConfig, sourceSchema, false);
                configurePostgresSchema(targetConn, targetConfig, targetSchema, true);
                configureOracleSchema(sourceConn, sourceConfig, sourceSchema);
                configureOracleSchema(targetConn, targetConfig, targetSchema);
                publish("Tùy chọn: truncate=" + truncateTarget
                    + ", copyNewOnly=" + copyNewOnly
                    + ", copyOnlyTargetEmptyTables=" + copyOnlyTargetEmptyTables
                    + ", limit=" + (limitRows == null ? "ALL" : limitRows)
                    + ", exportInsertSql=" + exportInsertSqlFiles
                    + ", onlyExportSql=" + onlyExportInsertSqlFiles);
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
                    publish("Resume namespace: " + checkpointNamespace);
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

                publish("Tìm thấy " + tableNames.size() + " bảng. Đang trích xuất cấu trúc chi tiết (batch)...");
                List<TableDefinition> allTables = metadataExtractor.extractAllTableDefinitions(
                        sourceConn, sourceSchema, tableNames, !isDataOnly
                );
                for (TableDefinition td : allTables) {
                    td.setTargetSchema(targetSchema);
                }
                publish("  -> Đã đọc metadata " + allTables.size() + " bảng (batch mode).");
                setProgress(25);

                // ── Trích xuất SEQUENCES ────────────────────────────────
                if (migrateSequences && !isDataOnly) {
                    publish("Đang trích xuất SEQUENCES từ schema nguồn...");
                    allSequences = extractAllSequences(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType());
                } else if (migrateSequences) {
                    publish("Bo qua trich xuat SEQUENCES do chon che do Data Only.");
                }

                // ── Trích xuất INDEXES (cho các bảng đã chọn) ─────────
                if (migrateIndexes && !isDataOnly) {
                    publish("Đang trích xuất INDEXES từ schema nguồn...");
                    allIndexes = extractAllIndexes(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType(), tableNames);
                } else if (migrateIndexes) {
                    publish("Bo qua trich xuat INDEXES do chon che do Data Only.");
                }

                // ── Trích xuất FUNCTIONS / PROCEDURES ─────────────────
                if (migrateFunctions && !isDataOnly) {
                    publish("Đang trích xuất FUNCTIONS/PROCEDURES từ schema nguồn...");
                    allFunctions = extractAllFunctions(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType());
                } else if (migrateFunctions) {
                    publish("Bo qua trich xuat FUNCTIONS/PROCEDURES do chon che do Data Only.");
                }

                // ── Trích xuất TRIGGERS (cho các bảng đã chọn) ──────
                if (migrateTriggers && !isDataOnly) {
                    publish("Đang trích xuất TRIGGERS từ schema nguồn...");
                    allTriggers = extractAllTriggers(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType(), tableNames);
                } else if (migrateTriggers) {
                    publish("Bo qua trich xuat TRIGGERS do chon che do Data Only.");
                }

                // ── Trích xuất VIEWS (với topological sort) ────────
                if (migrateViews && !isDataOnly) {
                    publish("Đang trích xuất VIEWS từ schema nguồn...");
                    allViews = extractAllViews(metadataExtractor, sourceConn, sourceSchema, sourceConfig.getType());
                } else if (migrateViews) {
                    publish("Bo qua trich xuat views do chon che do Data Only.");
                    allViews = new ArrayList<>();
                } else {
                    publish("Bo qua trich xuat views (khong chon migrate views).");
                    allViews = new ArrayList<>();
                }

                SqlDialect sourceDialect = DialectFactory.getDialect(sourceConfig.getType());
                SqlDialect targetDialect = DialectFactory.getDialect(targetConfig.getType());
                allTables = deduplicateTablesForTarget(allTables, targetDialect);
                allTables = TableDependencySortUtil.sortByForeignKeyDependency(allTables);
                publish("Da sap xep thu tu bang theo phu thuoc FK (parent truoc, child sau).");

                if (allTables.isEmpty()) {
                    publish("Không còn bảng hợp lệ để migrate sau khi đối chiếu tên bảng trên target.");
                    setProgress(100);
                    return null;
                }

                if (!isDataOnly) {
                    publish("--- BẮT ĐẦU TẠO CẤU TRÚC BẢNG (DDL) TRÊN TARGET ---");
                    runCreateTablesPhase(targetConn, allTables, targetDialect);
                    setProgress(45);

                    // Phase 2: TẠO SEQUENCES (sau table, trước data  Evì trigger dùng sequence)
                    if (migrateSequences) {
                        publish("--- TẠO SEQUENCES TRÊN TARGET ---");
                        runCreateSequencesPhase(targetConn, allSequences, targetDialect);
                    }

                    // Phase 3: TẠO FUNCTIONS/PROCEDURES (trước trigger  Etrigger có thềEgọi function)
                    if (migrateFunctions) {
                        publish("--- TẠO FUNCTIONS/PROCEDURES TRÊN TARGET ---");
                        runCreateFunctionsPhase(targetConn, allFunctions, sourceConfig.getType(), targetDialect);
                    }
                    setProgress(50);
                } else {
                    publish("Bỏ qua bước tạo cấu trúc do chọn chế đềEData Only.");
                    setProgress(60);
                }

                if (!isStructureOnly) {
                    publish("--- BẮT ĐẦU CHUYỂN DỮ LIỆU (DML) ---");
                        SqlGenerator sqlGenerator = new SqlGenerator(
                            sourceDialect,
                            targetDialect,
                            sourceSchema,
                            targetSchema
                        );

                    if (truncateTarget) {
                        publish("--- TRUNCATE DỮ LIỆU CŨ TRÊN TARGET ---");
                        runTruncatePhase(targetConn, allTables, targetDialect);
                        if (checkpointStore != null) {
                            checkpointStore.clear();
                            publish("Resume state da duoc reset do chon truncate target.");
                        }
                    }

                        DataTransferService.InsertSqlExportOptions insertSqlExportOptions =
                            resolveInsertSqlExportOptions(isStructureOnly);

                    runDataMigrationPhaseMultiThread(
                            manager,
                            sourcePoolId,
                            targetPoolId,
                            sqlGenerator,
                            allTables,
                            checkpointStore,
                            insertSqlExportOptions
                    );
                } else {
                    publish("Bo qua buoc chuyen du lieu do chon che do Structure Only.");
                    setProgress(90);
                }

                if (!isDataOnly) {
                    publish("--- BẮT ĐẦU THÊM KHÓA NGOẠI (FOREIGN KEYS) ---");
                    runAddForeignKeysPhase(targetConn, allTables, targetDialect);

                    // Indexes: sau FK + sau data  Eindex cần data đềEbuild
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

                // ── VIEWS: chỉ chạy nếu user chọn migrate views và KHONG o DATA_ONLY ──
                if (migrateViews && !isDataOnly) {
                    publish("--- TẠO VIEWS TRÊN TARGET ---");
                    runCreateViewsPhase(
                            targetConn,
                            allViews,
                            sourceSchema,
                            targetSchema,
                            sourceConfig.getType(),
                            targetDialect,
                            replaceExistingViews
                    );
                } else if (migrateViews) {
                    publish("Bỏ qua views do chọn chế độ Data Only.");
                } else {
                    publish("Bỏ qua views (không chọn migrate views).");
                }
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
                boolean rawOracleTableDdlMode = isOracleToOracleDirectDdlMode(targetDialect)
                        && table.getDdlText() != null
                        && !table.getDdlText().isBlank();
                boolean rawPostgresTableDdlMode = isPostgresToPostgresDirectDdlMode(targetDialect)
                    && table.getDdlText() != null
                    && !table.getDdlText().isBlank();

                String createSql = normalizeSqlForJdbc(targetDialect.buildCreateTableSql(table));
                if (targetDialect instanceof OracleDialect
                        && targetSchema != null
                        && !targetSchema.isBlank()) {
                    createSql = qualifyOracleCreateTableSql(createSql, targetDialect, targetSchema, table.getTableName());
                }
                try {
                    statement.execute(createSql);
                    publish("  -> Đã tạo bảng " + table.getTableName());
                } catch (SQLException e) {
                    if (isTableAlreadyExistsError(e)) {
                        publish("  -> Bỏ qua bảng đã tồn tại: " + table.getTableName());
                    } else if (rawOracleTableDdlMode
                            && targetDialect instanceof OracleDialect
                            && isOracleRawTableDdlIncompatibleError(e)) {
                        publish("  -> [WARN] Raw Oracle DDL khong tuong thich cho bang "
                                + table.getTableName() + ". Se fallback sang DDL tong hop. Ly do: " + e.getMessage());

                        String fallbackCreateSql = buildOracleGeneratedCreateTableSql(table, targetDialect, targetSchema);
                        try {
                            statement.execute(fallbackCreateSql);
                            publish("  -> Đã tạo bảng " + table.getTableName() + " (fallback DDL tong hop)");
                        } catch (SQLException fallbackEx) {
                            publish("  -> [DDL-DEBUG] RAW CREATE SQL: " + abbreviateSqlForLog(createSql));
                            publish("  -> [DDL-DEBUG] FALLBACK CREATE SQL: " + abbreviateSqlForLog(fallbackCreateSql));
                            throw fallbackEx;
                        }
                    } else if (rawPostgresTableDdlMode
                            && targetDialect instanceof PostgresDialect) {
                        publish("  -> [WARN] Raw PostgreSQL DDL khong tuong thich cho bang "
                                + table.getTableName() + ". Se fallback sang DDL tong hop. Ly do: " + e.getMessage());

                        String fallbackCreateSql = buildOracleGeneratedCreateTableSql(table, targetDialect, targetSchema);
                        try {
                            statement.execute(fallbackCreateSql);
                            publish("  -> Đã tạo bảng " + table.getTableName() + " (fallback DDL tong hop)");
                        } catch (SQLException fallbackEx) {
                            publish("  -> [DDL-DEBUG] RAW CREATE SQL: " + abbreviateSqlForLog(createSql));
                            publish("  -> [DDL-DEBUG] FALLBACK CREATE SQL: " + abbreviateSqlForLog(fallbackCreateSql));
                            throw fallbackEx;
                        }
                    } else {
                        publish("  -> [DDL-DEBUG] CREATE TABLE SQL: " + abbreviateSqlForLog(createSql));
                        throw e;
                    }
                }

                if (targetDialect instanceof OracleDialect) {
                    ensureOracleIdentityColumnsAllowExplicitInsert(statement, table, targetDialect);
                }
                if (targetDialect instanceof PostgresDialect) {
                    ensurePostgresIdentityColumnsAllowExplicitInsert(statement, table, targetDialect);
                }
            }
        }
    }

    private static String buildOracleGeneratedCreateTableSql(
            TableDefinition table,
            SqlDialect targetDialect,
            String targetSchema
    ) {
        TableDefinition generated = new TableDefinition(table.getTableName());
        generated.setSourceDialect(table.getSourceDialect());
        generated.setSourceSchema(table.getSourceSchema());
        generated.setTargetSchema(table.getTargetSchema());

        for (ColumnDefinition column : table.getColumns()) {
            generated.addColumn(column);
        }
        for (String pk : table.getPrimaryKeys()) {
            generated.addPrimaryKey(pk);
        }
        for (ForeignKeyDefinition fk : table.getForeignKeys()) {
            generated.addForeignKey(fk);
        }

        String fallbackCreateSql = normalizeSqlForJdbc(targetDialect.buildCreateTableSql(generated));
        if (targetDialect instanceof OracleDialect
                && targetSchema != null
                && !targetSchema.isBlank()) {
            fallbackCreateSql = qualifyOracleCreateTableSql(
                    fallbackCreateSql,
                    targetDialect,
                    targetSchema,
                    table.getTableName()
            );
        }
        return fallbackCreateSql;
    }

    private static String qualifyOracleCreateTableSql(
            String createSql,
            SqlDialect targetDialect,
            String schema,
            String tableName
    ) {
        if (createSql == null || targetDialect == null || schema == null || schema.isBlank() || tableName == null || tableName.isBlank()) {
            return createSql;
        }

        String quotedTable = targetDialect.quoteIdentifier(tableName.toUpperCase(Locale.ROOT));
        String prefix = "CREATE TABLE " + quotedTable;
        if (!createSql.startsWith(prefix)) {
            return createSql;
        }

        return "CREATE TABLE "
                + targetDialect.quoteIdentifier(schema)
                + "."
                + quotedTable
                + createSql.substring(prefix.length());
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
                    + qualifyTableName(
                        targetDialect,
                        targetSchema,
                        normalizeTableNameForDialect(targetDialect, table.getTableName())
                    )
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

    private void ensurePostgresIdentityColumnsAllowExplicitInsert(
            Statement statement,
            TableDefinition table,
            SqlDialect targetDialect
    ) throws SQLException {
        for (ColumnDefinition column : table.getColumns()) {
            if (!column.isAutoIncrement()) {
                continue;
            }

            String alterIdentitySql = "ALTER TABLE "
                    + qualifyTableName(
                        targetDialect,
                        targetSchema,
                        normalizeTableNameForDialect(targetDialect, table.getTableName())
                    )
                    + " ALTER COLUMN "
                    + targetDialect.quoteIdentifier(column.getName())
                    + " SET GENERATED BY DEFAULT";

            try {
                statement.execute(alterIdentitySql);
                publish("  -> Chuan hoa identity BY DEFAULT cho "
                        + table.getTableName() + "." + column.getName());
            } catch (SQLException e) {
                if (isPostgresIdentityAlterNotApplicableError(e)) {
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
                boolean rawForeignKeyDdlMode = isSameTypeRawDdlMode(targetDialect, table.getSourceDialect())
                        && table.getForeignKeyDdls() != null
                        && !table.getForeignKeyDdls().isEmpty();

                List<String> fkStatements = targetDialect.buildAddForeignKeySql(table);

                for (String fkSql : fkStatements) {
                    try {
                        statement.execute(normalizeSqlForJdbc(fkSql));
                    } catch (SQLException e) {
                        if (isConstraintAlreadyExistsError(e)) {
                            publish("  -> Bo qua FK da ton tai cho bang " + table.getTableName() + ": " + e.getMessage());
                            continue;
                        }

                        if (rawForeignKeyDdlMode) {
                            publish("  -> [WARN] Raw FK DDL khong tuong thich cho bang "
                                    + table.getTableName() + ". Se fallback sang FK tong hop. Ly do: " + e.getMessage());

                            TableDefinition fallbackTable = buildGeneratedForeignKeyTableDefinition(table);
                            for (String fallbackFkSql : targetDialect.buildAddForeignKeySql(fallbackTable)) {
                                try {
                                    statement.execute(normalizeSqlForJdbc(fallbackFkSql));
                                } catch (SQLException fallbackEx) {
                                    if (isConstraintAlreadyExistsError(fallbackEx) || isIncompatibleForeignKeyError(fallbackEx)) {
                                        publish("  -> Bo qua FK khong the ap dung cho bang " + table.getTableName() + ": " + fallbackEx.getMessage());
                                        continue;
                                    }
                                    throw fallbackEx;
                                }
                            }
                            break;
                        }

                        if (isIncompatibleForeignKeyError(e)) {
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
            throw new IllegalArgumentException("Target dialect khong hop le");
        }

        List<TableDefinition> oracleDeleteFallbackTables = new ArrayList<>();

        try (Statement statement = targetConn.createStatement()) {
            for (TableDefinition table : allTables) {
                ensureNotCancelled();
                String tableNameForSql = normalizeTableNameForDialect(targetDialect, table.getTableName());
                String qualifiedTable = qualifyTableName(targetDialect, targetSchema, tableNameForSql);
                String truncateSql;

                if (targetDialect instanceof PostgresDialect) {
                    truncateSql = "TRUNCATE TABLE "
                            + qualifiedTable
                            + " RESTART IDENTITY CASCADE";
                } else if (targetDialect instanceof OracleDialect) {
                    truncateSql = "TRUNCATE TABLE " + qualifiedTable;
                } else {
                    truncateSql = "TRUNCATE TABLE " + qualifiedTable;
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
                    String qualifiedTable = qualifyTableName(
                        targetDialect,
                        targetSchema,
                        normalizeTableNameForDialect(targetDialect, table.getTableName())
                    );
                    String deleteSql = "DELETE FROM "
                        + qualifiedTable;

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
                        "Không thể xóa dữ liệu cho các bảng Oracle do ràng buộc FK vòng lặp hoặc dữ liệu tham chiếu còn tồn tại: "
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

            if (!includeTables.isEmpty() && !matchesAnyWildcardPattern(normalized, includeTables)) {
                continue;
            }

            if (matchesAnyWildcardPattern(normalized, excludeTables)) {
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

    private boolean matchesAnyWildcardPattern(String normalizedObjectName, Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }

        for (String pattern : patterns) {
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

        if (!normalizedPattern.contains("*")) {
            return normalizedObjectName.equals(normalizedPattern);
        }

        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char ch = normalizedPattern.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
            }
        }
        regex.append("$");

        return java.util.regex.Pattern.compile(regex.toString()).matcher(normalizedObjectName).matches();
    }

    private void runDataMigrationPhaseMultiThread(
            ConnectionManager manager,
            String sourcePoolId,
            String targetPoolId,
            SqlGenerator sqlGenerator,
            List<TableDefinition> allTables,
            MigrationCheckpointStore checkpointStore,
            DataTransferService.InsertSqlExportOptions insertSqlExportOptions
    ) throws SQLException {
        List<TableMigrationPlan> plans = buildTableMigrationPlans(manager, sourcePoolId, allTables);
        if (plans.isEmpty()) {
            publish("Khong co bang nao de migrate trong phase data.");
            setProgress(90);
            return;
        }

        publish("Da thong ke so dong cho " + plans.size()
            + " bang. Se migrate theo FK-level truoc, sau do tang dan du lieu trong tung level.");

        boolean exportOnlyToFile = insertSqlExportOptions != null && insertSqlExportOptions.exportOnlyToFile();
        SqlDialect targetDialect = DialectFactory.getDialect(targetConfig.getType());
        List<TableMigrationPlan> runnablePlans = new ArrayList<>();
        if (exportOnlyToFile) {
            for (TableMigrationPlan plan : plans) {
                TableDefinition table = plan.table();
                if (checkpointStore != null && checkpointStore.isTableCompleted(table.getTableName())) {
                    publish("  -> Bo qua bang da migrate truoc do (resume): " + table.getTableName());
                    continue;
                }
                runnablePlans.add(plan);
            }
        } else {
            try (Connection targetPlanConn = manager.getConnection(targetPoolId)) {
                configurePostgresSchema(targetPlanConn, targetConfig, targetSchema, true);
                configureOracleSchema(targetPlanConn, targetConfig, targetSchema);

                for (TableMigrationPlan plan : plans) {
                    TableDefinition table = plan.table();
                    if (checkpointStore != null && checkpointStore.isTableCompleted(table.getTableName())) {
                        publish("  -> Bo qua bang da migrate truoc do (resume): " + table.getTableName());
                        continue;
                    }

                    if (!doesTableExist(targetPlanConn, targetDialect, targetSchema, table.getTableName())) {
                        publish("  -> Bo qua bang " + table.getTableName()
                                + " vi target table khong ton tai (tranh ORA-00942). ");
                        continue;
                    }

                    if (copyOnlyTargetEmptyTables
                            && !isTargetTableEmpty(targetPlanConn, table, targetDialect)) {
                        publish("  -> Bo qua bang " + table.getTableName()
                                + " vi target da co du lieu (copy-only-target-empty). ");
                        continue;
                    }

                    runnablePlans.add(plan);
                }
            }
        }

        if (runnablePlans.isEmpty()) {
            publish("Tat ca bang da hoan tat theo checkpoint. Bo qua phase data.");
            setProgress(90);
            return;
        }

        int threadCount = resolveDataMigrationThreadCount(runnablePlans.size());
        publish("Data phase multithread: " + threadCount + " thread(s), " + runnablePlans.size() + " bang can migrate.");

        Map<Integer, List<TableMigrationPlan>> plansByLevel = new TreeMap<>();
        for (TableMigrationPlan plan : runnablePlans) {
            plansByLevel
                .computeIfAbsent(plan.fkLevel(), ignored -> new ArrayList<>())
                .add(plan);
        }

        SQLException firstFailure = null;
        int completed = 0;
        int totalSubmitted = runnablePlans.size();
        Map<String, List<String>> exportedSqlFilesByTable = new LinkedHashMap<>();

        for (Map.Entry<Integer, List<TableMigrationPlan>> levelEntry : plansByLevel.entrySet()) {
            ensureNotCancelled();

            int fkLevel = levelEntry.getKey();
            List<TableMigrationPlan> levelPlans = levelEntry.getValue();
            int levelThreadCount = Math.max(1, Math.min(threadCount, levelPlans.size()));
            publish("--- FK LEVEL " + fkLevel + ": " + levelPlans.size()
                    + " bang, " + levelThreadCount + " thread(s) ---");

            ExecutorService levelExecutor = Executors.newFixedThreadPool(levelThreadCount);
            CompletionService<TableTransferOutcome> levelCompletionService =
                    new ExecutorCompletionService<>(levelExecutor);

            try {
                for (TableMigrationPlan plan : levelPlans) {
                    TableDefinition table = plan.table();
                    final String tableName = table.getTableName();
                    final int startOffset = checkpointStore == null ? 0 : checkpointStore.getTableOffset(tableName);
                    final boolean effectiveCopyNewOnly = !exportOnlyToFile
                            && (copyNewOnly
                            || (retryPolicy.isResumeEnabled() && !table.getPrimaryKeys().isEmpty()));

                    if (startOffset > 0) {
                        publish("  -> Resume bang " + tableName + " tu offset " + startOffset + ".");
                    }

                    if (effectiveCopyNewOnly && table.getPrimaryKeys().isEmpty()) {
                        publish("  -> Bang " + tableName + " khong co PK, copyNewOnly khong the loc trung theo PK.");
                    }

                    levelCompletionService.submit(() -> {
                        if (isCancelled()) {
                            return TableTransferOutcome.failed(tableName, "Tien trinh da bi huy.");
                        }

                        try {
                            DataTransferService transferService = new DataTransferService(sqlGenerator, retryPolicy);
                            publish("Dang sao chep du lieu bang " + tableName + " (rows=" + plan.rowCount() + ")...");

                            DataTransferService.TransferResult result = transferTableWithRetry(
                                    manager,
                                    sourcePoolId,
                                    targetPoolId,
                                    transferService,
                                    table,
                                    effectiveCopyNewOnly,
                                    checkpointStore,
                                    startOffset,
                                    insertSqlExportOptions
                            );

                            if (checkpointStore != null) {
                                if (result.isLimitReached()) {
                                    checkpointStore.updateTableOffset(tableName, result.getTransferredRows());
                                } else {
                                    checkpointStore.markTableCompleted(tableName, result.getTransferredRows());
                                }
                            }

                            return TableTransferOutcome.success(
                                    tableName,
                                    result.getTransferredRows(),
                                    result.getSkippedRows(),
                                    result.isLimitReached(),
                                    result.getExportedSqlFiles()
                            );
                        } catch (SQLException | RuntimeException ex) {
                            SQLException sqlException = extractSqlException(ex);
                            if (isTableNotExistsError(sqlException)) {
                                String detail = sqlException == null ? ex.getMessage() : sqlException.getMessage();
                                return TableTransferOutcome.skippedTableNotExists(tableName, detail);
                            }
                            return TableTransferOutcome.failed(tableName, ex.getMessage());
                        }
                    });
                }

                for (int i = 0; i < levelPlans.size(); i++) {
                    ensureNotCancelled();
                    Future<TableTransferOutcome> future = levelCompletionService.take();
                    TableTransferOutcome outcome = future.get();

                    if (outcome.skippedTable()) {
                        publish("  -> BO QUA bang " + outcome.tableName()
                                + " do ORA-00942 (table/view khong ton tai): " + outcome.errorMessage());
                    } else if (!outcome.success()) {
                        if (firstFailure == null) {
                            firstFailure = new SQLException(
                                    "Khong the migrate bang " + outcome.tableName() + ": " + outcome.errorMessage()
                            );
                        }
                        publish("  -> LOI bang " + outcome.tableName() + ": " + outcome.errorMessage());
                    } else {
                        publish("  -> Hoan tat bang " + outcome.tableName()
                                + " | copied=" + outcome.transferredRows()
                                + " | skipped=" + outcome.skippedRows()
                                + (outcome.limitReached() ? " | dat nguong limit" : ""));
                        if (!outcome.exportedSqlFiles().isEmpty()) {
                            exportedSqlFilesByTable.put(outcome.tableName(), outcome.exportedSqlFiles());
                        }
                    }

                    completed++;
                    setProgress(60 + (int) ((completed * 30.0f) / Math.max(1, totalSubmitted)));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SQLException("Bi ngat trong khi cho cac task migrate du lieu hoan tat.", ex);
            } catch (ExecutionException ex) {
                throw new SQLException("Task migrate du lieu gap loi thuc thi: " + ex.getMessage(), ex);
            } finally {
                levelExecutor.shutdownNow();
                try {
                    levelExecutor.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            if (firstFailure != null) {
                break;
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }

        if (insertSqlExportOptions != null && !exportedSqlFilesByTable.isEmpty()) {
            writeOracleAllScript(insertSqlExportOptions.outputDirectory(), runnablePlans, exportedSqlFilesByTable);
        }
    }

    private int resolveDataMigrationThreadCount(int tableCount) {
        int sourcePool = sourceConfig == null ? 10 : Math.max(1, sourceConfig.getMaximumPoolSize());
        int targetPool = targetConfig == null ? 10 : Math.max(1, targetConfig.getMaximumPoolSize());
        int maxByPool = Math.max(1, Math.min(sourcePool, targetPool) - 2);
        int maxByTable = Math.max(1, tableCount);

        if (dataThreadCount > 0) {
            return Math.max(1, Math.min(dataThreadCount, Math.min(maxByPool, maxByTable)));
        }

        int suggested = Math.min(4, maxByTable);
        return Math.max(1, Math.min(suggested, maxByPool));
    }

    private List<TableMigrationPlan> buildTableMigrationPlans(
            ConnectionManager manager,
            String sourcePoolId,
            List<TableDefinition> allTables
    ) throws SQLException {
        List<TableMigrationPlan> plans = new ArrayList<>();
        SqlDialect sourceDialect = DialectFactory.getDialect(sourceConfig.getType());
        Map<TableDefinition, Integer> fkLevels = TableDependencySortUtil.computeForeignKeyLevels(allTables);

        try (Connection sourceConn = manager.getConnection(sourcePoolId)) {
            configurePostgresSchema(sourceConn, sourceConfig, sourceSchema, false);
            configureOracleSchema(sourceConn, sourceConfig, sourceSchema);

            for (int i = 0; i < allTables.size(); i++) {
                TableDefinition table = allTables.get(i);
                if (!doesTableExist(sourceConn, sourceDialect, sourceSchema, table.getTableName())) {
                    publish("  -> [WARN] Bo qua bang nguon khong ton tai: " + table.getTableName());
                    continue;
                }
                long rowCount = countRowsForTable(sourceConn, table, sourceDialect);
            int fkLevel = fkLevels.getOrDefault(table, 0);
            plans.add(new TableMigrationPlan(table, rowCount, i, fkLevel));
            }
        }

        plans.sort(
            Comparator.comparingInt(TableMigrationPlan::fkLevel)
                .thenComparingLong(TableMigrationPlan::rowCount)
                        .thenComparingInt(TableMigrationPlan::originalOrder)
        );
        return plans;
    }

    private long countRowsForTable(Connection sourceConn, TableDefinition table, SqlDialect sourceDialect) {
        String sourceTableName = normalizeTableNameForDialect(sourceDialect, table.getTableName());
        String countSql = "SELECT COUNT(*) FROM " + qualifyTableName(sourceDialect, sourceSchema, sourceTableName);
        try (Statement statement = sourceConn.createStatement();
             ResultSet rs = statement.executeQuery(countSql)) {
            if (rs.next()) {
                return Math.max(0L, rs.getLong(1));
            }
        } catch (SQLException ex) {
            publish("  -> [WARN] Khong the dem so dong bang " + table.getTableName()
                    + ", se xep cuoi hang. Ly do: " + ex.getMessage());
        }
        return Long.MAX_VALUE;
    }

    private boolean isTargetTableEmpty(Connection targetConn, TableDefinition table, SqlDialect targetDialect) {
        String sql = buildTargetHasDataSql(table, targetDialect);
        try (Statement statement = targetConn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return !rs.next();
        } catch (SQLException ex) {
            publish("  -> [WARN] Khong the kiem tra target co du lieu hay khong cho bang "
                    + table.getTableName() + ". Se coi nhu bang da co du lieu. Ly do: " + ex.getMessage());
            return false;
        }
    }

    private String buildTargetHasDataSql(TableDefinition table, SqlDialect targetDialect) {
        if (targetDialect == null) {
            throw new IllegalArgumentException("Target dialect khong hop le");
        }

        String targetTableName = normalizeTableNameForDialect(targetDialect, table.getTableName());
        String qualifiedTable = qualifyTableName(targetDialect, targetSchema, targetTableName);
        if (targetDialect instanceof OracleDialect) {
            return "SELECT 1 FROM " + qualifiedTable + " WHERE ROWNUM = 1";
        }
        return "SELECT 1 FROM " + qualifiedTable + " LIMIT 1";
    }

    private boolean doesTableExist(
            Connection conn,
            SqlDialect dialect,
            String schema,
            String tableName
    ) {
        String normalizedTableName = normalizeTableNameForDialect(dialect, tableName);
        String probeSql = "SELECT 1 FROM "
                + qualifyTableName(dialect, schema, normalizedTableName)
                + " WHERE 1 = 0";

        try (Statement statement = conn.createStatement()) {
            statement.executeQuery(probeSql);
            return true;
        } catch (SQLException ex) {
            if (isTableNotExistsError(ex)) {
                return false;
            }

            publish("  -> [WARN] Khong the xac minh su ton tai cua bang " + tableName
                    + ". Se tiep tuc migrate de tranh bo sot. Ly do: " + ex.getMessage());
            return true;
        }
    }

    private static String normalizeTableNameForDialect(SqlDialect dialect, String tableName) {
        if (tableName == null) {
            return "";
        }
        String trimmed = tableName.trim();
        if (dialect instanceof OracleDialect) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed;
    }

    private static String qualifyTableName(SqlDialect dialect, String schema, String tableName) {
        String quotedTable = dialect.quoteIdentifier(tableName);
        if (schema == null || schema.isBlank()) {
            return quotedTable;
        }
        return dialect.quoteIdentifier(schema) + "." + quotedTable;
    }

    private record TableMigrationPlan(TableDefinition table, long rowCount, int originalOrder, int fkLevel) {}

    private record TableTransferOutcome(
            String tableName,
            int transferredRows,
            int skippedRows,
            boolean limitReached,
            List<String> exportedSqlFiles,
            boolean skippedTable,
            boolean success,
            String errorMessage
    ) {
        private static TableTransferOutcome success(
                String tableName,
                int transferredRows,
                int skippedRows,
            boolean limitReached,
            List<String> exportedSqlFiles
        ) {
            return new TableTransferOutcome(
                tableName,
                transferredRows,
                skippedRows,
                limitReached,
                exportedSqlFiles == null ? List.of() : List.copyOf(exportedSqlFiles),
                false,
                true,
                null
            );
        }

        private static TableTransferOutcome failed(String tableName, String errorMessage) {
            return new TableTransferOutcome(
                    tableName,
                    0,
                    0,
                    false,
                List.of(),
                    false,
                    false,
                    errorMessage == null ? "unknown error" : errorMessage
            );
        }

        private static TableTransferOutcome skippedTableNotExists(String tableName, String errorMessage) {
            return new TableTransferOutcome(
                    tableName,
                    0,
                    0,
                    false,
                    List.of(),
                    true,
                    true,
                    errorMessage == null ? "ORA-00942: table or view does not exist" : errorMessage
            );
        }
    }

    private static SQLException extractSqlException(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        if (throwable instanceof SQLException sqlException) {
            return sqlException;
        }

        Throwable current = throwable.getCause();
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
            depth++;
        }

        return null;
    }

    private DataTransferService.TransferResult transferTableWithRetry(
            ConnectionManager manager,
            String sourcePoolId,
            String targetPoolId,
            DataTransferService transferService,
            TableDefinition table,
            boolean effectiveCopyNewOnly,
            MigrationCheckpointStore checkpointStore,
            int startOffset,
            DataTransferService.InsertSqlExportOptions insertSqlExportOptions
    ) throws SQLException {
        int attempts = retryPolicy.resolveAttempts();
        int currentOffset = Math.max(0, startOffset);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            ensureNotCancelled();

            try (Connection transferSourceConn = manager.getConnection(sourcePoolId);
                 Connection transferTargetConn = manager.getConnection(targetPoolId)) {

                configurePostgresSchema(transferSourceConn, sourceConfig, sourceSchema, false);
                configurePostgresSchema(transferTargetConn, targetConfig, targetSchema, true);
                configureOracleSchema(transferSourceConn, sourceConfig, sourceSchema);
                configureOracleSchema(transferTargetConn, targetConfig, targetSchema);

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
                        insertSqlExportOptions,
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

    private DataTransferService.InsertSqlExportOptions resolveInsertSqlExportOptions(boolean structureOnly) throws SQLException {
        if (!exportInsertSqlFiles || structureOnly) {
            return null;
        }

        if (targetConfig == null || targetConfig.getType() != DatabaseType.ORACLE) {
            publish("[WARN] Export INSERT SQL chi ho tro target Oracle. Option nay se bi bo qua.");
            return null;
        }

        boolean exportOnlyToFile = onlyExportInsertSqlFiles;
        if (exportOnlyToFile) {
            publish("Only-export mode da bat: se chi xuat file INSERT SQL, khong insert vao target.");
        }

        String baseDir = exportInsertSqlDir == null || exportInsertSqlDir.isBlank()
                ? "migration-sql-export"
                : exportInsertSqlDir;
        Path basePath = Paths.get(baseDir);
        if (!basePath.isAbsolute()) {
            basePath = Paths.get(System.getProperty("user.dir")).resolve(basePath).normalize();
        }

        String runFolder = "run_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        Path runOutputPath = basePath.resolve(runFolder);

        try {
            Files.createDirectories(runOutputPath);
        } catch (IOException e) {
            throw new SQLException("Khong the tao thu muc export INSERT SQL: " + runOutputPath, e);
        }

        publish("Export INSERT SQL da bat. Output folder: " + runOutputPath.toAbsolutePath());
        return new DataTransferService.InsertSqlExportOptions(runOutputPath, 100_000, 1_000, exportOnlyToFile);
    }

    private void writeOracleAllScript(
            Path outputDirectory,
            List<TableMigrationPlan> orderedPlans,
            Map<String, List<String>> exportedSqlFilesByTable
    ) throws SQLException {
        if (outputDirectory == null) {
            return;
        }

        Path allScriptPath = outputDirectory.resolve("all.sql");
        try (BufferedWriter writer = Files.newBufferedWriter(
                allScriptPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            writer.write("SET DEFINE OFF;");
            writer.newLine();
            writer.newLine();

            for (TableMigrationPlan plan : orderedPlans) {
                List<String> tableFiles = exportedSqlFilesByTable.get(plan.table().getTableName());
                if (tableFiles == null || tableFiles.isEmpty()) {
                    continue;
                }

                writer.write("PROMPT Running scripts for table " + plan.table().getTableName());
                writer.newLine();
                for (String fileName : tableFiles) {
                    writer.write("@" + fileName);
                    writer.newLine();
                }
                writer.newLine();
            }
        } catch (IOException e) {
            throw new SQLException("Khong the ghi file all.sql tai " + allScriptPath, e);
        }

        publish("Da tao file all.sql: " + allScriptPath.toAbsolutePath());
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

    private boolean isOracleToOracleDirectDdlMode(SqlDialect targetDialect) {
        return sourceConfig != null
                && targetConfig != null
                && sourceConfig.getType() == DatabaseType.ORACLE
                && targetConfig.getType() == DatabaseType.ORACLE
                && targetDialect instanceof OracleDialect;
    }

    private boolean isPostgresToPostgresDirectDdlMode(SqlDialect targetDialect) {
        return sourceConfig != null
                && targetConfig != null
                && sourceConfig.getType() == DatabaseType.POSTGRESQL
                && targetConfig.getType() == DatabaseType.POSTGRESQL
                && targetDialect instanceof PostgresDialect;
    }

    private static String prepareOracleRawRoutineDdl(String ddl, String sourceSchema, String targetSchema) {
        String remapped = OracleDialect.remapSchemaPrefixSafely(ddl, sourceSchema, targetSchema);
        return normalizeOracleRawRoutineDdlForJdbc(remapped);
    }

    private static String normalizeOracleRawRoutineDdlForJdbc(String ddl) {
        String normalized = ddl == null ? "" : ddl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
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

        if (JdbcUrlParamResolver.hasExplicitPostgresSchemaParam(config.getJdbcUrlValue())) {
            return;
        }

        String effectiveSchema = schema == null ? "" : schema.trim();
        if (effectiveSchema.isBlank()) {
            return;
        }

        String quotedSchema = quotePostgresIdentifier(effectiveSchema);
        try (Statement statement = conn.createStatement()) {
            if (createIfMissing) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + quotedSchema);
            }
            statement.execute("SET search_path TO " + quotedSchema + ", public");
        }
    }

    private static String quotePostgresIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void configureOracleSchema(
            Connection conn,
            DatabaseConfig config,
            String schema
    ) throws SQLException {
        if (conn == null || config == null || config.getType() != DatabaseType.ORACLE) {
            return;
        }

        String effectiveSchema = schema == null ? "" : schema.trim();
        if (effectiveSchema.isBlank()) {
            return;
        }

        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + quoteOracleIdentifier(effectiveSchema));
        }
    }

    private static String quoteOracleIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String normalizeRoutineSqlForJdbc(String sql, SqlDialect targetDialect) {
        String normalized = sql == null ? "" : sql.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }

        if (targetDialect instanceof OracleDialect) {
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            return normalized;
        }

        return normalizeSqlForJdbc(normalized);
    }

    private static boolean isTableAlreadyExistsError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)
                || message.contains("already exists")
                || message.contains("ora-00955");
    }

    private static boolean isOracleRawTableDdlIncompatibleError(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ora-00922")
                || message.contains("ora-00933")
                || message.contains("ora-00907")
                || message.contains("ora-00904")
                || message.contains("ora-65021");
    }

    private static String abbreviateSqlForLog(String sql) {
        if (sql == null) {
            return "";
        }
        String compact = sql.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 400) {
            return compact;
        }
        return compact.substring(0, 400) + "...";
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

    private static boolean isPostgresIdentityAlterNotApplicableError(SQLException e) {
        String sqlState = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42809".equals(sqlState)
                || message.contains("is not an identity column")
                || message.contains("cannot alter") && message.contains("identity")
                || message.contains("column") && message.contains("does not exist");
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
     * Trích xuất toàn bềEsequences từ schema nguồn.
     * BềEqua system sequences (ISEQ$$, BIN$, DR$).
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
     * Trích xuất toàn bềEindexes từ các bảng đã chọn.
     * BềEqua system indexes (pg_*, sql_*, PK indexes, implicit constraint indexes).
     */
    private List<IndexDefinition> extractAllIndexes(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType,
            List<String> tableNames
    ) throws SQLException {
        List<IndexDefinition> result = new ArrayList<>();
        boolean sameEngineMigration = targetConfig != null && dbType == targetConfig.getType();
        for (String tableName : tableNames) {
            ensureNotCancelled();
            List<String> idxNames = extractor.getIndexNames(conn, schema, tableName);
            for (String idxName : idxNames) {
                IndexDefinition idx = extractor.extractIndexDefinition(conn, schema, idxName, dbType);
                if (idx != null && !idx.isSystemIndex() && (idx.isMigratable() || sameEngineMigration)) {
                    idx.setSourceSchema(schema);
                    idx.setTargetSchema(targetSchema);
                    result.add(idx);
                    publish("  -> Index: " + idxName + " tren bang " + tableName
                            + " (unique=" + idx.isUnique()
                            + ", type=" + idx.getIndexType() + ")");
                } else if (idx != null && !idx.isMigratable() && !sameEngineMigration) {
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
     * Lý do: Trigger có thềEgọi sequence.NEXTVAL, nên sequence cần tồn tại trước.
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
                boolean rawSequenceDdlMode = isSameTypeRawDdlMode(targetDialect, seq.getSourceDialect())
                        && hasText(seq.getDdlText());
                try {
                    String createSql = targetDialect.buildCreateSequenceSql(seq);
                    stmt.execute(normalizeSqlForJdbc(createSql));
                    publish("  -> Da tao sequence: " + seq.getSequenceName());
                } catch (SQLException e) {
                    if (isSequenceAlreadyExistsError(e)) {
                        publish("  -> Bo qua sequence da ton tai: " + seq.getSequenceName());
                    } else if (rawSequenceDdlMode) {
                        publish("  -> [WARN] Raw sequence DDL khong tuong thich cho " + seq.getSequenceName()
                                + ". Se fallback sang DDL tong hop. Ly do: " + e.getMessage());

                        SequenceDefinition fallbackSeq = buildGeneratedSequenceDefinition(seq);
                        try {
                            String fallbackSql = targetDialect.buildCreateSequenceSql(fallbackSeq);
                            stmt.execute(normalizeSqlForJdbc(fallbackSql));
                            publish("  -> Da tao sequence " + seq.getSequenceName() + " (fallback DDL tong hop)");
                        } catch (SQLException fallbackEx) {
                            if (isSequenceAlreadyExistsError(fallbackEx)) {
                                publish("  -> Bo qua sequence da ton tai: " + seq.getSequenceName());
                            } else {
                                publish("  -> LOI tao sequence " + seq.getSequenceName()
                                        + " (fallback): " + fallbackEx.getMessage());
                            }
                        }
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
     * Chạy SAU sequence, TRƯỚC trigger  Evì trigger có thềEgọi function.
     */
    private void runCreateFunctionsPhase(
            Connection targetConn,
            List<FunctionDefinition> functions,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        if (targetDialect == null) {
            throw new IllegalArgumentException("Target dialect khong hop le");
        }

        if (functions.isEmpty()) {
            publish("  -> Khong co function/procedure nao de tao.");
            return;
        }

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
            publish("  -> Oracle -> PostgreSQL: bat dau transform PL/SQL...");
        }

        boolean oracleToOracleDirectDdlMode = isOracleToOracleDirectDdlMode(targetDialect);

        try (Statement stmt = targetConn.createStatement()) {
            for (FunctionDefinition fn : functions) {
                ensureNotCancelled();
                boolean rawFunctionDdlMode = isSameTypeRawDdlMode(targetDialect, fn.getSourceDialect())
                        && hasText(fn.getDdlText());

                if (oracleToOracleDirectDdlMode
                        && fn.getDdlText() != null
                        && !fn.getDdlText().isBlank()) {
                    try {
                        String directDdl = prepareOracleRawRoutineDdl(fn.getDdlText(), fn.getSourceSchema(), fn.getTargetSchema());
                        stmt.execute(normalizeRoutineSqlForJdbc(directDdl, targetDialect));
                        publish("  -> Da tao " + fn.getFunctionType() + " (DDL goc): " + fn.getFunctionName());
                        continue;
                    } catch (SQLException e) {
                        if (isFunctionAlreadyExistsError(e)) {
                            publish("  -> Bo qua " + fn.getFunctionType() + " da ton tai: " + fn.getFunctionName());
                            continue;
                        } else {
                            publish("  -> [WARN] Raw DDL khong tuong thich cho "
                                    + fn.getFunctionType() + " " + fn.getFunctionName()
                                    + ". Se fallback sang SQL tong hop. Ly do: " + e.getMessage());
                        }
                    }
                }

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
                        stmt.execute(normalizeRoutineSqlForJdbc(sql, targetDialect));
                        publish("  -> Da tao " + fn.getFunctionType() + ": " + fn.getFunctionName());
                    } catch (SQLException e) {
                        if (isFunctionAlreadyExistsError(e)) {
                            publish("  -> Bo qua " + fn.getFunctionType() + " da ton tai: " + fn.getFunctionName());
                            break;
                        } else if (rawFunctionDdlMode) {
                            publish("  -> [WARN] Raw DDL that bai cho " + fn.getFunctionType() + " " + fn.getFunctionName()
                                    + ". Se fallback sang SQL tong hop. Ly do: " + e.getMessage());

                            FunctionDefinition fallbackFn = buildGeneratedFunctionDefinition(fn);
                            List<String> fallbackStmts = targetDialect.buildCreateFunctionSql(fallbackFn, transformer);
                            for (String fallbackSql : fallbackStmts) {
                                if (fallbackSql == null || fallbackSql.isBlank()) {
                                    continue;
                                }
                                try {
                                    stmt.execute(normalizeRoutineSqlForJdbc(fallbackSql, targetDialect));
                                    publish("  -> Da tao " + fn.getFunctionType() + ": " + fn.getFunctionName()
                                            + " (fallback SQL tong hop)");
                                } catch (SQLException fallbackEx) {
                                    if (isFunctionAlreadyExistsError(fallbackEx)) {
                                        publish("  -> Bo qua " + fn.getFunctionType() + " da ton tai: " + fn.getFunctionName());
                                        continue;
                                    }
                                    publish("  -> LOI tao " + fn.getFunctionType() + " " + fn.getFunctionName()
                                            + " (fallback): " + fallbackEx.getMessage());
                                }
                            }
                            break;
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
     * Chạy SAU function (vì trigger body có thềEgọi function).
     */
    private void runCreateTriggersPhase(
            Connection targetConn,
            List<TriggerDefinition> triggers,
            DatabaseType sourceDbType,
            SqlDialect targetDialect
    ) throws SQLException {
        if (targetDialect == null) {
            throw new IllegalArgumentException("Target dialect khong hop le");
        }

        if (triggers.isEmpty()) {
            publish("  -> Khong co trigger nao de tao.");
            return;
        }

        OracleToPgsqlTransformer transformer = null;
        if (sourceDbType == DatabaseType.ORACLE && targetDialect instanceof PostgresDialect) {
            transformer = new OracleToPgsqlTransformer();
            publish("  -> Oracle -> PostgreSQL: bat dau transform trigger body...");
        }

        boolean oracleToOracleDirectDdlMode = isOracleToOracleDirectDdlMode(targetDialect);

        try (Statement stmt = targetConn.createStatement()) {
            for (TriggerDefinition trig : triggers) {
                ensureNotCancelled();
            boolean rawTriggerDdlMode = isSameTypeRawDdlMode(targetDialect, trig.getSourceDialect())
                && (hasText(trig.getDdlText())
                || (trig.getSourceDialect() == DatabaseType.POSTGRESQL && hasText(trig.getFunctionDdl())));

                if (oracleToOracleDirectDdlMode
                        && trig.getDdlText() != null
                        && !trig.getDdlText().isBlank()) {
                    try {
                        String directDdl = prepareOracleRawRoutineDdl(trig.getDdlText(), trig.getSourceSchema(), trig.getTargetSchema());
                        stmt.execute(normalizeRoutineSqlForJdbc(directDdl, targetDialect));
                        publish("  -> Da tao trigger (DDL goc): " + trig.getTriggerName());
                        continue;
                    } catch (SQLException e) {
                        if (isTriggerAlreadyExistsError(e)) {
                            publish("  -> Bo qua trigger da ton tai: " + trig.getTriggerName());
                            continue;
                        } else {
                            publish("  -> [WARN] Raw trigger DDL khong tuong thich cho " + trig.getTriggerName()
                                    + ". Se fallback sang SQL tong hop. Ly do: " + e.getMessage());
                        }
                    }
                }

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
                            break;
                        } else if (rawTriggerDdlMode) {
                            publish("  -> [WARN] Raw trigger DDL that bai cho " + trig.getTriggerName()
                                    + ". Se fallback sang SQL tong hop. Ly do: " + e.getMessage());

                            TriggerDefinition fallbackTrig = buildGeneratedTriggerDefinition(trig);
                            List<String> fallbackStmts = targetDialect.buildCreateTriggerSql(fallbackTrig, transformer);
                            for (String fallbackSql : fallbackStmts) {
                                if (fallbackSql == null || fallbackSql.isBlank()) {
                                    continue;
                                }
                                try {
                                    stmt.execute(normalizeSqlForJdbc(fallbackSql));
                                    publish("  -> Da tao trigger: " + trig.getTriggerName()
                                            + " (fallback SQL tong hop)");
                                } catch (SQLException fallbackEx) {
                                    if (isTriggerAlreadyExistsError(fallbackEx)) {
                                        publish("  -> Bo qua trigger da ton tai: " + trig.getTriggerName());
                                        continue;
                                    }
                                    publish("  -> LOI tao trigger " + trig.getTriggerName()
                                            + " (fallback): " + fallbackEx.getMessage());
                                }
                            }
                            break;
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
     * Lý do: Index cần dữ liệu đềEbuild; tạo sau FK đềEtránh conflict.
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

        List<String> failedIndexes = new ArrayList<>();

        try (Statement stmt = targetConn.createStatement()) {
            int total = indexes.size();
            for (int i = 0; i < total; i++) {
                ensureNotCancelled();
                IndexDefinition idx = indexes.get(i);
                boolean failed = false;
                String failureMessage = null;
                boolean rawIndexDdlMode = isSameTypeRawDdlMode(targetDialect, idx.getSourceDialect())
                        && hasText(idx.getDdlText());
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
                        } else if (rawIndexDdlMode) {
                            publish("  -> [WARN] Raw index DDL khong tuong thich cho " + idx.getIndexName()
                                    + ". Se fallback sang SQL tong hop. Ly do: " + e.getMessage());

                            IndexDefinition fallbackIdx = buildGeneratedIndexDefinition(idx);
                            List<String> fallbackSqls = targetDialect.buildCreateIndexSql(fallbackIdx);
                            boolean fallbackFailed = false;
                            String fallbackFailureMessage = null;
                            for (String fallbackSql : fallbackSqls) {
                                try {
                                    stmt.execute(normalizeSqlForJdbc(fallbackSql));
                                    publish("  -> Da tao index: " + idx.getIndexName()
                                            + " (fallback SQL tong hop)");
                                } catch (SQLException fallbackEx) {
                                    if (isIndexAlreadyExistsError(fallbackEx)) {
                                        publish("  -> Bo qua index da ton tai: " + idx.getIndexName());
                                        continue;
                                    }
                                    if (isUniqueIndexDataConflict(fallbackEx)) {
                                        fallbackFailureMessage = "Du lieu trung lap, khong tao duoc UNIQUE INDEX";
                                    } else {
                                        fallbackFailureMessage = fallbackEx.getMessage();
                                    }
                                    publish("  -> LOI tao index " + idx.getIndexName() + " (fallback): " + fallbackFailureMessage);
                                    fallbackFailed = true;
                                    break;
                                }
                            }

                            if (fallbackFailed) {
                                failed = true;
                                failureMessage = fallbackFailureMessage;
                            }
                            break;
                        } else {
                            if (isUniqueIndexDataConflict(e)) {
                                failureMessage = "Du lieu trung lap, khong tao duoc UNIQUE INDEX";
                            } else {
                                failureMessage = e.getMessage();
                            }
                            publish("  -> LOI tao index " + idx.getIndexName() + ": " + failureMessage);
                            failed = true;
                            break;
                        }
                    }
                }

                if (failed) {
                    failedIndexes.add(idx.getIndexName() + " (" + idx.getTableName() + ") - " + failureMessage);
                }
            }

            if (!failedIndexes.isEmpty()) {
                publish("  -> [WARN] Co " + failedIndexes.size() + " index tao that bai (da tiep tuc migration):");
                for (String failedIndex : failedIndexes) {
                    publish("     - " + failedIndex);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIEW EXTRACTION & CREATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trích xuất toàn bềEviews từ schema nguồn với topological sort.
     */
    private List<ViewDefinition> extractAllViews(
            MetadataExtractor extractor,
            Connection conn,
            String schema,
            DatabaseType dbType
    ) throws SQLException {
        try {
            List<ViewDefinition> views = extractor.extractViewDefinitionsWithDependencySort(conn, schema, dbType);
            for (ViewDefinition view : views) {
                view.setSourceSchema(schema);
                view.setTargetSchema(targetSchema);
            }
            publish("  -> Tim thay " + views.size() + " views (da sort theo dependency).");
            return views;
        } catch (IllegalStateException e) {
            publish("  -> [WARN] Circular view dependency: " + e.getMessage() + ". Bo qua views.");
            return new ArrayList<>();
        }
    }

    /**
     * Tạo views trên target database.
     * Chạy CUỐI CÙNG  Eviews phụ thuộc vào tables đã tạo.
     * Áp dụng include/exclude filters và Oracle ↁEPostgreSQL transformation nếu cần.
     */
    private void runCreateViewsPhase(
            Connection targetConn,
            List<ViewDefinition> views,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            SqlDialect targetDialect,
            boolean replaceExistingViews
    ) throws SQLException {
        if (targetDialect == null) {
            throw new IllegalArgumentException("Target dialect khong hop le");
        }

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
                vd.setSourceSchema(sourceSchema);
                vd.setTargetSchema(targetSchema);
                boolean rawViewDdlMode = isSameTypeRawDdlMode(targetDialect, vd.getSourceDialect())
                        && hasText(vd.getDdlText());
                publish("  -> Tao view: " + vd.getViewName() + " (" + (i + 1) + "/" + total + ")");

                try {
                    if (rawViewDdlMode) {
                        String directDdl = targetDialect.buildCreateViewSql(vd);
                        if (replaceExistingViews) {
                            String qualifiedViewName = targetSchema != null && !targetSchema.isBlank()
                                    ? targetDialect.quoteIdentifier(targetSchema)
                                            + "." + targetDialect.quoteIdentifier(vd.getViewName())
                                    : targetDialect.quoteIdentifier(vd.getViewName());

                            String dropSql = (targetDialect instanceof OracleDialect)
                                    ? "DROP VIEW " + qualifiedViewName
                                    : "DROP VIEW IF EXISTS " + qualifiedViewName;
                            try {
                                stmt.execute(dropSql);
                            } catch (SQLException dropEx) {
                                if (!isViewNotExistsError(dropEx)) {
                                    throw dropEx;
                                }
                            }
                        }

                        try {
                            stmt.execute(normalizeRoutineSqlForJdbc(directDdl, targetDialect));
                            publish("  -> Da tao view (DDL goc): " + vd.getViewName());
                            continue;
                        } catch (SQLException rawEx) {
                            if (isViewAlreadyExistsError(rawEx)) {
                                publish("  -> Bo qua view da ton tai: " + vd.getViewName());
                                continue;
                            }
                            publish("  -> [WARN] Raw view DDL khong tuong thich cho " + vd.getViewName()
                                    + ". Se fallback sang SQL tong hop. Ly do: " + rawEx.getMessage());
                        }
                    }

                    // 1. Transform view body
                    String transformedBody = transformViewBodySwing(
                            vd.getSelectClause(),
                            sourceSchema,
                            targetSchema,
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
                            .ddlText(rawViewDdlMode ? null : vd.getDdlText())
                            .materialized(vd.isMaterialized())
                            .build();

                    // 3. Build SQL
                    String createSql = targetDialect.buildCreateViewSql(transformedVd);
                    if (createSql == null || createSql.isBlank()) {
                        publish("  -> LOI: Khong the build CREATE VIEW SQL cho " + vd.getViewName());
                        continue;
                    }

                    // 4. Execute — honor replaceExistingViews selection from UI.
                    if (replaceExistingViews) {
                        // Schema-qualify to avoid dropping a view in another schema.
                        String qualifiedViewName = targetSchema != null && !targetSchema.isBlank()
                                ? targetDialect.quoteIdentifier(targetSchema)
                                        + "." + targetDialect.quoteIdentifier(vd.getViewName())
                                : targetDialect.quoteIdentifier(vd.getViewName());

                        String dropSql;
                        if (targetDialect instanceof OracleDialect) {
                            dropSql = "DROP VIEW " + qualifiedViewName;
                        } else {
                            dropSql = "DROP VIEW IF EXISTS " + qualifiedViewName;
                        }

                        try {
                            stmt.execute(dropSql);
                        } catch (SQLException dropEx) {
                            if (!isViewNotExistsError(dropEx)) {
                                throw dropEx;
                            }
                        }

                        stmt.execute(normalizeSqlForJdbc(createSql));
                        publish("  -> Da tao view: " + vd.getViewName());
                    } else {
                        try {
                            stmt.execute(normalizeSqlForJdbc(createSql));
                            publish("  -> Da tao view: " + vd.getViewName());
                        } catch (SQLException e) {
                            if (isViewAlreadyExistsError(e)) {
                                publish("  -> Bo qua view da ton tai: " + vd.getViewName());
                            } else {
                                throw e;
                            }
                        }
                    }

                } catch (SQLException e) {
                    publish("  -> LOI tao view " + vd.getViewName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Transform view body -- schema replacement + Oracle to PostgreSQL transformation.
     */
    private String transformViewBodySwing(
            String clause,
            String fromSchema,
            String toSchema,
            OracleToPgsqlTransformer transformer
    ) {
        if (clause == null) {
            return clause;
        }

        String result = clause;

        // Remap schema prefix safely and avoid touching literals/comments.
        if (fromSchema != null && toSchema != null
                && !fromSchema.equalsIgnoreCase(toSchema)) {
            result = OracleDialect.remapSchemaPrefixSafely(result, fromSchema, toSchema);
        }

        // Oracle to PostgreSQL transformation (if required by caller).
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
        return "42710".equals(sqlState)
            || "42P07".equals(sqlState)
            || message.contains("ora-00955")
            || (message.contains("already exists") && message.contains("trigger"));
    }

    private static boolean isIndexAlreadyExistsError(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P07".equals(sqlState)   // PostgreSQL
            || "42710".equals(sqlState)
            || message.contains("ora-00955")
            || (message.contains("already exists")
            && (message.contains("index") || message.contains("relation")))
                || message.contains("ora-01408");
    }

    private static boolean isUniqueIndexDataConflict(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "23505".equals(sqlState)
                || message.contains("ora-00001")
                || message.contains("duplicate key")
                || message.contains("could not create unique index");
    }

    private static boolean isViewAlreadyExistsError(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P06".equals(sqlState)
                || (message.contains("already exists") && message.contains("view"))
                || message.contains("ora-00955");
    }

    private static boolean isViewNotExistsError(SQLException e) {
        if (e == null) {
            return false;
        }
        String sqlState = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42P01".equals(sqlState)
                || (message.contains("does not exist") && message.contains("view"))
                || message.contains("ora-00942")
                || message.contains("ora-04043");
    }

    private static String buildCheckpointNamespace(
            DatabaseConfig source,
            DatabaseConfig target,
            String sourceSchema,
            String targetSchema
    ) {
        String sourceScope = String.join("|",
                source == null || source.getType() == null ? "UNKNOWN" : source.getType().name(),
                source == null ? "" : normalizeScopeValue(source.getHost()),
                source == null ? "0" : String.valueOf(source.getPort()),
                source == null ? "" : normalizeScopeValue(source.getDatabaseName()),
                source == null ? "" : normalizeScopeValue(source.getUsername()),
                normalizeScopeValue(sourceSchema)
        );

        String targetScope = String.join("|",
                target == null || target.getType() == null ? "UNKNOWN" : target.getType().name(),
                target == null ? "" : normalizeScopeValue(target.getHost()),
                target == null ? "0" : String.valueOf(target.getPort()),
                target == null ? "" : normalizeScopeValue(target.getDatabaseName()),
                target == null ? "" : normalizeScopeValue(target.getUsername()),
                normalizeScopeValue(targetSchema)
        );

        return sourceScope + "=>" + targetScope;
    }

    private static String normalizeScopeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isSameTypeRawDdlMode(SqlDialect targetDialect, DatabaseType sourceDialect) {
        if (sourceConfig == null || targetConfig == null || sourceDialect == null || targetDialect == null) {
            return false;
        }

        if (sourceConfig.getType() != targetConfig.getType()) {
            return false;
        }

        if (sourceDialect != sourceConfig.getType()) {
            return false;
        }

        if (sourceDialect == DatabaseType.ORACLE) {
            return targetDialect instanceof OracleDialect;
        }

        if (sourceDialect == DatabaseType.POSTGRESQL) {
            return targetDialect instanceof PostgresDialect;
        }

        return false;
    }

    private static TableDefinition buildGeneratedForeignKeyTableDefinition(TableDefinition table) {
        TableDefinition generated = new TableDefinition(table.getTableName());
        generated.setSourceDialect(table.getSourceDialect());
        generated.setSourceSchema(table.getSourceSchema());
        generated.setTargetSchema(table.getTargetSchema());

        for (ColumnDefinition column : table.getColumns()) {
            generated.addColumn(column);
        }
        for (String pk : table.getPrimaryKeys()) {
            generated.addPrimaryKey(pk);
        }
        for (ForeignKeyDefinition fk : table.getForeignKeys()) {
            generated.addForeignKey(fk);
        }

        return generated;
    }

    private static SequenceDefinition buildGeneratedSequenceDefinition(SequenceDefinition sequence) {
        return SequenceDefinition.builder()
                .sequenceName(sequence.getSequenceName())
                .schema(sequence.getSchema())
                .sourceDialect(sequence.getSourceDialect())
                .startValue(sequence.getStartValue())
                .incrementBy(sequence.getIncrementBy())
                .minValue(sequence.getMinValue())
                .maxValue(sequence.getMaxValue())
                .cacheSize(sequence.getCacheSize())
                .cycle(sequence.isCycle())
                .lastNumber(sequence.getLastNumber())
                .sourceSchema(sequence.getSourceSchema())
                .targetSchema(sequence.getTargetSchema())
                .build();
    }

    private static IndexDefinition buildGeneratedIndexDefinition(IndexDefinition index) {
        return IndexDefinition.builder()
                .indexName(index.getIndexName())
                .schema(index.getSchema())
                .tableName(index.getTableName())
                .columns(new ArrayList<>(index.getColumns()))
                .descendings(new ArrayList<>(index.getDescendings()))
                .unique(index.isUnique())
                .indexType(index.getIndexType())
                .tablespace(index.getTablespace())
                .expression(index.getExpression())
                .whereClause(index.getWhereClause())
                .indexTypeName(index.getIndexTypeName())
                .sourceDialect(index.getSourceDialect())
                .sourceSchema(index.getSourceSchema())
                .targetSchema(index.getTargetSchema())
                .systemIndex(index.isSystemIndex())
                .build();
    }

    private static FunctionDefinition buildGeneratedFunctionDefinition(FunctionDefinition function) {
        return FunctionDefinition.builder()
                .functionName(function.getFunctionName())
                .schema(function.getSchema())
                .sourceDialect(function.getSourceDialect())
                .functionType(function.getFunctionType())
                .language(function.getLanguage())
                .arguments(new ArrayList<>(function.getArguments()))
                .returnType(function.getReturnType())
                .functionBody(function.getFunctionBody())
                .transformedBody(function.getTransformedBody())
                .hasOutParams(function.isHasOutParams())
                .deterministic(function.isDeterministic())
                .sourceSchema(function.getSourceSchema())
                .targetSchema(function.getTargetSchema())
                .build();
    }

    private static TriggerDefinition buildGeneratedTriggerDefinition(TriggerDefinition trigger) {
        return TriggerDefinition.builder()
                .triggerName(trigger.getTriggerName())
                .schema(trigger.getSchema())
                .sourceDialect(trigger.getSourceDialect())
                .timing(trigger.getTiming())
                .triggeringEvent(trigger.getTriggeringEvent())
                .tableName(trigger.getTableName())
                .level(trigger.getLevel())
                .whenClause(trigger.getWhenClause())
                .triggerBody(trigger.getTriggerBody())
                .transformedBody(trigger.getTransformedBody())
                .enabled(trigger.isEnabled())
                .functionName(trigger.getFunctionName())
                .description(trigger.getDescription())
                .actionType(trigger.getActionType())
                .sourceSchema(trigger.getSourceSchema())
                .targetSchema(trigger.getTargetSchema())
                .build();
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
            ui.appendLog("\n=== THẤT BẠI: Tiến trình bềEgián đoạn ===");
        } catch (ExecutionException e) {
            ui.appendLog("\n=== THẤT BẠI: Quá trình bị gián đoạn ===");
        } finally {
            // MềEkhóa lại nút Start Migration khi xong việc
            ui.enableStartButton(true);
        }
    }
}

