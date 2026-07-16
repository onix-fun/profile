<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import { Bold, Code2, Italic, Link, List, Paperclip, Plus, Quote } from "lucide-vue-next";
import { createAttachment, creatorDirective, type ContentAttachment, type CreatorBlockType } from "@/features/contentDocument/contentModel";
import { editorJsonToMarkdown, markdownToEditorHtml } from "@/features/contentDocument/tiptapMarkdown";
import { MediaFile } from "@/features/contentDocument/mediaFileExtension";

const props = withDefaults(defineProps<{
  modelValue: string;
  attachments: ContentAttachment[];
  placeholder?: string;
  compact?: boolean;
  maxAttachments?: number;
}>(), {
  placeholder: "Write...",
  compact: false,
  maxAttachments: Number.POSITIVE_INFINITY,
});

const emit = defineEmits<{
  "update:modelValue": [value: string];
  "update:attachments": [value: ContentAttachment[]];
}>();

const attachmentById = computed(() => new Map(props.attachments.map((attachment) => [attachment.id, attachment])));
const blockMenuOpen = ref(false);
const creatorBlockOptions: Array<{ type: CreatorBlockType; label: string }> = [
  { type: "CALLOUT", label: "Акцент" }, { type: "QUOTE", label: "Цитата" }, { type: "DIVIDER", label: "Разделитель" },
  { type: "CODE", label: "Код" }, { type: "CHECKLIST", label: "Чеклист" }, { type: "POLL", label: "Опрос" },
  { type: "LINK_CARD", label: "Ссылка" }, { type: "TRUSTED_EMBED", label: "Embed" }, { type: "GALLERY", label: "Галерея" },
];

function removeAttachment(id: string) {
  const attachment = attachmentById.value.get(id);
  if (attachment?.url.startsWith("blob:")) URL.revokeObjectURL(attachment.url);
  emit("update:attachments", props.attachments.filter((item) => item.id !== id));
}

const editor = useEditor({
  content: markdownToEditorHtml(props.modelValue),
  extensions: [
    StarterKit.configure({
      heading: { levels: [1, 2, 3] },
    }),
    MediaFile.configure({ onRemove: removeAttachment }),
  ],
  editorProps: {
    attributes: {
      class: "content-editor__surface",
      "data-placeholder": props.placeholder,
    },
  },
  onUpdate: ({ editor: current }) => {
    emit("update:modelValue", editorJsonToMarkdown(current.getJSON()));
  },
});

watch(() => props.modelValue, (value) => {
  if (!editor.value) return;
  const current = editorJsonToMarkdown(editor.value.getJSON());
  if (current === value) return;
  editor.value.commands.setContent(markdownToEditorHtml(value), { emitUpdate: false });
});

function insertFiles(files: FileList | File[]) {
  const available = Math.max(0, props.maxAttachments - props.attachments.length);
  const next = Array.from(files).slice(0, available).map(createAttachment);
  if (!next.length || !editor.value) return;
  emit("update:attachments", [...props.attachments, ...next]);
  next.forEach((attachment) => {
    editor.value?.chain().focus().insertMediaFile({
      id: attachment.id,
      name: attachment.name,
      type: attachment.type,
      url: attachment.url,
      mimeType: attachment.mimeType,
      size: attachment.size,
    }).run();
  });
}

function onFileInput(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files) insertFiles(input.files);
  input.value = "";
}

function setLink() {
  if (!editor.value) return;
  const previous = editor.value.getAttributes("link").href as string | undefined;
  const href = window.prompt("Link URL", previous || "https://");
  if (href === null) return;
  if (!href.trim()) {
    editor.value.chain().focus().unsetLink().run();
    return;
  }
  editor.value.chain().focus().extendMarkRange("link").setLink({ href }).run();
}

