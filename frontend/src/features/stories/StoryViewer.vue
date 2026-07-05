<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import type { Story, StoryBlock, StoryGroup, StoryRailItem } from "@/api/types";
import { displayUsername } from "@/features/display/displayText";
import { mergeSeenState, nextAuthorAfter, sortStoryRail } from "@/features/stories/storyState";

const route = useRoute();
const router = useRouter();
const group = ref<StoryGroup | null>(null);
const rail = ref<StoryRailItem[]>([]);
const storyIndex = ref(0);
const progress = ref(0);
const holdPaused = ref(false);
const lockedPaused = ref(false);
const muted = ref(true);
const captionOpen = ref(false);
const isTogglingLike = ref(false);
let timer: number | undefined;
let elapsedMs = 0;
let lastTickAt = 0;

const isArchive = computed(() => route.query.archive === "1" || route.query.archive === "true");
const isPaused = computed(() => holdPaused.value || lockedPaused.value || captionOpen.value);
const stories = computed(() => group.value?.stories || []);
const activeStory = computed(() => stories.value[storyIndex.value] || null);
const renderBlocks = computed(() => (activeStory.value?.blocks || []).filter((block) => block.type !== "TEXT"));
const storyCaption = computed(() => {
  const blocks = activeStory.value?.blocks || [];
  const textBlock = blocks.find((block) => block.type === "TEXT" && typeof block.data.text === "string");
  const caption = blocks.find((block) => typeof block.data.caption === "string");
  return String(textBlock?.data.text || caption?.data.caption || "").trim();
});
const storyTags = computed(() => {
  const fromCaption = Array.from(storyCaption.value.matchAll(/(^|\s)#([\p{L}\p{N}_-]+)/gu)).map((match) => match[2].toLowerCase());
  const fromData = (activeStory.value?.blocks || [])
    .flatMap((block) => Array.isArray(block.data.tags) ? block.data.tags : [])
    .filter((tag): tag is string => typeof tag === "string");
  return Array.from(new Set([...fromCaption, ...fromData]));
});
const authorName = computed(() => displayUsername(group.value?.author?.username || group.value?.authorName, "User"));
const authorAvatar = computed(() => group.value?.author?.avatarUrl || group.value?.avatarUrl || "");
const showCloseFriends = computed(() => Boolean(activeStory.value?.closeFriends || activeStory.value?.visibility === "CLOSE_FRIENDS"));
const activeLikeCount = computed(() => activeStory.value?.likeCount || 0);
const activeLiked = computed(() => Boolean(activeStory.value?.likedByViewer));
const lifetimeLabel = computed(() => {
  if (isArchive.value) return formatArchiveTime(activeStory.value?.createdAt);
  const seconds = activeStory.value?.remainingLifeSeconds ?? secondsUntil(activeStory.value?.expiresAt);
  if (seconds <= 60) return "now";
  const hours = Math.floor(seconds / 3600);
  if (hours > 0) return `${hours}h`;
  return `${Math.max(1, Math.floor(seconds / 60))}m`;
});

onMounted(() => {
  void loadQueue();
  window.addEventListener("keydown", onKeydown);
});

onBeforeUnmount(() => {
  stopProgress();
  window.removeEventListener("keydown", onKeydown);
});

watch(() => route.fullPath, () => {
  void loadQueue();
});

watch(isPaused, () => {
  lastTickAt = Date.now();
  void syncMediaPlayback();
});

async function loadQueue() {
  stopProgress();
  captionOpen.value = false;
  lockedPaused.value = false;
  const startStoryId = String(route.params.storyId || "");
  let authorId = typeof route.query.author === "string" ? route.query.author : "";
  if (!authorId) {
    const loaded = await ContentService.story(startStoryId);
    authorId = loaded.authorId;
  }
  if (!isArchive.value) {
    rail.value = sortStoryRail(mergeSeenState(await ContentService.storiesFeed()));
  }
  group.value = await ContentService.storyGroup(authorId, startStoryId, isArchive.value);
  const startIndex = stories.value.findIndex((story) => story.id === (group.value?.startStoryId || startStoryId));
  storyIndex.value = Math.max(0, startIndex);
  await recordActiveView();
  startProgress();
}

function startProgress() {
  stopProgress();
  progress.value = 0;
  elapsedMs = 0;
  lastTickAt = Date.now();
  timer = window.setInterval(() => {
    const now = Date.now();
    if (!isPaused.value && activeStory.value) {
      elapsedMs += now - lastTickAt;
      progress.value = Math.min(100, (elapsedMs / activeDurationMs()) * 100);
      if (progress.value >= 100) next();
    }
    lastTickAt = now;
  }, 80);
  void syncMediaPlayback();
}

function stopProgress() {
  if (timer) window.clearInterval(timer);
  timer = undefined;
}

async function recordActiveView() {
  if (!activeStory.value?.id) return;
  window.localStorage.setItem(`story-seen:${activeStory.value.id}`, "true");
  await ContentService.recordStoryView(activeStory.value.id);
}

async function toggleStoryLike() {
  const story = activeStory.value;
  if (!story || isTogglingLike.value) return;
  isTogglingLike.value = true;
  try {
    const reaction = story.likedByViewer
      ? await ContentService.unlikeStory(story.id)
      : await ContentService.likeStory(story.id);
    replaceActiveStory({
      ...story,
      likedByViewer: reaction.liked,
      likeCount: reaction.likeCount,
    });
  } finally {
    isTogglingLike.value = false;
  }
}

function replaceActiveStory(story: Story) {
  if (!group.value) return;
  group.value = {
    ...group.value,
    stories: group.value.stories.map((item) => item.id === story.id ? story : item),
  };
}

function activeDurationMs(): number {
  const duration = Number(activeStory.value?.durationMs || firstMedia(activeStory.value)?.data.durationMs || 5000);
  return Math.min(Math.max(duration, 1000), 60000);
}

async function next() {
  captionOpen.value = false;
  if (storyIndex.value < stories.value.length - 1) {
    storyIndex.value += 1;
    await recordActiveView();
    startProgress();
    return;
  }
  if (!isArchive.value) {
    const nextAuthor = group.value ? nextAuthorAfter(rail.value, group.value.authorId) : null;
    if (nextAuthor?.storyIds[0]) {
      await router.replace({
        path: `/story/${encodeURIComponent(nextAuthor.storyIds[0])}`,
        query: { author: nextAuthor.authorId },
      });
      return;
    }
  }
  close();
}

function prev() {
  captionOpen.value = false;
  if (storyIndex.value > 0) {
    storyIndex.value -= 1;
  }
  startProgress();
}

function close() {
  const back = typeof route.query.from === "string" ? route.query.from : "/";
  void router.push(back);
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "ArrowRight") void next();
  if (event.key === "ArrowLeft") prev();
  if (event.key === "Escape") close();
  if (event.key === " ") {
    event.preventDefault();
    lockedPaused.value = !lockedPaused.value;
  }
}

function source(block: StoryBlock): string {
  return ContentService.mediaSource(block);
}

function text(block: StoryBlock): string {
  const value = block.data.text || block.data.fileName;
  return typeof value === "string" ? value : block.type.toLowerCase();
}

function firstMedia(story: Story | null): StoryBlock | undefined {
  return story?.blocks.find((block) => block.type !== "TEXT");
}

function secondsUntil(value?: string | null): number {
  if (!value) return 0;
  return Math.max(0, Math.floor((Date.parse(value) - Date.now()) / 1000));
}

function formatArchiveTime(value?: string | null): string {
  if (!value) return "";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

async function syncMediaPlayback() {
  await nextTick();
  const media = document.querySelector<HTMLVideoElement | HTMLAudioElement>(".story-stage video, .story-stage audio");
  if (!media) return;
  if (isPaused.value) {
    media.pause();
  } else {
    await media.play().catch(() => undefined);
  }
}
</script>

<template>
  <section
    class="story-viewer"
    :class="{ 'story-viewer--caption-open': captionOpen }"
    @pointerdown="holdPaused = true"
    @pointerup="holdPaused = false"
    @pointercancel="holdPaused = false"
  >
    <div class="story-progress">
      <span
        v-for="(storyItem, index) in stories"
        :key="storyItem.id"
        class="story-progress__track"
      >
        <span :style="{ width: index < storyIndex ? '100%' : index === storyIndex ? `${progress}%` : '0%' }"></span>
      </span>
    </div>

    <header class="story-meta">
      <span class="story-meta__avatar">
        <img v-if="authorAvatar" :src="authorAvatar" alt="" />
        <i v-else class="pi pi-user"></i>
      </span>
      <strong>{{ authorName }}</strong>
      <i v-if="showCloseFriends" class="pi pi-star-fill story-meta__star" aria-label="Close friends"></i>
      <span class="story-meta__time">{{ lifetimeLabel }}</span>
    </header>

    <button type="button" class="story-close" aria-label="Close story" @click="close"><i class="pi pi-times"></i></button>
    <button type="button" class="story-pause" aria-label="Toggle pause" @click.stop="lockedPaused = !lockedPaused">
      <i :class="isPaused ? 'pi pi-play' : 'pi pi-pause'"></i>
    </button>
    <button type="button" class="story-mute" aria-label="Toggle mute" @click.stop="muted = !muted">
      <i :class="muted ? 'pi pi-volume-off' : 'pi pi-volume-up'"></i>
    </button>
    <button
      type="button"
      class="story-like"
      :class="{ active: activeLiked }"
      :disabled="isTogglingLike"
      :aria-label="activeLiked ? 'Unlike story' : 'Like story'"
      @pointerdown.stop
      @click.stop="toggleStoryLike"
    >
      <i :class="activeLiked ? 'pi pi-heart-fill' : 'pi pi-heart'"></i>
      <span>{{ activeLikeCount }}</span>
    </button>

    <button type="button" class="tap-zone tap-zone--left" aria-label="Previous story" @click.stop="prev"></button>
    <button type="button" class="tap-zone tap-zone--right" aria-label="Next story" @click.stop="next"></button>

    <main v-if="activeStory" class="story-stage">
      <article v-for="block in renderBlocks" :key="block.id || text(block)" class="story-block">
        <img v-if="block.type === 'IMAGE' && source(block)" :src="source(block)" alt="" />
        <video v-else-if="block.type === 'VIDEO' && source(block)" :src="source(block)" autoplay playsinline :muted="muted" />
        <audio v-else-if="block.type === 'AUDIO' && source(block)" :src="source(block)" autoplay :muted="muted" controls />
      </article>
    </main>

    <button v-if="storyCaption" type="button" class="caption-sheet" :class="{ open: captionOpen }" @click.stop="captionOpen = !captionOpen">
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
  background: #0b0f14;
  color: #ffffff;
}

.story-viewer--caption-open::before {
  content: "";
  position: fixed;
  z-index: 204;
  inset: 0;
  background: rgba(0, 0, 0, 0.38);
  pointer-events: none;
}

.story-progress {
  position: fixed;
  z-index: 207;
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

.story-meta {
  position: fixed;
  z-index: 208;
  top: 34px;
  left: 22px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  max-width: min(52vw, 360px);
  border-radius: 999px;
  padding: 6px 12px 6px 6px;
  background: rgba(0, 0, 0, 0.32);
  backdrop-filter: blur(14px);
}

.story-meta__avatar {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border-radius: 999px;
  background: linear-gradient(135deg, #f97316, #8b5cf6);
}

.story-meta__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.story-meta strong,
.story-meta__time {
  overflow: hidden;
  font-size: 13px;
  font-weight: 900;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.story-meta__time {
  color: rgba(255, 255, 255, 0.72);
}

.story-meta__star {
  color: #86efac;
  font-size: 13px;
}

.story-close,
.story-mute,
.story-pause,
.story-like {
  position: fixed;
  z-index: 208;
  top: 36px;
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
  cursor: pointer;
}

.story-like {
  right: 172px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: auto;
  min-width: 54px;
  padding: 0 13px;
  font: inherit;
  font-size: 12px;
  font-weight: 900;
}

.story-like.active {
  background: rgba(225, 29, 72, 0.92);
}

.story-like:disabled {
  opacity: 0.72;
  cursor: progress;
}

.story-close {
  right: 22px;
}

.story-mute {
  right: 72px;
}

.story-pause {
  right: 122px;
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
  background: #111827;
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

.caption-sheet {
  position: fixed;
  z-index: 209;
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

@media (max-width: 720px) {
  .story-meta {
    max-width: calc(100vw - 176px);
  }

  .story-pause {
    right: 116px;
  }

  .story-stage {
    width: 100vw;
    height: 100dvh;
    border-radius: 0;
  }
}
</style>
