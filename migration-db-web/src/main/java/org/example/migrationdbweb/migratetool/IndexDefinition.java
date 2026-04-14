package org.example.migrationdbweb.migratetool;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * IndexDefinition — POJO chứa định nghĩa một INDEX,
 * song song với ForeignKeyDefinition.
 *
 * So sánh với ForeignKeyDefinition:
 * - ForeignKeyDefinition: ràng buộc từ bảng này → bảng khác
 * - IndexDefinition:      cấu trúc tăng tốc truy vấn trên cột
 *
 * Lưu ý:
 * - Index thường tạo SAU khi có dữ liệu (Phase 7)
 * - Một số loại index không có tương đương giữa Oracle và PostgreSQL
 *   (bitmap index, domain index, function-based index)
 */
@Data
@Builder
public class IndexDefinition {

    /** Tên index — null nếu là anonymous index (PostgreSQL) */
    private String indexName;

    /** Schema chứa index */
    private String schema;

    /** Tên bảng mà index được tạo trên */
    private String tableName;

    /** Tên các cột trong index (theo thứ tự) */
    @Builder.Default
    private List<String> columns = new ArrayList<>();

    /** Danh sách cột theo thứ tự ASC/DESC */
    @Builder.Default
    private List<String> descendings = new ArrayList<>();

    /** Tính duy nhất: UNIQUE / NONUNIQUE */
    private boolean unique;

    /** Loại index: BTREE, HASH, BITMAP, GIST, GIN, BRIN, FUNCTION, DOMAIN */
    private IndexType indexType;

    /** Tablespace — Oracle only, PostgreSQL bỏ qua */
    private String tablespace;

    /**
     * Biểu thức cho function-based index (Oracle: function-based;
     * PostgreSQL: expression index).
     * Ví dụ: "UPPER(name)" hoặc "(price * qty)"
     */
    private String expression;

    /**
     * Filter predicate cho partial index (WHERE clause).
     * Ví dụ: "WHERE status = 'ACTIVE'"
     */
    private String whereClause;

    /**
     * Tên index type (Oracle: INDEX_TYPE từ DBA_INDEXES;
     * PostgreSQL: từ pg_indexes.indexdef).
     */
    private String indexTypeName;

    /** Database type nguồn */
    private DatabaseType sourceDialect;

    /** Schema nguồn — dùng để replace trong DDL khi source != target schema */
    private String sourceSchema;

    /** Schema đích — thay thế sourceSchema trong DDL */
    private String targetSchema;

    /** Full DDL text gốc của index (DBMS_METADATA / pg metadata). */
    private String ddlText;

    /** Cờ: index có được tạo bởi system (FK index, implicit) hay user */
    private boolean systemIndex;

    public enum IndexType {
        /** B-tree — mặc định, dùng cho =, <, >, BETWEEN */
        BTREE,
        /** Hash — chỉ dùng cho equality trên large tables */
        HASH,
        /** Bitmap — Oracle-only, PostgreSQL: BITMAP không tồn tại */
        BITMAP,
        /** GiST — PostgreSQL spatial index */
        GIST,
        /** GIN — PostgreSQL inverted index cho JSONB, ARRAY, full-text */
        GIN,
        /** BRIN — PostgreSQL block range index cho large sequential data */
        BRIN,
        /** Function-based index (Oracle) / Expression index (PostgreSQL) */
        FUNCTION,
        /** Domain index — Oracle, extension-dependent */
        DOMAIN,
        /** Unknown / không xác định được */
        UNKNOWN
    }

    /**
     * Kiểm tra index có hỗ trợ migrate từ Oracle → PostgreSQL hay không.
     *
     * BITMAP, GIST, GIN, BRIN, DOMAIN: chỉ migrate khi target hỗ trợ.
     * PostgreSQL: BITMAP, DOMAIN không tương đương.
     */
    public boolean isMigratable() {
        if (indexType == null) {
            return true; // mặc định coi là migratable
        }
        switch (indexType) {
            case BITMAP:
            case DOMAIN:
                return false;
            default:
                return true;
        }
    }

    /**
     * Kiểm tra index có phải là function-based / expression index không.
     */
    public boolean isExpressionIndex() {
        return (expression != null && !expression.isBlank())
                || (indexType == IndexType.FUNCTION);
    }

    /**
     * Kiểm tra index có phải là partial index (WHERE clause) không.
     */
    public boolean isPartialIndex() {
        return whereClause != null && !whereClause.isBlank();
    }

    public List<String> getColumns() {
        if (columns == null) columns = new ArrayList<>();
        return columns;
    }

    public List<String> getDescendings() {
        if (descendings == null) descendings = new ArrayList<>();
        return descendings;
    }
}
