<template>
  <div class="dashboard">
    <header class="hero">
      <p class="eyebrow">DATABASE TOOLING</p>
      <h1>Migration Database Dashboard</h1>
      <p class="subtitle">
        Migrate Data and Structure between Databases with Ease. Monitor Progress, View Logs, and Manage Credentials in One Place.
      </p>
      <div class="status-pill" :class="{ connected: wsConnected }">
        {{ wsConnected ? 'WebSocket Connected' : 'WebSocket Disconnected' }}
      </div>
    </header>

    <section class="layout-grid">
      <DatabaseConfigCard
        title="Source Database"
        badge="Nguon"
        database-name-label="Ten DB / SID"
        v-model="sourceDb"
        :is-testing="sourceTestState.status === 'pending'"
        :test-state="sourceTestState"
        :saved-credentials="savedCredentials"
        :selected-saved-credential-id="selectedSourceCredentialId"
        :is-saving-credential="sourceSaveState.status === 'pending'"
        :save-state="sourceSaveState"
        @test-connection="testConnection('source')"
        @save-credential="saveCredential('source', $event)"
        @select-saved-credential="selectSavedCredential('source', $event)"
      />

      <DatabaseConfigCard
        title="Target Database"
        badge="Dich"
        database-name-label="Ten DB"
        v-model="targetDb"
        :is-testing="targetTestState.status === 'pending'"
        :test-state="targetTestState"
        :saved-credentials="savedCredentials"
        :selected-saved-credential-id="selectedTargetCredentialId"
        :is-saving-credential="targetSaveState.status === 'pending'"
        :save-state="targetSaveState"
        @test-connection="testConnection('target')"
        @save-credential="saveCredential('target', $event)"
        @select-saved-credential="selectSavedCredential('target', $event)"
      />
    </section>

    <section class="options-shell">
      <MigrationOptions
        v-model:migration-mode="migrationMode"
        v-model:options="options"
        v-model:retry="retry"
        v-model:resume="resume"
      />
    </section>

    <section class="execution-shell">
      <div class="action-row">
        <button class="start-btn" :disabled="!canStartMigration" @click="startMigration">
          {{ isMigrating ? 'Dang migration...' : 'Bat dau migration' }}
        </button>

        <span class="helper">REST endpoint: {{ migrationStartUrl }}</span>
      </div>

      <MigrationProgress :progress="progress" :is-migrating="isMigrating" />
      <MigrationLogConsole :logs="logs" />
    </section>
  </div>
</template>

<script setup>
import axios from 'axios';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client/dist/sockjs';
import { computed, onMounted, onUnmounted, ref } from 'vue';
import DatabaseConfigCard from './DatabaseConfigCard.vue';
import MigrationLogConsole from './MigrationLogConsole.vue';
import MigrationOptions from './MigrationOptions.vue';
import MigrationProgress from './MigrationProgress.vue';

const apiBaseUrl = process.env.VUE_APP_API_BASE_URL || '';
const wsEndpoint = process.env.VUE_APP_WS_ENDPOINT || '/ws-migration';
const wsTopic = process.env.VUE_APP_WS_TOPIC || '/topic/status';
const migrationStartUrl = `${apiBaseUrl}/api/migration/start`;
const migrationStatusUrl = `${apiBaseUrl}/api/migration/status`;
const testConnectionUrl = `${apiBaseUrl}/api/migration/test-connection`;
const credentialStorageUrl = `${apiBaseUrl}/api/migration/credentials`;

const isMigrating = ref(false);
const progress = ref(0);
const logs = ref([]);
const wsConnected = ref(false);
const stompClient = ref(null);

const sourceDb = ref({
  type: 'ORACLE',
  jdbcUrl: '',
  host: 'localhost',
  port: 1521,
  databaseName: 'ORCL',
  schemaName: '',
  username: 'system',
  password: ''
});

const targetDb = ref({
  type: 'POSTGRESQL',
  jdbcUrl: '',
  host: '127.0.0.1',
  port: 5432,
  databaseName: 'migration_db',
  schemaName: 'public',
  username: 'postgres',
  password: ''
});

