<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ChevronLeft, ChevronRight, Eye, FileAudio, Image as ImageIcon, LoaderCircle, Plus, RotateCcw, Settings2, Trash2, Video, X } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import type { PostAsset, PostPublication, PostRevisionState } from "@/api/types";
import ProjectLayoutEditor from "@/features/editor/ProjectLayoutEditor.vue";
import { fileToPostAsset, mediaStatusLabel, postAssets } from "@/features/mediaProject/mediaAssets";
import { ensureProjectLayouts, reconcileProjectLayouts } from "@/features/mediaProject/projectLayout";
import { acceptedMediaFiles, assetNeedsAction, emptyMediaEditorState, mediaDraftInput, mediaPublishability, mergeCanonicalEditorAssets, normalizeMediaTags, reorderMedia, type MediaEditorState } from "@/features/editor/mediaEditor";
import { clearMediaDraftRecovery, readMediaDraftRecovery, saveMediaDraftRecovery } from "@/features/editor/mediaDraftRecovery";
import { cacheLocalPreview, createLocalImagePreview, deleteLocalPreview, readLocalPreview } from "@/features/editor/localPreviewCache";

const route = useRoute();
const router = useRouter();
const state = ref<MediaEditorState>(emptyMediaEditorState());
const files = new Map<string, File>();
const uploadControllers = new Map<string, AbortController>();
const previewBlobs = new Map<string, Blob>();
const draftId = ref("");
const revisionId = ref("");
const revisionState = ref<PostRevisionState>("DRAFT");
const editVersion = ref(0);
const loading = ref(false);
const publishing = ref(false);
const saving = ref(false);
const status = ref("Черновик");
const errorMessage = ref("");
const publication = ref<PostPublication | null>(null);
let publicationTimer = 0;
const tagInput = ref("");
const showDetails = ref(false);
const showReadiness = ref(false);
const previewMode = ref(false);
let autosaveTimer = 0;
let recoveryTimer = 0;
let recoveryIdle = 0;
let assetPollTimer = 0;
let assetPollingStartedAt = 0;
let changeGeneration = 0;
let savedGeneration = 0;
let activeSave: Promise<boolean> | null = null;
let suppressNextWatch = false;

const editingPostId = computed(() => typeof route.params.postId === "string" ? route.params.postId : "");
const recoveryKey = computed(() => editingPostId.value ? `post:${editingPostId.value}` : "new-post");
const publishReason = computed(() => mediaPublishability(state.value));
const publicationPending = computed(() => Boolean(publication.value && ["PENDING_SOURCE", "PROCESSING_MEDIA", "PENDING_MEDIA", "NEEDS_MEDIA_ACTION"].includes(publication.value.state)));
const revisionMutable = computed(() => revisionState.value === "DRAFT" && !publicationPending.value);
const canPublish = computed(() => revisionMutable.value && !publishReason.value && !publishing.value);

onMounted(async () => {
  if (!editingPostId.value) {
    loading.value = true;
    try {
      const recovered = await readMediaDraftRecovery(recoveryKey.value);
      if (recovered?.state && recovered.revisionId) {
        state.value = { ...recovered.state, assets: reconcileProjectLayouts(recovered.state.assets) };
        draftId.value = recovered.draftId || "";
        revisionId.value = recovered.revisionId;
        editVersion.value = recovered.editVersion || 1;
        await restoreRevisionLifecycle();
        if (revisionMutable.value) {
          await refreshCanonicalAssets(false);
          await hydrateCachedPreviews();
          scheduleAssetPoll(0);
        }
      } else {
        if (recovered) await clearMediaDraftRecovery(recoveryKey.value);
        const created = await ContentService.createPostDraft();
        draftId.value = created.postId;
        revisionId.value = created.revisionId;
        editVersion.value = created.editVersion;
        revisionState.value = created.state;
      }
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : "Не удалось создать черновик";
    } finally { loading.value = false; }
    return;
  }
  loading.value = true;
  try {
    const document = await ContentService.beginPostEdit(editingPostId.value);
    state.value = {
      assets: ensureProjectLayouts(document.assets),
      tags: normalizeMediaTags(document.tags || []),
      allowComments: document.allowComments !== false,
    };
    draftId.value = document.postId;
    revisionId.value = document.revisionId;
    editVersion.value = document.editVersion;
    revisionState.value = document.state;
    // Persisted draft snapshots may still contain an older processing state or
    // fallback geometry. Refresh before enabling publication so READY media is
    // not presented as cancelled and collisions are repaired immediately.
    await refreshCanonicalAssets(false);
    await hydrateCachedPreviews();
    scheduleAssetPoll(0);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Не удалось открыть проект";
  } finally { loading.value = false; }
});

