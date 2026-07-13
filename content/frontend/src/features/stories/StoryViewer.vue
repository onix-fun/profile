<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { CSSProperties } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import { redirectToAccount } from "@/api/authRedirect";
import { profileUrl } from "@/api/navigation";
import type { CurrentActor, Story, StoryBlock, StoryGroup, StoryRailItem } from "@/api/types";
import { displayUsername } from "@/features/display/displayText";
import { mergeSeenState, nextAuthorAfter, sortStoryRail } from "@/features/stories/storyState";

const route = useRoute();
const router = useRouter();
const group = ref<StoryGroup | null>(null);
const rail = ref<StoryRailItem[]>([]);
const currentActor = ref<CurrentActor | null>(null);
const storyIndex = ref(0);
const progress = ref(0);
const holdPaused = ref(false);
const lockedPaused = ref(false);
const muted = ref(true);
const captionOpen = ref(false);
const isTogglingLike = ref(false);
const ringRadius = 54;
const ringCircumference = 2 * Math.PI * ringRadius;
let pointerStartX = 0;
let pointerStartY = 0;
let pointerStartedAt = 0;
let pointerMoved = false;
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
const authorPath = computed(() => {
  const ownerType = group.value?.author?.ownerType || group.value?.ownerType || "USER";
  const username = group.value?.author?.username || group.value?.authorName || "user";
  return profileUrl(`/${ownerType === "ORGANIZATION" ? "o" : "u"}/${encodeURIComponent(username)}`, true);
});
const showCloseFriends = computed(() => Boolean(activeStory.value?.closeFriends || activeStory.value?.visibility === "CLOSE_FRIENDS"));
const activeLikeCount = computed(() => activeStory.value?.likeCount || 0);
const activeLiked = computed(() => Boolean(activeStory.value?.likedByViewer));
const canDeleteStory = computed(() => Boolean(
  activeStory.value
    && currentActor.value
    && (activeStory.value.ownerId || activeStory.value.authorId) === currentActor.value.activeOwner.id
    && (activeStory.value.ownerType || "USER") === (currentActor.value.activeOwner.ownerType || "USER"),
));
const progressDashOffset = computed(() => ringCircumference * (1 - progress.value / 100));
const lifetimeLabel = computed(() => {
  if (isArchive.value) return formatArchiveTime(activeStory.value?.createdAt);
  const seconds = activeStory.value?.remainingLifeSeconds ?? secondsUntil(activeStory.value?.expiresAt);
  if (seconds <= 60) return "now";
  const hours = Math.floor(seconds / 3600);
  if (hours > 0) return `${hours}h`;
  return `${Math.max(1, Math.floor(seconds / 60))}m`;
});

onMounted(() => {
  void initialize();
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
  let ownerType: "USER" | "ORGANIZATION" = route.query.ownerType === "ORGANIZATION" ? "ORGANIZATION" : "USER";
  if (!authorId) {
    const loaded = await ContentService.story(startStoryId);
    authorId = loaded.ownerId || loaded.authorId;
    ownerType = loaded.ownerType || "USER";
  }
  if (!isArchive.value) {
    rail.value = sortStoryRail(mergeSeenState(await ContentService.storiesFeed()));
  }
  group.value = await ContentService.storyGroup(authorId, startStoryId, isArchive.value, ownerType);
  const startIndex = stories.value.findIndex((story) => story.id === (group.value?.startStoryId || startStoryId));
  storyIndex.value = Math.max(0, startIndex);
  await recordActiveView();
  startProgress();
}

async function initialize() {
  currentActor.value = await ContentService.currentActor().catch(() => null);
  await loadQueue();
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
  if (!currentActor.value) return;
  await ContentService.recordStoryView(activeStory.value.id).catch(() => undefined);
}

async function toggleStoryLike() {
  const story = activeStory.value;
  if (!story || isTogglingLike.value) return;
  if (!currentActor.value) return redirectToAccount();
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

async function deleteActiveStory() {
  const story = activeStory.value;
  if (!story || !canDeleteStory.value || !window.confirm("Delete this story?")) return;
  await ContentService.deleteStory(story.id);
  if (!group.value) return close();
  const nextStories = group.value.stories.filter((item) => item.id !== story.id);
  if (nextStories.length === 0) {
    close();
    return;
  }
  group.value = { ...group.value, stories: nextStories };
  storyIndex.value = Math.min(storyIndex.value, nextStories.length - 1);
  startProgress();
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
    await switchToStory(storyIndex.value + 1);
    return;
  }
  if (!isArchive.value) {
    const nextAuthor = group.value ? nextAuthorAfter(rail.value, group.value.ownerId || group.value.authorId, group.value.ownerType || "USER") : null;
    if (nextAuthor?.storyIds[0]) {
      await router.replace({
        path: `/story/${encodeURIComponent(nextAuthor.storyIds[0])}`,
        query: { author: nextAuthor.ownerId || nextAuthor.authorId, ownerType: nextAuthor.ownerType || "USER" },
      });
      return;
    }
  }
  close();
}

async function prev() {
  captionOpen.value = false;
  if (storyIndex.value > 0) {
    await switchToStory(storyIndex.value - 1);
  }
}

async function switchToStory(index: number) {
  if (index < 0 || index >= stories.value.length || index === storyIndex.value) return;
  captionOpen.value = false;
  storyIndex.value = index;
  await recordActiveView();
  startProgress();
}

function close() {
  const back = typeof route.query.from === "string" ? route.query.from : "/";
  void router.push(back);
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "ArrowRight") void next();
  if (event.key === "ArrowLeft") void prev();
  if (event.key === "Escape") close();
  if (event.key === " ") {
    event.preventDefault();
    lockedPaused.value = !lockedPaused.value;
  }
}

