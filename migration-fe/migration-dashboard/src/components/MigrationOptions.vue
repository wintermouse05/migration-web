<template>
  <section class="card">
    <header class="card-header">
      <h2>Migration Options</h2>
      <p class="card-note">If the process stalls for over 5 minutes without new logs, please verify database connectivity.</p>
    </header>

    <div class="sections-grid">
      <article class="option-section">
        <h3>1. Migration Mode</h3>
        <div class="radio-stack">
          <label class="option-line">
            <input v-model="localMode" type="radio" value="ALL" />
            Copy structure and data
          </label>
          <label class="option-line">
            <input v-model="localMode" type="radio" value="STRUCTURE_ONLY" />
            Structure only (DDL)
          </label>
          <label class="option-line">
            <input v-model="localMode" type="radio" value="DATA_ONLY" />
            Data only (DML)
          </label>
        </div>
      </article>

      <article class="option-section">
        <h3>2. Core Options</h3>
        <div class="content-stack">
          <label class="option-line">
            <input
              v-model="localOptions.truncate"
              type="checkbox"
              :disabled="isStructureOnlyMode"
            />
            Clear existing target data before migration (TRUNCATE)
          </label>

          <label class="option-line">
            <input
              v-model="localOptions.copyNewOnly"
              type="checkbox"
              :disabled="isStructureOnlyMode"
            />
            Copy only new records (by PK, skip duplicates)
          </label>

          <label class="option-line">
            <input
              v-model="localOptions.copyOnlyTargetEmptyTables"
              type="checkbox"
              :disabled="isStructureOnlyMode"
            />
            Copy data only for empty target tables
          </label>

          <label class="field-line">
            <span>Per-table limit (0 = all rows)</span>
            <input
              v-model.number="localOptions.limit"
              type="number"
              min="0"
              class="number-input"
            />
          </label>

          <label class="field-line">
            <span>Include tables (CSV)</span>
            <input
              v-model.trim="localOptions.includeTablesCsv"
              type="text"
              placeholder="users,orders,order_items"
            />
          </label>

          <label class="field-line">
            <span>Exclude tables (CSV)</span>
            <input
              v-model.trim="localOptions.excludeTablesCsv"
              type="text"
              placeholder="audit_log,temp_table"
            />
          </label>
        </div>
      </article>

      <article class="option-section">
        <h3>3. Advanced Objects</h3>
        <div class="check-grid">
          <label class="option-line half">
            <input v-model="localOptions.migrateSequences" type="checkbox" :disabled="isDataOnlyMode" />
            Sequences
          </label>
          <label class="option-line half">
            <input v-model="localOptions.migrateIndexes" type="checkbox" :disabled="isDataOnlyMode" />
            Indexes
          </label>
          <label class="option-line half">
            <input v-model="localOptions.migrateFunctions" type="checkbox" :disabled="isDataOnlyMode" />
            Functions / Procedures
          </label>
          <label class="option-line half">
            <input v-model="localOptions.migrateTriggers" type="checkbox" :disabled="isDataOnlyMode" />
            Triggers
          </label>
        </div>
      </article>

      <article class="option-section">
        <h3>4. Views</h3>
        <div class="content-stack">
          <label class="option-line">
            <input v-model="localOptions.migrateViews" type="checkbox" :disabled="isDataOnlyMode" />
            Migrate views
          </label>

          <label class="option-line">
            <input
              v-model="localOptions.replaceExistingViews"
              type="checkbox"
              :disabled="isViewOptionsDisabled"
            />
            Replace existing views (DROP + CREATE)
          </label>

          <label class="field-line">
            <span>Include views (CSV)</span>
            <input
              v-model.trim="localOptions.includeViewsCsv"
              type="text"
              :disabled="isViewOptionsDisabled"
              placeholder="v_emp_details,v_orders_sum"
            />
          </label>

          <label class="field-line">
            <span>Exclude views (CSV)</span>
            <input
              v-model.trim="localOptions.excludeViewsCsv"
              type="text"
              :disabled="isViewOptionsDisabled"
              placeholder="v_temp"
            />
          </label>
        </div>
      </article>

      <article class="option-section">
        <h3>5. Retry</h3>
        <div class="content-stack">
          <label class="option-line">
            <input v-model="localRetry.enabled" type="checkbox" />
            Enable retry when temporary errors occur
          </label>

          <label class="field-line compact">
            <span>Max retry attempts</span>
            <input
              v-model.number="localRetry.maxAttempts"
              type="number"
              min="1"
              max="20"
              :disabled="!localRetry.enabled"
              class="number-input"
            />
          </label>

          <label class="field-line compact">
            <span>Initial delay (ms)</span>
            <input
              v-model.number="localRetry.initialDelayMs"
              type="number"
              min="0"
              step="100"
              :disabled="!localRetry.enabled"
              class="number-input"
            />
          </label>

          <label class="field-line compact">
            <span>Backoff multiplier</span>
            <input
              v-model.number="localRetry.backoffMultiplier"
              type="number"
              min="1"
              max="10"
              step="0.1"
              :disabled="!localRetry.enabled"
              class="number-input"
            />
          </label>
        </div>
      </article>

      <article class="option-section">
        <h3>6. Resume</h3>
        <div class="content-stack">
          <label class="option-line">
            <input v-model="localResume.enabled" type="checkbox" />
            Enable resume (continue from last checkpoint)
          </label>

          <label class="field-line">
            <span>File checkpoint</span>
            <input
              v-model.trim="localResume.stateFile"
              type="text"
              :disabled="!localResume.enabled"
              placeholder=".migration-resume.properties"
            />
          </label>

          <label class="option-line">
            <input
              v-model="localResume.reset"
              type="checkbox"
              :disabled="!localResume.enabled"
            />
            Reset previous checkpoint before start
          </label>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, reactive, watch } from 'vue';

