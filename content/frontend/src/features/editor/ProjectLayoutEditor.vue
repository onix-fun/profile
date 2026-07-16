<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import { LayoutGrid, Play, Redo2, Trash2, Undo2, Volume2 } from "lucide-vue-next";
import type { AssetSizePreset, PostAsset } from "@/api/types";
import { assetKind, assetSource } from "@/features/mediaProject/mediaAssets";
import { imagePresentation } from "@/features/mediaProject/mediaPresentation";
import { autoArrangeProject, ensureProjectLayouts, moveProjectAsset, projectAssetBox, projectBounds, resizeProjectAsset } from "@/features/mediaProject/projectLayout";

const props = withDefaults(defineProps<{ modelValue: PostAsset[]; interactive?: boolean }>(), { interactive: true });
const emit = defineEmits<{ "update:modelValue": [assets: PostAsset[]]; remove: [id: string] }>();
const viewport = ref<HTMLElement | null>(null);
const camera = ref({ x: 0, y: 0 });
const size = ref({ width: 900, height: 650 });
const selectedId = ref("");
const draggingId = ref("");
const undoStack = ref<PostAsset[][]>([]);
const redoStack = ref<PostAsset[][]>([]);
const localAssets = shallowRef<PostAsset[]>([]);
const rendered = computed(() => localAssets.value.map((asset) => {
  const box = projectAssetBox(asset);
  const image = imagePresentation(asset, box.width, "EDITOR");
  return {
    asset,
    box,
    image,
    style: { transform: `translate3d(${box.x}px,${box.y}px,0)`, width: `${box.width}px`, height: `${box.height}px` },
  };
}));
const worldStyle = computed(() => ({
  transform: `translate3d(${Math.round(size.value.width / 2 - camera.value.x)}px,${Math.round(size.value.height / 2 - camera.value.y)}px,0)`,
}));

let observer: ResizeObserver | null = null;
let pan: { id: number; x: number; y: number; cx: number; cy: number; latestX: number; latestY: number } | null = null;
let mediaDrag: { id: number; assetId: string; x: number; y: number; assets: PostAsset[]; originX: number; originY: number; latestX: number; latestY: number } | null = null;
let longPress = 0;
let interactionFrame = 0;

watch(() => props.modelValue, (assets) => {
  const normalized = ensureProjectLayouts(assets);
  if (!mediaDrag) {
    localAssets.value = normalized;
    return;
  }
  const layouts = new Map(localAssets.value.map((asset) => [asset.id, asset.layout]));
  localAssets.value = normalized.map((asset) => ({ ...asset, layout: layouts.get(asset.id) || asset.layout }));
}, { immediate: true });

onMounted(async () => {
  await nextTick();
  resize();
  if (typeof ResizeObserver !== "undefined") {
    observer = new ResizeObserver(resize);
    if (viewport.value) observer.observe(viewport.value);
  }
  recenter();
});
onBeforeUnmount(() => {
  observer?.disconnect();
  window.clearTimeout(longPress);
  if (interactionFrame) window.cancelAnimationFrame(interactionFrame);
});

