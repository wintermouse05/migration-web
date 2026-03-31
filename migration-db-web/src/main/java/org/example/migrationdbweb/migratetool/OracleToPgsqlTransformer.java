package org.example.migrationdbweb.migratetool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle → PostgreSQL PL/SQL Transformation Engine.
 *
 * Dựa trên các regex substitution rules từ ora2pg (lib/Ora2Pg/PLSQL.pm).
 *
 * Mỗi transformation là một bước trong pipeline, áp dụng theo thứ tự.
 * Class này xử lý:
 * - Function/View body SQL
 * - Trigger body
 * - Function/Procedure PL/SQL
 *
 * Cú pháp Oracle đặc thù (DECODE, NVL, ROWNUM, v.v.) được chuyển thành
 * tương đương PostgreSQL trước khi execute trên target.
 */
public class OracleToPgsqlTransformer {

    // ─────────────────────────────────────────────────────────────────
    // TRANSFORMATION RULES (theo thứ tự áp dụng)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Thay thế một loạt Oracle function đặc thù bằng tương đương PostgreSQL.
     * Áp dụng theo thứ tự: trước tiên các function phức tạp (nhiều tham số hơn),
     * sau đó các function đơn giản (để tránh double-replace).
     */
    private final Map<Pattern, String> functionMappings = new LinkedHashMap<>();

