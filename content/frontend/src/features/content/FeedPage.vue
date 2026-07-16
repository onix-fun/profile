<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { RefreshCw } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import type { CanvasPostNode, RecommendationFeedResponse } from "@/api/types";
import {
  FEED_CHUNK_LIMIT,
  buildRecommendationCanvasNodes,
  feedChunkKey,
  requiredFeedChunks,
  shouldKeepFeedChunk,
  type FeedCamera,
} from "@/features/content/feedCanvasLayout";
import PostCloudNode from "@/features/content/PostCloudNode.vue";
import StoryRail from "@/features/stories/StoryRail.vue";

const viewport = ref<HTMLElement | null>(null);
const router = useRouter();
const route = useRoute();
const camera = ref<FeedCamera>({ x: 0, y: 0 });
const viewportSize = ref({ width: 1024, height: 720 });
const chunks = ref<Record<string, RecommendationFeedResponse>>({});
const pendingCount = ref(0);
const loadError = ref(false);
const isDragging = ref(false);
const inFlight = new Set<string>();
let resizeObserver: ResizeObserver | null = null;
let syncFrame = 0;
let dragStart: { pointerId: number; x: number; y: number; camera: FeedCamera } | null = null;

const nodes = computed<CanvasPostNode[]>(() => buildRecommendationCanvasNodes(Object.values(chunks.value)));
const visibleNodes = computed(() => nodes.value.map((node) => {
  const left = node.x - camera.value.x + viewportSize.value.width / 2;
  const top = node.y - camera.value.y + viewportSize.value.height / 2;
  return {
    node,
    style: {
      left: `${left}px`,
      top: `${top}px`,
      width: `${node.width}px`,
      height: `${node.height}px`,
    },
  };
}));
const isLoading = computed(() => pendingCount.value > 0 && nodes.value.length === 0);

onMounted(async () => {
  restoreCamera();
  await nextTick();
  updateViewportSize();
  if (typeof ResizeObserver !== "undefined") {
    resizeObserver = new ResizeObserver(() => {
      updateViewportSize();
      scheduleChunkSync();
    });
    if (viewport.value) resizeObserver.observe(viewport.value);
  }
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

function onWheel(event: WheelEvent) {
  if (event.ctrlKey || event.metaKey) return;
  event.preventDefault();
  camera.value = {
    x: camera.value.x + event.deltaX + (event.shiftKey ? event.deltaY : 0),
    y: camera.value.y + (event.shiftKey ? 0 : event.deltaY),
  };
  rememberCamera();
  scheduleChunkSync();
}

function onPointerDown(event: PointerEvent) {
  if (event.button !== 0) return;
  viewport.value?.setPointerCapture(event.pointerId);
  dragStart = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, camera: { ...camera.value } };
  isDragging.value = true;
}

function onPointerMove(event: PointerEvent) {
  if (!dragStart || dragStart.pointerId !== event.pointerId) return;
  camera.value = {
    x: dragStart.camera.x - (event.clientX - dragStart.x),
    y: dragStart.camera.y - (event.clientY - dragStart.y),
  };
  rememberCamera();
  scheduleChunkSync();
}

function onPointerUp(event: PointerEvent) {
  if (dragStart?.pointerId !== event.pointerId) return;
  viewport.value?.releasePointerCapture(event.pointerId);
  dragStart = null;
  isDragging.value = false;
}

function rememberCamera() {
  sessionStorage.setItem("feedCamera", JSON.stringify(camera.value));
  sessionStorage.setItem("mediaCanvasCameraV3", JSON.stringify(camera.value));
}

function restoreCamera() {
  const saved = sessionStorage.getItem("mediaCanvasCameraV3");
  if (!saved) return;
  try {
    const state = JSON.parse(saved) as Partial<FeedCamera>;
    if (Number.isFinite(state.x) && Number.isFinite(state.y)) camera.value = { x: Number(state.x), y: Number(state.y) };
  } catch {
    sessionStorage.removeItem("mediaCanvasCameraV3");
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
    const response = await ContentService.recommendationFeed({ chunkX, chunkY, limit: FEED_CHUNK_LIMIT });
    chunks.value = { ...chunks.value, [key]: response };
    loadError.value = false;
  } catch {
    loadError.value = true;
  } finally {
    inFlight.delete(key);
    pendingCount.value = Math.max(0, pendingCount.value - 1);
  }
}

function retry() { void syncChunks(); }
</script>

<template>
  <section class="feed-shell" aria-label="Рекомендации">
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
      <PostCloudNode
        v-for="{ node, style } in visibleNodes"
        :key="node.id"
        class="canvas-post"
        :class="{ 'canvas-post--focused': route.name === 'PostPage' && String(route.params.postId) === node.id }"
        :post="node.item.post"
        :emphasis="node.emphasis"
        :suspended="route.name !== 'Feed'"
        :style="style"
        @pointerdown.stop
        @open="openPost(node)"
      />
    </div>
    <StoryRail v-show="route.name === 'Feed'" />
    <div v-if="isLoading" class="feed-loader" aria-label="Загрузка" role="status"><i></i><i></i><i></i></div>
    <button v-else-if="loadError && nodes.length === 0" class="feed-retry" type="button" aria-label="Повторить загрузку" @click="retry"><RefreshCw :size="19" /></button>
  </section>
</template>

<style scoped>
.feed-shell { position: relative; height: 100dvh; overflow: hidden; background: #eef0f2; color: #30343b; }
.canvas-viewport { position: relative; width: 100%; height: 100%; overflow: hidden; cursor: grab; touch-action: pinch-zoom; user-select: none; }
.canvas-viewport.dragging { cursor: grabbing; }
.canvas-post { position: absolute; z-index: 1; transform-origin: top left; }
.canvas-post--focused { opacity: 0; pointer-events: none; }
.feed-loader { position: fixed; left: 50%; top: 50%; display: flex; gap: 5px; transform: translate(-50%, -50%); }
.feed-loader i { display: block; width: 7px; height: 7px; border-radius: 50%; background: #8d949f; animation: media-dot 760ms ease-in-out infinite alternate; }
.feed-loader i:nth-child(2) { animation-delay: 120ms; }.feed-loader i:nth-child(3) { animation-delay: 240ms; }
.feed-retry { position: fixed; left: 50%; top: 50%; display: grid; place-items: center; width: 42px; height: 42px; border: 0; border-radius: 50%; background: #fff; box-shadow: 0 8px 20px rgba(35, 40, 50, .12); color: #4d5663; cursor: pointer; transform: translate(-50%, -50%); }
.feed-retry:focus-visible { outline: 3px solid #335cf2; outline-offset: 3px; }
@keyframes media-dot { from { transform: translateY(-3px); opacity: .55; } to { transform: translateY(3px); opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .feed-loader i { animation: none; } }
</style>