const props = defineProps({
  migrationMode: {
    type: String,
    required: true
  },
  options: {
    type: Object,
    required: true
  },
  retry: {
    type: Object,
    default: () => ({ enabled: false, maxAttempts: 3, initialDelayMs: 2000, backoffMultiplier: 2.0 })
  },
  resume: {
    type: Object,
    default: () => ({ enabled: false, stateFile: '.migration-resume.properties', reset: false })
  }
});

const emit = defineEmits(['update:migrationMode', 'update:options', 'update:retry', 'update:resume']);

const defaultOptions = {
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
  migrateViews: false,
  replaceExistingViews: false,
  includeViewsCsv: '',
  excludeViewsCsv: ''
};

const localMode = computed({
  get: () => props.migrationMode,
  set: (value) => emit('update:migrationMode', value)
});

const isStructureOnlyMode = computed(() => localMode.value === 'STRUCTURE_ONLY');
const isDataOnlyMode = computed(() => localMode.value === 'DATA_ONLY');
const isViewOptionsDisabled = computed(() => isDataOnlyMode.value || !localOptions.migrateViews);

const localOptions = reactive({ ...defaultOptions, ...props.options });
const localRetry = reactive({ ...props.retry });
const localResume = reactive({ ...props.resume });

watch(
  () => props.options,
  (nextValue) => Object.assign(localOptions, { ...defaultOptions, ...nextValue }),
  { deep: true }
);
watch(
  () => props.retry,
  (nextValue) => Object.assign(localRetry, nextValue),
  { deep: true }
);
watch(
  () => props.resume,
  (nextValue) => Object.assign(localResume, nextValue),
  { deep: true }
);

watch(localOptions, (nextValue) => emit('update:options', { ...nextValue }), { deep: true });
watch(localRetry, (nextValue) => emit('update:retry', { ...nextValue }), { deep: true });
watch(localResume, (nextValue) => emit('update:resume', { ...nextValue }), { deep: true });
</script>

<style scoped>
.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 18px;
  box-shadow: 0 12px 28px var(--shadow-soft);
  padding: 18px;
}

.card-header {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: space-between;
  margin-bottom: 12px;
}

h2 {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1.08rem;
  margin: 0;
}

.card-note {
  color: #6f5a41;
  font-size: 0.78rem;
  font-style: italic;
  margin: 0;
}

.sections-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.option-section {
  background: linear-gradient(180deg, var(--surface) 0%, var(--surface-alt) 100%);
  border: 1px solid #d9bf99;
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  padding: 12px;
}

.option-section h3 {
  color: var(--ink);
  font-family: 'Space Grotesk', sans-serif;
  font-size: 0.88rem;
  letter-spacing: 0.02em;
  margin: 0;
  text-transform: uppercase;
}

.content-stack,
.radio-stack {
  display: grid;
  gap: 10px;
}

.check-grid {
  column-gap: 10px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  row-gap: 10px;
}

.option-line {
  align-items: flex-start;
  color: var(--ink);
  cursor: pointer;
  display: flex;
  font-size: 0.88rem;
  font-weight: 600;
  gap: 8px;
  line-height: 1.35;
}

.option-line input {
  margin-top: 2px;
}

.field-line {
  align-items: center;
  display: grid;
  gap: 10px;
  grid-template-columns: 160px minmax(0, 1fr);
}

.field-line span {
  color: var(--ink-soft);
  font-size: 0.82rem;
  font-weight: 700;
}

.field-line.compact {
  grid-template-columns: 170px 130px;
}

.number-input {
  max-width: 130px;
  width: 100%;
}

input[type="text"],
input[type="number"] {
  background: #fffaf2;
  border: 1px solid #ccb089;
  border-radius: 10px;
  color: var(--ink);
  font-family: inherit;
  font-size: 0.9rem;
  outline: none;
  padding: 8px 11px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px #c7dded;
}

input:disabled {
  background: #e8d9c2;
  color: #7f7365;
  cursor: not-allowed;
}

@media (max-width: 1080px) {
  .sections-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .field-line,
  .field-line.compact {
    grid-template-columns: 1fr;
  }
}
</style>
