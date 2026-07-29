<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import AppShell from "@/features/shell/ui/AppShell.vue";
import { installEmbedPreferenceReceiver, isEmbeddedProfile, postEmbedPreferences, postProfileRoute } from "@/features/embed/lib/profileEmbed";

const route = useRoute();
const embedded = computed(() => isEmbeddedProfile(route));
const standaloneSearch = computed(() => route.name === "Search");

watch(() => route.fullPath, () => postProfileRoute(route), { immediate: true });

let stopPreferenceReceiver: (() => void) | null = null;
onMounted(() => {
  stopPreferenceReceiver = installEmbedPreferenceReceiver(route);
  postEmbedPreferences(route);
});
onBeforeUnmount(() => stopPreferenceReceiver?.());
</script>

<template>
  <PToast />
  <router-view v-if="embedded || standaloneSearch" />
  <AppShell v-else>
    <router-view />
  </AppShell>
</template>
