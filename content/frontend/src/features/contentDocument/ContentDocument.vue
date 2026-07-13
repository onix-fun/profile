<script setup lang="ts">
import { computed } from "vue";
import { ContentService } from "@/api/contentService";
import type { ContentBlock } from "@/api/types";
import { blockMediaRef, isMediaPreviewBlock, mediaReferences } from "@/features/contentDocument/contentModel";
import { renderInlineMarkdown } from "@/features/display/markdown";

const props = withDefaults(defineProps<{
  markdown: string;
  blocks?: ContentBlock[];
  mode?: "post" | "comment" | "card";
}>(), {
  blocks: () => [],
  mode: "post",
});

type Item =
  | { type: "heading"; level: number; text: string }
  | { type: "paragraph"; html: string }
  | { type: "quote"; html: string }
  | { type: "list"; items: string[] }
  | { type: "media"; ref: string; label: string };

const blockByRef = computed(() => new Map(props.blocks.map((block) => [blockMediaRef(block), block])));
const textWithoutMediaOnlyLines = computed(() => props.markdown || "");

const items = computed<Item[]>(() => {
  const parsed = parseMarkdownDocument(textWithoutMediaOnlyLines.value);
  const usedRefs = new Set(mediaReferences(textWithoutMediaOnlyLines.value).map((ref) => ref.id));
  props.blocks.forEach((block) => {
    const ref = blockMediaRef(block);
    if (block.type !== "TEXT" && ref && !usedRefs.has(ref)) {
      parsed.push({ type: "media", ref, label: fileName(block, ref) });
    }
  });
  return parsed;
});

function parseMarkdownDocument(markdown: string): Item[] {
  const result: Item[] = [];
  const chunks = markdown.split(/\n{2,}/).map((item) => item.trim()).filter(Boolean);
  chunks.forEach((chunk) => {
    const media = chunk.match(/^!\[\[media:([^|\]]+)(?:\|([^\]]+))?\]\]$/);
    if (media) {
      result.push({ type: "media", ref: media[1], label: media[2] || media[1] });
      return;
    }
    const oldMedia = chunk.match(/^\[([^\]]+)\]\(media:([^)]+)\)$/);
    if (oldMedia) {
      result.push({ type: "media", ref: oldMedia[2], label: oldMedia[1] });
      return;
    }

    const heading = chunk.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      result.push({ type: "heading", level: heading[1].length, text: heading[2] });
      return;
    }

    const lines = chunk.split("\n");
    if (lines.every((line) => /^-\s+/.test(line))) {
      result.push({ type: "list", items: lines.map((line) => line.replace(/^-\s+/, "")) });
      return;
    }

    if (lines.every((line) => /^>\s?/.test(line))) {
      result.push({ type: "quote", html: renderInlineMarkdown(lines.map((line) => line.replace(/^>\s?/, "")).join("\n")) });
      return;
    }

    let html = renderInlineMarkdown(chunk);
    mediaReferences(chunk).forEach((ref) => {
      const block = blockByRef.value.get(ref.id);
      const replacement = block && isMediaPreviewBlock(block)
        ? ""
        : `<span class="file-link-inline">${ref.label}</span>`;
      html = html.replace(ref.raw, replacement);
    });
    if (html.trim()) result.push({ type: "paragraph", html });
  });
  return result;
}

function mediaSource(block: ContentBlock): string {
  return ContentService.mediaSource(block);
}

function fileName(block: ContentBlock, fallback: string): string {
  const value = block.data.fileName;
  return typeof value === "string" && value.trim() ? value : fallback;
}
</script>

<template>
  <article class="content-document" :class="`content-document--${mode}`">
    <template v-for="(item, index) in items" :key="`${item.type}-${index}`">
      <component :is="`h${item.level}`" v-if="item.type === 'heading'" v-html="renderInlineMarkdown(item.text)" />
      <blockquote v-else-if="item.type === 'quote'" v-html="item.html" />
      <ul v-else-if="item.type === 'list'">
        <li v-for="listItem in item.items" :key="listItem" v-html="renderInlineMarkdown(listItem)" />
      </ul>
      <section v-else-if="item.type === 'media'" class="content-document__media">
        <template v-if="blockByRef.get(item.ref)">
          <img v-if="blockByRef.get(item.ref)?.type === 'IMAGE' && mediaSource(blockByRef.get(item.ref)!)" :src="mediaSource(blockByRef.get(item.ref)!)" alt="" />
          <video v-else-if="blockByRef.get(item.ref)?.type === 'VIDEO' && mediaSource(blockByRef.get(item.ref)!)" :src="mediaSource(blockByRef.get(item.ref)!)" controls playsinline />
          <audio v-else-if="blockByRef.get(item.ref)?.type === 'AUDIO' && mediaSource(blockByRef.get(item.ref)!)" :src="mediaSource(blockByRef.get(item.ref)!)" controls />
          <a v-else class="content-document__file" :href="mediaSource(blockByRef.get(item.ref)!) || undefined" target="_blank" rel="noreferrer">
            <i class="pi pi-file"></i>
            <span>{{ fileName(blockByRef.get(item.ref)!, item.label) }}</span>
          </a>
        </template>
      </section>
      <p v-else-if="item.type === 'paragraph'" v-html="item.html" />
    </template>
  </article>
</template>

<style scoped>
.content-document {
  width: 100%;
  color: #111827;
  font-size: clamp(17px, 1.5vw, 21px);
  line-height: 1.65;
}

.content-document--comment {
  font-size: 14px;
  line-height: 1.48;
}

.content-document h1,
.content-document h2,
.content-document h3 {
  margin: 0 0 0.52em;
  letter-spacing: 0;
  line-height: 1.12;
}

.content-document h1 {
  font-size: clamp(34px, 6vw, 64px);
}

.content-document h2 {
  font-size: clamp(25px, 3vw, 38px);
}

.content-document h3 {
  font-size: clamp(20px, 2.3vw, 28px);
}

.content-document--comment h1 {
  font-size: 20px;
}

.content-document--comment h2 {
  font-size: 17px;
}

.content-document p,
.content-document blockquote,
.content-document ul {
  margin: 0 0 1.05em;
}

.content-document blockquote {
  border-left: 3px solid #14b8a6;
  padding-left: 16px;
  color: #475569;
}

.content-document img,
.content-document video {
  width: min(100%, 820px);
  max-height: 72dvh;
  display: block;
  border-radius: 8px;
  object-fit: contain;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.16);
}

.content-document--comment img,
.content-document--comment video {
  width: min(100%, 280px);
  max-height: 240px;
}

.content-document__media {
  margin: 0 0 1.25em;
}

.content-document__file {
  max-width: min(520px, 100%);
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  border-radius: 999px;
  padding: 0 16px;
  background: #dbeafe;
  color: #1d4ed8;
  text-decoration: none;
  font-weight: 900;
}

.content-document__file span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-document :deep(a),
.content-document :deep(.tag-inline),
.content-document :deep(.mention-inline) {
  color: #0f766e;
  font-weight: 850;
}

.content-document :deep(code) {
  border-radius: 5px;
  padding: 2px 5px;
  background: #e2e8f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
}

.content-document :deep(.file-link-inline) {
  border-radius: 999px;
  padding: 3px 9px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 900;
}
</style>