function resize() { if (viewport.value) size.value = { width: viewport.value.clientWidth, height: viewport.value.clientHeight }; }
function recenter() { const b = projectBounds(localAssets.value); camera.value = { x: (b.left + b.right) / 2, y: (b.top + b.bottom) / 2 }; }
function commit(next: PostAsset[], record = true) {
  if (record) { undoStack.value = [...undoStack.value.slice(-49), clone(localAssets.value)]; redoStack.value = []; }
  localAssets.value = next;
  emit("update:modelValue", clone(next));
}
function undo() { const previous = undoStack.value.at(-1); if (!previous) return; redoStack.value = [...redoStack.value, clone(localAssets.value)]; undoStack.value = undoStack.value.slice(0, -1); commit(clone(previous), false); }
function redo() { const next = redoStack.value.at(-1); if (!next) return; undoStack.value = [...undoStack.value, clone(localAssets.value)]; redoStack.value = redoStack.value.slice(0, -1); commit(clone(next), false); }
function arrange() { commit(autoArrangeProject(localAssets.value)); window.requestAnimationFrame(recenter); }
function resizeSelected(preset: AssetSizePreset) { if (!selectedId.value) return; const result = resizeProjectAsset(localAssets.value, selectedId.value, preset); if (result.accepted) commit(result.assets); }
function wheel(event: WheelEvent) { if (event.ctrlKey || event.metaKey) return; event.preventDefault(); camera.value = { x: camera.value.x + event.deltaX + (event.shiftKey ? event.deltaY : 0), y: camera.value.y + (event.shiftKey ? 0 : event.deltaY) }; }
function panDown(event: PointerEvent) { if (event.button !== 0) return; viewport.value?.setPointerCapture(event.pointerId); pan = { id: event.pointerId, x: event.clientX, y: event.clientY, latestX: event.clientX, latestY: event.clientY, cx: camera.value.x, cy: camera.value.y }; }
function assetDown(event: PointerEvent, asset: PostAsset) {
  if (event.button !== 0) return;
  event.stopPropagation();
  selectedId.value = asset.id;
  if (!props.interactive) return;
  const start = () => {
    const current = localAssets.value.find((item) => item.id === asset.id);
    if (!current?.layout) return;
    viewport.value?.setPointerCapture(event.pointerId);
    mediaDrag = { id: event.pointerId, assetId: asset.id, x: event.clientX, y: event.clientY, latestX: event.clientX, latestY: event.clientY, assets: clone(localAssets.value), originX: current.layout.x, originY: current.layout.y };
    draggingId.value = asset.id;
  };
  if (window.matchMedia?.("(pointer: coarse)").matches) longPress = window.setTimeout(start, 220); else start();
}
function pointerMove(event: PointerEvent) {
  if (mediaDrag?.id === event.pointerId) { mediaDrag.latestX = event.clientX; mediaDrag.latestY = event.clientY; scheduleInteraction(); return; }
  if (pan?.id === event.pointerId) { pan.latestX = event.clientX; pan.latestY = event.clientY; scheduleInteraction(); }
}
function scheduleInteraction() { if (!interactionFrame) interactionFrame = window.requestAnimationFrame(flushInteraction); }
function flushInteraction() {
  interactionFrame = 0;
  if (mediaDrag) {
    const result = moveProjectAsset(mediaDrag.assets, mediaDrag.assetId, mediaDrag.originX + mediaDrag.latestX - mediaDrag.x, mediaDrag.originY + mediaDrag.latestY - mediaDrag.y);
    if (result.accepted) localAssets.value = result.assets;
    return;
  }
  if (pan) camera.value = { x: pan.cx - (pan.latestX - pan.x), y: pan.cy - (pan.latestY - pan.y) };
}
function pointerUp(event: PointerEvent) {
  window.clearTimeout(longPress);
  if (interactionFrame) { window.cancelAnimationFrame(interactionFrame); interactionFrame = 0; flushInteraction(); }
  if (mediaDrag?.id === event.pointerId) {
    undoStack.value = [...undoStack.value.slice(-49), clone(mediaDrag.assets)];
    redoStack.value = [];
    mediaDrag = null;
    draggingId.value = "";
    emit("update:modelValue", clone(localAssets.value));
  }
  if (pan?.id === event.pointerId) pan = null;
  try { viewport.value?.releasePointerCapture(event.pointerId); } catch { /* capture may already be released */ }
}
function keyMove(event: KeyboardEvent, asset: PostAsset) {
  if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
  event.preventDefault(); selectedId.value = asset.id;
  const step = event.shiftKey ? 32 : 8;
  const dx = event.key === "ArrowLeft" ? -step : event.key === "ArrowRight" ? step : 0;
  const dy = event.key === "ArrowUp" ? -step : event.key === "ArrowDown" ? step : 0;
  const result = moveProjectAsset(localAssets.value, asset.id, asset.layout!.x + dx, asset.layout!.y + dy);
  if (result.accepted) commit(result.assets);
}
function canvasKey(event: KeyboardEvent) {
  const command = event.metaKey || event.ctrlKey;
  if (command && event.key.toLowerCase() === "z") { event.preventDefault(); event.shiftKey ? redo() : undo(); return; }
  if (!selectedId.value) return;
  if (["s", "m", "l"].includes(event.key.toLowerCase())) { event.preventDefault(); resizeSelected(event.key.toUpperCase() as AssetSizePreset); return; }
  if (event.key === "Delete" || event.key === "Backspace") { event.preventDefault(); emit("remove", selectedId.value); selectedId.value = ""; }
}
function clone(value: PostAsset[]): PostAsset[] { return value.map((asset) => ({ ...asset, layout: asset.layout ? { ...asset.layout } : asset.layout })); }
</script>

