<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import { createAttachment, type ContentAttachment } from "@/features/contentDocument/contentModel";
import { editorJsonToMarkdown, markdownToEditorHtml } from "@/features/contentDocument/tiptapMarkdown";
import { MediaFile } from "@/features/contentDocument/mediaFileExtension";

const props = withDefaults(defineProps<{
  modelValue: string;
  attachments: ContentAttachment[];
  placeholder?: string;
  compact?: boolean;
}>(), {
  placeholder: "Write...",
  compact: false,
});

const emit = defineEmits<{
  "update:modelValue": [value: string];
  "update:attachments": [value: ContentAttachment[]];
}>();

const attachmentById = computed(() => new Map(props.attachments.map((attachment) => [attachment.id, attachment])));

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
  const next = Array.from(files).map(createAttachment);
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

onBeforeUnmount(() => {
  editor.value?.destroy();
});
</script>

<template>
  <section class="content-editor" :class="{ compact }">
    <div v-if="editor" class="content-editor__toolbar" aria-label="Formatting toolbar">
      <button type="button" title="Heading 1" :class="{ active: editor.isActive('heading', { level: 1 }) }" @click="editor.chain().focus().toggleHeading({ level: 1 }).run()">H1</button>
      <button type="button" title="Heading 2" :class="{ active: editor.isActive('heading', { level: 2 }) }" @click="editor.chain().focus().toggleHeading({ level: 2 }).run()">H2</button>
      <button type="button" title="Bold" :class="{ active: editor.isActive('bold') }" @click="editor.chain().focus().toggleBold().run()"><strong>B</strong></button>
      <button type="button" title="Italic" :class="{ active: editor.isActive('italic') }" @click="editor.chain().focus().toggleItalic().run()"><em>I</em></button>
      <button type="button" title="Inline code" :class="{ active: editor.isActive('code') }" @click="editor.chain().focus().toggleCode().run()"><i class="pi pi-code"></i></button>
      <button type="button" title="Bullet list" :class="{ active: editor.isActive('bulletList') }" @click="editor.chain().focus().toggleBulletList().run()"><i class="pi pi-list"></i></button>
      <button type="button" title="Quote" :class="{ active: editor.isActive('blockquote') }" @click="editor.chain().focus().toggleBlockquote().run()">”</button>
      <button type="button" title="Link" :class="{ active: editor.isActive('link') }" @click="setLink"><i class="pi pi-link"></i></button>
      <label title="Attach file">
        <i class="pi pi-paperclip"></i>
        <input type="file" multiple @change="onFileInput" />
      </label>
    </div>
    <EditorContent :editor="editor" />
  </section>
</template>

<style scoped>
.content-editor {
  display: grid;
  gap: 14px;
}

.content-editor__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.content-editor__toolbar button,
.content-editor__toolbar label {
  min-width: 34px;
  height: 34px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  display: grid;
  place-items: center;
  padding: 0 9px;
  background: #ffffff;
  color: #111827;
  font: inherit;
  font-size: 13px;
  font-weight: 850;
  cursor: pointer;
}

.content-editor__toolbar .active {
  background: #111827;
  color: #ffffff;
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
  color: #111827;
  font-size: clamp(17px, 1.8vw, 21px);
  line-height: 1.64;
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
  border-left: 3px solid #14b8a6;
  padding-left: 14px;
  color: #475569;
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
  border-radius: 8px;
  object-fit: cover;
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.16);
}

:deep(.media-file-node__chip),
:deep(.media-file-node__capsule) {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border-radius: 999px;
  padding: 6px 8px 6px 13px;
  background: #dbeafe;
  color: #1d4ed8;
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
  box-shadow: 0 12px 30px rgba(37, 99, 235, 0.2);
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
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: rgba(29, 78, 216, 0.12);
  color: inherit;
  cursor: pointer;
}
</style>
