<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import type { Story, StoryBlock, StorySlide } from "@/api/types";

const route = useRoute();
const router = useRouter();
const story = ref<Story | null>(null);
const slideIndex = ref(0);
const progress = ref(0);
const isPaused = ref(false);
const muted = ref(true);
const captionOpen = ref(false);
let timer: number | undefined;
let startedAt = 0;

const slides = computed<StorySlide[]>(() => {
  if (!story.value) return [];
  if (story.value.slides?.length) return story.value.slides;
  return [{
    id: `${story.value.id}-slide`,
    blocks: story.value.blocks,
    durationMs: 5000,
    background: "#111827",
  }];
});

const activeSlide = computed(() => slides.value[slideIndex.value]);
const renderBlocks = computed(() => (activeSlide.value?.blocks || []).filter((block) => block.type !== "TEXT"));
const storyCaption = computed(() => {
  const blocks = activeSlide.value?.blocks || story.value?.blocks || [];
  const textBlock = blocks.find((block) => block.type === "TEXT" && typeof block.data.text === "string");
  const caption = blocks.find((block) => typeof block.data.caption === "string");
  return String(textBlock?.data.text || caption?.data.caption || "").trim();
});
const storyTags = computed(() => {
  const fromCaption = Array.from(storyCaption.value.matchAll(/(^|\s)#([\p{L}\p{N}_-]+)/gu)).map((match) => match[2].toLowerCase());
  const fromData = (activeSlide.value?.blocks || story.value?.blocks || [])
    .flatMap((block) => Array.isArray(block.data.tags) ? block.data.tags : [])
    .filter((tag): tag is string => typeof tag === "string");
  return Array.from(new Set([...fromCaption, ...fromData]));
});

onMounted(async () => {
  story.value = await ContentService.story(String(route.params.storyId || ""));
  await ContentService.recordStoryView(String(route.params.storyId || ""));
  startProgress();
  window.addEventListener("keydown", onKeydown);
});

onBeforeUnmount(() => {
  stopProgress();
  window.removeEventListener("keydown", onKeydown);
});

function startProgress() {
  stopProgress();
  progress.value = 0;
  startedAt = Date.now();
  timer = window.setInterval(() => {
    if (isPaused.value || !activeSlide.value) return;
    progress.value = Math.min(100, ((Date.now() - startedAt) / activeSlide.value.durationMs) * 100);
    if (progress.value >= 100) next();
  }, 80);
}

function stopProgress() {
  if (timer) window.clearInterval(timer);
  timer = undefined;
}

function next() {
  captionOpen.value = false;
  if (slideIndex.value < slides.value.length - 1) {
    slideIndex.value += 1;
    startProgress();
  } else {
    close();
  }
}

function prev() {
  captionOpen.value = false;
  if (slideIndex.value > 0) {
    slideIndex.value -= 1;
    startProgress();
  } else {
    startProgress();
  }
}

function close() {
  void router.push("/");
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "ArrowRight") next();
  if (event.key === "ArrowLeft") prev();
  if (event.key === "Escape") close();
  if (event.key === " ") isPaused.value = !isPaused.value;
}

function source(block: StoryBlock): string {
  return ContentService.mediaSource(block);
}

function text(block: StoryBlock): string {
  const value = block.data.text || block.data.fileName;
  return typeof value === "string" ? value : block.type.toLowerCase();
}
</script>

<template>
  <section
    class="story-viewer"
    :style="{ background: activeSlide?.background || '#111827' }"
    @pointerdown="isPaused = true"
    @pointerup="isPaused = false"
  >
    <div class="story-progress">
      <span
        v-for="(slide, index) in slides"
        :key="slide.id"
        class="story-progress__track"
      >
        <span :style="{ width: index < slideIndex ? '100%' : index === slideIndex ? `${progress}%` : '0%' }"></span>
      </span>
    </div>

    <button type="button" class="story-close" aria-label="Close story" @click="close"><i class="pi pi-times"></i></button>
    <button type="button" class="story-mute" aria-label="Toggle mute" @click="muted = !muted">
      <i :class="muted ? 'pi pi-volume-off' : 'pi pi-volume-up'"></i>
    </button>

    <button type="button" class="tap-zone tap-zone--left" aria-label="Previous story" @click="prev"></button>
    <button type="button" class="tap-zone tap-zone--right" aria-label="Next story" @click="next"></button>

    <main v-if="activeSlide" class="story-stage">
      <article v-for="block in renderBlocks" :key="block.id || text(block)" class="story-block">
        <img v-if="block.type === 'IMAGE' && source(block)" :src="source(block)" alt="" />
        <video v-else-if="block.type === 'VIDEO' && source(block)" :src="source(block)" autoplay playsinline :muted="muted" />
        <audio v-else-if="block.type === 'AUDIO' && source(block)" :src="source(block)" autoplay :muted="muted" controls />
      </article>
    </main>

    <button v-if="storyCaption" type="button" class="caption-sheet" :class="{ open: captionOpen }" @click="captionOpen = !captionOpen">
      <span>{{ storyCaption }}</span>
      <strong v-if="storyTags.length">{{ storyTags.map((tag) => `#${tag}`).join(" ") }}</strong>
      <i :class="captionOpen ? 'pi pi-chevron-down' : 'pi pi-chevron-up'"></i>
    </button>
  </section>
</template>

<style scoped>
.story-viewer {
  position: fixed;
  z-index: 200;
  inset: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #ffffff;
}

.story-progress {
  position: fixed;
  z-index: 205;
  top: 18px;
  left: 18px;
  right: 18px;
  display: flex;
  gap: 5px;
}

.story-progress__track {
  flex: 1;
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.3);
}

