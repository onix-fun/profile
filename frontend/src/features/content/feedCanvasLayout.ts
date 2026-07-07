import type { CanvasPostNode, FeedItem } from "@/api/types";

const CANVAS_CENTER = 2400;
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
const COLLISION_GAP = 8;

function hash(value: string): number {
  let result = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    result ^= value.charCodeAt(index);
    result = Math.imul(result, 16777619);
  }
  return result >>> 0;
}

function mediaType(item: FeedItem): CanvasPostNode["mediaType"] {
  return item.post.blocks.find((block) => block.type !== "TEXT")?.type ?? "TEXT";
}

function nodeSize(type: CanvasPostNode["mediaType"], score: number): Pick<CanvasPostNode, "width" | "height" | "emphasis"> {
  if (score >= 80 || type === "VIDEO") return { width: 224, height: 224, emphasis: "hero" };
  if (type === "IMAGE") return { width: 196, height: 196, emphasis: "standard" };
  if (type === "AUDIO" || type === "FILE") return { width: 250, height: 84, emphasis: "standard" };
  return { width: 214, height: 142, emphasis: "compact" };
}

export function buildFeedCanvasNodes(items: FeedItem[]): CanvasPostNode[] {
  const placed: CanvasPostNode[] = [];
  items.forEach((item, index) => {
    const idHash = hash(item.post.id);
    const rank = index + 1;
    const score = Number.isFinite(item.score) ? item.score : 0;
    const radius = 48 + Math.sqrt(rank) * 68 + (100 - Math.min(score, 100)) * 0.32;
    const angle = rank * GOLDEN_ANGLE + (idHash % 360) * Math.PI / 180;
    const jitterX = (idHash % 32) - 16;
    const jitterY = ((idHash >>> 8) % 30) - 15;
    const type = mediaType(item);
    const size = nodeSize(type, score);
    const node = resolveCollision({
      id: item.post.id,
      item,
      x: Math.round(CANVAS_CENTER + Math.cos(angle) * radius + jitterX),
      y: Math.round(CANVAS_CENTER + Math.sin(angle) * radius + jitterY),
      mediaType: type,
      ...size,
    }, placed);

    placed.push(node);
  });
  return placed;
}

export function initialCanvasScroll(viewportWidth: number, viewportHeight: number) {
  return {
    left: Math.max(0, CANVAS_CENTER - viewportWidth / 2),
    top: Math.max(0, CANVAS_CENTER - viewportHeight / 2),
  };
}

function resolveCollision(node: CanvasPostNode, placed: CanvasPostNode[]): CanvasPostNode {
  let next = node;
  for (let attempt = 0; attempt < 900 && placed.some((item) => overlaps(item, next)); attempt += 1) {
    const ring = Math.ceil((attempt + 1) / 14);
    const angle = (attempt + 1) * GOLDEN_ANGLE;
    const distance = ring * (COLLISION_GAP + 14);
    next = {
      ...node,
      x: Math.round(node.x + Math.cos(angle) * distance),
      y: Math.round(node.y + Math.sin(angle) * distance),
    };
  }
  return next;
}

export function feedNodesOverlap(a: Pick<CanvasPostNode, "x" | "y" | "width" | "height">, b: Pick<CanvasPostNode, "x" | "y" | "width" | "height">): boolean {
  return overlaps(a, b);
}

function overlaps(a: Pick<CanvasPostNode, "x" | "y" | "width" | "height">, b: Pick<CanvasPostNode, "x" | "y" | "width" | "height">): boolean {
  return a.x < b.x + b.width + COLLISION_GAP
    && a.x + a.width + COLLISION_GAP > b.x
    && a.y < b.y + b.height + COLLISION_GAP
    && a.y + a.height + COLLISION_GAP > b.y;
}
