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
                } else {
                    broadcastStatus(95, "RUNNING", "Bo qua tao foreign keys (DATA_ONLY).", true);
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
}