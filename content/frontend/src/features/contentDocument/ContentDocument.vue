<script setup lang="ts">
import { computed, ref } from "vue";
import { ContentService } from "@/api/contentService";
import type { ContentBlock, PollVoteState } from "@/api/types";
import { blockMediaRef, isMediaPreviewBlock, mediaReferences, stripCreatorDirectives } from "@/features/contentDocument/contentModel";
import { renderInlineMarkdown } from "@/features/display/markdown";

const props = withDefaults(defineProps<{
  markdown: string;
  blocks?: ContentBlock[];
  mode?: "post" | "comment" | "card";
  postId?: string;
  canManagePolls?: boolean;
}>(), {
  blocks: () => [],
  mode: "post",
  postId: "",
  canManagePolls: false,
});

type Item =
  | { type: "heading"; level: number; text: string }
  | { type: "paragraph"; html: string }
  | { type: "quote"; html: string }
  | { type: "list"; items: string[] }
  | { type: "media"; ref: string }
  | { type: "special"; block: ContentBlock };

const pollStates = ref<Record<string, PollVoteState>>({});
const blockByRef = computed(() => new Map(props.blocks.map((block) => [blockMediaRef(block), block])));
const textWithoutMediaOnlyLines = computed(() => stripCreatorDirectives(props.markdown || ""));

const items = computed<Item[]>(() => {
  const parsed = parseMarkdownDocument(textWithoutMediaOnlyLines.value);
  const usedRefs = new Set(mediaReferences(textWithoutMediaOnlyLines.value).map((reference) => reference.id));
  props.blocks.forEach((block) => {
    const ref = blockMediaRef(block);
    if (["IMAGE", "VIDEO", "AUDIO", "FILE"].includes(block.type) && ref && !usedRefs.has(ref)) parsed.push({ type: "media", ref });
    if (!["TEXT", "IMAGE", "VIDEO", "AUDIO", "FILE"].includes(block.type)) parsed.push({ type: "special", block });
  });
  return parsed;
});

function parseMarkdownDocument(markdown: string): Item[] {
  const result: Item[] = [];
  const chunks = markdown.split(/\n{2,}/).map((item) => item.trim()).filter(Boolean);
  chunks.forEach((chunk) => {
    const media = chunk.match(/^!\[\[media:([^|\]]+)(?:\|([^\]]+))?\]\]$/);
    if (media) {
      result.push({ type: "media", ref: media[1] });
      return;
    }
    const oldMedia = chunk.match(/^\[([^\]]+)\]\(media:([^)]+)\)$/);
    if (oldMedia) {
      result.push({ type: "media", ref: oldMedia[2] });
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
    mediaReferences(chunk).forEach((reference) => {
      const block = blockByRef.value.get(reference.id);
      // A media directive owns its representation; its automatically generated file label must not leak into the post.
      if (block && (isMediaPreviewBlock(block) || block.type === "FILE")) html = html.replace(reference.raw, "");
    });
    if (html.trim()) result.push({ type: "paragraph", html });
  });
  return result;
}

function mediaSource(block: ContentBlock): string {
  return ContentService.mediaSource(block);
}

function optionEntries(block: ContentBlock): Array<{ id: string; label: string }> {
  const values = Array.isArray(block.data.options) ? block.data.options : [];
  return values.map((value, index) => typeof value === "string"
    ? { id: value, label: value }
    : { id: String((value as Record<string, unknown>).id || index), label: String((value as Record<string, unknown>).label || (value as Record<string, unknown>).text || `Вариант ${index + 1}`) });
}

function gallerySources(block: ContentBlock): string[] {
  const values = Array.isArray(block.data.items) ? block.data.items : Array.isArray(block.data.images) ? block.data.images : [];
  return values.map((value) => typeof value === "string" ? value : typeof value === "object" && value ? String((value as Record<string, unknown>).url || (value as Record<string, unknown>).src || "") : "").filter(Boolean);
}

function specialText(block: ContentBlock, fallback = ""): string {
  for (const key of ["text", "content", "quote", "message", "caption", "title", "code"]) {
    const value = block.data[key];
    if (typeof value === "string" && value.trim()) return value;
  }
  return fallback;
}

function specialUrl(block: ContentBlock): string {
  const value = block.data.url || block.data.href || block.data.embedUrl;
  return typeof value === "string" ? value : "";
}