const migrationMode = ref('ALL');
const options = ref({
  truncate: false,
  copyNewOnly: false,
  copyOnlyTargetEmptyTables: false,
  limit: 0,
  includeTablesCsv: '',
  excludeTablesCsv: '',
  migrateSequences: false,
  migrateIndexes: false,
  migrateFunctions: false,
  migrateTriggers: false,
  // View migration options
  migrateViews: false,
  replaceExistingViews: false,
  includeViewsCsv: '',
  excludeViewsCsv: ''
});

const retry = ref({
  enabled: false,
  maxAttempts: 3,
  initialDelayMs: 2000,
  backoffMultiplier: 2.0
});

const resume = ref({
  enabled: false,
  stateFile: '.migration-resume.properties',
  reset: false
});

const sourceTestState = ref({ status: 'idle', message: '' });
const targetTestState = ref({ status: 'idle', message: '' });
const sourceSaveState = ref({ status: 'idle', message: '' });
const targetSaveState = ref({ status: 'idle', message: '' });
const savedCredentials = ref([]);
const selectedSourceCredentialId = ref('');
const selectedTargetCredentialId = ref('');

const normalizeCredentialId = (value) => {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
};

const hasJdbcUrl = (config) => {
  return Boolean(config?.jdbcUrl && String(config.jdbcUrl).trim());
};

const hasConnectionIdentity = (config) => {
  if (hasJdbcUrl(config)) {
    return true;
  }

  return Boolean(
    config?.host &&
      config?.databaseName &&
      Number(config?.port) > 0
  );
};

const canStartMigration = computed(() => {
  return Boolean(
    !isMigrating.value &&
      hasConnectionIdentity(sourceDb.value) &&
      sourceDb.value.username &&
      hasConnectionIdentity(targetDb.value) &&
      targetDb.value.username
  );
});

const addLog = (message, level = 'info') => {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  const time = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
  let levelClass = 'level-info';

  if (level === 'error') {
    levelClass = 'level-error';
  }
  if (level === 'success') {
    levelClass = 'level-success';
  }

  logs.value.push({
    id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    time,
    message,
    levelClass
  });
};

const handleStatusUpdate = (payload) => {
  if (typeof payload.progress === 'number') {
    progress.value = payload.progress;
  }

  if (typeof payload.inProgress === 'boolean') {
    isMigrating.value = payload.inProgress;
  }

  if (payload.message) {
    const level = payload.level || payload.type || 'info';
    addLog(payload.message, String(level).toLowerCase());
  }

  if (payload.status === 'RUNNING' && typeof payload.inProgress !== 'boolean') {
    isMigrating.value = true;
  }

  if (payload.progress === 100 || payload.status === 'COMPLETED') {
    isMigrating.value = false;
  }

  if (payload.progress === -1 || payload.status === 'FAILED') {
    isMigrating.value = false;
  }
};

const fetchCurrentStatus = async (silent = false) => {
  try {
    const response = await axios.get(migrationStatusUrl);
    if (response?.data) {
      handleStatusUpdate(response.data);
      if (!silent) {
        addLog('Da dong bo trang thai hien tai tu server.');
      }
    }
  } catch (error) {
    if (!silent) {
      addLog(`Khong lay duoc trang thai hien tai: ${error.message}`, 'error');
    }
  }
};

const connectWebSocket = () => {
  const client = new Client({
    reconnectDelay: 2500,
    webSocketFactory: () => new SockJS(wsEndpoint)
  });

  client.onConnect = () => {
    wsConnected.value = true;
    addLog('Da ket noi WebSocket thanh cong.', 'success');
    fetchCurrentStatus(true);

    client.subscribe(wsTopic, (message) => {
      try {
        const payload = JSON.parse(message.body);
        handleStatusUpdate(payload);
      } catch (error) {
        addLog(`Khong parse duoc thong diep WebSocket: ${error}`, 'error');
      }
    });
  };

  client.onStompError = (frame) => {
    addLog(`STOMP error: ${frame.headers.message || 'Unknown error'}`, 'error');
  };

  client.onWebSocketClose = () => {
    wsConnected.value = false;
    addLog('WebSocket dong ket noi, dang thu ket noi lai...', 'error');
  };

  client.activate();
  stompClient.value = client;
};

