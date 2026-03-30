# Backend Architecture & File Relationship

## 1. Tổng Quan Cấu Trúc Package

```
src/main/java/org/example/migrationdbweb/
│
├── MigrationDbWebApplication.java     ← Spring Boot entry point
│
├── config/
│   └── WebSocketConfig.java            ← STOMP WebSocket broker configuration
│
├── DTO/
│   ├── MigrationRequest.java            ← REST API request body
│   └── MigrationStatus.java            ← Progress/status response DTO
│
├── controller/
│   └── MigrationController.java         ← REST API endpoints (HTTP layer)
│
├── service/
│   └── MigrationWebWorkerService.java  ← Async worker (core orchestration)
│
└── migratetool/                         ← SHARED CORE ENGINE
    ├── DatabaseConfig.java              ← JDBC config POJO
    ├── DatabaseType.java               ← Enum: ORACLE / POSTGRESQL
    ├── ConnectionManager.java           ← HikariCP connection pool singleton
    ├── MetadataExtractor.java           ← JDBC DatabaseMetaData reader
    ├── SqlDialect.java                  ← Interface for SQL generation
    ├── PostgresDialect.java             ← PostgreSQL SQL implementation
    ├── OracleDialect.java               ← Oracle SQL implementation
    ├── DialectFactory.java              ← Factory to get dialect by DB type
    ├── SqlGenerator.java                ← SELECT/INSERT SQL builder
    ├── DataTransferService.java         ← Core data transfer engine
    ├── ColumnDefinition.java            ← Column metadata POJO
    ├── TableDefinition.java             ← Table metadata POJO
    ├── ForeignKeyDefinition.java        ← FK metadata POJO
    ├── ViewDefinition.java              ← View metadata POJO (Builder pattern)
    ├── TopologicalSortUtil.java         ← Kahn's algorithm for view dependencies
    ├── MigrationRetryPolicy.java        ← Retry/backoff/resume config
    └── MigrationCheckpointStore.java    ← Persistence of resume offsets
```

---

## 2. Luồng Request Toàn Cục (Request Lifecycle)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT (Vue.js / Frontend)                           │
│  ─ POST /api/migration/start  ─ GET /api/migration/status                        │
│  ─ WebSocket /ws-migration → subscribe /topic/status                              │
└──────────────────────────────┬───────────────────────────────────────────────────┘
                               │ HTTP POST (MigrationRequest JSON)
                               │ WebSocket STOMP connect
                               ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│  MigrationDbWebApplication.java                                                    │
│  @SpringBootApplication  @EnableAsync                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐  │
│  │  MigrationController.java  (REST Layer — HTTP)                              │  │
│  │  POST /api/migration/start        → kích hoạt async worker                  │  │
│  │  GET  /api/migration/status       → trả về MigrationStatus hiện tại       │  │
│  │  POST /api/migration/test-connection → test DB connectivity                │  │
│  └──────────────────────────┬──────────────────────────────────────────────────┘  │
│  ┌──────────────────────────▼──────────────────────────────────────────────────┐  │
│  │  WebSocketConfig.java (STOMP Broker)                                        │  │
│  │  Endpoint: /ws-migration  |  Broker prefix: /topic  |  App prefix: /app   │  │
│  │  (SimpMessagingTemplate dùng broker này để gửi /topic/status về client)   │  │
│  └──────────────────────────┬──────────────────────────────────────────────────┘  │
└──────────────────────────────┼───────────────────────────────────────────────────┘
                               │ @Async (chạy trên thread pool riêng)
                               ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│  MigrationWebWorkerService.java  (ASYNC WORKER — Core Orchestrator)              │
