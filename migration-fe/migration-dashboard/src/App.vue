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
  --bg-start: #f7f4ef;
  --bg-end: #ede8dc;
  --panel: #ffffff;
  --ink: #24333f;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-height: 100vh;
  font-family: 'Manrope', sans-serif;
  color: var(--ink);
  background:
    radial-gradient(circle at 10% 5%, rgba(238, 143, 86, 0.18) 0%, transparent 40%),
    radial-gradient(circle at 90% 85%, rgba(45, 126, 247, 0.18) 0%, transparent 35%),
    linear-gradient(155deg, var(--bg-start), var(--bg-end));
}

#app {
  min-height: 100vh;
  padding: 28px 16px 36px;
}
</style>