watch(state, () => {
  if (suppressNextWatch) { suppressNextWatch = false; return; }
  if (loading.value || publishing.value || !revisionMutable.value) return;
  changeGeneration += 1;
  window.clearTimeout(autosaveTimer);
  autosaveTimer = window.setTimeout(() => void saveDraft(), 800);
  scheduleRecoverySave();
}, { deep: true });

onBeforeUnmount(() => {
  window.clearTimeout(autosaveTimer);
  window.clearTimeout(recoveryTimer);
  window.clearTimeout(assetPollTimer);
  cancelRecoveryIdle();
  uploadControllers.forEach((controller) => controller.abort());
  state.value.assets.forEach((asset) => { if (asset.previewUrl?.startsWith("blob:")) URL.revokeObjectURL(asset.previewUrl); });
  window.clearTimeout(publicationTimer);
  document.removeEventListener("visibilitychange", resumeAssetPolling);
  window.removeEventListener("online", resumeAssetPolling);
});

onMounted(() => {
  document.addEventListener("visibilitychange", resumeAssetPolling);
  window.addEventListener("online", resumeAssetPolling);
});

function scheduleRecoverySave() {
  window.clearTimeout(recoveryTimer);
  recoveryTimer = window.setTimeout(() => {
    const callback = () => void saveMediaDraftRecovery(recoveryKey.value, draftId.value, revisionId.value, editVersion.value, state.value);
    const idleWindow = window as Window & { requestIdleCallback?: (callback: IdleRequestCallback, options?: IdleRequestOptions) => number };
    recoveryIdle = idleWindow.requestIdleCallback?.(() => callback(), { timeout: 1_500 }) || window.setTimeout(callback, 0);
  }, 250);
}

function cancelRecoveryIdle() {
  if (!recoveryIdle) return;
  const idleWindow = window as Window & { cancelIdleCallback?: (id: number) => void };
  if (idleWindow.cancelIdleCallback) idleWindow.cancelIdleCallback(recoveryIdle); else window.clearTimeout(recoveryIdle);
  recoveryIdle = 0;
}

function appendFiles(fileList: FileList | File[]) {
  if (!revisionMutable.value) return;
  const selected = [...fileList];
  const accepted = acceptedMediaFiles(selected);
  if (accepted.length !== selected.length) {
    errorMessage.value = "Поддерживаются JPEG, PNG, WebP, MP4 (H.264), MP3 и M4A/AAC.";
  }
  const available = Math.max(0, 12 - state.value.assets.length);
  const additions = accepted.slice(0, available).map((file) => {
    const asset = fileToPostAsset(file);
    files.set(asset.id, file);
    return { asset, file };
  });
  if (!additions.length) return;
  state.value = { ...state.value, assets: ensureProjectLayouts([...state.value.assets, ...additions.map(({ asset }) => asset)]) };
  additions.forEach(({ asset, file }) => void uploadLocalAsset(asset.id, file));
  additions.forEach(({ asset, file }) => void optimizeLocalPreview(asset.id, file));
}

function replaceAsset(asset: PostAsset, fileList: FileList | null) {
  if (!revisionMutable.value) return;
  const file = fileList?.[0];
  if (!file || acceptedMediaFiles([file]).length !== 1) {
    errorMessage.value = "Для замены выберите JPEG, PNG, WebP, MP4, MP3 или M4A/AAC.";
    return;
  }
  if (asset.previewUrl?.startsWith("blob:")) URL.revokeObjectURL(asset.previewUrl);
  uploadControllers.get(asset.id)?.abort();
  const local = fileToPostAsset(file);
  const replacement: PostAsset = { ...local, id: asset.id, clientId: asset.clientId || asset.id, layout: asset.layout };
  files.set(asset.id, file);
  state.value = { ...state.value, assets: state.value.assets.map((item) => item.id === asset.id ? replacement : item) };
  void uploadLocalAsset(asset.id, file);
  void optimizeLocalPreview(asset.id, file);
}

function updateUploadedAsset(localId: string, remote: PostAsset) {
  const merged = state.value.assets.map((asset) => asset.id === localId ? {
    ...asset,
    ...remote,
    // A local id is the key of the editor list. The media service asset id is
    // persisted separately and is what Content verifies on publish.
    id: asset.id,
    clientId: asset.clientId,
    assetId: remote.assetId || asset.assetId || remote.id,
    previewUrl: asset.previewUrl || remote.previewUrl || null,
    layout: asset.layout || remote.layout || null,
  } : asset);
  state.value = {
    ...state.value,
    assets: reconcileProjectLayouts(merged),
  };
  const assetId = remote.assetId || remote.id;
  const preview = previewBlobs.get(localId);
  if (assetId && preview) void cacheLocalPreview(assetId, preview);
}

