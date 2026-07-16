<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Image as ImageIcon, Music2, Play, TriangleAlert } from "lucide-vue-next";
import type { PostAsset } from "@/api/types";
import { assetKind, assetSource, isAssetReady } from "@/features/mediaProject/mediaAssets";
import { buildFeedProjectPreview } from "@/features/mediaProject/feedProjectPreview";
import { imagePresentation } from "@/features/mediaProject/mediaPresentation";

const props = withDefaults(defineProps<{
  assets: PostAsset[];
  previewLimit?: number;
  interactive?: boolean;
  labelledBy?: string;
  suspended?: boolean;
}>(), {
  previewLimit: 6,
  interactive: false,
  labelledBy: undefined,
  suspended: false,
});

const emit = defineEmits<{ select: [index: number] }>();
const board = ref<HTMLElement | null>(null);
const boardWidth = ref(420);
const items = computed(() => buildFeedProjectPreview(props.assets, props.previewLimit).map((item) => ({
  ...item,
  image: imagePresentation(item.asset, boardWidth.value * item.width / 100, "FEED"),
})));
const unavailableIds = ref(new Set<string>());
const videos = new Map<string, HTMLVideoElement>();
const videoTimes = new Map<string, number>();
let videoObserver: IntersectionObserver | null = null;
let resizeObserver: ResizeObserver | null = null;
const livePreview = ref(true);

watch(() => props.assets, async () => { unavailableIds.value = new Set(); await nextTick(); observeVideos(); }, { deep: false });
watch(() => props.suspended, (suspended) => {
  videos.forEach((video, id) => {
    if (suspended) { videoTimes.set(id, video.currentTime); video.pause(); }
    else videoObserver?.observe(video);
  });
});

onMounted(() => {
  if (typeof ResizeObserver !== "undefined" && board.value) {
    resizeObserver = new ResizeObserver(() => { if (board.value) boardWidth.value = Math.max(1, board.value.clientWidth); });
    resizeObserver.observe(board.value);
    boardWidth.value = Math.max(1, board.value.clientWidth);
  }
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  livePreview.value = !window.matchMedia?.("(prefers-reduced-motion: reduce)").matches && !connection?.saveData;
  if (!livePreview.value) return;
  if (typeof IntersectionObserver === "undefined") return;
  videoObserver = new IntersectionObserver((entries) => entries.forEach((entry) => {
    const video = entry.target as HTMLVideoElement;
    if (entry.isIntersecting && entry.intersectionRatio >= .6 && !document.hidden && !props.suspended) void video.play().catch(() => undefined);
    else video.pause();
  }), { threshold: [0, .6, 1] });
  observeVideos();
  document.addEventListener("visibilitychange", syncVideoVisibility);
});

onBeforeUnmount(() => {
  videoObserver?.disconnect();
  resizeObserver?.disconnect();
  document.removeEventListener("visibilitychange", syncVideoVisibility);
});

function setVideo(id: string, element: unknown) {
  const video = element instanceof HTMLVideoElement ? element : null;
  const previous = videos.get(id);
  if (previous && previous !== video) videoObserver?.unobserve(previous);
  if (video) {
    videos.set(id, video);
    const savedTime = videoTimes.get(id);
    if (savedTime && Number.isFinite(savedTime)) {
      const restore = () => { try { video.currentTime = savedTime; } catch { /* metadata is not available yet */ } };
      if (video.readyState >= 1) restore(); else video.addEventListener("loadedmetadata", restore, { once: true });
    }
    videoObserver?.observe(video);
  }
  else videos.delete(id);
}

function observeVideos() { videos.forEach((video) => videoObserver?.observe(video)); }
function syncVideoVisibility() { videos.forEach((video) => { if (document.hidden) video.pause(); }); }

function markUnavailable(id: string) {
  unavailableIds.value = new Set([...unavailableIds.value, id]);
}
function isUnavailable(id: string) { return unavailableIds.value.has(id); }

function select(index: number) {
  if (props.interactive) emit("select", index);
}

function keydown(event: KeyboardEvent, index: number) {
  if (!props.interactive || (event.key !== "Enter" && event.key !== " ")) return;
  event.preventDefault();
  emit("select", index);
}
</script>

