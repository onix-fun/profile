<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import type { AccountProfile, Story, StoryBlock } from "@/api/types";
import { displayUsername, safeDisplayText } from "@/features/display/displayText";

interface DayGroup {
  id: string;
  label: string;
  tooltip: string;
  stories: Story[];
}

const route = useRoute();
const router = useRouter();
const profile = ref<AccountProfile | null>(null);
const stories = ref<Story[]>([]);
const nextCursor = ref<string | null>(null);
const isLoading = ref(false);
const activeDayIndex = ref(0);
const isTransitioning = ref(false);
let wheelLock = false;

const isOrganizationRoute = computed(() => route.name === "OrganizationStoryArchive");
const nickname = computed(() => String(route.params.nickname || route.params.orgname || ""));
const ownerType = computed<"USER" | "ORGANIZATION">(() => isOrganizationRoute.value ? "ORGANIZATION" : "USER");
const ownerName = computed(() => displayUsername(profile.value?.username, "Profile"));
const dayGroups = computed<DayGroup[]>(() => {
  const groups = new Map<string, Story[]>();
  for (const story of stories.value) {
    const date = storyDate(story);
    const key = `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
    const list = groups.get(key) || [];
    list.push(story);
    groups.set(key, list);
  }
  return Array.from(groups.entries())
    .map(([id, groupStories]) => {
      const sorted = [...groupStories].sort((a, b) => storyDate(a).getTime() - storyDate(b).getTime());
      const date = storyDate(sorted[0]);
      return {
        id,
        label: new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(date),
        tooltip: new Intl.DateTimeFormat(undefined, { weekday: "short", month: "short", day: "numeric", year: "numeric" }).format(date),
        stories: sorted,
      };
    })
    .sort((a, b) => storyDate(a.stories[0]).getTime() - storyDate(b.stories[0]).getTime());
});
const activeDay = computed(() => dayGroups.value[activeDayIndex.value] || null);

onMounted(() => {
  void loadArchive();
});

onBeforeUnmount(() => {
  wheelLock = false;
});

async function loadArchive(cursor?: string | null) {
  isLoading.value = true;
  try {
    if (!profile.value) {
      const response = isOrganizationRoute.value
        ? await ProfileService.getOrganization(nickname.value)
        : await ProfileService.getProfile(nickname.value);
      profile.value = response.profile || null;
    }
    if (!profile.value) return;
    const archive = await ContentService.storyArchive(profile.value.id, cursor, 80, profile.value.ownerType || ownerType.value);
    stories.value = cursor ? [...stories.value, ...archive.stories] : archive.stories;
    nextCursor.value = archive.nextCursor || null;
    activeDayIndex.value = clamp(activeDayIndex.value, 0, Math.max(0, dayGroups.value.length - 1));
  } finally {
    isLoading.value = false;
  }
}

function openStory(story: Story) {
  void router.push({
    path: `/story/${encodeURIComponent(story.id)}`,
    query: {
      author: story.authorId,
      ownerType: story.ownerType || profile.value?.ownerType || ownerType.value,
      archive: "1",
      from: route.fullPath,
    },
  });
}

function close() {
  void router.push(`/${isOrganizationRoute.value ? "o" : "u"}/${encodeURIComponent(nickname.value)}`);
}

function firstMedia(story: Story): StoryBlock | undefined {
  return story.blocks.find((block) => block.type !== "TEXT");
}

function source(story: Story): string {
  const media = firstMedia(story);
  return media ? ContentService.mediaSource(media) : "";
}

function title(story: Story): string {
  const textBlock = story.blocks.find((block) => block.type === "TEXT" && typeof block.data.text === "string");
  const media = firstMedia(story);
  return safeDisplayText(textBlock?.data.text, safeDisplayText(media?.data.fileName, "Story"));
}

function dateLabel(story: Story): string {
  const date = storyDate(story);
  return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(date);
}

function storyDate(story: Story): Date {
  const date = new Date(story.createdAt || story.expiresAt || Date.now());
  return Number.isNaN(date.getTime()) ? new Date() : date;
}

function setActiveDay(index: number) {
  const next = clamp(index, 0, dayGroups.value.length - 1);
  if (next === activeDayIndex.value) return;
  activeDayIndex.value = next;
  isTransitioning.value = true;
  window.setTimeout(() => {
    isTransitioning.value = false;
  }, 360);
}

function onWheel(event: WheelEvent) {
  if (Math.abs(event.deltaY) < Math.abs(event.deltaX) || Math.abs(event.deltaY) < 16 || wheelLock) return;
  event.preventDefault();
  wheelLock = true;
  setActiveDay(activeDayIndex.value + (event.deltaY > 0 ? 1 : -1));
  window.setTimeout(() => {
    wheelLock = false;
  }, 520);
}

function depthClass(index: number): string {
  const delta = index - activeDayIndex.value;
  if (delta === 0) return "is-active";
  if (delta === -1) return "is-before";
  if (delta === 1) return "is-after";
  return "is-hidden";
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
</script>

<template>
  <section class="archive-page" @wheel="onWheel">
    <div class="particles" aria-hidden="true">
      <span v-for="index in 18" :key="index"></span>
    </div>

    <header class="archive-header">
      <button type="button" aria-label="Close archive" @click="close"><i class="pi pi-times"></i></button>
      <div>
        <strong>{{ ownerName }}</strong>
        <span>{{ activeDay?.tooltip || "Story archive" }}</span>
      </div>
    </header>

    <main class="archive-depth" aria-label="Story archive by day">
      <section
        v-for="(group, index) in dayGroups"
        :key="group.id"
        class="archive-day"
        :class="[depthClass(index), { 'is-transitioning': isTransitioning }]"
        :aria-hidden="index !== activeDayIndex"
      >
        <div class="archive-day__rail">
          <button
            v-for="story in group.stories"
            :key="story.id"
            type="button"
            class="archive-card"
            :class="{ 'archive-card--close': story.closeFriends || story.visibility === 'CLOSE_FRIENDS' }"
            @click="openStory(story)"
          >
            <span class="archive-preview">
              <img v-if="firstMedia(story)?.type === 'IMAGE' && source(story)" :src="source(story)" alt="" />
              <video v-else-if="firstMedia(story)?.type === 'VIDEO' && source(story)" :src="source(story)" muted playsinline preload="metadata" />
              <i v-else class="pi pi-stopwatch"></i>
            </span>
            <strong>{{ title(story) }}</strong>
            <small>
              <i v-if="story.closeFriends || story.visibility === 'CLOSE_FRIENDS'" class="pi pi-star-fill"></i>
              {{ dateLabel(story) }}
            </small>
          </button>
        </div>
      </section>

      <button v-if="nextCursor" type="button" class="archive-more" :disabled="isLoading" @click="loadArchive(nextCursor)">
        {{ isLoading ? "Loading" : "More" }}
      </button>

      <p v-if="!isLoading && !stories.length" class="archive-empty">No archived stories</p>
    </main>

    <nav v-if="dayGroups.length" class="archive-nav" aria-label="Archive days">
      <button
        v-for="(group, index) in dayGroups"
        :key="group.id"
        type="button"
        class="archive-tick"
        :class="{ 'is-active': index === activeDayIndex }"
        :aria-label="group.tooltip"
        :data-tooltip="group.tooltip"
        @click="setActiveDay(index)"
      ></button>
    </nav>
  </section>
</template>

<style scoped>
.archive-page {
  position: fixed;
  z-index: 190;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 22% 18%, rgba(45, 212, 191, 0.18), transparent 28%),
    radial-gradient(circle at 78% 22%, rgba(129, 140, 248, 0.16), transparent 30%),
    radial-gradient(circle at 52% 82%, rgba(34, 197, 94, 0.12), transparent 28%),
    #06080d;
  color: #ffffff;
  perspective: 1200px;
}

.particles {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.particles span {
  position: absolute;
  width: 4px;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.38);
  animation: drift 12s linear infinite;
}

.particles span:nth-child(3n) {
  background: rgba(134, 239, 172, 0.55);
}

.particles span:nth-child(1) { left: 8%; top: 12%; animation-delay: -1s; }
.particles span:nth-child(2) { left: 18%; top: 72%; animation-delay: -5s; }
.particles span:nth-child(3) { left: 28%; top: 32%; animation-delay: -2s; }
.particles span:nth-child(4) { left: 38%; top: 82%; animation-delay: -7s; }
.particles span:nth-child(5) { left: 48%; top: 18%; animation-delay: -3s; }
.particles span:nth-child(6) { left: 58%; top: 64%; animation-delay: -8s; }
.particles span:nth-child(7) { left: 68%; top: 26%; animation-delay: -4s; }
.particles span:nth-child(8) { left: 78%; top: 78%; animation-delay: -9s; }
.particles span:nth-child(9) { left: 88%; top: 38%; animation-delay: -6s; }
.particles span:nth-child(10) { left: 12%; top: 44%; animation-delay: -10s; }
.particles span:nth-child(11) { left: 24%; top: 20%; animation-delay: -2s; }
.particles span:nth-child(12) { left: 34%; top: 58%; animation-delay: -11s; }
.particles span:nth-child(13) { left: 44%; top: 76%; animation-delay: -4s; }
.particles span:nth-child(14) { left: 54%; top: 36%; animation-delay: -8s; }
.particles span:nth-child(15) { left: 64%; top: 12%; animation-delay: -3s; }
.particles span:nth-child(16) { left: 74%; top: 54%; animation-delay: -7s; }
.particles span:nth-child(17) { left: 84%; top: 16%; animation-delay: -1s; }
.particles span:nth-child(18) { left: 92%; top: 68%; animation-delay: -5s; }

@keyframes drift {
  from { transform: translate3d(0, 18px, 0); opacity: 0.2; }
  50% { opacity: 0.8; }
  to { transform: translate3d(16px, -32px, 0); opacity: 0.2; }
}

.archive-header {
  position: fixed;
  z-index: 5;
  top: max(18px, env(safe-area-inset-top));
  left: 22px;
  right: 22px;
  display: flex;
  align-items: center;
  gap: 14px;
}

.archive-header button {
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #ffffff;
  cursor: pointer;
}

.archive-header div {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.archive-header strong,
.archive-header span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-header strong {
  font-size: 18px;
  font-weight: 900;
}

.archive-header span {
  color: rgba(255, 255, 255, 0.68);
  font-size: 12px;
  font-weight: 800;
}

.archive-depth {
  position: relative;
  z-index: 2;
  height: 100%;
  overflow: hidden;
}

.archive-day {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  transform-style: preserve-3d;
  pointer-events: none;
  opacity: 0;
  transition: transform 360ms ease, opacity 360ms ease, filter 360ms ease;
}

.archive-day.is-active {
  pointer-events: auto;
  opacity: 1;
  transform: translate3d(0, 0, 0) scale(1);
  filter: blur(0);
}

.archive-day.is-before {
  opacity: 0.22;
  transform: translate3d(0, -34vh, -260px) scale(0.72);
  filter: blur(3px);
}

.archive-day.is-after {
  opacity: 0.22;
  transform: translate3d(0, 34vh, -260px) scale(0.72);
  filter: blur(3px);
}

.archive-day.is-hidden {
  transform: translate3d(0, 0, -520px) scale(0.54);
}

.archive-day__rail {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 30px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 94px max(28px, 8vw) 112px;
  scroll-snap-type: x proximity;
}

.archive-card,
.archive-more {
  border: 0;
  color: #ffffff;
  font: inherit;
  cursor: pointer;
}

.archive-card {
  width: 188px;
  min-width: 188px;
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 0;
  background: transparent;
  text-align: center;
  scroll-snap-align: center;
  transition: transform 180ms ease, filter 180ms ease;
}

.archive-card:hover,
.archive-card:focus-visible {
  transform: translateY(-4px) scale(1.02);
}

.archive-preview {
  position: relative;
  width: 178px;
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 7px solid transparent;
  border-radius: 999px;
  background:
    linear-gradient(#101827, #101827) padding-box,
    conic-gradient(from 210deg, #22d3ee, #818cf8 42%, #22c55e 74%, #22d3ee) border-box;
  box-shadow:
    0 28px 88px rgba(0, 0, 0, 0.34),
    0 0 34px rgba(45, 212, 191, 0.18);
}

.archive-preview::before,
.archive-preview::after {
  content: "";
  position: absolute;
  z-index: 2;
  border-radius: 999px;
  pointer-events: none;
}

.archive-preview::before {
  inset: 10px;
  border: 1px solid rgba(255, 255, 255, 0.14);
}

.archive-preview::after {
  right: 12%;
  bottom: 9%;
  width: 12px;
  height: 12px;
  background: #ffffff;
  box-shadow:
    -112px -126px 0 -3px rgba(34, 211, 238, 0.72),
    30px -118px 0 -4px rgba(34, 197, 94, 0.72);
}

.archive-preview img,
.archive-preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.archive-card strong {
  width: 100%;
  overflow: hidden;
  font-size: 13px;
  font-weight: 900;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-card small {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.66);
  font-size: 11px;
  font-weight: 800;
  backdrop-filter: blur(14px);
}

.archive-card--close .archive-preview {
  background:
    linear-gradient(#101827, #101827) padding-box,
    conic-gradient(from 220deg, #22c55e, #86efac 45%, #22d3ee 76%, #22c55e) border-box;
}

.archive-card--close small i {
  color: #86efac;
}

.archive-more {
  position: fixed;
  z-index: 4;
  right: 22px;
  bottom: 72px;
  width: 118px;
  min-width: 118px;
  height: 42px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
  font-weight: 900;
}

.archive-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  margin: 0;
  color: rgba(255, 255, 255, 0.68);
  text-align: center;
  font-weight: 900;
}

.archive-nav {
  position: fixed;
  z-index: 6;
  left: 50%;
  bottom: max(18px, env(safe-area-inset-bottom));
  max-width: calc(100vw - 32px);
  display: flex;
  align-items: end;
  gap: 10px;
  overflow-x: auto;
  border-radius: 999px;
  padding: 12px 16px;
  background: rgba(9, 13, 20, 0.42);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(18px);
  transform: translateX(-50%);
}

.archive-tick {
  position: relative;
  width: 12px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
}

.archive-tick::before {
  content: "";
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.42);
  transform: translateX(-50%);
  transition: height 160ms ease, background 160ms ease, box-shadow 160ms ease;
}

.archive-tick.is-active::before,
.archive-tick:hover::before,
.archive-tick:focus-visible::before {
  height: 28px;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 0 18px rgba(45, 212, 191, 0.42);
}

.archive-tick::after {
  content: attr(data-tooltip);
  position: absolute;
  left: 50%;
  bottom: 42px;
  width: max-content;
  max-width: 180px;
  padding: 6px 9px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
  font-size: 11px;
  font-weight: 900;
  opacity: 0;
  pointer-events: none;
  transform: translateX(-50%) translateY(4px);
  transition: opacity 160ms ease, transform 160ms ease;
  backdrop-filter: blur(14px);
}

.archive-tick:hover::after,
.archive-tick:focus-visible::after {
  opacity: 1;
  transform: translateX(-50%) translateY(0);
}

@media (max-width: 720px) {
  .archive-day__rail {
    padding-inline: 22px;
  }

  .archive-card,
  .archive-preview {
    width: 154px;
    min-width: 154px;
  }
}
</style>
