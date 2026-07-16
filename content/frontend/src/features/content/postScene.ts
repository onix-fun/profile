import type { ContentBlock, FeedEmphasis, ProfileContentPost } from "@/api/types";

export interface ScenePalette {
  colors: [string, string, string];
  ink: string;
}

export interface PostSceneFragment {
  block: ContentBlock | null;
  key: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rotate: number;
  z: number;
  shape: "orb" | "pebble" | "slice" | "spark" | "text";
  scale: number;
}

const TAG_COLORS: Record<string, string> = {
  art: "#ff4ca0", design: "#a855f7", illustration: "#ff6b35", music: "#7c3aed",
  audio: "#8b5cf6", tech: "#2477ff", code: "#00a7ff", education: "#72d400",
  study: "#9ad900", travel: "#10c7b7", nature: "#25ba65", food: "#ff8a2a",
  gaming: "#5947f2", business: "#ff5d42", photo: "#ff4ca0", fashion: "#ff3b84",
};

const TYPE_COLORS: Record<ContentBlock["type"], string> = {
  TEXT: "#2563eb", IMAGE: "#ff4ca0", VIDEO: "#ff7a1a", AUDIO: "#7c3aed", FILE: "#00a7ff",
  GALLERY: "#f03ea8", LINK_CARD: "#00a7ff", CALLOUT: "#72d400", QUOTE: "#ffb000",
  DIVIDER: "#7c3aed", CODE: "#2563eb", CHECKLIST: "#25ba65", POLL: "#ff7a1a", TRUSTED_EMBED: "#5947f2",
};

export function postScenePalette(post: ProfileContentPost): ScenePalette {
  const picked = [
    ...post.tags.map((tag) => TAG_COLORS[tag.toLowerCase().replace(/^#/, "")]).filter(Boolean),
    ...((post.blocks || []).map((block) => accentColor(block) || TYPE_COLORS[block.type])),
  ];
  const seed = seeded(post.id || post.title || post.text || "onix");
  const fallback = ["#ff4ca0", "#00b7d9", "#84d900", "#ff8a2a", "#7956f2"];
  const colors = [...new Set(picked)].slice(0, 3);
  while (colors.length < 3) colors.push(fallback[Math.floor(seed() * fallback.length)]);
  return { colors: colors.slice(0, 3) as [string, string, string], ink: "#16213b" };
}

export function composePostScene(post: ProfileContentPost, emphasis: FeedEmphasis = "standard", maxBlocks?: number): PostSceneFragment[] {
  const seed = seeded(`${post.id}:${post.title}:${post.tags.join(",")}`);
  const blocks = chooseBlocks(post.blocks || [], emphasis, maxBlocks ?? (emphasis === "hero" ? 5 : emphasis === "compact" ? 2 : 4));
  const centerX = 50 + Math.round((seed() - 0.5) * 9);
  const centerY = 47 + Math.round((seed() - 0.5) * 10);
  const fragments: PostSceneFragment[] = blocks.map((block, index) => {
    const isCenter = index === 0;
    const angle = (Math.PI * 2 * index) / Math.max(blocks.length - 1, 1) + seed() * 0.68;
    const distance = isCenter ? 0 : 22 + index * 7 + seed() * 7;
    const scale = isCenter ? 1 : Math.max(0.48, 0.88 - index * 0.13 + (seed() - 0.5) * 0.14);
    const media = isMedia(block);
    const width = Math.round((isCenter ? 47 : media ? 31 : 27) * scale);
    const height = Math.round((isCenter ? 56 : media ? 38 : 25) * scale);
    const shape = !isCenter && index % 4 === 0 ? "spark" : block?.type === "TEXT" ? "text" : index % 3 === 0 ? "slice" : index % 2 === 0 ? "pebble" : "orb";
    return {
      block,
      key: block.id || `${block.type}-${index}`,
      x: Math.round(centerX + Math.cos(angle) * distance - width / 2),
      y: Math.round(centerY + Math.sin(angle) * distance - height / 2),
      width,
      height,
      rotate: Math.round((seed() - 0.5) * (isCenter ? 9 : 26)),
      z: isCenter ? 5 : blocks.length - index,
      shape,
      scale,
    };
  });
  return fragments;
}

function chooseBlocks(blocks: ContentBlock[], emphasis: FeedEmphasis, limit: number): ContentBlock[] {
  const priority = (block: ContentBlock) => {
    if (["VIDEO", "IMAGE", "GALLERY", "TRUSTED_EMBED"].includes(block.type)) return 4;
    if (["POLL", "AUDIO", "LINK_CARD", "CALLOUT"].includes(block.type)) return 3;
    if (block.type === "TEXT") return 2;
    return 1;
  };
  const ordered = [...blocks].sort((a, b) => priority(b) - priority(a));
  const selected = ordered.slice(0, Math.max(1, limit));
  const text = blocks.find((block) => block.type === "TEXT");
  if (text && !selected.includes(text) && selected.length < limit) selected.push(text);
  return selected;
}

function accentColor(block: ContentBlock): string | undefined {
  const value = block.data.accentColor || block.data.color;
  return typeof value === "string" && /^#[\da-f]{6}$/i.test(value) ? value : undefined;
}

function isMedia(block: ContentBlock): boolean {
  return ["IMAGE", "VIDEO", "AUDIO", "GALLERY", "TRUSTED_EMBED"].includes(block.type);
}

function seeded(value: string): () => number {
  let state = 2166136261;
  for (const char of value) state = Math.imul(state ^ char.charCodeAt(0), 16777619);
  return () => {
    state += 0x6d2b79f5;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
