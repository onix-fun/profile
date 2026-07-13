<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import { profileUrl } from "@/api/navigation";
import type { Story, StoryArchiveResponse, StoryBlock } from "@/api/types";
import { displayUsername } from "@/features/display/displayText";

const route = useRoute();
const router = useRouter();

const archive = ref<StoryArchiveResponse | null>(null);
const isLoading = ref(true);
const errorMessage = ref("");

const ownerType = computed<"USER" | "ORGANIZATION">(() => route.query.ownerType === "ORGANIZATION" ? "ORGANIZATION" : "USER");
const ownerId = computed(() => typeof route.query.ownerId === "string" ? route.query.ownerId : "");
const cursor = computed(() => typeof route.query.cursor === "string" ? route.query.cursor : null);
const stories = computed(() => archive.value?.stories || []);
const ownerName = computed(() => displayUsername(archive.value?.owner?.username, ownerType.value === "ORGANIZATION" ? "Organization" : "User"));
const ownerAvatar = computed(() => archive.value?.owner?.avatarUrl || "");
const ownerProfilePath = computed(() => {
  const username = archive.value?.owner?.username;
  if (!username) return "";
  return profileUrl(`/${ownerType.value === "ORGANIZATION" ? "o" : "u"}/${encodeURIComponent(username)}`, true);
});

onMounted(loadArchive);

async function loadArchive() {
  isLoading.value = true;
  errorMessage.value = "";
  try {
    if (!ownerId.value) throw new Error("ownerId is required");
    archive.value = await ContentService.storyArchive(ownerId.value, cursor.value, 80, ownerType.value);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Archive unavailable";
  } finally {
    isLoading.value = false;
  }
}

function previewBlock(story: Story): StoryBlock | undefined {
  return story.blocks.find((block) => block.type === "IMAGE" || block.type === "VIDEO") || story.blocks[0];
}

function previewSource(story: Story): string {
  const block = previewBlock(story);
  return block ? ContentService.mediaSource(block) : "";
}

function storyCaption(story: Story): string {
  return story.blocks
    .map((block) => typeof block.data.text === "string" ? block.data.text : typeof block.data.caption === "string" ? block.data.caption : "")
    .filter(Boolean)
    .join(" ")
    .trim();
}

function archiveDate(story: Story): string {
  const value = story.createdAt || story.expiresAt;
  if (!value) return "";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function openStory(story: Story) {
  void router.push({
    path: `/story/${encodeURIComponent(story.id)}`,
    query: {
      archive: "1",
      author: ownerId.value,
      ownerType: ownerType.value,
      from: route.fullPath,
    },
  });
}

function goBack() {
  const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "";
  if (redirect) {
    window.location.assign(redirect);
    return;
  }
  void router.push("/");
}
</script>

<template>
  <section class="archive-view">
    <div class="archive-ambient" aria-hidden="true"></div>

    <div class="archive-controls">
      <button type="button" class="archive-control" aria-label="Close archive" @click="goBack">
        <i class="pi pi-times"></i>
      </button>
    </div>

    <section v-if="isLoading" class="archive-state">Loading archive</section>
    <section v-else-if="errorMessage" class="archive-state archive-state--panel">
      <i class="pi pi-lock"></i>
      <strong>Archive unavailable</strong>
      <span>{{ errorMessage }}</span>
    </section>

    <main v-else class="archive-scene" aria-label="Story archive">
      <a v-if="ownerProfilePath" class="archive-owner" :href="ownerProfilePath">
        <span class="archive-owner__avatar">
          <img v-if="ownerAvatar" :src="ownerAvatar" alt="" />
          <i v-else-if="ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
          <i v-else class="pi pi-user"></i>
        </span>
        <span>
          <strong>{{ ownerName }}</strong>
          <small>Archive</small>
        </span>
      </a>

      <header class="archive-title">
        <i class="pi pi-history"></i>
        <div>
          <h1>Story archive</h1>
          <p>{{ stories.length ? `${stories.length} saved stories` : "No archived stories yet" }}</p>
        </div>
      </header>

      <div v-if="stories.length" class="archive-timeline" tabindex="0" aria-label="Archived stories timeline">
        <button
          v-for="story in stories"
          :key="story.id"
          type="button"
          class="archive-card"
          @click="openStory(story)"
        >
          <span class="archive-card__media" :class="{ 'is-empty': !previewSource(story) }">
            <img v-if="previewSource(story)" :src="previewSource(story)" alt="" />
            <i v-else class="pi pi-history"></i>
          </span>
          <span class="archive-card__copy">
            <strong>{{ storyCaption(story) || "Story" }}</strong>
            <small>{{ archiveDate(story) }}</small>
          </span>
        </button>
      </div>

      <section v-else class="archive-empty">
        <i class="pi pi-history"></i>
        <strong>Archive is empty</strong>
        <span>Stories will appear here after they expire.</span>
      </section>
    </main>
  </section>
</template>

<style scoped>
.archive-view {
  position: fixed;
  z-index: 180;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 20%, rgba(45, 212, 191, 0.16), transparent 30%),
    radial-gradient(circle at 84% 12%, rgba(129, 140, 248, 0.16), transparent 28%),
    radial-gradient(circle at 52% 90%, rgba(34, 197, 94, 0.12), transparent 30%),
    #05070b;
  color: #ffffff;
}

