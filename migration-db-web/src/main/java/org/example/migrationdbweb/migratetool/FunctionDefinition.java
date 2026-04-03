package org.example.migrationdbweb.migratetool;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * FunctionDefinition — POJO chứa định nghĩa một FUNCTION hoặc PROCEDURE.
 *
 * PostgreSQL từ PG 11+ hỗ trợ PROCEDURE riêng (CREATE PROCEDURE).
 * Trước PG 11, procedure được viết như function void.
 */
@Data
@Builder
public class FunctionDefinition {

    /** Tên function */
    private String functionName;

    /** Schema chứa function */
    private String schema;

    /** Database type nguồn (ORACLE / POSTGRESQL) */
    private DatabaseType sourceDialect;

    /** Loại: FUNCTION hoặc PROCEDURE */
    private FunctionType functionType;

    /** Ngôn ngữ: PL/SQL (Oracle), plpgsql (PostgreSQL) */
    private String language;

    /**
     * Danh sách tham số.
     * Ví dụ: (p_emp_id IN NUMBER, p_name OUT VARCHAR2)
     */
    @Builder.Default
    private List<FunctionArgument> arguments = new ArrayList<>();

    /**
     * Kiểu trả về (chỉ cho FUNCTION, null cho PROCEDURE).
     * Ví dụ: "NUMBER", "VARCHAR2", "REFCURSOR"
     */
    private String returnType;

    /**
     * Function body — phần PL/SQL thực thi.
     * Oracle: phần BETWEEN AS/IS và END
     * PostgreSQL: phần BETWEEN AS $$ và $$ LANGUAGE
     */
    private String functionBody;

    /**
     * Function body đã được transform từ Oracle → PostgreSQL.
     */
    private String transformedBody;

    /**
     * Cờ: function có OUT parameter hay không.
     * PostgreSQL dùng INOUT thay vì OUT riêng.
     */
    private boolean hasOutParams;

    /**
     * Cờ: function có deterministic (Oracle) hay không.
     * PostgreSQL không có khái niệm này.
     */
    private boolean deterministic;

    /** Schema nguồn */
    private String sourceSchema;

    /** Schema đích */
    private String targetSchema;

    /** Full DDL text gốc (ví dụ từ pg_get_functiondef) để tái tạo function/procedure theo nguyên bản. */
    private String ddlText;

    public enum FunctionType {
        FUNCTION,
        PROCEDURE
    }

    @Data
    @Builder
    public static class FunctionArgument {
        /** Tên tham số (có thể null cho positional) */
        private String name;

        /** Kiểu dữ liệu */
        private String dataType;

        /** IN (default) / OUT / INOUT */
        private ArgumentMode mode;

        /** Giá trị mặc định */
        private String defaultValue;

        public enum ArgumentMode {
            IN,
            OUT,
            INOUT
        }
    }

    public List<FunctionArgument> getArguments() {
        if (arguments == null) arguments = new ArrayList<>();
        return arguments;
    }

    public boolean isFunction() {
        return functionType == FunctionType.FUNCTION;
    }

    public boolean isProcedure() {
        return functionType == FunctionType.PROCEDURE;
    }
}
