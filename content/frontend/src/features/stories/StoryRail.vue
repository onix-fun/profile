<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { Building2, Plus, User } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import { isStorySeen, mergeSeenState, sortStoryRail } from "@/features/stories/storyState";
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
      ContentService.currentActor().catch(() => null),
      ContentService.storiesFeed(),
    ]);
    currentActor.value = actor;
    stories.value = sortStoryRail(mergeSeenState(feed));
  } catch {
    currentActor.value = null;
    stories.value = [];
  } finally {
    isLoading.value = false;
  }
});

function openStory(item: StoryRailItem) {
  const firstStory = item.storyIds.find((id) => !isStorySeen(id)) || item.storyIds[0];
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
          <span v-else><User :size="20" /></span>
        </span>
        <strong>{{ displayStoryAuthor(viewerItem()!) }}</strong>
      </button>

      <RouterLink v-else class="story-pill story-pill--create" to="/story/new" aria-label="Create story">
        <span class="story-ring">
          <img v-if="viewerAvatar()" :src="viewerAvatar()" alt="" />
          <span v-else>
            <Building2 v-if="activeOwner?.ownerType === 'ORGANIZATION'" :size="20" />
            <strong v-else-if="viewerInitial()">{{ viewerInitial() }}</strong>
            <Plus v-else :size="20" />
          </span>
          <small><Plus :size="13" /></small>
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
            <Building2 v-if="item.ownerType === 'ORGANIZATION'" :size="20" />
            <User v-else :size="20" />
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
  top: max(14px, env(safe-area-inset-top));
  left: 50%;
  width: min(760px, calc(100vw - 32px));
  transform: translateX(-50%);
  overflow-x: auto;
  overflow-y: hidden;
  padding: 6px;
  border-radius: 999px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(35, 40, 50, .10);
  scrollbar-color: rgba(100, 110, 126, .45) transparent;
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
  gap: 10px;
  align-items: center;
  margin-inline: auto;
  padding-inline: 6px;
}

.story-pill {
  width: 66px;
  min-width: 66px;
  display: grid;
  justify-items: center;
  gap: 4px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #4d5663;
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
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border: 2px solid #d7dce3;
  border-radius: 50%;
  padding: 3px;
  background: #ff5ab1;
  box-shadow: 0 4px 12px rgba(35, 40, 50, .10);
}

.story-ring::before {
  content: "";
  position: absolute;
  inset: -4px;
  border-radius: inherit;
  border: 2px solid #ff5ab1;
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
.story-ring > svg {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #ffffff;
  object-fit: cover;
  color: #526071;
  font-weight: 900;
}

.story-ring > span > strong {
  max-width: none;
  color: inherit;
  font-size: 18px;
  line-height: 1;
}

.story-pill strong {
  max-width: 66px;
  overflow: hidden;
  color: #596574;
  font-size: 10px;
  font-weight: 900;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.story-pill--seen .story-ring {
  background: #c5cad2;
  box-shadow: 0 4px 12px rgba(35, 40, 50, .08);
  opacity: 0.74;
}

.story-pill--close .story-ring {
  background: #9eea38;
}

.story-pill--seen.story-pill--close .story-ring {
  background: #b8cf97;
}

.story-pill--create .story-ring {
  position: relative;
  background: #c7ecff;
}

.story-pill--create .story-ring > svg {
  background: #fff;
  color: #526071;
}

.story-pill--create small {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #ff5ab1;
  color: #fff;
  font-size: 10px;
  box-shadow: 0 3px 8px rgba(35, 40, 50, .14);
}

.story-loading {
  color: #7c8490;
  font-size: 11px;
  font-weight: 800;
}

@media (max-width: 720px) {
  .story-rail {
    top: max(10px, env(safe-area-inset-top));
    width: min(100vw - 24px, 760px);
    padding: 5px;
    scrollbar-width: none;
  }

  .story-rail::-webkit-scrollbar {
    display: none;
  }

  .story-track {
    gap: 7px;
    padding-inline: 4px;
  }

  .story-pill {
    width: 58px;
    min-width: 58px;
  }

  .story-ring {
    width: 43px;
    height: 43px;
  }
}
</style>