│                                                                                   │
│  startMigrationProcess(request) ────────────────────────────────────────────   │
│  │                                                                               │
│  ├── 1. Tạo connection pools (source + target)                                  │
│  │      ConnectionManager.getInstance()                                         │
│  │       └── createPool("WEB_SOURCE_" + nanoTime, DatabaseConfig)              │
│  │       └── createPool("WEB_TARGET_" + nanoTime, DatabaseConfig)              │
│  │       └── testConnection(poolId) → conn.isValid(5)                            │
│  │                                                                               │
│  ├── 2. Đọc metadata từ source DB                                               │
│  │      new MetadataExtractor()                                                 │
│  │       ├── getTableNames(conn, schema) → List<String>                        │
│  │       └── extractTableDefinition(conn, schema, tableName) → TableDefinition │
│  │           ├── List<ColumnDefinition>  (DatabaseMetaData.getColumns)         │
│  │           ├── List<String> primaryKeys  (DatabaseMetaData.getPrimaryKeys)   │
│  │           └── List<ForeignKeyDefinition> (DatabaseMetaData.getImportedKeys) │
│  │                                                                               │
│  ├── 3. Resolve SQL Dialect                                                      │
│  │      DialectFactory.getDialect(DatabaseType)                                 │
│  │       ├── ORACLE     → new OracleDialect()                                   │
│  │       └── POSTGRESQL → new PostgresDialect()                                  │
│  │                                                                               │
│  ├── 4. PHASE: CREATE TABLES (DDL)           [progress: 42–55%]                 │
│  │      targetDialect.buildCreateTableSql(table)                                │
│  │       ├── PostgresDialect: NUMBER→NUMERIC, SERIAL, BOOLEAN                   │
│  │       └── OracleDialect:   VARCHAR→VARCHAR2, GENERATED BY DEFAULT AS IDENTITY│
│  │      statement.execute(sql)  → bỏ qua lỗi "already exists"                  │
│  │                                                                               │
│  ├── 5. PHASE: TRUNCATE (optional)           [progress: 58%]                    │
│  │      TRUNCATE TABLE targetDialect.quoteIdentifier(name)                      │
│  │       └── Postgres: thêm "RESTART IDENTITY CASCADE"                            │
│  │       └── bỏ qua lỗi "table does not exist"                                  │
│  │                                                                               │
│  ├── 6. PHASE: TRANSFER DATA (DML)             [progress: 60–85%]                │
│  │      new SqlGenerator(sourceDialect, targetDialect)                          │
│  │      new DataTransferService(sqlGenerator)                                   │
│  │                                                                               │
│  │      for (each TableDefinition):                                             │
│  │       transferTableData(sourceConn, targetConn, table, batchSize, limit,    │
│  │                          copyNewOnly, progressCallback)                       │
│  │        │                                                                      │
│  │        ├── SqlGenerator.buildSelectSql(table)                                │
│  │        │    → SELECT col1, col2, ... FROM schema.table WHERE pk > offset     │
│  │        ├── SqlGenerator.buildInsertSql(table)                               │
│  │        │    → INSERT INTO ... VALUES (?, ?, ...)                             │
│  │        │    → PostgreSQL: thêm "ON CONFLICT (pk) DO NOTHING"                  │
│  │        ├── SqlGenerator.buildExistsByPrimaryKeySql(table) (nếu copyNewOnly)│
│  │        │    → SELECT 1 FROM ... WHERE pk1=? AND pk2=?                        │
│  │        │                                                                      │
│  │        ├── Execute SELECT trên source (streaming, fetchSize=10000)          │
│  │        ├── Batch INSERT vào target (addBatch/executeBatch, commit/1000 rows)│
│  │        ├── Retry on transient errors (deadlock, timeout, SQLState 08*)      │
│  │        │    → MigrationRetryPolicy: exponential backoff, max 3 attempts    │
│  │        └── Gọi callback → broadcastStatus() qua WebSocket                    │
│  │                                                                               │
│  ├── 7. PHASE: ADD FOREIGN KEYS             [progress: 88–90%]                  │
│  │      targetDialect.buildAddForeignKeySql(table)                               │
│  │       → ALTER TABLE ADD CONSTRAINT fk_name FOREIGN KEY (col)                │
│  │           REFERENCES target_table(col)                                       │
│  │      statement.execute(sql) → bỏ qua lỗi constraint already exists         │
│  │                                                                               │
│  ├── 8. PHASE: CREATE VIEWS                  [progress: 92–100%]                 │
│  │      MetadataExtractor.extractViewDefinitionsWithDependencySort(conn,        │
│  │                              sourceSchema, sourceDbType)                      │
│  │       ├── PostgreSQL: pg_get_viewdef + pg_depends (dependency map)           │
│  │       └── Oracle:    USER_VIEWS.TEXT + USER_DEPENDENCIES                      │
│  │      TopologicalSortUtil.sortByDependencies(depMap)                          │
│  │       └── Kahn's algorithm: BFS trên đồ thị dependency                        │
│  │       └── Circular dependency → throw IllegalStateException (skip views)     │
│  │                                                                               │
│  │      for (each sorted ViewDefinition):                                       │
│  │       ├── replaceSchemaInClause(selectClause, sourceSchema, targetSchema)    │
│  │       ├── targetDialect.buildCreateViewSql(vd)                                │
│  │       │    ├── PostgresDialect: CREATE OR REPLACE VIEW ... AS selectClause   │
│  │       │    └── OracleDialect:  CREATE VIEW ... AS selectClause (full DDL)    │
│  │       ├── DROP VIEW IF EXISTS (nếu replaceExistingViews=true)               │
│  │       └── statement.execute(createSql) → bỏ qua "view already exists"       │
│  │                                                                               │
│  └── 9. Hoàn tất: broadcastStatus(100, "COMPLETED", ...)                         │
│       finally: closePool(sourcePoolId) + closePool(targetPoolId)                │
│                                                                                   │
│  broadcastStatus(progress, status, message, inProgress)                         │
│   ├── latestStatus.set(payload)                                                 │
│   └── messagingTemplate.convertAndSend("/topic/status", payload)                │
│       → Gửi qua WebSocket/STOMP broker đến subscribed clients                   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Biểu Đồ Mối Liên Hệ Giữa Các File (File Relationship Diagram)

