<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from "vue";
import { onBeforeRouteLeave, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import {
  applyFormatting,
  attachmentMarkdown,
  buildCreatePostInput,
  createAttachment,
  emptyPostEditorState,
  extractHashtags,
  isPostEditorDirty,
  type PostAttachment,
} from "@/features/editor/postEditor";

const router = useRouter();
const toast = useToast();
const state = ref(emptyPostEditorState());
const textarea = ref<HTMLTextAreaElement | null>(null);
const isPublishing = ref(false);
const hasPublished = ref(false);
const isDragging = ref(false);

const detectedTags = computed(() => extractHashtags(state.value.markdown));
const allTags = computed(() => Array.from(new Set([...detectedTags.value, ...state.value.tags])));
const isDirty = computed(() => isPostEditorDirty(state.value));
const renderedParagraphs = computed(() =>
  state.value.markdown
    .split(/\n{2,}/)
    .map((line) => line.trim())
    .filter((line) => line && !attachmentByRef(line)),
);

function revokeAttachment(attachment: PostAttachment) {
  URL.revokeObjectURL(attachment.url);
}

function insertAtCursor(value: string) {
  const input = textarea.value;
  const start = input?.selectionStart ?? state.value.markdown.length;
  const end = input?.selectionEnd ?? state.value.markdown.length;
  state.value.markdown = `${state.value.markdown.slice(0, start)}${value}${state.value.markdown.slice(end)}`;
  void nextTick(() => {
    textarea.value?.focus();
    textarea.value?.setSelectionRange(start + value.length, start + value.length);
  });
}

function format(marker: "**" | "_" | "`") {
  const input = textarea.value;
  const start = input?.selectionStart ?? state.value.markdown.length;
  const end = input?.selectionEnd ?? state.value.markdown.length;
  const result = applyFormatting(state.value.markdown, start, end, marker);
  state.value.markdown = result.markdown;
  void nextTick(() => {
    textarea.value?.focus();
    textarea.value?.setSelectionRange(result.selectionStart, result.selectionEnd);
  });
}

function addFiles(files: FileList | File[]) {
  const items = Array.from(files);
  if (!items.length) return;
  const attachments = items.map(createAttachment);
  state.value.attachments.push(...attachments);
  insertAtCursor(`${state.value.markdown.endsWith("\n") || !state.value.markdown ? "" : "\n"}${attachments.map(attachmentMarkdown).join("\n")}\n`);
}

function onFileInput(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files) addFiles(input.files);
  input.value = "";
}

function onDrop(event: DragEvent) {
  isDragging.value = false;
  if (event.dataTransfer?.files.length) addFiles(event.dataTransfer.files);
}

function removeAttachment(id: string) {
  const attachment = state.value.attachments.find((item) => item.id === id);
  if (attachment) revokeAttachment(attachment);
  state.value.attachments = state.value.attachments.filter((item) => item.id !== id);
  state.value.markdown = state.value.markdown
    .replace(new RegExp(`\\n?\\[[^\\]]+\\]\\(media:${id}\\)`, "g"), "")
    .trimStart();
}

function attachmentByRef(line: string): PostAttachment | undefined {
  const id = line.match(/\(media:([^)]+)\)/)?.[1];
  return state.value.attachments.find((item) => item.id === id);
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function renderInlineMarkdown(line: string): string {
  return escapeHtml(line)
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/_([^_]+)_/g, "<em>$1</em>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/(^|\s)#([\p{L}\p{N}_-]+)/gu, '$1<span class="tag-inline">#$2</span>')
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\[([^\]]+)\]\(media:[^)]+\)/g, "$1");
}

function confirmDiscard(): boolean {
  if (!isDirty.value || hasPublished.value) return true;
  return window.confirm("Discard this post?");
}

async function publish() {
  isPublishing.value = true;
  try {
    const post = await ContentService.createPost(
      buildCreatePostInput(state.value),
      state.value.attachments.map((attachment) => attachment.file),
    );
    hasPublished.value = true;
    toast.add({ severity: "success", summary: "Post published", life: 2500 });
    await router.push(`/p/${post.id}`);
  } catch (error) {
    toast.add({ severity: "error", summary: "Publish", detail: error instanceof Error ? error.message : "Unable to publish", life: 5000 });
  } finally {
    isPublishing.value = false;
  }
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (!isDirty.value || hasPublished.value) return;
  event.preventDefault();
  event.returnValue = "";
}

window.addEventListener("beforeunload", onBeforeUnload);

onBeforeRouteLeave(() => confirmDiscard());

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", onBeforeUnload);
  state.value.attachments.forEach(revokeAttachment);
});
</script>

