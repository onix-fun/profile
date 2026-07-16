import type {
  CanvasPostNode,
  ContentBlock,
  FeedEmphasis,
  RecommendationFeedItem,
  RecommendationFeedResponse,
  RecommendationPlacement,
} from "@/api/types";

export const FEED_CHUNK_SIZE = 1440;
export const FEED_PREFETCH_RADIUS = 1;
export const FEED_KEEP_RADIUS = 2;
export const FEED_CHUNK_LIMIT = 12;
export const FEED_NODE_WIDTH = 348;
export const FEED_NODE_HEIGHT = 278;
export const FEED_NODE_GAP = 24;

export interface FeedCamera {
  x: number;
  y: number;
}

export interface FeedChunkCoord {
  x: number;
  y: number;
}

interface RecommendationEntry {
  chunk: RecommendationFeedResponse;
  item: RecommendationFeedItem;
}

function mediaType(item: RecommendationFeedItem): ContentBlock["type"] | "TEXT" {
  const asset = item.post.assets?.[0];
  if (asset?.kind === "IMAGE") return "IMAGE";
  if (asset?.kind === "VIDEO") return "VIDEO";
  if (asset?.kind === "AUDIO") return "AUDIO";
  return "TEXT";
}

function resolvedEmphasis(item: RecommendationFeedItem): FeedEmphasis {
  return item.emphasis || (item.score >= 80 ? "hero" : item.score >= 30 ? "standard" : "compact");
}

function validPlacement(value: RecommendationPlacement | undefined): value is RecommendationPlacement {
  return Boolean(value
    && value.constellationKey
    && Number.isFinite(value.worldX)
    && Number.isFinite(value.worldY)
    && Number.isFinite(value.salt)
    && Number.isFinite(value.orbitOrder));
}

/** Only published media projects belong on the v2 canvas. */
export function hasFeedContent(item: RecommendationFeedItem): boolean {
  const assets = item.post.assets || [];
  return assets.length >= 1 && assets.length <= 12 && assets.every((asset) =>
    asset.sourceKind === "UPLOAD"
      && asset.status === "READY"
      && Boolean(asset.url || asset.previewUrl || asset.assetId || asset.variants?.some((variant) => variant.url)),
  );
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
    for (let x = center.x - radius; x <= center.x + radius; x += 1) chunks.push({ x, y });
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

function entryOrder(a: RecommendationEntry, b: RecommendationEntry): number {
  const aPlacement = a.item.placement;
  const bPlacement = b.item.placement;
  if (validPlacement(aPlacement) && validPlacement(bPlacement)) {
    return aPlacement.constellationKey.localeCompare(bPlacement.constellationKey)
      || aPlacement.orbitOrder - bPlacement.orbitOrder
      || a.item.post.id.localeCompare(b.item.post.id);
  }
  return a.chunk.chunkX - b.chunk.chunkX
    || a.chunk.chunkY - b.chunk.chunkY
    || a.item.post.id.localeCompare(b.item.post.id);
}

export function buildRecommendationCanvasNodes(chunks: RecommendationFeedResponse[]): CanvasPostNode[] {
  const entries = chunks.flatMap((chunk) => chunk.items.map((item) => ({ chunk, item }))).sort(entryOrder);
  const seen = new Set<string>();
  const nodes: CanvasPostNode[] = [];

  for (const entry of entries) {
    const { item, chunk } = entry;
    if (seen.has(item.post.id) || !hasFeedContent(item)) continue;
    const placement = validPlacement(item.placement) ? item.placement : undefined;
    // Server coordinates are the map contract. A partial/old response is
    // intentionally invisible instead of being placed by client arrival order.
    if (!placement) continue;
    seen.add(item.post.id);

    const position = { x: Math.round(placement.worldX), y: Math.round(placement.worldY) };
    const size = placement.sizePreset === "S"
      ? { width: 288, height: 230 }
      : placement.sizePreset === "L"
        ? { width: 432, height: 344 }
        : { width: FEED_NODE_WIDTH, height: FEED_NODE_HEIGHT };
    nodes.push({
      id: item.post.id,
      item,
      chunkKey: feedChunkKey(chunk.chunkX, chunk.chunkY),
      cell: item.cell,
      placement,
      constellationKey: placement.constellationKey,
      orbitOrder: placement.orbitOrder,
      ...position,
      ...size,
      mediaType: mediaType(item),
      emphasis: resolvedEmphasis(item),
    });
  }
  return nodes;
}

export function screenPosition(node: Pick<CanvasPostNode, "x" | "y">, camera: FeedCamera, viewportWidth: number, viewportHeight: number) {
  return {
    left: node.x - camera.x + viewportWidth / 2,
    top: node.y - camera.y + viewportHeight / 2,
  };
}

export function feedNodesOverlap(a: Pick<CanvasPostNode, "x" | "y" | "width" | "height">, b: Pick<CanvasPostNode, "x" | "y" | "width" | "height">): boolean {
  return a.x < b.x + b.width + FEED_NODE_GAP
    && a.x + a.width + FEED_NODE_GAP > b.x
    && a.y < b.y + b.height + FEED_NODE_GAP
    && a.y + a.height + FEED_NODE_GAP > b.y;
}