```
┌──────────────────────────────────────────────────────────────────────┐
│                   Vue.js Frontend (Browser)                          │
│   HTTP Client  ·  WebSocket STOMP Client                            │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               │ HTTP + WebSocket
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│              MigrationDbWebApplication.java                          │
│  @SpringBootApplication  @EnableAsync                                │
│  ─ Spring container khởi tạo, quản lý bean lifecycle                │
│  ─ Tạo thread pool cho @Async                                       │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
          ┌────────────────────┴────────────────────┐
          │                                         │
          ▼                                         ▼
┌──────────────────────────┐           ┌──────────────────────────────┐
│  WebSocketConfig.java    │           │  MigrationController.java    │
│  ─ STOMP endpoint        │           │  REST Endpoints              │
│    /ws-migration          │           │  @RequestMapping             │
│  ─ Broker: /topic        │           │  /api/migration/*            │
│  ─ App prefix: /app      │           └──────────────┬───────────────┘
└──────────┬───────────────┘                            │
           │                                              │
           │ SimpMessagingTemplate                       │ Autowired
           │ (injected vào service)                       ▼
           │                              ┌──────────────────────────────┐
           │                              │  MigrationWebWorkerService   │
           │                              │  @Service                     │
           │                              │  @Async startMigrationProcess│
           │                              └──────────────┬───────────────┘
           │                                             │
           │                                             │ new / static calls
           ▼                                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       migratetool/  (Shared Core Engine)               │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  DatabaseConfig.java                                         │   │
│  │  DatabaseType.java (enum: ORACLE, POSTGRESQL)                │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  ConnectionManager.java (Singleton)                           │   │
│  │  ─ createPool(poolId, DatabaseConfig) → HikariDataSource      │   │
│  │  ─ getConnection(poolId) → java.sql.Connection                │   │
│  │  ─ testConnection(poolId) → conn.isValid(5)                   │   │
│  │  ─ closePool(poolId)                                          │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  MetadataExtractor.java                                      │   │
│  │  ─ getTableNames(conn, schema) → List<String>                 │   │
│  │  ─ extractTableDefinition(conn, schema, name)               │   │
│  │      → TableDefinition (columns, PKs, FKs)                   │   │
│  │  ─ getViewNames(conn, schema)                                 │   │
│  │  ─ extractViewDefinition(conn, schema, name, dbType)        │   │
│  │  ─ extractViewDefinitionsWithDependencySort(conn, schema)   │   │
│  │      → MetadataExtractor ──────────────────────────────┐     │   │
│  │      │ (gọi pg_depends hoặc USER_DEPENDENCIES)         │     │   │
│  │      └── TopologicalSortUtil.sortByDependencies(depMap)│     │   │
│  └──────────────────────────────────────────────────────────│────   │
│                                                               │        │
│  ┌───────────────────────────────────────────────────────────▼────┐   │
│  │  SqlDialect.java (Interface)                                  │   │
│  │    ├─ PostgresDialect.java  implements SqlDialect            │   │
│  │    └─ OracleDialect.java   implements SqlDialect             │   │
│  └───────────────────────────────────────────────────────────│────┘   │
│                                                               │        │
│  ┌────────────────────────────────────────────────────────────▼────┐   │
│  │  DialectFactory.java                                           │   │
│  │    getDialect(DatabaseType) → SqlDialect instance             │   │
│  └───────────────────────────────────────────────────────────────│────┘   │
│                                                                   │        │
│  ┌────────────────────────────────────────────────────────────────▼──┐  │
│  │  SqlGenerator.java                                                │  │
│  │  ─ buildSelectSql(table)         → SELECT ... FROM schema.table  │  │
│  │  ─ buildInsertSql(table)         → INSERT INTO ... VALUES (?,?)   │  │
│  │       PostgreSQL: thêm ON CONFLICT (pk) DO NOTHING               │  │
│  │  ─ buildExistsByPrimaryKeySql(table)                             │  │
│  │       → SELECT 1 FROM ... WHERE pk1=? AND pk2=?                  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                              │                                        │
│                              ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  DataTransferService.java                                       │   │
│  │  transferTableData(sourceConn, targetConn, table, batchSize,     │   │
│  │                     limit, copyNewOnly, progressCallback)       │   │
│  │   │                                                               │   │
│  │   ├── SqlGenerator.buildSelectSql/InsertSql/ExistsSql           │   │
│  │   ├── JDBC: setFetchSize(10000), TYPE_FORWARD_ONLY              │   │
│  │   ├── Batch INSERT: addBatch() / executeBatch() / commit()      │   │
│  │   ├── copyNewOnly: kiểm tra PK tồn tại trước khi INSERT          │   │
│  │   ├── Retry: MigrationRetryPolicy (exponential backoff)         │   │
│  │   ├── Resume: MigrationCheckpointStore (per-table offset)       │   │
│  │   └── Return: TransferResult (transferredRows, skippedRows)     │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────┐ ┌──────────────────┐ ┌──────────────────────────┐  │
│  │ ColumnDef    │ │ TableDefinition  │ │ ForeignKeyDefinition      │  │
│  │ (POJO)       │ │ (POJO + FK list) │ │ (POJO)                    │  │
│  └──────────────┘ └──────────────────┘ └──────────────────────────┘  │
│                                                                      │
│  ┌──────────────────┐  ┌───────────────────────┐  ┌────────────────┐  │
│  │ ViewDefinition  │  │ TopologicalSortUtil  │  │ MigrationRetry │  │
│  │ (Builder pattern)│  │ (Kahn's algorithm)    │  │ Policy         │  │
│  └──────────────────┘  └───────────────────────┘  └────────────────┘  │
│                                                                      │
│  ┌──────────────────────────┐                                        │
│  │ MigrationCheckpointStore │ ←─── .migration-resume.properties     │
│  └──────────────────────────┘                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. Chi Tiết Từng Phase — Progress Mapping

```
┌──────┬───────────┬─────────────────────────────────────────────────────┐
│ (%)  │ Phase     │ Chi tiết thực thi                                   │
├──────┼───────────┼─────────────────────────────────────────────────────┤
│  0–3 │ INIT      │ Tạo HikariCP pool: source + target                  │
│  3–10│ CONN TEST │ manager.testConnection() → conn.isValid(5)           │
│ 15–20│ META READ │ getTableNames() → extractTableDefinition() mỗi bảng  │
│ 42–55│ CREATE DDL│ buildCreateTableSql() → statement.execute() mỗi bảng  │
│  58  │ TRUNCATE  │ TRUNCATE TABLE (PostgreSQL: RESTART IDENTITY CASCADE│
│ 60–85│ DATA TRANS│ transferTableData() — batch INSERT mỗi bảng         │
│ 88–90│ ADD FK    │ buildAddForeignKeySql() → ALTER TABLE ADD CONSTRAINT │
│ 92–100│ CREATE VIEW│ topological sort → buildCreateViewSql() → execute │
└──────┴───────────┴─────────────────────────────────────────────────────┘
```

---

## 5. WebSocket Communication Flow

```
Browser (Vue.js)                              Spring Boot Backend
      │                                              │
      │  1. STOMP CONNECT /ws-migration              │
      │ ────────────────────────────────────────────▶  WebSocketConfig
      │                                              │  registerStompEndpoint
      │  2. STOMP SUBSCRIBE /topic/status            │
      │ ────────────────────────────────────────────▶
      │                                              │
      │  3. HTTP POST /api/migration/start           │
      │     (MigrationRequest JSON)                  │
      │ ────────────────────────────────────────────▶  MigrationController
      │      │                                        │  startMigrationProcess()
      │      │  @Async thread                          │
      │      └──────────────────────────────────────────▶  MigrationWebWorkerService
      │                                                    │
      │                                                    │ broadcastStatus(5, ...)
      │  4. STOMP SEND /topic/status ◀────────────────────┘
      │     (MigrationStatus JSON)                         │
      │      │                                              │
      │      │  broadcastStatus(20, ...)                   │
      │  5. STOMP SEND /topic/status ◀────────────────────┘
      │     (progress update)                              │
      │      │                                              │
      │      │  ... (nhiều message trong quá trình)        │
      │      │                                              │
      │      │  broadcastStatus(100, "COMPLETED", ...)    │
      │  N. STOMP SEND /topic/status ◀────────────────────┘
      │     (final status)                                 │
      │                                              │
      │  6. HTTP GET /api/migration/status               │
      │ ────────────────────────────────────────────▶  MigrationController
      │ ◀────────────────────────────────────────────  MigrationStatus JSON
```

---

## 6. Class Diagram (POJO & DTO)

```
┌─────────────────────────────────────────────────────────────────┐
│  MigrationRequest.java (@Data)                                  │
│  ├── source         : DatabaseConfig                             │
│  ├── target         : DatabaseConfig                             │
│  ├── structureOnly  : boolean                                   │
│  ├── dataOnly       : boolean                                    │
│  └── options        : MigrationOptions                           │
│                       ├── truncate          : boolean            │
│                       ├── copyNewOnly       : boolean            │
│                       ├── limit             : int                │
│                       ├── includeTablesCsv  : String             │
│                       ├── excludeTablesCsv  : String             │
│                       ├── migrateViews       : boolean           │
│                       ├── includeViewsCsv   : String             │
│                       ├── excludeViewsCsv   : String             │
│                       └── replaceExistingViews: boolean           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ được truyền vào
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  MigrationWebWorkerService.java                                 │
│  @Service                                                       │
│  @Async startMigrationProcess(MigrationRequest)                 │
│  SimpMessagingTemplate messagingTemplate  ← WebSocket broadcast│
│  AtomicReference<MigrationStatus> latestStatus                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  MigrationStatus.java (@Data)                                   │
│  ├── progress    : int                                           │
│  ├── status      : String (HEALTHY/RUNNING/COMPLETED/FAILED)   │
│  ├── message     : String                                       │
│  ├── inProgress  : boolean                                       │
│  └── timestamp   : long                                         │
└─────────────────────────────────────────────────────────────────┘
```

```
┌──────────────────────────────────────────────────────────────────┐
│  TableDefinition.java                                             │
│  ├── tableName           : String                                 │
│  ├── columns             : List<ColumnDefinition>                 │
│  ├── primaryKeys         : List<String>                          │
│  └── foreignKeys         : List<ForeignKeyDefinition>             │
└──────────────────────────────────────────────────────────────────┘
                    │ contains
        ┌───────────┴───────────────┐
        ▼                           ▼
┌────────────────────┐   ┌──────────────────────────────────────┐
│ ColumnDefinition   │   │ ForeignKeyDefinition                  │
│ ├── name           │   │ ├── fkName        : String           │
│ ├── jdbcType       │   │ ├── fkColumnName  : String           │
│ ├── typeName       │   │ ├── targetTableName: String         │
│ ├── size           │   │ └── targetColumnName: String        │
│ ├── scale          │   └──────────────────────────────────────┘
│ ├── isNullable     │
│ └── isAutoIncrement│
└────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  ViewDefinition.java (Builder pattern)                           │
│  ├── viewName      : String                                      │
│  ├── schema        : String                                      │
│  ├── sourceDialect : DatabaseType                                │
│  ├── selectClause : String                                      │
│  ├── checkOption  : String                                      │
│  ├── sourceSchema : String                                      │
│  ├── targetSchema : String                                      │
│  └── materialized : boolean                                     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 7. Key Error Handling Patterns

```
┌──────────────────────────────────────────────────────────────────┐
│ Error Pattern: "Already Exists" — SILENT SKIP                    │
│                                                                  │
│ CREATE TABLE  → "already exists" / "ora-00955" / SQLState 42P07 │
│   → Bỏ qua, tiếp tục bảng/ký tự tiếp theo                       │
│                                                                  │
│ ADD CONSTRAINT → "constraint already exists" / "ora-02275"       │
│   → Bỏ qua, không fail job                                      │
│                                                                  │
│ CREATE VIEW   → "already exists" / "ora-00955" / SQLState 42P06 │
│   → Bỏ qua (nếu replaceExistingViews=false)                    │
│   → DROP VIEW trước (nếu replaceExistingViews=true)            │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Error Pattern: "Not Exists" — SILENT SKIP                        │
│                                                                  │
│ TRUNCATE TABLE → "does not exist" / "ora-00942" / SQLState 42P01│
│   → Bỏ qua, table không tồn tại ở target là OK                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Error Pattern: RETRY on Transient Errors                        │
│                                                                  │
│ Retry khi:                                                        │
│   ├── SQLState "08*"        → connection error                   │
│   ├── SQLState "40001"      → deadlock (PostgreSQL)              │
│   ├── SQLState "40P01"      → serialization failure              │
│   ├── SQLState "HYT00/HYT01" → HikariCP connection timeout       │
│                                                                  │
│ Backoff: delay = 2000ms × 2^attempt  (exponential)             │
│ Max attempts: 3                                                  │
│ Config via env: MIGRATION_ENABLE_RETRY, MIGRATION_MAX_ATTEMPTS   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 8. Dependency Injection Summary

```
Spring Container Bean Graph
═════════════════════════

  MigrationDbWebApplication
         │
         ├── @EnableAsync
         │    └── Tạo TaskExecutor (thread pool cho @Async methods)
         │
         ├── WebSocketMessageBrokerConfiguration
         │    └── Cấu hình STOMP broker thông qua WebSocketConfig
         │
         ├── MigrationController (@RestController)
         │    └── @Autowired MigrationWebWorkerService
         │         └── @Autowired SimpMessagingTemplate (system bean)
         │
         └── MigrationWebWorkerService (@Service)
              └── Được gọi trực tiếp bởi Controller
                  └── new MetadataExtractor()         (manual new)
                  └── new SqlGenerator(...)           (manual new)
                  └── new DataTransferService(...)    (manual new)
                  └── ConnectionManager.getInstance() (singleton, static)

Note: Các class trong migratetool/ KHÔNG phải Spring bean
      (trừ MigrationWebWorkerService được @Autowired vào Controller).
      Chúng được khởi tạo thủ công (new) bên trong service.
      ConnectionManager là Singleton pattern, không phải Spring bean.
```

---

## 9. Data Flow for Single Table Migration

```
SOURCE DB (Oracle/PostgreSQL)          MIGRATION ENGINE               TARGET DB (Oracle/PostgreSQL)
═══════════════════════════════         ════════════════               ═══════════════════════

1. SELECT * FROM schema.table
   WHERE pk > offset
   ORDER BY pk
   [streaming, fetchSize=10000]
   ─────────────────────────────────▶ DataTransferService
                                       │
                                       │  ① SqlGenerator
                                       │     buildSelectSql(table)
                                       │
                                       │  ② Execute on sourceConn
                                       │     → ResultSet (streaming)
                                       │
                                       │  ③ For each row batch (1000):
                                       │     buildInsertSql(table)
                                       │     ON CONFLICT (pk) DO NOTHING (PG)
                                       │
                                       │  ④ PreparedStatement.addBatch()
                                       │     executeBatch()
                                       │
                                       │  ⑤ connection.commit()
                                       │
                                       │  ⑥ Retry if transient error
                                       │
                                       │  ⑦ Update checkpoint offset
                                       │
                                       │  ⑧ Callback → broadcastStatus()
                                       │
                                       │  ⑨ copyNewOnly?
                                       │     buildExistsByPrimaryKeySql()
                                       │     → SELECT 1 FROM target
                                       │       WHERE pk1=? AND pk2=?
                                       │     → skip if exists
                                       │
                                       ▼
2. INSERT INTO schema.table
   (col1, col2, ...)
   VALUES (?, ?, ...)
   [batch, commit per 1000 rows]
   ─────────────────────────────────▶ TARGET DB (execute)
```
