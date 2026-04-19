# BÁO CÁO REVIEW: HOMOGENEOUS MIGRATION

## PHẠM VI: `migratetool/` — toàn bộ phase: metadata extraction → DDL → data transfer → checkpoint/resume

---

## 🔴 BUG NGHIÊM TRỌNG (#1) — Silent Row Loss khi `ON CONFLICT DO NOTHING`

**File:** `SqlGenerator.java` dòng 90–97

```java
// Khi target là Postgres + bảng có PK → LUÔN thêm ON CONFLICT DO NOTHING
if (targetDialect instanceof PostgresDialect && !table.getPrimaryKeys().isEmpty()) {
    sql.append(" ON CONFLICT (")
        .append(conflictColumns)
        .append(") DO NOTHING");
}
```

**Tác động:** Trong homogeneous PostgreSQL → PostgreSQL, nếu bảng đích đã có dữ liệu (ví dụ: re-run migration, hoặc trùng PK thủ công), các dòng trùng PK sẽ **bị DROP SILENTLY** — không log, không warning, không exception. Target DB **không giống hoàn toàn** source DB.

**Nguyên nhân gốc:** Comment dòng 82–89 giải thích logic nhưng logic đó **không đúng**:
> *"Khi copyNewOnly=false: không kiểm tra trùng → ON CONFLICT DO NOTHING không cần thiết (vì bản ghi mới không thể conflict)"*

Logic này sai vì `copyNewOnly=false` chỉ là chế độ default — bản ghi trong source DB **hoàn toàn có thể** trùng PK với target DB đã có dữ liệu. `ON CONFLICT DO NOTHING` vẫn skip những dòng đó.

**Mức độ:** 🔴 CRITICAL — vi phạm yêu cầu "target DB giống hoàn toàn source DB"

---

## 🟠 BUG LỚN (#2) — `copyNewOnly=false` kết hợp resume enable vẫn thêm `ON CONFLICT`

**File:** `MigrationWorker.java` dòng 943–946

```java
final boolean effectiveCopyNewOnly = !exportOnlyToFile
        && (copyNewOnly
        || (retryPolicy.isResumeEnabled() && !table.getPrimaryKeys().isEmpty()));
```

**Tác động:** Khi `copyNewOnly=false` nhưng bật `resume`, `effectiveCopyNewOnly` vẫn = `true` vì có check `retryPolicy.isResumeEnabled()`. Kết hợp với `ON CONFLICT DO NOTHING` trong `buildInsertSql`, mọi bảng có PK trong homogeneous PG → PG sẽ **luôn luôn skip rows trùng PK** — ngay cả khi user không muốn.

**Mức độ:** 🟠 HIGH — hành vi không matching expectation của user

---

## 🟠 BUG LỚN (#3) — `isPostgresToPostgresDirectDdlMode` không được dùng ở mọi nơi

**File:** `MigrationWorker.java`

- Dòng 403–405: `isPostgresToPostgresDirectDdlMode` check được khai báo nhưng:
  - Chỉ dùng trong `runCreateTablesPhase` (dòng 403)
  - **Không có** trong `runCreateSequencesPhase`, `runCreateFunctionsPhase`, `runCreateTriggersPhase`, `runCreateIndexesPhase`, `runCreateViewsPhase`
  - Các phase đó dùng `isSameTypeRawDdlMode` (dòng 1812, 1881, 1989, 2092, 2235) — về mặt kỹ thuật tương đương, nhưng inconsistency về naming pattern gây khó debug.

**Mức độ:** 🟡 MEDIUM — inconsistency code, không trực tiếp gây lỗi nhưng thiếu uniform coverage.

---

## 🟡 BUG TRUNG BÌNH (#4) — `buildOracleGeneratedCreateTableSql` dùng sai `targetSchema`

**File:** `MigrationWorker.java` dòng 439

```java
String fallbackCreateSql = buildOracleGeneratedCreateTableSql(table, targetDialect, targetSchema);
```

Gọi `buildOracleGeneratedCreateTableSql` (tên method nhắc đến Oracle) cho cả trường hợp `isPostgresToPostgresDirectDdlMode`. Method này xử lý `OracleDialect` schema prefix (dòng 485–493) — nhưng khi `targetDialect instanceof PostgresDialect`, phần qualify schema Oracle không bao giờ chạy. **Kết quả đúng nhưng tên method gây nhầm lẫn và dễ break khi maintain.**

**Mức độ:** 🟡 MEDIUM — functional đúng nhưng misleading name.

---

## 🟡 BUG TRUNG BÌNH (#5) — `OVERRIDING SYSTEM VALUE` có thể sai trong Oracle → Oracle

**File:** `SqlGenerator.java` dòng 74–76

```java
if (targetDialect instanceof PostgresDialect && requiresPostgresOverridingSystemValue(table)) {
    sql.append(" OVERRIDING SYSTEM VALUE");
}
```

