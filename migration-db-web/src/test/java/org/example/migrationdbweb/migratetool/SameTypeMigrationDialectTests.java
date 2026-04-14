package org.example.migrationdbweb.migratetool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SameTypeMigrationDialectTests {

    @Test
    void shouldUseRawPostgresFunctionDdlForSameTypeMigration() {
        FunctionDefinition fn = FunctionDefinition.builder()
                .functionName("fn_test")
                .sourceDialect(DatabaseType.POSTGRESQL)
                .functionType(FunctionDefinition.FunctionType.FUNCTION)
                .sourceSchema("public")
                .targetSchema("archive")
                .ddlText("CREATE OR REPLACE FUNCTION public.fn_test() RETURNS integer LANGUAGE plpgsql AS $$ BEGIN RETURN 1; END; $$;")
                .build();

        List<String> sqls = new PostgresDialect().buildCreateFunctionSql(fn, null);

        assertEquals(1, sqls.size());
        assertTrue(sqls.get(0).contains("archive.fn_test()"));
    }

    @Test
    void shouldUseRawPostgresTriggerDdlForSameTypeMigration() {
        TriggerDefinition trig = TriggerDefinition.builder()
                .triggerName("trg_audit")
                .sourceDialect(DatabaseType.POSTGRESQL)
                .sourceSchema("public")
                .targetSchema("archive")
                .functionDdl("CREATE OR REPLACE FUNCTION public.trg_audit_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$;")
                .ddlText("CREATE TRIGGER trg_audit BEFORE INSERT ON public.orders FOR EACH ROW EXECUTE FUNCTION public.trg_audit_fn();")
                .build();

        List<String> sqls = new PostgresDialect().buildCreateTriggerSql(trig, null);

        assertEquals(2, sqls.size());
        assertTrue(sqls.get(0).contains("archive.trg_audit_fn()"));
        assertTrue(sqls.get(1).contains("archive.orders"));
    }

    @Test
    void shouldStripOracleRoutineHeaderWhenTargetIsOracle() {
        FunctionDefinition fn = FunctionDefinition.builder()
                .functionName("calc_tax")
                .sourceDialect(DatabaseType.ORACLE)
                .functionType(FunctionDefinition.FunctionType.FUNCTION)
                .returnType("NUMBER")
                .arguments(List.of(
                        FunctionDefinition.FunctionArgument.builder()
                                .name("p_amount")
                                .mode(FunctionDefinition.FunctionArgument.ArgumentMode.IN)
                                .dataType("NUMBER")
                                .build()
                ))
                .functionBody("FUNCTION CALC_TAX(P_AMOUNT NUMBER) RETURN NUMBER IS\nBEGIN\n  RETURN P_AMOUNT;\nEND;")
                .build();

        List<String> sqls = new OracleDialect().buildCreateFunctionSql(fn, null);

        assertEquals(1, sqls.size());
        String sql = sqls.get(0);
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION \"CALC_TAX\""));
        assertFalse(sql.contains("\nFUNCTION CALC_TAX("));
        assertTrue(sql.contains(" AS\nBEGIN\n  RETURN P_AMOUNT;"));
    }

    @Test
    void shouldUseRawOracleRoutineDdlForSameTypeMigration() {
        FunctionDefinition fn = FunctionDefinition.builder()
                .functionName("calc_tax")
                .sourceDialect(DatabaseType.ORACLE)
                .functionType(FunctionDefinition.FunctionType.FUNCTION)
                .sourceSchema("SCOTT")
                .targetSchema("ARCHIVE")
                .ddlText("CREATE OR REPLACE FUNCTION \"SCOTT\".\"CALC_TAX\"(P_AMOUNT NUMBER) RETURN NUMBER AUTHID DEFINER IS BEGIN RETURN P_AMOUNT; END;")
                .functionBody("FUNCTION CALC_TAX(P_AMOUNT NUMBER) RETURN NUMBER IS BEGIN RETURN 0; END;")
                .build();

        List<String> sqls = new OracleDialect().buildCreateFunctionSql(fn, null);

        assertEquals(1, sqls.size());
        String sql = sqls.get(0);
        assertTrue(sql.contains("\"ARCHIVE\".\"CALC_TAX\""));
        assertTrue(sql.contains("AUTHID DEFINER"));
        assertFalse(sql.contains("RETURN 0"));
    }

    @Test
    void shouldStripOracleTableSharingClauseForSameTypeMigration() {
        TableDefinition table = new TableDefinition("EAT_ATENAKIHON_MASKING");
        table.setSourceSchema("SCOTT");
        table.setTargetSchema("SCOTT2");
        table.setDdlText("CREATE TABLE \"SCOTT\".\"EAT_ATENAKIHON_MASKING\" SHARING=METADATA (\"ATENA_NO\" VARCHAR2(15 CHAR) NOT NULL ENABLE)");

        String sql = new OracleDialect().buildCreateTableSql(table);

        assertTrue(sql.contains("\"SCOTT2\".\"EAT_ATENAKIHON_MASKING\""));
        assertFalse(sql.toUpperCase().contains("SHARING="));
    }

    @Test
    void shouldBuildOracleViewCreateSqlFromSelectClauseForSameTypeMigration() {
        ViewDefinition view = ViewDefinition.builder()
                .viewName("V_CUSTOMER_ORDERS")
                .sourceDialect(DatabaseType.ORACLE)
                .sourceSchema("SCOTT")
                .targetSchema("SCOTT2")
                .selectClause("SELECT * FROM SCOTT.ORDERS")
                .build();

        String sql = new OracleDialect().buildCreateViewSql(view);

        assertTrue(sql.startsWith("CREATE OR REPLACE VIEW"));
        assertTrue(sql.contains("\"V_CUSTOMER_ORDERS\""));
        assertTrue(sql.contains("SELECT * FROM SCOTT2.ORDERS"));
    }

    @Test
    void shouldRemapOracleSchemaSafelyWithoutTouchingLiteralsOrComments() {
        String ddl = """
                CREATE OR REPLACE FUNCTION SCOTT.CALC_TAX RETURN NUMBER IS
                BEGIN
                  -- keep SCOTT.ORDERS in comment
                  DBMS_OUTPUT.PUT_LINE('SCOTT.ORDERS literal');
                  RETURN (SELECT COUNT(*) FROM SCOTT.ORDERS);
                END;
                """;

        String remapped = OracleDialect.remapSchemaPrefixSafely(ddl, "SCOTT", "SCOTT2");

        assertTrue(remapped.contains("FUNCTION SCOTT2.CALC_TAX"));
        assertTrue(remapped.contains("FROM SCOTT2.ORDERS"));
        assertTrue(remapped.contains("'SCOTT.ORDERS literal'"));
        assertTrue(remapped.contains("-- keep SCOTT.ORDERS in comment"));
    }

    @Test
    void shouldPreserveTargetSchemaCaseWhenRemappingQuotedSchema() {
        String ddl = "SELECT * FROM \"public\".\"orders\"";

        String remapped = OracleDialect.remapSchemaPrefixSafely(ddl, "public", "archive");

        assertTrue(remapped.contains("\"archive\".\"orders\""));
        assertFalse(remapped.contains("\"ARCHIVE\""));
    }
}
