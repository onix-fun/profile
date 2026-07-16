<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { Focus, Play, Volume2 } from "lucide-vue-next";
import type { PostAsset } from "@/api/types";
import { assetKind } from "@/features/mediaProject/mediaAssets";
import { imagePresentation } from "@/features/mediaProject/mediaPresentation";
import { ensureProjectLayouts, projectAssetBox, projectBounds } from "@/features/mediaProject/projectLayout";

const props = withDefaults(defineProps<{ assets: PostAsset[]; interactive?: boolean }>(), { interactive: true });
const emit = defineEmits<{ select: [index: number] }>();
const viewport = ref<HTMLElement | null>(null);
const viewportSize = ref({ width: 1000, height: 700 });
const camera = ref({ x: 0, y: 0 });
const dragging = ref(false);
const normalized = computed(() => ensureProjectLayouts(props.assets));
const worldStyle = computed(() => ({ transform: `translate3d(${Math.round(viewportSize.value.width / 2 - camera.value.x)}px,${Math.round(viewportSize.value.height / 2 - camera.value.y)}px,0)` }));
const rendered = computed(() => normalized.value.map((asset, index) => {
  const box = projectAssetBox(asset);
  const screenX = box.x - camera.value.x + viewportSize.value.width / 2;
  const screenY = box.y - camera.value.y + viewportSize.value.height / 2;
  return {
    asset, index, box,
    visible: screenX + box.width >= -240 && screenY + box.height >= -240 && screenX <= viewportSize.value.width + 240 && screenY <= viewportSize.value.height + 240,
    image: imagePresentation(asset, box.width, "PROJECT"),
    style: { transform: `translate3d(${box.x}px,${box.y}px,0)`, width: `${box.width}px`, height: `${box.height}px` },
  };
}));
let resizeObserver: ResizeObserver | null = null;
let drag: { id: number; x: number; y: number; latestX: number; latestY: number; cameraX: number; cameraY: number } | null = null;
let interactionFrame = 0;
let wheelX = 0;
let wheelY = 0;

onMounted(async () => { await nextTick(); resize(); if (typeof ResizeObserver !== "undefined") { resizeObserver = new ResizeObserver(resize); if (viewport.value) resizeObserver.observe(viewport.value); } recenter(); });
onBeforeUnmount(() => { resizeObserver?.disconnect(); if (interactionFrame) window.cancelAnimationFrame(interactionFrame); });
function resize() { if (viewport.value) viewportSize.value = { width: viewport.value.clientWidth, height: viewport.value.clientHeight }; }
function recenter() { const bounds = projectBounds(normalized.value); camera.value = { x: (bounds.left + bounds.right) / 2, y: (bounds.top + bounds.bottom) / 2 }; }
function wheel(event: WheelEvent) { if (event.ctrlKey || event.metaKey) return; event.preventDefault(); wheelX += event.deltaX + (event.shiftKey ? event.deltaY : 0); wheelY += event.shiftKey ? 0 : event.deltaY; scheduleInteraction(); }
function pointerDown(event: PointerEvent) { if (event.button !== 0) return; viewport.value?.setPointerCapture(event.pointerId); drag = { id: event.pointerId, x: event.clientX, y: event.clientY, latestX: event.clientX, latestY: event.clientY, cameraX: camera.value.x, cameraY: camera.value.y }; dragging.value = true; }
function pointerMove(event: PointerEvent) { if (drag?.id === event.pointerId) { drag.latestX = event.clientX; drag.latestY = event.clientY; scheduleInteraction(); } }
function scheduleInteraction() { if (!interactionFrame) interactionFrame = window.requestAnimationFrame(flushInteraction); }
function flushInteraction() { interactionFrame = 0; if (drag) camera.value = { x: drag.cameraX - (drag.latestX - drag.x), y: drag.cameraY - (drag.latestY - drag.y) }; if (wheelX || wheelY) { camera.value = { x: camera.value.x + wheelX, y: camera.value.y + wheelY }; wheelX = 0; wheelY = 0; } }
function pointerUp(event: PointerEvent) { if (drag?.id !== event.pointerId) return; if (interactionFrame) { window.cancelAnimationFrame(interactionFrame); interactionFrame = 0; flushInteraction(); } viewport.value?.releasePointerCapture(event.pointerId); drag = null; dragging.value = false; }
</script>

<template>
  <section ref="viewport" class="project-canvas" :class="{ 'is-dragging': dragging }" aria-label="Canvas проекта" @wheel="wheel" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="pointerUp">
    <div class="project-canvas__world" :style="worldStyle">
      <button v-for="item in rendered" :key="item.asset.id" class="project-canvas__asset" :style="item.style" type="button" :aria-label="`Открыть медиа ${item.index + 1}`" @pointerdown.stop @click="interactive && emit('select', item.index)">
        <template v-if="item.visible">
          <img v-if="assetKind(item.asset) === 'IMAGE'" :src="item.image.src" :srcset="item.image.srcset" :sizes="item.image.sizes" alt="" draggable="false" decoding="async" loading="lazy" />
          <img v-else-if="assetKind(item.asset) === 'VIDEO' && item.asset.posterUrl" :src="item.asset.posterUrl" alt="" draggable="false" decoding="async" loading="lazy" />
          <Play v-else-if="assetKind(item.asset) === 'VIDEO'" :size="34" fill="currentColor" aria-hidden="true" />
          <Volume2 v-else :size="34" aria-hidden="true" />
        </template>
        <span v-else class="project-canvas__placeholder" aria-hidden="true"></span>
      </button>
    </div>
    <button class="project-canvas__recenter" type="button" aria-label="Вернуться к композиции" title="Вернуться к композиции" @pointerdown.stop @click="recenter"><Focus :size="19" /></button>
  </section>
</template>

<style scoped>
.project-canvas{position:relative;width:100%;height:100%;overflow:hidden;cursor:grab;touch-action:pinch-zoom;background:#eef0f2;user-select:none}.project-canvas.is-dragging{cursor:grabbing}.project-canvas__world{position:absolute;left:0;top:0;width:0;height:0;transform-origin:0 0;will-change:transform}.project-canvas__asset{position:absolute;left:0;top:0;display:grid;place-items:center;margin:0;padding:0;overflow:hidden;border:0;border-radius:18px;background:#fff;color:#667080;box-shadow:0 10px 28px rgba(44,50,62,.13);cursor:zoom-in}.project-canvas__asset img{display:block;width:100%;height:100%;object-fit:contain;background:#fff}.project-canvas__asset:focus-visible,.project-canvas__recenter:focus-visible{outline:3px solid #335cf2;outline-offset:4px}.project-canvas__placeholder{width:100%;height:100%;background:#f4f5f7}.project-canvas__recenter{position:absolute;z-index:4;right:18px;bottom:18px;display:grid;place-items:center;width:42px;height:42px;border:0;border-radius:50%;background:#fff;color:#4d5663;box-shadow:0 7px 18px rgba(35,40,50,.12);cursor:pointer}
@media(max-width:700px){.project-canvas__asset{border-radius:13px}.project-canvas__recenter{right:14px;bottom:76px}}
</style>
