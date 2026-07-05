import type { CreatePostInput, PostBlock } from "@/api/types";

export interface PostAttachment {
  id: string;
  file: File;
  url: string;
  type: "IMAGE" | "VIDEO" | "AUDIO";
}

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

export function extractHashtags(markdown: string): string[] {
  return Array.from(markdown.matchAll(/(^|\s)#([\p{L}\p{N}_-]+)/gu))
    .map((match) => match[2].toLowerCase())
    .filter((tag, index, tags) => tags.indexOf(tag) === index)
    .slice(0, 20);
}

export function attachmentType(file: File): PostAttachment["type"] {
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  return "IMAGE";
}

export function createAttachment(file: File): PostAttachment {
  return {
    id: crypto.randomUUID(),
    file,
    url: URL.createObjectURL(file),
    type: attachmentType(file),
  };
}

export function attachmentMarkdown(attachment: Pick<PostAttachment, "id" | "file">): string {
  return `[${attachment.file.name}](media:${attachment.id})`;
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
  const blocks: PostBlock[] = [];

  if (markdown) {
    blocks.push({
      id: crypto.randomUUID(),
      type: "TEXT",
      data: { text: markdown, format: "markdown" },
    });
  }

  state.attachments.forEach((attachment) => {
    blocks.push({
      id: attachment.id,
      type: attachment.type,
      data: {
        fileName: attachment.file.name,
        mimeType: attachment.file.type,
        size: attachment.file.size,
        markdownRef: `media:${attachment.id}`,
      },
    });
  });

  if (!markdown && state.attachments.length === 0) {
    throw new Error("Add text or attach media");
  }

  const tags = Array.from(new Set([...extractHashtags(markdown), ...state.tags])).slice(0, 20);

  return {
    title: state.title.trim() || undefined,
    text: markdown || state.attachments.map((attachment) => attachmentMarkdown(attachment)).join("\n"),
    blocks,
    tags,
    allowComments: state.allowComments,
  };
}
