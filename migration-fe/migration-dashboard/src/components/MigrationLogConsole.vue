<template>
  <section class="console-wrap">
    <header>
      <h2>Migration Status Stream</h2>
      <span class="count">{{ logs.length }} log</span>
    </header>

    <div ref="consoleRef" class="console">
      <p v-if="!logs.length" class="empty">No logs yet. Click Start migration to begin.</p>
      <div v-for="entry in logs" :key="entry.id" class="line" :class="entry.levelClass">
        <span class="time">[{{ entry.time }}]</span>
        <span class="message">{{ entry.message }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue';

const props = defineProps({
  logs: {
    type: Array,
    default: () => []
  }
});

const consoleRef = ref(null);

watch(
  () => props.logs.length,
  async () => {
    await nextTick();
    if (consoleRef.value) {
      consoleRef.value.scrollTop = consoleRef.value.scrollHeight;
    }
  }
);
</script>

<style scoped>
.console-wrap {
  background: #1e2a36;
  border: 1px solid #0f1821;
  border-radius: 14px;
  box-shadow: 0 12px 24px #9b8667;
  color: #c8d3e0;
  overflow: hidden;
}

header {
  align-items: center;
  background: #13202c;
  display: flex;
  justify-content: space-between;
  padding: 10px 14px;
}

h2 {
  color: #f5f8ff;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1rem;
  margin: 0;
}

.count {
  color: #9db4ca;
  font-size: 0.8rem;
}

.console {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 0.86rem;
  height: 290px;
  overflow-y: auto;
  padding: 12px;
}

.empty {
  color: #96a8bc;
  margin: 0;
}

.line {
  display: grid;
  gap: 8px;
  grid-template-columns: auto 1fr;
  line-height: 1.35;
  margin-bottom: 7px;
}

.time {
  color: #68ae7a;
}

.level-error .time,
.level-error .message {
  color: #ff6e6e;
}

.level-success .time,
.level-success .message {
  color: #6fd6aa;
}
</style>
