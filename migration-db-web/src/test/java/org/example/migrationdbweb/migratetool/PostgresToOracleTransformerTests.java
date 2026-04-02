package org.example.migrationdbweb.migratetool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PostgresToOracleTransformerTests {

    private final PostgresToOracleTransformer transformer = new PostgresToOracleTransformer();

    @Test
    void shouldTransformSequenceAndLimitOffset() {
        String input = "select nextval('order_seq') as id from orders limit 10 offset 20";
        String output = transformer.transform(input);

        assertTrue(output.contains("order_seq.NEXTVAL"));
        assertTrue(output.contains("OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY"));
    }

    @Test
    void shouldTransformJsonOperators() {
        String input = "select payload->>'customerId', payload->'meta' from event_log";
        String output = transformer.transform(input);

        assertTrue(output.contains("JSON_VALUE(payload, '$.customerId')"));
        assertTrue(output.contains("JSON_QUERY(payload, '$.meta')"));
    }

    @Test
    void shouldTransformRegexOperators() {
        String input = "select 1 from users where email ~* '^[a-z]+' and code !~ '^[0-9]+$'";
        String output = transformer.transform(input);

        assertTrue(output.contains("REGEXP_LIKE(email, '^[a-z]+', 'i')"));
        assertTrue(output.contains("NOT REGEXP_LIKE(code, '^[0-9]+$')"));
    }

    @Test
    void shouldTransformTriggerBodyFromPgToOracle() {
        String input = "BEGIN\n  NEW.updated_at := now();\n  RETURN NEW;\nEND;";
        String output = transformer.transformTriggerBody(input);

        assertTrue(output.contains(":NEW.updated_at := SYSTIMESTAMP"));
        assertFalse(output.toUpperCase().contains("RETURN NEW"));
    }

    @Test
    void shouldWarnOnUnsupportedFeatures() {
        String input = "insert into t(id) values (1) on conflict(id) do nothing returning id";
        List<String> warnings = PostgresToOracleTransformer.detectUnsupportedFeatures(input);

        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ON CONFLICT")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("RETURNING")));
    }
}
