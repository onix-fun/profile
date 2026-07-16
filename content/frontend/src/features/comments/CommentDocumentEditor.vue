<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import TaskList from "@tiptap/extension-task-list";
import TaskItem from "@tiptap/extension-task-item";
import { Bold, Code2, Download, Heading2, Heading3, ImagePlus, Italic, List, ListChecks, ListOrdered, Minus, Quote, Strikethrough, Upload } from "lucide-vue-next";
import type { CommentDocumentBlock, CommentDocumentV1, PostAsset } from "@/api/types";
import { MediaFile } from "@/features/contentDocument/mediaFileExtension";
import { commentDocumentText, commentDocumentToMarkdown, commentDocumentToTiptap, markdownToCommentDocument, tiptapToCommentDocument } from "@/features/comments/commentDocument";

const props = withDefaults(defineProps<{ modelValue: CommentDocumentV1; attachments: PostAsset[]; placeholder?: string }>(), { placeholder: "Напишите комментарий…" });
const emit = defineEmits<{ "update:modelValue": [document: CommentDocumentV1]; "add-files": [files: FileList]; "remove-attachment": [id: string] }>();
const slashOpen = ref(false);
let applying = false;
const count = computed(() => commentDocumentText(props.modelValue).length);

const editor = useEditor({
  content: commentDocumentToTiptap(props.modelValue, props.attachments),
  extensions: [StarterKit.configure({ heading: { levels: [2, 3] } }), TaskList, TaskItem.configure({ nested: false }), MediaFile.configure({ onRemove: (id) => emit("remove-attachment", id) })],
  editorProps: {
    attributes: { class: "comment-document-editor__surface", "data-placeholder": props.placeholder },
    handleKeyDown: (_view, event) => { if (event.key === "/") slashOpen.value = true; return false; },
  },
  onUpdate: ({ editor: current }) => {
    if (applying) return;
    emit("update:modelValue", tiptapToCommentDocument(current.getJSON(), props.attachments));
  },
});

watch(() => props.modelValue, (document) => {
  if (!editor.value) return;
  const current = tiptapToCommentDocument(editor.value.getJSON(), props.attachments);
  if (JSON.stringify(current.blocks.map(stripId)) === JSON.stringify(document.blocks.map(stripId))) return;
  applying = true;
  editor.value.commands.setContent(commentDocumentToTiptap(document, props.attachments), { emitUpdate: false });
  applying = false;
}, { deep: true });

watch(() => props.attachments, (attachments) => {
  if (!editor.value) return;
  const current = tiptapToCommentDocument(editor.value.getJSON(), attachments);
  const referenced = new Set(current.blocks.filter((block) => block.type === "MEDIA").map((block) => block.assetId));
  attachments.forEach((asset) => {
    const assetId = asset.assetId || asset.id;
    if (referenced.has(assetId)) return;
    editor.value?.chain().focus().insertMediaFile({ id: asset.id, name: "media", type: asset.kind, url: asset.previewUrl || asset.url || "" }).run();
  });
}, { deep: true });

onBeforeUnmount(() => editor.value?.destroy());

function insert(kind: "paragraph"|"h2"|"h3"|"list"|"ordered"|"checklist"|"quote"|"code"|"divider") {
  const chain = editor.value?.chain().focus();
  if (!chain) return;
  if (kind === "paragraph") chain.setParagraph().run();
  if (kind === "h2") chain.toggleHeading({ level: 2 }).run();
  if (kind === "h3") chain.toggleHeading({ level: 3 }).run();
  if (kind === "list") chain.toggleBulletList().run();
  if (kind === "ordered") chain.toggleOrderedList().run();
  if (kind === "checklist") chain.toggleTaskList().run();
  if (kind === "quote") chain.toggleBlockquote().run();
  if (kind === "code") chain.toggleCodeBlock().run();
  if (kind === "divider") chain.setHorizontalRule().run();
  slashOpen.value = false;
}
function files(event: Event) { const input=event.target as HTMLInputElement;if(input.files)emit("add-files",input.files);input.value=""; }
function importMarkdown(){const value=window.prompt("Вставьте Markdown");if(value!==null)emit("update:modelValue",markdownToCommentDocument(value));}
async function exportMarkdown(){await navigator.clipboard?.writeText?.(commentDocumentToMarkdown(props.modelValue));}
function stripId(block: CommentDocumentBlock) { const { id: _id, ...rest } = block; return rest; }
</script>

