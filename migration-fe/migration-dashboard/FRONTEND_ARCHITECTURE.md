# Frontend Architecture & File Relationship

## 1. Tổng Quan Cấu Trúc Project

```
migration-fe/migration-dashboard/
├── public/
│   ├── index.html              ← SPA entry point
│   └── favicon.ico
├── src/
│   ├── main.js                 ← Vue 3 bootstrap (createApp → mount)
│   ├── App.vue                 ← Root component: global CSS, fonts, layout shell
│   └── components/
│       ├── HelloWorld.vue      ← Vue CLI stub (UNUSED — artifact)
│       ├── MigrationDashboard.vue    ← Brain: state, API, WebSocket, orchestration
│       ├── DatabaseConfigCard.vue    ← DB connection form (Source / Target)
│       ├── MigrationOptions.vue      ← Migration mode + options panel
│       ├── MigrationProgress.vue     ← Progress bar widget
│       └── MigrationLogConsole.vue   ← Live terminal log widget
├── package.json                ← vue ^3.2, axios, @stomp/stompjs, sockjs-client
├── vue.config.js              ← Dev proxy: /api & /ws-migration → localhost:8080
├── babel.config.js
└── jsconfig.json              ← Path alias: @/* → src/*
```

---

## 2. Component Hierarchy

```
App.vue  (Root — global styles, fonts, gradient background)
└── MigrationDashboard.vue  (SINGLE REAL COMPONENT — holds all state)
    ├── DatabaseConfigCard.vue  (×2 instances: Source & Target)
    ├── MigrationOptions.vue
    ├── MigrationProgress.vue
    └── MigrationLogConsole.vue
```

**Lưu ý:** `HelloWorld.vue` tồn tại trong codebase nhưng **không được import ở bất kỳ đâu** — đây là artifact thừa từ Vue CLI scaffolding, nên bỏ qua.

---

