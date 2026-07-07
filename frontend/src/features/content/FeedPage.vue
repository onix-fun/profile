<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { CanvasPostNode, FeedItem } from "@/api/types";
import { buildFeedCanvasNodes, initialCanvasScroll } from "@/features/content/feedCanvasLayout";
import PostCloudNode from "@/features/content/PostCloudNode.vue";
import StoryRail from "@/features/stories/StoryRail.vue";

const CANVAS_SIZE = 4800;
const viewport = ref<HTMLElement | null>(null);
const router = useRouter();
const toast = useToast();
const feed = ref<FeedItem[]>([]);
const isLoading = ref(true);
const loadError = ref("");

const nodes = computed<CanvasPostNode[]>(() => buildFeedCanvasNodes(feed.value));
const stageStyle = computed(() => ({
  width: `${CANVAS_SIZE}px`,
  height: `${CANVAS_SIZE}px`,
}));

onMounted(async () => {
  await loadFeed();
  await nextTick();
  restoreCanvasPosition();
});

onBeforeUnmount(() => {
  rememberCanvasPosition();
});

async function loadFeed() {
  isLoading.value = true;
  loadError.value = "";
  try {
    feed.value = await ContentService.feed([], 42);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : "Unable to load feed";
    toast.add({ severity: "error", summary: "Feed", detail: loadError.value, life: 5000 });
  } finally {
    isLoading.value = false;
  }
}

function openPost(node: CanvasPostNode) {
  rememberCanvasPosition();
  void router.push(`/p/${encodeURIComponent(node.id)}`);
}

async function toggleLike(node: CanvasPostNode) {
  try {
    const next = node.item.post.likedByViewer
      ? await ContentService.unlikePost(node.id)
      : await ContentService.likePost(node.id);
    feed.value = feed.value.map((item) => item.post.id === node.id
      ? { ...item, post: { ...item.post, likedByViewer: next.liked, likeCount: next.likeCount } }
      : item);
  } catch (error) {
    toast.add({ severity: "error", summary: "Like", detail: error instanceof Error ? error.message : "Unable to update like", life: 5000 });
  }
}

function rememberCanvasPosition() {
  if (!viewport.value) return;
  sessionStorage.setItem("feedCanvas", JSON.stringify({
    left: viewport.value.scrollLeft,
    top: viewport.value.scrollTop,
  }));
}

function restoreCanvasPosition() {
  const element = viewport.value;
  if (!element) return;
  const saved = sessionStorage.getItem("feedCanvas");
  if (saved) {
    try {
      const state = JSON.parse(saved) as { left?: number; top?: number };
      element.scrollLeft = state.left || 0;
      element.scrollTop = state.top || 0;
      return;
    } catch {
      sessionStorage.removeItem("feedCanvas");
    }
  }
  const initial = initialCanvasScroll(element.clientWidth, element.clientHeight);
  element.scrollLeft = initial.left;
  element.scrollTop = initial.top;
}

</script>

<template>
  <section class="feed-shell" aria-label="Canvas feed">
    <StoryRail />

    <div ref="viewport" class="canvas-viewport">
      <div class="canvas-stage" :style="stageStyle">
        <div class="canvas-origin">
          <strong>Ranked canvas</strong>
          <span>Pan in any direction</span>
        </div>

        <PostCloudNode
          v-for="node in nodes"
          :key="node.id"
          class="canvas-post"
          :post="node.item.post"
          :reasons="node.item.reasons"
          mode="feed"
          :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${node.width}px`, minHeight: `${node.height}px` }"
          @open="openPost(node)"
          @comments="openPost(node)"
          @like="toggleLike(node)"
        />
      </div>
    </div>

    <div v-if="isLoading" class="feed-state">Loading feed</div>
    <div v-else-if="loadError" class="feed-state feed-state-panel">
      <i class="pi pi-exclamation-triangle"></i>
      <strong>Content service unavailable</strong>
      <span>{{ loadError }}</span>
      <button type="button" @click="loadFeed"><i class="pi pi-refresh"></i>Retry</button>
    </div>
    <div v-else-if="nodes.length === 0" class="feed-state feed-state-panel">
      <i class="pi pi-sparkles"></i>
      <strong>No posts yet</strong>
      <span>Create the first post from the account menu.</span>
    </div>
  </section>
</template>

<style scoped>
.feed-shell {
  position: relative;
  height: 100dvh;
  overflow: hidden;
  background:
    radial-gradient(circle at 74% 16%, rgba(16, 185, 129, 0.08), transparent 25%),
    linear-gradient(180deg, #ffffff 0%, #f7f9fb 100%);
}

.canvas-viewport {
  width: 100%;
  height: 100%;
  overflow: auto;
  overscroll-behavior: contain;
  cursor: grab;
}

.canvas-stage {
  position: relative;
  transform-origin: 2400px 2400px;
  background-image:
    radial-gradient(circle, rgba(71, 85, 105, 0.18) 1px, transparent 1px),
    linear-gradient(rgba(148, 163, 184, 0.11) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.11) 1px, transparent 1px);
  background-size: 22px 22px, 180px 180px, 180px 180px;
  transition: transform 180ms ease;
}

.canvas-origin {
  position: absolute;
  left: 2360px;
  top: 2350px;
  display: grid;
  gap: 3px;
  color: rgba(15, 23, 42, 0.45);
  font-size: 12px;
  font-weight: 800;
  text-align: center;
  pointer-events: none;
}

.canvas-origin strong {
  color: rgba(15, 23, 42, 0.62);
  font-size: 16px;
}

.canvas-post {
  position: absolute;
  z-index: 2;
}

.feed-state {
  position: fixed;
  z-index: 4;
  left: 50%;
  top: 55%;
  transform: translate(-50%, -50%);
  color: #64748b;
  font-weight: 900;
  pointer-events: auto;
}

.feed-state-panel {
  width: min(360px, calc(100vw - 32px));
  display: grid;
  justify-items: center;
  gap: 9px;
  padding: 20px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.15);
  text-align: center;
}

.feed-state-panel button {
  border: 0;
  border-radius: 999px;
  padding: 10px 14px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: #111827;
  color: #ffffff;
  font-weight: 900;
  cursor: pointer;
}

</style>
