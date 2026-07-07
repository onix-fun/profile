<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { onBeforeRouteLeave, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import ContentEditor from "@/features/contentDocument/ContentEditor.vue";
import {
  buildCreatePostInput,
  emptyPostEditorState,
  filesFromAttachments,
  isPostEditorDirty,
  type PostAttachment,
} from "@/features/editor/postEditor";

const router = useRouter();
const toast = useToast();
const state = ref(emptyPostEditorState());
const isPublishing = ref(false);
const hasPublished = ref(false);
const isDragging = ref(false);

const isDirty = computed(() => isPostEditorDirty(state.value));

function setAttachments(value: PostAttachment[]) {
  state.value.attachments = value;
}

function addDroppedFiles(files: FileList | File[]) {
  const input = document.querySelector<HTMLInputElement>(".content-editor input[type='file']");
  if (!input) return;
  const transfer = new DataTransfer();
  Array.from(files).forEach((file) => transfer.items.add(file));
  input.files = transfer.files;
  input.dispatchEvent(new Event("change", { bubbles: true }));
}

function onDrop(event: DragEvent) {
  isDragging.value = false;
  if (event.dataTransfer?.files.length) addDroppedFiles(event.dataTransfer.files);
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
      filesFromAttachments(state.value.attachments),
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
  state.value.attachments.forEach((attachment) => {
    if (attachment.url.startsWith("blob:")) URL.revokeObjectURL(attachment.url);
  });
});
</script>

<template>
  <section class="post-editor-page" @dragover.prevent="isDragging = true" @dragleave="isDragging = false" @drop.prevent="onDrop">
    <header class="post-editor-header">
      <button type="button" class="icon-button" aria-label="Back" @click="confirmDiscard() && router.push('/')">
        <i class="pi pi-arrow-left"></i>
      </button>
      <label class="comments-toggle" title="Comments">
        <i class="pi pi-comments"></i>
        <input v-model="state.allowComments" type="checkbox" />
      </label>
      <button type="button" class="publish-button" :disabled="isPublishing" @click="publish">
        <i class="pi pi-send"></i>
        <span>{{ isPublishing ? "Publishing" : "Publish" }}</span>
      </button>
    </header>

    <main class="post-editor-grid" :class="{ 'is-dragging': isDragging }">
      <article class="markdown-workspace" aria-label="Post markdown editor">
        <ContentEditor
          v-model="state.markdown"
          :attachments="state.attachments"
          placeholder="Start with # Title. Write like Obsidian. Drop files anywhere."
          @update:attachments="setAttachments"
        />
      </article>
    </main>
  </section>
</template>

<style scoped>
.post-editor-page {
  min-height: 100dvh;
  padding: 78px clamp(14px, 4vw, 48px) 32px;
  background: #f8fafc;
}

.post-editor-header {
  width: min(980px, 100%);
  margin: 0 auto 14px;
  display: grid;
  grid-template-columns: auto auto auto;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.icon-button,
.publish-button,
.comments-toggle {
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

.comments-toggle {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
}

.comments-toggle input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.comments-toggle:has(input:checked) {
  background: #111827;
  color: #ffffff;
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
  box-shadow: 0 0 0 4px rgba(20, 184, 166, 0.18), 0 24px 58px rgba(15, 23, 42, 0.12);
}

.markdown-workspace {
  min-height: calc(100dvh - 128px);
  padding: clamp(20px, 4vw, 46px);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 20px 54px rgba(15, 23, 42, 0.08);
}

@media (max-width: 720px) {
  .post-editor-header {
    grid-template-columns: auto auto;
  }

  .comments-toggle {
    order: 3;
  }

  .publish-button {
    min-width: 132px;
    justify-content: center;
  }
}
</style>
