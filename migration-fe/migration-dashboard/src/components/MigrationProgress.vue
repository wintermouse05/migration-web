<template>
  <section class="progress-wrap" v-if="isMigrating || progress > 0">
    <div class="progress-title-row">
      <strong>Tien trinh migration</strong>
      <span>{{ normalizedProgress }}%</span>
    </div>

    <div class="progress-track" role="progressbar" :aria-valuenow="normalizedProgress" aria-valuemin="0" aria-valuemax="100">
      <div class="progress-fill" :style="{ width: normalizedProgress + '%' }"></div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  progress: {
    type: Number,
    default: 0
  },
  isMigrating: {
    type: Boolean,
    default: false
  }
});

const normalizedProgress = computed(() => {
  if (props.progress < 0) {
    return 0;
  }
  if (props.progress > 100) {
    return 100;
  }
  return Math.round(props.progress);
});
</script>

<style scoped>
.progress-wrap {
  background: linear-gradient(180deg, #fff 0%, #f8f7f3 100%);
  border: 1px solid #e8e2d8;
  border-radius: 14px;
  margin: 14px 0;
  padding: 13px;
}

.progress-title-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.progress-track {
  background: #d8dfe7;
  border-radius: 999px;
  height: 14px;
  overflow: hidden;
}

.progress-fill {
  background: linear-gradient(95deg, #1e9878 0%, #2d7ef7 100%);
  height: 100%;
  transition: width 0.35s ease;
}
</style>
