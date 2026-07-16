<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import AppShell from "@/features/shell/AppShell.vue";
import FeedPage from "@/features/content/FeedPage.vue";

const route = useRoute();
const isFocusedStoryRoute = computed(() => route.name === "StoryViewer");
const isPostPageRoute = computed(() => route.name === "PostPage");
const isPostCommentsRoute = computed(() => route.name === "PostComments" || route.name === "PostCommentThread");
// Keep the canvas instance alive for the full `/` → `/p/:id` journey.
// `v-show` only hides it while the reader is open, so its camera and loaded chunks survive Back.
const isCanvasSceneRoute = computed(() => route.name === "Feed" || isPostPageRoute.value || isPostCommentsRoute.value);
const hidesCanvas = computed(() => isPostPageRoute.value || isPostCommentsRoute.value);
</script>

<template>
  <PToast />
  <router-view v-if="isFocusedStoryRoute" />
  <AppShell v-else>
    <template v-if="isCanvasSceneRoute">
      <section class="canvas-scene-host" :aria-hidden="hidesCanvas ? 'true' : undefined" :inert="hidesCanvas || undefined">
        <FeedPage v-show="!hidesCanvas" />
      </section>
      <router-view v-if="isPostPageRoute || isPostCommentsRoute" />
    </template>
    <router-view v-else />
  </AppShell>
</template>

<style>
.canvas-scene-host {
  min-width: 0;
  min-height: 0;
}
</style>
