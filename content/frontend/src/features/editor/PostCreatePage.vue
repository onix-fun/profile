<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { profileUrl } from "@/api/navigation";
import { ContentService } from "@/api/contentService";
import type { CurrentActor } from "@/api/types";
import ContentEditor from "@/features/contentDocument/ContentEditor.vue";
import {
  buildCreatePostInput,
  emptyPostEditorState,
  filesFromAttachments,
  isPostEditorDirty,
  type PostAttachment,
} from "@/features/editor/postEditor";

const router = useRouter();
const route = useRoute();
const toast = useToast();
const state = ref(emptyPostEditorState());
const isPublishing = ref(false);
const hasPublished = ref(false);
const isDragging = ref(false);
const currentActor = ref<CurrentActor | null>(null);

const isDirty = computed(() => isPostEditorDirty(state.value));
const activeOwner = computed(() => currentActor.value?.activeOwner || null);
const activeOwnerName = computed(() => activeOwner.value?.displayName || activeOwner.value?.username || "User");
const activeOwnerInitial = computed(() => activeOwnerName.value.slice(0, 1).toUpperCase());
const activeOwnerPath = computed(() => activeOwner.value ? ownerPath(activeOwner.value.ownerType || "USER", activeOwner.value.username) : "/");
const editingPostId = computed(() => typeof route.params.postId === "string" ? route.params.postId : "");
const isEditing = computed(() => Boolean(editingPostId.value));

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

function ownerPath(ownerType: "USER" | "ORGANIZATION", username: string): string {
  const prefix = ownerType === "ORGANIZATION" ? "o" : "u";
  return profileUrl(`/${prefix}/${encodeURIComponent(username)}`, true);
}

async function publish() {
  isPublishing.value = true;
  try {
    const input = buildCreatePostInput(state.value);
    const post = isEditing.value
      ? await ContentService.updatePost({ id: editingPostId.value, ...input }, filesFromAttachments(state.value.attachments))
      : await ContentService.createPost(input, filesFromAttachments(state.value.attachments));
    hasPublished.value = true;
    toast.add({ severity: "success", summary: isEditing.value ? "Post updated" : "Post published", life: 2500 });
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

onMounted(async () => {
  try {
    currentActor.value = await ContentService.currentActor();
    if (isEditing.value) await loadPostForEdit(editingPostId.value);
  } catch {
    currentActor.value = null;
  }
});

async function loadPostForEdit(postId: string) {
  const post = await ContentService.post(postId);
  if (!post) throw new Error("Post not found");
  state.value = {
    title: post.title || "",
    markdown: ContentService.textFromBlocks(post.blocks || []) || post.text || "",
    tags: post.tags || [],
    allowComments: post.allowComments !== false,
    attachments: (post.blocks || []).filter((block) => block.type !== "TEXT").map((block) => ({
      id: block.id || crypto.randomUUID(),
      blobId: typeof block.data.blobId === "string" ? block.data.blobId : undefined,
      url: ContentService.mediaSource(block),
      name: typeof block.data.fileName === "string" ? block.data.fileName : "media",
      mimeType: typeof block.data.mimeType === "string" ? block.data.mimeType : "application/octet-stream",
      size: typeof block.data.size === "number" ? block.data.size : 0,
      type: block.type === "IMAGE" || block.type === "VIDEO" || block.type === "AUDIO" || block.type === "FILE" ? block.type : "FILE",
    })),
  };
}

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
      <a v-if="activeOwner" class="active-owner-pill" :href="activeOwnerPath" :title="activeOwnerName">
        <span>
          <img v-if="activeOwner.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
          <i v-else-if="activeOwner.ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
          <strong v-else>{{ activeOwnerInitial }}</strong>
        </span>
        <strong>{{ activeOwnerName }}</strong>
        <small>@{{ activeOwner.username }}</small>
      </a>
      <label class="comments-toggle" title="Comments">
        <i class="pi pi-comments"></i>
        <input v-model="state.allowComments" type="checkbox" />
      </label>
      <button type="button" class="publish-button" :disabled="isPublishing" @click="publish">
        <i class="pi pi-send"></i>
        <span>{{ isPublishing ? (isEditing ? "Saving" : "Publishing") : (isEditing ? "Save" : "Publish") }}</span>
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
  grid-template-columns: auto minmax(0, 1fr) auto auto;
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

.active-owner-pill {
  min-width: 0;
  justify-self: start;
  height: 42px;
  max-width: min(360px, 45vw);
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 5px 12px 5px 5px;
  border-radius: 999px;
  background: #ffffff;
  color: #111827;
  text-decoration: none;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.08);
}

.active-owner-pill span {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  background: #f1f5f9;
  display: grid;
  place-items: center;
  overflow: hidden;
}

.active-owner-pill img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.active-owner-pill small {
  grid-column: 2;
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.active-owner-pill > strong {
  grid-column: 2;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

  .active-owner-pill {
    order: 3;
    grid-column: 1 / -1;
    max-width: 100%;
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
