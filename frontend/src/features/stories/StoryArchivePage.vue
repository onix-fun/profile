<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import type { AccountProfile, Story, StoryBlock } from "@/api/types";
import { displayUsername, safeDisplayText } from "@/features/display/displayText";

const route = useRoute();
const router = useRouter();
const profile = ref<AccountProfile | null>(null);
const stories = ref<Story[]>([]);
const nextCursor = ref<string | null>(null);
const isLoading = ref(false);

const nickname = computed(() => String(route.params.nickname || ""));
const ownerName = computed(() => displayUsername(profile.value?.username, "Profile"));
const navItems = computed(() => {
  const groups = new Map<string, string>();
  stories.value.forEach((story) => {
    const date = new Date(story.createdAt || story.expiresAt || Date.now());
    const key = `${date.getFullYear()}-${date.getMonth()}`;
    if (!groups.has(key)) groups.set(key, new Intl.DateTimeFormat(undefined, { month: "short", year: "numeric" }).format(date));
  });
  return Array.from(groups.entries()).map(([id, label]) => ({ id, label }));
});

onMounted(() => {
  void loadArchive();
});

async function loadArchive(cursor?: string | null) {
  isLoading.value = true;
  try {
    if (!profile.value) {
      const response = await ProfileService.getProfile(nickname.value);
      profile.value = response.profile || null;
    }
    if (!profile.value) return;
    const archive = await ContentService.storyArchive(profile.value.id, cursor, 40);
    stories.value = cursor ? [...stories.value, ...archive.stories] : archive.stories;
    nextCursor.value = archive.nextCursor || null;
  } finally {
    isLoading.value = false;
  }
}

function openStory(story: Story) {
  void router.push({
    path: `/story/${encodeURIComponent(story.id)}`,
    query: {
      author: story.authorId,
      archive: "1",
      from: route.fullPath,
    },
  });
}

function close() {
  void router.push(`/u/${encodeURIComponent(nickname.value)}`);
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
  const date = new Date(story.createdAt || story.expiresAt || Date.now());
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
}

function scrollToMonth(id: string) {
  document.querySelector(`[data-month="${id}"]`)?.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "center" });
}
</script>

<template>
  <section class="archive-page">
    <div class="particles" aria-hidden="true">
      <span v-for="index in 18" :key="index"></span>
    </div>

    <header class="archive-header">
      <button type="button" aria-label="Close archive" @click="close"><i class="pi pi-times"></i></button>
      <div>
        <strong>{{ ownerName }}</strong>
        <span>Story archive</span>
      </div>
    </header>

    <main class="archive-timeline" aria-label="Story archive timeline">
      <button
        v-for="story in stories"
        :key="story.id"
        type="button"
        class="archive-card"
        :class="{ 'archive-card--close': story.closeFriends || story.visibility === 'CLOSE_FRIENDS' }"
        :data-month="`${new Date(story.createdAt || story.expiresAt || Date.now()).getFullYear()}-${new Date(story.createdAt || story.expiresAt || Date.now()).getMonth()}`"
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

      <button v-if="nextCursor" type="button" class="archive-more" :disabled="isLoading" @click="loadArchive(nextCursor)">
        {{ isLoading ? "Loading" : "More" }}
      </button>

      <p v-if="!isLoading && !stories.length" class="archive-empty">No archived stories</p>
    </main>

    <nav v-if="navItems.length" class="archive-nav" aria-label="Archive months">
      <button v-for="item in navItems" :key="item.id" type="button" @click="scrollToMonth(item.id)">
        {{ item.label }}
      </button>
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
    radial-gradient(circle at 22% 18%, rgba(34, 197, 94, 0.18), transparent 28%),
    radial-gradient(circle at 78% 22%, rgba(99, 102, 241, 0.16), transparent 30%),
    #06080d;
  color: #ffffff;
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
  z-index: 3;
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
  display: grid;
  gap: 3px;
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

.archive-timeline {
  position: relative;
  z-index: 2;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 24px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 94px max(28px, 8vw) 110px;
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
  width: 178px;
  min-width: 178px;
  display: grid;
  gap: 10px;
  padding: 0;
  background: transparent;
  text-align: left;
  scroll-snap-align: center;
}

.archive-preview {
  width: 178px;
  aspect-ratio: 9 / 14;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.32);
}

.archive-preview img,
.archive-preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.archive-card strong {
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
  color: rgba(255, 255, 255, 0.66);
  font-size: 11px;
  font-weight: 800;
}

.archive-card--close small i {
  color: #86efac;
}

.archive-more {
  width: 118px;
  min-width: 118px;
  height: 42px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
  font-weight: 900;
}

.archive-empty {
  width: 100%;
  color: rgba(255, 255, 255, 0.68);
  text-align: center;
  font-weight: 900;
}

.archive-nav {
  position: fixed;
  z-index: 4;
  left: 50%;
  bottom: max(18px, env(safe-area-inset-bottom));
  max-width: calc(100vw - 32px);
  display: flex;
  gap: 8px;
  overflow-x: auto;
  border-radius: 999px;
  padding: 8px;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(18px);
  transform: translateX(-50%);
}

.archive-nav button {
  height: 34px;
  border: 0;
  border-radius: 999px;
  padding: 0 14px;
  background: rgba(255, 255, 255, 0.14);
  color: #ffffff;
  font: inherit;
  font-size: 12px;
  font-weight: 900;
  white-space: nowrap;
  cursor: pointer;
}

@media (max-width: 720px) {
  .archive-timeline {
    padding-inline: 22px;
  }

  .archive-card,
  .archive-preview {
    width: 154px;
    min-width: 154px;
  }
}
</style>
