package org.example.migrationdbweb.migratetool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class MigrationCheckpointStoreTests {

    @Test
    void shouldUseDefaultStateFileWhenPathIsBlank() {
        MigrationCheckpointStore store = new MigrationCheckpointStore("   ");

        assertTrue(store.getStateFilePath().endsWith(".migration-resume.properties"));
    }

    @Test
    void clearShouldOnlyAffectCurrentNamespace() throws IOException {
        Path stateFile = Files.createTempFile("migration-checkpoint", ".properties");
        try {
            MigrationCheckpointStore namespaceA = new MigrationCheckpointStore(stateFile.toString(), "A");
            MigrationCheckpointStore namespaceB = new MigrationCheckpointStore(stateFile.toString(), "B");

            namespaceA.markTableCompleted("orders", 15);
            namespaceB.markTableCompleted("orders", 25);

            namespaceA.clear();

            assertFalse(namespaceA.isTableCompleted("orders"));
            assertTrue(namespaceB.isTableCompleted("orders"));
            assertTrue(namespaceB.getTableOffset("orders") == 25);
        } finally {
            Files.deleteIfExists(stateFile);
        }
    }
}
