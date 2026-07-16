<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute } from "vue-router";
import AppShell from "@/features/shell/AppShell.vue";
import { isEmbeddedProfile, postProfileRoute } from "@/features/embed/profileEmbed";

const route = useRoute();
const embedded = computed(() => isEmbeddedProfile(route));

watch(() => route.fullPath, () => postProfileRoute(route), { immediate: true });
</script>

<template>
  <PToast />
  <router-view v-if="embedded" />
  <AppShell v-else>
    <router-view />
  </AppShell>
</template>
