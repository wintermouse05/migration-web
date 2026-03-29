# Code Review Report — migration-db-web

> Generated: 2026-03-30
> Reviewer: Claude Code

---

## 1. Build Status

### Backend (Spring Boot + Java 17)

| Item | Result |
|---|---|
| Command | `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn clean compile` |
| Source files compiled | 30 |
| Errors | 0 |
| **Status** | **BUILD SUCCESS** ✅ |

### Frontend (Vue 3)

| Item | Result |
|---|---|
| Command | `npm run build` |
| Output | `dist/` generated |
| Errors | 0 |
| **Status** | **BUILD SUCCESS** ✅ |

---

## 2. Frontend-Backend Compatibility

**Status: FULLY COMPATIBLE** ✅

### API Endpoints Covered

| Endpoint | Method | Frontend | Backend |
|---|---|---|---|
| `/api/migration/start` | POST | ✅ | ✅ |
| `/api/migration/status` | GET | ✅ | ✅ |
| `/api/migration/test-connection` | POST | ✅ | ✅ |

### WebSocket

| Channel | Frontend | Backend |
|---|---|---|
| `/topic/status` | ✅ `VUE_APP_WS_TOPIC` | ✅ `WebSocketConfig` broker prefix |

### View Migration Options — Field Mapping

| Backend Field (Java) | Frontend Field | Type | Match |
|---|---|---|---|
| `options.migrateViews` | `migrateViews` | boolean | ✅ |
| `options.includeViewsCsv` | `includeViewsCsv` | String | ✅ |
| `options.excludeViewsCsv` | `excludeViewsCsv` | String | ✅ |
| `options.replaceExistingViews` | `replaceExistingViews` | boolean | ✅ |

---

## 3. Backend Issues (Priority Order)

### 🔴 CRITICAL — Must Fix

#### Issue #1: Operator Precedence Bug — `isConstraintAlreadyExistsError`

**File:** `MigrationWebWorkerService.java`, `DirectionalMigration.java`, `MigrationWorker.java`

**Current code:**
```java
// ❌ WRONG — && binds before ||, so "ora-02275" always bypasses
return "42710".equals(sqlState)
    || message.contains("constraint") && message.contains("already exists")
    || message.contains("ora-02275");
```

**Problem:** Due to Java operator precedence, this is evaluated as:
```
("42710".equals(sqlState))
  || ((message.contains("constraint") && message.contains("already exists")))
  || (message.contains("ora-02275"))
```
The `ora-02275` check is unreachable because it is after the `||` group.

**Fix:**
```java
return "42710".equals(sqlState)
    || (message.contains("constraint") && message.contains("already exists"))
    || message.contains("ora-02275");
```

---

#### Issue #2: Operator Precedence Bug — `isViewAlreadyExistsError`

**File:** `MigrationWebWorkerService.java`

**Current code:**
```java
// ❌ WRONG — same precedence issue
return "42P06".equals(sqlState)
    || message.contains("already exists")
    && message.contains("view")
    || message.contains("ora-00955");
```

**Fix:**
```java
return "42P06".equals(sqlState)
    || (message.contains("already exists") && message.contains("view"))
    || message.contains("ora-00955");
```

---

#### Issue #3: Hardcoded Password in Source Code

**Files:**
- `OracleToPostgresMigration.java`
- `PostgresToOracleMigration.java`

```java
// ❌ SECURITY RISK
config.setPassword("admin123");
```

**Fix:** Load from environment variables or configuration file.

---

#### Issue #4: NPE Risk in `broadcastStatus`

**File:** `MigrationWebWorkerService.java`

```java
// ❌ NPE risk — if WS fails, exception propagates up,
//    connection pools in finally{} may not be closed properly
private void broadcastStatus(...) {
    MigrationStatus payload = buildStatus(...);
    latestStatus.set(payload);
    messagingTemplate.convertAndSend("/topic/status", payload); // can throw NPE
}
```

**Fix:** Wrap in try-catch:
```java
try {
    messagingTemplate.convertAndSend("/topic/status", payload);
} catch (Exception e) {
    System.err.println("WS broadcast failed: " + e.getMessage());
}
```

---

#### Issue #5: Mutable List Exposure in `TableDefinition`

**File:** `TableDefinition.java`

```java
// ❌ Caller can modify the internal list directly
public List<ColumnDefinition> getColumns() {
    return columns; // returns reference, not copy
}
```

