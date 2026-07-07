<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { CanvasPostNode, RecommendationFeedResponse } from "@/api/types";
import {
  FEED_CHUNK_LIMIT,
  buildRecommendationCanvasNodes,
  feedChunkKey,
  requiredFeedChunks,
  screenPosition,
  shouldKeepFeedChunk,
  type FeedCamera,
} from "@/features/content/feedCanvasLayout";
import PostCloudNode from "@/features/content/PostCloudNode.vue";
import StoryRail from "@/features/stories/StoryRail.vue";

const viewport = ref<HTMLElement | null>(null);
const router = useRouter();
const toast = useToast();
const camera = ref<FeedCamera>({ x: 0, y: 0 });
const viewportSize = ref({ width: 1024, height: 720 });
const chunks = ref<Record<string, RecommendationFeedResponse>>({});
const pendingCount = ref(0);
const loadError = ref("");
const isDragging = ref(false);
const sessionSeed = sessionStorage.getItem("feedSessionSeed") || crypto.randomUUID();
const inFlight = new Set<string>();
let resizeObserver: ResizeObserver | null = null;
let syncFrame = 0;
let dragStart: { pointerId: number; x: number; y: number; camera: FeedCamera } | null = null;

sessionStorage.setItem("feedSessionSeed", sessionSeed);

const nodes = computed<CanvasPostNode[]>(() => buildRecommendationCanvasNodes(Object.values(chunks.value)));
const visibleNodes = computed(() => nodes.value.map((node) => {
  const position = screenPosition(node, camera.value, viewportSize.value.width, viewportSize.value.height);
  return {
    node,
    style: {
      left: `${position.left}px`,
      top: `${position.top}px`,
      width: `${node.width}px`,
      height: `${node.height}px`,
    },
  };
}));
const isLoading = computed(() => pendingCount.value > 0 && nodes.value.length === 0);
const gridStyle = computed(() => ({
  backgroundPosition: `${viewportSize.value.width / 2 - camera.value.x}px ${viewportSize.value.height / 2 - camera.value.y}px`,
}));

onMounted(async () => {
  restoreCamera();
  await nextTick();
  updateViewportSize();
  resizeObserver = new ResizeObserver(() => {
    updateViewportSize();
    scheduleChunkSync();
  });
  if (viewport.value) resizeObserver.observe(viewport.value);
  scheduleChunkSync();
});

onBeforeUnmount(() => {
  rememberCamera();
  resizeObserver?.disconnect();
  if (syncFrame) window.cancelAnimationFrame(syncFrame);
});

function updateViewportSize() {
  const element = viewport.value;
  if (!element) return;
  viewportSize.value = {
    width: Math.max(320, element.clientWidth),
    height: Math.max(420, element.clientHeight),
  };
}

function openPost(node: CanvasPostNode) {
  rememberCamera();
  void router.push(`/p/${encodeURIComponent(node.id)}`);
}

async function toggleLike(node: CanvasPostNode) {
  try {
    const next = node.item.post.likedByViewer
      ? await ContentService.unlikePost(node.id)
      : await ContentService.likePost(node.id);
    const updated: Record<string, RecommendationFeedResponse> = {};
    Object.entries(chunks.value).forEach(([key, chunk]) => {
      updated[key] = {
        ...chunk,
        items: chunk.items.map((item) => item.post.id === node.id
          ? { ...item, post: { ...item.post, likedByViewer: next.liked, likeCount: next.likeCount } }
          : item),
      };
    });
    chunks.value = updated;
  } catch (error) {
    toast.add({ severity: "error", summary: "Like", detail: error instanceof Error ? error.message : "Unable to update like", life: 5000 });
  }
}

function onWheel(event: WheelEvent) {
  event.preventDefault();
  setCamera({
    x: camera.value.x + event.deltaX + (event.shiftKey ? event.deltaY : 0),
    y: camera.value.y + (event.shiftKey ? 0 : event.deltaY),
  });
}

function onPointerDown(event: PointerEvent) {
  if (event.button !== 0) return;
  viewport.value?.setPointerCapture(event.pointerId);
  dragStart = {
    pointerId: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    camera: { ...camera.value },
  };
  isDragging.value = true;
}

function onPointerMove(event: PointerEvent) {
  if (!dragStart || dragStart.pointerId !== event.pointerId) return;
  setCamera({
    x: dragStart.camera.x - (event.clientX - dragStart.x),
    y: dragStart.camera.y - (event.clientY - dragStart.y),
  });
}

