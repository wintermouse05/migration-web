package org.example.migrationdbweb.migratetool;

import lombok.Builder;
import lombok.Data;

/**
 * TriggerDefinition — POJO chứa định nghĩa một TRIGGER.
 *
 * Trigger trong Oracle và PostgreSQL có cú pháp khác nhau:
 *
 * Oracle:
 *   CREATE OR REPLACE TRIGGER trg_emp_audit
 *   BEFORE INSERT OR UPDATE ON emp
 *   FOR EACH ROW
 *   WHEN (NEW.salary > 0)
 *   BEGIN
 *     :NEW.audit_date := SYSDATE;
 *   END;
 *
 * PostgreSQL:
 *   CREATE OR REPLACE FUNCTION trg_emp_audit() RETURNS trigger AS $$
 *   BEGIN
 *     NEW.audit_date := clock_timestamp();
 *     RETURN NEW;
 *   END;
 *   $$ LANGUAGE plpgsql;
 *
 *   CREATE TRIGGER trg_emp_audit
 *   BEFORE INSERT OR UPDATE ON emp
 *   FOR EACH ROW
 *   WHEN (NEW.salary > 0)
 *   EXECUTE FUNCTION trg_emp_audit();
 */
@Data
@Builder
public class TriggerDefinition {

    /** Tên trigger */
    private String triggerName;

    /** Schema chứa trigger */
    private String schema;

    /** Database type nguồn (ORACLE / POSTGRESQL) */
    private DatabaseType sourceDialect;

    // ─── Oracle trigger fields ─────────────────────────────────

    /**
     * Loại trigger:
     * - BEFORE / AFTER
     * - INSTEAD OF
     */
    private TriggerTiming timing;

    /**
     * Sự kiện kích hoạt:
     * - INSERT
     * - UPDATE
     * - DELETE
     * - INSERT OR UPDATE
     * - INSERT OR DELETE OR UPDATE
     */
    private String triggeringEvent;

    /**
     * Tên bảng mà trigger được gắn vào.
     * PostgreSQL: trigger gắn trực tiếp vào bảng.
     */
    private String tableName;

    /**
     * Trigger level:
     * - ROW (FOR EACH ROW)
     * - STATEMENT (default)
     */
    private TriggerLevel level;

    /**
     * WHEN clause — điều kiện lọc (không bắt buộc).
     * Ví dụ: "NEW.salary > 0"
     */
    private String whenClause;

    /**
     * Trigger body — phần PL/SQL thực thi.
     * Oracle: phần BETWEEN BEGIN và END
     * PostgreSQL: toàn bộ function body
     */
    private String triggerBody;

    /**
     * Trigger body đã được transform từ Oracle → PostgreSQL.
     * Set sau khi OracleToPgsqlTransformer.transform() được gọi.
     */
    private String transformedBody;

    /** Trạng thái: ENABLED / DISABLED */
    private boolean enabled;

    /** Tên function tương ứng (PostgreSQL tách trigger body thành function riêng) */
    private String functionName;

    // ─── Oracle-only metadata ─────────────────────────────────

    /** Description (Oracle) */
    private String description;

    /** Action type: CALL, PL/SQL (Oracle) */
    private String actionType;

    // ─── Schema replacement ───────────────────────────────────

    /** Schema nguồn */
    private String sourceSchema;

    /** Schema đích */
    private String targetSchema;

    /** Full DDL text gốc của trigger function (PostgreSQL pg_get_functiondef), nếu có. */
    private String functionDdl;

    /**
     * Full DDL text gốc của trigger.
     * Oracle: DBMS_METADATA.GET_DDL.
     * PostgreSQL: pg_get_triggerdef.
     */
    private String ddlText;

    // ─── Enum definitions ────────────────────────────────────

    public enum TriggerTiming {
        BEFORE,
        AFTER,
        INSTEAD_OF
    }

    public enum TriggerLevel {
        ROW,
        STATEMENT
    }

    // ─── Helper methods ──────────────────────────────────────

    /**
     * Kiểm tra trigger có phải là trigger trên VIEW hay không.
     * Oracle: INSTEAD OF trigger trên view.
     * PostgreSQL: chỉ BEFORE/AFTER trên table, INSTEAD OF trên view.
     */
    public boolean isInsteadOf() {
        return timing == TriggerTiming.INSTEAD_OF;
    }

    /**
     * Kiểm tra trigger có phải là ROW-level trigger hay không.
     */
    public boolean isRowLevel() {
        return level == TriggerLevel.ROW;
    }

    /**
     * Kiểm tra trigger có điều kiện WHEN hay không.
     */
    public boolean hasWhenClause() {
        return whenClause != null && !whenClause.isBlank();
    }

    /**
     * Kiểm tra trigger body có chứa :NEW hoặc :OLD (Oracle syntax) hay không.
     */
    public boolean hasOraclePseudoColumns() {
        if (triggerBody == null) return false;
        String upper = triggerBody.toUpperCase();
        return upper.contains(":NEW.") || upper.contains(":OLD.")
                || upper.contains(":NEW ") || upper.contains(":OLD ");
    }

    /**
     * Kiểm tra trigger body có chứa INSERTING/UPDATING/DELETING hay không.
     */
    public boolean hasOracleEventPredicates() {
        if (triggerBody == null) return false;
        String upper = triggerBody.toUpperCase();
        return upper.contains("INSERTING") || upper.contains("UPDATING")
                || upper.contains("DELETING");
    }

    /**
     * Tên function cho PostgreSQL.
     * Mặc định: trigger_name + '_func'.
     */
    public String resolveFunctionName() {
        if (functionName != null && !functionName.isBlank()) {
            return functionName;
        }
        return triggerName + "_func";
    }

    /**
     * Build WHEN clause đã transform cho PostgreSQL.
     * Oracle dùng :NEW.col, PostgreSQL dùng NEW.col (không có :)
     */
    public String getTransformedWhenClause() {
        if (!hasWhenClause()) return null;
        String clause = whenClause;
        clause = clause.replaceAll("(?i):NEW\\.", "NEW.");
        clause = clause.replaceAll("(?i):OLD\\.", "OLD.");
        return clause;
    }
}
