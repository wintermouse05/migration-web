package org.example.migrationdbweb.migratetool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class SavedCredentialJsonStore {

    public record SavedDbCredential(
            String name,
            String dbType,
            String jdbcUrl,
            String host,
            int port,
            String databaseName,
            String schemaName,
            String username,
            String password,
            int maximumPoolSize,
            long createdAtEpochMs
    ) {
        public DatabaseConfig config() {
            DatabaseType type = DatabaseType.valueOf(dbType);
            DatabaseConfig config = new DatabaseConfig(type, host, port, databaseName, username, password);
            config.setJdbcUrl(jdbcUrl);
            config.setSchemaName(schemaName);
            config.setMaximumPoolSize(maximumPoolSize <= 0 ? 10 : maximumPoolSize);
            return config;
        }

        public static SavedDbCredential from(String name, DatabaseConfig config, long createdAtEpochMs) {
            return new SavedDbCredential(
                    name,
                    config.getType().name(),
                    config.getJdbcUrlValue(),
                    config.getHost(),
                    config.getPort(),
                    config.getDatabaseName(),
                    config.getSchemaName(),
                    config.getUsername(),
                    config.getPassword(),
                    config.getMaximumPoolSize(),
                    createdAtEpochMs
            );
        }
    }

    private final Path storePath;
    private final ObjectMapper objectMapper;

    public SavedCredentialJsonStore() {
        this(Paths.get(System.getProperty("user.home"), ".migration-swing-credentials.json"));
    }

    public SavedCredentialJsonStore(Path storePath) {
        this.storePath = storePath.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized List<SavedDbCredential> loadAll() {
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }

        try {
            List<SavedDbCredential> credentials = objectMapper.readValue(
                    storePath.toFile(),
                    new TypeReference<List<SavedDbCredential>>() {}
            );
            if (credentials == null) {
                return new ArrayList<>();
            }

            credentials.sort(Comparator.comparingLong(SavedDbCredential::createdAtEpochMs).reversed());
            return new ArrayList<>(credentials);
        } catch (IOException ex) {
            throw new IllegalStateException("Khong the doc file credential JSON: " + storePath + " (" + ex.getMessage() + ")", ex);
        }
    }

    public synchronized void saveOrUpdate(String name, DatabaseConfig config) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ten credential khong duoc de trong.");
        }
        if (config == null || config.getType() == null) {
            throw new IllegalArgumentException("Thong tin credential khong hop le.");
        }

        String trimmedName = name.trim();
        List<SavedDbCredential> all = loadAll();

        long now = System.currentTimeMillis();
        SavedDbCredential newCredential = SavedDbCredential.from(trimmedName, config, now);

        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            SavedDbCredential existing = all.get(i);
            if (existing.name() != null && existing.name().equalsIgnoreCase(trimmedName)) {
                all.set(i, newCredential);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            all.add(newCredential);
        }

        all.sort(Comparator.comparingLong(SavedDbCredential::createdAtEpochMs).reversed());
        persist(all);
    }

    private void persist(List<SavedDbCredential> credentials) {
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(storePath.toFile(), credentials);
        } catch (IOException ex) {
            throw new IllegalStateException("Khong the ghi file credential JSON: " + storePath + " (" + ex.getMessage() + ")", ex);
        }
    }
}
