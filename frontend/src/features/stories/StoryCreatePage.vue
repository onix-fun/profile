<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { StoryBlock } from "@/api/types";
import {
  emptyStoryComposerState,
  extractStoryTags,
  reduceStoryComposer,
} from "@/features/stories/storyState";

const router = useRouter();
const toast = useToast();
const state = ref(emptyStoryComposerState());
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

const isRecording = computed(() => state.value.status === "recording");
const isEditing = computed(() => state.value.status === "edit" || state.value.status === "publishing");
const storyTags = computed(() => extractStoryTags(state.value.caption));

function setState(action: Parameters<typeof reduceStoryComposer>[1]) {
  state.value = reduceStoryComposer(state.value, action);
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
    void setStoryFile(new File([blob], `story-${Date.now()}.webm`, { type: blob.type || "video/webm" }), maxStoryVideoMs);
    setState({ type: "STOP_RECORDING", mediaReady: true });
  };
  mediaRecorder.value = recorder;
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
  revokeMediaUrl();
  mediaFile.value = file;
  mediaDurationMs.value = knownDurationMs ?? await readMediaDurationMs(file);
  mediaUrl.value = URL.createObjectURL(file);
  setState({ type: "SELECT_FILE" });
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
  try {
    const block: StoryBlock = {
      id: crypto.randomUUID(),
      type: mediaType(mediaFile.value),
      data: {
        fileName: mediaFile.value.name,
        mimeType: mediaFile.value.type,
        size: mediaFile.value.size,
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
      [mediaFile.value],
    );
    toast.add({ severity: "success", summary: "Story published", life: 2500 });
    await router.push({ path: `/story/${story.id}`, query: { author: story.authorId } });
  } catch (error) {
    toast.add({ severity: "error", summary: "Story", detail: error instanceof Error ? error.message : "Unable to publish story", life: 5000 });
    setState({ type: "EDIT" });
  }
}

onMounted(() => {
  void openCamera();
});

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
      <span>{{ isEditing ? "Edit story" : "Create story" }}</span>
      <button v-if="isEditing" type="button" :disabled="state.status === 'publishing'" @click="publish">
        <i class="pi pi-send"></i>
        <span>{{ state.status === "publishing" ? "Publishing" : "Publish" }}</span>
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
      <input ref="fileInput" type="file" accept="image/*,video/*,audio/*" @change="onFileInput" />
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
        <button type="button" class="publish-story" :disabled="state.status === 'publishing'" @click="publish">
          <i class="pi pi-send"></i>
          <span>{{ state.status === "publishing" ? "Publishing" : "Publish" }}</span>
        </button>
      </aside>
    </main>
  </section>
</template>

<style scoped>
.story-composer {
  min-height: 100dvh;
  overflow: hidden;
  background: #05070b;
  color: #ffffff;
}

.story-composer--edit {
  background: linear-gradient(180deg, #ffffff 0%, #f7f9fb 100%);
  color: #111827;
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
  border: 0;
  border-radius: 999px;
  padding: 0 14px;
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
  font: inherit;
  font-weight: 900;
  cursor: pointer;
  pointer-events: auto;
}

.story-composer--edit .story-topbar button {
  background: #111827;
}

.story-topbar span {
  font-weight: 900;
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
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.82);
  text-align: center;
  backdrop-filter: blur(16px);
}

.camera-error i {
  font-size: 32px;
}

.camera-error button,
.publish-story {
  border: 0;
  border-radius: 999px;
  background: #ffffff;
  color: #111827;
  font: inherit;
  font-weight: 900;
  cursor: pointer;
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
  background: rgba(15, 23, 42, 0.52);
  font-size: 12px;
  font-weight: 900;
  transform: translateX(-50%);
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
  border: 0;
  border-radius: 999px;
  cursor: pointer;
}

.side-control {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.18);
  color: #ffffff;
  font-size: 20px;
  backdrop-filter: blur(12px);
}

.side-control--hidden {
  visibility: hidden;
}

.record-button {
  width: 86px;
  height: 86px;
  border: 5px solid #ffffff;
  background: #ffffff;
  box-shadow: inset 0 0 0 8px #ef4444;
  transition: transform 140ms ease, border-radius 140ms ease;
}

.record-button.active {
  border-radius: 24px;
  transform: scale(0.88);
}

.edit-stage {
  width: min(1100px, 100%);
  min-height: 100dvh;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(280px, 390px) minmax(0, 1fr);
  gap: 28px;
  align-items: center;
  padding: 96px clamp(16px, 4vw, 36px) 36px;
}

.phone-preview {
  position: relative;
  width: min(390px, 100%);
  aspect-ratio: 9 / 16;
  overflow: hidden;
  border: 8px solid #111827;
  border-radius: 34px;
  background: #111827;
  box-shadow: 0 30px 90px rgba(15, 23, 42, 0.26);
}

.phone-preview img,
.phone-preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.phone-preview audio {
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 88px;
  width: calc(100% - 36px);
}

.caption-peek {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 18px;
  max-height: 58px;
  overflow: hidden;
  border-radius: 12px;
  padding: 12px;
  background: rgba(0, 0, 0, 0.5);
  color: #ffffff;
  font-weight: 800;
  line-height: 1.3;
}

.story-edit-panel {
  display: grid;
  gap: 16px;
}

.story-edit-panel label {
  display: grid;
  gap: 8px;
}

.story-edit-panel label > span {
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
  text-transform: uppercase;
}

.story-edit-panel textarea,
.story-edit-panel select {
  width: 100%;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.94);
  color: #111827;
  font: inherit;
  font-weight: 800;
  outline: 0;
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
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
  font-size: 12px;
  font-weight: 900;
}

.story-tags small {
  color: #64748b;
  font-weight: 800;
}

.publish-story {
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #111827;
  color: #ffffff;
}

.publish-story:disabled {
  opacity: 0.6;
  cursor: wait;
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
