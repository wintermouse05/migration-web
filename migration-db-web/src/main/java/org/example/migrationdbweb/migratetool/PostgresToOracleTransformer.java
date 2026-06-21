package org.example.migrationdbweb.migratetool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL -> Oracle SQL/PL block transformer.
 *
 * The intent is pragmatic conversion for migration-generated DDL bodies
 * (views, trigger function bodies, routine bodies), not full SQL parsing.
 */
public class PostgresToOracleTransformer {

    private static final Pattern SEQ_NEXTVAL = Pattern.compile("(?i)nextval\\s*\\(\\s*'([\\w$.]+)'\\s*\\)");
    private static final Pattern SEQ_CURRVAL = Pattern.compile("(?i)currval\\s*\\(\\s*'([\\w$.]+)'\\s*\\)");
    private static final Pattern LIMIT_OFFSET = Pattern.compile("(?is)\\s+LIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)\\b");
    private static final Pattern OFFSET_LIMIT = Pattern.compile("(?is)\\s+OFFSET\\s+(\\d+)\\s+LIMIT\\s+(\\d+)\\b");

    private final Map<Pattern, String> mappings = new LinkedHashMap<>();

    public PostgresToOracleTransformer() {
        // Timestamps
        register("(?i)\\bclock_timestamp\\s*\\(\\s*\\)", "SYSTIMESTAMP");
        register("(?i)\\bstatement_timestamp\\s*\\(\\s*\\)", "SYSTIMESTAMP");
        register("(?i)\\bnow\\s*\\(\\s*\\)", "SYSTIMESTAMP");
        register("(?i)\\bcurrent_timestamp\\b", "SYSTIMESTAMP");
        register("(?i)\\blocaltimestamp\\b", "SYSTIMESTAMP");
        register("(?i)\\bcurrent_date\\b", "TRUNC(SYSDATE)");
        register("(?i)\\bcurrent_time\\b", "TO_CHAR(SYSTIMESTAMP, 'HH24:MI:SS')");

        // Date truncation
        register("(?i)\\bdate_trunc\\s*\\(\\s*'day'\\s*,\\s*([^)]+)\\)", "TRUNC($1)");
        register("(?i)\\bdate_trunc\\s*\\(\\s*'month'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'MM')");
        register("(?i)\\bdate_trunc\\s*\\(\\s*'year'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'YYYY')");
        register("(?i)\\bdate_trunc\\s*\\(\\s*'hour'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'HH24')");
        register("(?i)\\bdate_trunc\\s*\\(\\s*'minute'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'MI')");

        // Date arithmetic helpers
        register("(?i)\\bmake_interval\\s*\\(\\s*days\\s*=>\\s*(\\d+)\\s*\\)", "NUMTODSINTERVAL($1, 'DAY')");
        register("(?i)\\bmake_interval\\s*\\(\\s*hours\\s*=>\\s*(\\d+)\\s*\\)", "NUMTODSINTERVAL($1, 'HOUR')");
        register("(?i)\\bmake_interval\\s*\\(\\s*mins\\s*=>\\s*(\\d+)\\s*\\)", "NUMTODSINTERVAL($1, 'MINUTE')");
        register("(?i)\\bmake_interval\\s*\\(\\s*secs\\s*=>\\s*(\\d+)\\s*\\)", "NUMTODSINTERVAL($1, 'SECOND')");

        // Common function/operator rewrites
        register("(?i)\\bcoalesce\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\)", "NVL($1, $2)");
        register("(?i)\\bnullif\\s*\\(", "NULLIF(");
        register("(?i)\\bposition\\s*\\(\\s*([^\\s].*?)\\s+in\\s+([^)]+)\\)", "INSTR($2, $1)");
        register("(?i)\\bsubstring\\s*\\(\\s*([^\\s].*?)\\s+from\\s+([^\\s].*?)\\s+for\\s+([^)]+)\\)", "SUBSTR($1, $2, $3)");
        register("(?i)\\bsubstring\\s*\\(\\s*([^\\s].*?)\\s+from\\s+([^)]+)\\)", "SUBSTR($1, $2)");
        register("(?i)\\bbtrim\\s*\\(", "TRIM(");
        register("(?i)\\bchar_length\\s*\\(", "LENGTH(");
        register("(?i)\\boctet_length\\s*\\(", "LENGTHB(");
        register("(?i)\\bstring_agg\\s*\\(\\s*([^,]+)\\s*,\\s*'([^']*)'\\s*\\)", "LISTAGG($1, '$2') WITHIN GROUP (ORDER BY $1)");
        register("(?i)\\bto_hex\\s*\\(\\s*([^)]+)\\s*\\)", "RAWTOHEX($1)");
        register("(?i)\\bmd5\\s*\\(\\s*([^)]+)\\s*\\)", "STANDARD_HASH($1, 'MD5')");
        register("(?i)\\bgen_random_uuid\\s*\\(\\s*\\)", "SYS_GUID()");
        register("(?i)\\buuid_generate_v4\\s*\\(\\s*\\)", "SYS_GUID()");
        register("(?i)\\bregexp_replace\\s*\\(", "REGEXP_REPLACE(");
        register("(?i)\\bregexp_substr\\s*\\(", "REGEXP_SUBSTR(");
        register("(?i)\\bregexp_count\\s*\\(", "REGEXP_COUNT(");
        register("(?i)\\bregexp_instr\\s*\\(", "REGEXP_INSTR(");
        register("(?i)\\bexcept\\b", "MINUS");
        register("(?i)\\bilike\\b", "LIKE");
        register("(?i)\\btrue\\b", "1");
        register("(?i)\\bfalse\\b", "0");

        // Session context
        register("(?i)\\bcurrent_user\\b", "USER");
        register("(?i)\\bcurrent_schema\\s*\\(\\s*\\)", "SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')");
        register("(?i)\\bsession_user\\b", "USER");
        register("(?i)\\binet_client_addr\\s*\\(\\s*\\)", "SYS_CONTEXT('USERENV', 'IP_ADDRESS')");
        register("(?i)\\binet_server_addr\\s*\\(\\s*\\)", "SYS_CONTEXT('USERENV', 'HOST')");
        register("(?i)\\brandom\\s*\\(\\s*\\)", "DBMS_RANDOM.VALUE");

        // Trigger-specific conveniences
        register("(?i)\\bTG_OP\\s*=\\s*'INSERT'", "INSERTING");
        register("(?i)\\bTG_OP\\s*=\\s*'UPDATE'", "UPDATING");
        register("(?i)\\bTG_OP\\s*=\\s*'DELETE'", "DELETING");
    }

