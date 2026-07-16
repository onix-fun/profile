<script setup lang="ts">
import { computed } from "vue";
import type { CommentDocumentV1, CommentInlineMark, PostAsset } from "@/api/types";
import { plainCommentDocument } from "@/features/comments/commentDocument";

const props = withDefaults(defineProps<{ document?: CommentDocumentV1 | null; text?: string; attachments?: PostAsset[] }>(), {
  document: null,
  text: "",
  attachments: () => [],
});
const normalized = computed(() => props.document?.version === 1 ? props.document : plainCommentDocument(props.text));
const media = computed(() => new Map(props.attachments.map((asset) => [asset.assetId || asset.id, asset])));

function safeHref(marks: CommentInlineMark[] = []): string | null {
  const href = marks.find((mark) => mark.type === "LINK")?.href;
  if (!href) return null;
  try { const parsed = new URL(href); return parsed.protocol === "https:" ? parsed.toString() : null; } catch { return null; }
}
function classes(marks: CommentInlineMark[] = []) {
  return {
    bold: marks.some((mark) => mark.type === "BOLD"), italic: marks.some((mark) => mark.type === "ITALIC"),
    strike: marks.some((mark) => mark.type === "STRIKE"), code: marks.some((mark) => mark.type === "INLINE_CODE"),
    mention: marks.some((mark) => mark.type === "MENTION"),
  };
}
function source(asset?: PostAsset) { return asset?.url || asset?.previewUrl || asset?.variants?.[0]?.url || ""; }
</script>

<template>
  <div class="comment-document">
    <template v-for="block in normalized.blocks" :key="block.id">
      <hr v-if="block.type === 'DIVIDER'" />
      <figure v-else-if="block.type === 'MEDIA' && block.assetId && media.get(block.assetId)" class="comment-document__media">
        <video v-if="media.get(block.assetId)?.kind === 'VIDEO'" :src="source(media.get(block.assetId))" controls playsinline preload="metadata" />
        <img v-else :src="source(media.get(block.assetId))" alt="" loading="lazy" />
      </figure>
      <component :is="block.type === 'HEADING' ? `h${block.level || 2}` : block.type === 'QUOTE' ? 'blockquote' : block.type === 'CODE' ? 'pre' : 'p'" v-else-if="!['BULLET_LIST','ORDERED_LIST','CHECKLIST'].includes(block.type)">
        <template v-for="(node, index) in block.content || []" :key="index">
          <a v-if="safeHref(node.marks)" :href="safeHref(node.marks) || undefined" target="_blank" rel="noopener noreferrer" :class="classes(node.marks)">{{ node.text }}</a>
          <span v-else :class="classes(node.marks)">{{ node.text }}</span>
        </template>
      </component>
      <component :is="block.type === 'ORDERED_LIST' ? 'ol' : 'ul'" v-else>
        <li v-for="(item, index) in block.items || []" :key="index">{{ block.type === 'CHECKLIST' ? '□ ' : '' }}{{ item }}</li>
      </component>
    </template>
  </div>
</template>

<style scoped>
.comment-document{display:grid;gap:7px;color:#333a44;font:600 14px/1.48 "Nunito",sans-serif;overflow-wrap:anywhere}.comment-document p,.comment-document h2,.comment-document h3,.comment-document blockquote,.comment-document pre,.comment-document ul,.comment-document ol{margin:0}.comment-document h2{font-size:19px}.comment-document h3{font-size:16px}.comment-document blockquote{padding:8px 12px;border-left:3px solid #8e99a7;background:#f2f4f6}.comment-document pre{overflow:auto;padding:10px;border-radius:10px;background:#252b35;color:#fff;white-space:pre-wrap}.comment-document hr{width:100%;border:0;border-top:1px solid #d8dde3}.comment-document ul,.comment-document ol{padding-left:22px}.comment-document .bold{font-weight:900}.comment-document .italic{font-style:italic}.comment-document .strike{text-decoration:line-through}.comment-document .code{padding:1px 4px;border-radius:4px;background:#eceff2;font-family:monospace}.comment-document .mention{color:#315fd6;font-weight:900}.comment-document a{color:#315fd6}.comment-document__media{margin:2px 0;max-width:420px}.comment-document__media img,.comment-document__media video{display:block;max-width:100%;max-height:360px;object-fit:contain;border-radius:12px;background:#eef0f2}
</style>
