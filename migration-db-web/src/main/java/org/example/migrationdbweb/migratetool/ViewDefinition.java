package org.example.migrationdbweb.migratetool;

import lombok.Builder;
import lombok.Data;

/**
 * ViewDefinition — POJO chứa định nghĩa một view, song song với TableDefinition.
 *
 * Chỉ chứa data thuần, KHÔNG chứa logic SQL generation.
 *
 * So sánh với TableDefinition:
 * - TableDefinition.tableName       -> ViewDefinition.viewName
 * - TableDefinition.columns         -> ViewDefinition.selectClause
 * - TableDefinition.primaryKeys     -> ViewDefinition (view không có PK)
 * - TableDefinition.foreignKeys     -> ViewDefinition (view không có FK)
 */
@Data
@Builder
public class ViewDefinition {

    /** Tên view */
    private String viewName;

    /** Schema chứa view */
    private String schema;

    /** Database type nguồn (ORACLE / POSTGRESQL) */
    private DatabaseType sourceDialect;

    /**
     * Phần SELECT clause thực tế của view.
     *
     * Postgres:  kết quả từ pg_get_viewdef(oid, true) — chỉ là SELECT ... (không có CREATE VIEW)
     * Oracle:    kết quả từ USER_VIEWS.TEXT — đã có "CREATE [OR REPLACE] ... VIEW ..."
     *
     * Ví dụ: "SELECT u.id, u.name, d.dept_name FROM users u JOIN dept d ON u.dept_id = d.id"
     */
    private String selectClause;

    /**
     * View có dùng WITH CHECK OPTION không.
     * Giá trị: "CASCADED", "LOCAL", hoặc null (không có)
     */
    private String checkOption;

    /** Schema nguồn — dùng để replace trong selectClause khi source != target schema */
    private String sourceSchema;

    /** Schema đích — thay thế sourceSchema trong selectClause */
    private String targetSchema;

    /**
     * Cờ cho biết đây là MATERIALIZED VIEW hay thường.
     * Hiện tại chỉ hỗ trợ regular VIEW.
     */
    private boolean materialized;
}