    public String transform(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = input;
        result = stripPgDollarQuoting(result);
        result = normalizePgCastOperator(result);
        result = transformJsonOperators(result);
        result = transformRegexOperators(result);
        result = transformExtract(result);
        result = applyMappings(result);
        result = transformSequenceCalls(result);
        result = transformLimitClause(result);
        result = normalizePlpgsqlArtifacts(result);
        return result;
    }

    public String transformTriggerBody(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = transform(input);

        // PostgreSQL trigger function pseudo columns -> Oracle trigger pseudo columns
        result = result.replaceAll("(?i)\\bNEW\\.", ":NEW.");
        result = result.replaceAll("(?i)\\bOLD\\.", ":OLD.");

        // PostgreSQL trigger function boilerplate is invalid in Oracle trigger body
        result = result.replaceAll("(?im)^\\s*RETURN\\s+NEW\\s*;?\\s*$", "");
        result = result.replaceAll("(?im)^\\s*RETURN\\s+OLD\\s*;?\\s*$", "");
        result = result.replaceAll("(?im)^\\s*RETURN\\s+NULL\\s*;?\\s*$", "");

        // PL/pgSQL PERFORM has no Oracle equivalent in this context.
        result = result.replaceAll("(?im)^\\s*PERFORM\\s+", "");
        result = result.replaceAll("(?im)^\\s*ELSIF\\b", "ELSE IF");
        result = result.replaceAll("(?im)\\bEND\\s+IF\\s*;", "END IF;");

        return result.trim();
    }