async function optimizeLocalPreview(localId: string, file: File) {
  const blob = await createLocalImagePreview(file).catch(() => null);
  if (!blob || !state.value.assets.some((asset) => asset.id === localId)) return;
  const url = URL.createObjectURL(blob);
  const previous = state.value.assets.find((asset) => asset.id === localId)?.previewUrl;
  previewBlobs.set(localId, blob);
  suppressNextWatch = true;
  state.value = { ...state.value, assets: state.value.assets.map((asset) => asset.id === localId ? { ...asset, previewUrl: url } : asset) };
  if (previous?.startsWith("blob:")) URL.revokeObjectURL(previous);
  const assetId = state.value.assets.find((asset) => asset.id === localId)?.assetId;
  if (assetId) void cacheLocalPreview(assetId, blob);
}

async function hydrateCachedPreviews() {
  const hydrated = await Promise.all(state.value.assets.map(async (asset) => {
    if (asset.previewUrl || !asset.assetId || asset.kind !== "IMAGE") return asset;
    const blob = await readLocalPreview(asset.assetId);
    return blob ? { ...asset, previewUrl: URL.createObjectURL(blob) } : asset;
  }));
  if (hydrated.some((asset, index) => asset !== state.value.assets[index])) {
    suppressNextWatch = true;
    state.value = { ...state.value, assets: hydrated };
  }
}

async function refreshCanonicalAssets(strict: boolean) {
  const ids = state.value.assets.map((asset) => asset.assetId).filter((id): id is string => Boolean(id));
  if (!ids.length) return;
  const results = await ContentService.editorMediaAssets(ids);
  const snapshots = new Map(results.flatMap((result) => result.asset ? [[result.assetId, result.asset] as const] : []));
  if (strict && results.some((result) => !result.asset)) throw new Error("Часть медиа больше недоступна");
  const merged = state.value.assets.map((asset) => {
    const remote = asset.assetId ? snapshots.get(asset.assetId) : null;
    return remote ? { ...asset, ...remote, id: asset.id, clientId: asset.clientId, assetId: asset.assetId, previewUrl: asset.previewUrl || remote.previewUrl || null, layout: asset.layout || remote.layout || null } : asset;
  });
  suppressNextWatch = true;
  state.value = { ...state.value, assets: reconcileProjectLayouts(merged) };
}

function markAssetFailed(localId: string, error: unknown) {
  if (error instanceof DOMException && error.name === "AbortError") return;
  state.value = {
    ...state.value,
    assets: state.value.assets.map((asset) => asset.id === localId ? { ...asset, status: "FAILED" } : asset),
  };
  errorMessage.value = error instanceof Error ? error.message : "Не удалось загрузить медиа";
}

async function uploadLocalAsset(localId: string, file: File) {
  uploadControllers.get(localId)?.abort();
  const controller = new AbortController();
  uploadControllers.set(localId, controller);
  try {
    const current = state.value.assets.find((asset) => asset.id === localId);
    if (!current) return;
    const uploaded = await ContentService.uploadMediaAsset(file, current, {
      signal: controller.signal,
      onReserved: (reserved) => updateUploadedAsset(localId, reserved),
    });
    updateUploadedAsset(localId, uploaded);
    scheduleAssetPoll(0);
  } catch (error) {
    markAssetFailed(localId, error);
  } finally {
    if (uploadControllers.get(localId) === controller) uploadControllers.delete(localId);
  }
}

async function retryAsset(asset: PostAsset) {
  errorMessage.value = "";
  const file = files.get(asset.id) || files.get(asset.clientId || "");
  uploadControllers.get(asset.id)?.abort();
  const controller = new AbortController();
  uploadControllers.set(asset.id, controller);
  try {
    if (asset.assetId && asset.processingRunId) {
      updateUploadedAsset(asset.id, { ...asset, status: "PROCESSING" });
      const retried = await ContentService.retryMediaAssetProcessing(asset.assetId);
      updateUploadedAsset(asset.id, retried);
      scheduleAssetPoll(0);
    } else if (file) {
      await uploadLocalAsset(asset.id, file);
    } else {
      throw new Error("Исходный файл больше недоступен. Добавьте его заново.");
    }
  } catch (error) {
    markAssetFailed(asset.id, error);
  } finally {
    if (uploadControllers.get(asset.id) === controller) uploadControllers.delete(asset.id);
  }
}

function pendingAssetIds(): string[] {
  if (publicationPending.value) return [];
  return state.value.assets
    .filter((asset) => ["UPLOADING", "VERIFYING", "PROCESSING"].includes(asset.status))
    .map((asset) => asset.assetId)
    .filter((id): id is string => Boolean(id));
}

function scheduleAssetPoll(delay = 1_000) {
  window.clearTimeout(assetPollTimer);
  if (!pendingAssetIds().length || document.hidden || !navigator.onLine) return;
  if (!assetPollingStartedAt) assetPollingStartedAt = Date.now();
  assetPollTimer = window.setTimeout(() => void pollAssetStatuses(), delay);
}

