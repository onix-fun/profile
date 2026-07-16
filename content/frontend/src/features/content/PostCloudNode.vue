<script setup lang="ts">
import { computed } from "vue";
import type { ProfileContentPost } from "@/api/types";
import MediaProjectBoard from "@/features/mediaProject/MediaProjectBoard.vue";
import { postAssets } from "@/features/mediaProject/mediaAssets";

const props = withDefaults(defineProps<{
  post: ProfileContentPost;
  emphasis?: "hero" | "standard" | "compact";
  suspended?: boolean;
}>(), { emphasis: "standard", suspended: false });
const emit = defineEmits<{ open: [] }>();
const assets = computed(() => postAssets(props.post));

function keydown(event: KeyboardEvent) {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  emit("open");
}
</script>

<template>
  <article
    class="post-cloud__body media-canvas-preview"
    :class="`media-canvas-preview--${emphasis}`"
    role="button"
    tabindex="0"
    aria-label="Открыть проект"
    @click="emit('open')"
    @keydown="keydown"
  >
    <MediaProjectBoard :assets="assets" :preview-limit="6" :suspended="suspended" />
  </article>
</template>

<style scoped>
.media-canvas-preview {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  cursor: pointer;
  outline: none;
}
.media-canvas-preview:focus-visible { outline: 3px solid #335cf2; outline-offset: 7px; border-radius: 20px; }
</style>