.story-progress__track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #ffffff;
}

.story-close,
.story-mute {
  position: fixed;
  z-index: 206;
  top: 36px;
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
  cursor: pointer;
}

.story-close {
  right: 22px;
}

.story-mute {
  right: 72px;
}

.tap-zone {
  position: fixed;
  z-index: 202;
  top: 0;
  bottom: 0;
  width: 34%;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.tap-zone--left {
  left: 0;
}

.tap-zone--right {
  right: 0;
}

.story-stage {
  position: relative;
  z-index: 203;
  width: min(430px, calc(100vw - 32px));
  height: min(780px, calc(100dvh - 86px));
  display: grid;
  overflow: hidden;
  border-radius: 24px;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.45);
}

.story-block {
  display: grid;
  place-items: center;
  min-height: 0;
}

.story-block img,
.story-block video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.story-block audio {
  width: calc(100% - 44px);
}

.story-block p {
  max-width: 82%;
  margin: 0;
  font-size: clamp(30px, 7vw, 58px);
  font-weight: 900;
  line-height: 1.05;
  text-align: center;
}

.caption-sheet {
  position: fixed;
  z-index: 207;
  left: 50%;
  bottom: 18px;
  width: min(430px, calc(100vw - 32px));
  max-height: 76px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 10px;
  overflow: hidden;
  border: 0;
  border-radius: 18px;
  padding: 14px 16px;
  background: rgba(0, 0, 0, 0.62);
  color: #ffffff;
  font: inherit;
  text-align: left;
  backdrop-filter: blur(16px);
  cursor: pointer;
  transform: translateX(-50%);
  transition: max-height 180ms ease;
}

.caption-sheet.open {
  max-height: min(46dvh, 360px);
  overflow: auto;
}

.caption-sheet span {
  grid-column: 1;
  overflow: hidden;
  font-weight: 800;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  white-space: pre-wrap;
}

.caption-sheet.open span {
  display: block;
}

.caption-sheet strong {
  grid-column: 1;
  color: #bbf7d0;
  font-size: 12px;
}

.caption-sheet i {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
}
</style>