<template>
  <section class="layout-editor">
    <div v-if="interactive" class="layout-editor__toolbar" aria-label="Инструменты композиции">
      <button type="button" :disabled="!undoStack.length" aria-label="Отменить" @click="undo"><Undo2 :size="17" /></button>
      <button type="button" :disabled="!redoStack.length" aria-label="Повторить" @click="redo"><Redo2 :size="17" /></button>
      <button type="button" aria-label="Расположить автоматически" @click="arrange"><LayoutGrid :size="17" /></button>
      <button v-for="preset in (['S','M','L'] as AssetSizePreset[])" :key="preset" type="button" :disabled="!selectedId" @click="resizeSelected(preset)">{{ preset }}</button>
      <button type="button" :disabled="!selectedId" aria-label="Удалить выбранное медиа" @click="selectedId && emit('remove', selectedId)"><Trash2 :size="16" /></button>
    </div>
    <div ref="viewport" class="layout-editor__canvas" tabindex="0" @wheel="wheel" @keydown="canvasKey" @pointerdown="panDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="pointerUp">
      <div class="layout-editor__world" :style="worldStyle">
        <button v-for="item in rendered" :key="item.asset.id" type="button" class="layout-editor__asset" :class="{ selected: selectedId === item.asset.id, dragging: draggingId === item.asset.id }" :style="item.style" @pointerdown="assetDown($event,item.asset)" @keydown="keyMove($event,item.asset)">
          <img v-if="assetKind(item.asset) === 'IMAGE'" :src="item.image.src" :srcset="item.image.srcset" :sizes="item.image.sizes" alt="" draggable="false" decoding="async" />
          <video v-else-if="assetKind(item.asset) === 'VIDEO' && selectedId === item.asset.id" :src="assetSource(item.asset)" :poster="item.asset.posterUrl || undefined" muted playsinline preload="metadata" />
          <img v-else-if="assetKind(item.asset) === 'VIDEO' && item.asset.posterUrl" :src="item.asset.posterUrl" alt="" decoding="async" draggable="false" />
          <Play v-else-if="assetKind(item.asset) === 'VIDEO'" :size="30" aria-hidden="true" />
          <audio v-else-if="selectedId === item.asset.id" :src="assetSource(item.asset)" controls preload="metadata" @pointerdown.stop />
          <Volume2 v-else :size="28" aria-hidden="true" />
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.layout-editor{position:relative;width:100%;height:100%;min-height:440px;overflow:hidden}.layout-editor__canvas{position:absolute;inset:0;overflow:hidden;outline:0;cursor:grab;touch-action:pinch-zoom;background:#eef0f2}.layout-editor__world{position:absolute;left:0;top:0;width:0;height:0;transform-origin:0 0;will-change:transform}.layout-editor__toolbar{position:absolute;z-index:5;top:14px;left:50%;display:flex;gap:5px;padding:6px;border-radius:999px;background:#fff;box-shadow:0 7px 18px rgba(35,40,50,.12);transform:translateX(-50%)}.layout-editor__toolbar button{display:grid;place-items:center;min-width:32px;height:32px;border:0;border-radius:999px;background:transparent;color:#596574;font:900 11px/1 inherit;cursor:pointer}.layout-editor__toolbar button:hover{background:#eef0f2}.layout-editor__toolbar button:disabled{opacity:.3}.layout-editor__asset{position:absolute;left:0;top:0;display:grid;place-items:center;margin:0;padding:0;overflow:hidden;border:0;border-radius:16px;background:#fff;color:#667080;box-shadow:0 10px 26px rgba(44,50,62,.13);cursor:grab;transition:transform 220ms cubic-bezier(.2,.8,.2,1),width 220ms ease,height 220ms ease}.layout-editor__asset.dragging{transition:none;will-change:transform}.layout-editor__asset.selected{outline:3px solid #335cf2;outline-offset:4px}.layout-editor__asset img,.layout-editor__asset video{width:100%;height:100%;display:block;object-fit:contain}.layout-editor__asset audio{width:calc(100% - 24px)}
@media(prefers-reduced-motion:reduce){.layout-editor__asset{transition:none}}
</style>
