package org.example.migrationdbweb.migratetool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MigrationCheckpointStore {
    private static final String DEFAULT_STATE_FILE = ".migration-resume.properties";
    private static final String OFFSET_SUFFIX = ".offset";
    private static final String DONE_SUFFIX = ".done";
    private static final ConcurrentMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path statePath;
    private final Properties properties;
    private final Object fileLock;
    private final String namespacePrefix;

    public MigrationCheckpointStore(String stateFile) {
        this(stateFile, null);
    }

    public MigrationCheckpointStore(String stateFile, String namespace) {
        this.statePath = Paths.get(normalizeStateFile(stateFile)).toAbsolutePath().normalize();
        this.properties = new Properties();
        this.fileLock = FILE_LOCKS.computeIfAbsent(this.statePath, ignored -> new Object());
        this.namespacePrefix = normalizeNamespace(namespace);
        synchronized (fileLock) {
            loadFromDisk();
        }
    }

    public boolean isTableCompleted(String tableName) {
        synchronized (fileLock) {
            loadFromDisk();
            return Boolean.parseBoolean(properties.getProperty(doneKey(tableName), "false"));
        }
    }

    public int getTableOffset(String tableName) {
        synchronized (fileLock) {
            loadFromDisk();
            String value = properties.getProperty(offsetKey(tableName), "0");
            try {
                return Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
    }

    public void updateTableOffset(String tableName, int offset) {
        synchronized (fileLock) {
            loadFromDisk();
            int safeOffset = Math.max(0, offset);
            properties.setProperty(offsetKey(tableName), Integer.toString(safeOffset));
            properties.setProperty(doneKey(tableName), "false");
            saveToDisk();
        }
    }

    public void markTableCompleted(String tableName, int finalOffset) {
        synchronized (fileLock) {
            loadFromDisk();
            int safeOffset = Math.max(0, finalOffset);
            properties.setProperty(offsetKey(tableName), Integer.toString(safeOffset));
            properties.setProperty(doneKey(tableName), "true");
            saveToDisk();
        }
    }

    public void clearTableState(String tableName) {
        synchronized (fileLock) {
            loadFromDisk();
            properties.remove(offsetKey(tableName));
            properties.remove(doneKey(tableName));
            saveToDisk();
        }
    }

    public void clear() {
        synchronized (fileLock) {
            loadFromDisk();
            if (namespacePrefix == null || namespacePrefix.isBlank()) {
                properties.clear();
            } else {
                String keyPrefix = namespacePrefix + ".";
                properties.keySet().removeIf(k -> String.valueOf(k).startsWith(keyPrefix));
            }
            saveToDisk();
        }
    }

    public String getStateFilePath() {
        return statePath.toString();
    }

    private String offsetKey(String tableName) {
        return scopedKey(normalizeTableName(tableName) + OFFSET_SUFFIX);
    }

    private String doneKey(String tableName) {
        return scopedKey(normalizeTableName(tableName) + DONE_SUFFIX);
    }

    private String scopedKey(String key) {
        if (namespacePrefix == null || namespacePrefix.isBlank()) {
            return key;
        }
        return namespacePrefix + "." + key;
    }

    private static String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "";
        }
        return namespace.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9._-]", "_");
    }

    private static String normalizeStateFile(String stateFile) {
        if (stateFile == null || stateFile.isBlank()) {
            return DEFAULT_STATE_FILE;
        }
        return stateFile.trim();
    }

    private static String normalizeTableName(String tableName) {
        if (tableName == null) {
            return "UNKNOWN";
        }
        return tableName.trim().toUpperCase(Locale.ROOT);
    }

    private void loadFromDisk() {
        properties.clear();
        if (!Files.exists(statePath)) {
            return;
        }

        try (InputStream inputStream = Files.newInputStream(statePath)) {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Khong the doc file resume state: " + statePath, e);
        }
    }

    private void saveToDisk() {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream outputStream = Files.newOutputStream(statePath)) {
                properties.store(outputStream, "Migration resume state");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Khong the ghi file resume state: " + statePath, e);
        }
    }
}