<template>
  <section class="comment-document-editor">
    <div v-if="editor" class="comment-document-editor__toolbar" aria-label="Форматирование комментария">
      <button type="button" :class="{active:editor.isActive('bold')}" aria-label="Жирный" @click="editor.chain().focus().toggleBold().run()"><Bold :size="16" /></button>
      <button type="button" :class="{active:editor.isActive('italic')}" aria-label="Курсив" @click="editor.chain().focus().toggleItalic().run()"><Italic :size="16" /></button>
      <button type="button" :class="{active:editor.isActive('strike')}" aria-label="Зачёркнутый" @click="editor.chain().focus().toggleStrike().run()"><Strikethrough :size="16" /></button>
      <button type="button" aria-label="Заголовок 2" @click="insert('h2')"><Heading2 :size="16" /></button><button type="button" aria-label="Заголовок 3" @click="insert('h3')"><Heading3 :size="16" /></button>
      <button type="button" aria-label="Список" @click="insert('list')"><List :size="16" /></button><button type="button" aria-label="Цитата" @click="insert('quote')"><Quote :size="16" /></button><button type="button" aria-label="Код" @click="insert('code')"><Code2 :size="16" /></button><button type="button" aria-label="Разделитель" @click="insert('divider')"><Minus :size="16" /></button>
      <button type="button" aria-label="Нумерованный список" @click="insert('ordered')"><ListOrdered :size="16" /></button><button type="button" aria-label="Чеклист" @click="insert('checklist')"><ListChecks :size="16" /></button>
      <label aria-label="Добавить изображение или видео"><ImagePlus :size="16" /><input type="file" accept="image/jpeg,image/png,image/webp,video/mp4" multiple @change="files" /></label>
      <button type="button" aria-label="Импортировать Markdown" @click="importMarkdown"><Upload :size="16" /></button><button type="button" aria-label="Скопировать Markdown" @click="exportMarkdown"><Download :size="16" /></button>
      <span :class="{invalid:count>4000}">{{ count }}/4000</span>
    </div>
    <div v-if="slashOpen" class="comment-document-editor__slash" role="menu"><button type="button" @click="insert('paragraph')">Текст</button><button type="button" @click="insert('h2')">H2</button><button type="button" @click="insert('h3')">H3</button><button type="button" @click="insert('list')">Список</button><button type="button" @click="insert('ordered')">Нумерация</button><button type="button" @click="insert('checklist')">Чеклист</button><button type="button" @click="insert('quote')">Цитата</button><button type="button" @click="insert('code')">Код</button><button type="button" @click="insert('divider')">Линия</button></div>
    <EditorContent :editor="editor" />
  </section>
</template>

<style scoped>
.comment-document-editor{position:relative;display:grid;gap:8px;min-width:0}.comment-document-editor__toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:3px}.comment-document-editor__toolbar button,.comment-document-editor__toolbar label{display:grid;place-items:center;width:31px;height:31px;border:0;border-radius:8px;background:transparent;color:#687383;cursor:pointer}.comment-document-editor__toolbar button:hover,.comment-document-editor__toolbar button.active,.comment-document-editor__toolbar label:hover{background:#e9edf1;color:#30343b}.comment-document-editor__toolbar input{position:absolute;width:1px;height:1px;opacity:0}.comment-document-editor__toolbar span{margin-left:auto;color:#8d96a1;font-size:11px;font-weight:800}.comment-document-editor__toolbar span.invalid{color:#b43b34}.comment-document-editor__slash{position:absolute;z-index:8;top:40px;left:8px;display:grid;grid-template-columns:repeat(2,minmax(80px,1fr));gap:4px;padding:8px;border-radius:12px;background:#fff;box-shadow:0 12px 30px rgba(35,40,50,.16)}.comment-document-editor__slash button{border:0;border-radius:8px;padding:8px;background:#eef0f2;color:#4d5663;text-align:left;cursor:pointer}:deep(.comment-document-editor__surface){min-height:72px;max-height:300px;overflow:auto;outline:0;color:#30343b;font:700 14px/1.48 "Nunito",sans-serif}:deep(.comment-document-editor__surface p){margin:0 0 .7em}:deep(.comment-document-editor__surface h2){font-size:19px}:deep(.comment-document-editor__surface h3){font-size:16px}:deep(.comment-document-editor__surface blockquote){margin:8px 0;padding:8px 12px;border-left:3px solid #8793a3;background:#f2f4f6}:deep(.comment-document-editor__surface pre){overflow:auto;padding:10px;border-radius:8px;background:#252b35;color:#fff}:deep(.media-file-node){max-width:320px;margin:8px 0}:deep(.media-file-node img),:deep(.media-file-node video){display:block;max-width:100%;max-height:240px;object-fit:contain;border-radius:10px}
</style>
