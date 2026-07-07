import type { CanvasPostNode, ContentBlock, FeedItem, RecommendationFeedResponse } from "@/api/types";

export const FEED_CHUNK_SIZE = 1120;
export const FEED_PREFETCH_RADIUS = 1;
export const FEED_KEEP_RADIUS = 2;
export const FEED_CHUNK_LIMIT = 12;

const FEED_CELL_WIDTH = 320;
const FEED_CELL_HEIGHT = 276;
const FEED_NODE_WIDTH = 280;
const FEED_NODE_HEIGHT = 238;
const COLLISION_GAP = 16;

export interface FeedCamera {
  x: number;
  y: number;
}

export interface FeedChunkCoord {
  x: number;
  y: number;
}

function mediaType(item: FeedItem): ContentBlock["type"] | "TEXT" {
  return item.post.blocks.find((block) => block.type !== "TEXT")?.type ?? "TEXT";
}

function fallbackCell(index: number) {
  return { q: index % 3, r: Math.floor(index / 3) };
}

export function feedChunkKey(x: number, y: number): string {
  return `${x}:${y}`;
}

export function cameraChunk(camera: FeedCamera): FeedChunkCoord {
  return {
    x: Math.floor(camera.x / FEED_CHUNK_SIZE),
    y: Math.floor(camera.y / FEED_CHUNK_SIZE),
  };
}

export function requiredFeedChunks(camera: FeedCamera, radius = FEED_PREFETCH_RADIUS): FeedChunkCoord[] {
  const center = cameraChunk(camera);
  const chunks: FeedChunkCoord[] = [];
  for (let y = center.y - radius; y <= center.y + radius; y += 1) {
    for (let x = center.x - radius; x <= center.x + radius; x += 1) {
      chunks.push({ x, y });
    }
  }
  return chunks;
}

export function shouldKeepFeedChunk(key: string, camera: FeedCamera, radius = FEED_KEEP_RADIUS): boolean {
  const [xRaw, yRaw] = key.split(":");
  const x = Number(xRaw);
  const y = Number(yRaw);
  const center = cameraChunk(camera);
  return Number.isFinite(x)
    && Number.isFinite(y)
    && Math.abs(x - center.x) <= radius
    && Math.abs(y - center.y) <= radius;
}

export function buildRecommendationCanvasNodes(chunks: RecommendationFeedResponse[]): CanvasPostNode[] {
  return chunks.flatMap((chunk) => chunk.items.map((item, index) => {
    const cell = item.cell || fallbackCell(index);
    return {
      id: item.post.id,
      item,
      chunkKey: feedChunkKey(chunk.chunkX, chunk.chunkY),
      cell,
      x: Math.round(chunk.chunkX * FEED_CHUNK_SIZE + 72 + cell.q * FEED_CELL_WIDTH + (cell.r % 2) * (FEED_CELL_WIDTH / 2)),
      y: Math.round(chunk.chunkY * FEED_CHUNK_SIZE + 48 + cell.r * FEED_CELL_HEIGHT),
      width: FEED_NODE_WIDTH,
      height: FEED_NODE_HEIGHT,
      mediaType: mediaType(item),
      emphasis: item.emphasis,
    };
  }));
}

export function buildFeedCanvasNodes(items: FeedItem[]): CanvasPostNode[] {
  return buildRecommendationCanvasNodes([{
    chunkX: 0,
    chunkY: 0,
    sessionSeed: "test",
    items: items.map((item, index) => ({
      ...item,
      cell: fallbackCell(index),
      emphasis: item.score >= 80 ? "hero" : item.score >= 30 ? "standard" : "compact",
    })),
  }]);
}

export function screenPosition(node: Pick<CanvasPostNode, "x" | "y">, camera: FeedCamera, viewportWidth: number, viewportHeight: number) {
  return {
    left: node.x - camera.x + viewportWidth / 2,
    top: node.y - camera.y + viewportHeight / 2,
  };
}

export function feedNodesOverlap(a: Pick<CanvasPostNode, "x" | "y" | "width" | "height">, b: Pick<CanvasPostNode, "x" | "y" | "width" | "height">): boolean {
  return a.x < b.x + b.width + COLLISION_GAP
    && a.x + a.width + COLLISION_GAP > b.x
    && a.y < b.y + b.height + COLLISION_GAP
    && a.y + a.height + COLLISION_GAP > b.y;
}