**Fix:** Return defensive copy:
```java
public List<ColumnDefinition> getColumns() {
    return new ArrayList<>(columns);
}
```

Also applies to: `getPrimaryKeys()`, `getForeignKeys()`

---

#### Issue #6: Null Schema Silently Returns Empty Results

**File:** `MetadataExtractor.java`

```java
// ❌ All methods pass schema directly to SQL
// If schema is null → SQL WHERE clause never matches → silent empty list
try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"TABLE"})) {
```

**Fix:** Validate schema at method entry:
```java
if (schema == null || schema.isBlank()) {
    throw new IllegalArgumentException("Schema must not be null or blank");
}
```

---

### 🟠 HIGH — Should Fix

#### Issue #7: Rollback on Closed Connection in `executeBatchWithRetry`

**File:** `DataTransferService.java`

```java
// ❌ After batch error, rollback may fail if connection is already closed
// This throws a secondary SQLException that shadows the real batch error
} catch (SQLException e) {
    targetConn.rollback(); // can throw if connection already closed
```

**Fix:** Check connection state before rollback:
```java
} catch (SQLException e) {
    try {
        if (!targetConn.isClosed()) {
            targetConn.rollback();
        }
    } catch (SQLException rollbackEx) {
        // Log but don't shadow original error
        System.err.println("Rollback failed: " + rollbackEx.getMessage());
    }
```

---

#### Issue #8: `NUMBER(22,6)` Precision Loss to BIGINT

**File:** `PostgresDialect.java`

```java
// ❌ precision==22 is mapped to BIGINT regardless of scale
// NUMBER(22,6) → BIGINT → decimal precision is silently lost!
if ((precision > 9 && precision <= 18) || precision == 22) {
    return "BIGINT";
}
```

**Fix:** Only map to BIGINT when scale == 0:
```java
if (scale == 0 && ((precision > 9 && precision <= 18) || precision == 22)) {
    return "BIGINT";
}
```

---

#### Issue #9: `@Async` with No Bounded Thread Pool

**File:** `MigrationWebWorkerService.java`

```java
// ❌ Default SimpleAsyncTaskExecutor = unlimited threads
// Multiple concurrent migrations → DB connection pool exhaustion
@Async
public void startMigrationProcess(MigrationRequest request) {
```

**Fix:** Configure a bounded thread pool in `MigrationDbWebApplication.java`:
```java
@Bean
public TaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("Migration-");
    executor.initialize();
    return executor;
}
```

And update the annotation:
```java
@Async("taskExecutor")
public void startMigrationProcess(...)
```

---

#### Issue #10: `ArrayIndexOutOfBoundsException` in `resolvePkIndexes`

**File:** `DataTransferService.java`

```java
// ❌ If metadata is inconsistent (PK column not in column list),
// this throws ArrayIndexOutOfBoundsException
for (int i = 0; i < primaryKeys.size(); i++) {
    String pkName = primaryKeys.get(i);
    // ...
    ColumnDefinition sourceColumn = table.getColumns().get(sourceColumnIndex - 1);
    //                    ↑ sourceColumnIndex could be > columns.size()
```

**Fix:** Validate index bounds:
```java
if (foundIndex < 1 || foundIndex > columns.size()) {
    throw new IllegalArgumentException(
        "PK column " + pkName + " has invalid index " + foundIndex
        + " in table " + table.getTableName()
    );
}
```

---

### 🟡 MEDIUM — Nice to Fix

#### Issue #11: Duplicate `isRetryableException` in 3 Files

**Files:** `DataTransferService.java`, `DirectionalMigration.java`, `MigrationWorker.java`

The same method is copy-pasted 3 times. Extract to a shared utility class:
```
migratetool/SqlExceptionUtil.java
```

---

#### Issue #12: No Bean Validation on Controller

**File:** `MigrationController.java`

```java
// ❌ No @Valid annotation
public ResponseEntity<?> startMigration(@RequestBody MigrationRequest request) {
    if (request.getSource() == null || request.getTarget() == null) {
        return ResponseEntity.badRequest()...
```

**Fix:** Add `@Valid` and validate at DTO level:
```java
public ResponseEntity<?> startMigration(@Valid @RequestBody MigrationRequest request) {
```

And add constraints to `MigrationRequest`:
```java
@NotNull(message = "Source database config is required")
private DatabaseConfig source;

@NotNull(message = "Target database config is required")
private DatabaseConfig target;
```

---

#### Issue #13: No Duplicate Request Protection

**File:** `MigrationController.java`

