<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Bookmark, ChevronLeft, Heart, MessageCircle, Pencil, Share2 } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import type { FeedItem } from "@/api/types";
import MediaLightbox from "@/features/mediaProject/MediaLightbox.vue";
import ProjectCanvas from "@/features/mediaProject/ProjectCanvas.vue";
import { postAssets } from "@/features/mediaProject/mediaAssets";
import SaveToCollectionsPopover from "@/features/content/SaveToCollectionsPopover.vue";

const route = useRoute();
const router = useRouter();
const post = ref<FeedItem["post"] | null>(null);
const loading = ref(true);
const loadFailed = ref(false);
const isOwner = ref(false);
const isLiking = ref(false);
const showSave = ref(false);
const lightboxIndex = ref<number | null>(null);
let lightboxHistoryActive = false;

const postId = computed(() => String(route.params.postId || ""));
const assets = computed(() => post.value ? postAssets(post.value) : []);
const authorName = computed(() => post.value?.author?.displayName || post.value?.authorName || post.value?.author?.username || "Автор проекта");
/** Visible only for untouched legacy data; v2 media projects intentionally have no title/description. */
const legacyTitle = computed(() => assets.value.length ? "" : post.value?.title?.trim() || "");

onMounted(async () => {
  await loadPost();
  window.addEventListener("popstate", closeLightboxFromHistory);
});
onBeforeUnmount(() => {
  window.removeEventListener("popstate", closeLightboxFromHistory);
  document.body.classList.remove("media-project-open");
});

