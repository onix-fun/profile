<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { profileUrl } from "@/api/navigation";
import { ContentService } from "@/api/contentService";
import type { CurrentActor, PostAsset, StoryBlock } from "@/api/types";
import {
  emptyStoryComposerState,
  extractStoryTags,
  reduceStoryComposer,
} from "@/features/stories/storyState";

const router = useRouter();
const toast = useToast();
const state = ref(emptyStoryComposerState());
const currentActor = ref<CurrentActor | null>(null);
const stream = ref<MediaStream | null>(null);
const devices = ref<MediaDeviceInfo[]>([]);
const selectedDeviceId = ref("");
const mediaRecorder = ref<MediaRecorder | null>(null);
const recordedChunks = ref<Blob[]>([]);
const mediaFile = ref<File | null>(null);
const mediaUrl = ref("");
const mediaDurationMs = ref<number | null>(null);
const permissionError = ref("");
const videoPreview = ref<HTMLVideoElement | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const holdTimer = ref<number | null>(null);
const recordingLimitTimer = ref<number | null>(null);
const holdStartedRecording = ref(false);
const holdThresholdMs = 260;
const maxStoryVideoMs = 60_000;
let recordingStartedAt = 0;
const uploadProgress = ref(0);
const publishStage = ref("");

const isRecording = computed(() => state.value.status === "recording");
const isEditing = computed(() => state.value.status === "edit" || state.value.status === "publishing");
const storyTags = computed(() => extractStoryTags(state.value.caption));
const activeOwner = computed(() => currentActor.value?.activeOwner || null);
const activeOwnerName = computed(() => activeOwner.value?.displayName || activeOwner.value?.username || "User");
const activeOwnerInitial = computed(() => activeOwnerName.value.slice(0, 1).toUpperCase());
const activeOwnerPath = computed(() => activeOwner.value ? ownerPath(activeOwner.value.ownerType || "USER", activeOwner.value.username) : "/");

function setState(action: Parameters<typeof reduceStoryComposer>[1]) {
  state.value = reduceStoryComposer(state.value, action);
}

function ownerPath(ownerType: "USER" | "ORGANIZATION", username: string): string {
  const prefix = ownerType === "ORGANIZATION" ? "o" : "u";
  return profileUrl(`/${prefix}/${encodeURIComponent(username)}`, true);
}

function stopStream() {
  stream.value?.getTracks().forEach((track) => track.stop());
  stream.value = null;
}

function revokeMediaUrl() {
  if (mediaUrl.value) URL.revokeObjectURL(mediaUrl.value);
  mediaUrl.value = "";
}

async function loadDevices() {
  if (!navigator.mediaDevices?.enumerateDevices) return;
  devices.value = (await navigator.mediaDevices.enumerateDevices()).filter((device) => device.kind === "videoinput");
  if (!selectedDeviceId.value && devices.value[0]?.deviceId) selectedDeviceId.value = devices.value[0].deviceId;
}

async function openCamera() {
  permissionError.value = "";
  if (!navigator.mediaDevices?.getUserMedia) {
    permissionError.value = "Camera is not available in this browser.";
    return;
  }
  stopStream();
  try {
    stream.value = await navigator.mediaDevices.getUserMedia({
      video: selectedDeviceId.value ? { deviceId: { exact: selectedDeviceId.value } } : { facingMode: "user" },
      audio: true,
    });
    if (videoPreview.value) videoPreview.value.srcObject = stream.value;
    await loadDevices();
  } catch {
    permissionError.value = "Camera permission is required, or choose a file from your device.";
  }
}

async function switchCamera() {
  await loadDevices();
  if (!devices.value.length) return;
  const index = devices.value.findIndex((device) => device.deviceId === selectedDeviceId.value);
  selectedDeviceId.value = devices.value[(index + 1) % devices.value.length]?.deviceId || "";
  await openCamera();
}