Multiple `/start` requests can be fired simultaneously with no deduplication.

**Fix:** Use an `AtomicBoolean isRunning` or a `ConcurrentHashMap<jobId, status>` to track active jobs.

---

#### Issue #14: Postgres Schema Hardcoded as `"public"`

**File:** `MigrationWebWorkerService.java`

```java
// ❌ Always uses "public" for Postgres, ignores user's schema
return "public";
```

**Fix:** Allow user to specify schema in `DatabaseConfig`, or infer from connection metadata:
```java
DatabaseMetaData metaData = conn.getMetaData();
String actualSchema = metaData.getUserName(); // or parse from search_path
```

---

#### Issue #15: `normalize("")` Silent Collision in `TopologicalSortUtil`

**File:** `TopologicalSortUtil.java`

```java
// ❌ If viewName is null, normalize("") makes all null names collide
private static String normalize(String name) {
    return name != null ? name.trim().toUpperCase() : "";
}
```

**Fix:** Throw exception for null view names:
```java
if (name == null || name.isBlank()) {
    throw new IllegalArgumentException("View name must not be null or blank");
}
```

---

## 4. Frontend Issues

### 🟡 MEDIUM

#### Issue #16: Missing `.env.development` File

The frontend uses `process.env.VUE_APP_API_BASE_URL` with an empty string fallback. Explicit `.env.development` should be added:

```env
# .env.development
VUE_APP_API_BASE_URL=http://localhost:8080
VUE_APP_WS_ENDPOINT=/ws-migration
VUE_APP_WS_TOPIC=/topic/status
```

---

## 5. Summary

| Severity | Count | Fixed? |
|---|---|---|
| 🔴 CRITICAL | 6 | No |
| 🟠 HIGH | 4 | No |
| 🟡 MEDIUM | 5 | No |
| ✅ Build | 2/2 | Yes |
| ✅ Compatibility | Full | Yes |

### Recommended Fix Order

```
1. Fix #1, #2  (operator precedence — silently drops valid errors)
2. Fix #6      (null schema — silent fail)
3. Fix #4      (NPE risk — pool leak)
4. Fix #5      (mutable list — data corruption)
5. Fix #3      (hardcoded password — security)
6. Fix #9      (async thread pool — connection exhaustion)
7. Fix #8      (precision loss — data corruption)
8. Fix #7      (rollback on closed — error masking)
9. Fix #10     (AIOOB — crash)
10. Fix #11-15 (code quality)
```

---

## 6. Files Reviewed

### Backend (30 files)

```
migratetool/
├── ColumnDefinition.java
├── ConnectionManager.java
├── DataTransferService.java
├── DatabaseConfig.java
├── DatabaseType.java
├── DialectFactory.java
├── ForeignKeyDefinition.java
├── MetadataExtractor.java           ⚠️ Issues #6, #14
├── MigrationRetryPolicy.java
├── OracleDialect.java
├── PostgresDialect.java            ⚠️ Issue #8
├── SequenceDefinition.java          (to be created)
├── SqlDialect.java
├── SqlGenerator.java
├── TableDefinition.java             ⚠️ Issue #5
├── TopologicalSortUtil.java        ⚠️ Issue #15
├── ViewDefinition.java             ✅ OK
├── DirectionalMigration.java       ⚠️ Issues #1, #3, #11
├── MigrationApp.java
├── MigrationAppUI.java
├── MigrationWorker.java            ⚠️ Issues #1, #11
├── OracleToPostgresMigration.java   ⚠️ Issue #3
└── PostgresToOracleMigration.java  ⚠️ Issue #3

dto/
├── DatabaseConfig.java
├── MigrationRequest.java          ✅ OK
└── MigrationStatus.java            ✅ OK

service/
└── MigrationWebWorkerService.java  ⚠️ Issues #1, #2, #4, #9

controller/
└── MigrationController.java         ⚠️ Issues #12, #13

config/
└── WebSocketConfig.java            ✅ OK

main/
└── MigrationDbWebApplication.java   ⚠️ Issue #9 (needs TaskExecutor bean)
```

### Frontend

```
src/
├── main.js                         ✅ OK
├── App.vue                         ✅ OK
└── components/
    ├── DatabaseConfigCard.vue      ✅ OK
    ├── MigrationDashboard.vue       ✅ OK
    ├── MigrationLogConsole.vue     ✅ OK
    ├── MigrationOptions.vue        ✅ OK
    └── MigrationProgress.vue       ✅ OK
```