.archive-ambient {
  position: fixed;
  inset: 8%;
  border-radius: 999px;
  background:
    conic-gradient(from 160deg, rgba(45, 212, 191, 0.12), transparent 22%, rgba(99, 102, 241, 0.14), transparent 58%, rgba(34, 197, 94, 0.1)),
    radial-gradient(circle, rgba(255, 255, 255, 0.06), transparent 62%);
  filter: blur(22px);
  opacity: 0.8;
  pointer-events: none;
}

.archive-controls {
  position: fixed;
  z-index: 205;
  top: max(18px, env(safe-area-inset-top));
  right: 20px;
}

.archive-control {
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.72);
  color: #ffffff;
  cursor: pointer;
  backdrop-filter: blur(16px);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
}

.archive-scene {
  position: relative;
  z-index: 185;
  min-height: 100dvh;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 24px;
  padding: max(76px, env(safe-area-inset-top)) 0 max(38px, env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.archive-owner,
.archive-title {
  margin-inline: clamp(18px, 6vw, 74px);
}

.archive-owner {
  width: max-content;
  max-width: min(360px, calc(100vw - 120px));
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(9, 13, 20, 0.58);
  color: #ffffff;
  text-decoration: none;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.12),
    0 16px 42px rgba(0, 0, 0, 0.24);
  backdrop-filter: blur(18px);
}

.archive-owner__avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 999px;
  background: conic-gradient(from 210deg, #22d3ee, #818cf8, #22c55e, #22d3ee);
}

.archive-owner__avatar img {
  width: 100%;
  height: 100%;
  border: 2px solid rgba(255, 255, 255, 0.86);
  border-radius: inherit;
  object-fit: cover;
}

.archive-owner span:last-child {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.archive-owner strong,
.archive-owner small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-owner strong {
  font-size: 13px;
  font-weight: 900;
}

.archive-owner small {
  color: rgba(255, 255, 255, 0.68);
  font-size: 11px;
  font-weight: 800;
}

.archive-title {
  display: flex;
  align-items: center;
  gap: 14px;
}

.archive-title > i {
  width: 56px;
  height: 56px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: rgba(34, 197, 94, 0.86);
  color: #ffffff;
  font-size: 22px;
  box-shadow: 0 0 32px rgba(34, 197, 94, 0.26);
}

.archive-title h1,
.archive-title p {
  margin: 0;
}

.archive-title h1 {
  font-size: clamp(30px, 5vw, 58px);
  line-height: 0.95;
}

.archive-title p {
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.68);
  font-weight: 850;
}

.archive-timeline {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(210px, 24vw);
  align-items: center;
  gap: 22px;
  min-height: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 10px clamp(18px, 6vw, 74px) 30px;
  scroll-snap-type: x mandatory;
  scrollbar-color: rgba(45, 212, 191, 0.38) transparent;
  scrollbar-width: thin;
}

.archive-card {
  scroll-snap-align: center;
  min-width: 0;
  border: 0;
  border-radius: 28px;
  padding: 10px;
  display: grid;
  grid-template-rows: minmax(260px, 54dvh) auto;
  gap: 12px;
  background: rgba(9, 13, 20, 0.58);
  color: #ffffff;
  text-align: left;
  cursor: pointer;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.12),
    0 24px 70px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(18px);
  transition: transform 180ms ease, background 180ms ease;
}

.archive-card:hover,
.archive-card:focus-visible {
  transform: translateY(-4px) scale(1.015);
  background: rgba(15, 23, 42, 0.72);
}

.archive-card__media {
  min-width: 0;
  min-height: 0;
  border-radius: 22px;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: #101827;
  color: rgba(255, 255, 255, 0.68);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.1);
}

.archive-card__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.archive-card__media.is-empty i {
  font-size: 34px;
}

.archive-card__copy {
  min-width: 0;
  display: grid;
  gap: 5px;
  padding: 0 6px 4px;
}

.archive-card__copy strong,
.archive-card__copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-card__copy strong {
  font-size: 14px;
  font-weight: 900;
}

.archive-card__copy small {
  color: rgba(255, 255, 255, 0.64);
  font-size: 12px;
  font-weight: 850;
}

.archive-state,
.archive-empty {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 900;
}

.archive-state--panel,
.archive-empty {
  align-content: center;
  gap: 9px;
  text-align: center;
}

.archive-empty {
  min-height: 46dvh;
  margin: 0 clamp(18px, 6vw, 74px);
  border: 1px dashed rgba(255, 255, 255, 0.18);
  border-radius: 28px;
  background: rgba(9, 13, 20, 0.42);
  backdrop-filter: blur(18px);
}

.archive-empty i {
  color: #86efac;
  font-size: 34px;
}

.archive-empty span {
  color: rgba(255, 255, 255, 0.58);
  font-size: 13px;
}

@media (max-width: 720px) {
  .archive-scene {
    gap: 18px;
    padding-top: max(70px, env(safe-area-inset-top));
  }

  .archive-timeline {
    grid-auto-columns: minmax(176px, 72vw);
    gap: 14px;
    padding-inline: 18px;
  }

  .archive-card {
    grid-template-rows: minmax(300px, 58dvh) auto;
    border-radius: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .archive-card {
    transition-duration: 1ms;
  }
}
</style>
