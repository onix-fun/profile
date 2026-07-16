import type { PostAsset } from "@/api/types";
import { ensureProjectLayouts, projectAssetBox } from "@/features/mediaProject/projectLayout";

export interface MediaBoardItem {
  asset: PostAsset;
  index: number;
  left: number;
  top: number;
  width: number;
  height: number;
  zIndex: number;
}

interface SourceItem {
  asset: PostAsset;
  index: number;
  x: number;
  y: number;
  width: number;
  height: number;
  centerX: number;
  centerY: number;
}

const PREVIEW_LIMIT = 6;
const PREVIEW_PADDING = 6;
const PREVIEW_CONTENT_SIZE = 100 - PREVIEW_PADDING * 2;
const MIN_OVERLAP = .12;

/**
 * Projects are collision-free on their full page. The recommendation preview
 * is a projection of that authored geometry: sizes stay proportional and only
 * distances between centers are compressed until the selected media forms one
 * connected collage. No post-id randomness, mirroring, or rotation is used.
 */
export function buildFeedProjectPreview(assets: PostAsset[], limit = PREVIEW_LIMIT): MediaBoardItem[] {
  if (!assets.length || limit <= 0) return [];
  const normalized = ensureProjectLayouts(assets);
  const source = normalized.map((asset, index): SourceItem => {
    const box = projectAssetBox(asset);
    return {
      asset,
      index,
      x: box.x,
      y: box.y,
      width: box.width,
      height: box.height,
      centerX: box.x + box.width / 2,
      centerY: box.y + box.height / 2,
    };
  });
  const bounds = sourceBounds(source);
  const projectCenterX = (bounds.left + bounds.right) / 2;
  const projectCenterY = (bounds.top + bounds.bottom) / 2;
  const selected = [...source]
    .sort((left, right) => {
      const leftDistance = squaredDistance(left.centerX, left.centerY, projectCenterX, projectCenterY);
      const rightDistance = squaredDistance(right.centerX, right.centerY, projectCenterX, projectCenterY);
      return leftDistance - rightDistance || left.index - right.index;
    })
    .slice(0, Math.min(PREVIEW_LIMIT, Math.max(0, limit)))
    .sort((left, right) => left.index - right.index);

  const compression = selected.length <= 1 ? 1 : connectedCompression(selected, projectCenterX, projectCenterY);
  const compressed = selected.map((item) => {
    const centerX = (item.centerX - projectCenterX) * compression;
    const centerY = (item.centerY - projectCenterY) * compression;
    return {
      ...item,
      x: centerX - item.width / 2,
      y: centerY - item.height / 2,
    };
  });
  const previewBounds = sourceBounds(compressed);
  const scale = Math.min(
    PREVIEW_CONTENT_SIZE / Math.max(1, previewBounds.right - previewBounds.left),
    PREVIEW_CONTENT_SIZE / Math.max(1, previewBounds.bottom - previewBounds.top),
  );
  const renderedWidth = (previewBounds.right - previewBounds.left) * scale;
  const renderedHeight = (previewBounds.bottom - previewBounds.top) * scale;
  const offsetX = PREVIEW_PADDING + (PREVIEW_CONTENT_SIZE - renderedWidth) / 2 - previewBounds.left * scale;
  const offsetY = PREVIEW_PADDING + (PREVIEW_CONTENT_SIZE - renderedHeight) / 2 - previewBounds.top * scale;

  return compressed.map((item) => ({
    asset: item.asset,
    index: item.index,
    left: roundPreview(offsetX + item.x * scale),
    top: roundPreview(offsetY + item.y * scale),
    width: roundPreview(item.width * scale),
    height: roundPreview(item.height * scale),
    // Author order is also the lightbox order. The first asset is the hero and
    // therefore remains above every later selected asset.
    zIndex: assets.length - item.index,
  }));
}

function connectedCompression(items: SourceItem[], centerX: number, centerY: number): number {
  // Work from the least compressed projection toward a denser one and keep the
  // first factor satisfying the visual overlap contract. The finite probe set
  // makes the result stable across browsers.
  for (let step = 100; step >= 0; step -= 1) {
    const factor = step / 100;
    const boxes = items.map((item) => {
      const projectedCenterX = (item.centerX - centerX) * factor;
      const projectedCenterY = (item.centerY - centerY) * factor;
      return {
        x: projectedCenterX - item.width / 2,
        y: projectedCenterY - item.height / 2,
        width: item.width,
        height: item.height,
      };
    });
    if (overlapGraphConnected(boxes)) return factor;
  }
  return 0;
}

function overlapGraphConnected(boxes: Array<{ x: number; y: number; width: number; height: number }>): boolean {
  if (boxes.length <= 1) return true;
  const visited = new Set([0]);
  const queue = [0];
  while (queue.length) {
    const current = queue.shift()!;
    boxes.forEach((candidate, index) => {
      if (visited.has(index) || overlapDepth(boxes[current], candidate) < MIN_OVERLAP) return;
      visited.add(index);
      queue.push(index);
    });
  }
  return visited.size === boxes.length;
}

function overlapDepth(
  left: { x: number; y: number; width: number; height: number },
  right: { x: number; y: number; width: number; height: number },
): number {
  const overlapX = Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x);
  const overlapY = Math.min(left.y + left.height, right.y + right.height) - Math.max(left.y, right.y);
  if (overlapX <= 0 || overlapY <= 0) return 0;
  return Math.min(overlapX / Math.min(left.width, right.width), overlapY / Math.min(left.height, right.height));
}

function sourceBounds(items: Array<{ x: number; y: number; width: number; height: number }>) {
  return {
    left: Math.min(...items.map((item) => item.x)),
    top: Math.min(...items.map((item) => item.y)),
    right: Math.max(...items.map((item) => item.x + item.width)),
    bottom: Math.max(...items.map((item) => item.y + item.height)),
  };
}

function squaredDistance(x: number, y: number, centerX: number, centerY: number): number {
  return (x - centerX) ** 2 + (y - centerY) ** 2;
}

function roundPreview(value: number): number {
  return Math.round(value * 10_000) / 10_000;
}