function startRecording() {
  if (!stream.value || !window.MediaRecorder) {
    permissionError.value = "Recording is not available. Choose a file instead.";
    return;
  }
  recordedChunks.value = [];
  const recorder = new MediaRecorder(stream.value, { mimeType: MediaRecorder.isTypeSupported("video/webm") ? "video/webm" : undefined });
  recorder.ondataavailable = (event) => {
    if (event.data.size > 0) recordedChunks.value.push(event.data);
  };
  recorder.onstop = () => {
    clearRecordingLimitTimer();
    const blob = new Blob(recordedChunks.value, { type: recorder.mimeType || "video/webm" });
    const recordedDuration = Math.min(maxStoryVideoMs, Math.max(1_000, Date.now() - recordingStartedAt));
    void setStoryFile(new File([blob], `story-${Date.now()}.webm`, { type: blob.type || "video/webm" }), recordedDuration);
    setState({ type: "STOP_RECORDING", mediaReady: true });
  };
  mediaRecorder.value = recorder;
  recordingStartedAt = Date.now();
  recorder.start();
  clearRecordingLimitTimer();
  recordingLimitTimer.value = window.setTimeout(() => stopRecording(), maxStoryVideoMs);
  setState({ type: "START_RECORDING" });
}

function stopRecording() {
  mediaRecorder.value?.stop();
  mediaRecorder.value = null;
}

function clearHoldTimer() {
  if (holdTimer.value !== null) {
    window.clearTimeout(holdTimer.value);
    holdTimer.value = null;
  }
}

function clearRecordingLimitTimer() {
  if (recordingLimitTimer.value !== null) {
    window.clearTimeout(recordingLimitTimer.value);
    recordingLimitTimer.value = null;
  }
}

function capturePhoto() {
  const video = videoPreview.value;
  if (!video || !video.videoWidth || !video.videoHeight) {
    permissionError.value = "Camera preview is not ready yet.";
    return;
  }
  const canvas = document.createElement("canvas");
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  const context = canvas.getContext("2d");
  if (!context) return;
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  canvas.toBlob((blob) => {
    if (!blob) return;
    void setStoryFile(new File([blob], `story-${Date.now()}.jpg`, { type: "image/jpeg" }));
  }, "image/jpeg", 0.92);
}

function onRecordPointerDown(event: PointerEvent) {
  event.preventDefault();
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  holdStartedRecording.value = false;
  clearHoldTimer();
  holdTimer.value = window.setTimeout(() => {
    holdTimer.value = null;
    holdStartedRecording.value = true;
    startRecording();
  }, holdThresholdMs);
}

function onRecordPointerUp(event: PointerEvent) {
  event.preventDefault();
  (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
  if (holdTimer.value !== null) {
    clearHoldTimer();
    capturePhoto();
    return;
  }
  if (holdStartedRecording.value && isRecording.value) {
    stopRecording();
  }
  holdStartedRecording.value = false;
}

async function setStoryFile(file: File, knownDurationMs: number | null = null) {
  if (!isSupportedStoryFile(file)) {
    permissionError.value = "Поддерживаются JPEG, PNG, WebP, MP4, WebM, MP3 и M4A/AAC.";
    return;
  }
  const sizeLimit = file.type.startsWith("image/") ? 40 * 1024 * 1024 : 500 * 1024 * 1024;
  if (!file.size || file.size > sizeLimit) {
    permissionError.value = file.size ? "Файл слишком большой для истории." : "Пустой файл нельзя опубликовать.";
    return;
  }
  permissionError.value = "";
  revokeMediaUrl();
  mediaFile.value = file;
  const duration = knownDurationMs ?? await readMediaDurationMs(file);
  if ((file.type.startsWith("video/") || file.type.startsWith("audio/")) && duration && duration > maxStoryVideoMs + 250) {
    mediaFile.value = null;
    permissionError.value = "История может длиться не больше 60 секунд.";
    return;
  }
  mediaDurationMs.value = duration;
  mediaUrl.value = URL.createObjectURL(file);
  setState({ type: "SELECT_FILE" });
}

function isSupportedStoryFile(file: File): boolean {
  return new Set([
    "image/jpeg", "image/png", "image/webp",
    "video/mp4", "video/webm",
    "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-m4a",
  ]).has(file.type.toLowerCase());
}

function onFileInput(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) void setStoryFile(file);
  input.value = "";
}

