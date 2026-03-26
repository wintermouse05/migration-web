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

    <div class="form-grid">
      <label class="field">
        <span>Loai co so du lieu</span>
        <select v-model="localConfig.type">
          <option value="ORACLE">Oracle</option>
          <option value="POSTGRESQL">PostgreSQL</option>
        </select>
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
import { reactive, watch } from 'vue';

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
  }
});

const emit = defineEmits(['update:modelValue', 'test-connection']);

const localConfig = reactive({ ...props.modelValue });

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
