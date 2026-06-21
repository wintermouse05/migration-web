package org.example.migrationdbweb.dto;

import org.example.migrationdbweb.migratetool.DatabaseConfig;

import lombok.Data;

@Data
public class SavedCredentialRequest {
    private String name;
    private DatabaseConfig config;
}
