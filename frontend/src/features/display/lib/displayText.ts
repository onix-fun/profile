import type { ContentBlock, ProfileContentPost, StoryRailItem } from "@/shared/api/types";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHORT_UUID_PATTERN = /^@?[0-9a-f]{8}$/i;

export function looksLikeUuid(value: unknown): boolean {
  return typeof value === "string" && (UUID_PATTERN.test(value.trim()) || SHORT_UUID_PATTERN.test(value.trim()));
}

export function safeDisplayText(value: unknown, fallback: string): string {
  if (typeof value !== "string") return fallback;
  const text = stripMediaReferences(value).trim();
  if (!text || looksLikeUuid(text)) return fallback;
  return text;
}

export function stripMediaReferences(value: string): string {
  return value
    .replace(/!\[\[media:[^|\]]+(?:\|([^\]]+))?\]\]/g, "$1")
    .replace(/\[([^\]]+)\]\(media:[^)]+\)/g, "$1");
}

export function displayUsername(value: unknown, fallback = "User"): string {
  return safeDisplayText(value, fallback).replace(/^@/, "");
}

export function displayStoryAuthor(item: StoryRailItem): string {
  return displayUsername(item.author?.username || item.authorName, "User");
}

export function mediaFileName(block: ContentBlock): string {
  return safeDisplayText(block.data.fileName, `${block.type.toLowerCase()} file`);
}

export function postSnippet(post: Pick<ProfileContentPost, "title" | "text" | "blocks">, fallback = "Post"): string {
  const title = safeDisplayText(post.title, "");
  if (title) return title;

  const text = safeDisplayText(post.text, "");
  if (text) return text.slice(0, 96);

  const media = post.blocks?.find((block) => block.type !== "TEXT");
  if (!media) return fallback;
  return media.type === "FILE" || media.type === "AUDIO" ? mediaFileName(media) : fallback;
}