function mediaType(file: File): StoryBlock["type"] {
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  return "IMAGE";
}

function storyDurationMetadata(file: File): Record<string, number> {
  if (!file.type.startsWith("video/") && !file.type.startsWith("audio/")) return {};
  const mediaDuration = mediaDurationMs.value ?? maxStoryVideoMs;
  const duration = Math.min(mediaDuration, maxStoryVideoMs);
  return {
    mediaDurationMs: Math.round(mediaDuration),
    durationMs: Math.round(duration),
    trimStartMs: 0,
    trimEndMs: Math.round(duration),
  };
}

function processedStoryData(asset: PostAsset): Record<string, unknown> {
  const preferred = asset.kind === "VIDEO"
    ? ["video-1080"]
    : asset.kind === "AUDIO"
      ? ["audio"]
      : ["image-1440", "image-960", "image-2048", "image-480"];
  const variant = preferred.map((name) => asset.variants?.find((item) => item.name === name)).find(Boolean);
  if (!asset.assetId || !asset.generation || !variant?.name) throw new Error("Media не подготовил версию для истории.");
  return {
    assetId: asset.assetId,
    generation: asset.generation,
    variantName: variant.name,
    deliveryContract: "STABLE_V2",
    mimeType: variant.mimeType,
    width: variant.width,
    height: variant.height,
    posterVariantName: asset.variants?.some((item) => item.name === "poster") ? "poster" : undefined,
    mediaDurationMs: asset.durationMs || mediaDurationMs.value || undefined,
  };
}

function readMediaDurationMs(file: File): Promise<number | null> {
  if (!file.type.startsWith("video/") && !file.type.startsWith("audio/")) return Promise.resolve(null);
  return new Promise((resolve) => {
    const element = document.createElement(file.type.startsWith("video/") ? "video" : "audio");
    const url = URL.createObjectURL(file);
    const cleanup = () => URL.revokeObjectURL(url);
    element.preload = "metadata";
    element.onloadedmetadata = () => {
      const seconds = Number.isFinite(element.duration) ? element.duration : 0;
      cleanup();
      resolve(seconds > 0 ? seconds * 1000 : null);
    };
    element.onerror = () => {
      cleanup();
      resolve(null);
    };
    element.src = url;
  });
}

function onPreviewTimeUpdate(event: Event) {
  const element = event.target as HTMLVideoElement | HTMLAudioElement;
  if (element.currentTime * 1000 >= maxStoryVideoMs) {
    element.currentTime = 0;
    void element.play().catch(() => undefined);
  }
}

async function publish() {
  if (!mediaFile.value) {
    toast.add({ severity: "warn", summary: "Story", detail: "Record or choose media first", life: 3000 });
    return;
  }
  setState({ type: "PUBLISH" });
  uploadProgress.value = 0;
  try {
    publishStage.value = "Загружаем оригинал";
    const uploaded = await ContentService.uploadMediaAsset(mediaFile.value, undefined, {
      sourcePolicyId: "browser-capture-v1",
      onProgress: (value) => { uploadProgress.value = Math.round(value * 55); },
    });
    const assetId = uploaded.assetId || uploaded.id;
    publishStage.value = "Проверяем файл";
    const available = await ContentService.waitForMediaAsset(assetId, {
      onUpdate: () => { uploadProgress.value = Math.max(uploadProgress.value, 62); },
    });
    if (available.status === "FAILED" || available.status === "CANCELLED") {
      throw new Error(available.failureReason || "Media отклонил файл истории.");
    }
    publishStage.value = "Готовим версию для просмотра";
    const processing = await ContentService.retryMediaAssetProcessing(assetId);
    const ready = processing.status === "READY"
      ? processing
      : await ContentService.waitForMediaAsset(assetId, {
          onUpdate: () => { uploadProgress.value = Math.max(uploadProgress.value, 78); },
        });
    if (ready.status !== "READY") throw new Error(ready.failureReason || "Не удалось обработать историю.");
    uploadProgress.value = 92;
    const block: StoryBlock = {
      id: crypto.randomUUID(),
      type: mediaType(mediaFile.value),
      data: {
        ...processedStoryData(ready),
        caption: state.value.caption.trim(),
        tags: storyTags.value,
        ...storyDurationMetadata(mediaFile.value),
      },
    };
    const story = await ContentService.createStory(
      {
        blocks: [
          block,
          ...(state.value.caption.trim()
            ? [{ id: crypto.randomUUID(), type: "TEXT" as const, data: { text: state.value.caption.trim(), tags: storyTags.value } }]
            : []),
        ],
        caption: state.value.caption.trim() || undefined,
        tags: storyTags.value,
        visibility: state.value.visibility,
      },
    );
    uploadProgress.value = 100;
    publishStage.value = "Опубликовано";
    toast.add({ severity: "success", summary: "Story published", life: 2500 });
    await router.push({ path: `/story/${story.id}`, query: { author: story.ownerId || story.authorId, ownerType: story.ownerType || "USER" } });
  } catch (error) {
    toast.add({ severity: "error", summary: "Story", detail: error instanceof Error ? error.message : "Unable to publish story", life: 5000 });
    setState({ type: "EDIT" });
    publishStage.value = "";
  }
}