function onViewerPointerDown(event: PointerEvent) {
  pointerStartX = event.clientX;
  pointerStartY = event.clientY;
  pointerStartedAt = Date.now();
  pointerMoved = false;
  holdPaused.value = true;
}

function onViewerPointerMove(event: PointerEvent) {
  const deltaX = event.clientX - pointerStartX;
  const deltaY = event.clientY - pointerStartY;
  pointerMoved = pointerMoved || Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10;
}

function onViewerPointerUp(event: PointerEvent) {
  holdPaused.value = false;
  const deltaX = event.clientX - pointerStartX;
  const deltaY = event.clientY - pointerStartY;
  const elapsed = Date.now() - pointerStartedAt;
  if (Math.abs(deltaX) > 48 && Math.abs(deltaX) > Math.abs(deltaY) * 1.15) {
    void (deltaX < 0 ? next() : prev());
    return;
  }
  if (!pointerMoved && elapsed < 260) {
    lockedPaused.value = false;
  }
}

function onViewerPointerCancel() {
  holdPaused.value = false;
}

function orbitDotStyle(index: number): CSSProperties {
  const total = Math.max(stories.value.length, 1);
  const angle = -90 + (360 / total) * index;
  return {
    "--orbit-angle": `${angle}deg`,
    "--orbit-delay": `${index * 42}ms`,
  } as CSSProperties;
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
  const media = document.querySelector<HTMLVideoElement | HTMLAudioElement>(".story-orb video, .story-orb audio");
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
    @pointerdown="onViewerPointerDown"
    @pointermove="onViewerPointerMove"
    @pointerup="onViewerPointerUp"
    @pointercancel="onViewerPointerCancel"
  >
    <div class="story-ambient" aria-hidden="true"></div>

    <div class="story-control-cluster" @pointerdown.stop>
      <button type="button" class="story-control" aria-label="Toggle pause" @click.stop="lockedPaused = !lockedPaused">
        <i :class="isPaused ? 'pi pi-play' : 'pi pi-pause'"></i>
      </button>
      <button type="button" class="story-control" aria-label="Toggle mute" @click.stop="muted = !muted">
        <i :class="muted ? 'pi pi-volume-off' : 'pi pi-volume-up'"></i>
      </button>
      <button type="button" class="story-control story-control--close" aria-label="Close story" @click.stop="close">
        <i class="pi pi-times"></i>
      </button>
    </div>

    <main class="story-orbit-scene" aria-label="Story viewer">
      <a class="story-node story-node--author" :href="authorPath" @pointerdown.stop>
        <span class="story-node__avatar">
          <img v-if="authorAvatar" :src="authorAvatar" alt="" />
          <i v-else-if="(group?.author?.ownerType || group?.ownerType) === 'ORGANIZATION'" class="pi pi-building"></i>
          <i v-else class="pi pi-user"></i>
        </span>
        <span>
          <strong>{{ authorName }}</strong>
          <small>{{ lifetimeLabel }}</small>
        </span>
      </a>

      <section class="story-node story-node--time" @pointerdown.stop>
        <i v-if="showCloseFriends" class="pi pi-star-fill" aria-label="Close friends"></i>
        <span>{{ isArchive ? "Archive" : "Live" }}</span>
        <strong>{{ lifetimeLabel }}</strong>
      </section>

      <section v-if="storyCaption || storyTags.length" class="story-node story-node--caption" :class="{ open: captionOpen }" @pointerdown.stop>
        <button type="button" class="story-caption-toggle" @click.stop="captionOpen = !captionOpen">
          <span>{{ storyCaption || "Story notes" }}</span>
          <strong v-if="storyTags.length">{{ storyTags.map((tag) => `#${tag}`).join(" ") }}</strong>
          <i :class="captionOpen ? 'pi pi-chevron-down' : 'pi pi-chevron-up'"></i>
        </button>
      </section>

      <section class="story-node story-node--actions" @pointerdown.stop>
        <button
          v-if="canDeleteStory"
          type="button"
          class="story-delete"
          aria-label="Delete story"
          @click.stop="deleteActiveStory"
        >
          <i class="pi pi-trash"></i>
        </button>
        <button
          type="button"
          class="story-like"
          :class="{ active: activeLiked }"
          :disabled="isTogglingLike"
          :aria-label="activeLiked ? 'Unlike story' : 'Like story'"
          @click.stop="toggleStoryLike"
        >
          <i :class="activeLiked ? 'pi pi-heart-fill' : 'pi pi-heart'"></i>
          <span>{{ activeLikeCount }}</span>
        </button>
        <span v-if="showCloseFriends" class="story-close-friends"><i class="pi pi-star-fill"></i>Close</span>
      </section>

      <div class="story-connector story-connector--author" aria-hidden="true"></div>
      <div class="story-connector story-connector--time" aria-hidden="true"></div>
      <div class="story-connector story-connector--caption" aria-hidden="true"></div>
      <div class="story-connector story-connector--actions" aria-hidden="true"></div>

      <section class="story-orb-shell" :class="{ 'is-paused': isPaused }">
        <svg class="story-progress-ring" viewBox="0 0 120 120" aria-hidden="true">
          <circle class="story-progress-ring__track" cx="60" cy="60" :r="ringRadius" />
          <circle
            class="story-progress-ring__value"
            cx="60"
            cy="60"
            :r="ringRadius"
            :stroke-dasharray="ringCircumference"
            :stroke-dashoffset="progressDashOffset"
          />
        </svg>

        <div class="story-orbit-selector" aria-label="Stories in this group">
          <button
            v-for="(storyItem, index) in stories"
            :key="storyItem.id"
            type="button"
            class="story-orbit-dot"
            :class="{ 'is-active': index === storyIndex, 'is-complete': index < storyIndex }"
            :style="orbitDotStyle(index)"
            :aria-label="`Open story ${index + 1}`"
            @pointerdown.stop
            @click.stop="switchToStory(index)"
          ></button>
        </div>

        <Transition name="orb-swap" mode="out-in">
          <main v-if="activeStory" :key="activeStory.id" class="story-orb">
            <article v-for="block in renderBlocks" :key="block.id || text(block)" class="story-block">
              <img v-if="block.type === 'IMAGE' && source(block)" :src="source(block)" alt="" />
              <video v-else-if="block.type === 'VIDEO' && source(block)" :src="source(block)" autoplay playsinline :muted="muted" />
              <audio v-else-if="block.type === 'AUDIO' && source(block)" :src="source(block)" autoplay :muted="muted" controls />
            </article>
          </main>
        </Transition>
      </section>
    </main>
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
  background:
    radial-gradient(circle at 20% 20%, rgba(45, 212, 191, 0.16), transparent 30%),
    radial-gradient(circle at 80% 16%, rgba(129, 140, 248, 0.16), transparent 30%),
    radial-gradient(circle at 52% 84%, rgba(34, 197, 94, 0.12), transparent 26%),
    #05070b;
  color: #ffffff;
  touch-action: pan-y;
  user-select: none;
}

