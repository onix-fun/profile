import type { ContentBlock, CreatePostInput, PostBlock, SavePostDraftInput } from "@/api/types";
import { isFileLikeUrl } from "@/features/display/markdown";
import {
  attachmentMarkdown,
  attachmentType,
  buildContentBlocks,
  createAttachment,
  extractHashtags,
  filesFromAttachments,
  firstMarkdownHeading,
  stripCreatorDirectives,
  stripMediaReferences,
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

/**
 * Mirrors the server's publication gate. A draft may be blank, but a post may
 * not become public because of a title, tag, divider, file placeholder, or an
 * empty creator directive alone.
 */
export function hasPublishablePostContent(state: Pick<PostEditorState, "markdown" | "attachments">): boolean {
  const markdown = stripMediaReferences(stripCreatorDirectives(state.markdown)).trim();
  if (markdown) return true;
  return buildContentBlocks(state.markdown, state.attachments).some((block) => {
    if (block.type === "TEXT") {
      return Boolean(stripMediaReferences(stripCreatorDirectives(String(block.data.text || ""))).trim());
    }
    return isPublishableBlock(block);
  });
}

export function publishabilityMessage(state: Pick<PostEditorState, "markdown" | "attachments">): string | null {
  return hasPublishablePostContent(state)
    ? null
    : "Добавьте текст или заполненный блок с медиа, ссылкой, кодом, цитатой, чеклистом или опросом.";
}

function isPublishableBlock(block: ContentBlock): boolean {
  const data = block.data;
  const text = (...keys: string[]) => keys.some((key) => typeof data[key] === "string" && String(data[key]).trim().length > 0);
  const source = () => text("blobId", "url", "src", "previewUrl", "markdownRef");
  const list = (value: unknown) => Array.isArray(value) && value.some((item) => {
    if (typeof item === "string") return item.trim().length > 0;
    return Boolean(item && typeof item === "object" && Object.values(item as Record<string, unknown>).some((field) => typeof field === "string" && field.trim().length > 0));
  });

  switch (block.type) {
    case "TEXT": return text("text");
    case "IMAGE":
    case "VIDEO":
    case "AUDIO":
    case "FILE": return source();
    case "GALLERY": return list(data.items);
    case "LINK_CARD":
    case "TRUSTED_EMBED": return text("url");
    case "CALLOUT": return text("text", "title", "body");
    case "QUOTE": return text("quote", "text");
    case "CODE": return text("code", "text");
    case "CHECKLIST": return list(data.items);
    case "POLL": return text("question") && Array.isArray(data.options) && data.options.filter((option) => {
      if (typeof option === "string") return option.trim().length > 0;
      return Boolean(option && typeof option === "object" && typeof (option as Record<string, unknown>).label === "string" && String((option as Record<string, unknown>).label).trim());
    }).length >= 2;
    case "DIVIDER": return false;
  }
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

  if (!hasPublishablePostContent(state)) {
    throw new Error("Добавьте содержательный блок перед публикацией");
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

export function buildPostDraftInput(state: PostEditorState, id?: string): SavePostDraftInput {
  const markdown = state.markdown.trim();
  const tags = Array.from(new Set([...extractHashtags(markdown), ...state.tags])).slice(0, 20);
  return {
    ...(id ? { id } : {}),
    title: state.title.trim() || firstMarkdownHeading(markdown),
    text: markdown,
    blocks: buildContentBlocks(markdown, state.attachments),
    tags,
    allowComments: state.allowComments,
    contentVersion: 2,
  };
}
