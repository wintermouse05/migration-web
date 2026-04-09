<template>
  <section class="card">
    <header class="card-header">
      <h2>{{ title }}</h2>
      <span class="card-badge">{{ badge }}</span>
    </header>

    <div class="card-actions">
      <button class="test-btn" type="button" :disabled="isTesting" @click="emit('test-connection')">
        {{ isTesting ? 'Dang test...' : 'Test connection' }}
      </button>
      <span v-if="testState?.message" :class="['test-message', `test-${testState.status || 'idle'}`]">
        {{ testState.message }}
      </span>
    </div>

    <div class="credential-tools">
      <label class="field">
        <span>Chon cau hinh da luu</span>
        <select :value="selectedSavedCredentialId" @change="onSelectSavedCredential">
          <option value="">-- Chon mot cau hinh da luu --</option>
          <option
            v-for="credential in savedCredentials"
            :key="credential.id"
            :value="credential.id"
          >
            {{ renderSavedCredentialOption(credential) }}
          </option>
        </select>
      </label>

      <label class="field">
        <span>Ten de luu nhanh (tu chon)</span>
        <input
          v-model.trim="saveCredentialName"
          type="text"
          placeholder="Vi du: Oracle QA, PG Local"
        />
      </label>

      <div class="save-row">
        <button class="save-btn" type="button" :disabled="isSavingCredential" @click="onSaveCredential">
          {{ isSavingCredential ? 'Dang luu...' : 'Luu credential' }}
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
    </div>

    <div class="form-grid">
      <label class="field">
        <span>Loai co so du lieu</span>
        <select v-model="localConfig.type">
          <option value="ORACLE">Oracle</option>
          <option value="POSTGRESQL">PostgreSQL</option>
        </select>
      </label>

      <label class="field">
        <span>JDBC URL (uu tien)</span>
        <input
          v-model.trim="localConfig.jdbcUrl"
          type="text"
          placeholder="jdbc:postgresql://localhost:5432/migration_db"
        />
        <small class="field-note">Neu co URL, he thong se uu tien dung URL thay vi Host/Port/DB Name.</small>
      </label>

      <label class="field">
        <span>Host</span>
        <input v-model.trim="localConfig.host" type="text" placeholder="localhost" />
      </label>

      <label class="field">
        <span>Port</span>
        <input v-model.number="localConfig.port" type="number" min="1" />
      </label>

      <label class="field">
        <span>{{ databaseNameLabel }}</span>
        <input v-model.trim="localConfig.databaseName" type="text" />
      </label>

      <label class="field">
        <span>Schema (tuy chon)</span>
        <input v-model.trim="localConfig.schemaName" type="text" placeholder="public, scott, ..." />
      </label>

      <label class="field">
        <span>Username</span>
        <input v-model.trim="localConfig.username" type="text" autocomplete="username" />
      </label>

      <label class="field">
        <span>Password</span>
        <input v-model="localConfig.password" type="password" autocomplete="current-password" />
      </label>
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
    saveCredentialError.value = 'Hay nhap ten de luu truoc khi bam nut luu.';
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
  emit('select-saved-credential', Number(selectedValue));
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
  background: #ffffff;
  border-radius: 18px;
  border: 1px solid #e8e2d8;
  padding: 18px;
  box-shadow: 0 18px 45px rgba(44, 53, 74, 0.08);
}

.card-header {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}

.card-actions {
  align-items: center;
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.credential-tools {
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
}

.save-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.test-btn {
  background: #eef7ff;
  border: 1px solid #bfd6f5;
  border-radius: 9px;
  color: #1f5ea8;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.82rem;
  font-weight: 700;
  padding: 6px 10px;
}

.test-btn:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.save-btn {
  background: #fff8e8;
  border: 1px solid #e8cf97;
  border-radius: 9px;
  color: #96620d;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.82rem;
  font-weight: 700;
  padding: 6px 10px;
}

.save-btn:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.save-message {
  font-size: 0.8rem;
  font-weight: 700;
}

.save-success {
  color: #1b7a4f;
}

.save-error {
  color: #b03535;
  font-size: 0.8rem;
  font-weight: 700;
}

.save-pending,
.save-idle {
  color: #5b6977;
}

.test-message {
  font-size: 0.8rem;
  font-weight: 700;
}

.test-success {
  color: #1b7a4f;
}

.test-error {
  color: #b03535;
}

.test-pending,
.test-idle {
  color: #5b6977;
}

.card-header h2 {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1.1rem;
  margin: 0;
}

.card-badge {
  background: #edf4ff;
  border-radius: 999px;
  color: #2d7ef7;
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 4px 10px;
  text-transform: uppercase;
}

.form-grid {
  display: grid;
  gap: 11px;
}

.field {
  display: grid;
  gap: 6px;
}

.field span {
  color: #415665;
  font-size: 0.84rem;
  font-weight: 700;
}

.field-note {
  color: #6e7d89;
  font-size: 0.76rem;
  line-height: 1.35;
}

.field input,
.field select {
  appearance: none;
  background: #fdfbf8;
  border: 1px solid #d9d5cd;
  border-radius: 10px;
  color: #1f2b36;
  font-family: inherit;
  font-size: 0.93rem;
  outline: none;
  padding: 10px 11px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.field input:focus,
.field select:focus {
  border-color: #2d7ef7;
  box-shadow: 0 0 0 4px rgba(45, 126, 247, 0.14);
}
</style>
