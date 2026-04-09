package org.example.migrationdbweb.migratetool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcUrlParamResolverTests {

    @Test
    void shouldResolveSchemaFromCurrentSchemaParam() {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/migration?sslmode=require&currentSchema=archive";

        String schema = JdbcUrlParamResolver.resolvePostgresSchemaFromUrl(jdbcUrl).orElse("");

        assertEquals("archive", schema);
    }

    @Test
    void shouldResolveFirstSchemaFromSearchPathParam() {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/migration?search_path=%24user,archive,public";

        String schema = JdbcUrlParamResolver.resolvePostgresSchemaFromUrl(jdbcUrl).orElse("");

        assertEquals("archive", schema);
    }

    @Test
    void shouldResolveTimezoneFromOptionsParam() {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/migration?options=-c%20TimeZone%3DAsia%2FHo_Chi_Minh";

        String timezone = JdbcUrlParamResolver.resolvePostgresTimezoneFromUrl(jdbcUrl).orElse("");

        assertEquals("Asia/Ho_Chi_Minh", timezone);
    }

    @Test
    void shouldDetectMissingSchemaAndTimezoneParams() {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/migration?sslmode=require";

        assertFalse(JdbcUrlParamResolver.hasExplicitPostgresSchemaParam(jdbcUrl));
        assertFalse(JdbcUrlParamResolver.hasExplicitPostgresTimezoneParam(jdbcUrl));
    }

    @Test
    void shouldIgnoreNonPostgresUrls() {
        String jdbcUrl = "jdbc:oracle:thin:@localhost:1521:ORCL?currentSchema=SCOTT&TimeZone=UTC";

        assertTrue(JdbcUrlParamResolver.resolvePostgresSchemaFromUrl(jdbcUrl).isEmpty());
        assertTrue(JdbcUrlParamResolver.resolvePostgresTimezoneFromUrl(jdbcUrl).isEmpty());
    }

    @Test
    void shouldResolveOracleSchemaFromCurrentSchemaParam() {
        String jdbcUrl = "jdbc:oracle:thin:@//localhost:1521/XEPDB1?currentSchema=APP_MIGRATION";

        String schema = JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl).orElse("");

        assertEquals("APP_MIGRATION", schema);
        assertTrue(JdbcUrlParamResolver.hasExplicitOracleSchemaParam(jdbcUrl));
    }

    @Test
    void shouldResolveOracleSchemaFromOracleJdbcCurrentSchemaParam() {
        String jdbcUrl = "jdbc:oracle:thin:@//localhost:1521/XEPDB1?oracle.jdbc.currentSchema=APP_URL";

        String schema = JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl).orElse("");

        assertEquals("APP_URL", schema);
        assertTrue(JdbcUrlParamResolver.hasExplicitOracleSchemaParam(jdbcUrl));
    }

    @Test
    void shouldPreferOracleSpecificSchemaKeyWhenMultipleSchemaKeysProvided() {
        String jdbcUrl = "jdbc:oracle:thin:@//localhost:1521/XEPDB1"
                + "?currentSchema=FROM_CURRENT"
                + "&oracle.jdbc.currentSchema=FROM_ORACLE_KEY"
                + "&schema=FROM_SCHEMA";

        String schema = JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl).orElse("");

        assertEquals("FROM_ORACLE_KEY", schema);
    }

    @Test
    void shouldResolveOracleSchemaFromSchemaParamAndUnquote() {
        String jdbcUrl = "jdbc:oracle:thin:@//localhost:1521/XEPDB1?schema=%22AppSchema%22";

        String schema = JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl).orElse("");

        assertEquals("AppSchema", schema);
    }

    @Test
    void shouldIgnoreNonOracleUrlsForOracleSchemaResolver() {
        String jdbcUrl = "jdbc:postgresql://localhost:5432/migration?currentSchema=archive";

        assertTrue(JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl).isEmpty());
        assertFalse(JdbcUrlParamResolver.hasExplicitOracleSchemaParam(jdbcUrl));
    }
}