<template>
  <section class="post-editor-page" @dragover.prevent="isDragging = true" @dragleave="isDragging = false" @drop.prevent="onDrop">
    <header class="post-editor-header">
      <button type="button" class="icon-button" aria-label="Back" @click="confirmDiscard() && router.push('/')">
        <i class="pi pi-arrow-left"></i>
      </button>
      <div class="toolbar" aria-label="Formatting toolbar">
        <button type="button" title="Bold" @click="format('**')"><strong>B</strong></button>
        <button type="button" title="Italic" @click="format('_')"><em>I</em></button>
        <button type="button" title="Code" @click="format('`')"><i class="pi pi-code"></i></button>
        <button type="button" title="Heading" @click="insertAtCursor('## ')"><i class="pi pi-hashtag"></i></button>
        <label title="Attach media">
          <i class="pi pi-paperclip"></i>
          <input type="file" multiple accept="image/*,video/*,audio/*" @change="onFileInput" />
        </label>
        <label class="comments-toggle" title="Comments">
          <i class="pi pi-comments"></i>
          <input v-model="state.allowComments" type="checkbox" />
        </label>
      </div>
      <button type="button" class="publish-button" :disabled="isPublishing" @click="publish">
        <i class="pi pi-send"></i>
        <span>{{ isPublishing ? "Publishing" : "Publish" }}</span>
      </button>
    </header>

    <main class="post-editor-grid" :class="{ 'is-dragging': isDragging }">
      <article class="markdown-workspace" aria-label="Post markdown editor">
        <textarea
          ref="textarea"
          v-model="state.markdown"
          spellcheck="true"
          placeholder="Write. Select text to format it. Attach media, use #hashtags inline."
        ></textarea>

        <section v-if="state.attachments.length" class="media-canvas" aria-label="Attached media">
          <article v-for="attachment in state.attachments" :key="attachment.id" class="media-card">
            <img v-if="attachment.type === 'IMAGE'" :src="attachment.url" alt="" />
            <video v-else-if="attachment.type === 'VIDEO'" :src="attachment.url" muted playsinline controls />
            <audio v-else :src="attachment.url" controls />
            <code>{{ attachment.file.name }}</code>
            <button type="button" aria-label="Remove attachment" @click="removeAttachment(attachment.id)">
              <i class="pi pi-times"></i>
            </button>
          </article>
        </section>

        <section v-if="renderedParagraphs.length" class="live-preview" aria-label="Formatted preview">
          <p v-for="line in renderedParagraphs" :key="line" v-html="renderInlineMarkdown(line)"></p>
        </section>

        <footer class="editor-meta">
          <span v-for="tag in allTags" :key="tag">#{{ tag }}</span>
        </footer>
      </article>
    </main>
  </section>
</template>

<style scoped>
.post-editor-page {
  min-height: 100dvh;
  padding: 96px clamp(14px, 4vw, 48px) 32px;
  background: #f8fafc;
}

.post-editor-header {
  width: min(980px, 100%);
  margin: 0 auto 14px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
}

.icon-button,
.publish-button,
.toolbar button,
.toolbar label,
.media-card button {
  border: 0;
  border-radius: 999px;
  background: #ffffff;
  color: #111827;
  font: inherit;
  font-weight: 900;
  cursor: pointer;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.08);
}

.icon-button {
  width: 42px;
  height: 42px;
}

.publish-button {
  height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  background: #111827;
  color: #ffffff;
}

.publish-button:disabled {
  opacity: 0.6;
  cursor: wait;
}

.post-editor-grid {
  width: min(980px, 100%);
  margin: 0 auto;
}

.post-editor-grid.is-dragging .markdown-workspace {
  border-color: #111827;
  box-shadow: 0 0 0 4px rgba(17, 24, 39, 0.08), 0 24px 58px rgba(15, 23, 42, 0.12);
}

.markdown-workspace {
  min-height: calc(100dvh - 146px);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 20px 54px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: center;
}

.toolbar button,
.toolbar label {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
}

.toolbar input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.comments-toggle {
  position: relative;
}

.comments-toggle:has(input:checked) {
  background: #111827;
  color: #ffffff;
}

textarea {
  width: 100%;
  min-height: 44dvh;
  border: 0;
  padding: clamp(22px, 4vw, 42px);
  background: transparent;
  color: #111827;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: clamp(18px, 2vw, 24px);
  line-height: 1.65;
  resize: none;
  outline: 0;
}

.media-canvas {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  padding: 0 clamp(18px, 4vw, 42px) 24px;
}

.media-card {
  position: relative;
  min-height: 180px;
  overflow: hidden;
  border-radius: 8px;
  background: #111827;
}

.media-card img,
.media-card video {
  width: 100%;
  height: 100%;
  min-height: 220px;
  display: block;
  object-fit: cover;
}

.media-card audio {
  width: 100%;
  margin-top: 72px;
}

.media-card code {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 16px;
  background: #0f172a;
  color: #ffffff;
  font-size: 13px;
  line-height: 1.5;
  opacity: 0;
  overflow-wrap: anywhere;
  transition: opacity 140ms ease;
}

.media-card:hover img,
.media-card:hover video,
.media-card:hover audio {
  opacity: 0;
}

.media-card:hover code {
  opacity: 1;
}

.media-card button {
  position: absolute;
  z-index: 2;
  top: 10px;
  right: 10px;
  width: 32px;
  height: 32px;
}

.live-preview {
  display: grid;
  gap: 14px;
  padding: 0 clamp(22px, 4vw, 42px) 28px;
}

.live-preview p {
  margin: 0;
  color: #1f2937;
  font-size: clamp(17px, 1.8vw, 21px);
  line-height: 1.65;
  white-space: pre-wrap;
}

.live-preview :deep(strong) {
  font-weight: 900;
}

.live-preview :deep(em) {
  font-style: italic;
}

.live-preview :deep(code) {
  border-radius: 5px;
  padding: 2px 5px;
  background: #e2e8f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
}

.live-preview :deep(.tag-inline),
.editor-meta span {
  color: #047857;
  font-weight: 900;
}

.editor-meta {
  min-height: 44px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 clamp(22px, 4vw, 42px) 28px;
}

.editor-meta span {
  border-radius: 999px;
  padding: 7px 10px;
  background: #ecfdf5;
  font-size: 13px;
}

@media (max-width: 720px) {
  .post-editor-header {
    grid-template-columns: auto 1fr;
  }

  .publish-button {
    grid-column: 1 / -1;
    justify-content: center;
  }
}
</style>
