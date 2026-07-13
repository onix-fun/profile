<script setup lang="ts">
import AppShell from "@/features/shell/AppShell.vue";
</script>

<template>
  <PToast />
  <AppShell>
    <router-view />
  </AppShell>
</template>
