<template>
  <section class="card">
    <header class="card-header">
      <h2>{{ title }}</h2>
      <span class="card-badge">{{ badge }}</span>
    </header>

    <section class="credential-panel">
      <p class="panel-title">Quick credentials</p>

      <div class="credential-grid">
        <label class="credential-field">
          <span>Saved credential</span>
          <select :value="selectedSavedCredentialId" @change="onSelectSavedCredential">
            <option value="">-- Select a saved credential --</option>
            <option
              v-for="credential in savedCredentials"
              :key="credential.id"
              :value="credential.id"
            >
              {{ renderSavedCredentialOption(credential) }}
            </option>
          </select>
        </label>

        <label class="credential-field">
          <span>Quick save name (optional)</span>
          <input
            v-model.trim="saveCredentialName"
            type="text"
            placeholder="Example: Oracle QA, PG Local"
          />
        </label>
      </div>

      <div class="save-row">
        <button class="save-btn" type="button" :disabled="isSavingCredential" @click="onSaveCredential">
          {{ isSavingCredential ? 'Saving...' : 'Save credential' }}
        </button>

        <span v-if="saveCredentialError" class="save-error">
          {{ saveCredentialError }}
        </span>

        <span
          v-else-if="saveState?.message"
          :class="['save-message', `save-${saveState.status || 'idle'}`]"
        >
          {{ saveState.message }}
        </span>
      </div>
    </section>

    <div class="form-grid">
      <label class="field-row">
        <span>Database type</span>
        <select v-model="localConfig.type">
          <option value="ORACLE">Oracle</option>
          <option value="POSTGRESQL">PostgreSQL</option>
        </select>
      </label>

      <label class="field-row">
        <span>JDBC URL (preferred)</span>
        <input
          v-model.trim="localConfig.jdbcUrl"
          type="text"
          placeholder="jdbc:postgresql://localhost:5432/migration_db"
        />
        <small class="field-note">When provided, JDBC URL is used instead of Host/Port/DB Name.</small>
      </label>

      <label class="field-row">
        <span>Host</span>
        <input v-model.trim="localConfig.host" type="text" placeholder="localhost" />
      </label>

      <label class="field-row">
        <span>Port</span>
        <input v-model.number="localConfig.port" type="number" min="1" />
      </label>

      <label class="field-row">
        <span>{{ databaseNameLabel }}</span>
        <input v-model.trim="localConfig.databaseName" type="text" />
      </label>

      <label class="field-row">
        <span>Schema (optional)</span>
        <input v-model.trim="localConfig.schemaName" type="text" placeholder="public, scott, ..." />
      </label>

      <label class="field-row">
        <span>Username</span>
        <input v-model.trim="localConfig.username" type="text" autocomplete="username" />
      </label>

      <label class="field-row">
        <span>Password</span>
        <input v-model="localConfig.password" type="password" autocomplete="current-password" />
      </label>
    </div>

    <div class="test-action-bar">
      <span v-if="testState?.message" :class="['test-message', `test-${testState.status || 'idle'}`]">
        {{ testState.message }}
      </span>
      <button class="test-btn" type="button" :disabled="isTesting" @click="emit('test-connection')">
        {{ isTesting ? 'Testing...' : 'Test connection' }}
      </button>
    </div>
  </section>
</template>

<script setup>
import { reactive, ref, watch } from 'vue';

const props = defineProps({
  title: {
    type: String,
    required: true
  },
  badge: {
    type: String,
    required: true
  },
  databaseNameLabel: {
    type: String,
    required: true
  },
  modelValue: {
    type: Object,
    required: true
  },
  isTesting: {
    type: Boolean,
    default: false
  },
  testState: {
    type: Object,
    default: () => ({ status: 'idle', message: '' })
  },
  savedCredentials: {
    type: Array,
    default: () => []
  },
  selectedSavedCredentialId: {
    type: [Number, String],
    default: ''
  },
  isSavingCredential: {
    type: Boolean,
    default: false
  },
  saveState: {
    type: Object,
    default: () => ({ status: 'idle', message: '' })
  }
});

const emit = defineEmits([
  'update:modelValue',
  'test-connection',
  'save-credential',
  'select-saved-credential'
]);

const localConfig = reactive({ ...props.modelValue });
const saveCredentialName = ref('');
const saveCredentialError = ref('');

watch(
  () => props.modelValue,
  (nextValue) => {
    Object.assign(localConfig, nextValue);
  },
  { deep: true }
);

watch(
  localConfig,
  (nextValue) => {
    emit('update:modelValue', { ...nextValue });
  },
  { deep: true }
);

watch(saveCredentialName, () => {
  saveCredentialError.value = '';
});

