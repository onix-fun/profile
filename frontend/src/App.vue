<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import AppShell from "@/features/shell/AppShell.vue";
import FeedPage from "@/features/content/FeedPage.vue";
import PostOverlay from "@/features/post/PostOverlay.vue";

const route = useRoute();
const isFocusedStoryRoute = computed(() => route.name === "StoryViewer" || route.name === "StoryArchive");
const isPostOverlay = computed(() => route.name === "PostOverlay");
</script>

<template>
  <PToast />
  <router-view v-if="isFocusedStoryRoute" />
  <AppShell v-else>
    <template v-if="isPostOverlay">
      <FeedPage />
      <PostOverlay />
    </template>
    <router-view v-else />
  </AppShell>
</template>