onMounted(() => {
  void loadCurrentActor();
  void openCamera();
});

async function loadCurrentActor() {
  try {
    currentActor.value = await ContentService.currentActor();
  } catch {
    currentActor.value = null;
  }
}

onBeforeUnmount(() => {
  clearHoldTimer();
  clearRecordingLimitTimer();
  mediaRecorder.value?.state === "recording" && mediaRecorder.value.stop();
  stopStream();
  revokeMediaUrl();
});
</script>

<template>
  <section class="story-composer" :class="{ 'story-composer--edit': isEditing }">
    <header class="story-topbar">
      <button type="button" aria-label="Close" @click="router.push('/')"><i class="pi pi-times"></i></button>
      <div class="story-title-group">
        <span>{{ isEditing ? "Edit story" : "Create story" }}</span>
        <a v-if="activeOwner" class="story-owner-pill" :href="activeOwnerPath" :title="activeOwnerName">
          <span class="story-owner-avatar">
            <img v-if="activeOwner.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
            <i v-else-if="activeOwner.ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
            <strong v-else>{{ activeOwnerInitial }}</strong>
          </span>
          <small>{{ activeOwnerName }} · @{{ activeOwner.username }}</small>
        </a>
      </div>
      <button v-if="isEditing" type="button" :disabled="state.status === 'publishing'" @click="publish">
        <i class="pi pi-send"></i>
        <span>{{ state.status === "publishing" ? "Публикуем" : "Опубликовать" }}</span>
      </button>
    </header>

    <main v-if="!isEditing" class="capture-stage">
      <video ref="videoPreview" autoplay playsinline muted></video>
      <div v-if="permissionError" class="camera-error">
        <i class="pi pi-video"></i>
        <strong>{{ permissionError }}</strong>
        <button type="button" @click="fileInput?.click()">Choose file</button>
      </div>
      <div class="recording-indicator" :class="{ active: isRecording }">
        <span></span>{{ isRecording ? "Recording" : "Ready" }}
      </div>
      <div class="capture-controls">
        <button v-if="!isRecording" type="button" class="side-control" aria-label="Choose file" @click="fileInput?.click()">
          <i class="pi pi-image"></i>
        </button>
        <span v-else class="side-control side-control--hidden"></span>
        <button
          type="button"
          class="record-button"
          :class="{ active: isRecording }"
          aria-label="Take photo or hold to record story"
          @pointerdown="onRecordPointerDown"
          @pointerup="onRecordPointerUp"
          @pointercancel="onRecordPointerUp"
          @pointerleave="isRecording && onRecordPointerUp($event)"
        ></button>
        <button type="button" class="side-control" aria-label="Switch camera" @click="switchCamera">
          <i class="pi pi-refresh"></i>
        </button>
      </div>
      <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp,video/mp4,video/webm,audio/mpeg,audio/mp4,audio/aac" @change="onFileInput" />
    </main>

    <main v-else class="edit-stage">
      <section class="phone-preview">
        <img v-if="mediaFile?.type.startsWith('image/')" :src="mediaUrl" alt="" />
        <video v-else-if="mediaFile?.type.startsWith('video/')" :src="mediaUrl" autoplay loop muted playsinline controls @timeupdate="onPreviewTimeUpdate" />
        <audio v-else :src="mediaUrl" controls @timeupdate="onPreviewTimeUpdate" />
        <div v-if="state.caption.trim()" class="caption-peek">
          {{ state.caption.trim() }}
        </div>
      </section>

      <aside class="story-edit-panel">
        <label>
          <span>Caption</span>
          <textarea
            :value="state.caption"
            rows="6"
            placeholder="Add a description. Hashtags like #travel will stay searchable."
            @input="setState({ type: 'SET_CAPTION', caption: ($event.target as HTMLTextAreaElement).value })"
          ></textarea>
        </label>
        <label>
          <span>Visibility</span>
          <select :value="state.visibility" @change="setState({ type: 'SET_VISIBILITY', visibility: ($event.target as HTMLSelectElement).value as 'PUBLIC' | 'CLOSE_FRIENDS' })">
            <option value="PUBLIC">Everyone</option>
            <option value="CLOSE_FRIENDS">Close friends</option>
          </select>
        </label>
        <div class="story-tags">
          <span v-for="tag in storyTags" :key="tag">#{{ tag }}</span>
          <small v-if="!storyTags.length">Hashtags are read from the caption.</small>
        </div>
        <div v-if="state.status === 'publishing'" class="story-publish-progress" role="status" aria-live="polite">
          <span><i :style="{ width: `${uploadProgress}%` }"></i></span>
          <small>{{ publishStage }} · {{ uploadProgress }}%</small>
        </div>
        <button type="button" class="publish-story" :disabled="state.status === 'publishing'" @click="publish">
          <i class="pi pi-send"></i>
          <span>{{ state.status === "publishing" ? "Публикуем" : "Опубликовать" }}</span>
        </button>
      </aside>
    </main>
  </section>
