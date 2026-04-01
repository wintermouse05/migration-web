package org.example.migrationdbweb.migratetool;


import java.util.Locale;

import static org.example.migrationdbweb.migratetool.DirectionalMigration.getEnv;
import static org.example.migrationdbweb.migratetool.DirectionalMigration.getEnvAsInt;

public class PostgresToOracleMigration extends DirectionalMigration {

    public PostgresToOracleMigration(Boolean metadataTestOverride) {
        super(metadataTestOverride);
    }

    @Override
    protected DatabaseConfig buildSourceConfig() {
        String password = getEnv("POSTGRES_PASSWORD", null);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "POSTGRES_PASSWORD environment variable is not set. " +
                    "Please set it before running migration. " +
                    "Example: export POSTGRES_PASSWORD=your_secure_password"
            );
        }
        return new DatabaseConfig(
                DatabaseType.POSTGRESQL,
                getEnv("POSTGRES_HOST", "localhost"),
                getEnvAsInt("POSTGRES_PORT", 5432),
                getEnv("POSTGRES_DB", "migration_db"),
                getEnv("POSTGRES_USER", "postgres"),
                password
        );
    }

    @Override
    protected DatabaseConfig buildTargetConfig() {
        return new DatabaseConfig(
                DatabaseType.ORACLE,
                getEnv("ORACLE_HOST", "localhost"),
                getEnvAsInt("ORACLE_PORT", 1521),
                getEnv("ORACLE_SERVICE", "ORCL"),
                getEnv("ORACLE_USER", "scott"),
                getEnv("ORACLE_USER_PASSWORD", "tiger")
        );
    }

    @Override
    protected String defaultSourceSchema() {
        return getEnv("SOURCE_SCHEMA", "public");
    }

    @Override
    protected String defaultTargetSchema() {
        return getEnv("TARGET_SCHEMA", getEnv("ORACLE_USER", "scott").toUpperCase(Locale.ROOT));
    }
}
