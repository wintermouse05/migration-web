<template>
  <section class="card">
    <h2>Tuy chon Migration</h2>

    <div class="radio-stack">
      <label>
        <input v-model="localMode" type="radio" value="ALL" />
        Copy cau truc va du lieu
      </label>
      <label>
        <input v-model="localMode" type="radio" value="STRUCTURE_ONLY" />
        Chi copy cau truc
      </label>
      <label>
        <input v-model="localMode" type="radio" value="DATA_ONLY" />
        Chi copy du lieu
      </label>
    </div>

    <div class="options-grid">
      <label class="checkbox-item">
        <input
          v-model="localOptions.truncate"
          type="checkbox"
          :disabled="localMode === 'STRUCTURE_ONLY'"
        />
        Xoa data dich truoc khi copy (truncate)
      </label>

      <label class="checkbox-item">
        <input
          v-model="localOptions.copyNewOnly"
          type="checkbox"
          :disabled="localMode === 'STRUCTURE_ONLY'"
        />
        Chi copy data moi
      </label>

      <label class="field">
        <span>Limit so dong copy (0 = tat ca)</span>
        <input v-model.number="localOptions.limit" type="number" min="0" />
      </label>

      <label class="field">
        <span>Include table (CSV)</span>
        <input
          v-model.trim="localOptions.includeTablesCsv"
          type="text"
          placeholder="users,orders,order_items"
        />
      </label>

      <label class="field">
        <span>Exclude table (CSV)</span>
        <input
          v-model.trim="localOptions.excludeTablesCsv"
          type="text"
          placeholder="audit_log,temp_table"
        />
      </label>
    </div>

    <!-- View Migration Options -->
    <div class="section-divider">
      <h3>View Migration</h3>
    </div>

    <div class="options-grid">
      <label class="checkbox-item">
        <input v-model="localOptions.migrateViews" type="checkbox" />
        Migrate views
      </label>

      <label class="checkbox-item">
        <input
          v-model="localOptions.replaceExistingViews"
          type="checkbox"
          :disabled="!localOptions.migrateViews"
        />
        Thay the views da ton tai (drop + create)
      </label>

      <label class="field">
        <span>Include view (CSV)</span>
        <input
          v-model.trim="localOptions.includeViewsCsv"
          type="text"
          :disabled="!localOptions.migrateViews"
          placeholder="view_users,view_orders"
        />
      </label>

      <label class="field">
        <span>Exclude view (CSV)</span>
        <input
          v-model.trim="localOptions.excludeViewsCsv"
          type="text"
          :disabled="!localOptions.migrateViews"
          placeholder="view_temp"
        />
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
  }
});

const emit = defineEmits(['update:migrationMode', 'update:options']);

const localMode = computed({
  get: () => props.migrationMode,
  set: (value) => emit('update:migrationMode', value)
});

const localOptions = reactive({ ...props.options });

watch(
  () => props.options,
  (nextValue) => {
    Object.assign(localOptions, nextValue);
  },
  { deep: true }
);

watch(
  localOptions,
  (nextValue) => {
    emit('update:options', { ...nextValue });
  },
  { deep: true }
);
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

.radio-stack {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
}

label {
  align-items: center;
  display: flex;
  gap: 8px;
}

.options-grid {
  display: grid;
  gap: 10px;
}

.checkbox-item {
  color: #2d3f4d;
  font-size: 0.93rem;
}

.field {
  align-items: stretch;
  display: grid;
  gap: 6px;
}

.field span {
  color: #415665;
  font-size: 0.84rem;
  font-weight: 700;
}

.field input {
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

.field input:focus {
  border-color: #2d7ef7;
  box-shadow: 0 0 0 4px rgba(45, 126, 247, 0.14);
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
