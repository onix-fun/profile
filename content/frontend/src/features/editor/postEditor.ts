import type { CreatePostInput, PostBlock } from "@/api/types";
import { isFileLikeUrl } from "@/features/display/markdown";
import {
  attachmentMarkdown,
  attachmentType,
  buildContentBlocks,
  createAttachment,
  extractHashtags,
  filesFromAttachments,
  firstMarkdownHeading,
  type ContentAttachment,
} from "@/features/contentDocument/contentModel";

export type PostAttachment = ContentAttachment;

export interface PostEditorState {
  title: string;
  markdown: string;
  attachments: PostAttachment[];
  tags: string[];
  allowComments: boolean;
}

export const emptyPostEditorState = (): PostEditorState => ({
  title: "",
  markdown: "",
  attachments: [],
  tags: [],
  allowComments: true,
});

export { attachmentMarkdown, attachmentType, createAttachment, extractHashtags, filesFromAttachments };

export function isFileLikeMarkdownLink(label: string, href: string): boolean {
  return isFileLikeUrl(href, label);
}

export function isPostEditorDirty(state: PostEditorState): boolean {
  return Boolean(state.title.trim() || state.markdown.trim() || state.attachments.length);
}

export function applyFormatting(markdown: string, selectionStart: number, selectionEnd: number, marker: "**" | "_" | "`"): {
  markdown: string;
  selectionStart: number;
  selectionEnd: number;
} {
  const selected = markdown.slice(selectionStart, selectionEnd) || "text";
  const next = `${markdown.slice(0, selectionStart)}${marker}${selected}${marker}${markdown.slice(selectionEnd)}`;
  return {
    markdown: next,
    selectionStart: selectionStart + marker.length,
    selectionEnd: selectionStart + marker.length + selected.length,
  };
}

export function applySlashCommand(state: PostEditorState, command: string): PostEditorState {
  const value = command.trim();
  if (!value.startsWith("/")) return state;
  const [name, ...parts] = value.slice(1).split(/\s+/);
  const payload = parts.join(" ").trim();

  if (name === "tags") {
    return {
      ...state,
      tags: payload.split(/[,\s]+/).map((tag) => tag.replace(/^#/, "").toLowerCase()).filter(Boolean),
    };
  }
  if (name === "comments") {
    return {
      ...state,
      allowComments: !["off", "false", "disabled", "закрыть"].includes(payload.toLowerCase()),
    };
  }
  return state;
}

export function buildCreatePostInput(state: PostEditorState): CreatePostInput {
  const markdown = state.markdown.trim();
  const blocks: PostBlock[] = buildContentBlocks(markdown, state.attachments);

  if (!markdown && state.attachments.length === 0) {
    throw new Error("Add text or attach media");
  }

  const tags = Array.from(new Set([...extractHashtags(markdown), ...state.tags])).slice(0, 20);

  return {
    title: state.title.trim() || firstMarkdownHeading(markdown),
    text: markdown || state.attachments.map((attachment) => attachmentMarkdown(attachment)).join("\n"),
    blocks,
    tags,
    allowComments: state.allowComments,
  };
}
