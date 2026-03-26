package org.example.migrationdbweb.dto;

import org.example.migrationdbweb.migratetool.DatabaseConfig;

import lombok.Data;

@Data
public class MigrationRequest {
    private DatabaseConfig source;
    private DatabaseConfig target;
    private boolean structureOnly;
    private boolean dataOnly;
    private MigrationOptions options = new MigrationOptions();

    @Data
    public static class MigrationOptions {
        private boolean truncate;
        private boolean copyNewOnly;
        private int limit;
        private String includeTablesCsv = "";
        private String excludeTablesCsv = "";
    }

}
