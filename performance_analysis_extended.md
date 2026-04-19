# 🔍 Extended Performance Analysis: Beyond N+1 & I/O

> **Phạm vi**: Tất cả các bottleneck **ngoài** N+1 và I/O đã phân tích trước đó
> **Focus**: Threading, CPU, Memory, Redundant Work, Ordering, Swing overhead

---

## 🔴 Vấn Đề Nghiêm Trọng

### 1. Regex Compilation Storm — OracleToPgsqlTransformer

**File**: [OracleToPgsqlTransformer.java:33-576](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/OracleToPgsqlTransformer.java#L33-L576)

Mỗi lần tạo instance `OracleToPgsqlTransformer`, **~150+ regex Pattern** được compile trong instance initializer block:

```java
private final Map<Pattern, String> functionMappings = new LinkedHashMap<>();
{
    // ~150 calls to register() — mỗi call compile 1 Pattern
    register("(?i)\\bSYSTIMESTAMP\\b", "statement_timestamp()");
    register("(?i)\\bSYSDATE\\b(?!\\s*\\+)", "clock_timestamp()");
    // ... 148 lần nữa
}

private void register(String regex, String replacement) {
    functionMappings.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement);
}
```

**Code tạo instance mới nhiều lần**:

- [MigrationWorker.java:1871-1872](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L1871-L1872): `new OracleToPgsqlTransformer()` trong `runCreateFunctionsPhase`
- [MigrationWorker.java:1978-1979](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L1978-L1979): `new OracleToPgsqlTransformer()` trong `runCreateTriggersPhase`
- [MigrationWorker.java:2222-2224](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L2222-L2224): `new OracleToPgsqlTransformer()` trong `runCreateViewsPhase`
- [OracleDialect.java:287](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/OracleDialect.java#L287): `new PostgresToOracleTransformer()` trong `buildCreateTriggerSql`
- [OracleDialect.java:395](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/OracleDialect.java#L395): `new PostgresToOracleTransformer()` trong `buildCreateViewSql`
- [OracleDialect.java:487](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/OracleDialect.java#L487): `new PostgresToOracleTransformer()` trong `buildCreateFunctionSql`

> **Impact**: Mỗi instance = **~150 × Pattern.compile()** = ~5-15ms. Với 50 views + 30 triggers + 20 functions, tạo instance trong loop → **hàng trăm ms** wasted trên regex compilation.

> [!CAUTION]
> `PostgresToOracleTransformer` được tạo **TRONG vòng lặp** (per trigger/view/function) trong `OracleDialect` — dòng 287, 395, 487. Mỗi trigger/view/function gọi `new PostgresToOracleTransformer()` riêng!

**Solution**: Singleton hoặc `static final` precompiled patterns
```java
public class OracleToPgsqlTransformer {
    // Compile 1 lần duy nhất khi class load
    private static final Map<Pattern, String> FUNCTION_MAPPINGS = buildFunctionMappings();
    
    private static Map<Pattern, String> buildFunctionMappings() {
        Map<Pattern, String> map = new LinkedHashMap<>();
        map.put(Pattern.compile("(?i)\\bSYSTIMESTAMP\\b"), "statement_timestamp()");
        // ...
        return Collections.unmodifiableMap(map);
    }
}
```

Tương tự, `PostgresToOracleTransformer` trong `OracleDialect` nên được tạo 1 lần rồi truyền vào:
```java
// BEFORE (trong vòng lặp per trigger):
PostgresToOracleTransformer transformer = new PostgresToOracleTransformer(); // 150 regex mỗi lần!

// AFTER:  
private static final PostgresToOracleTransformer PG2ORA_TRANSFORMER = new PostgresToOracleTransformer();
```

---

### 2. Wildcard Pattern Recompilation Per Table

**File**: [MigrationWorker.java:815-841](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L815-L841)

```java
private boolean matchesWildcardPattern(String normalizedObjectName, String rawPattern) {
    // ... 
    // COMPILE regex MỖI LẦN GỌI cho MỖI bảng × MỖI pattern
    return java.util.regex.Pattern.compile(regex.toString()).matcher(normalizedObjectName).matches();
}
```

Hàm `matchesWildcardPattern` được gọi bởi:
- `applyTableFilters()` → gọi cho mỗi bảng × mỗi include pattern + mỗi exclude pattern
- `applyViewFilters()` → tương tự cho views

100 bảng × 5 include patterns = **500 lần Pattern.compile()**.

**Solution**: Cache compiled patterns
```java
// Cache patterns khi parse filter lần đầu
private final Map<String, Pattern> compiledPatternCache = new HashMap<>();

private Pattern getOrCompilePattern(String rawPattern) {
    return compiledPatternCache.computeIfAbsent(rawPattern, p -> {
        StringBuilder regex = new StringBuilder("^");
        // ... build regex
        return Pattern.compile(regex.toString());
    });
}
```

---

### 3. ExecutorService Tạo Mới Mỗi FK Level — Thread Pool Thrashing

**File**: [MigrationWorker.java:925-1042](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L925-L1042)

```java
for (Map.Entry<Integer, List<TableMigrationPlan>> levelEntry : plansByLevel.entrySet()) {
    // MỖI FK level tạo MỘT ExecutorService MỚI rồi shutdown
    ExecutorService levelExecutor = Executors.newFixedThreadPool(levelThreadCount);
    // ... submit tasks ...
    levelExecutor.shutdownNow();
    levelExecutor.awaitTermination(10, TimeUnit.SECONDS);
}
```

> **Problem**: 5 FK levels → tạo và hủy 5 thread pools. Mỗi lần tạo pool = tạo N threads mới (OS-level), chi phí ~1-5ms/thread. Ngoài ra, threads cũ bị kill, mất JIT warm-up.

**Solution**: Reuse 1 ExecutorService cho toàn bộ data phase
```java
ExecutorService sharedExecutor = Executors.newFixedThreadPool(threadCount);
try {
    for (var levelEntry : plansByLevel.entrySet()) {
        // Submit vào cùng 1 pool, chờ level hoàn tất rồi mới tiếp
        List<Future<TableTransferOutcome>> futures = new ArrayList<>();
        for (TableMigrationPlan plan : levelEntry.getValue()) {
            futures.add(sharedExecutor.submit(() -> { /* ... */ }));
        }
        for (Future<TableTransferOutcome> f : futures) {
            f.get(); // chờ level hoàn tất
        }
    }
} finally {
    sharedExecutor.shutdown();
}
```

---

### 4. FK-Level Serialization Gây Thread Starvation

**File**: [MigrationWorker.java:925-1047](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L925-L1047)

FK-level migration hiện tại chạy **hoàn toàn tuần tự giữa các level**: Level 0 phải hoàn tất 100% trước khi Level 1 bắt đầu.

```
Timeline hiện tại:
Level 0: [====Table_A====][==Table_B==][========Table_C========]
Level 1:                                                        [==Table_D==][=Table_E=]
Level 2:                                                                                [=Table_F=]
```

> **Problem**: Nếu Level 0 có 1 bảng rất lớn (Table_C — 10M rows), tất cả Level 1 tables phải chờ dù chúng không phụ thuộc vào Table_C.

**Solution**: Dependency-aware pipeline — bảng Level 1 có thể bắt đầu ngay khi **parent tables** của nó hoàn tất, không cần chờ toàn bộ Level 0:

```java
// Xây dựng dependency graph: table → set of parent tables
Map<String, Set<String>> parentDependencies = buildParentDependencyMap(runnablePlans);
Set<String> completedTables = ConcurrentHashMap.newKeySet();

// Submit ngay khi dependencies thỏa mãn
CompletionService<TableTransferOutcome> completionService = new ExecutorCompletionService<>(executor);
// ... logic: khi 1 table hoàn tất, check xem children nào đã sẵn sàng → submit
```

```
Timeline tối ưu:
Level 0: [====Table_A====][==Table_B==][========Table_C========]
Level 1:                   [==Table_D==]            [=Table_E=]  ← bắt đầu sớm hơn!
Level 2:                                [=Table_F=]              ← sớm hơn nữa!
```

---

## 🟡 Vấn Đề Trung Bình

### 5. Single Connection cho Row Counting — Sequential Bottleneck

**File**: [MigrationWorker.java:1072-1103](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L1072-L1103)

```java
private List<TableMigrationPlan> buildTableMigrationPlans(...) throws SQLException {
    try (Connection sourceConn = manager.getConnection(sourcePoolId)) {
        for (int i = 0; i < allTables.size(); i++) {
            // TUẦN TỰ: SELECT COUNT(*) cho từng bảng, trên 1 connection
            long rowCount = countRowsForTable(sourceConn, table, sourceDialect);
        }
    }
}
```

> **Problem**: 100 bảng × `SELECT COUNT(*)` tuần tự. Oracle `SELECT COUNT(*)` trên bảng lớn có thể mất 1-10 giây / bảng. Tổng: **2-15 phút** chỉ cho row counting.

**Solutions**:

#### 5a: Song song hóa COUNT queries
```java
// Sử dụng pool connections để chạy COUNT(*) đồng thời
ExecutorService countExecutor = Executors.newFixedThreadPool(
    Math.min(4, manager.getPoolSize(sourcePoolId))
);
List<Future<TableMigrationPlan>> futures = new ArrayList<>();
for (TableDefinition table : allTables) {
    futures.add(countExecutor.submit(() -> {
        try (Connection conn = manager.getConnection(sourcePoolId)) {
            long count = countRowsForTable(conn, table, sourceDialect);
            return new TableMigrationPlan(table, count, ...);
        }
    }));
}
```

#### 5b: Sử dụng approximate count (Oracle)
```java
// Oracle: lấy row count từ statistics thay vì COUNT(*)
String approxCountSql = """
    SELECT NUM_ROWS FROM ALL_TABLES 
    WHERE OWNER = ? AND TABLE_NAME = ?
""";
// Nhanh hơn 1000x nhưng có thể không chính xác nếu stats outdated
// Kết hợp: dùng approximate cho sorting, exact cho progress tracking
```

---

### 6. `isOracleConnection()` / `isPostgreSqlConnection()` — Metadata Query Mỗi Lần Gọi

**File**: [MetadataExtractor.java:259-288](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MetadataExtractor.java#L259-L288)

```java
private static boolean isOracleConnection(Connection conn) {
    return conn.getMetaData().getDatabaseProductName().toLowerCase().contains("oracle");
    // ↑ conn.getMetaData() có thể tạo roundtrip hoặc ít nhất là overhead internal
}
```

Gọi **PER TABLE** trong `extractTableDefinition()` (dòng 57-61, 87, 91, 122, 124, 142):
- 6 lần `isXxxConnection()` × 100 bảng = **600 gọi `getMetaData()`**.

**Solution**: Detect DB type 1 lần, truyền vào parameter
```java
public TableDefinition extractTableDefinition(
    Connection conn, String schema, String tableName, 
    boolean includeStructuralMetadata,
    DatabaseType dbType  // detect 1 lần bên ngoài
) {
    if (dbType == DatabaseType.ORACLE) { /* ... */ }
}
```

---

### 7. Duplicate Topological Sort Computation

**File**: [MigrationWorker.java:273](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L273) và [MigrationWorker.java:1079](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L1079)

```java
// Lần 1: Sort toàn bộ tables theo FK dependency
allTables = TableDependencySortUtil.sortByForeignKeyDependency(allTables);  // L273

// Lần 2: Tính FK levels cho CÙNG tập tables
Map<TableDefinition, Integer> fkLevels = 
    TableDependencySortUtil.computeForeignKeyLevels(allTables);  // L1079
```

Cả `sortByForeignKeyDependency()` và `computeForeignKeyLevels()` ([TableDependencySortUtil.java](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/TableDependencySortUtil.java)) đều build cùng 1 dependency graph (adjacency list + in-degree) → **duplicate O(V+E) computation**.

**Solution**: Merge thành 1 method trả cả sorted list + levels
```java
public record SortResult(
    List<TableDefinition> sortedTables, 
    Map<TableDefinition, Integer> fkLevels
) {}

public static SortResult sortAndComputeLevels(List<TableDefinition> tables) {
    // Build graph 1 lần, BFS 1 lần, return cả hai
}
```

---

### 8. Memory Pressure từ `pendingBatchRows` ArrayList

**File**: [DataTransferService.java:307, 336-353](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/DataTransferService.java#L307)

```java
List<Object[]> pendingBatchRows = new ArrayList<>(safeBatchSize);  // L307

Object[] rowValues = new Object[columnCount];  // L336 — allocated PER ROW
for (int i = 1; i <= columnCount; i++) {
    Object value = rs.getObject(i);  // có thể là byte[], String lớn
    rowValues[i - 1] = value;
}
pendingBatchRows.add(rowValues);  // Giữ reference → GC không thể thu hồi
```

> **Problem**: `pendingBatchRows` lưu **toàn bộ dữ liệu batch** trong memory cho mục đích retry (`rebindBatchRows`). Với batchSize=5000, 20 columns, mỗi row chứa String/byte[] → **hàng chục MB per batch per thread**.

> [!WARNING]
> Với 4 threads chạy song song, mỗi thread giữ 1 batch → **~100-400 MB RAM** chỉ cho retry buffer. Nếu columns chứa BLOB/CLOB, có thể OOM.

**Solutions**:

#### 8a: Lazy retry — chỉ lưu row data khi cần retry
```java
// Đa số batch thành công → không cần retry
// Chỉ tạo pendingBatchRows khi retry policy enabled
boolean needRetryBuffer = retryPolicy.isRetryEnabled() && retryPolicy.resolveAttempts() > 1;
List<Object[]> pendingBatchRows = needRetryBuffer 
    ? new ArrayList<>(safeBatchSize)
    : Collections.emptyList();
```

#### 8b: Re-read from source khi retry thay vì buffer
```java
// Thay vì buffer data, re-query source từ checkpoint offset khi retry
// Yêu cầu: SELECT ... ORDER BY pk OFFSET {lastCommittedOffset}
```

---

### 9. Truncate Fallback Ordering — Multi-Pass DELETE

**File**: [MigrationWorker.java:695-757](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L695-L757)

Oracle truncate fallback uses iterative DELETE passes:

```java
while (!remaining.isEmpty()) {
    for (TableDefinition table : remaining) {
        try {
            statement.executeUpdate("DELETE FROM " + qualifiedTable);
        } catch (SQLException e) {
            if (isDeleteBlockedByChildRows(e)) {
                nextRemaining.add(table);  // retry next pass
            }
        }
    }
    remaining = nextRemaining;  // loop lại
}
```

> **Problem**: Worst case với deep FK chains (5 levels) → **5 passes**. Mỗi pass DELETE toàn bộ bảng (có thể rất lớn). Nếu 1 bảng có 10M rows, DELETE mất rất lâu và tạo redo log lớn.

**Solution**: DELETE theo reverse topological order (child trước, parent sau)
```java
// Đã có sortByForeignKeyDependency → reverse nó
List<TableDefinition> reverseOrder = new ArrayList<>(allTables);
Collections.reverse(reverseOrder);
// DELETE theo thứ tự này → KHÔNG BAO GIỜ bị ORA-02292
for (TableDefinition table : reverseOrder) {
    statement.executeUpdate("DELETE FROM " + table);  // luôn thành công
}
```

---

### 10. DDL Phase Chạy Single-Threaded trên 1 Connection

**File**: [MigrationWorker.java:392-461](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L392-L461)

```java
private void runCreateTablesPhase(Connection targetConn, List<TableDefinition> allTables, ...) {
    try (Statement statement = targetConn.createStatement()) {
        for (TableDefinition table : allTables) {
            statement.execute(createSql);  // TUẦN TỰ, 1 statement, 1 connection
        }
    }
}
```

Tất cả DDL phases (create tables, add FKs, create indexes, create sequences, create triggers, create views) đều chạy trên **1 connection, tuần tự**.

> **Problem**: CREATE TABLE thường nhanh, nhưng **CREATE INDEX** (đặc biệt trên bảng lớn sau data load) có thể mất **phút đến hàng giờ**. Indexes không phụ thuộc nhau → có thể chạy song song.

**Solution**: Song song hóa CREATE INDEX phase
```java
private void runCreateIndexesPhase(...) {
    int parallelism = Math.min(4, indexes.size());
    ExecutorService indexExecutor = Executors.newFixedThreadPool(parallelism);
    
    for (IndexDefinition idx : indexes) {
        indexExecutor.submit(() -> {
            try (Connection conn = manager.getConnection(targetPoolId);
                 Statement stmt = conn.createStatement()) {
                stmt.execute(indexSql);
            }
        });
    }
}
```

> [!TIP]
> Oracle 12c+ hỗ trợ `CREATE INDEX ... PARALLEL n` — cho phép 1 CREATE INDEX sử dụng nhiều CPU core. Kết hợp cả hai: song song giữa indexes + parallel bên trong mỗi index.

---

### 11. Oracle: Thiếu NOLOGGING / APPEND Hint cho Bulk Insert

Khi insert data lớn vào Oracle, thêm `NOLOGGING` và `/*+ APPEND */` hint giảm đáng kể redo log I/O:

```java
// HIỆN TẠI: standard INSERT
"INSERT INTO schema.table (col1, col2) VALUES (?, ?)"

// TỐI ƯU cho homogeneous Oracle:
// 1. Trước khi load data:
"ALTER TABLE schema.table NOLOGGING"
// 2. Sử dụng APPEND hint (direct-path insert):
"INSERT /*+ APPEND */ INTO schema.table (col1, col2) VALUES (?, ?)"
// 3. Sau khi load xong:
"ALTER TABLE schema.table LOGGING"
```

> **Impact**: NOLOGGING + APPEND có thể giảm **30-50%** thời gian INSERT cho Oracle target bằng cách bypass redo log.

> [!WARNING]
> `/*+ APPEND */` yêu cầu commit sau mỗi batch (đã có). Nhưng nếu DB crash giữa chừng, data chưa backup sẽ mất. Chỉ dùng cho migration scenario, không production.

---

### 12. Swing `publish()` Overhead — EDT Flooding

**File**: [MigrationWorker.java](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java) — hàng chục calls `publish()` per table

```java
// Trong data transfer loop — gọi publish() mỗi batch commit
publish("    -> Batch " + tableName + ": +" + justTransferred + " dòng, ...");
```

Với `batchSize=1000`, bảng 1M rows → **1000 publish() calls** cho 1 bảng. 100 bảng → **100,000 publish() calls**. Swing `SwingWorker.publish()` gộp messages (coalesce) nhưng vẫn tạo overhead:

1. **String concatenation**: Xây dựng message mỗi lần (allocation)
2. **EDT queue**: Mỗi publish → schedule Runnable lên EDT
3. **JTextArea append**: `ui.appendLog()` trên EDT gây reflow/repaint

**Solution**: Rate-limit publish — chỉ log mỗi N giây
```java
private volatile long lastPublishTimeMs = 0;
private static final long PUBLISH_INTERVAL_MS = 2000; // 2 giây

private void publishThrottled(String message) {
    long now = System.currentTimeMillis();
    if (now - lastPublishTimeMs >= PUBLISH_INTERVAL_MS) {
        publish(message);
        lastPublishTimeMs = now;
    }
}
```

---

### 13. `DataTransferService` Instantiation Per Table

**File**: [MigrationWorker.java:961](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L961)

```java
// Trong lambda submitted cho mỗi table
levelCompletionService.submit(() -> {
    DataTransferService transferService = new DataTransferService(sqlGenerator, retryPolicy);
    // ...
});
```

`DataTransferService` **không có mutable state** giữa các tables — nó hoàn toàn stateless: chỉ giữ `sqlGenerator` + `retryPolicy`. Tạo mới mỗi bảng là lãng phí.

**Solution**: Tạo 1 lần, share giữa threads (thread-safe vì stateless)
```java
DataTransferService sharedTransferService = new DataTransferService(sqlGenerator, retryPolicy);
for (TableMigrationPlan plan : levelPlans) {
    levelCompletionService.submit(() -> {
        // Sử dụng sharedTransferService thay vì new instance
        return transferTableWithRetry(manager, ..., sharedTransferService, ...);
    });
}
```

---

### 14. Redundant AutoCommit Toggle 

**File**: [DataTransferService.java:277-280](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/DataTransferService.java#L277-L280), [DataTransferService.java:435-437](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/DataTransferService.java#L435-L437)

```java
// Set autoCommit = false
boolean originalSourceAutoCommit = sourceConn.getAutoCommit();
boolean originalTargetAutoCommit = targetConn.getAutoCommit();
sourceConn.setAutoCommit(false);
targetConn.setAutoCommit(false);

// ... transfer data ...

// Restore autoCommit
sourceConn.setAutoCommit(originalSourceAutoCommit);
targetConn.setAutoCommit(originalTargetAutoCommit);
```

Trong `transferTableWithRetry` ([MigrationWorker.java:1269-1335](file:///home/wintermouse/Documents/Development/migration-web/migration-db-web/src/main/java/org/example/migrationdbweb/migratetool/MigrationWorker.java#L1269-L1335)), retry loop tạo **connection mới mỗi attempt** → `transferTableDataInternal` lại toggle autoCommit cho connection mới.

Mỗi `setAutoCommit()` = **1 roundtrip DB** (Oracle: `SET AUTOCOMMIT OFF`). Retry 3 attempts × 2 connections = **6 roundtrips** chỉ cho toggle.

**Solution**: Set autoCommit = false 1 lần khi tạo connection pool
```java
// Trong ConnectionManager.createPool():
hikariConfig.setAutoCommit(false);  // HikariCP set cho tất cả connections

// Hoặc không restore trong finally:
// Migration connections luôn dùng manual commit → không cần toggle
```

---

## 📊 Tổng Kết Priority (Extended)

| # | Vấn đề | Category | Effort | Impact | Priority |
|---|--------|----------|--------|--------|----------|
| 1 | Regex compilation storm | CPU | Low | ⭐⭐⭐⭐ | **P0** |
| 3 | Thread pool thrashing per FK level | Threading | Low | ⭐⭐⭐ | **P0** |
| 4 | FK-level serialization | Threading | Medium | ⭐⭐⭐⭐ | **P1** |
| 5 | Sequential row counting | CPU/I/O | Medium | ⭐⭐⭐⭐ | **P1** |
| 10 | DDL indexes single-threaded | Threading | Medium | ⭐⭐⭐⭐ | **P1** |
| 11 | Missing NOLOGGING/APPEND | Oracle | Low | ⭐⭐⭐⭐ | **P1** |
| 9 | Multi-pass DELETE truncate fallback | Ordering | Low | ⭐⭐⭐ | **P1** |
| 12 | publish() EDT flooding | Swing | Low | ⭐⭐⭐ | **P1** |
| 2 | Wildcard pattern recompile | CPU | Low | ⭐⭐ | **P2** |
| 6 | isOracleConnection() per table | Redundant | Low | ⭐⭐ | **P2** |
| 7 | Duplicate topological sort | Redundant | Low | ⭐⭐ | **P2** |
| 8 | Memory pressure from retry buffer | Memory | Medium | ⭐⭐⭐ | **P2** |
| 13 | DataTransferService per table | Redundant | Low | ⭐ | **P2** |
| 14 | AutoCommit toggle overhead | Redundant | Low | ⭐ | **P2** |

---

## 🎯 Top 5 Quick-Win Actions (Kết hợp cả 2 bản phân tích)

Sắp xếp theo **Effort thấp nhưng Impact cao**:

| # | Action | File | Lines Changed | Expected Impact |
|---|--------|------|---------------|-----------------|
| 1 | `reWriteBatchedInserts=true` cho PG | ConnectionManager.java | ~3 | **2-3x** insert speed |
| 2 | Tăng fetchSize = batchSize | DataTransferService.java | ~3 | **3-5x** read speed |
| 3 | Static regex patterns | OracleToPgsqlTransformer.java | ~10 | Eliminate **~1s** wasted CPU |
| 4 | HikariCP `autoCommit=false` default | ConnectionManager.java | ~1 | Eliminate redundant toggles |
| 5 | Oracle `NOLOGGING` during load | MigrationWorker.java | ~15 | **30-50%** faster INSERT |
