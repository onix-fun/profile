<script setup lang="ts">
import { computed } from "vue";
import { ContentService } from "@/shared/api/contentService";
import type { ContentBlock, ProfileContentPost } from "@/shared/api/types";
import { postSnippet, stripMediaReferences } from "@/features/display/lib/displayText";
import { renderInlineMarkdown } from "@/features/display/lib/markdown";
import OnixIcon from "@/shared/ui/OnixIcon.vue";

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
  bookmark: [];
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
        <OnixIcon v-else :name="mediaType === 'VIDEO' ? 'video' : 'image'" :size="22" />
      </span>

      <span v-else-if="fileBlock" class="post-cloud__file">
        <OnixIcon :name="fileBlock.type === 'AUDIO' ? 'audio' : 'file'" :size="20" />
        <span>{{ fileName }}</span>
      </span>

      <span v-else class="post-cloud__text">
        <span v-if="mode !== 'profile'" class="post-cloud__author">
          <img v-if="authorAvatar" :src="authorAvatar" alt="" />
          <OnixIcon v-else name="user" :size="18" />
          <small>@{{ authorName }}</small>
        </span>
        <strong>{{ title }}</strong>
        <span v-if="mode !== 'profile'" v-html="renderedText"></span>
      </span>
    </button>

    <span v-if="mode !== 'full'" class="post-cloud__actions" :class="{ disabled: disabledActions }">
      <button type="button" title="Like" :disabled="disabledActions" @click.stop="emit('like')">
        <OnixIcon :name="post.likedByViewer ? 'heart-filled' : 'heart'" :size="18" />
        <span>{{ post.likeCount || 0 }}</span>
      </button>
      <button type="button" title="Comments" :disabled="disabledActions" @click.stop="emit('comments')">
        <OnixIcon name="message" :size="18" />
      </button>
      <button type="button" title="Bookmark" :disabled="disabledActions" @click.stop="emit('bookmark')">
        <OnixIcon name="bookmark" :size="18" />
      </button>
    </span>

    <span v-if="mode === 'feed' && (reasons.length || post.tags.length)" class="post-cloud__meta">
      <small>@{{ authorName }}</small>
      <small v-for="reason in reasons.slice(0, 1)" :key="reason">{{ reason }}</small>
      <small v-for="tag in post.tags.slice(0, 2)" :key="tag">#{{ tag }}</small>
    </span>
  </article>
</template>

<style scoped>
.post-cloud {
  position: relative;
  height: 100%;
  min-width: 0;
  color: var(--onix-color-text);
}

.post-cloud__body,
.post-cloud__actions button {
  
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
  background: var(--onix-color-surface-floating);
  
  transition: transform 150ms ease, box-shadow 150ms ease;
}

.post-cloud:hover .post-cloud__media,
.post-cloud:hover .post-cloud__text,
.post-cloud:hover .post-cloud__file {
  transform: translateY(-3px);
  
}

.post-cloud__media {
  aspect-ratio: 1;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--onix-color-text);
  color: var(--onix-tone-on-solid);
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
  color: var(--onix-color-text-muted);
  font-size: var(--onix-font-size-caption);
  font-weight: 900;
}

.post-cloud__author img,
.post-cloud__author i {
  width: 18px;
  height: 18px;
  border-radius: var(--onix-radius-pill);
  display: grid;
  place-items: center;
  object-fit: cover;
  background: var(--onix-color-surface-active);
  font-size: var(--onix-font-size-caption);
}

.post-cloud__author small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud__text strong {
  overflow: hidden;
  color: var(--onix-color-text);
  font-size: 18px;
  line-height: 1.12;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud__text span {
  display: -webkit-box;
  overflow: hidden;
  color: var(--onix-color-text-muted);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.38;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.post-cloud__text :deep(a) {
  color: var(--onix-color-tone-success-ink);
  font-weight: 900;
}

.post-cloud__text :deep(.tag-inline) {
  color: var(--onix-color-tone-success-ink);
  font-weight: 900;
}

.post-cloud__text :deep(.file-link-inline) {
  display: inline-flex;
  border-radius: var(--onix-radius-pill);
  padding: 2px 8px;
  background: var(--onix-color-tone-info-soft);
  color: var(--onix-color-tone-info-ink);
  font-weight: 900;
}

.post-cloud__file {
  min-height: 76px;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
  border-radius: var(--onix-radius-pill);
  background: var(--onix-color-tone-info-soft);
  color: var(--onix-color-tone-info-ink);
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
  border-radius: var(--onix-radius-pill);
  padding: 6px;
  background: var(--onix-color-text);
  
}

.post-cloud__actions button {
  min-width: var(--onix-control-md);
  height: var(--onix-control-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: var(--onix-radius-pill);
  background: transparent;
  color: var(--onix-color-surface-active);
  font-size: 12px;
  font-weight: 900;
}

.post-cloud__actions button:hover:not(:disabled),
.post-cloud__actions button:focus-visible {
  background: var(--onix-color-surface-floating);
  color: var(--onix-tone-on-solid);
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
  color: var(--onix-color-text-muted);
  font-size: var(--onix-font-size-caption);
  font-weight: 900;
}

.post-cloud__meta small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.post-cloud--feed {
  padding-bottom: 0;
}

.post-cloud--feed .post-cloud__body {
  min-height: calc(100% - 42px);
}

.post-cloud--feed .post-cloud__media,
.post-cloud--feed .post-cloud__text,
.post-cloud--feed .post-cloud__file {
  max-height: calc(100% - 42px);
}

.post-cloud--feed .post-cloud__actions {
  right: 10px;
  bottom: 8px;
}

.post-cloud--feed .post-cloud__meta {
  left: 14px;
  top: auto;
  bottom: 50px;
  max-width: calc(100% - 28px);
}

.post-cloud--profile .post-cloud__text {
  height: 100%;
  min-height: 0;
  padding: 14px;
  border-radius: 22px;
  place-items: center;
  text-align: center;
}

.post-cloud--profile .post-cloud__text strong {
  display: -webkit-box;
  color: var(--onix-color-text);
  font-size: 13px;
  line-height: 1.18;
  white-space: normal;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.post-cloud--profile .post-cloud__text span {
  font-size: 12px;
  -webkit-line-clamp: 3;
}

.post-cloud--profile .post-cloud__media {
  border-radius: 22px;
}

.post-cloud--profile.post-cloud--video .post-cloud__media {
  border-radius: 22px;
  transform: none;
}

.post-cloud--profile.post-cloud--video .post-cloud__media video,
.post-cloud--profile.post-cloud--video .post-cloud__media i {
  transform: none;
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
