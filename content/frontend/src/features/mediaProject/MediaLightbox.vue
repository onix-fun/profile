<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ChevronLeft, ChevronRight, TriangleAlert, X } from "lucide-vue-next";
import type { PostAsset } from "@/api/types";
import { assetKind, assetSource } from "@/features/mediaProject/mediaAssets";
import { imagePresentation } from "@/features/mediaProject/mediaPresentation";

const props = defineProps<{
  assets: PostAsset[];
  index: number;
}>();
const emit = defineEmits<{ close: []; updateIndex: [index: number] }>();
const item = computed(() => props.assets[props.index]);
const image = computed(() => item.value ? imagePresentation(item.value, Math.max(1, window.innerWidth), "LIGHTBOX") : { src: "" });
const unavailable = ref(false);

function close() { emit("close"); }
function previous() { emit("updateIndex", (props.index - 1 + props.assets.length) % props.assets.length); }
function next() { emit("updateIndex", (props.index + 1) % props.assets.length); }
function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") close();
  if (event.key === "ArrowLeft" && props.assets.length > 1) previous();
  if (event.key === "ArrowRight" && props.assets.length > 1) next();
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
watch(() => props.index, () => { unavailable.value = false; });
</script>

<template>
  <Teleport to="body">
    <section class="media-lightbox" role="dialog" aria-modal="true" aria-label="Просмотр медиа" @click.self="close">
      <button class="media-lightbox__close" type="button" aria-label="Закрыть" @click="close"><X :size="22" /></button>
      <button v-if="assets.length > 1" class="media-lightbox__nav media-lightbox__nav--previous" type="button" aria-label="Предыдущее медиа" @click="previous"><ChevronLeft :size="26" /></button>
      <main v-if="item" class="media-lightbox__content">
        <div v-if="unavailable" class="media-lightbox__unavailable" role="status">
          <TriangleAlert :size="26" aria-hidden="true" />
        </div>
        <img v-else-if="assetKind(item) === 'IMAGE'" :src="image.src" :srcset="image.srcset" :sizes="image.sizes" alt="" decoding="async" @error="unavailable = true" />
        <video v-else-if="assetKind(item) === 'VIDEO'" :src="assetSource(item)" :poster="item.posterUrl || undefined" controls autoplay playsinline @error="unavailable = true"></video>
        <div v-else-if="assetKind(item) === 'AUDIO'" class="media-lightbox__audio">
          <div class="media-lightbox__wave" aria-hidden="true"><i v-for="bar in 34" :key="bar" :style="{ '--bar': `${16 + ((bar * 23) % 70)}%` }"></i></div>
          <audio :src="assetSource(item)" controls autoplay @error="unavailable = true"></audio>
        </div>
      </main>
      <button v-if="assets.length > 1" class="media-lightbox__nav media-lightbox__nav--next" type="button" aria-label="Следующее медиа" @click="next"><ChevronRight :size="26" /></button>
    </section>
  </Teleport>
</template>

<style scoped>
.media-lightbox { position: fixed; z-index: 1000; inset: 0; display: grid; place-items: center; padding: 64px 76px; background: rgba(244, 245, 247, .94); backdrop-filter: blur(16px); }
.media-lightbox__content { display: grid; place-items: center; width: min(1120px, 100%); height: min(780px, 100%); min-width: 0; min-height: 0; }
.media-lightbox__content img, .media-lightbox__content video { display: block; width: 100%; height: 100%; border: 0; object-fit: contain; background: #fff; border-radius: 16px; box-shadow: 0 18px 46px rgba(35, 40, 50, .15); }
.media-lightbox__close, .media-lightbox__nav { position: fixed; z-index: 1; display: grid; place-items: center; border: 0; border-radius: 50%; background: #fff; color: #2b3039; box-shadow: 0 7px 18px rgba(35, 40, 50, .12); cursor: pointer; }
.media-lightbox__close { right: 22px; top: 22px; width: 46px; height: 46px; }
.media-lightbox__nav { top: 50%; width: 48px; height: 48px; transform: translateY(-50%); }
.media-lightbox__nav--previous { left: 20px; }
.media-lightbox__nav--next { right: 20px; }
.media-lightbox__close:focus-visible, .media-lightbox__nav:focus-visible { outline: 3px solid #335cf2; outline-offset: 3px; }
.media-lightbox__audio { width: min(520px, 100%); display: grid; gap: 24px; padding: 36px; border-radius: 20px; background: #fff; box-shadow: 0 18px 46px rgba(35, 40, 50, .15); }
.media-lightbox__audio audio { width: 100%; }
.media-lightbox__unavailable { display:grid; place-items:center; gap:15px; width:min(420px, 100%); min-height:220px; border-radius:20px; background:#fff; color:#87909c; box-shadow:0 18px 46px rgba(35, 40, 50, .15); }.media-lightbox__unavailable a { display:grid; place-items:center; width:38px; height:38px; border-radius:50%; background:#edf0f3; color:#526071; }
.media-lightbox__wave { display: flex; align-items: center; justify-content: space-between; height: 120px; gap: 4px; color: #536277; }
.media-lightbox__wave i { display: block; width: 5px; height: var(--bar); border-radius: 99px; background: currentColor; }
@media (max-width: 640px) { .media-lightbox { padding: 70px 18px 96px; } .media-lightbox__nav { top: auto; bottom: 23px; transform: none; } .media-lightbox__nav--previous { left: calc(50% - 58px); } .media-lightbox__nav--next { right: calc(50% - 58px); } }
</style>
