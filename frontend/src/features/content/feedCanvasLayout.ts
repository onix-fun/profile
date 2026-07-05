import type { CanvasPostNode, FeedItem } from "@/api/types";

const CANVAS_CENTER = 2400;
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

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
  if (score >= 80 || type === "VIDEO") return { width: 330, height: 250, emphasis: "hero" };
  if (type === "IMAGE") return { width: 292, height: 226, emphasis: "standard" };
  if (type === "AUDIO") return { width: 268, height: 176, emphasis: "standard" };
  return { width: 246, height: 154, emphasis: "compact" };
}

export function buildFeedCanvasNodes(items: FeedItem[]): CanvasPostNode[] {
  return items.map((item, index) => {
    const idHash = hash(item.post.id);
    const rank = index + 1;
    const score = Number.isFinite(item.score) ? item.score : 0;
    const radius = 130 + Math.sqrt(rank) * 190 + (100 - Math.min(score, 100)) * 2.2;
    const angle = rank * GOLDEN_ANGLE + (idHash % 360) * Math.PI / 180;
    const jitterX = (idHash % 140) - 70;
    const jitterY = ((idHash >>> 8) % 120) - 60;
    const type = mediaType(item);
    const size = nodeSize(type, score);

    return {
      id: item.post.id,
      item,
      x: Math.round(CANVAS_CENTER + Math.cos(angle) * radius + jitterX),
      y: Math.round(CANVAS_CENTER + Math.sin(angle) * radius + jitterY),
      mediaType: type,
      ...size,
    };
  });
}

export function initialCanvasScroll(viewportWidth: number, viewportHeight: number) {
  return {
    left: Math.max(0, CANVAS_CENTER - viewportWidth / 2),
    top: Math.max(0, CANVAS_CENTER - viewportHeight / 2),
  };
}