    public static java.util.List<String> detectUnsupportedFeatures(String text) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return warnings;
        }

        if (Pattern.compile("(?i)\\bON\\s+CONFLICT\\b").matcher(text).find()) {
            warnings.add("ON CONFLICT - Oracle khong ho tro truc tiep, can MERGE hoac xu ly duplicate thu cong");
        }
        if (Pattern.compile("(?i)\\bRETURNING\\b").matcher(text).find()) {
            warnings.add("RETURNING - Oracle cu phap khac, can review thu cong");
        }
        if (Pattern.compile("(?i)\\bFILTER\\s*\\(").matcher(text).find()) {
            warnings.add("FILTER (...) aggregate syntax - Oracle can rewrite by CASE WHEN");
        }
        if (Pattern.compile("(?i)\\bDISTINCT\\s+ON\\b").matcher(text).find()) {
            warnings.add("DISTINCT ON - Oracle khong ho tro truc tiep, can rewrite bang analytic function");
        }
        if (Pattern.compile("(?i)::").matcher(text).find()) {
            warnings.add("PostgreSQL cast operator :: - da co rewrite co ban, nhung nen kiem tra lai");
        }
        if (Pattern.compile("(?i)\\bJSONB\\b|\\b->>|\\b->\\b").matcher(text).find()) {
            warnings.add("JSONB operators - Oracle co cu phap JSON khac, can review thu cong");
        }
        if (Pattern.compile("(?i)\\bWITH\\s+RECURSIVE\\b").matcher(text).find()) {
            warnings.add("WITH RECURSIVE - Oracle ho tro de quy bang cu phap khac trong mot so truong hop");
        }
        if (Pattern.compile("(?i)\\bUNNEST\\s*\\(").matcher(text).find()) {
            warnings.add("UNNEST - Oracle can rewrite with TABLE() over collection types");
        }
        return warnings;
    }

    private void register(String regex, String replacement) {
        mappings.put(Pattern.compile(regex), replacement);
    }

    private String applyMappings(String text) {
        for (Map.Entry<Pattern, String> entry : mappings.entrySet()) {
            Matcher matcher = entry.getKey().matcher(text);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String replacement = entry.getValue();
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String group = matcher.group(i);
                    if (group != null) {
                        replacement = replacement.replace("$" + i, Matcher.quoteReplacement(group));
                    }
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private static String transformSequenceCalls(String text) {
        Matcher nextval = SEQ_NEXTVAL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (nextval.find()) {
            nextval.appendReplacement(sb, nextval.group(1) + ".NEXTVAL");
        }
        nextval.appendTail(sb);

        Matcher currval = SEQ_CURRVAL.matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (currval.find()) {
            currval.appendReplacement(sb2, currval.group(1) + ".CURRVAL");
        }
        currval.appendTail(sb2);

        return sb2.toString();
    }

    private static String normalizePgCastOperator(String text) {
        // Simple type casts used commonly in generated SQL.
        text = text.replaceAll("(?i)::\\s*text\\b", "");
        text = text.replaceAll("(?i)::\\s*int(?:eger)?\\b", "");
        text = text.replaceAll("(?i)::\\s*bigint\\b", "");
        text = text.replaceAll("(?i)::\\s*numeric\\b", "");
        text = text.replaceAll("(?i)::\\s*timestamp\\b", "");
        text = text.replaceAll("(?i)::\\s*timestamptz\\b", "");
        text = text.replaceAll("(?i)::\\s*date\\b", "");
        text = text.replaceAll("(?i)::\\s*boolean\\b", "");
        text = text.replaceAll("(?i)::\\s*jsonb?\\b", "");
        return text;
    }

    private static String transformLimitClause(String text) {
        // Oracle 12c+: OFFSET ... FETCH NEXT ...
        Matcher m = LIMIT_OFFSET.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, " OFFSET " + m.group(2) + " ROWS FETCH NEXT " + m.group(1) + " ROWS ONLY");
        }
        m.appendTail(sb);

        Matcher m2 = OFFSET_LIMIT.matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            m2.appendReplacement(sb2, " OFFSET " + m2.group(1) + " ROWS FETCH NEXT " + m2.group(2) + " ROWS ONLY");
        }
        m2.appendTail(sb2);

        return sb2.toString().replaceAll("(?is)\\s+LIMIT\\s+(\\d+)\\b", " FETCH FIRST $1 ROWS ONLY");
    }

    private static String transformJsonOperators(String text) {
        // col->>'key'  => JSON_VALUE(col, '$.key')
        text = text.replaceAll("(?i)([a-zA-Z_][a-zA-Z0-9_.]*)\\s*->>\\s*'([a-zA-Z0-9_]+)'",
            "JSON_VALUE($1, '\\$.$2')");
        // col->'key' => JSON_QUERY(col, '$.key')
        text = text.replaceAll("(?i)([a-zA-Z_][a-zA-Z0-9_.]*)\\s*->\\s*'([a-zA-Z0-9_]+)'",
            "JSON_QUERY($1, '\\$.$2')");
        return text;
    }

    private static String transformRegexOperators(String text) {
        // expr ~* 'pat' => REGEXP_LIKE(expr, 'pat', 'i')
        text = text.replaceAll("(?i)([a-zA-Z_][a-zA-Z0-9_\\.\\)\\(]*)\\s*~\\*\\s*'([^']*)'",
                "REGEXP_LIKE($1, '$2', 'i')");
        // expr ~ 'pat' => REGEXP_LIKE(expr, 'pat')
        text = text.replaceAll("(?i)([a-zA-Z_][a-zA-Z0-9_\\.\\)\\(]*)\\s*~\\s*'([^']*)'",
                "REGEXP_LIKE($1, '$2')");
        // expr !~* 'pat' => NOT REGEXP_LIKE(..., 'i')
        text = text.replaceAll("(?i)([a-zA-Z_][a-zA-Z0-9_\\.\\)\\(]*)\\s*!~\\*\\s*'([^']*)'",
                "NOT REGEXP_LIKE($1, '$2', 'i')");
        // expr !~ 'pat' => NOT REGEXP_LIKE(...)
        text = text.replaceAll("(?i)([a-zA-Z_][a-zA-Z0-9_\\.\\)\\(]*)\\s*!~\\s*'([^']*)'",
                "NOT REGEXP_LIKE($1, '$2')");
        return text;
    }

    private static String transformExtract(String text) {
        // EXTRACT(EPOCH FROM ts) => ((CAST(ts AS DATE) - DATE '1970-01-01') * 86400)
        text = text.replaceAll("(?i)EXTRACT\\s*\\(\\s*EPOCH\\s+FROM\\s+([^)]+)\\)",
                "((CAST($1 AS DATE) - DATE '1970-01-01') * 86400)");
        return text;
    }

    private static String normalizePlpgsqlArtifacts(String text) {
        text = text.replaceAll("(?im)^\\s*LANGUAGE\\s+plpgsql\\s*;?\\s*$", "");
        text = text.replaceAll("(?im)^\\s*VOLATILE\\s*;?\\s*$", "");
        text = text.replaceAll("(?im)^\\s*STABLE\\s*;?\\s*$", "");
        text = text.replaceAll("(?im)^\\s*IMMUTABLE\\s*;?\\s*$", "");
        return text;
    }

    private static String stripPgDollarQuoting(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length() >= 4) {
            return trimmed.substring(2, trimmed.length() - 2).trim();
        }

        // Tagged dollar quote: $tag$...$tag$
        if (trimmed.startsWith("$") && trimmed.length() > 2) {
            int second = trimmed.indexOf('$', 1);
            if (second > 1) {
                String tag = trimmed.substring(1, second);
                String closing = "$" + tag + "$";
                if (trimmed.endsWith(closing) && trimmed.length() > (second + 1 + closing.length())) {
                    return trimmed.substring(second + 1, trimmed.length() - closing.length()).trim();
                }
            }
        }

        return text;
    }
}