function pollTotal(block: ContentBlock): number {
  const state = pollState(block);
  return state ? Object.values(state.counts).reduce((total, count) => total + count, 0) : 0;
}

function checklistEntries(block: ContentBlock): Array<{ text: string; checked: boolean }> {
  const values = Array.isArray(block.data.items) ? block.data.items : [];
  return values.map((entry) => {
    if (typeof entry === "string") return { text: entry, checked: false };
    const record = entry && typeof entry === "object" ? entry as Record<string, unknown> : {};
    return { text: String(record.text || record.label || ""), checked: Boolean(record.checked) };
  }).filter((entry) => entry.text);
}

function pollState(block: ContentBlock): PollVoteState | undefined {
  return pollStates.value[block.id || ""];
}

async function votePoll(block: ContentBlock, optionId: string) {
  if (!props.postId || !block.id) return;
  pollStates.value = { ...pollStates.value, [block.id]: await ContentService.votePoll(props.postId, block.id, optionId) };
}

async function closePoll(block: ContentBlock) {
  if (!props.postId || !block.id) return;
  pollStates.value = { ...pollStates.value, [block.id]: await ContentService.closePoll(props.postId, block.id) };
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
          <a v-else class="content-document__file" :href="mediaSource(blockByRef.get(item.ref)!) || undefined" target="_blank" rel="noreferrer" aria-label="Открыть файл" title="Открыть файл"><i class="pi pi-file" /></a>
        </template>
      </section>
      <section v-else-if="item.type === 'special'" class="content-document__special" :class="`content-document__special--${item.block.type.toLowerCase()}`">
        <template v-if="item.block.type === 'GALLERY'">
          <img v-for="(src, imageIndex) in gallerySources(item.block)" :key="`${src}-${imageIndex}`" :src="src" alt="" />
        </template>
        <a v-else-if="item.block.type === 'LINK_CARD'" class="content-document__link-card" :href="specialUrl(item.block)" target="_blank" rel="noreferrer">
          <small>{{ item.block.data.domain || 'Ссылка' }}</small>
          <strong>{{ item.block.data.title || specialUrl(item.block) }}</strong>
          <span v-if="item.block.data.description || specialText(item.block)">{{ item.block.data.description || specialText(item.block) }}</span>
        </a>
        <blockquote v-else-if="item.block.type === 'QUOTE'" v-html="renderInlineMarkdown(specialText(item.block))" />
        <div v-else-if="item.block.type === 'CALLOUT'" class="content-document__callout"><i :class="String(item.block.data.icon || 'pi pi-sparkles')" /><span v-html="renderInlineMarkdown(specialText(item.block))" /></div>
        <div v-else-if="item.block.type === 'DIVIDER'" class="content-document__divider" aria-hidden="true" />
        <pre v-else-if="item.block.type === 'CODE'"><code>{{ specialText(item.block) }}</code></pre>
        <ul v-else-if="item.block.type === 'CHECKLIST'" class="content-document__checklist">
          <li v-for="(entry, entryIndex) in checklistEntries(item.block)" :key="entryIndex"><i class="pi" :class="entry.checked ? 'pi-check-square' : 'pi-stop'" />{{ entry.text }}</li>
        </ul>
        <div v-else-if="item.block.type === 'POLL'" class="content-document__poll">
          <strong>{{ item.block.data.question || specialText(item.block, 'Опрос') }}</strong>
          <button v-for="option in optionEntries(item.block)" :key="option.id" type="button" :disabled="Boolean(item.block.data.closed) || Boolean(pollState(item.block)?.closed)" :class="{ 'is-selected': pollState(item.block)?.optionId === option.id }" @click="votePoll(item.block, option.id)"><span>{{ option.label }}</span><small>{{ pollState(item.block)?.counts[option.id] || 0 }}</small></button>
          <footer>{{ pollTotal(item.block) }} голосов<button v-if="canManagePolls && !item.block.data.closed && !pollState(item.block)?.closed" type="button" @click="closePoll(item.block)">Закрыть</button></footer>
        </div>
        <iframe v-else-if="item.block.type === 'TRUSTED_EMBED' && specialUrl(item.block)" class="content-document__embed" :src="specialUrl(item.block)" :title="String(item.block.data.title || 'Встроенный материал')" sandbox="allow-scripts allow-same-origin allow-popups allow-forms" referrerpolicy="strict-origin-when-cross-origin" loading="lazy" />
        <p v-else>{{ specialText(item.block) }}</p>
      </section>
      <p v-else-if="item.type === 'paragraph'" v-html="item.html" />
    </template>
  </article>
</template>

<style scoped>
.content-document {
  --document-ink: #17264b;
  --document-blue: #2855ff;
  --document-pink: #ff4fa3;
  --document-lime: #b8f348;
  --document-orange: #ff9f36;
  width: 100%;
  color: var(--document-ink);
  font-family: "Nunito", "Avenir Next", "Roboto", sans-serif;
  font-size: clamp(17px, 1.5vw, 21px);
  line-height: 1.62;
}

.content-document--comment { font-size: 14px; line-height: 1.48; }
.content-document h1,
.content-document h2,
.content-document h3 { margin: 0 0 .55em; color: var(--document-ink); line-height: 1.11; letter-spacing: -.035em; }
.content-document h1 { font-size: clamp(34px, 6vw, 64px); }
.content-document h2 { font-size: clamp(25px, 3vw, 38px); }
.content-document h3 { font-size: clamp(20px, 2.3vw, 28px); }
.content-document--comment h1 { font-size: 20px; }
.content-document--comment h2 { font-size: 17px; }
.content-document p,
.content-document blockquote,
.content-document ul { margin: 0 0 1.05em; }
.content-document blockquote { margin-inline: 0; padding: 14px 18px; background: #fff1b6; color: #4d2a59; box-shadow: 0 10px 20px rgba(255, 159, 54, .16); }
.content-document__media { margin: 0 0 1.28em; }
.content-document img,
.content-document video { width: min(100%, 820px); max-height: 72dvh; display: block; background: #edf2ff; object-fit: contain; box-shadow: 0 16px 30px rgba(40, 85, 255, .14); }
.content-document audio { width: min(100%, 640px); display: block; }
.content-document--comment img,
.content-document--comment video { width: min(100%, 280px); max-height: 240px; }
.content-document__file { width: 46px; height: 46px; display: grid; place-items: center; background: var(--document-lime); color: var(--document-ink); box-shadow: 0 9px 17px rgba(184, 243, 72, .25); text-decoration: none; }
.content-document :deep(a),
.content-document :deep(.tag-inline),
.content-document :deep(.mention-inline) { color: var(--document-blue); font-weight: 850; }
.content-document :deep(code) { padding: 2px 5px; background: #e7eeff; color: #162750; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace; }
.content-document :deep(.file-link-inline) { display: none; }
.content-document__special { margin: 0 0 1.28em; }
.content-document__special--gallery { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.content-document__special--gallery img { width: 100%; max-height: none; object-fit: contain; }
.content-document__link-card { display: grid; gap: 4px; padding: 20px; background: var(--document-blue); color: #fff; box-shadow: 0 14px 26px rgba(40, 85, 255, .2); text-decoration: none; }
.content-document__link-card small { color: #dce5ff; }
.content-document__link-card strong { overflow-wrap: anywhere; }
.content-document__callout { display: flex; gap: 12px; padding: 18px 20px; background: #a4f0d7; color: #153e58; }
.content-document__callout i { padding-top: 5px; }
.content-document__divider { width: min(180px, 46%); height: 7px; background: var(--document-pink); }
.content-document pre { margin: 0; overflow: auto; padding: 18px; background: #1c2860; color: #f5f1ff; }
.content-document pre code { padding: 0; background: transparent; color: inherit; }
.content-document__checklist { display: grid; gap: 10px; padding: 18px 22px; background: var(--document-orange); list-style: none; }
.content-document__checklist li { display: flex; gap: 9px; align-items: center; }
.content-document__poll { display: grid; gap: 9px; padding: 20px; background: #ff8d9f; color: #48294f; }
.content-document__poll > button { display: flex; justify-content: space-between; gap: 16px; padding: 11px 14px; border: 0; border-radius: 0; background: #fff; color: #432b70; font: inherit; text-align: left; cursor: pointer; }
.content-document__poll > button.is-selected { background: #a4f0d7; }
.content-document__poll footer { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.content-document__poll footer button { border: 0; border-radius: 999px; padding: 7px 11px; background: #573b96; color: #fff; font: inherit; cursor: pointer; }
.content-document__embed { width: 100%; min-height: 320px; border: 0; background: #dce5ff; box-shadow: 0 14px 26px rgba(40, 85, 255, .16); }
@media (max-width: 640px) { .content-document__embed { min-height: 230px; } }
</style>