.story-viewer--caption-open::before {
  content: "";
  position: fixed;
  z-index: 204;
  inset: 0;
  background: rgba(0, 0, 0, 0.34);
  pointer-events: none;
}

.story-ambient {
  position: fixed;
  inset: 8%;
  border-radius: 999px;
  background:
    conic-gradient(from 160deg, rgba(45, 212, 191, 0.12), transparent 20%, rgba(99, 102, 241, 0.14), transparent 58%, rgba(34, 197, 94, 0.1)),
    radial-gradient(circle, rgba(255, 255, 255, 0.06), transparent 62%);
  filter: blur(22px);
  opacity: 0.8;
  pointer-events: none;
}

.story-control-cluster {
  position: fixed;
  z-index: 211;
  top: max(18px, env(safe-area-inset-top));
  right: 20px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.story-control,
.story-like,
.story-delete {
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.13);
  color: #ffffff;
  cursor: pointer;
  backdrop-filter: blur(16px);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
  transition: transform 180ms ease, background 180ms ease;
}

.story-control:hover,
.story-like:hover,
.story-delete:hover {
  transform: translateY(-1px) scale(1.03);
  background: rgba(255, 255, 255, 0.2);
}

.story-control--close {
  background: rgba(15, 23, 42, 0.72);
}