</template>

<style scoped>
.story-composer {
  min-height: 100dvh;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 18%, rgba(0, 229, 255, 0.24), transparent 28%),
    radial-gradient(circle at 82% 20%, rgba(255, 79, 123, 0.2), transparent 28%),
    linear-gradient(142deg, rgba(246, 255, 24, 0.92) 0 13%, transparent 13%),
    #05070b;
  color: #ffffff;
}

.story-composer--edit {
  background:
    radial-gradient(circle at 20% 20%, rgba(0, 229, 255, 0.2), transparent 30%),
    radial-gradient(circle at 84% 18%, rgba(255, 79, 123, 0.18), transparent 28%),
    linear-gradient(142deg, rgba(246, 255, 24, 0.9) 0 13%, transparent 13%),
    #05070b;
  color: #ffffff;
}

.story-topbar {
  position: fixed;
  z-index: 20;
  top: 0;
  left: 0;
  right: 0;
  height: 74px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px clamp(16px, 4vw, 36px);
  pointer-events: none;
}

.story-topbar button {
  min-width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: var(--comic-line);
  border-radius: 8px;
  padding: 0 14px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  font: inherit;
  font-weight: 900;
  cursor: pointer;
  pointer-events: auto;
  box-shadow: var(--comic-shadow-small);
}

.story-composer--edit .story-topbar button {
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
}

.story-topbar span {
  font-weight: 900;
}

.story-title-group {
  display: grid;
  justify-items: center;
  gap: 6px;
  min-width: 0;
  pointer-events: auto;
}

.story-owner-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: min(260px, 46vw);
  border: 3px solid var(--comic-ink);
  border-radius: 8px;
  padding: 5px 9px 5px 5px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  text-decoration: none;
  box-shadow: var(--comic-shadow-small);
}

.story-owner-avatar {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 2px solid var(--comic-ink);
  border-radius: 6px;
  background: var(--comic-cyan);
  color: var(--comic-ink);
  font-size: 11px;
}