<template>
  <section ref="board" class="media-project-board" :aria-labelledby="labelledBy">
    <component
      :is="interactive ? 'button' : 'div'"
      v-for="item in items"
      :key="item.asset.id"
      class="media-project-board__asset"
      :class="[`media-project-board__asset--${assetKind(item.asset).toLowerCase()}`, { 'is-pending': !isAssetReady(item.asset), 'is-interactive': interactive }]"
      :style="{
        left: `${item.left}%`,
        top: `${item.top}%`,
        width: `${item.width}%`,
        height: `${item.height}%`,
        zIndex: item.zIndex,
      }"
      :type="interactive ? 'button' : undefined"
      :aria-label="interactive ? `Открыть медиа ${item.index + 1}` : undefined"
      @click.stop="select(item.index)"
      @keydown="keydown($event, item.index)"
    >
      <div v-if="suspended" class="media-project-board__placeholder" aria-hidden="true"></div>
      <img
        v-else-if="assetKind(item.asset) === 'IMAGE' && item.image.src && !isUnavailable(item.asset.id)"
        :src="item.image.src"
        :srcset="item.image.srcset"
        :sizes="item.image.sizes"
        alt=""
        draggable="false"
        decoding="async"
        loading="lazy"
        @error="markUnavailable(item.asset.id)"
      />
      <div v-else-if="assetKind(item.asset) === 'IMAGE'" class="media-project-board__placeholder" aria-hidden="true">
        <ImageIcon :size="28" />
      </div>

      <video
        v-else-if="assetKind(item.asset) === 'VIDEO' && livePreview && assetSource(item.asset) && !isUnavailable(item.asset.id)"
        :ref="(element) => setVideo(item.asset.id, element)"
        :src="assetSource(item.asset)"
        :poster="item.asset.posterUrl || undefined"
        muted
        loop
        playsinline
        preload="metadata"
        @error="markUnavailable(item.asset.id)"
      ></video>
      <img
        v-else-if="assetKind(item.asset) === 'VIDEO' && item.asset.posterUrl && !isUnavailable(item.asset.id)"
        :src="item.asset.posterUrl"
        alt=""
        draggable="false"
        @error="markUnavailable(item.asset.id)"
      />
      <div v-else-if="assetKind(item.asset) === 'VIDEO'" class="media-project-board__placeholder" aria-hidden="true">
        <Play :size="28" fill="currentColor" />
      </div>

      <div v-else-if="assetKind(item.asset) === 'AUDIO'" class="media-project-board__audio">
        <div class="media-project-board__wave" aria-hidden="true">
          <i v-for="bar in 24" :key="bar" :style="{ '--bar': `${18 + ((bar * 17) % 61)}%` }"></i>
        </div>
        <audio v-if="assetSource(item.asset) && !isUnavailable(item.asset.id)" :src="assetSource(item.asset)" controls preload="metadata" @click.stop @error="markUnavailable(item.asset.id)"></audio>
        <Music2 v-else :size="25" aria-hidden="true" />
      </div>

      <span v-if="item.asset.status === 'FAILED'" class="media-project-board__failed" aria-label="Не удалось обработать"><TriangleAlert :size="18" /></span>
    </component>
  </section>
</template>

<style scoped>
.media-project-board {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
}

.media-project-board__asset {
  position: absolute;
  display: grid;
  place-items: center;
  box-sizing: border-box;
  min-width: 0;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(44, 50, 62, .13);
  color: #30343b;
}

.media-project-board__asset.is-interactive { cursor: zoom-in; }
.media-project-board__asset.is-interactive:focus-visible { outline: 3px solid #335cf2; outline-offset: 4px; }
.media-project-board__asset.is-pending { opacity: .66; }
.media-project-board__asset img,
.media-project-board__asset video {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  object-fit: contain;
  background: #fff;
}

.media-project-board__placeholder { width: 100%; height: 100%; display: grid; place-items: center; background: #f4f5f7; color: #8c919b; }
.media-project-board__audio { width: 100%; height: 100%; display: grid; align-content: center; gap: 10px; padding: 14px; box-sizing: border-box; background: #fff; }
.media-project-board__audio audio { width: 100%; min-width: 0; height: 28px; }
.media-project-board__wave { display: flex; align-items: center; justify-content: space-between; height: 32px; gap: 2px; color: #536277; }
.media-project-board__wave i { display: block; width: 3px; height: var(--bar); border-radius: 999px; background: currentColor; }
.media-project-board__failed { position: absolute; right: 8px; top: 8px; display: grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; background: #fff1ed; color: #bb3926; }

@media (max-width: 640px) {
  .media-project-board__asset { border-radius: 13px; box-shadow: 0 7px 18px rgba(44, 50, 62, .12); }
  .media-project-board__audio { padding: 9px; }
}
</style>
