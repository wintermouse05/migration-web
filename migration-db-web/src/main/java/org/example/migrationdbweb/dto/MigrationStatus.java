package org.example.migrationdbweb.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrationStatus {
    private int progress;
    private String status;
    private String message;
    private boolean inProgress;
    private long timestamp;
}