const startMigration = async () => {
  if (!canStartMigration.value) {
    return;
  }

  isMigrating.value = true;
  progress.value = 0;
  logs.value = [];
  addLog('Dang gui cau hinh migration len server...');

  const payload = {
    source: { ...sourceDb.value },
    target: { ...targetDb.value },
    structureOnly: migrationMode.value === 'STRUCTURE_ONLY',
    dataOnly: migrationMode.value === 'DATA_ONLY',
    options: { ...options.value },
    retry: { ...retry.value },
    resume: { ...resume.value }
  };

  try {
    const response = await axios.post(migrationStartUrl, payload);
    addLog(response?.data?.message || 'Server da nhan yeu cau migration.');
  } catch (error) {
    const errorMessage = error?.response?.data?.message || error?.response?.data || error.message;
    addLog(`Loi goi API: ${errorMessage}`, 'error');
    isMigrating.value = false;
  }
};

const testConnection = async (role) => {
  const config = role === 'source' ? sourceDb.value : targetDb.value;
  const state = role === 'source' ? sourceTestState : targetTestState;
  const roleLabel = role === 'source' ? 'Source' : 'Target';

  state.value = { status: 'pending', message: 'Dang test...' };

  try {
    const response = await axios.post(testConnectionUrl, { ...config });
    const message = response?.data?.message || `Ket noi ${roleLabel} thanh cong.`;
    state.value = { status: 'success', message };
    addLog(`[${roleLabel}] ${message}`, 'success');
  } catch (error) {
    const message =
      error?.response?.data?.message ||
      error?.response?.data ||
      error?.message ||
      `Ket noi ${roleLabel} that bai.`;
    state.value = { status: 'error', message: String(message) };
    addLog(`[${roleLabel}] ${message}`, 'error');
  }
};

const normalizeDatabaseConfig = (rawConfig) => {
  if (!rawConfig) {
    return null;
  }

  return {
    type: rawConfig.type || 'POSTGRESQL',
    jdbcUrl: rawConfig.jdbcUrl || '',
    host: rawConfig.host || '',
    port: Number(rawConfig.port) || 0,
    databaseName: rawConfig.databaseName || '',
    schemaName: rawConfig.schemaName || '',
    username: rawConfig.username || '',
    password: rawConfig.password || ''
  };
};

const fetchSavedCredentials = async (silent = false) => {
  try {
    const response = await axios.get(credentialStorageUrl);
    const payload = Array.isArray(response?.data) ? response.data : [];
    savedCredentials.value = payload;
    if (!silent) {
      addLog(`Da tai ${payload.length} credential da luu.`, 'success');
    }
  } catch (error) {
    const message = error?.response?.data?.message || error?.message || 'Khong tai duoc credentials da luu.';
    if (!silent) {
      addLog(message, 'error');
    }
  }
};

const saveCredential = async (role, payload) => {
  const state = role === 'source' ? sourceSaveState : targetSaveState;
  const roleLabel = role === 'source' ? 'Source' : 'Target';

  state.value = { status: 'pending', message: 'Dang luu...' };

  try {
    const response = await axios.post(credentialStorageUrl, {
      name: payload.name,
      config: payload.config
    });

    const saved = response?.data;
    state.value = {
      status: 'success',
      message: `Da luu credential "${saved?.displayName || payload.name}".`
    };

    if (role === 'source') {
      selectedSourceCredentialId.value = normalizeCredentialId(saved?.id);
    } else {
      selectedTargetCredentialId.value = normalizeCredentialId(saved?.id);
    }

    await fetchSavedCredentials(true);
    addLog(`[${roleLabel}] Da luu credential de tai su dung.`, 'success');
  } catch (error) {
    const message =
      error?.response?.data?.message ||
      error?.response?.data ||
      error?.message ||
      'Khong the luu credential.';

    state.value = { status: 'error', message: String(message) };
    addLog(`[${roleLabel}] ${message}`, 'error');
  }
};

