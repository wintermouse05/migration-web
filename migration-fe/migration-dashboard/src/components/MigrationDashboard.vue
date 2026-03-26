<template>
  <div class="dashboard">
    <header class="hero">
      <p class="eyebrow">DATABASE TOOLING</p>
      <h1>Migration Database Dashboard</h1>
      <p class="subtitle">
        Cau hinh ket noi DB, gui lenh migration qua REST API va theo doi log theo thoi gian thuc bang WebSocket.
      </p>
      <div class="status-pill" :class="{ connected: wsConnected }">
        {{ wsConnected ? 'WebSocket dang ket noi' : 'WebSocket dang ngat ket noi' }}
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
        @test-connection="testConnection('source')"
      />

      <DatabaseConfigCard
        title="Target Database"
        badge="Dich"
        database-name-label="Ten DB"
        v-model="targetDb"
        :is-testing="targetTestState.status === 'pending'"
        :test-state="targetTestState"
        @test-connection="testConnection('target')"
      />
    </section>

    <MigrationOptions
      v-model:migration-mode="migrationMode"
      v-model:options="options"
    />

    <section class="action-row">
      <button class="start-btn" :disabled="!canStartMigration" @click="startMigration">
        {{ isMigrating ? 'Dang migration...' : 'Bat dau migration' }}
      </button>

      <span class="helper">REST endpoint: {{ migrationStartUrl }}</span>
    </section>

    <MigrationProgress :progress="progress" :is-migrating="isMigrating" />
    <MigrationLogConsole :logs="logs" />
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

const isMigrating = ref(false);
const progress = ref(0);
const logs = ref([]);
const wsConnected = ref(false);
const stompClient = ref(null);

const sourceDb = ref({
  type: 'ORACLE',
  host: 'localhost',
  port: 1521,
  databaseName: 'ORCL',
  username: 'system',
  password: ''
});

const targetDb = ref({
  type: 'POSTGRESQL',
  host: '127.0.0.1',
  port: 5432,
  databaseName: 'migration_db',
  username: 'postgres',
  password: ''
});

const migrationMode = ref('ALL');
const options = ref({
  truncate: false,
  copyNewOnly: false,
  limit: 0,
  includeTablesCsv: '',
  excludeTablesCsv: ''
});

const sourceTestState = ref({ status: 'idle', message: '' });
const targetTestState = ref({ status: 'idle', message: '' });

const canStartMigration = computed(() => {
  return Boolean(
    !isMigrating.value &&
      sourceDb.value.host &&
      sourceDb.value.databaseName &&
      sourceDb.value.username &&
      targetDb.value.host &&
      targetDb.value.databaseName &&
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
    options: { ...options.value }
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

onMounted(() => {
  fetchCurrentStatus(true);
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
  max-width: 1080px;
}

.hero {
  animation: rise-in 0.5s ease-out;
  margin-bottom: 18px;
}

.eyebrow {
  color: #1e9878;
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
  color: #4b6070;
  line-height: 1.5;
  margin: 0;
  max-width: 760px;
}

.status-pill {
  background: #f6d8d8;
  border-radius: 999px;
  color: #953535;
  display: inline-flex;
  font-size: 0.81rem;
  font-weight: 700;
  margin-top: 11px;
  padding: 7px 12px;
}

.status-pill.connected {
  background: #dbf3e8;
  color: #16674d;
}

.layout-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 14px;
}

.action-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 16px 0 8px;
}

.start-btn {
  background: linear-gradient(102deg, #1f8f72, #226ad8);
  border: 0;
  border-radius: 12px;
  color: #fff;
  cursor: pointer;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1rem;
  font-weight: 700;
  padding: 11px 16px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.start-btn:hover:enabled {
  box-shadow: 0 12px 24px rgba(31, 143, 114, 0.2);
  transform: translateY(-1px);
}

.start-btn:disabled {
  background: #a4acb5;
  cursor: not-allowed;
}

.helper {
  color: #5c6e7d;
  font-size: 0.84rem;
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
}
</style>
