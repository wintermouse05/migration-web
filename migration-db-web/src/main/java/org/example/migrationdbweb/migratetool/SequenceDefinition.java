package org.example.migrationdbweb.migratetool;

import lombok.Builder;
import lombok.Data;

/**
 * SequenceDefinition — POJO chứa định nghĩa một SEQUENCE,
 * song song với TableDefinition / ViewDefinition.
 *
 * Sequence dùng để tạo giá trị tự tăng (auto-increment) thay thế
 * cho IDENTITY/GENERATED COLUMN khi cần shared counter giữa nhiều bảng.
 *
 * Ví dụ Oracle:
 *   CREATE SEQUENCE emp_seq START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 999999999999999 NOCACHE CYCLE;
 *
 * Ví dụ PostgreSQL:
 *   CREATE SEQUENCE emp_seq START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 999999999999999 CACHE 1 NO CYCLE;
 */
@Data
@Builder
public class SequenceDefinition {

    /** Tên sequence */
    private String sequenceName;

    /** Schema chứa sequence */
    private String schema;

    /** Database type nguồn (ORACLE / POSTGRESQL) */
    private DatabaseType sourceDialect;

    /** Giá trị bắt đầu */
    private long startValue;

    /** Bước tăng (âm cho sequence giảm) */
    private long incrementBy;

    /** Giá trị nhỏ nhất */
    private Long minValue;

    /** Giá trị lớn nhất */
    private Long maxValue;

    /** Số giá trị được cache (Oracle: CACHE; Postgres: CACHE) */
    private Long cacheSize;

    /** Oracle: CYCLE_FLAG=Y/N ; PostgreSQL: CYCLE (true/false) */
    private boolean cycle;

    /**
     * Giá trị LAST_NUMBER từ Oracle — dùng để đặt lại sequence
     * sao cho giá trị tiếp theo không trùng với dữ liệu đã insert.
     * Chỉ set khi extract từ Oracle.
     */
    private Long lastNumber;

    /** Schema nguồn — dùng để replace trong DDL khi source != target schema */
    private String sourceSchema;

    /** Schema đích — thay thế sourceSchema trong DDL */
    private String targetSchema;

    /** Full DDL text gốc của sequence (DBMS_METADATA / pg metadata). */
    private String ddlText;

    /**
     * Kiểm tra sequence có phải là sequence hệ thống (nên bỏ qua) hay không.
     * Oracle: sequences bắt đầu bằng ISEQ$$ là hệ thống.
     */
    public boolean isSystemSequence() {
        return sequenceName != null
                && (sequenceName.startsWith("ISEQ$$")
                    || sequenceName.startsWith("BIN$")
                    || sequenceName.startsWith("DR$"));
    }
}
