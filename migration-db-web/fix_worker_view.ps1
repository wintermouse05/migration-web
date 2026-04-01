$path = 'c:\Users\dongdd\Documents\Development\migration-web\migration-db-web\src\main\java\org\example\migrationdbweb\migratetool\MigrationWorker.java'
$content = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)

$oldMethod = @'
    /**
     * Transform view body  Eschema replacement thong minh + Oracle EPG transformation.
     */
    private String transformViewBodySwing(
            String clause,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            OracleToPgsqlTransformer transformer
    ) {
        if (clause == null) return clause;
        String result = clause;

        // 1. Schema replacement thong minh
        if (sourceSchema != null && targetSchema != null
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {
            // Unquoted: SCOTT.DEPT EPUBLIC.DEPT
            result = result.replaceAll(
                    "(?<![a-zA-Z0-9_'\"])" + java.util.regex.Pattern.quote(sourceSchema) + "\\.([a-zA-Z_][a-zA-Z0-9_]*)",
                    targetSchema + ".$1"
            );
            // Quoted: "SCOTT"."DEPT" E"PUBLIC"."DEPT"
            result = result.replaceAll(
                    "\"\\s*" + java.util.regex.Pattern.quote(sourceSchema) + "\\s*\"\\s*\\.",
                    "\"" + targetSchema + "\"."
            );
        }

        // 2. Oracle EPG transformation
        if (transformer != null) {
            result = transformer.transform(result);
        }

        return result;
    }
'@

$newMethod = @'
    /**
     * Transform view body -- schema replacement + Oracle to PostgreSQL transformation.
     */
    private String transformViewBodySwing(
            String clause,
            String sourceSchema,
            String targetSchema,
            DatabaseType sourceDbType,
            OracleToPgsqlTransformer transformer
    ) {
        if (clause == null) return clause;
        String result = clause;

        // 1. Schema replacement -- an toan cho Oracle to PostgreSQL
        //    PostgreSQL dung lowercase identifiers, Oracle dung UPPERCASE (quoted hoac khong)
        if (sourceSchema != null && targetSchema != null
                && !sourceSchema.equalsIgnoreCase(targetSchema)) {

            // a) Quoted identifier: "SCOTT"."PRODUCTS" -> "public"."products"
            result = result.replaceAll(
                    "\"\\s*" + java.util.regex.Pattern.quote(sourceSchema) + "\\s*\"\\s*\\.\"",
                    "\"" + targetSchema.toLowerCase() + "\".\""
            );

            // b) Unquoted identifier: SCOTT.PRODUCTS -> public.products
            result = result.replaceAll(
                    "(?<![a-zA-Z0-9_'\"])" + java.util.regex.Pattern.quote(sourceSchema) + "\\.([a-zA-Z_][a-zA-Z0-9_]*)",
                    targetSchema.toLowerCase() + ".$1"
            );

            // c) Lowercase quoted uppercase identifiers after schema
            //    "public"."PRODUCTS" -> "public"."products"
            result = result.replaceAll(
                    "\"\\s*" + java.util.regex.Pattern.quote(targetSchema.toLowerCase()) + "\\s*\"\\s*\\.\\s*\"([^\"]+)\"\\s*",
                    "\"" + targetSchema.toLowerCase() + "\".\"`$1\""
            );
        }

        // 2. Oracle to PostgreSQL transformation
        if (transformer != null) {
            result = transformer.transform(result);
        }

        return result;
    }
'@

if ($content.Contains($oldMethod)) {
    $content = $content.Replace($oldMethod, $newMethod)
    [IO.File]::WriteAllText($path, $content, [Text.Encoding]::UTF8)
    Write-Host "Replaced successfully"
} else {
    Write-Host "Old method NOT found. Searching for partial match..."
    if ($content.Contains('transformViewBodySwing')) {
        Write-Host "Method exists but content differs"
    } else {
        Write-Host "Method not found at all"
    }
}