**Phân tích:** Check này đã wrapped trong `targetDialect instanceof PostgresDialect` nên Oracle → Oracle **không bị ảnh hưởng**. Tuy nhiên, `requiresPostgresOverridingSystemValue` chỉ trả về `true` khi `column.isIdentityAlways()` — nhưng trong homogeneous PostgreSQL → PostgreSQL với source là `SERIAL`, `identityAlways = false`. Vậy `OVERRIDING SYSTEM VALUE` **không bao giờ được thêm** trong PG → PG, đúng.

**Nhưng có issue tiềm ẩn:** Nếu source PostgreSQL dùng `GENERATED ALWAYS AS IDENTITY` (Oracle-style identity), `identityAlways=true` → `OVERRIDING SYSTEM VALUE` được thêm. Điều này đúng cho PG 10+ khi dùng identity columns. → **Không phải bug, nhưng cần verify nếu PG schema dùng identity thay vì serial.**

**Mức độ:** 🟡 MEDIUM — đúng cho SERIAL, cần test với identity columns.

---

## 🟠 BUG LỚN (#6) — `MigrationCheckpointStore.loadFromDisk()` không merge mà clear + reload

**File:** `MigrationCheckpointStore.java` dòng 141–152

```java
private void loadFromDisk() {
    properties.clear();   // ← XÓA TẤT CẢ trước
    if (!Files.exists(statePath)) {
        return;
    }
    try (InputStream inputStream = Files.newInputStream(statePath)) {
        properties.load(inputStream);
    } catch (IOException e) { ... }
}
```

**Tác động:** Trong multi-thread data migration (MigrationWorker), mỗi thread ghi checkpoint sau mỗi batch. Nếu 2 process/thread đồng thời ghi file, `clear()` + `load()` sẽ mất dữ liệu của thread kia. Mặc dù có `ConcurrentHashMap` lock per file, nhưng atomicity không đảm bảo ở cấp độ file I/O.

**Mức độ:** 🟠 HIGH — có thể mất checkpoint data trong multi-thread edge case.

---

## 🟡 BUG TRUNG BÌNH (#7) — Oracle Sequence `START WITH` = `LAST_NUMBER - INCREMENT + 1` không đảm bảo continuity

**File:** `MetadataExtractor.java` dòng 823–826

```java
long startValue = Math.max(1L, toLongSaturated(startValueRaw, 1L, "START_VALUE", sequenceName, !hasRawDdl));
```

**Phân tích:** Code tính `startValueRaw = LAST_NUMBER - INCREMENT + 1`. Điều này **không đúng** trong mọi trường hợp:
- `LAST_NUMBER` là giá trị **đã cached** chưa used, không phải last generated value thực sự.
- Nếu sequence dùng `CACHE > 1`, `LAST_NUMBER` sẽ lớn hơn giá trị thực tế đã returned.
- Kết quả: sequence trên target có thể **bỏ qua nhiều giá trị** so với source.

**Mức độ:** 🟡 MEDIUM — gây PK conflict khi target INSERT dùng `seq.NEXTVAL` sau khi migrate explicit values.

---

## 🟡 BUG TRUNG BÌNH (#8) — `SERIAL` PostgreSQL không preserve `GENERATED ALWAYS` semantics

**File:** `PostgresDialect.java` dòng 19–25 và `MigrationWorker.java` dòng 458–459

```java
// PostgresDialect.mapDataType()
if (columnDefinition.isAutoIncrement()) {
    return "BIGSERIAL"; // hoặc "SERIAL"
}
```

```java
ensurePostgresIdentityColumnsAllowExplicitInsert(statement, table, targetDialect);
```

**Phân tích:** Khi source PG table có `GENERATED ALWAYS AS IDENTITY`:
1. `enrichPostgresIdentityGeneration` → `identityAlways = true` → `isAutoIncrement = true`
2. `PostgresDialect.mapDataType()` → map sang `SERIAL` (không phải `GENERATED ALWAYS`)
3. `ensurePostgresIdentityColumnsAllowExplicitInsert` chạy sau CREATE TABLE
4. Nhưng `SERIAL` tạo `GENERATED BY DEFAULT`, nên `SET GENERATED BY DEFAULT` → no-op hoặc ignore

**Hệ quả:** Identity column trên target có thể có behavior khác source — source là `ALWAYS` nhưng target thành `BY DEFAULT`. Mismatch này ảnh hưởng đến `INSERT` và `seq.NEXTVAL`.

**Mức độ:** 🟡 MEDIUM — cần test với PG identity columns (không phải serial).

---

## 🟠 BUG LỚN (#9) — Oracle → Oracle DDL không qualify schema prefix

**File:** `OracleDialect.java` dòng 80–88

```java
public String buildCreateTableSql(TableDefinition table) {
    if (table != null && table.getDdlText() != null && !table.getDdlText().isBlank()) {
        String ddl = remapOracleSchemaPrefix(table.getDdlText(), table.getSourceSchema(), table.getTargetSchema());
        ...
    }
    // Generated DDL path (dòng 90-122) — KHÔNG qualify với schema prefix
    sql.append("CREATE TABLE ").append(quoteIdentifier(table.getTableName().toUpperCase())).append(" (\n");
```

