<script setup lang="ts">
import { computed } from "vue";
import { ContentService } from "@/api/contentService";
import type { ContentBlock, ProfileContentPost } from "@/api/types";
import { postSnippet, stripMediaReferences } from "@/features/display/displayText";
import { renderInlineMarkdown } from "@/features/display/markdown";

const props = withDefaults(defineProps<{
  post: ProfileContentPost;
  reasons?: string[];
  mode?: "feed" | "profile" | "full";
  disabledActions?: boolean;
}>(), {
  reasons: () => [],
  mode: "feed",
  disabledActions: false,
});

const emit = defineEmits<{
  open: [];
  like: [];
  comments: [];
}>();

const blocks = computed(() => props.post.blocks || []);
function mimeType(block: ContentBlock): string {
  const value = block.data.mimeType;
  return typeof value === "string" ? value : "";
}

function visualMediaType(block: ContentBlock): "IMAGE" | "VIDEO" | null {
  if (block.type === "IMAGE" || mimeType(block).startsWith("image/")) return "IMAGE";
  if (block.type === "VIDEO" || mimeType(block).startsWith("video/")) return "VIDEO";
  return null;
}

const mediaBlock = computed(() => blocks.value.find((block) => visualMediaType(block)));
const mediaType = computed(() => mediaBlock.value ? visualMediaType(mediaBlock.value) : null);
const fileBlock = computed(() => blocks.value.find((block) => !visualMediaType(block) && (block.type === "FILE" || block.type === "AUDIO")));
const textValue = computed(() => stripMediaReferences(props.post.text || ContentService.textFromBlocks(blocks.value) || ""));
const title = computed(() => postSnippet(props.post, mediaBlock.value || fileBlock.value ? "Media post" : "Post"));
const cloudType = computed<ContentBlock["type"] | "TEXT">(() => mediaType.value || fileBlock.value?.type || "TEXT");
const source = computed(() => mediaBlock.value ? ContentService.mediaSource(mediaBlock.value) : "");
const fileName = computed(() => {
  const value = fileBlock.value?.data.fileName;
  return typeof value === "string" && value.trim() ? value : title.value;
});
const renderedText = computed(() => renderInlineMarkdown(textValue.value || title.value));
const authorName = computed(() => props.post.author?.username || props.post.authorName || "User");
const authorAvatar = computed(() => props.post.author?.avatarUrl || "");
</script>

<template>
  <article
    class="post-cloud"
    :class="[`post-cloud--${cloudType.toLowerCase()}`, `post-cloud--${mode}`]"
  >
    <button type="button" class="post-cloud__body" @click="emit('open')">
      <span v-if="mediaBlock" class="post-cloud__media">
        <img v-if="mediaType === 'IMAGE' && source" :src="source" alt="" />
        <video v-else-if="mediaType === 'VIDEO' && source" :src="source" muted playsinline autoplay loop preload="metadata" />
        <i v-else :class="mediaType === 'VIDEO' ? 'pi pi-video' : 'pi pi-image'"></i>
      </span>

      <span v-else-if="fileBlock" class="post-cloud__file">
        <i :class="fileBlock.type === 'AUDIO' ? 'pi pi-volume-up' : 'pi pi-file'"></i>
        <span>{{ fileName }}</span>
      </span>

      <span v-else class="post-cloud__text">
        <span class="post-cloud__author">
          <img v-if="authorAvatar" :src="authorAvatar" alt="" />
          <i v-else class="pi pi-user"></i>
          <small>@{{ authorName }}</small>
        </span>
        <strong>{{ title }}</strong>
        <span v-html="renderedText"></span>
      </span>
    </button>

    <span v-if="mode !== 'full'" class="post-cloud__actions" :class="{ disabled: disabledActions }">
      <button type="button" title="Like" :disabled="disabledActions" @click.stop="emit('like')">
        <i :class="post.likedByViewer ? 'pi pi-heart-fill' : 'pi pi-heart'"></i>
        <span>{{ post.likeCount || 0 }}</span>
      </button>
      <button type="button" title="Comments" :disabled="disabledActions" @click.stop="emit('comments')">
        <i class="pi pi-comments"></i>
      </button>
      <button type="button" title="Bookmark" :disabled="disabledActions">
        <i class="pi pi-bookmark"></i>
      </button>
    </span>

    <span v-if="reasons.length || post.tags.length" class="post-cloud__meta">
      <small>@{{ authorName }}</small>
      <small v-for="reason in reasons.slice(0, 1)" :key="reason">{{ reason }}</small>
      <small v-for="tag in post.tags.slice(0, mode === 'profile' ? 1 : 2)" :key="tag">#{{ tag }}</small>
    </span>
  </article>
</template>

