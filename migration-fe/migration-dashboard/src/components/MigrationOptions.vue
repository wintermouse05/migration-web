<template>
  <section class="card">
    <h2>Tuy chon Migration</h2>

    <!-- ── Migration mode ── -->
    <div class="section-title">Che do migration</div>
    <div class="radio-stack">
      <label>
        <input v-model="localMode" type="radio" value="ALL" />
        Copy cau truc va du lieu
      </label>
      <label>
        <input v-model="localMode" type="radio" value="STRUCTURE_ONLY" />
        Chi copy cau truc (DDL)
      </label>
      <label>
        <input v-model="localMode" type="radio" value="DATA_ONLY" />
        Chi copy du lieu (DML)
      </label>
    </div>

    <!-- ── Core options ── -->
    <div class="section-title">Tuy chon co ban</div>
    <div class="options-grid">
      <label class="checkbox-item">
        <input
          v-model="localOptions.truncate"
          type="checkbox"
          :disabled="isStructureOnlyMode"
        />
        Xoa du lieu cu truoc khi migrate (TRUNCATE)
      </label>

      <label class="checkbox-item">
        <input
          v-model="localOptions.copyNewOnly"
          type="checkbox"
          :disabled="isStructureOnlyMode"
        />
        Chi copy ban ghi moi (theo PK — bo qua trung lap)
      </label>

      <label class="field-row">
        <span>So dong toi da moi bang (Limit, 0 = tat ca)</span>
        <input
          v-model.number="localOptions.limit"
          type="number"
          min="0"
          class="number-input"
        />
      </label>

      <label class="field-row">
        <span>Chi migrate cac bang (CSV, trong = tat ca)</span>
        <input
          v-model.trim="localOptions.includeTablesCsv"
          type="text"
          placeholder="users,orders,order_items"
          class="text-input"
        />
      </label>

      <label class="field-row">
        <span>Loai tru cac bang (CSV)</span>
        <input
          v-model.trim="localOptions.excludeTablesCsv"
          type="text"
          placeholder="audit_log,temp_table"
          class="text-input"
        />
      </label>
    </div>

    <!-- ── Advanced objects ── -->
    <div class="section-divider">
      <h3>Doi tuong nang cao</h3>
    </div>

    <div class="options-grid">
      <label class="checkbox-item">
        <input v-model="localOptions.migrateSequences" type="checkbox" :disabled="isDataOnlyMode" />
        Sequences
      </label>

      <label class="checkbox-item">
        <input v-model="localOptions.migrateIndexes" type="checkbox" :disabled="isDataOnlyMode" />
        Indexes
      </label>

      <label class="checkbox-item">
        <input v-model="localOptions.migrateFunctions" type="checkbox" :disabled="isDataOnlyMode" />
        Functions / Procedures
      </label>

      <label class="checkbox-item">
        <input v-model="localOptions.migrateTriggers" type="checkbox" :disabled="isDataOnlyMode" />
        Triggers
      </label>
    </div>

    <!-- ── Views ── -->
    <div class="section-divider">
      <h3>Views</h3>
    </div>

    <div class="options-grid">
      <label class="checkbox-item">
        <input v-model="localOptions.migrateViews" type="checkbox" :disabled="isDataOnlyMode" />
        Migrate views
      </label>

      <label class="checkbox-item">
        <input
          v-model="localOptions.replaceExistingViews"
          type="checkbox"
          :disabled="isViewOptionsDisabled"
        />
        Thay the views da ton tai (DROP + CREATE)
      </label>

      <label class="field-row">
        <span>Chi migrate cac views (CSV)</span>
        <input
          v-model.trim="localOptions.includeViewsCsv"
          type="text"
          :disabled="isViewOptionsDisabled"
          placeholder="v_emp_details,v_orders_sum"
          class="text-input"
        />
      </label>

      <label class="field-row">
        <span>Loai tru cac views (CSV)</span>
        <input
          v-model.trim="localOptions.excludeViewsCsv"
          type="text"
          :disabled="isViewOptionsDisabled"
          placeholder="v_temp"
          class="text-input"
        />
      </label>
    </div>

    <!-- ── Retry ── -->
    <div class="section-divider">
      <h3>Retry / Tu dong thu lai</h3>
    </div>

    <div class="options-grid">
      <label class="checkbox-item">
        <input v-model="localRetry.enabled" type="checkbox" />
        Bat dau tinh nang retry khi gap loi tam thoi
      </label>

      <label class="field-row">
        <span>So lan retry toi da</span>
        <input
          v-model.number="localRetry.maxAttempts"
          type="number"
          min="1"
          max="20"
          :disabled="!localRetry.enabled"
          class="number-input small"
        />
      </label>

      <label class="field-row">
        <span>Delay ban dau (ms)</span>
        <input
          v-model.number="localRetry.initialDelayMs"
          type="number"
          min="0"
          step="100"
          :disabled="!localRetry.enabled"
          class="number-input small"
        />
      </label>

      <label class="field-row">
        <span>He so backoff (nhan delay sau moi lan)</span>
        <input
          v-model.number="localRetry.backoffMultiplier"
          type="number"
          min="1"
          max="10"
          step="0.1"
          :disabled="!localRetry.enabled"
          class="number-input small"
        />
      </label>
    </div>

    <!-- ── Resume ── -->
    <div class="section-divider">
      <h3>Resume / Tiep tuc tu diem da dung</h3>
    </div>

    <div class="options-grid">
      <label class="checkbox-item">
        <input v-model="localResume.enabled" type="checkbox" />
        Bat dau tinh nang resume (tiep tuc tu diem da dung)
      </label>

      <label class="field-row">
        <span>Duong dan file checkpoint</span>
        <input
          v-model.trim="localResume.stateFile"
          type="text"
          :disabled="!localResume.enabled"
          placeholder=".migration-resume.properties"
          class="text-input"
        />
      </label>

      <label class="checkbox-item">
        <input
          v-model="localResume.reset"
          type="checkbox"
          :disabled="!localResume.enabled"
        />
        Xoa checkpoint cu truoc khi bat dau (reset)
      </label>
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

