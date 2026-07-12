<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import { mergeSeenState, sortStoryRail } from "@/features/stories/storyState";
import { displayStoryAuthor } from "@/features/display/displayText";
import type { CurrentActor, StoryRailItem } from "@/api/types";

const router = useRouter();
const stories = ref<StoryRailItem[]>([]);
const currentActor = ref<CurrentActor | null>(null);
const isLoading = ref(false);
const activeOwner = computed(() => currentActor.value?.activeOwner || null);

onMounted(async () => {
  isLoading.value = true;
  try {
    const [actor, feed] = await Promise.all([
      ContentService.currentActor(),
      ContentService.storiesFeed(),
    ]);
    currentActor.value = actor;
    stories.value = sortStoryRail(mergeSeenState(feed));
  } finally {
    isLoading.value = false;
  }
});

function openStory(item: StoryRailItem) {
  const firstStory = item.storyIds[0];
  if (firstStory) {
    void router.push({
      path: `/story/${encodeURIComponent(firstStory)}`,
      query: { author: item.ownerId || item.authorId, ownerType: item.ownerType || "USER" },
    });
  }
}

function authorAvatar(item: StoryRailItem): string {
  return item.author?.avatarUrl || item.avatarUrl || "";
}

function viewerItem(): StoryRailItem | null {
  const owner = activeOwner.value;
  return stories.value.find((item) => (
    item.isViewer
    || (owner && (item.ownerId || item.authorId) === owner.id && (item.ownerType || "USER") === (owner.ownerType || "USER"))
  )) || null;
}

function otherItems(): StoryRailItem[] {
  const viewer = viewerItem();
  return stories.value.filter((item) => item !== viewer);
}

function viewerAvatar(): string {
  const item = viewerItem();
  return item ? authorAvatar(item) : activeOwner.value?.avatarUrl || "";
}

function viewerName(): string {
  return activeOwner.value?.username || "Create";
}

function viewerInitial(): string {
  const owner = activeOwner.value;
  const name = owner?.displayName || owner?.username || "";
  return name.slice(0, 1).toUpperCase();
}
</script>

<template>
  <section class="story-rail" aria-label="Stories">
    <div class="story-track">
      <button
        v-if="viewerItem()"
        type="button"
        class="story-pill story-pill--viewer"
        :class="{ 'story-pill--seen': viewerItem()?.seen, 'story-pill--close': viewerItem()?.closeFriends }"
        @click="viewerItem() && openStory(viewerItem()!)"
      >
        <span class="story-ring">
          <img v-if="viewerAvatar()" :src="viewerAvatar()" alt="" />
          <span v-else><i class="pi pi-user"></i></span>
        </span>
        <strong>{{ displayStoryAuthor(viewerItem()!) }}</strong>
      </button>

      <RouterLink v-else class="story-pill story-pill--create" to="/story/new" aria-label="Create story">
        <span class="story-ring">
          <img v-if="viewerAvatar()" :src="viewerAvatar()" alt="" />
          <span v-else>
            <i v-if="activeOwner?.ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
            <strong v-else-if="viewerInitial()">{{ viewerInitial() }}</strong>
            <i v-else class="pi pi-plus"></i>
          </span>
          <small><i class="pi pi-plus"></i></small>
        </span>
        <strong>{{ viewerName() }}</strong>
      </RouterLink>

      <button
        v-for="item in otherItems()"
        :key="`${item.ownerType || 'USER'}:${item.ownerId || item.authorId}`"
        type="button"
        class="story-pill"
        :class="{ 'story-pill--seen': item.seen, 'story-pill--close': item.closeFriends }"
        @click="openStory(item)"
      >
        <span class="story-ring">
          <img v-if="authorAvatar(item)" :src="authorAvatar(item)" alt="" />
          <span v-else>
            <i v-if="item.ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
            <i v-else class="pi pi-user"></i>
          </span>
        </span>
        <strong>{{ displayStoryAuthor(item) }}</strong>
      </button>

      <span v-if="isLoading" class="story-loading">Loading stories</span>
    </div>
  </section>
</template>

