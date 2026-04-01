package org.example.migrationdbweb.migratetool;

/**
 * Entry point — delegates to MigrationApp (CLI) or MigrationAppUI (Swing).
 *
 * Usage:
 *   java ...Main                    → Swing GUI
 *   java ...Main --direction=O2P   → Oracle → PostgreSQL (CLI)
 *   java ...Main --direction=P2O   → PostgreSQL → Oracle (CLI)
 *
 * All other options (env vars, metadata test flags) are handled by MigrationApp.
 *
 * @see MigrationApp
 * @see MigrationAppUI
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            // No arguments → launch Swing GUI
            MigrationAppUI.main(args);
            return;
        }

        // Forward to MigrationApp which handles all CLI parsing:
        //   --direction=ORACLE_TO_POSTGRES/POSTGRES_TO_ORACLE/O2P/P2O
        //   --enable-metadata-test / --disable-metadata-test
        //   --metadata-test=true/false
        //   MIGRATION_DIRECTION env var
        MigrationApp.main(args);
    }
}