    {
        // ── DATE/TIME FUNCTIONS ──────────────────────────────────────

        // SYSDATE và SYSTIMESTAMP
        register("(?i)\\bSYSTIMESTAMP\\b", "statement_timestamp()");
        register("(?i)\\bSYSDATE\\b(?!\s*\\+)", "clock_timestamp()");
        // SYSDATE + N ngày: SYSDATE + 7 → clock_timestamp() + '7 days'::interval
        register("(?i)\\bSYSDATE\\s*\\+\\s*(\\d+)\\b",
                "clock_timestamp() + ($1 || ' days')::interval");

        // TRUNC(date) — Oracle cắt ngày
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,)]+)\\s*\\)",
                "date_trunc('day', $1)");
        // TRUNC(num) — Oracle cắt số thập phân
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,)]+,\\s*[^)]+)\\s*\\)",
                "trunc($1)");

        // ADD_MONTHS(d, n)
        register("(?i)\\bADD_MONTHS\\s*\\(\\s*([^,]+)\\s*,\\s*(-?\\d+)\\s*\\)",
                "($1 + ($2 || ' months')::interval)");
        register("(?i)\\bADD_MONTHS\\s*\\(\\s*([^,]+)\\s*,\\s*(\\d+)\\s*\\)",
                "($1 + ($2 || ' months')::interval)");

        // LAST_DAY(d) — ngày cuối tháng
        register("(?i)\\bLAST_DAY\\s*\\(\\s*([^)]+)\\s*\\)",
                "(date_trunc('month', $1) + interval '1 month' - interval '1 day')::date");

        // MONTHS_BETWEEN
        register("(?i)\\bMONTHS_BETWEEN\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "((date_part('epoch', ($1)::date - ($2)::date) / 2629800.0))");

        // NUMTODSINTERVAL
        register("(?i)\\bNUMTODSINTERVAL\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "($1 || ' $2')::interval");

        // ── STRING FUNCTIONS ────────────────────────────────────────

        // NVL(a, b) → COALESCE(a, b)
        register("(?i)\\bNVL\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "COALESCE($1, $2)");

        // NVL2(a, b, c) → CASE WHEN a IS NOT NULL THEN b ELSE c END
        register("(?i)\\bNVL2\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "(CASE WHEN $1 IS NOT NULL THEN $2 ELSE $3 END)");

        // DECODE — phức tạp nhất, xử lý nhiều trường hợp
        // DECODE(expr, val1, res1 [, val2, res2 ...] [, default])
        // Chuyển thành CASE WHEN expr = val1 THEN res1 WHEN expr = val2 THEN res2 ... ELSE default END

        // INSTR(str, sub) → position(sub IN str)
        register("(?i)\\bINSTR\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "position('$2' IN $1)");
        register("(?i)\\bINSTR\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*\\)",
                "position($2 IN $1)");

        // SUBSTR(str, pos[, len]) → substring(str FROM pos[ FOR len])
        // Oracle: SUBSTR('abc', 2, 1) = 'b'
        // PostgreSQL: substring('abc' FROM 2 FOR 1) = 'b'
        register("(?i)\\bSUBSTR\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "substring($1 FROM $2 FOR $3)");
        register("(?i)\\bSUBSTR\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "substring($1 FROM $2)");

        // LENGTH2 → octet_length (cho multi-byte)
        register("(?i)\\bLENGTH2\\s*\\(\\s*([^)]+)\\s*\\)",
                "octet_length($1)");

        // LISTAGG — Oracle: LISTAGG(col, ',') WITHIN GROUP (ORDER BY col)
        // PostgreSQL: string_agg(col, ',') OVER (ORDER BY col)
        register("(?i)\\bLISTAGG\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)\\s*WITHIN\\s*GROUP\\s*\\(\\s*ORDER\\s+BY\\s+([^)]+)\\s*\\)",
                "string_agg($1, '$2') OVER (ORDER BY $3)");
        register("(?i)\\bLISTAGG\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "string_agg($1, '$2')");

        // REPLACE(a, b) → replace(a, b, '') (PostgreSQL bắt buộc 3 tham số)
        register("(?i)\\bREPLACE\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "replace($1, '$2', '')");
        register("(?i)\\bREPLACE\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*\\)",
                "replace($1, $2, '')");

        // ── REGULAR EXPRESSION ───────────────────────────────────────

        // REGEXP_LIKE(str, pattern[, flags]) → str ~ pattern
        register("(?i)\\bREGEXP_LIKE\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "($1 ~ '$2')");
        register("(?i)\\bREGEXP_LIKE\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)",
                "($1 ~* '$2')");

        // REGEXP_COUNT → count(*) FROM regexp_matches
        register("(?i)\\bREGEXP_COUNT\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "(SELECT count(*) FROM regexp_matches($1, '$2', 'g'))");

        // REGEXP_SUBSTR → regexp_matches (lấy row đầu)
        register("(?i)\\bREGEXP_SUBSTR\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "(SELECT (regexp_matches($1, '$2', 'g'))[1])");

        // REGEXP_REPLACE → regexp_replace
        register("(?i)\\bREGEXP_REPLACE\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)",
                "regexp_replace($1, '$2', '$3', 'g')");

        // ── BITWISE ─────────────────────────────────────────────────

        register("(?i)\\bBITAND\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "($1 & $2)");
        register("(?i)\\bBITOR\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "($1 | $2)");
        register("(?i)\\bBITXOR\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "($1 # $2)");

        // ── DATABASE UTILITIES ──────────────────────────────────────

        // DBMS_LOB
        register("(?i)\\bDBMS_LOB\\.GETLENGTH\\s*\\(\\s*([^)]+)\\s*\\)",
                "octet_length($1)");
        register("(?i)\\bDBMS_LOB\\.SUBSTR\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "substring($1, $3, $2)");

        // DBMS_OUTPUT.PUT_LINE → RAISE NOTICE
        register("(?i)\\bDBMS_OUTPUT\\.PUT_LINE\\s*\\(\\s*([^)]+)\\s*\\)",
                "RAISE NOTICE '%', $1");

        // DBMS_LOCK.SLEEP → pg_sleep (giây)
        register("(?i)\\bDBMS_LOCK\\.SLEEP\\s*\\(\\s*([^)]+)\\s*\\)",
                "pg_sleep($1)");

        // TO_CLOB(...) → (...)
        register("(?i)\\bTO_CLOB\\s*\\(\\s*([^)]+)\\s*\\)", "($1)");
        register("(?i)\\bTO_CHAR\\s*\\(\\s*([^)]+)\\s*\\)", "($1)::text");

        // EMPTY_BLOB / EMPTY_CLOB → NULL
        register("(?i)\\bEMPTY_BLOB\\s*\\(\\s*\\)", "''::bytea");
        register("(?i)\\bEMPTY_CLOB\\s*\\(\\s*\\)", "''::text");

        // SYS_GUID() → gen_random_uuid()
        register("(?i)\\bSYS_GUID\\s*\\(\\s*\\)", "gen_random_uuid()");

        // DUMP(expr) → md5(expr::text)
        register("(?i)\\bDUMP\\s*\\(\\s*([^)]+)\\s*\\)", "md5($1::text)");

        // ── SYSTEM CONTEXT ───────────────────────────────────────────

        register("(?i)\\bUSER\\b", "current_user");
        register("(?i)\\bUID\\b", "current_setting('regrole')::oid");
        register("(?i)\\bSYSDATE\\b(?=\\s*\\+)", "clock_timestamp()");

        // SYS_CONTEXT('USERENV', 'SESSION_USER') → session_user
        register("(?i)\\bSYS_CONTEXT\\s*\\(\\s*'USERENV'\\s*,\\s*'SESSION_USER'\\s*\\)",
                "session_user");
        register("(?i)\\bSYS_CONTEXT\\s*\\(\\s*'USERENV'\\s*,\\s*'CURRENT_SCHEMA'\\s*\\)",
                "current_schema");
        register("(?i)\\bSYS_CONTEXT\\s*\\(\\s*'USERENV'\\s*,\\s*'HOST'\\s*\\)",
                "inet_client_addr()");
        register("(?i)\\bSYS_CONTEXT\\s*\\(\\s*'USERENV'\\s*,\\s*'IP_ADDRESS'\\s*\\)",
                "inet_client_addr()");

        // ── SQL CLAUSE TRANSFORMATIONS ──────────────────────────────

        // MINUS → EXCEPT
        register("\\bMINUS\\b", "EXCEPT");
        // SELECT UNIQUE → SELECT DISTINCT (Oracle syntax)
        register("(?i)\\bSELECT\\s+UNIQUE\\b", "SELECT DISTINCT");

        // DELETE tablename → DELETE FROM tablename (Oracle允许)
        register("(?i)\\bDELETE\\s+(\\w+)(?!\\s+FROM)", "DELETE FROM $1");

        // INSERT INTO ... VALUES val → INSERT INTO ... VALUES (val) (thêm ngoặc)
        // Chỉ áp dụng khi có VALUES nhưng không có dấu (
        register("(?i)\\bVALUES\\s+([^'(]+)(?!\\s*\\))", "VALUES ($1)");

        // ── ROWNUM & ROWID ──────────────────────────────────────────

        // ROWNUM (Oracle) → ROW_NUMBER() OVER() (PostgreSQL)
        // Đặt ở đây để tránh bị SYSDATE regex phía trên match nhầm
        register("\\bROWNUM\\b", "row_number() OVER()");

        // ROWID (Oracle) → ctid::text::bigint (PostgreSQL)
        register("(?i)\\bROWID\\b", "(ctid::text::bigint)");

        // ── RANKING FUNCTIONS ──────────────────────────────────────

        // RANK() OVER(ORDER BY ...) → RANK() OVER(ORDER BY ...) (same syntax, just word boundary)
        register("(?i)\\bRANK\\s*\\(\\s*\\)\\s*OVER\\b", "RANK() OVER");
        // DENSE_RANK() OVER(ORDER BY ...) → DENSE_RANK() OVER (same syntax)
        register("(?i)\\bDENSE_RANK\\s*\\(\\s*\\)\\s*OVER\\b", "DENSE_RANK() OVER");
        // ROW_NUMBER() OVER (keep as-is, same syntax)
        register("(?i)\\bROW_NUMBER\\s*\\(\\s*\\)\\s*OVER\\b", "ROW_NUMBER() OVER");

        // ROWNUM alias trong subquery: SELECT ..., ROWNUM FROM ... → row_number() OVER()
        register("(?i)\\bROWNUM\\s+(AS\\s+)?(\\w+)", "row_number() OVER() AS $2");

        // ── LEVEL PSEUDO-COLUMN ────────────────────────────────────
        // Oracle: LEVEL (pseudo-column for hierarchical queries) → 1-based depth
        register("(?i)\\bLEVEL\\b", "(row_number() OVER())");

        // ── CONNECT BY (Recursive CTE) ─────────────────────────────
        // Oracle: SELECT * FROM table START WITH col=val CONNECT BY PRIOR col=parent
        // PG: WITH RECURSIVE cte AS (SELECT * FROM table WHERE col=val
        //                            UNION ALL SELECT t.* FROM table t JOIN cte ON ...)
        // Đây là placeholder cảnh báo — cần viết lại thủ công cho từng trường hợp
        register("(?i)\\bSTART\\s+WITH\\b", "/* START WITH — rewrite manually with WITH RECURSIVE */");
        register("(?i)\\bCONNECT\\s+BY\\s+(PRIOR\\s+)?", "/* CONNECT BY — rewrite manually */");

        // ── DATE/TIME CONVERSIONS (ora2pg) ────────────────────────────

        // TO_DATE(str, fmt) — 3 biến thể (ora2pg lines 1884-1888)
        // TO_DATE('2024-01-01', 'YYYY-MM-DD') → to_date('2024-01-01', 'YYYY-MM-DD')
        register("(?i)\\bTO_DATE\\s*\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)",
                "to_date('$1', '$2')");
        // TO_DATE(expr, fmt) với biến hoặc expression
        register("(?i)\\bTO_DATE\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "to_date(CAST($1 AS TEXT), '$2')");

        // TO_TIMESTAMP(expr) — Oracle timestamp conversion
        register("(?i)\\bTO_TIMESTAMP\\s*\\(\\s*([^)]+)\\s*\\)",
                "to_timestamp($1)");
        // TO_TIMESTAMP_TZ
        register("(?i)\\bTO_TIMESTAMP_TZ\\s*\\(\\s*([^)]+)\\s*\\)",
                "($1)::timestamptz");

        // FROM_TZ — Oracle chuyển timestamp có timezone
        // FROM_TZ(ts, 'UTC') → ts AT TIME ZONE 'UTC'
        register("(?i)\\bFROM_TZ\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "($1 AT TIME ZONE '$2')::timestamptz");

        // SYS_EXTRACT_UTC(ts) → ts AT TIME ZONE 'UTC'
        register("(?i)\\bSYS_EXTRACT_UTC\\s*\\(\\s*([^)]+)\\s*\\)",
                "($1 AT TIME ZONE 'UTC')");

        // NEXT_DAY(date, day_name) — ngày tiếp theo có tên ngày trong tuần
        // Oracle: NEXT_DAY(SYSDATE, 'FRIDAY') → date_trunc cộng interval
        // Cần cảnh báo vì đây là complex transformation
        register("(?i)\\bNEXT_DAY\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "/* NEXT_DAY($1, '$2') — rewrite manually using EXTRACT(DOW) + interval */ $1");

        // ADD_MONTHS với tham số là biến (dynamic, không phải số cố định)
        // ora2pg: ADD_MONTHS(expr, var) → expr + var * '1 month'::interval
        register("(?i)\\bADD_MONTHS\\s*\\(\\s*([^,]+)\\s*,\\s*([^\\)]+)\\s*\\)",
                "($1 + ($2 * interval '1 month'))");

        // NUMTOYMINTERVAL — Oracle chuyển số thành interval năm/tháng
        register("(?i)\\bNUMTOYMINTERVAL\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "($1 * ('1' || '$2')::interval)");

        // TRUNC với format specifier (khác 'day')
        // TRUNC(date, 'YYYY') → date_trunc('year', date)
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,]+)\\s*,\\s*'YYYY'\\s*\\)",
                "date_trunc('year', $1)");
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,]+)\\s*,\\s*'MONTH'\\s*\\)",
                "date_trunc('month', $1)");
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,]+)\\s*,\\s*'DD'\\s*\\)",
                "date_trunc('day', $1)");
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,]+)\\s*,\\s*'HH24'\\s*\\)",
                "date_trunc('hour', $1)");
        register("(?i)\\bTRUNC\\s*\\(\\s*([^,]+)\\s*,\\s*'MI'\\s*\\)",
                "date_trunc('minute', $1)");

        // ── STRING FUNCTIONS (ora2pg) ───────────────────────────────

        // TRIM — Oracle TRIM không có BOTH/LEADING/TRAILING prefix
        // TRIM(' x ') → btrim(' x ')
        // TRIM(LEADING '0' FROM col) → ltrim(col, '0')
        register("(?i)\\bTRIM\\s*\\(\\s*'([^']*)'\\s*FROM\\s+([^)]+)\\s*\\)",
                "trim(BOTH '$1' FROM $2)");
        register("(?i)\\bLTRIM\\s*\\(\\s*'([^']*)'\\s*FROM\\s+([^)]+)\\s*\\)",
                "ltrim($2, '$1')");
        register("(?i)\\bRTRIM\\s*\\(\\s*'([^']*)'\\s*FROM\\s+([^)]+)\\s*\\)",
                "rtrim($2, '$1')");
        // Simple TRIM(expr) → btrim(expr)
        register("(?i)\\bTRIM\\s*\\(\\s*([^)]+)\\s*\\)", "btrim($1)");
        register("(?i)\\bLTRIM\\s*\\(\\s*([^)]+)\\s*\\)", "ltrim($1)");
        register("(?i)\\bRTRIM\\s*\\(\\s*([^)]+)\\s*\\)", "rtrim($1)");

        // LPAD(str, len[, pad]) → lpad(str, len[, pad])
        register("(?i)\\bLPAD\\s*\\(\\s*([^,]+)\\s*,\\s*([^,)]+)\\s*\\)",
                "lpad($1, $2)");
        register("(?i)\\bLPAD\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "lpad($1, $2, '$3')");

        // RPAD(str, len[, pad]) → rpad(str, len[, pad])
        register("(?i)\\bRPAD\\s*\\(\\s*([^,]+)\\s*,\\s*([^,)]+)\\s*\\)",
                "rpad($1, $2)");
        register("(?i)\\bRPAD\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*\\)",
                "rpad($1, $2, '$3')");

        // CHR(n) — Oracle: ký tự từ mã ASCII
        register("(?i)\\bCHR\\s*\\(\\s*(\\d+)\\s*\\)",
                "chr($1)");
        // CHR với USING NCHAR_CS
        register("(?i)\\bCHR\\s*\\(\\s*([^)]+)\\s*\\)",
                "chr($1)");

        // ASCII — lấy mã ASCII của ký tự đầu tiên
        register("(?i)\\bASCII\\s*\\(\\s*'([^']+)'\\s*\\)",
                "ascii('$1')");

        // TRANSLATE(string, from, to) — Oracle thay từng ký tự
        // PG không có TRANSLATE tương đương trực tiếp, cần regexp_replace
        register("(?i)\\bTRANSLATE\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)",
                "/* TRANSLATE($1, '$2', '$3') — rewrite manually with regexp_replace per char */ $1");

        // TO_NCHAR(expr) — Oracle chuyển sang NCHAR
        register("(?i)\\bTO_NCHAR\\s*\\(\\s*([^)]+)\\s*\\)", "($1)::text");

        // ── NUMERIC / TYPE CONVERSIONS (ora2pg) ───────────────────────

        // TO_NUMBER(expr) — Oracle số, bỏ format hoặc dùng ::numeric
        register("(?i)\\bTO_NUMBER\\s*\\(\\s*'([^']+)'\\s*\\)",
                "($1)::numeric");
        register("(?i)\\bTO_NUMBER\\s*\\(\\s*([^)]+)\\s*\\)",
                "($1)::numeric");

        // BINARY_FLOAT_NAN / BINARY_DOUBLE_NAN → 'NaN'::real/'NaN'::double precision
        register("(?i)\\bBINARY_FLOAT_NAN\\s*\\(\\s*\\)",
                "'NaN'::real");
        register("(?i)\\bBINARY_DOUBLE_NAN\\s*\\(\\s*\\)",
                "'NaN'::double precision");
        // BINARY_FLOAT_INFINITY / BINARY_DOUBLE_INFINITY
        register("(?i)\\bBINARY_FLOAT_INFINITY\\s*\\(\\s*\\)",
                "'Infinity'::real");
        register("(?i)\\bBINARY_DOUBLE_INFINITY\\s*\\(\\s*\\)",
                "'Infinity'::double precision");
        register("(?i)-?Inf'\\b", "'$1Infinity'");

        // SUBSTRB — byte semantics (khác SUBSTR char semantics)
        // Oracle: SUBSTRB('abc', 1, 2) — 2 bytes, không phải 2 ký tự
        register("(?i)\\bSUBSTRB\\s*\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "/* SUBSTRB (byte semantics) — verify manually */ substring($1, $2, $3)");

        // ── SEQUENCE CALLS WITH SCHEMA QUALIFIED (ora2pg) ────────────
        // schema.seq.NEXTVAL / schema.seq.CURRVAL — đặt TRƯỚC simple patterns

        // UTL_MATCH.EDIT_DISTANCE — Oracle fuzzy matching
        register("(?i)\\bUTL_MATCH\\.EDIT_DISTANCE\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                "levenshtein($1, $2)");

        // UTL_RAW.CAST_TO_RAW — Oracle chuyển text → raw bytes
        register("(?i)\\bUTL_RAW\\.CAST_TO_RAW\\s*\\(\\s*([^)]+)\\s*\\)",
                "encode($1::bytea, 'hex')::bytea");

        // ── EXCEPTION HANDLING (ora2pg) ───────────────────────────────

        // raise_application_error(code, message) → RAISE EXCEPTION '%' USING ERRCODE
        register("(?i)\\bRAISE_APPLICATION_ERROR\\s*\\(\\s*([^,]+)\\s*,\\s*([^);]+)\\s*\\)",
                "RAISE EXCEPTION '%', $2 USING ERRCODE = $1");

        // SQLCODE (Oracle) → SQLSTATE (PostgreSQL)
        register("(?i)\\bSQLCODE\\b", "SQLSTATE");

        // dup_val_on_index → unique_violation
        register("(?i)\\bdup_val_on_index\\b", "unique_violation");
        register("(?i)\\bno_data_found\\b", "no_data_found");
        register("(?i)\\btoo_many_rows\\b", "too_many_rows");

        // COMMIT / ROLLBACK trong function — Oracle có thể, PostgreSQL cần procedure
        register("(?i)\\bCOMMIT\\s*;", "/* COMMIT — not allowed in SQL functions */");
        register("(?i)\\bROLLBACK\\s*;", "/* ROLLBACK — not allowed in SQL functions */");
        register("(?i)\\bROLLBACK\\s+TO\\s+(\\w+)\\s*;",
                "/* ROLLBACK TO $1 — not supported */");
        register("(?i)\\bSAVEPOINT\\s+(\\w+)\\s*;",
                "/* SAVEPOINT $1 — not supported */");

        // EXIT WHEN ... NOTFOUND → EXIT WHEN NOT FOUND
        register("(?i)\\bEXIT\\s+WHEN\\s+(\\w+)\\s*%\\s*NOTFOUND\\s*;",
                "EXIT WHEN NOT FOUND /* apply on $1 */");
        register("(?i)\\bEXIT\\s+WHEN\\s+NOTFOUND\\s*;", "EXIT WHEN NOT FOUND");

        // LEAVE (MySQL/SQLServer) → EXIT
        register("(?i)\\bLEAVE\\s+(\\w+)\\s*;", "EXIT $1;");
        register("(?i)\\bLEAVE\\s*;", "EXIT;");

        // SQL%ROWCOUNT → GET DIAGNOSTICS
        register("(?i)\\bSQL\\s*%\\s*ROWCOUNT\\b", "GET DIAGNOSTICS _rowcount = ROW_COUNT");

        // CURSOR%NOTFOUND → NOT FOUND
        register("(?i)\\bNOTFOUND\\b", "NOT FOUND");

        // SELECT ... INTO STRICT → giữ nguyên nhưng bổ sung STRICT check
        // Ora2pg: thêm STRICT vào SELECT INTO để bắt no_data_found
        register("(?i)\\bSELECT\\s+([^;]+)\\s+INTO\\s+(\\w+)\\s*;",
                "SELECT INTO $2 $1; GET DIAGNOSTICS $2 = ROW_COUNT;");
    }

    private void register(String regex, String replacement) {
        functionMappings.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement);
    }

    // ─────────────────────────────────────────────────────────────────
    // SEQUENCE CALLS
    // ─────────────────────────────────────────────────────────────────

    private static final Pattern SEQ_NEXTVAL = Pattern.compile(
            "\\b(\\w+)\\.NEXTVAL\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SEQ_CURRVAL = Pattern.compile(
            "\\b(\\w+)\\.CURRVAL\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ─────────────────────────────────────────────────────────────────
    // PSEUDO-COLUMNS (Trigger context)
    // ─────────────────────────────────────────────────────────────────

    private static final Pattern TRIGGER_NEW = Pattern.compile(
            ":NEW\\.(\\w+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRIGGER_OLD = Pattern.compile(
            ":OLD\\.(\\w+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INSERTING = Pattern.compile(
            "\\bINSERTING\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern UPDATING = Pattern.compile(
            "\\bUPDATING\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DELETING = Pattern.compile(
            "\\bDELETING\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ─────────────────────────────────────────────────────────────────
    // SQL OPERATOR NORMALIZATION
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    // MAIN TRANSFORM METHOD
    // ─────────────────────────────────────────────────────────────────

    /**
     * Transform một Oracle PL/SQL expression, trigger body, view body,
     * function body thành PostgreSQL tương đương.
     *
     * Áp dụng pipeline:
     * 1. Operator normalization
     * 2. DECODE → CASE (trước function mappings vì DECODE chứa nested expressions)
     * 3. Function mappings (theo thứ tự trong LinkedHashMap)
     * 4. Sequence calls
     * 5. Trigger pseudo-columns
     *
     * @param input text Oracle PL/SQL
     * @return text đã chuyển đổi
     */
    public String transform(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = input;

        // 1. Normalize operators (xóa khoảng trắng thừa)
        result = normalizeOperators(result);

        // 2. DECODE → CASE WHEN (phải trước function mappings để tránh match nhầm)
        result = transformDecode(result);

        // 3. Function mappings (theo thứ tự trong LinkedHashMap)
        result = applyFunctionMappings(result);

        // 4. Sequence calls
        result = transformSequenceCalls(result);

        // 5. Trigger pseudo-columns
        result = transformTriggerPseudoColumns(result);

        return result;
    }

    /**
     * Transform chỉ trigger body (áp dụng thêm trigger-specific rules).
     */
    public String transformTriggerBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String result = transform(body);
        // Trigger: bỏ dấu : trước NEW./OLD. (đã xử lý ở trên)
        return result;
    }

    /**
     * Transform chỉ DECODE statement phức tạp (nhiều WHEN branches).
     * Oracle: DECODE(val, v1, r1, v2, r2, default)
     * PG: CASE WHEN val = v1 THEN r1 WHEN val = v2 THEN r2 ELSE default END
     */
    public String transformDecode(String decodeExpr) {
        if (decodeExpr == null || !decodeExpr.toUpperCase().contains("DECODE")) {
            return decodeExpr;
        }

        // Tìm và thay từng DECODE
        Pattern decodePattern = Pattern.compile(
                "(?i)DECODE\\s*\\(\\s*(.+?)\\s*\\)",
                Pattern.DOTALL
        );
        Matcher m = decodePattern.matcher(decodeExpr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1).trim();
            String transformed = decodeToCase(inner);
            m.appendReplacement(sb, Matcher.quoteReplacement(transformed));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Chuyển phần inner của DECODE thành CASE WHEN.
     * inner format: expr, val1, res1 [, val2, res2 ...] [, default]
     */
    private String decodeToCase(String inner) {
        String[] parts = splitDecodeParams(inner);
        if (parts.length < 3) {
            return inner; // không phải DECODE hợp lệ
        }

        String expr = parts[0].trim();
        StringBuilder caseExpr = new StringBuilder("CASE");
        boolean hasDefault = (parts.length % 2) == 1; // lẻ = có default (expr, val, res, default)

        for (int i = 1; i < parts.length; i += 2) {
            String val = parts[i].trim();
            String res = (i + 1 < parts.length) ? parts[i + 1].trim() : parts[parts.length - 1].trim();
            // Nếu là phần tử cuối và là default (số lẻ phần tử)
            if (i + 1 >= parts.length || (hasDefault && i + 1 == parts.length - 1)) {
                if (hasDefault && i + 1 == parts.length - 1) {
                    caseExpr.append(" ELSE ").append(res);
                }
                break;
            }
            caseExpr.append(" WHEN ").append(expr).append(" = ").append(val)
                    .append(" THEN ").append(res);
        }

        if (hasDefault) {
            // Đã thêm ELSE ở trên
        } else {
            // Không có default → thêm ELSE NULL
            caseExpr.append(" ELSE NULL");
        }
        caseExpr.append(" END");
        return caseExpr.toString();
    }

    /**
     * Tách parameters của DECODE (phức tạp vì có thể có nested parentheses).
     */
    private String[] splitDecodeParams(String inner) {
        // Đếm số parentheses để tách đúng
        int depth = 0;
        StringBuilder current = new StringBuilder();
        java.util.List<String> parts = new java.util.ArrayList<>();

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == ')') {
                depth += (c == '(') ? 1 : -1;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        return parts.toArray(new String[parts.size()]);
    }

    // ─────────────────────────────────────────────────────────────────
    // PIPELINE STEPS
    // ─────────────────────────────────────────────────────────────────

    private String normalizeOperators(String text) {
        // Bỏ khoảng trắng trong operators
        // =  >  → =>    (với khoảng trắng 2 bên)
        text = text.replaceAll("\\s*=>\\s*", " => ");
        text = text.replaceAll("\\s*!=\\s*", " != ");
        text = text.replaceAll("\\s*<>\\s*", " <> ");
        text = text.replaceAll("\\s*:=\\s*", " := ");
        return text;
    }

    private String applyFunctionMappings(String text) {
        for (Map.Entry<Pattern, String> entry : functionMappings.entrySet()) {
            Pattern pattern = entry.getKey();
            String replacement = entry.getValue();
            Matcher m = pattern.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String replacementStr = replacement;
                // Đánh số các capture groups ($1, $2, v.v.)
                for (int i = 1; i <= m.groupCount(); i++) {
                    String group = m.group(i);
                    if (group != null) {
                        replacementStr = replacementStr.replace("$" + i, Matcher.quoteReplacement(group));
                    }
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacementStr));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private String transformSequenceCalls(String text) {
        // seq.NEXTVAL → nextval('seq')
        Matcher m1 = SEQ_NEXTVAL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m1.find()) {
            String seqName = m1.group(1).toLowerCase();
            m1.appendReplacement(sb, "nextval('" + seqName + "')");
        }
        m1.appendTail(sb);
        text = sb.toString();

        // seq.CURRVAL → currval('seq')
        Matcher m2 = SEQ_CURRVAL.matcher(text);
        sb = new StringBuffer();
        while (m2.find()) {
            String seqName = m2.group(1).toLowerCase();
            m2.appendReplacement(sb, "currval('" + seqName + "')");
        }
        m2.appendTail(sb);
        return sb.toString();
    }

    private String transformTriggerPseudoColumns(String text) {
        // :NEW.col → NEW.col
        text = TRIGGER_NEW.matcher(text).replaceAll("NEW.$1");
        // :OLD.col → OLD.col
        text = TRIGGER_OLD.matcher(text).replaceAll("OLD.$1");
        // INSERTING → TG_OP = 'INSERT'
        text = INSERTING.matcher(text).replaceAll("(TG_OP = 'INSERT')");
        // UPDATING → TG_OP = 'UPDATE'
        text = UPDATING.matcher(text).replaceAll("(TG_OP = 'UPDATE')");
        // DELETING → TG_OP = 'DELETE'
        text = DELETING.matcher(text).replaceAll("(TG_OP = 'DELETE')");
        return text;
    }

    // ─────────────────────────────────────────────────────────────────
    // UNSUPPORTED FEATURES DETECTION
    // ─────────────────────────────────────────────────────────────────

    /**
     * Kiểm tra text có chứa các Oracle feature không tương thích.
     * Dùng để cảnh báo người dùng trước khi migrate.
     */
    public static java.util.List<String> detectUnsupportedFeatures(String text) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (text == null) return warnings;

        if (Pattern.compile("(?i)\\bGOTO\\b").matcher(text).find()) {
            warnings.add("GOTO — PostgreSQL không hỗ trợ GOTO");
        }
        if (Pattern.compile("(?i)\\bPRAGMA\\s+AUTONOMOUS_TRANSACTION\\b").matcher(text).find()) {
            warnings.add("PRAGMA AUTONOMOUS_TRANSACTION — PostgreSQL không hỗ trợ trực tiếp");
        }
        if (Pattern.compile("(?i)\\bBULK\\s+COLLECT\\b").matcher(text).find()) {
            warnings.add("BULK COLLECT — cần viết lại bằng array hoặc FOR loop");
        }
        if (Pattern.compile("(?i)\\bFORALL\\b").matcher(text).find()) {
            warnings.add("FORALL — cần viết lại bằng unnest() hoặc loop");
        }
        if (Pattern.compile("(?i)\\bCONNECT\\s+BY\\b").matcher(text).find()) {
            warnings.add("CONNECT BY — cần viết lại bằng WITH RECURSIVE");
        }
        if (Pattern.compile("(?i)\\bPACKAGE\\b").matcher(text).find()) {
            warnings.add("PACKAGE — Oracle-only, cần tách thành function/procedure riêng");
        }
        if (Pattern.compile("(?i)\\bTYPE\\s+BODY\\b").matcher(text).find()) {
            warnings.add("TYPE BODY — Oracle-only, cần viết lại bằng CREATE TYPE trong PostgreSQL");
        }
        if (Pattern.compile("(?i)\\bREF\\s+CURSOR\\b").matcher(text).find()) {
            warnings.add("REF CURSOR — PostgreSQL dùng REFCURSOR hoặc TABLE OF");
        }
        if (Pattern.compile("(?i)\\bXMLTYPE\\b").matcher(text).find()) {
            warnings.add("XMLTYPE — cần dùng XML type của PostgreSQL hoặc extension");
        }
        if (Pattern.compile("(?i)\\bSDO_GEOMETRY\\b").matcher(text).find()) {
            warnings.add("SDO_GEOMETRY — cần PostGIS extension và chuyển đổi geometry");
        }

        return warnings;
    }
}