function onPointerUp(event: PointerEvent) {
  if (dragStart?.pointerId === event.pointerId) {
    viewport.value?.releasePointerCapture(event.pointerId);
    dragStart = null;
    isDragging.value = false;
  }
}

function setCamera(next: FeedCamera) {
  camera.value = next;
  rememberCamera();
  scheduleChunkSync();
}

function rememberCamera() {
  sessionStorage.setItem("feedCamera", JSON.stringify(camera.value));
}

function restoreCamera() {
  const saved = sessionStorage.getItem("feedCamera");
  if (!saved) return;
  try {
    const state = JSON.parse(saved) as Partial<FeedCamera>;
    if (Number.isFinite(state.x) && Number.isFinite(state.y)) {
      camera.value = { x: Number(state.x), y: Number(state.y) };
    }
  } catch {
    sessionStorage.removeItem("feedCamera");
  }
}

function scheduleChunkSync() {
  if (syncFrame) return;
  syncFrame = window.requestAnimationFrame(() => {
    syncFrame = 0;
    void syncChunks();
  });
}

async function syncChunks() {
  const required = requiredFeedChunks(camera.value);
  const keep = Object.fromEntries(Object.entries(chunks.value).filter(([key]) => shouldKeepFeedChunk(key, camera.value)));
  if (Object.keys(keep).length !== Object.keys(chunks.value).length) chunks.value = keep;

  required.forEach((coord) => {
    const key = feedChunkKey(coord.x, coord.y);
    if (!chunks.value[key] && !inFlight.has(key)) void loadChunk(coord.x, coord.y);
  });
}

async function loadChunk(chunkX: number, chunkY: number) {
  const key = feedChunkKey(chunkX, chunkY);
  inFlight.add(key);
  pendingCount.value += 1;
  try {
    const response = await ContentService.recommendationFeed({ chunkX, chunkY, sessionSeed, limit: FEED_CHUNK_LIMIT });
    chunks.value = { ...chunks.value, [key]: response };
    loadError.value = "";
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : "Unable to load feed";
    if (nodes.value.length === 0) {
      toast.add({ severity: "error", summary: "Feed", detail: loadError.value, life: 5000 });
    }
  } finally {
    inFlight.delete(key);
    pendingCount.value = Math.max(0, pendingCount.value - 1);
  }
}
</script>

<template>
  <section class="feed-shell" aria-label="Canvas feed">
    <StoryRail />

    <div
      ref="viewport"
      class="canvas-viewport"
      :class="{ dragging: isDragging }"
      @wheel="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <div class="canvas-grid" :style="gridStyle"></div>
      <div class="canvas-origin">
        <strong>Recommendation canvas</strong>
        <span>Drag in any direction</span>
      </div>

      <PostCloudNode
        v-for="{ node, style } in visibleNodes"
        :key="`${node.chunkKey}:${node.id}`"
        class="canvas-post"
        :post="node.item.post"
        :reasons="node.item.reasons"
        mode="feed"
        :style="style"
        @pointerdown.stop
        @open="openPost(node)"
        @comments="openPost(node)"
        @like="toggleLike(node)"
      />
    </div>

    <div v-if="isLoading" class="feed-state">Loading feed</div>
    <div v-else-if="loadError && nodes.length === 0" class="feed-state feed-state-panel">
      <i class="pi pi-exclamation-triangle"></i>
      <strong>Content service unavailable</strong>
      <span>{{ loadError }}</span>
      <button type="button" @click="syncChunks"><i class="pi pi-refresh"></i>Retry</button>
    </div>
    <div v-else-if="nodes.length === 0 && pendingCount === 0" class="feed-state feed-state-panel">
      <i class="pi pi-sparkles"></i>
      <strong>No posts here yet</strong>
      <span>Drag to another part of the canvas or create the first post.</span>
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
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.canvas-viewport.dragging {
  cursor: grabbing;
}

.canvas-grid {
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(circle, rgba(71, 85, 105, 0.18) 1px, transparent 1px),
    linear-gradient(rgba(148, 163, 184, 0.11) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.11) 1px, transparent 1px);
  background-size: 22px 22px, 160px 160px, 160px 160px;
  pointer-events: none;
}

.canvas-origin {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
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
