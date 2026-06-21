package org.example.migrationdbweb.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.example.migrationdbweb.dto.SavedCredentialResponse;
import org.example.migrationdbweb.migratetool.DatabaseConfig;
import org.example.migrationdbweb.migratetool.DatabaseType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SavedCredentialService {

    private static final String TABLE_NAME = "saved_database_credentials";

    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;

    private volatile boolean storageInitialized = false;

    public SavedCredentialService(
            @Value("${credential.store.jdbc-url:}") String jdbcUrl,
            @Value("${credential.store.username:}") String dbUsername,
            @Value("${credential.store.password:}") String dbPassword
    ) {
        this.jdbcUrl = jdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    public SavedCredentialResponse saveCredential(String displayName, DatabaseConfig config) {
        validateInput(displayName, config);
        ensureStorageReady();

        String insertSql = """
                INSERT INTO saved_database_credentials
                    (display_name, db_type, jdbc_url, host, port, database_name, schema_name, username, password)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql)) {

            statement.setString(1, displayName.trim());
            statement.setString(2, config.getType().name());
            statement.setString(3, config.getJdbcUrlValue());
            statement.setString(4, config.getHost());
            statement.setInt(5, config.getPort());
            statement.setString(6, config.getDatabaseName());
            statement.setString(7, config.getSchemaName());
            statement.setString(8, config.getUsername());
            statement.setString(9, config.getPassword());

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Unable to save credential to storage.");
                }
                long id = rs.getLong("id");
                Timestamp createdAt = rs.getTimestamp("created_at");
                return new SavedCredentialResponse(
                        id,
                        displayName.trim(),
                        copyConfig(config),
                        createdAt == null ? System.currentTimeMillis() : createdAt.getTime()
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Credential save failed: " + e.getMessage(), e);
        }
    }

    public List<SavedCredentialResponse> listCredentials() {
        ensureStorageReady();

        String querySql = """
            SELECT id, display_name, db_type, jdbc_url, host, port, database_name, schema_name, username, password, created_at
                FROM saved_database_credentials
                ORDER BY created_at DESC, id DESC
                """;

        List<SavedCredentialResponse> results = new ArrayList<>();

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(querySql)) {

            while (rs.next()) {
                DatabaseConfig config = new DatabaseConfig(
                        DatabaseType.valueOf(rs.getString("db_type")),
                        rs.getString("host"),
                        rs.getInt("port"),
                        rs.getString("database_name"),
                        rs.getString("username"),
                        rs.getString("password")
                );
                config.setJdbcUrl(rs.getString("jdbc_url"));
                config.setSchemaName(rs.getString("schema_name"));

                Timestamp createdAt = rs.getTimestamp("created_at");
                results.add(new SavedCredentialResponse(
                        rs.getLong("id"),
                        rs.getString("display_name"),
                        config,
                        createdAt == null ? System.currentTimeMillis() : createdAt.getTime()
                ));
            }

            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load credential list: " + e.getMessage(), e);
        }
    }

    private synchronized void ensureStorageReady() {
        if (storageInitialized) {
            return;
        }

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException(
                    "credential.store.jdbc-url is not configured for PostgreSQL credential storage."
            );
        }

        String ddl = """
                CREATE TABLE IF NOT EXISTS saved_database_credentials (
                    id BIGSERIAL PRIMARY KEY,
                    display_name VARCHAR(200) NOT NULL,
                    db_type VARCHAR(32) NOT NULL,
                    host VARCHAR(255) NOT NULL,
                    port INTEGER NOT NULL,
                    database_name VARCHAR(255) NOT NULL,
                    schema_name VARCHAR(255),
                    username VARCHAR(255) NOT NULL,
                    password TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;

        String alterAddSchemaColumnSql = """
                ALTER TABLE saved_database_credentials
                ADD COLUMN IF NOT EXISTS schema_name VARCHAR(255)
                """;

        String alterAddJdbcUrlColumnSql = """
            ALTER TABLE saved_database_credentials
            ADD COLUMN IF NOT EXISTS jdbc_url TEXT
            """;

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(ddl);
                statement.execute(alterAddSchemaColumnSql);
                statement.execute(alterAddJdbcUrlColumnSql);
            }
            storageInitialized = true;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("PostgreSQL JDBC driver not found.", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to create table " + TABLE_NAME + ": " + e.getMessage(), e);
        }
    }

    private Connection openConnection() throws SQLException {
        String effectiveJdbcUrl = normalizeCredentialJdbcUrl(jdbcUrl);
        normalizeJvmTimezoneForPostgres(effectiveJdbcUrl);
        if (dbUsername == null || dbUsername.isBlank()) {
            return DriverManager.getConnection(effectiveJdbcUrl);
        }
        return DriverManager.getConnection(effectiveJdbcUrl, dbUsername, dbPassword == null ? "" : dbPassword);
    }

    private static String normalizeCredentialJdbcUrl(String rawJdbcUrl) {
        if (rawJdbcUrl == null) {
            return null;
        }

        String jdbc = rawJdbcUrl.trim();
        if (jdbc.isEmpty()) {
            return jdbc;
        }

        String lower = jdbc.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:postgresql:")) {
            return jdbc;
        }

        // Some environments still provide legacy alias Asia/Saigon; PostgreSQL may reject it.
        jdbc = jdbc.replace("Asia/Saigon", "Asia/Ho_Chi_Minh");

        String lowerAfterReplace = jdbc.toLowerCase(Locale.ROOT);
        boolean hasTimezoneInQuery = lowerAfterReplace.contains("timezone=")
                || lowerAfterReplace.contains("timezone%3d")
                || lowerAfterReplace.contains("timezone%253d");

        if (hasTimezoneInQuery) {
            return jdbc;
        }

        String separator = jdbc.contains("?") ? "&" : "?";
        return jdbc + separator + "options=-c%20TimeZone=UTC";
    }

    private static void normalizeJvmTimezoneForPostgres(String effectiveJdbcUrl) {
        if (effectiveJdbcUrl == null) {
            return;
        }

        String lower = effectiveJdbcUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:postgresql:")) {
            return;
        }

        String timezoneId = TimeZone.getDefault().getID();
        if (timezoneId == null || timezoneId.isBlank()) {
            return;
        }

        if ("asia/saigon".equalsIgnoreCase(timezoneId.trim())) {
            TimeZone safeTz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");
            TimeZone.setDefault(safeTz);
            System.setProperty("user.timezone", safeTz.getID());
        }
    }

    private static void validateInput(String displayName, DatabaseConfig config) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Credential display name must not be empty.");
        }
        if (config == null) {
            throw new IllegalArgumentException("Missing database connection information.");
        }
        if (config.getType() == null) {
            throw new IllegalArgumentException("Invalid database type.");
        }

        boolean hasJdbcUrl = config.getJdbcUrlValue() != null && !config.getJdbcUrlValue().isBlank();
        if (!hasJdbcUrl) {
            if (config.getHost() == null || config.getHost().isBlank()) {
                throw new IllegalArgumentException("Host must not be empty.");
            }
            if (config.getPort() <= 0) {
                throw new IllegalArgumentException("Port must be greater than 0.");
            }
            if (config.getDatabaseName() == null || config.getDatabaseName().isBlank()) {
                throw new IllegalArgumentException("Database name/SID must not be empty.");
            }
        }

        if (config.getUsername() == null || config.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username must not be empty.");
        }
        if (config.getPassword() == null) {
            throw new IllegalArgumentException("Password must not be null.");
        }
    }

    private static DatabaseConfig copyConfig(DatabaseConfig config) {
        DatabaseConfig copied = new DatabaseConfig(
                config.getType(),
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getUsername(),
                config.getPassword()
        );
        copied.setJdbcUrl(config.getJdbcUrlValue());
        copied.setSchemaName(config.getSchemaName());
        copied.setMaximumPoolSize(config.getMaximumPoolSize());
        return copied;
    }
}
