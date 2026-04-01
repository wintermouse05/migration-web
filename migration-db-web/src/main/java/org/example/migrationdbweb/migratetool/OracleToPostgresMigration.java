package org.example.migrationdbweb.migratetool;




import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OracleToPostgresMigration extends DirectionalMigration {

    public OracleToPostgresMigration(Boolean metadataTestOverride) {
        super(metadataTestOverride);
    }

    @Override
    protected DatabaseConfig buildSourceConfig() {
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
    protected DatabaseConfig buildTargetConfig() {
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
    protected String defaultSourceSchema() {
        return getEnv("SOURCE_SCHEMA", getEnv("ORACLE_USER", "scott").toUpperCase(Locale.ROOT));
    }

    @Override
    protected String defaultTargetSchema() {
        return getEnv("TARGET_SCHEMA", "public");
    }
}