const onSaveCredential = () => {
  const trimmedName = saveCredentialName.value.trim();
  if (!trimmedName) {
    saveCredentialError.value = 'Please enter a credential name before saving.';
    return;
  }

  saveCredentialError.value = '';
  emit('save-credential', {
    name: trimmedName,
    config: { ...localConfig }
  });
};

const onSelectSavedCredential = (event) => {
  const selectedValue = event?.target?.value;
  if (!selectedValue) {
    emit('select-saved-credential', null);
    return;
  }
  emit('select-saved-credential', selectedValue);
};

const renderSavedCredentialOption = (credential) => {
  if (!credential) {
    return 'Credential';
  }

  const name = credential.displayName || 'Credential';
  const cfg = credential.databaseConfig || {};
  const type = cfg.type || 'DB';
  const host = cfg.host || (cfg.jdbcUrl ? 'jdbc-url' : 'unknown-host');
  const dbName = cfg.databaseName || 'unknown-db';
  return `${name} (${type} - ${host}/${dbName})`;
};
</script>

<style scoped>
.card {
  background: var(--surface);
  border-radius: 18px;
  border: 2px solid #a7865d;
  box-shadow: 0 12px 28px var(--shadow-soft);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
}

.card-header {
  align-items: center;
  border-bottom: 2px solid #ccb089;
  display: flex;
  justify-content: space-between;
  padding-bottom: 10px;
}

.test-action-bar {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: space-between;
  margin-top: 2px;
}

.credential-panel {
  background: #f9edd8;
  border: 1px solid #d5b790;
  border-radius: 12px;
  padding: 10px;
}

.panel-title {
  color: #6f532f;
  font-size: 0.78rem;
  font-weight: 800;
  letter-spacing: 0.04em;
  margin: 0 0 8px;
  text-transform: uppercase;
}

.credential-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 10px;
}

.credential-field {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.credential-field span {
  color: var(--ink-soft);
  font-size: 0.82rem;
  font-weight: 700;
}

.save-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.test-btn {
  background: var(--accent-soft);
  border: 1px solid #8eb5d4;
  border-radius: 9px;
  color: var(--accent);
  cursor: pointer;
  font-family: inherit;
  font-size: 0.82rem;
  font-weight: 700;
  padding: 6px 10px;
}

.test-btn:disabled {
  cursor: not-allowed;
  background: #c4cfda;
  color: #5d6a76;
}

.save-btn {
  background: #f6e5cc;
  border: 1px solid #cda77c;
  border-radius: 9px;
  color: #7c4b13;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.82rem;
  font-weight: 700;
  padding: 6px 10px;
}

.save-btn:disabled {
  cursor: not-allowed;
  background: #d7cab7;
  color: #756555;
}

.save-message {
  font-size: 0.8rem;
  font-weight: 700;
}

.save-success {
  color: var(--ok-text);
}

.save-error {
  color: var(--error-text);
  font-size: 0.8rem;
  font-weight: 700;
}

.save-pending,
.save-idle {
  color: var(--ink-soft);
}

.test-message {
  font-size: 0.8rem;
  font-weight: 700;
}

.test-success {
  color: var(--ok-text);
}

.test-error {
  color: var(--error-text);
}

.test-pending,
.test-idle {
  color: var(--ink-soft);
}

.card-header h2 {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1.1rem;
  margin: 0;
}

.card-badge {
  background: var(--accent-soft);
  border: 1px solid #8eb5d4;
  border-radius: 999px;
  color: var(--accent);
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 4px 10px;
  text-transform: uppercase;
}

.form-grid {
  display: grid;
  gap: 9px;
}

.field-row {
  align-items: start;
  display: grid;
  gap: 8px;
  grid-template-columns: minmax(170px, 38%) minmax(0, 1fr);
}

.field-row span {
  color: var(--ink-soft);
  font-size: 0.82rem;
  font-weight: 700;
  line-height: 1.35;
  padding-top: 8px;
}

.field-note {
  color: #6c5c47;
  font-size: 0.76rem;
  grid-column: 2;
  line-height: 1.35;
  margin-top: -2px;
}

.field-row input,
.field-row select,
.credential-field input,
.credential-field select {
  appearance: none;
  background: #fffaf2;
  border: 1px solid #ccb089;
  border-radius: 10px;
  color: var(--ink);
  font-family: inherit;
  font-size: 0.93rem;
  outline: none;
  padding: 9px 11px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.credential-field input,
.credential-field select {
  min-width: 0;
  width: 100%;
}

.field-row input:focus,
.field-row select:focus,
.credential-field input:focus,
.credential-field select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px #c7dded;
}

@media (max-width: 1180px) {
  .credential-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 960px) {
  .field-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .field-row span {
    padding-top: 0;
  }

  .field-note {
    grid-column: auto;
    margin-top: 0;
  }

  .test-action-bar {
    justify-content: flex-end;
  }
}
</style>