.story-orbit-scene {
  position: relative;
  z-index: 205;
  width: min(920px, calc(100vw - 40px));
  height: min(760px, calc(100dvh - 42px));
  display: grid;
  place-items: center;
}

.story-orb-shell {
  --orb-size: min(54vw, 420px);
  position: relative;
  width: var(--orb-size);
  height: var(--orb-size);
  display: grid;
  place-items: center;
  animation: orb-breathe 4.8s ease-in-out infinite;
}

.story-orb-shell.is-paused {
  animation-play-state: paused;
}

.story-orb {
  position: relative;
  z-index: 3;
  width: calc(var(--orb-size) - 56px);
  height: calc(var(--orb-size) - 56px);
  display: grid;
  overflow: hidden;
  border-radius: 999px;
  background: #101827;
  box-shadow:
    0 36px 120px rgba(0, 0, 0, 0.5),
    inset 0 0 0 1px rgba(255, 255, 255, 0.16);
}

.story-block {
  display: grid;
  place-items: center;
  min-width: 0;
  min-height: 0;
}

.story-block img,
.story-block video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.story-block audio {
  width: 70%;
}

.story-progress-ring {
  position: absolute;
  z-index: 5;
  inset: 0;
  overflow: visible;
  transform: rotate(-90deg);
  filter: drop-shadow(0 0 18px rgba(45, 212, 191, 0.36));
  pointer-events: none;
}

.story-progress-ring__track,
.story-progress-ring__value {
  fill: none;
  stroke-linecap: round;
  stroke-width: 2.6;
}

.story-progress-ring__track {
  stroke: rgba(255, 255, 255, 0.16);
}

.story-progress-ring__value {
  stroke: #67e8f9;
  transition: stroke-dashoffset 80ms linear;
}

.story-orbit-selector {
  --orbit-radius: calc((var(--orb-size) / 2) + 3px);
  position: absolute;
  z-index: 7;
  inset: 50%;
}

.story-orbit-dot {
  position: absolute;
  left: 0;
  top: 0;
  width: 13px;
  height: 13px;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.36);
  box-shadow: 0 0 0 5px rgba(255, 255, 255, 0.07);
  cursor: pointer;
  transform:
    rotate(var(--orbit-angle))
    translate(var(--orbit-radius))
    rotate(calc(var(--orbit-angle) * -1))
    translate(-50%, -50%);
  transition: background 180ms ease, box-shadow 180ms ease, width 180ms ease, height 180ms ease;
  animation: orbit-dot-in 380ms ease var(--orbit-delay) both;
}

.story-orbit-dot.is-complete {
  background: rgba(34, 197, 94, 0.8);
}

.story-orbit-dot.is-active {
  width: 18px;
  height: 18px;
  background: #ffffff;
  box-shadow:
    0 0 0 6px rgba(45, 212, 191, 0.22),
    0 0 24px rgba(45, 212, 191, 0.62);
}

.story-node {
  position: absolute;
  z-index: 8;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  border-radius: 999px;
  padding: 8px 12px;
  background: rgba(9, 13, 20, 0.58);
  color: #ffffff;
  text-decoration: none;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.12),
    0 16px 42px rgba(0, 0, 0, 0.24);
  backdrop-filter: blur(18px);
}

.story-node::after,
.story-connector {
  content: "";
  position: absolute;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(148, 240, 229, 0.72), transparent);
  transform-origin: left center;
  pointer-events: none;
}

.story-node--author {
  top: 15%;
  left: 7%;
  max-width: 280px;
}

.story-node--author::after {
  right: -104px;
  top: 50%;
  width: 110px;
  transform: rotate(18deg);
}

.story-node--time {
  top: 18%;
  right: 12%;
}

.story-node--time::after {
  left: -118px;
  top: 54%;
  width: 124px;
  transform: rotate(160deg);
}

.story-node--caption {
  left: 7%;
  bottom: 12%;
  width: min(360px, 42vw);
  border-radius: 22px;
  padding: 0;
}