function insertCreatorBlock(type: CreatorBlockType) {
  if (!editor.value) return;
  let data: Record<string, unknown> = {};
  if (type === "POLL") {
    const raw = window.prompt("Вопрос и варианты через |", "Что выбрать?|Первый вариант|Второй вариант");
    if (!raw) return;
    const [question, ...labels] = raw.split("|").map((part) => part.trim()).filter(Boolean);
    if (!question || labels.length < 2) return;
    data = { question, options: labels.map((label, index) => ({ id: `option-${index + 1}`, label })) };
  } else if (type === "TRUSTED_EMBED" || type === "LINK_CARD") {
    const url = window.prompt("Ссылка", "https://");
    if (!url?.trim()) return;
    data = { url: url.trim(), title: type === "LINK_CARD" ? "Новая ссылка" : "Встроенный материал" };
  } else if (type === "GALLERY") {
    const raw = window.prompt("Ссылки на изображения через запятую");
    if (!raw?.trim()) return;
    data = { items: raw.split(",").map((item) => item.trim()).filter(Boolean) };
  } else if (type === "CHECKLIST") {
    const raw = window.prompt("Пункты через запятую");
    if (!raw?.trim()) return;
    data = { items: raw.split(",").map((text) => ({ text: text.trim(), checked: false })).filter((item) => item.text) };
  } else if (type !== "DIVIDER") {
    const text = window.prompt("Текст блока");
    if (!text?.trim()) return;
    data = type === "QUOTE" ? { quote: text.trim() } : type === "CODE" ? { code: text } : { text: text.trim() };
  }
  editor.value.chain().focus().insertContent(`\n${creatorDirective(type, data)}\n`).run();
  blockMenuOpen.value = false;
}

onBeforeUnmount(() => {
  editor.value?.destroy();
});
</script>

<template>
  <section class="content-editor" :class="{ compact }">
    <div v-if="editor" class="content-editor__toolbar" aria-label="Formatting toolbar">
      <button type="button" title="Heading 1" :class="{ active: editor.isActive('heading', { level: 1 }) }" @click="editor.chain().focus().toggleHeading({ level: 1 }).run()">H1</button>
      <button type="button" title="Heading 2" :class="{ active: editor.isActive('heading', { level: 2 }) }" @click="editor.chain().focus().toggleHeading({ level: 2 }).run()">H2</button>
      <button type="button" title="Bold" :class="{ active: editor.isActive('bold') }" @click="editor.chain().focus().toggleBold().run()"><Bold :size="17" /></button>
      <button type="button" title="Italic" :class="{ active: editor.isActive('italic') }" @click="editor.chain().focus().toggleItalic().run()"><Italic :size="17" /></button>
      <button type="button" title="Inline code" :class="{ active: editor.isActive('code') }" @click="editor.chain().focus().toggleCode().run()"><Code2 :size="17" /></button>
      <button type="button" title="Bullet list" :class="{ active: editor.isActive('bulletList') }" @click="editor.chain().focus().toggleBulletList().run()"><List :size="17" /></button>
      <button type="button" title="Quote" :class="{ active: editor.isActive('blockquote') }" @click="editor.chain().focus().toggleBlockquote().run()"><Quote :size="17" /></button>
      <button type="button" title="Link" :class="{ active: editor.isActive('link') }" @click="setLink"><Link :size="17" /></button>
      <button v-if="!compact" type="button" title="Вставить блок" aria-label="Вставить блок" @click="blockMenuOpen = !blockMenuOpen"><Plus :size="17" /></button>
      <label title="Attach file">
        <Paperclip :size="17" />
        <input type="file" multiple @change="onFileInput" />
      </label>
      <div v-if="!compact && blockMenuOpen" class="content-editor__blocks" role="menu" aria-label="Специальные блоки">
        <button v-for="option in creatorBlockOptions" :key="option.type" type="button" role="menuitem" @click="insertCreatorBlock(option.type)">
          {{ option.label }}
        </button>
      </div>
    </div>
    <EditorContent :editor="editor" />
  </section>
</template>

<style scoped>
.content-editor {
  display: grid;
  gap: 16px;
}

