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
    private RetryOptions retry = new RetryOptions();
    private ResumeOptions resume = new ResumeOptions();

    /**
     * Retry options — kiểm soát số lần thử lại khi gặp lỗi tạm thời.
     */
    @Data
    public static class RetryOptions {
        /** Bật/tắt retry. Mặc định: false */
        private boolean enabled = false;

        /** Số lần thử tối đa. Mặc định: 3 */
        private int maxAttempts = 3;

        /** Delay ban đầu (ms) trước lần retry đầu tiên. Mặc định: 2000ms */
        private long initialDelayMs = 2000;

        /** Hệ số exponential backoff. Mặc định: 2.0 */
        private double backoffMultiplier = 2.0;
    }

    /**
     * Resume options — cho phép tiếp tục migration từ điểm đã dừng.
     */
    @Data
    public static class ResumeOptions {
        /** Bật/tắt resume. Mặc định: false */
        private boolean enabled = false;

        /**
         * File lưu checkpoint state.
         * Mặc định: ".migration-resume.properties" (trong thư mục làm việc).
         */
        private String stateFile = ".migration-resume.properties";

        /** Xóa checkpoint cũ trước khi bắt đầu migration mới. Mặc định: false */
        private boolean reset = false;
    }

    @Data
    public static class MigrationOptions {
        private boolean truncate;
        private boolean copyNewOnly;
        private boolean copyOnlyTargetEmptyTables;
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

        // ─── Advanced object migration options ─────────────────────
        /** Bật migrate sequences */
        private boolean migrateSequences = false;

        /** Bật migrate indexes */
        private boolean migrateIndexes = false;

        /** Bật migrate functions/procedures */
        private boolean migrateFunctions = false;

        /** Bật migrate triggers */
        private boolean migrateTriggers = false;
    }

}