.story-node--caption::after {
  right: -128px;
  top: 30%;
  width: 136px;
  transform: rotate(-24deg);
}

.story-node--actions {
  right: 8%;
  bottom: 15%;
}

.story-node--actions::after {
  left: -110px;
  top: 42%;
  width: 118px;
  transform: rotate(198deg);
}

.story-node__avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 999px;
  background: conic-gradient(from 210deg, #22d3ee, #818cf8, #22c55e, #22d3ee);
}

.story-node__avatar img {
  width: 100%;
  height: 100%;
  border: 2px solid rgba(255, 255, 255, 0.86);
  border-radius: inherit;
  object-fit: cover;
}

.story-node span {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.story-node strong,
.story-node small,
.story-node > span,
.story-close-friends {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.story-node strong {
  font-size: 13px;
  font-weight: 900;
}

.story-node small,
.story-node > span {
  color: rgba(255, 255, 255, 0.68);
  font-size: 11px;
  font-weight: 800;
}

.story-node--time i,
.story-close-friends i {
  color: #86efac;
}

.story-caption-toggle {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 10px;
  border: 0;
  border-radius: inherit;
  padding: 13px 15px;
  background: transparent;
  color: #ffffff;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.story-caption-toggle span {
  max-height: 42px;
  overflow: hidden;
  color: #ffffff;
  font-size: 13px;
  font-weight: 850;
  line-height: 1.3;
  white-space: pre-wrap;
}

.story-node--caption.open .story-caption-toggle span {
  max-height: min(28dvh, 220px);
  overflow: auto;
}

.story-caption-toggle strong {
  grid-column: 1;
  color: #99f6e4;
  font-size: 11px;
  font-weight: 900;
}

.story-caption-toggle i {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
}

.story-like {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: auto;
  min-width: 58px;
  padding: 0 14px;
  font: inherit;
  font-size: 12px;
  font-weight: 900;
}

.story-delete {
  display: grid;
  place-items: center;
  color: #fecaca;
}

.story-like.active {
  background: rgba(225, 29, 72, 0.88);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.18),
    0 0 28px rgba(225, 29, 72, 0.34);
}

.story-like:disabled {
  opacity: 0.7;
  cursor: wait;
}

.story-close-friends {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 900;
}

.orb-swap-enter-active,
.orb-swap-leave-active {
  transition: opacity 220ms ease, transform 220ms ease, filter 220ms ease;
}

.orb-swap-enter-from,
.orb-swap-leave-to {
  opacity: 0;
  filter: blur(10px);
  transform: scale(0.94) rotate(-2deg);
}

@keyframes orb-breathe {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.015); }
}

@keyframes orbit-dot-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@media (max-width: 820px) {
  .story-control-cluster {
    right: 14px;
    gap: 6px;
  }

  .story-control {
    width: 38px;
    height: 38px;
  }

  .story-orbit-scene {
    width: 100vw;
    height: 100dvh;
    padding: max(76px, env(safe-area-inset-top)) 14px max(28px, env(safe-area-inset-bottom));
    box-sizing: border-box;
    align-content: center;
  }

  .story-orb-shell {
    --orb-size: min(76vw, 360px);
  }

  .story-orb {
    width: calc(var(--orb-size) - 42px);
    height: calc(var(--orb-size) - 42px);
  }

  .story-orbit-dot {
    width: 10px;
    height: 10px;
  }

  .story-orbit-dot.is-active {
    width: 15px;
    height: 15px;
  }

  .story-node::after,
  .story-connector {
    display: none;
  }

  .story-node {
    border-radius: 18px;
  }

  .story-node--author {
    top: max(18px, env(safe-area-inset-top));
    left: 14px;
    right: 142px;
    max-width: none;
  }

  .story-node--time {
    top: calc(max(18px, env(safe-area-inset-top)) + 58px);
    left: 14px;
    right: auto;
    padding: 7px 10px;
  }

  .story-node--caption {
    left: 14px;
    right: 14px;
    bottom: max(18px, env(safe-area-inset-bottom));
    width: auto;
  }

  .story-node--actions {
    right: 14px;
    bottom: calc(max(18px, env(safe-area-inset-bottom)) + 88px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .story-orb-shell,
  .story-orbit-dot {
    animation: none;
  }

  .orb-swap-enter-active,
  .orb-swap-leave-active,
  .story-progress-ring__value,
  .story-control,
  .story-like,
  .story-delete {
    transition-duration: 1ms;
  }
}
</style>