.content-editor__toolbar {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.content-editor__blocks { position: absolute; z-index: 8; top: 46px; left: 0; display: grid; grid-template-columns: repeat(3, max-content); gap: 6px; padding: 10px; border-radius: 20px 8px 20px 8px; background: #6756e8; }
.content-editor__blocks button { min-width: 0; border: 0; border-radius: 999px; padding: 8px 10px; background: #fff; color: #27305a; box-shadow: none; font-weight: 800; }

.content-editor__toolbar button,
.content-editor__toolbar label {
  min-width: 38px;
  height: 36px;
  border: 3px solid var(--comic-ink);
  border-radius: 7px;
  display: grid;
  place-items: center;
  padding: 0 9px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  box-shadow: 3px 3px 0 var(--comic-ink);
  font: inherit;
  font-size: 13px;
  font-weight: 950;
  cursor: pointer;
  transition: transform 150ms ease, background 150ms ease, box-shadow 150ms ease;
}

.content-editor__toolbar button:hover,
.content-editor__toolbar label:hover {
  background: var(--comic-yellow);
  transform: translate(-1px, -2px) rotate(-1deg);
  box-shadow: 5px 5px 0 var(--comic-ink);
}

.content-editor__toolbar .active {
  background: var(--comic-cyan);
  color: var(--comic-ink);
}

.content-editor__toolbar input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

:deep(.content-editor__surface) {
  min-height: 52dvh;
  border: 0;
  outline: 0;
  color: var(--comic-ink);
  font-size: clamp(17px, 1.8vw, 21px);
  line-height: 1.64;
}

:deep(.content-editor__surface.is-empty::before) {
  color: rgba(5, 7, 11, 0.38);
}

.compact :deep(.content-editor__surface) {
  min-height: 92px;
  max-height: 260px;
  overflow: auto;
  font-size: 15px;
  line-height: 1.5;
}

:deep(.content-editor__surface p) {
  margin: 0 0 0.85em;
}

:deep(.content-editor__surface h1),
:deep(.content-editor__surface h2),
:deep(.content-editor__surface h3) {
  margin: 0 0 0.5em;
  line-height: 1.12;
  letter-spacing: 0;
  font-family: var(--display-font);
  font-weight: 400;
}

:deep(.content-editor__surface h1) {
  font-size: clamp(32px, 5vw, 56px);
}

:deep(.content-editor__surface h2) {
  font-size: clamp(24px, 3vw, 34px);
}

.compact :deep(.content-editor__surface h1) {
  font-size: 22px;
}

.compact :deep(.content-editor__surface h2) {
  font-size: 18px;
}

:deep(.content-editor__surface blockquote) {
  margin: 0 0 1em;
  border-left: 6px solid var(--comic-coral);
  padding: 10px 14px;
  background: rgba(246, 255, 24, 0.26);
  color: #252a31;
  box-shadow: inset 4px 0 0 var(--comic-ink);
}

:deep(.media-file-node) {
  position: relative;
  display: inline-grid;
  max-width: min(520px, 100%);
  vertical-align: middle;
}

:deep(.media-file-node__preview img),
:deep(.media-file-node__preview video) {
  max-width: min(460px, 100%);
  max-height: 320px;
  border: var(--comic-line);
  border-radius: 8px;
  object-fit: cover;
  box-shadow: var(--comic-shadow-small);
}

:deep(.media-file-node__chip),
:deep(.media-file-node__capsule) {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border: 3px solid var(--comic-ink);
  border-radius: 7px;
  padding: 6px 8px 6px 13px;
  background: var(--comic-cyan);
  color: var(--comic-ink);
  box-shadow: 3px 3px 0 var(--comic-ink);
  font-size: 14px;
  font-weight: 900;
}

:deep(.media-file-node__capsule) {
  position: absolute;
  inset: auto auto 50% 50%;
  transform: translate(-50%, 50%);
  max-width: min(360px, 90vw);
  opacity: 0;
  pointer-events: none;
  box-shadow: var(--comic-shadow-small);
}

:deep(.media-file-node:hover .media-file-node__capsule),
:deep(.media-file-node.ProseMirror-selectednode .media-file-node__capsule) {
  opacity: 1;
  pointer-events: auto;
}

:deep(.media-file-node__capsule span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.media-file-node__capsule button) {
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 6px;
  display: grid;
  place-items: center;
  background: rgba(5, 7, 11, 0.12);
  color: inherit;
  cursor: pointer;
}
</style>
