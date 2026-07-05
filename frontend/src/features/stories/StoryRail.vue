<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import { mergeSeenState, sortStoryRail } from "@/features/stories/storyState";
import { displayStoryAuthor } from "@/features/display/displayText";
import type { SessionUser, StoryRailItem } from "@/api/types";

const router = useRouter();
const stories = ref<StoryRailItem[]>([]);
const currentUser = ref<SessionUser | null>(null);
const isLoading = ref(false);

onMounted(async () => {
  isLoading.value = true;
  try {
    const [session, feed] = await Promise.all([
      ProfileService.session(),
      ContentService.storiesFeed(),
    ]);
    currentUser.value = session;
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
      query: { author: item.authorId },
    });
  }
}

function authorAvatar(item: StoryRailItem): string {
  return item.author?.avatarUrl || item.avatarUrl || "";
}

function viewerItem(): StoryRailItem | null {
  return stories.value.find((item) => item.isViewer || item.authorId === currentUser.value?.id) || null;
}

function otherItems(): StoryRailItem[] {
  const viewer = viewerItem();
  return stories.value.filter((item) => item !== viewer);
}

function viewerAvatar(): string {
  const item = viewerItem();
  return item ? authorAvatar(item) : currentUser.value?.avatarUrl || "";
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
          <i v-else class="pi pi-plus"></i>
          <small><i class="pi pi-plus"></i></small>
        </span>
        <strong>{{ currentUser?.username || "Create" }}</strong>
      </RouterLink>

      <button
        v-for="item in otherItems()"
        :key="item.authorId"
        type="button"
        class="story-pill"
        :class="{ 'story-pill--seen': item.seen, 'story-pill--close': item.closeFriends }"
        @click="openStory(item)"
      >
        <span class="story-ring">
          <img v-if="authorAvatar(item)" :src="authorAvatar(item)" alt="" />
          <span v-else><i class="pi pi-user"></i></span>
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
  scrollbar-color: rgba(15, 23, 42, 0.24) transparent;
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
  gap: 14px;
  align-items: center;
  margin-inline: auto;
  padding-inline: 20px;
}

.story-pill {
  width: 72px;
  min-width: 72px;
  display: grid;
  justify-items: center;
  gap: 5px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #111827;
  text-decoration: none;
  cursor: pointer;
}

.story-ring {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  padding: 3px;
  background:
    conic-gradient(from 210deg, #f97316, #ec4899, #6366f1, #10b981, #f97316);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.12);
}

.story-ring img,
.story-ring > span,
.story-ring > i {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  border: 2px solid #ffffff;
  border-radius: 999px;
  background: #ffffff;
  object-fit: cover;
  color: #111827;
  font-weight: 900;
}

.story-pill strong {
  max-width: 70px;
  overflow: hidden;
  color: #111827;
  font-size: 11px;
  font-weight: 800;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.story-pill--seen .story-ring {
  background: transparent;
  box-shadow: none;
  padding: 0;
}

.story-pill--close .story-ring {
  background: conic-gradient(from 220deg, #22c55e, #86efac, #22c55e);
}

.story-pill--seen.story-pill--close .story-ring {
  background: transparent;
}

.story-pill--create .story-ring {
  position: relative;
  background: #111827;
}

.story-pill--create .story-ring > i {
  background: #111827;
  color: #ffffff;
}

.story-pill--create small {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 2px solid #ffffff;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font-size: 10px;
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