async function pollAssetStatuses() {
  if (!pendingAssetIds().length || document.hidden || !navigator.onLine) return;
  try { await refreshCanonicalAssets(false); } catch { /* the next batch retry is sufficient */ }
  const elapsed = Date.now() - assetPollingStartedAt;
  if (pendingAssetIds().length) scheduleAssetPoll(elapsed < 15_000 ? 1_000 : 3_000); else assetPollingStartedAt = 0;
}

function resumeAssetPolling() { if (!document.hidden && navigator.onLine) scheduleAssetPoll(0); }

function removeAsset(index: number) {
  if (!revisionMutable.value) return;
  const asset = state.value.assets[index];
  if (asset?.previewUrl?.startsWith("blob:")) URL.revokeObjectURL(asset.previewUrl);
  if (asset) {
    uploadControllers.get(asset.id)?.abort();
    uploadControllers.delete(asset.id);
    files.delete(asset.id);
    if (asset.clientId) files.delete(asset.clientId);
    previewBlobs.delete(asset.id);
    if (asset.assetId) void deleteLocalPreview(asset.assetId);
  }
  state.value = { ...state.value, assets: state.value.assets.filter((_, itemIndex) => itemIndex !== index) };
}

function removeAssetById(id: string) {
  const index = state.value.assets.findIndex((asset) => asset.id === id);
  if (index >= 0) removeAsset(index);
}

function moveAsset(index: number, direction: -1 | 1) {
  if (!revisionMutable.value) return;
  state.value = { ...state.value, assets: reorderMedia(state.value.assets, index, index + direction) };
}

function updateProjectLayout(assets: PostAsset[]) {
  if (!revisionMutable.value) return;
  state.value = { ...state.value, assets };
}

function commitTags() {
  if (!revisionMutable.value) return;
  state.value = { ...state.value, tags: normalizeMediaTags([...state.value.tags, ...normalizeMediaTags(tagInput.value)]) };
  tagInput.value = "";
}

function removeTag(tag: string) { if (revisionMutable.value) state.value = { ...state.value, tags: state.value.tags.filter((item) => item !== tag) }; }

async function saveDraft(force = false): Promise<boolean> {
  if (activeSave) {
    const result = await activeSave;
    return force && result && savedGeneration < changeGeneration ? saveDraft(true) : result;
  }
  if (publishing.value || revisionState.value !== "DRAFT") return false;
  // Do not persist an editor-only id before MediaStore has reserved its asset.
  // Once an asset id exists, PROCESSING and FAILED states remain in the draft.
  if (state.value.assets.some((asset) => asset.sourceKind === "UPLOAD" && !asset.assetId)) {
    status.value = "Загружаем медиа";
    return false;
  }
  const targetGeneration = changeGeneration;
  const snapshot = { assets: state.value.assets, tags: state.value.tags, allowComments: state.value.allowComments };
  activeSave = (async () => {
    saving.value = true;
    status.value = "Сохраняем";
    try {
    if (revisionId.value) {
      const saved = await ContentService.savePostEditorDocument({
        revisionId: revisionId.value,
        editVersion: editVersion.value,
        assets: snapshot.assets,
        tags: snapshot.tags,
        allowComments: snapshot.allowComments,
      });
      editVersion.value = saved.editVersion;
      revisionState.value = saved.state;
      suppressNextWatch = true;
      state.value = {
        ...state.value,
        assets: reconcileProjectLayouts(mergeCanonicalEditorAssets(state.value.assets, saved.assets)),
      };
      if (saved.layoutAdjustments.length) status.value = "Композиция безопасно скорректирована";
    } else {
      const saved = await ContentService.savePostDraft(mediaDraftInput(state.value, draftId.value || undefined));
      draftId.value = saved.id;
    }
    savedGeneration = Math.max(savedGeneration, targetGeneration);
    status.value = "Сохранено";
    return true;
    } catch (error) {
    const message = error instanceof Error ? error.message : "Не удалось сохранить черновик";
    if (message.includes("Publication revision is immutable")) {
      // Another tab (or a publication request racing the final autosave) may
      // freeze the revision before this response arrives. Re-read the server
      // lifecycle instead of surfacing an internal invariant to the author.
      revisionState.value = "PROCESSING_MEDIA";
      status.value = "Публикация выполняется";
      errorMessage.value = "";
      await restoreRevisionLifecycle().catch(() => undefined);
    } else {
      status.value = "Не синхронизировано";
      errorMessage.value = message;
    }
    return false;
    } finally { saving.value = false; }
  })();
  try { return await activeSave; }
  finally {
    activeSave = null;
    if (savedGeneration < changeGeneration && !publishing.value) {
      window.clearTimeout(autosaveTimer);
      autosaveTimer = window.setTimeout(() => void saveDraft(), 800);
    }
  }
}

