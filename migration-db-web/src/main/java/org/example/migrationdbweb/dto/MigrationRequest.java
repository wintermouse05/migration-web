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

        // ─── View migration options ───────────────────────────────
        /** Bật migrate view (table luôn luôn migrate trước view) */
        private boolean migrateViews = false;

        /** Chỉ migrate những view trong danh sách, bỏ trống = tất cả */
        private String includeViewsCsv = "";

        /** Loại trừ những view khỏi migration */
        private String excludeViewsCsv = "";

        /**
         * Khi view đã tồn tại ở target:
         * - true:  DROP VIEW + CREATE (thay thế hoàn toàn)
         * - false: skip, không làm gì
         */
        private boolean replaceExistingViews = false;
    }

}