const selectSavedCredential = (role, credentialId) => {
  const normalizedCredentialId = normalizeCredentialId(credentialId);
  if (!normalizedCredentialId) {
    if (role === 'source') {
      selectedSourceCredentialId.value = '';
    } else {
      selectedTargetCredentialId.value = '';
    }
    return;
  }

  const selected = savedCredentials.value.find(
    (item) => normalizeCredentialId(item.id) === normalizedCredentialId
  );
  if (!selected) {
    addLog('Khong tim thay credential da chon.', 'error');
    return;
  }

  const config = normalizeDatabaseConfig(selected.databaseConfig);
  if (!config) {
    addLog('Credential da chon khong hop le.', 'error');
    return;
  }

  if (role === 'source') {
    sourceDb.value = { ...config };
    selectedSourceCredentialId.value = normalizeCredentialId(selected.id);
  } else {
    targetDb.value = { ...config };
    selectedTargetCredentialId.value = normalizeCredentialId(selected.id);
  }

  addLog(`[${role === 'source' ? 'Source' : 'Target'}] Da nap credential "${selected.displayName}".`, 'success');
};

onMounted(() => {
  fetchCurrentStatus(true);
  fetchSavedCredentials(true);
  connectWebSocket();
});

onUnmounted(() => {
  if (stompClient.value) {
    stompClient.value.deactivate();
  }
});
</script>

<style scoped>
.dashboard {
  margin: 0 auto;
  max-width: 1240px;
}

.hero {
  animation: rise-in 0.5s ease-out;
  margin-bottom: 18px;
}

.eyebrow {
  color: var(--accent);
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0.11em;
  margin: 0;
}

h1 {
  font-family: 'Space Grotesk', sans-serif;
  font-size: clamp(1.7rem, 4vw, 2.5rem);
  line-height: 1.12;
  margin: 8px 0;
}

.subtitle {
  color: var(--ink-soft);
  line-height: 1.5;
  margin: 0;
  max-width: 760px;
}

.status-pill {
  background: var(--error-bg);
  border-radius: 999px;
  color: var(--error-text);
  display: inline-flex;
  font-size: 0.81rem;
  font-weight: 700;
  margin-top: 11px;
  padding: 7px 12px;
}

.status-pill.connected {
  background: var(--ok-bg);
  color: var(--ok-text);
}

.layout-grid {
  display: grid;
  align-items: stretch;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 12px;
}

.options-shell {
  margin-bottom: 12px;
}

.execution-shell {
  background: linear-gradient(180deg, var(--surface) 0%, var(--surface-alt) 100%);
  border: 1px solid var(--line);
  border-radius: 18px;
  box-shadow: 0 12px 28px var(--shadow-soft);
  padding: 14px;
}

.action-row {
  align-items: center;
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin: 2px 0 10px;
}

.start-btn {
  background: linear-gradient(102deg, #e56d2e, #c94b18);
  border: 0;
  border-radius: 12px;
  color: #fff;
  cursor: pointer;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 0.98rem;
  font-weight: 700;
  letter-spacing: 0.02em;
  min-height: 46px;
  min-width: 228px;
  padding: 10px 18px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.start-btn:hover:enabled {
  box-shadow: 0 10px 20px #c38f62;
  transform: translateY(-1px);
}

.start-btn:disabled {
  background: #9a8870;
  cursor: not-allowed;
}

.helper {
  color: var(--ink-soft);
  font-size: 0.82rem;
  font-weight: 700;
}

@keyframes rise-in {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 900px) {
  .layout-grid {
    grid-template-columns: 1fr;
  }

  .execution-shell {
    padding: 12px;
  }

  .start-btn {
    width: 100%;
  }
}

@media (max-width: 1100px) {
  .action-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .helper {
    width: 100%;
  }

  .start-btn {
    min-width: 0;
  }
}
</style>