const localMode = computed({
  get: () => props.migrationMode,
  set: (value) => emit('update:migrationMode', value)
});

const isStructureOnlyMode = computed(() => localMode.value === 'STRUCTURE_ONLY');
const isDataOnlyMode = computed(() => localMode.value === 'DATA_ONLY');
const isViewOptionsDisabled = computed(() => isDataOnlyMode.value || !localOptions.migrateViews);

const localOptions = reactive({ ...props.options });
const localRetry = reactive({ ...props.retry });
const localResume = reactive({ ...props.resume });

watch(
  () => props.options,
  (nextValue) => Object.assign(localOptions, nextValue),
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
  background: #ffffff;
  border: 1px solid #e8e2d8;
  border-radius: 18px;
  box-shadow: 0 18px 45px rgba(44, 53, 74, 0.08);
  padding: 18px;
}

h2 {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1.1rem;
  margin: 0 0 14px;
}

.section-title {
  color: #2d3f4d;
  font-size: 0.78rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  margin: 0 0 8px;
  text-transform: uppercase;
}

.radio-stack {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
}

label {
  align-items: center;
  cursor: pointer;
  display: flex;
  gap: 8px;
}

.options-grid {
  display: grid;
  gap: 10px;
  margin-bottom: 4px;
}

.checkbox-item {
  color: #2d3f4d;
  font-size: 0.93rem;
}

.field-row {
  align-items: center;
  color: #2d3f4d;
  display: flex;
  gap: 10px;
  font-size: 0.9rem;
  justify-content: space-between;
}

.field-row > span {
  flex: 1;
  font-size: 0.85rem;
}

.text-input {
  flex: 0 0 280px;
}

.number-input {
  flex: 0 0 120px;
}

.number-input.small {
  flex: 0 0 100px;
}

input[type="text"],
input[type="number"] {
  background: #fdfbf8;
  border: 1px solid #d9d5cd;
  border-radius: 10px;
  color: #1f2b36;
  font-family: inherit;
  font-size: 0.9rem;
  outline: none;
  padding: 8px 11px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

input:focus {
  border-color: #2d7ef7;
  box-shadow: 0 0 0 4px rgba(45, 126, 247, 0.14);
}

input:disabled {
  background: #f0ede8;
  color: #999;
  cursor: not-allowed;
  opacity: 0.7;
}

.section-divider {
  border-top: 1px dashed #d9d5cd;
  margin: 12px 0 10px;
  padding-top: 12px;
}

.section-divider h3 {
  color: #2d7ef7;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 0.92rem;
  font-weight: 700;
  margin: 0 0 2px;
}
</style>