async function publish() {
  if (!canPublish.value) return;
  showReadiness.value = false;
  errorMessage.value = "";
  try {
    if (!await saveDraft(true) || !draftId.value) throw new Error("Черновик ещё не синхронизирован");
    publishing.value = true;
    window.clearTimeout(assetPollTimer);
    publication.value = revisionId.value
      ? await ContentService.requestPostRevisionPublication(revisionId.value, crypto.randomUUID())
      : await ContentService.requestPostPublication(draftId.value, crypto.randomUUID());
    revisionState.value = publication.value.state === "PENDING_SOURCE" ? "PENDING_SOURCE" : publication.value.state === "ACTIVE" ? "ACTIVE" : "PROCESSING_MEDIA";
    status.value = "Поставлено в публикацию";
    if (publication.value.state === "ACTIVE") {
      await router.replace(`/p/${encodeURIComponent(draftId.value)}`);
      await clearMediaDraftRecovery(recoveryKey.value);
    } else {
      await monitorPublication();
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "Не удалось опубликовать проект";
    if (message.includes("revision is already frozen") || message.includes("revision is immutable")) {
      errorMessage.value = "";
      await restoreRevisionLifecycle().catch(() => {
        errorMessage.value = "Не удалось обновить состояние публикации";
      });
    } else {
      errorMessage.value = message;
    }
  } finally { publishing.value = false; }
}

async function monitorPublication() {
  if (!draftId.value || !publication.value || ["ACTIVE", "CANCELLED", "DRAFT"].includes(publication.value.state)) return;
  window.clearTimeout(publicationTimer);
  publicationTimer = window.setTimeout(async () => {
    try {
      const next = await ContentService.postPublication(draftId.value);
      publication.value = next;
      if (next.state === "ACTIVE") revisionState.value = "ACTIVE";
      else if (next.state === "NEEDS_MEDIA_ACTION") revisionState.value = "NEEDS_ACTION";
      if (next.state === "ACTIVE") {
        status.value = "Опубликовано";
        await router.replace(`/p/${encodeURIComponent(draftId.value)}`);
        await clearMediaDraftRecovery(recoveryKey.value);
      } else if (next.state === "NEEDS_MEDIA_ACTION") {
        status.value = "Нужно действие с медиа";
        const failed = state.value.assets.filter(assetNeedsAction).map(mediaStatusLabel);
        errorMessage.value = failed.length
          ? `Не готово медиа: ${failed.join("; ")}`
          : "Обработка одного из файлов не завершилась. Откройте список медиа для точной причины.";
      } else if (!["CANCELLED", "DRAFT"].includes(next.state)) {
        await monitorPublication();
      }
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : "Не удалось проверить публикацию";
    }
  }, 2000);
}

async function cancelPublication() {
  if (!draftId.value) return;
  try {
    publication.value = await ContentService.cancelPostPublication(draftId.value);
    revisionState.value = "DRAFT";
    status.value = "Публикация отменена";
    errorMessage.value = "";
    scheduleAssetPoll(0);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Не удалось отменить публикацию";
  }
}

async function restoreRevisionLifecycle() {
  if (!revisionId.value) return;
  const document = await ContentService.postEditorDocument(revisionId.value);
  revisionState.value = document.state;
  editVersion.value = document.editVersion;
  if (document.state === "DRAFT") return;
  if (!draftId.value) return;
  publication.value = await ContentService.postPublication(draftId.value);
  if (publication.value.state === "ACTIVE") {
    await clearMediaDraftRecovery(recoveryKey.value);
    await router.replace(`/p/${encodeURIComponent(draftId.value)}`);
    return;
  }
  if (["DRAFT", "CANCELLED"].includes(publication.value.state)) {
    // Older deployments left a cancelled publication revision frozen. Calling
    // cancel again is idempotent and repairs it to an editable DRAFT on the
    // current backend.
    publication.value = await ContentService.cancelPostPublication(draftId.value);
    revisionState.value = "DRAFT";
    status.value = "Черновик";
    errorMessage.value = "";
    return;
  }
  status.value = publication.value.state === "NEEDS_MEDIA_ACTION" ? "Нужно действие с медиа" : "Публикация выполняется";
  await monitorPublication();
}

function goBack() { void router.back(); }
</script>

<template>
  <section class="media-editor" aria-label="Редактор проекта">
    <header class="media-editor__topbar">
      <button type="button" aria-label="Назад" @click="goBack"><ChevronLeft :size="22" /></button>
      <span class="media-editor__sync" aria-live="polite"><i :class="{ active: saving || publishing }" />{{ saving ? "Сохраняем" : status }}</span>
      <nav class="media-editor__top-actions" aria-label="Команды проекта">
        <label class="media-editor__icon-action" aria-label="Добавить медиа" title="Добавить медиа">
          <input type="file" :disabled="!revisionMutable" accept="image/jpeg,image/png,image/webp,video/mp4,audio/mpeg,audio/mp4,audio/aac,.m4a" multiple @change="appendFiles(($event.target as HTMLInputElement).files || [])" />
          <Plus :size="20" />
        </label>
        <button type="button" aria-label="Медиа и настройки" title="Медиа и настройки" @click="showDetails = true"><Settings2 :size="19" /></button>
        <button type="button" :aria-pressed="previewMode" aria-label="Предпросмотр проекта" title="Предпросмотр проекта" @click="previewMode = !previewMode"><Eye :size="19" /></button>
        <button class="media-editor__publish" type="button" :disabled="publishing || !revisionMutable" @click="showReadiness = true">{{ publishing ? "Ставим в очередь" : "Опубликовать" }}</button>
      </nav>
    </header>

    <div v-if="loading" class="media-editor__state"><LoaderCircle class="spin" :size="28" /></div>
    <main v-else class="media-editor__canvas" @dragover.prevent @drop.prevent="appendFiles($event.dataTransfer?.files || [])">
      <ProjectLayoutEditor :model-value="state.assets" :interactive="revisionMutable && !previewMode" @update:model-value="updateProjectLayout" @remove="removeAssetById" />
      <span v-if="!revisionMutable" class="media-editor__preview-label">Ревизия зафиксирована · публикация выполняется</span>
      <span v-if="previewMode" class="media-editor__preview-label">Предпросмотр · нажмите 👁, чтобы продолжить</span>
      <label v-if="!state.assets.length && revisionMutable" class="media-editor__empty">
        <input type="file" accept="image/jpeg,image/png,image/webp,video/mp4,audio/mpeg,audio/mp4,audio/aac,.m4a" multiple @change="appendFiles(($event.target as HTMLInputElement).files || [])" />
        <Plus :size="27" /><strong>Добавьте медиа</strong><span>Перетащите файлы или нажмите здесь</span>
      </label>
      <button class="media-editor__status-button" type="button" @click="showDetails = true">
        {{ state.assets.length }}/12 медиа<span v-if="publishReason"> · требуется действие</span>
      </button>
    </main>

    <Transition name="sheet">
      <section v-if="showDetails" class="media-editor__sheet" aria-label="Медиа и настройки">
        <header><strong>Проект</strong><button type="button" aria-label="Закрыть" @click="showDetails = false"><X :size="20" /></button></header>
        <ol class="media-editor__assets" aria-label="Медиа проекта">
          <li v-for="(asset, index) in state.assets" :key="asset.id">
            <span class="media-editor__asset-icon" aria-hidden="true"><ImageIcon v-if="asset.kind === 'IMAGE'" :size="18" /><Video v-else-if="asset.kind === 'VIDEO'" :size="18" /><FileAudio v-else :size="18" /></span>
            <span class="media-editor__asset-status">{{ mediaStatusLabel(asset) }}</span>
            <span class="media-editor__asset-actions">
              <button type="button" :disabled="!revisionMutable || index === 0" aria-label="Переместить выше" @click="moveAsset(index, -1)"><ChevronLeft :size="16" /></button>
              <button type="button" :disabled="!revisionMutable || index === state.assets.length - 1" aria-label="Переместить ниже" @click="moveAsset(index, 1)"><ChevronRight :size="16" /></button>
              <button v-if="assetNeedsAction(asset) || asset.processingStatus === 'FAILED'" type="button" aria-label="Повторить обработку медиа" title="Повторить" @click="retryAsset(asset)"><RotateCcw :size="16" /></button>
              <label v-if="revisionMutable" class="media-editor__replace" aria-label="Заменить медиа" title="Заменить"><input type="file" accept="image/jpeg,image/png,image/webp,video/mp4,audio/mpeg,audio/mp4,audio/aac,.m4a" @change="replaceAsset(asset, ($event.target as HTMLInputElement).files)" /><Plus :size="16" /></label>
              <button type="button" :disabled="!revisionMutable" aria-label="Удалить медиа" @click="removeAsset(index)"><Trash2 :size="16" /></button>
            </span>
          </li>
        </ol>

        <section class="media-editor__settings" aria-label="Настройки публикации">
          <label>Хэштеги для рекомендаций <small>1–5, не видны в проекте</small><input v-model="tagInput" :disabled="!revisionMutable" placeholder="#дизайн #музыка" @keydown.enter.prevent="commitTags" @blur="commitTags" /></label>
          <div class="media-editor__tags"><button v-for="tag in state.tags" :key="tag" type="button" :disabled="!revisionMutable" @click="removeTag(tag)">#{{ tag }} ×</button></div>
          <label class="media-editor__toggle"><input v-model="state.allowComments" :disabled="!revisionMutable" type="checkbox" /> <span>Разрешить комментарии</span></label>
        </section>
        <p v-if="publication && publication.state === 'PENDING_SOURCE'" class="media-editor__hint">Проверяем оригиналы. После проверки обработка начнётся автоматически.</p>
        <p v-if="publication && ['PROCESSING_MEDIA', 'PENDING_MEDIA'].includes(publication.state)" class="media-editor__hint">Создаём версии для публикации. Проект появится автоматически.</p>
        <button v-if="publication && ['PENDING_SOURCE', 'PROCESSING_MEDIA', 'PENDING_MEDIA', 'NEEDS_MEDIA_ACTION'].includes(publication.state)" class="media-editor__cancel" type="button" @click="cancelPublication">Отменить публикацию</button>
        <p v-if="publishReason" class="media-editor__hint">{{ publishReason }}</p>
        <p v-if="errorMessage" class="media-editor__error">{{ errorMessage }}</p>
      </section>
    </Transition>

    <Transition name="sheet">
      <section v-if="showReadiness" class="media-editor__readiness" role="dialog" aria-modal="true" aria-label="Готовность проекта">
        <header><strong>Готовность проекта</strong><button type="button" aria-label="Закрыть" @click="showReadiness = false"><X :size="20" /></button></header>
        <div class="media-editor__check" :class="{ ok: state.assets.length >= 1 && state.assets.length <= 12 }"><span />{{ state.assets.length }} медиа из 12</div>
        <div class="media-editor__check" :class="{ ok: state.tags.length >= 1 && state.tags.length <= 5 }"><span />{{ state.tags.length }} тегов из 5</div>
        <div class="media-editor__check" :class="{ ok: !publishReason }"><span />{{ publishReason || "Все файлы готовы к фоновой обработке" }}</div>
        <p>После подтверждения вкладку можно закрыть. Проект появится автоматически, когда Media подготовит все варианты.</p>
        <button class="media-editor__confirm" type="button" :disabled="!canPublish" @click="publish">Поставить в публикацию</button>
      </section>
    </Transition>
  </section>
</template>

<style scoped>
.media-editor { position:relative; height:100dvh; overflow:hidden; background:#eef0f2; color:#30343b; font-family:"Nunito","Avenir Next",sans-serif; }
.media-editor__topbar { position:fixed; z-index:20; top:14px; left:16px; right:16px; display:grid; grid-template-columns:44px 1fr auto; align-items:center; gap:12px; pointer-events:none; }.media-editor__topbar button,.media-editor__topbar label{pointer-events:auto}.media-editor__topbar>button:first-child,.media-editor__top-actions>button,.media-editor__icon-action{display:grid;place-items:center;width:42px;height:42px;border:0;border-radius:50%;background:#fff;color:#30343b;box-shadow:0 7px 20px rgba(35,40,50,.11);cursor:pointer}.media-editor__sync{justify-self:start;display:inline-flex;align-items:center;gap:7px;padding:8px 11px;border-radius:999px;background:#fff;color:#7c8490;font-size:12px;font-weight:900;box-shadow:0 7px 20px rgba(35,40,50,.08)}.media-editor__sync i{width:7px;height:7px;border-radius:50%;background:#48b779}.media-editor__sync i.active{background:#f1a43b;animation:pulse 900ms ease-in-out infinite}.media-editor__top-actions{display:flex;gap:7px;align-items:center}.media-editor__top-actions input,.media-editor__empty input{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none}.media-editor__publish{width:auto!important;min-height:42px!important;border-radius:999px!important;padding:0 17px!important;background:#30343b!important;color:#fff!important;font:900 13px/1 inherit}.media-editor__publish:disabled{opacity:.45}.media-editor__canvas{position:absolute;inset:0}.media-editor__canvas :deep(.layout-editor){min-height:100%;height:100%}.media-editor__empty{position:absolute;z-index:5;left:50%;top:50%;display:grid;place-items:center;gap:6px;min-width:250px;padding:32px;border-radius:28px;background:#fff;color:#596574;box-shadow:0 14px 40px rgba(35,40,50,.1);cursor:pointer;transform:translate(-50%,-50%)}.media-editor__empty span{font-size:12px;color:#9097a1}.media-editor__status-button{position:fixed;z-index:10;left:50%;bottom:18px;border:0;border-radius:999px;padding:11px 15px;background:#fff;color:#657284;box-shadow:0 7px 20px rgba(35,40,50,.1);font:900 12px/1 inherit;cursor:pointer;transform:translateX(-50%)}
.media-editor__preview-label{position:fixed;z-index:10;left:50%;top:72px;padding:8px 12px;border-radius:999px;background:#fff;color:#657284;box-shadow:0 7px 20px rgba(35,40,50,.1);font-size:12px;font-weight:900;transform:translateX(-50%)}
.media-editor__sheet,.media-editor__readiness{position:fixed;z-index:30;right:18px;top:72px;bottom:18px;width:min(390px,calc(100vw - 36px));box-sizing:border-box;overflow:auto;padding:18px;border-radius:26px;background:#f8f9fa;box-shadow:0 20px 60px rgba(35,40,50,.16)}.media-editor__sheet>header,.media-editor__readiness>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.media-editor__sheet>header button,.media-editor__readiness>header button{display:grid;place-items:center;width:34px;height:34px;border:0;border-radius:50%;background:#e9ecef;color:#596574;cursor:pointer}.media-editor__readiness{top:auto;left:50%;right:auto;bottom:18px;width:min(480px,calc(100vw - 32px));height:auto;display:grid;gap:11px;transform:translateX(-50%)}.media-editor__readiness p{margin:5px 0;color:#7c8490;font-size:12px;font-weight:700;line-height:1.45}.media-editor__check{display:flex;align-items:center;gap:9px;color:#a24d48;font-size:13px;font-weight:850}.media-editor__check span{width:10px;height:10px;border-radius:50%;background:#d86d64}.media-editor__check.ok{color:#478463}.media-editor__check.ok span{background:#53b77a}.media-editor__confirm{min-height:44px;border:0;border-radius:999px;background:#30343b;color:#fff;font:900 13px/1 inherit;cursor:pointer}.media-editor__confirm:disabled{opacity:.4}
.media-editor__settings input { min-width:0; min-height:38px; box-sizing:border-box; border:1px solid #d7dbe1; border-radius:10px; padding:0 10px; background:#fff; color:#30343b; font:700 13px/1 inherit; }
.media-editor__assets { display:grid; gap:7px; margin:0; padding:0; list-style:none; }.media-editor__assets li { min-height:46px; display:grid; grid-template-columns:34px minmax(0,1fr) auto; align-items:center; gap:8px; padding:0 8px; border-radius:12px; background:#fff; }.media-editor__asset-icon { display:grid; place-items:center; color:#657284; }.media-editor__asset-status { overflow:hidden; color:#657284; font-size:12px; font-weight:800; text-overflow:ellipsis; white-space:nowrap; }.media-editor__asset-actions { display:flex; gap:3px; }.media-editor__asset-actions button,.media-editor__replace { display:grid; place-items:center; width:28px; height:28px; border:0; border-radius:8px; background:#f0f2f4; color:#596574; cursor:pointer; }.media-editor__asset-actions button:disabled { opacity:.35; cursor:not-allowed; }.media-editor__replace input{position:absolute;width:1px;height:1px;opacity:0}
.media-editor__settings { display:grid; gap:9px; padding-top:4px; }.media-editor__settings > label:not(.media-editor__toggle) { display:grid; gap:5px; color:#596574; font-size:12px; font-weight:900; }.media-editor__settings small { color:#9097a1; font-size:11px; font-weight:700; }.media-editor__tags { display:flex; flex-wrap:wrap; gap:6px; }.media-editor__tags button { border:0; border-radius:999px; padding:6px 9px; background:#e6e9ed; color:#596574; font:800 11px/1 inherit; cursor:pointer; }.media-editor__toggle { display:flex; align-items:center; gap:8px; color:#596574; font-size:13px; font-weight:800; }.media-editor__toggle input { accent-color:#30343b; }.media-editor__hint,.media-editor__error { margin:0; font-size:12px; font-weight:800; }.media-editor__hint { color:#7c8490; }.media-editor__error { color:#b43b34; }
.media-editor__cancel { justify-self:start; border:0; border-radius:999px; padding:9px 13px; background:#e6e9ed; color:#596574; font:800 12px/1 inherit; cursor:pointer; }
.media-editor__state { min-height:100dvh; display:grid; place-items:center; color:#697382; }.spin { animation:spin .9s linear infinite; }@keyframes spin { to { transform:rotate(360deg); } }@keyframes pulse{50%{opacity:.4}}
.media-editor button:focus-visible,.media-editor input:focus-visible,.media-editor select:focus-visible { outline:3px solid #335cf2; outline-offset:2px; }
.sheet-enter-active,.sheet-leave-active{transition:transform 220ms ease,opacity 220ms ease}.sheet-enter-from,.sheet-leave-to{opacity:0;transform:translateY(18px)}
@media(max-width:700px){.media-editor__topbar{top:10px;left:10px;right:10px;grid-template-columns:42px 1fr auto}.media-editor__sync{max-width:92px;overflow:hidden;white-space:nowrap}.media-editor__top-actions>button:nth-of-type(2){display:none}.media-editor__sheet{left:10px;right:10px;top:auto;bottom:10px;width:auto;max-height:72dvh}.media-editor__readiness{bottom:10px}.media-editor__status-button{bottom:12px}}
@media(prefers-reduced-motion:reduce){.sheet-enter-active,.sheet-leave-active{transition:none}.media-editor__sync i.active{animation:none}}
</style>