**Phân tích:** Generated DDL (khi không dùng raw DDL) không thêm `schema.table`. Oracle yêu cầu `ALTER SESSION SET CURRENT_SCHEMA` hoặc qualify trong DDL. Nếu target schema khác source schema và connection không set current schema đúng → table được tạo sai schema (thường là schema của connection user).

**Fix có sẵn:** `MigrationWorker` có `qualifyOracleCreateTableSql()` (dòng 498–519) dùng trong `runCreateTablesPhase` (dòng 408–412). Nhưng OracleDialect generated path **không tự qualify**, phụ thuộc hoàn toàn vào caller.

**Mức độ:** 🟠 HIGH — có thể tạo table sai schema khi `targetSchema != sourceSchema`.

---

## 🟡 BUG TRUNG BÌNH (#10) — `copyOnlyTargetEmptyTables` không verify source table empty

**File:** `MigrationWorker.java` dòng 892–897

```java
if (copyOnlyTargetEmptyTables
        && !isTargetTableEmpty(targetPlanConn, table, targetDialect)) {
    publish("  -> Bo qua bang " + table.getTableName()
            + " vi target da co du lieu (copy-only-target-empty). ");
    continue;
}
```

**Phân tích:** Check này chỉ verify target **chưa có dữ liệu**. Nếu source table **trống** mà target **chưa có dữ liệu**, migration skip đúng. Nhưng không có check source empty → target empty: nếu source có dữ liệu và target đã có (từ migration trước), check này không phân biệt được "target có dữ liệu từ migration trước" vs "target có dữ liệu thủ công". Điều này có thể gây skip nhầm trong re-run scenario.

**Mức độ:** 🟡 MEDIUM — hành vi borderline, phụ thuộc use case.

---

## ✅ KHÔNG CÓ BUG

| Component | Assessment |
|---|---|
| `TableDependencySortUtil` | Topological sort FK đúng, circular dep fallback ổn |
| `DataTransferService.bindValueSafely` | Conversion type an toàn, fallback hợp lý |
| `MigrationRetryPolicy` | Exponential backoff, retry/sleep đúng |
| `SqlDialect.buildCreateIndexSql` | Index type mapping đúng, BITMAP skip cho PG OK |
| `MetadataExtractor` FK fallback | `information_schema` fallback cho PG rất tốt |
| `TriggerDefinition` PG → PG | Raw DDL được reuse chính xác |
| `SequenceDefinition` PG → PG | Raw DDL reuse OK |
| `ViewDefinition` PG → PG | Raw DDL + `CREATE OR REPLACE` đúng |

---

## TÓM TẮT RISK MATRIX

| ID | Severity | Impact | Fix Difficulty |
|---|---|---|---|
| **#1** ON CONFLICT DO NOTHING silent row loss | 🔴 CRITICAL | Data loss | Easy |
| **#2** copyNewOnly false + resume → ON CONFLICT | 🟠 HIGH | Unexpected skip | Easy |
| **#6** Checkpoint store clear/reload race | 🟠 HIGH | Lost checkpoint data | Medium |
| **#9** Oracle DDL no schema qualify | 🟠 HIGH | Wrong schema | Medium |
| **#7** Sequence START WITH ≠ actual value | 🟡 MEDIUM | Identity gaps | Hard |
| **#8** SERIAL vs IDENTITY ALWAYS mismatch | 🟡 MEDIUM | Behavior change | Medium |
| **#5** OVERRIDING SYSTEM VALUE edge case | 🟡 MEDIUM | Potential error | Easy |
| **#3** Inconsistent direct DDL naming | 🟡 MEDIUM | Maintainability | Easy |
| **#4** Misleading method name | 🟡 MEDIUM | Maintainability | Easy |
| **#10** copyOnlyTargetEmptyTables edge case | 🟡 MEDIUM | Unexpected skip | Medium |

---

## ƯU TIÊN SỬA LỖI

### Giai đoạn 1 (Ngay lập tức)

1. **#1 + #2** — Thêm parameter `allowDuplicateSkip` vào `buildInsertSql()` hoặc bỏ `ON CONFLICT DO NOTHING` mặc định, chỉ thêm khi `copyNewOnly=true`. Tách rời logic `effectiveCopyNewOnly` trong MigrationWorker ra để không tự động activate conflict checking khi chỉ bật resume.

2. **#9** — Qualify schema trong `OracleDialect.buildCreateTableSql()` khi `targetSchema` được set, không phụ thuộc hoàn toàn vào caller.

### Giai đoạn 2

3. **#6** — Dùng atomic write (rename temp file) thay vì overwrite trực tiếp. Viết ra file temp trước, sau đó rename atomic.

4. **#7** — Lấy actual last sequence value bằng `SELECT last_value FROM sequence_name` sau khi INSERT, hoặc dùng DBMS_SEQUENCES để get real current value thay vì LAST_NUMBER.

### Giai đoạn 3

5. **#8** — Map `GENERATED ALWAYS IDENTITY` sang đúng type PG (`GENERATED ALWAYS AS IDENTITY`), preserve semantics đầy đủ.

6. **#3/#4** — Refactor consistent naming cho same-type direct DDL mode, đổi tên `buildOracleGeneratedCreateTableSql` thành `buildGeneratedCreateTableSql`.
