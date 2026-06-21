package org.example.migrationdbweb.dto;

import org.example.migrationdbweb.migratetool.DatabaseConfig;

public record SavedCredentialResponse(
        long id,
        String displayName,
        DatabaseConfig databaseConfig,
        long createdAtEpochMs
) {
}