## 3. Luồng Toàn Cục (Full Lifecycle Flow)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Browser loads index.html                                                 │
│  → main.js: createApp(App).mount('#app')                                  │
│     → App.vue: renders <MigrationDashboard />                           │
│        → MigrationDashboard.vue: onMounted()                            │
│           ├── connectWebSocket()    ──► Backend WebSocket               │
│           └── fetchCurrentStatus()  ──► GET /api/migration/status       │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  USER FILLS FORM & CLICKS "Bat dau migration"                             │
│                                                                          │
│  MigrationDashboard.vue                                                 │
│   ├── User nhập form → v-model two-way binding                          │
│   │    ├── sourceDb ref  (→ DatabaseConfigCard.vue)                    │
│   │    ├── targetDb ref  (→ DatabaseConfigCard.vue)                    │
│   │    ├── migrationMode ref  (→ MigrationOptions.vue)                │
│   │    └── options ref  (→ MigrationOptions.vue)                      │
│   │                                                                     │
│   ├── canStartMigration computed                                        │
│   │    → kiểm tra host, databaseName, username của cả source & target  │
│   │                                                                     │
│   └── startMigration()                                                 │
│        ├── isMigrating = true                                           │
│        ├── logs = []                                                    │
│        ├── addLog('Dang gui cau hinh migration...')                     │
│        └── axios.post(migrationStartUrl, payload)                      │
│             payload = {                                                  │
│               source: { type, host, port, databaseName,                 │
│                         username, password },                           │
│               target: { ... },                                          │
│               structureOnly: migrationMode === 'STRUCTURE_ONLY',        │
│               dataOnly: migrationMode === 'DATA_ONLY',                  │
│               options: { truncate, copyNewOnly, limit,                  │
│                          includeTablesCsv, excludeTablesCsv,            │
│                          migrateViews, replaceExistingViews,             │
│                          includeViewsCsv, excludeViewsCsv }             │
│             }                                                           │
│                                                                          │
│  Backend responds 202 ACCEPTED                                           │
│  → Frontend addLog('Server da nhan yeu cau migration.')                │
│  → MigrationWorker starts @Async on backend                            │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                    WebSocket STOMP message arrives
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  handleStatusUpdate(payload) — triggered by STOMP message on /topic/status│
│                                                                          │
│  MigrationStatus payload:                                               │
│  { progress: Int, status: String, message: String,                       │
│    inProgress: Boolean, timestamp: Long }                                │
│                                                                          │
│  Step-by-step logic:                                                    │
│                                                                          │
│  1. payload.progress ≠ undefined                                        │
│     → progress.value = payload.progress  → MigrationProgress bar updates│
│                                                                          │
│  2. payload.inProgress === Boolean                                      │
│     → isMigrating.value = payload.inProgress                            │
│                                                                          │
│  3. payload.message exists                                              │
│     → addLog(payload.message, level)                                    │
│        → logs.value.unshift({ id, time: 'HH:MM:SS', message, levelClass })
│        → MigrationLogConsole auto-scrolls to bottom                     │
│                                                                          │
│  4. payload.status === 'RUNNING' && inProgress not explicitly false    │
│     → isMigrating = true                                                │
│                                                                          │
│  5. payload.progress === 100 || payload.status === 'COMPLETED'          │
│     → isMigrating = false  (completed)                                 │
│                                                                          │
│  6. payload.progress === -1 || payload.status === 'FAILED'             │
│     → isMigrating = false  (failed)                                    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Biểu Đồ Mối Liên Hệ Giữa Các File (File Relationship Diagram)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Browser                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  main.js                                                        │ │
│  │  createApp(App).mount('#app')                                   │ │
│  └──────────────────────────┬────────────────────────────────────┘ │
│                              │                                        │
│                              ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  App.vue                                                        │ │
│  │  ─ Mounts MigrationDashboard                                    │ │
│  │  ─ Global CSS: fonts (Manrope, Space Grotesk), gradient bg       │ │
│  │  ─ CSS variables: --bg-start, --bg-end, --panel, --ink          │ │
│  └──────────────────────────┬────────────────────────────────────┘ │
│                              │ <MigrationDashboard />               │
│                              ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  MigrationDashboard.vue  (SINGLE SOURCE OF TRUTH)              │ │
│  │                                                                 │ │
│  │  STATE (ref / reactive — no store, no Vuex/Pinia)              │ │
│  │  ─ sourceDb, targetDb         : DB config objects               │ │
│  │  ─ migrationMode             : 'ALL' | 'STRUCTURE_ONLY' | ...  │ │
│  │  ─ options                   : full options object             │ │
│  │  ─ sourceTestState, targetTestState : connection test results  │ │
│  │  ─ progress, isMigrating     : migration running state         │ │
│  │  ─ logs                       : log entries array               │ │
│  │  ─ wsConnected                : WebSocket connection status     │ │
│  │  ─ stompClient                : STOMP client reference          │ │
│  │                                                                 │ │
│  │  COMPUTED: canStartMigration  (requires all mandatory fields)   │ │
│  │                                                                 │ │
│  │  NETWORK                                                         │ │
│  │  ─ axios.get(migrationStatusUrl)    → GET /api/migration/status│ │
│  │  ─ axios.post(migrationStartUrl)   → POST /api/migration/start │ │
│  │  ─ axios.post(testConnectionUrl)    → POST /api/migration/     │ │
│  │                                        test-connection          │ │
│  │  ─ new Client({ webSocketFactory: () => new SockJS(wsEndpoint) })│ │
│  │     └── client.subscribe(wsTopic, handleStatusUpdate)           │ │
│  │        wsTopic = /topic/status (configurable via env)           │ │
│  │                                                                 │ │
│  │  LIFECYCLE                                                        │ │
│  │  ─ onMounted: fetchCurrentStatus() + connectWebSocket()         │ │
│  │  ─ onUnmounted: stompClient.deactivate()                        │ │
│  │                                                                 │ │
│  │  CHILD COMPONENTS (props down / events up)                    │ │
│  │  ┌────────────────────────────────────────────────────────────┐ │ │
│  │  │  <DatabaseConfigCard v-model="sourceDb"                  │ │ │
│  │  │      :test-state="sourceTestState"                       │ │ │
│  │  │      @test-connection="testConnection('source')" />       │ │ │
│  │  └────────────────────────────────────────────────────────────┘ │ │
│  │  ┌────────────────────────────────────────────────────────────┐ │ │
│  │  │  <DatabaseConfigCard v-model="targetDb"                   │ │ │
│  │  │      :test-state="targetTestState"                       │ │ │
│  │  │      @test-connection="testConnection('target')" />       │ │ │
│  │  └────────────────────────────────────────────────────────────┘ │ │
│  │  ┌────────────────────────────────────────────────────────────┐ │ │
│  │  │  <MigrationOptions                                        │ │ │
│  │  │      v-model:migration-mode="migrationMode"             │ │ │
│  │  │      v-model:options="options" />                        │ │ │
│  │  └────────────────────────────────────────────────────────────┘ │ │
│  │  ┌────────────────────────────────────────────────────────────┐ │ │
│  │  │  <MigrationProgress :progress="progress"                  │ │ │
│  │  │      :is-migrating="isMigrating" />                      │ │ │
│  │  └────────────────────────────────────────────────────────────┘ │ │
│  │  ┌────────────────────────────────────────────────────────────┐ │ │
│  │  │  <MigrationLogConsole :logs="logs" />                     │ │ │
│  │  └────────────────────────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  DatabaseConfigCard.vue  (Stateless presentation)                   │
│                                                                      │
│  Props: title, badge, databaseNameLabel, modelValue,                │
│         isTesting, testState                                          │
│  Emits: update:modelValue, test-connection                          │
│                                                                      │
│  Internal: localConfig = reactive({ ...props.modelValue })          │
│  Watch modelValue → Object.assign(localConfig)  (parent → child)  │
│  Watch localConfig → emit('update:modelValue', {...}) (child → parent)│
│                                                                      │
│  Form fields: type (Oracle/PG), host, port,                         │
│              databaseName, username, password                        │
│  Buttons: [Test connection]                                         │
│  Status: colored message (success/error/pending)                   │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  MigrationOptions.vue  (Stateless presentation)                     │
│                                                                      │
│  Props: migrationMode (String), options (Object)                    │
│  Emits: update:migrationMode, update:options                       │
│                                                                      │
│  Local: localMode = computed({ get, set }) — bidirectional        │
│         localOptions = reactive({ ...props.options })              │
│  Watch options → Object.assign(localOptions)                       │
│  Watch localOptions → emit('update:options', {...})               │
│                                                                      │
│  Sections:                                                           │
│  ── Migration mode (radio): ALL | STRUCTURE_ONLY | DATA_ONLY      │
│  ── Data options: truncate, copyNewOnly, limit,                    │
│                    includeTablesCsv, excludeTablesCsv                │
│  ── View options: migrateViews, replaceExistingViews,              │
│                    includeViewsCsv, excludeViewsCsv                  │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  MigrationProgress.vue  (Stateless display only)                     │
│                                                                      │
│  Props: progress (Number), isMigrating (Boolean)                    │
│  Emits: nothing                                                      │
│                                                                      │
│  Computed: normalizedProgress = clamp(progress, 0, 100)            │
│                                                                      │
│  Visibility: v-if="isMigrating || progress > 0"                     │
│  Style: animated fill bar, gradient (#1e9878 → #2d7ef7)           │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  MigrationLogConsole.vue  (Stateless display only)                   │
│                                                                      │
│  Props: logs (Array)                                                │
│  Emits: nothing                                                      │
│                                                                      │
│  Auto-scroll: watch(logs.length) → nextTick → scrollTop = height   │
│                                                                      │
│  Theme: dark terminal (#10161f bg, monospace font)                  │
│  Entry format: [HH:MM:SS] message                                   │
│  Colors:                                                             │
│    level-info    → green timestamp                                  │
│    level-error   → red timestamp + red message                      │
│    level-success → mint green timestamp + message                  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. Network Communication Flow

```
Browser (localhost:3000)                              Backend (localhost:8080)
───────────────────────                               ──────────────────────

1. STOMP CONNECT /ws-migration
   (SockJS fallback)
   ─────────────────────────────────────────────────▶  WebSocketConfig
                                                       registerStompEndpoint
                                                       enableSimpleBroker(/topic)

2. STOMP SUBSCRIBE /topic/status
   ─────────────────────────────────────────────────▶

3. GET /api/migration/status
   ─────────────────────────────────────────────────▶  MigrationController
   ◀──────────────────────────────────────────────────  MigrationStatus JSON
                                                       { progress, status, message,
                                                         inProgress, timestamp }

4. POST /api/migration/start
   body: MigrationRequest JSON
   ─────────────────────────────────────────────────▶  MigrationController
   ◀────────────────────────────────────────────────── 202 ACCEPTED
                                                       { status: "ACCEPTED",
                                                         message: "..." }

5. STOMP SEND /topic/status
   ◀──────────────────────────────────────────────────  MigrationWebWorkerService
   (MigrationStatus JSON: progress, status, message)      broadcastStatus()
      │
      │ handleStatusUpdate(payload)
      │  → progress.value = payload.progress
      │  → addLog(payload.message)
      │  → MigrationProgress bar animates
      │
   ◀──────────────────────────────────────────────────  (more status updates)
   (multiple messages during migration)

6. STOMP SEND /topic/status (final)
   ◀──────────────────────────────────────────────────  progress = 100
   { progress: 100, status: "COMPLETED", inProgress: false }
      │
      │ handleStatusUpdate: isMigrating = false
      │  → Start button re-enabled
      │  → Progress bar shows 100%

───────────────────────────────────────────────────────────────────────────

Dev Proxy (vue.config.js):
  /api/*            ──────▶  http://localhost:8080/api/*
  /ws-migration/*   ──────▶  http://localhost:8080/ws-migration/*

Env variables (with fallbacks):
  VUE_APP_API_BASE_URL        (default: '')
  VUE_APP_WS_ENDPOINT         (default: '/ws-migration')
  VUE_APP_WS_TOPIC             (default: '/topic/status')
```

---

## 6. State Management Architecture

```
MigrationDashboard.vue  — SINGLE SOURCE OF TRUTH
═════════════════════════════════════════════════

┌─────────────────────────────────────────────────────────────────────┐
│  Local State (ref / reactive)                                       │
├────────────────────────┬────────────────────────────────────────────┤
│ sourceDb               │ { type, host, port, databaseName,           │
│                        │   username, password }                     │
├────────────────────────┼────────────────────────────────────────────┤
│ targetDb               │ { type, host, port, databaseName,           │
│                        │   username, password }                     │
├────────────────────────┼────────────────────────────────────────────┤
│ migrationMode          │ 'ALL' | 'STRUCTURE_ONLY' | 'DATA_ONLY'      │
├────────────────────────┼────────────────────────────────────────────┤
│ options                │ { truncate, copyNewOnly, limit,              │
│                        │   includeTablesCsv, excludeTablesCsv,       │
│                        │   migrateViews, replaceExistingViews,       │
│                        │   includeViewsCsv, excludeViewsCsv }       │
├────────────────────────┼────────────────────────────────────────────┤
│ sourceTestState        │ { status: 'idle'|'pending'|'success'|'error',│
│                        │   message: String }                        │
├────────────────────────┼────────────────────────────────────────────┤
│ targetTestState        │ { status: 'idle'|'pending'|'success'|'error',│
│                        │   message: String }                        │
├────────────────────────┼────────────────────────────────────────────┤
│ progress               │ 0–100 (Number)                              │
├────────────────────────┼────────────────────────────────────────────┤
│ isMigrating           │ Boolean                                     │
├────────────────────────┼────────────────────────────────────────────┤
│ logs                  │ Array<{ id, time, message, levelClass }>   │
├────────────────────────┼────────────────────────────────────────────┤
│ wsConnected           │ Boolean                                     │
├────────────────────────┼────────────────────────────────────────────┤
│ stompClient           │ Client (STOMP instance) or null             │
└────────────────────────┴────────────────────────────────────────────┘

PROPS DOWN  (parent → child)
═════════════════════════════
  MigrationDashboard
   ├── :progress="progress"               → MigrationProgress
   ├── :is-migrating="isMigrating"      → MigrationProgress
   ├── :logs="logs"                      → MigrationLogConsole
   ├── v-model="sourceDb"                → DatabaseConfigCard (Source)
   │    ├── :test-state="sourceTestState"
   │    └── @test-connection="testConnection('source')"
   ├── v-model="targetDb"                → DatabaseConfigCard (Target)
   │    ├── :test-state="targetTestState"
   │    └── @test-connection="testConnection('target')"
   ├── v-model:migration-mode="migrationMode"
   │    └── v-model:options="options"   → MigrationOptions

EVENTS UP    (child → parent)
═════════════════════════════
  DatabaseConfigCard
   └── emit('test-connection')          → testConnection('source'|'target')

  MigrationOptions
   └── emit('update:migrationMode')    → mutationMode.value = ...
   └── emit('update:options')          → options.value = ...

  NO EVENTS from: MigrationProgress, MigrationLogConsole
  (chúng chỉ hiển thị props, không emit gì)
```

---

## 7. WebSocket STOMP Setup Chi Tiết

```javascript
// MigrationDashboard.vue — connectWebSocket()

const client = new Client({
  reconnectDelay: 2500,                              // Tự động reconnect sau 2.5s
  webSocketFactory: () => new SockJS(wsEndpoint)     // SockJS fallback → /ws-migration
});

client.onConnect = () => {
  wsConnected.value = true;
  addLog('Da ket noi WebSocket thanh cong.', 'success');

  fetchCurrentStatus(true);  // Lấy trạng thái hiện tại ngay khi connect

  // Subscribe vào /topic/status — backend gửi MigrationStatus tại đây
  client.subscribe(wsTopic, (message) => {
    const payload = JSON.parse(message.body);
    handleStatusUpdate(payload);  // Cập nhật progress + logs
  });
};

client.onStompError = (frame) => {
  addLog(`STOMP error: ${frame.headers.message}`, 'error');
};

client.onWebSocketClose = () => {
  wsConnected.value = false;
  addLog('WebSocket dong ket noi, dang thu ket noi lai...', 'error');
};

client.activate();   // Bắt đầu kết nối
stompClient.value = client;
```

**STOMP Frame flow:**
```
Client → Server: CONNECT
Client → Server: SUBSCRIBE /topic/status
Client ← Server: MESSAGE /topic/status  (MigrationStatus JSON)
Client ← Server: MESSAGE /topic/status  (nhiều lần trong quá trình migration)
```

---

## 8. Two-Way Data Sync Pattern (v-model)

```
MigrationDashboard (parent)                    DatabaseConfigCard / MigrationOptions (child)
───────────────────────────                    ──────────────────────────────────────────

sourceDb = ref({ type, host, ... })            localConfig = reactive({ ...props.modelValue })
                                                  │
                                                  │ watch(() => props.modelValue, ...)
                                                  │   Object.assign(localConfig, nextValue)
                                                  │   ↑ parent changes → child updates
                                                  │
          v-model="sourceDb"
          ──────────────────────────────────────▶
          (shorthand for :model-value + @update:model-value)
                                                  │
                                                  │ watch(localConfig, ...)
                                                  │   emit('update:modelValue', {...})
                                                  │   ↑ child changes → parent updates
                                                  │
          ←──────────────────────────────────────
          @update:modelValue → sourceDb.value = $event

Tương tự cho:
  v-model:migration-mode="migrationMode"  → computed getter/setter
  v-model:options="options"              → reactive + watch
```

---

## 9. Error Handling Patterns

```
addLog() Error Classification
═════════════════════════════

error?.response?.data?.message    → ưu tiên message từ server
error?.response?.data             → fallback message từ response body
error?.message                    → fallback message từ JS Error
String(message)                   → final fallback

testConnection() Error Flow
════════════════════════════

POST /api/migration/test-connection
├── 200 OK  → state = { status: 'success', message }
│            addLog(`[${role}] ${message}`, 'success')
└── Error   → state = { status: 'error', message: String(msg) }
             addLog(`[${role}] ${message}`, 'error')

startMigration() Error Flow
════════════════════════════

POST /api/migration/start
├── 2xx OK  → addLog(response.data.message)
└── Error   → addLog(`Loi goi API: ${errorMessage}`, 'error')
              isMigrating = false

WebSocket Error Flow
════════════════════════

STOMP ERROR frame  → addLog(`STOMP error: ...`, 'error')
WebSocket close    → wsConnected = false, addLog('...', 'error')
                    (client tự reconnect sau 2500ms)
JSON parse error   → addLog(`Khong parse duoc...`, 'error')
```

---

## 10. API Payload Reference

### POST `/api/migration/start` — Request Body

```json
{
  "source": {
    "type": "ORACLE",
    "host": "localhost",
    "port": 1521,
    "databaseName": "ORCL",
    "username": "system",
    "password": "..."
  },
  "target": {
    "type": "POSTGRESQL",
    "host": "127.0.0.1",
    "port": 5432,
    "databaseName": "migration_db",
    "username": "postgres",
    "password": "..."
  },
  "structureOnly": false,
  "dataOnly": false,
  "options": {
    "truncate": false,
    "copyNewOnly": false,
    "limit": 0,
    "includeTablesCsv": "",
    "excludeTablesCsv": "",
    "migrateViews": false,
    "replaceExistingViews": false,
    "includeViewsCsv": "",
    "excludeViewsCsv": ""
  }
}
```

### GET `/api/migration/status` — Response Body

```json
{
  "progress": 67,
  "status": "RUNNING",
  "message": "Bang EMPLOYEES: copied=5000, skipped=200",
  "inProgress": true,
  "timestamp": 1740754800000
}
```

### POST `/api/migration/test-connection` — Response Body

```json
// Success
{ "success": true, "message": "Ket noi thanh cong toi ORACLE @ localhost:1521" }

// Failure
{ "success": false, "message": "Loi test connection: ..." }
```

---

## 11. Key Technical Observations

| # | Detail | Note |
|---|--------|------|
| 1 | **No router** | Single-page app, không dùng `vue-router` |
| 2 | **No Vuex/Pinia** | Tất cả state tập trung trong `MigrationDashboard.vue` |
| 3 | **No .env file** | Dùng `process.env.VUE_APP_*` với fallback defaults |
| 4 | **SockJS fallback** | `sockjs-client` cho trình duyệt không hỗ trợ native WebSocket |
| 5 | **STOMP protocol** | Dùng `@stomp/stompjs` v7.3.0, giao thức truyền message trên WebSocket |
| 6 | **Two-way sync** | `v-model` + `watch` + `reactive` — mô hình manual two-way binding |
| 7 | **Auto-scroll** | `watch(logs.length)` + `nextTick` + `scrollTop = scrollHeight` |
| 8 | **Bilingual UI** | Tất cả label/placeholder/log message bằng tiếng Việt |
| 9 | **HelloWorld unused** | Orphan Vue CLI scaffold artifact, có thể xóa |
| 10 | **No TypeScript** | Tất cả `.vue` / `.js` là plain JavaScript / Vue 3 Composition API |
