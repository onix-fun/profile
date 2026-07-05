<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { CanvasPostNode, ContentBlock, FeedItem } from "@/api/types";
import { buildFeedCanvasNodes, initialCanvasScroll } from "@/features/content/feedCanvasLayout";
import StoryRail from "@/features/stories/StoryRail.vue";

const CANVAS_SIZE = 4800;
const viewport = ref<HTMLElement | null>(null);
const router = useRouter();
const toast = useToast();
const feed = ref<FeedItem[]>([]);
const isLoading = ref(true);
const loadError = ref("");
const zoom = ref(1);
const selectedTags = ref<string[]>([]);
const tagText = ref("");

const nodes = computed<CanvasPostNode[]>(() => buildFeedCanvasNodes(feed.value));
const stageStyle = computed(() => ({
  width: `${CANVAS_SIZE}px`,
  height: `${CANVAS_SIZE}px`,
  transform: `scale(${zoom.value})`,
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
    feed.value = await ContentService.feed(selectedTags.value, 42);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : "Unable to load feed";
    toast.add({ severity: "error", summary: "Feed", detail: loadError.value, life: 5000 });
  } finally {
    isLoading.value = false;
  }
}

function applyTags() {
  selectedTags.value = tagText.value.split(/[,\s]+/).map((tag) => tag.replace(/^#/, "").toLowerCase()).filter(Boolean);
  void loadFeed();
}

function openPost(node: CanvasPostNode) {
  rememberCanvasPosition();
  void router.push(`/p/${encodeURIComponent(node.id)}`);
}

function openCreatePost() {
  rememberCanvasPosition();
  void router.push("/post/new");
}

function setZoom(next: number) {
  zoom.value = Math.min(1.35, Math.max(0.62, Number(next.toFixed(2))));
}

function rememberCanvasPosition() {
  if (!viewport.value) return;
  sessionStorage.setItem("feedCanvas", JSON.stringify({
    left: viewport.value.scrollLeft,
    top: viewport.value.scrollTop,
    zoom: zoom.value,
  }));
}

function restoreCanvasPosition() {
  const element = viewport.value;
  if (!element) return;
  const saved = sessionStorage.getItem("feedCanvas");
  if (saved) {
    try {
      const state = JSON.parse(saved) as { left?: number; top?: number; zoom?: number };
      zoom.value = state.zoom || zoom.value;
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

function firstMedia(node: CanvasPostNode): ContentBlock | undefined {
  return node.item.post.blocks.find((block) => block.type !== "TEXT");
}

function mediaPreview(node: CanvasPostNode): string {
  const media = firstMedia(node);
  if (!media) return "";
  return ContentService.mediaSource(media);
}

function nodeText(node: CanvasPostNode): string {
  const text = node.item.post.text || ContentService.textFromBlocks(node.item.post.blocks);
  return text || "Media post";
}
</script>

<template>
  <section class="feed-shell" aria-label="Canvas feed">
    <StoryRail />

    <div class="feed-controls" aria-label="Feed controls">
      <input v-model="tagText" type="search" placeholder="tags or topics" @keyup.enter="applyTags" />
      <button type="button" aria-label="Apply tags" @click="applyTags"><i class="pi pi-search"></i></button>
    </div>

    <button type="button" class="create-post-fab" aria-label="Create post" @click="openCreatePost">
      <i class="pi pi-plus"></i>
      <span>Post</span>
    </button>

    <div class="zoom-controls" aria-label="Canvas zoom controls">
      <button type="button" aria-label="Zoom out" @click="setZoom(zoom - 0.12)"><i class="pi pi-minus"></i></button>
      <span>{{ Math.round(zoom * 100) }}%</span>
      <button type="button" aria-label="Zoom in" @click="setZoom(zoom + 0.12)"><i class="pi pi-plus"></i></button>
    </div>

    <div ref="viewport" class="canvas-viewport">
      <div class="canvas-stage" :style="stageStyle">
        <div class="canvas-origin">
          <strong>Ranked canvas</strong>
          <span>Pan in any direction</span>
        </div>

        <button
          v-for="node in nodes"
          :key="node.id"
          type="button"
          class="canvas-post"
          :class="[`canvas-post--${node.mediaType.toLowerCase()}`, `canvas-post--${node.emphasis}`]"
          :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${node.width}px`, minHeight: `${node.height}px` }"
          @click="openPost(node)"
        >
          <span v-if="node.mediaType !== 'TEXT'" class="post-media">
            <img v-if="node.mediaType === 'IMAGE' && mediaPreview(node)" :src="mediaPreview(node)" alt="" />
            <video v-else-if="node.mediaType === 'VIDEO' && mediaPreview(node)" :src="mediaPreview(node)" muted playsinline />
            <i v-else :class="node.mediaType === 'AUDIO' ? 'pi pi-volume-up' : 'pi pi-image'"></i>
          </span>
          <span class="post-copy">
            <span class="post-kicker">{{ node.item.reasons.slice(0, 2).join(" / ") || "recommended" }}</span>
            <strong>{{ node.item.post.title || "Untitled post" }}</strong>
            <span>{{ nodeText(node) }}</span>
          </span>
          <span class="post-meta">
            <span v-for="tag in node.item.post.tags.slice(0, 3)" :key="tag">#{{ tag }}</span>
            <small><i class="pi pi-heart"></i>{{ Math.max(1, Math.round(node.item.score)) }}</small>
          </span>
        </button>
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
      <span>Create the first post or adjust the tag filter.</span>
      <button type="button" @click="openCreatePost"><i class="pi pi-plus"></i>Create post</button>
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

.feed-controls {
  position: fixed;
  z-index: 72;
  left: 50%;
  top: 26px;
  display: flex;
  width: min(330px, calc(100vw - 160px));
  transform: translateX(-50%);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.74);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(18px);
}

.feed-controls input {
  min-width: 0;
  flex: 1;
  border: 0;
  padding: 0 16px;
  background: transparent;
  color: #111827;
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  outline: 0;
}

.feed-controls button,
.zoom-controls button,
.create-post-fab {
  border: 0;
  color: #ffffff;
  background: #111827;
  cursor: pointer;
}

.feed-controls button {
  width: 42px;
  height: 42px;
  border-radius: 999px;
}

.create-post-fab {
  position: fixed;
  z-index: 72;
  right: clamp(18px, 3vw, 42px);
  bottom: 28px;
  height: 48px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border-radius: 999px;
  padding: 0 18px;
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.22);
  font-weight: 900;
}

.zoom-controls {
  position: fixed;
  z-index: 72;
  left: clamp(18px, 3vw, 42px);
  bottom: 28px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.1);
  backdrop-filter: blur(18px);
}

.zoom-controls button {
  width: 34px;
  height: 34px;
  border-radius: 999px;
}

.zoom-controls span {
  min-width: 48px;
  color: #111827;
  font-size: 12px;
  font-weight: 900;
  text-align: center;
}

.canvas-post {
  position: absolute;
  z-index: 2;
  display: grid;
  gap: 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 10px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.13);
  color: #111827;
  text-align: left;
  cursor: pointer;
  backdrop-filter: blur(12px);
  transition: transform 150ms ease, box-shadow 150ms ease;
}

.canvas-post:hover {
  transform: translateY(-4px);
  box-shadow: 0 26px 70px rgba(15, 23, 42, 0.18);
}

.post-media {
  height: 120px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(15, 23, 42, 0.82), rgba(51, 65, 85, 0.72)),
    radial-gradient(circle at 20% 20%, rgba(255, 255, 255, 0.28), transparent 28%);
  color: #ffffff;
}

.canvas-post--hero .post-media {
  height: 146px;
}

.canvas-post--audio .post-media {
  height: 72px;
  background: linear-gradient(135deg, #111827, #0f766e);
}

.post-media img,
.post-media video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.post-media i {
  font-size: 24px;
}

.post-copy {
  display: grid;
  gap: 4px;
}

.post-kicker {
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
  text-transform: uppercase;
}

.post-copy strong {
  font-size: 16px;
  line-height: 1.15;
}

.post-copy > span:last-child {
  display: -webkit-box;
  overflow: hidden;
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.post-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
}

.post-meta small {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font: inherit;
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

@media (max-width: 760px) {
  .feed-controls {
    top: auto;
    left: 14px;
    right: 14px;
    bottom: 84px;
    width: auto;
    transform: none;
  }

  .zoom-controls {
    bottom: 22px;
  }

  .create-post-fab {
    right: 14px;
    bottom: 20px;
  }
}
</style>