<style scoped>
.post-cloud {
  position: relative;
  min-width: 0;
  color: #111827;
}

.post-cloud__body,
.post-cloud__actions button {
  border: 0;
  font: inherit;
  cursor: pointer;
}

.post-cloud__body {
  width: 100%;
  min-height: 100%;
  display: grid;
  place-items: stretch;
  padding: 0;
  background: transparent;
  color: inherit;
  text-align: left;
}

.post-cloud__media,
.post-cloud__text,
.post-cloud__file {
  min-width: 0;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 22px 52px rgba(15, 23, 42, 0.16);
  transition: transform 150ms ease, box-shadow 150ms ease;
}

.post-cloud:hover .post-cloud__media,
.post-cloud:hover .post-cloud__text,
.post-cloud:hover .post-cloud__file {
  transform: translateY(-3px);
  box-shadow: 0 30px 72px rgba(15, 23, 42, 0.2);
}

.post-cloud__media {
  aspect-ratio: 1;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #111827;
  color: #ffffff;
}

.post-cloud--video .post-cloud__media {
  border-radius: 32% 44% 32% 44%;
  transform: rotate(4deg);
}

.post-cloud--video .post-cloud__media video,
.post-cloud--video .post-cloud__media i {
  transform: rotate(-4deg);
}

.post-cloud__media img,
.post-cloud__media video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.post-cloud__text {
  min-height: 132px;
  display: grid;
  gap: 7px;
  align-content: center;
  padding: 22px 24px;
  border-radius: 34px 26px 38px 24px;
}

.post-cloud__author {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
}

.post-cloud__author img,
.post-cloud__author i {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  object-fit: cover;
  background: #e2e8f0;
  font-size: 9px;
}

.post-cloud__author small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud__text strong {
  overflow: hidden;
  color: #111827;
  font-size: 18px;
  line-height: 1.12;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud__text span {
  display: -webkit-box;
  overflow: hidden;
  color: #475569;
  font-size: 14px;
  font-weight: 650;
  line-height: 1.38;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.post-cloud__text :deep(a) {
  color: #0f766e;
  font-weight: 900;
}

.post-cloud__text :deep(.tag-inline) {
  color: #047857;
  font-weight: 900;
}

.post-cloud__text :deep(.file-link-inline) {
  display: inline-flex;
  border-radius: 999px;
  padding: 2px 8px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 900;
}

.post-cloud__file {
  min-height: 76px;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
  border-radius: 999px;
  background: #e0f2fe;
  color: #075985;
  font-weight: 900;
}

.post-cloud__file span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud__actions {
  position: absolute;
  z-index: 3;
  right: -24px;
  bottom: -18px;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border-radius: 999px;
  padding: 6px;
  background: #2f303a;
  box-shadow: 0 12px 26px rgba(15, 23, 42, 0.28);
}

.post-cloud__actions button {
  min-width: 34px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 999px;
  background: transparent;
  color: #d1d5db;
  font-size: 12px;
  font-weight: 900;
}

.post-cloud__actions button:hover:not(:disabled),
.post-cloud__actions button:focus-visible {
  background: rgba(255, 255, 255, 0.11);
  color: #ffffff;
}

.post-cloud__actions button:disabled {
  cursor: default;
  opacity: 0.62;
}

.post-cloud__meta {
  position: absolute;
  left: 12px;
  top: calc(100% + 12px);
  display: flex;
  max-width: calc(100% - 24px);
  gap: 6px;
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
}

.post-cloud__meta small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud--profile .post-cloud__text {
  min-height: 102px;
  padding: 16px 18px;
}

.post-cloud--profile .post-cloud__text strong {
  font-size: 15px;
}

.post-cloud--profile .post-cloud__text span {
  font-size: 12px;
  -webkit-line-clamp: 3;
}

.post-cloud--profile .post-cloud__actions {
  right: -18px;
  bottom: -15px;
  padding: 5px;
}

.post-cloud--full .post-cloud__body {
  cursor: default;
}

.post-cloud--full .post-cloud__media {
  width: min(70vmin, 680px);
  max-width: 100%;
}

.post-cloud--full .post-cloud__text {
  width: min(720px, 100%);
  min-height: 220px;
  padding: clamp(28px, 6vw, 58px);
}

.post-cloud--full .post-cloud__text strong {
  font-size: clamp(30px, 5vw, 58px);
}

.post-cloud--full .post-cloud__text span {
  font-size: clamp(17px, 2vw, 22px);
  -webkit-line-clamp: unset;
}

@media (max-width: 720px) {
  .post-cloud__actions {
    right: 4px;
    bottom: -20px;
  }

  .post-cloud__meta {
    display: none;
  }
}
</style>
