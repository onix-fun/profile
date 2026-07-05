import type { StoryRailItem } from "@/api/types";

export type StoryComposerStatus = "idle" | "recording" | "stopped" | "edit" | "publishing";

export interface StoryComposerState {
  status: StoryComposerStatus;
  mediaReady: boolean;
  caption: string;
  visibility: "PUBLIC" | "CLOSE_FRIENDS";
}

export type StoryComposerAction =
  | { type: "START_RECORDING" }
  | { type: "STOP_RECORDING"; mediaReady?: boolean }
  | { type: "SELECT_FILE" }
  | { type: "EDIT" }
  | { type: "SET_CAPTION"; caption: string }
  | { type: "SET_VISIBILITY"; visibility: "PUBLIC" | "CLOSE_FRIENDS" }
  | { type: "PUBLISH" }
  | { type: "RESET" };

export function emptyStoryComposerState(): StoryComposerState {
  return {
    status: "idle",
    mediaReady: false,
    caption: "",
    visibility: "PUBLIC",
  };
}

export function reduceStoryComposer(state: StoryComposerState, action: StoryComposerAction): StoryComposerState {
  switch (action.type) {
    case "START_RECORDING":
      return { ...state, status: "recording", mediaReady: false };
    case "STOP_RECORDING":
      return { ...state, status: action.mediaReady ? "edit" : "stopped", mediaReady: Boolean(action.mediaReady) };
    case "SELECT_FILE":
      return { ...state, status: "edit", mediaReady: true };
    case "EDIT":
      return state.mediaReady ? { ...state, status: "edit" } : state;
    case "SET_CAPTION":
      return { ...state, caption: action.caption };
    case "SET_VISIBILITY":
      return { ...state, visibility: action.visibility };
    case "PUBLISH":
      return { ...state, status: "publishing" };
    case "RESET":
      return emptyStoryComposerState();
    default:
      return state;
  }
}

export function extractStoryTags(caption: string): string[] {
  return Array.from(caption.matchAll(/(^|\s)#([\p{L}\p{N}_-]+)/gu))
    .map((match) => match[2].toLowerCase())
    .filter((tag, index, tags) => tags.indexOf(tag) === index)
    .slice(0, 20);
}

export function isStorySeen(storyId: string): boolean {
  return window.localStorage.getItem(`story-seen:${storyId}`) === "true";
}

export function mergeSeenState(items: StoryRailItem[]): StoryRailItem[] {
  return items.map((item) => ({
    ...item,
    seen: item.seen || item.storyIds.every(isStorySeen),
  }));
}

export function sortStoryRail(items: StoryRailItem[]): StoryRailItem[] {
  return [...items].sort((a, b) => {
    if (a.isViewer !== b.isViewer) return a.isViewer ? -1 : 1;
    if (a.seen !== b.seen) return a.seen ? 1 : -1;
    return Date.parse(b.latestAt || "1970-01-01") - Date.parse(a.latestAt || "1970-01-01");
  });
}

export function nextAuthorAfter(items: StoryRailItem[], authorId: string): StoryRailItem | null {
  const index = items.findIndex((item) => item.authorId === authorId);
  return index >= 0 ? items[index + 1] || null : null;
}