async function loadPost() {
  loading.value = true;
  loadFailed.value = false;
  try {
    const loaded = await ContentService.post(postId.value);
    if (!loaded) throw new Error("not found");
    post.value = loaded;
    void ContentService.recordView?.(postId.value, 0).catch?.(() => undefined);
    try {
      const actor = await ContentService.currentActor();
      isOwner.value = actor.activeOwner.id === (loaded.ownerId || loaded.authorId);
    } catch { isOwner.value = false; }
  } catch {
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

function close() {
  if (window.history.length > 1) void router.back();
  else void router.push("/");
}

function openComments() { void router.push(`/p/${encodeURIComponent(postId.value)}/comments`); }
function edit() { void router.push(`/p/${encodeURIComponent(postId.value)}/edit`); }

async function toggleLike() {
  if (!post.value || isLiking.value) return;
  isLiking.value = true;
  try {
    const state = post.value.likedByViewer
      ? await ContentService.unlikePost(post.value.id)
      : await ContentService.likePost(post.value.id);
    post.value = { ...post.value, likedByViewer: state.liked, likeCount: state.likeCount };
  } finally { isLiking.value = false; }
}

async function share() {
  const url = new URL(`/p/${encodeURIComponent(postId.value)}`, window.location.origin).toString();
  if (navigator.share) {
    try { await navigator.share({ url }); return; } catch { /* user cancelled */ }
  }
  await navigator.clipboard?.writeText?.(url);
}

function openLightbox(index: number) {
  lightboxIndex.value = index;
  document.body.classList.add("media-project-open");
  // The lightbox is a board state, not a separate project route. A history
  // entry lets browser Back close it first and preserve the exact `/p` scene.
  if (!lightboxHistoryActive) {
    window.history.pushState({ ...window.history.state, onixMediaLightbox: postId.value }, "", window.location.href);
    lightboxHistoryActive = true;
  }
}
function closeLightbox() {
  if (lightboxHistoryActive) {
    window.history.back();
    return;
  }
  finishLightboxClose();
}
function closeLightboxFromHistory() {
  if (lightboxIndex.value === null) return;
  finishLightboxClose();
}
function finishLightboxClose() {
  lightboxIndex.value = null;
  lightboxHistoryActive = false;
  document.body.classList.remove("media-project-open");
}

</script>

<template>
  <section class="media-project-page media-project-page--full">
    <template v-if="loading">
      <div class="media-project-state" role="status" aria-label="Загрузка"><i></i><i></i><i></i></div>
    </template>
    <template v-else-if="loadFailed || !post">
      <div class="media-project-state"><button type="button" aria-label="Назад" @click="close"><ChevronLeft :size="23" /></button></div>
    </template>
    <template v-else>
      <button class="media-project-close" type="button" aria-label="Закрыть" @click="close"><ChevronLeft :size="23" /></button>
      <header class="media-project-author">
        <span class="media-project-author__avatar" aria-hidden="true">{{ authorName.slice(0, 1).toUpperCase() }}</span>
        <span>{{ authorName }}</span>
      </header>

      <main class="media-project-board-shell">
        <h1 v-if="legacyTitle" class="media-project-legacy-title">{{ legacyTitle }}</h1>
        <ProjectCanvas :assets="assets" :interactive="true" @select="openLightbox" />
      </main>

      <aside class="media-project-actions" aria-label="Действия проекта">
        <button type="button" :class="{ active: post.likedByViewer }" :disabled="isLiking" :aria-label="post.likedByViewer ? 'Убрать отметку «Нравится»' : 'Нравится'" @click="toggleLike"><Heart :size="19" :fill="post.likedByViewer ? 'currentColor' : 'none'" /></button>
        <button type="button" aria-label="Сохранить в коллекцию" @click="showSave = true"><Bookmark :size="19" /></button>
        <button type="button" aria-label="Поделиться" @click="share"><Share2 :size="19" /></button>
        <button v-if="post.allowComments !== false" type="button" aria-label="Комментарии" @click="openComments"><MessageCircle :size="19" /></button>
        <button v-if="isOwner" type="button" aria-label="Редактировать" @click="edit"><Pencil :size="19" /></button>
      </aside>
    </template>
    <MediaLightbox v-if="lightboxIndex !== null" :assets="assets" :index="lightboxIndex" @close="closeLightbox" @update-index="lightboxIndex = $event" />
    <SaveToCollectionsPopover v-if="showSave && post" :post-id="post.id" @close="showSave = false" />
  </section>
</template>

<style scoped>
.media-project-page { position: fixed; z-index: 30; inset: 0; display: grid; grid-template-columns: 1fr; min-width: 0; min-height: 0; overflow: hidden; background: #eef0f2; color: #30343b; }
.media-project-page--full { position: relative; min-height: 100dvh; }
.media-project-author { position: fixed; z-index: 2; left: 26px; top: 22px; display: inline-flex; align-items: center; gap: 9px; min-width: 0; max-width: calc(100vw - 160px); color: #4d5663; font: 700 14px/1.2 "Nunito", "Avenir Next", sans-serif; }
.media-project-author__avatar { display: grid; place-items: center; width: 31px; height: 31px; flex: 0 0 auto; border-radius: 50%; background: #fff; color: #596574; box-shadow: 0 4px 10px rgba(35, 40, 50, .1); font-size: 12px; font-weight: 900; }
.media-project-close { position: fixed; z-index: 3; right: 24px; top: 20px; display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 50%; background: #fff; color: #30343b; box-shadow: 0 6px 16px rgba(35,40,50,.1); cursor: pointer; }
.media-project-board-shell { width: min(1120px, calc(100vw - 120px)); height: min(76dvh, 760px); min-height: 360px; place-self: center; position: relative; }
.media-project-page--full .media-project-board-shell { width: min(1140px, calc(100vw - 190px)); height: min(78dvh, 800px); }
.media-project-legacy-title { position: absolute; z-index: 3; left: 50%; top: 50%; max-width: 72%; margin: 0; color: #30343b; font: 800 clamp(20px, 4vw, 42px)/1.05 "Nunito", sans-serif; text-align: center; transform: translate(-50%, -50%); pointer-events: none; }
.open-full-post { position: fixed; z-index: 2; bottom: 25px; left: 50%; min-height: 42px; border: 0; border-radius: 999px; padding: 0 18px; background: #fff; box-shadow: 0 8px 20px rgba(35, 40, 50, .11); color: #4d5663; font: 800 13px/1 "Nunito", sans-serif; cursor: pointer; transform: translateX(-50%); }
.media-project-actions { position: fixed; z-index: 3; right: 24px; top: 50%; display: grid; gap: 9px; transform: translateY(-50%); }
.media-project-actions button { display: grid; place-items: center; width: 43px; height: 43px; border: 0; border-radius: 50%; background: #fff; color: #4d5663; box-shadow: 0 6px 16px rgba(35,40,50,.11); cursor: pointer; }
.media-project-actions button.active { color: #e64f74; }.media-project-actions button:disabled { opacity: .55; cursor: wait; }
.media-project-close:focus-visible, .open-full-post:focus-visible, .media-project-actions button:focus-visible { outline: 3px solid #335cf2; outline-offset: 3px; }
.media-project-state { display: flex; align-items: center; justify-content: center; gap: 5px; }.media-project-state i { width: 7px; height: 7px; border-radius: 50%; background: #8d949f; animation: media-state 760ms ease-in-out infinite alternate; }.media-project-state i:nth-child(2) { animation-delay: 120ms; }.media-project-state i:nth-child(3) { animation-delay: 240ms; }.media-project-state button { display:grid; place-items:center; width:44px; height:44px; border:0; border-radius:50%; background:#fff; color:#30343b; cursor:pointer; }
@keyframes media-state { from { transform: translateY(-3px); opacity: .55; } to { transform: translateY(3px); opacity: 1; } }
@media (max-width: 700px) { .media-project-author { left: 17px; top: 16px; }.media-project-close { right: 15px; top: 12px; }.media-project-board-shell, .media-project-page--full .media-project-board-shell { width: calc(100vw - 32px); height: min(68dvh, 590px); min-height: 300px; }.media-project-actions { right: 50%; top: auto; bottom: 19px; grid-auto-flow: column; grid-template-columns: repeat(4, 43px); transform: translateX(50%); }.media-project-actions button { width: 41px; height: 41px; }.media-project-page--full .media-project-board-shell { margin-bottom: 72px; }.open-full-post { bottom: 20px; white-space: nowrap; } }
@media (prefers-reduced-motion: reduce) { .media-project-state i { animation: none; } }
</style>
