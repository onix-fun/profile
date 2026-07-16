<script setup lang="ts">
import { RouterLink } from "vue-router";
</script>

<template>
  <main class="not-found"><strong>404</strong><p>Этой страницы больше нет.</p><RouterLink to="/">Открыть рекомендации</RouterLink></main>
</template>

<style scoped>
.not-found{min-height:100dvh;display:grid;place-content:center;gap:12px;background:#eef0f2;color:#30343b;text-align:center;font-family:"Nunito",sans-serif}.not-found strong{font-size:72px}.not-found p{margin:0;color:#737d89}.not-found a{margin-top:8px;padding:11px 16px;border-radius:999px;background:#fff;color:#30343b;text-decoration:none;font-weight:900;box-shadow:0 8px 20px rgba(35,40,50,.1)}
</style>
