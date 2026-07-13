import type { ContentBlock, PostBlock } from "@/api/types";

export interface ContentAttachment {
  id: string;
  file?: File;
  blobId?: string;
  url: string;
  name: string;
  mimeType: string;
  size: number;
  type: "IMAGE" | "VIDEO" | "AUDIO" | "FILE";
}

export interface MediaReference {
  id: string;
  label: string;
  raw: string;
}

const WIKI_MEDIA_PATTERN = /!\[\[media:([^|\]]+)(?:\|([^\]]+))?\]\]/g;
const MARKDOWN_MEDIA_PATTERN = /\[([^\]]+)\]\(media:([^)]+)\)/g;

export function attachmentType(file: File): ContentAttachment["type"] {
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type.startsWith("image/")) return "IMAGE";
  return "FILE";
}

export function createAttachment(file: File): ContentAttachment {
  return {
    id: crypto.randomUUID(),
    file,
    url: URL.createObjectURL(file),
    name: file.name,
    mimeType: file.type || "application/octet-stream",
    size: file.size,
    type: attachmentType(file),
  };
}

export function attachmentMarkdown(attachment: Pick<ContentAttachment, "id" | "name">): string {
  return `![[media:${attachment.id}|${attachment.name}]]`;
}

export function mediaReferences(markdown: string): MediaReference[] {
  const wiki = Array.from(markdown.matchAll(WIKI_MEDIA_PATTERN)).map((match) => ({
    id: match[1],
    label: match[2] || match[1],
    raw: match[0],
  }));
  const markdownLinks = Array.from(markdown.matchAll(MARKDOWN_MEDIA_PATTERN)).map((match) => ({
    id: match[2],
    label: match[1],
    raw: match[0],
  }));
  return [...wiki, ...markdownLinks];
}

export function stripMediaReferences(markdown: string): string {
  return markdown
    .replace(WIKI_MEDIA_PATTERN, "")
    .replace(MARKDOWN_MEDIA_PATTERN, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function extractHashtags(markdown: string): string[] {
  return Array.from(markdown.matchAll(/(^|\s)#([\p{L}\p{N}_-]+)/gu))
    .map((match) => match[2].toLowerCase())
    .filter((tag, index, tags) => tags.indexOf(tag) === index)
    .slice(0, 20);
}

export function firstMarkdownHeading(markdown: string): string | undefined {
  return markdown.match(/^#\s+(.+)$/m)?.[1].trim() || undefined;
}

export function isMediaPreviewBlock(block?: ContentBlock): boolean {
  if (!block) return false;
  const mimeType = typeof block.data.mimeType === "string" ? block.data.mimeType : "";
  return block.type === "IMAGE"
    || block.type === "VIDEO"
    || block.type === "AUDIO"
    || mimeType.startsWith("image/")
    || mimeType.startsWith("video/")
    || mimeType.startsWith("audio/");
}

export function blockMediaRef(block: ContentBlock): string {
  const ref = block.data.markdownRef;
  if (typeof ref === "string" && ref.startsWith("media:")) return ref.slice("media:".length);
  return block.id || "";
}

export function buildContentBlocks(markdown: string, attachments: ContentAttachment[]): PostBlock[] {
  const blocks: PostBlock[] = [];
  if (markdown.trim()) {
    blocks.push({
      id: crypto.randomUUID(),
      type: "TEXT",
      data: { text: markdown.trim(), format: "markdown" },
    });
  }

  attachments.forEach((attachment) => {
    blocks.push({
      id: attachment.id,
      type: attachment.type,
      data: {
        fileName: attachment.name,
        mimeType: attachment.mimeType,
        size: attachment.size,
        markdownRef: `media:${attachment.id}`,
        ...(attachment.blobId ? { blobId: attachment.blobId } : {}),
      },
    });
  });

  return blocks;
}

export function plainTextFromContentBlocks(blocks: ContentBlock[]): string {
  return blocks.map((block) => {
    const value = block.data.text;
    return typeof value === "string" ? value : "";
  }).filter(Boolean).join("\n");
}

export function filesFromAttachments(attachments: ContentAttachment[]): File[] {
  return attachments.map((attachment) => attachment.file).filter((file): file is File => Boolean(file));
}
