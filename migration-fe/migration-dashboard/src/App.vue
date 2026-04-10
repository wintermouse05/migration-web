<template>
  <main id="app">
    <MigrationDashboard />
  </main>
</template>

<script setup>
import MigrationDashboard from './components/MigrationDashboard.vue';
</script>

<style>
@import url('https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;700;800&family=Space+Grotesk:wght@500;700&display=swap');

:root {
  --bg-start: #f1e1c9;
  --bg-mid: #e6cfaf;
  --bg-end: #d8bc97;
  --surface: #fffaf0;
  --surface-alt: #f8ebd7;
  --surface-strong: #f0ddbf;
  --line: #c5aa82;
  --ink: #233545;
  --ink-soft: #526676;
  --primary: #b64a20;
  --primary-strong: #8f3615;
  --accent: #235f8b;
  --accent-soft: #d8e8f4;
  --ok-bg: #dcebd8;
  --ok-text: #205f3f;
  --error-bg: #f2d8d3;
  --error-text: #8a2d21;
  --shadow-soft: #cbb797;
  --shadow-strong: #b19a77;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-height: 100vh;
  font-family: 'Manrope', sans-serif;
  color: var(--ink);
  background: linear-gradient(160deg, var(--bg-start) 0%, var(--bg-mid) 50%, var(--bg-end) 100%);
}

#app {
  min-height: 100vh;
  padding: 28px 16px 36px;
}
</style>