.story-owner-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.story-owner-pill small {
  overflow: hidden;
  font-size: 11px;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capture-stage {
  position: fixed;
  inset: 0;
  display: grid;
  place-items: center;
}

.capture-stage video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.capture-stage input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.camera-error {
  position: absolute;
  width: min(360px, calc(100vw - 32px));
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 22px;
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  box-shadow: var(--comic-shadow);
  text-align: center;
  backdrop-filter: blur(16px);
}

.camera-error i {
  font-size: 32px;
}

.camera-error button,
.publish-story {
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-yellow);
  color: var(--comic-ink);
  font: inherit;
  font-family: var(--display-font);
  font-size: 12px;
  font-weight: 400;
  cursor: pointer;
  box-shadow: var(--comic-shadow-small);
}

.camera-error button {
  height: 40px;
  padding: 0 16px;
}

.recording-indicator {
  position: fixed;
  top: 88px;
  left: 50%;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-radius: 999px;
  padding: 8px 12px;
  background: rgba(9, 13, 20, 0.58);
  font-size: 12px;
  font-weight: 900;
  transform: translateX(-50%);
  backdrop-filter: blur(16px);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.12),
    0 14px 38px rgba(0, 0, 0, 0.28);
}

.recording-indicator span {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #94a3b8;
}

.recording-indicator.active span {
  background: #ef4444;
}

.capture-controls {
  position: fixed;
  left: 0;
  right: 0;
  bottom: max(26px, env(safe-area-inset-bottom));
  display: grid;
  grid-template-columns: 62px 92px 62px;
  justify-content: center;
  align-items: center;
  gap: 26px;
}

.side-control,
.record-button {
  border: var(--comic-line);
  border-radius: 999px;
  cursor: pointer;
}

.side-control {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  font-size: 20px;
  box-shadow: var(--comic-shadow-small);
  transition: transform 160ms ease, background 160ms ease;
}

.side-control:hover {
  transform: translateY(-2px);
  background: var(--comic-yellow);
}

.side-control--hidden {
  visibility: hidden;
}

.record-button {
  width: 86px;
  height: 86px;
  border: var(--comic-line);
  background:
    radial-gradient(circle, var(--comic-paper-bright) 0 44%, transparent 45%),
    conic-gradient(from 210deg, var(--comic-cyan), var(--comic-magenta) 42%, var(--comic-coral) 62%, var(--comic-lime) 84%, var(--comic-cyan));
  box-shadow: var(--comic-shadow);
  transition: transform 140ms ease, border-radius 140ms ease, filter 140ms ease;
}

.record-button.active {
  border-radius: 28px;
  filter: drop-shadow(0 0 28px rgba(255, 79, 123, 0.46));
  transform: scale(0.88) rotate(45deg);
}

.edit-stage {
  width: min(1100px, 100%);
  min-height: 100dvh;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(280px, 440px) minmax(0, 1fr);
  gap: 34px;
  align-items: center;
  padding: 96px clamp(16px, 4vw, 36px) 36px;
}