<style scoped>
.story-rail {
  position: fixed;
  z-index: 70;
  top: 0;
  left: 0;
  right: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding-block: max(10px, env(safe-area-inset-top)) 12px;
  background: linear-gradient(180deg, rgba(5, 7, 11, 0.18), transparent);
  scrollbar-color: rgba(45, 212, 191, 0.28) transparent;
  scrollbar-width: thin;
  pointer-events: auto;
}

.story-rail::-webkit-scrollbar {
  height: 6px;
}

.story-rail::-webkit-scrollbar-track {
  background: transparent;
}

.story-rail::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.24);
}

.story-track {
  width: max-content;
  box-sizing: border-box;
  display: flex;
  gap: 16px;
  align-items: center;
  margin-inline: auto;
  padding-inline: 20px;
}

.story-pill {
  width: 76px;
  min-width: 76px;
  display: grid;
  justify-items: center;
  gap: 6px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #0f172a;
  text-decoration: none;
  cursor: pointer;
  transition: transform 180ms ease, opacity 180ms ease;
}

.story-pill:hover,
.story-pill:focus-visible {
  transform: translateY(-2px);
}

.story-pill:active {
  transform: translateY(0) scale(0.96);
}

.story-ring {
  position: relative;
  width: 60px;
  height: 60px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  padding: 4px;
  background:
    conic-gradient(from 210deg, #22d3ee 0 42%, rgba(255, 255, 255, 0.32) 42% 48%, #818cf8 48% 72%, #22c55e 72% 100%);
  box-shadow:
    0 14px 36px rgba(15, 23, 42, 0.16),
    0 0 22px rgba(45, 212, 191, 0.18);
}

.story-ring::before {
  content: "";
  position: absolute;
  inset: -5px;
  border-radius: inherit;
  border: 1px solid rgba(45, 212, 191, 0.24);
  opacity: 0;
  transform: scale(0.86);
  transition: opacity 180ms ease, transform 180ms ease;
}

.story-pill:hover .story-ring::before,
.story-pill:focus-visible .story-ring::before {
  opacity: 1;
  transform: scale(1);
}

.story-ring img,
.story-ring > span,
.story-ring > i {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  border: 2px solid rgba(255, 255, 255, 0.96);
  border-radius: 999px;
  background: #ffffff;
  object-fit: cover;
  color: #0f172a;
  font-weight: 900;
}

.story-ring > span > strong {
  max-width: none;
  color: inherit;
  font-size: 18px;
  line-height: 1;
}

.story-pill strong {
  max-width: 76px;
  overflow: hidden;
  color: #0f172a;
  font-size: 11px;
  font-weight: 900;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.story-pill--seen .story-ring {
  background: rgba(15, 23, 42, 0.12);
  box-shadow: none;
}

.story-pill--close .story-ring {
  background: conic-gradient(from 220deg, #22c55e, #86efac 45%, #22d3ee 76%, #22c55e);
}

.story-pill--seen.story-pill--close .story-ring {
  background: rgba(34, 197, 94, 0.16);
}

.story-pill--create .story-ring {
  position: relative;
  background: conic-gradient(from 210deg, #0f172a, #22d3ee, #0f172a);
}

.story-pill--create .story-ring > i {
  background: #0f172a;
  color: #ffffff;
}

.story-pill--create small {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 2px solid #ffffff;
  border-radius: 999px;
  background: #0f172a;
  color: #ffffff;
  font-size: 10px;
  box-shadow: 0 0 18px rgba(45, 212, 191, 0.32);
}

.story-loading {
  color: #667085;
  font-size: 12px;
  font-weight: 800;
}

@media (max-width: 720px) {
  .story-rail {
    top: 0;
    padding-block: max(8px, env(safe-area-inset-top)) 10px;
    scrollbar-width: none;
  }

  .story-rail::-webkit-scrollbar {
    display: none;
  }

  .story-track {
    gap: 10px;
    padding-inline: 20px;
  }

  .story-pill {
    width: 64px;
    min-width: 64px;
  }

  .story-ring {
    width: 52px;
    height: 52px;
  }
}
</style>