.phone-preview {
  position: relative;
  width: min(420px, 82vw);
  aspect-ratio: 1;
  overflow: hidden;
  border: var(--comic-line);
  border-radius: 999px;
  background:
    linear-gradient(#101827, #101827) padding-box,
    conic-gradient(from 210deg, var(--comic-cyan), var(--comic-magenta) 40%, var(--comic-lime) 74%, var(--comic-cyan)) border-box;
  box-shadow: var(--comic-shadow);
}

.phone-preview::before,
.phone-preview::after {
  content: "";
  position: absolute;
  z-index: 3;
  border-radius: 999px;
  pointer-events: none;
}

.phone-preview::before {
  inset: 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
}

.phone-preview::after {
  right: 12%;
  bottom: 8%;
  width: 17px;
  height: 17px;
  background: #ffffff;
  box-shadow:
    -128px -300px 0 -5px rgba(34, 211, 238, 0.78),
    36px -248px 0 -4px rgba(34, 197, 94, 0.7);
}

.phone-preview img,
.phone-preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.phone-preview audio {
  position: absolute;
  left: 15%;
  right: 15%;
  bottom: 42%;
  width: 70%;
}

.caption-peek {
  position: absolute;
  z-index: 4;
  left: 13%;
  right: 13%;
  bottom: 12%;
  max-height: 64px;
  overflow: hidden;
  border: 3px solid var(--comic-ink);
  border-radius: 8px;
  padding: 12px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  font-weight: 800;
  line-height: 1.3;
  backdrop-filter: blur(14px);
}

.story-edit-panel {
  display: grid;
  gap: 16px;
  border: var(--comic-line);
  border-radius: 8px;
  padding: 22px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  box-shadow: var(--comic-shadow);
}

.story-edit-panel label {
  display: grid;
  gap: 8px;
}

.story-edit-panel label > span {
  color: #46505a;
  font-size: 12px;
  font-weight: 900;
  text-transform: uppercase;
}

.story-edit-panel textarea,
.story-edit-panel select {
  width: 100%;
  border: 3px solid var(--comic-ink);
  border-radius: 8px;
  background: #ffffff;
  color: var(--comic-ink);
  font: inherit;
  font-weight: 800;
  outline: 0;
}

.story-edit-panel textarea::placeholder {
  color: #73706a;
}

.story-edit-panel textarea {
  padding: 16px;
  font-size: 18px;
  line-height: 1.5;
  resize: vertical;
}

.story-edit-panel select {
  height: 46px;
  padding: 0 12px;
}

.story-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.story-tags span {
  padding: 7px 10px;
  border: 2px solid var(--comic-ink);
  border-radius: 6px;
  background: var(--comic-cyan);
  color: var(--comic-ink);
  font-size: 12px;
  font-weight: 900;
}

.story-tags small {
  color: #46505a;
  font-weight: 800;
}

.publish-story {
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #ffffff;
  color: #0f172a;
}

.publish-story:disabled {
  opacity: 0.6;
  cursor: wait;
}

.story-publish-progress {
  display: grid;
  gap: 7px;
}

.story-publish-progress > span {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e8eaf0;
}

.story-publish-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #5b5df0;
  transition: width 220ms ease;
}

.story-publish-progress small {
  color: #5e6473;
  font-weight: 700;
}

/* Story v2: media-first surfaces without the retired comic treatment. */
.story-composer,
.story-composer--edit {
  background: #f3f4f7;
  color: #1a1c24;
}

.story-topbar {
  background: #f3f4f7;
}

.story-topbar button,
.story-composer--edit .story-topbar button {
  border: 0;
  border-radius: 999px;
  background: #ffffff;
  color: #232631;
  box-shadow: 0 8px 24px rgba(88, 92, 112, 0.14);
}

.phone-preview {
  width: min(390px, 82vw);
  aspect-ratio: 9 / 16;
  border: 0;
  border-radius: 30px;
  background: #ffffff;
  box-shadow: 0 22px 60px rgba(73, 78, 100, 0.18);
}

.phone-preview::before,
.phone-preview::after {
  display: none;
}

.phone-preview img,
.phone-preview video {
  object-fit: contain;
  background: #ffffff;
}

.caption-peek {
  border: 0;
  border-radius: 16px;
  background: #ffffff;
  color: #252834;
  box-shadow: 0 10px 30px rgba(73, 78, 100, 0.15);
}

.story-edit-panel {
  border: 0;
  border-radius: 24px;
  background: #ffffff;
  color: #232631;
  box-shadow: 0 18px 52px rgba(73, 78, 100, 0.14);
}

.story-edit-panel textarea,
.story-edit-panel select {
  border: 0;
  border-radius: 14px;
  background: #f1f2f6;
  color: #232631;
}

.story-tags span {
  border: 0;
  border-radius: 999px;
  background: #e8e7ff;
  color: #4b46c8;
}

.publish-story {
  border: 0;
  border-radius: 999px;
  background: #5b5df0;
  color: #ffffff;
  box-shadow: 0 12px 28px rgba(91, 93, 240, 0.28);
}

@media (max-width: 840px) {
  .edit-stage {
    grid-template-columns: 1fr;
    justify-items: center;
  }

  .story-edit-panel {
    width: 100%;
  }
}
</style>
