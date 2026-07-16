import type { AssetSizePreset, PostAsset, PostAssetLayout } from "@/api/types";
import { assetKind } from "@/features/mediaProject/mediaAssets";

export const PROJECT_WORLD_MIN = -2048;
export const PROJECT_WORLD_MAX = 2048;
export const PROJECT_ASSET_GAP = 24;

export interface AssetBox { id: string; x: number; y: number; width: number; height: number; }
export interface CollisionResult { assets: PostAsset[]; accepted: boolean; }

export function ensureProjectLayouts(assets: PostAsset[]): PostAsset[] {
  let resolved: PostAsset[] = assets.filter((asset) => Boolean(asset.layout)).map((asset) => ({ ...asset, layout: { ...asset.layout! } }));
  assets.filter((asset) => !asset.layout).forEach((asset, index) => {
    const placed = nearestFreeLayout(asset, resolved, index);
    resolved.push({ ...asset, layout: placed });
  });
  const byId = new Map(resolved.map((asset) => [asset.id, asset]));
  return assets.map((asset) => byId.get(asset.id)!);
}

export function autoArrangeProject(assets: PostAsset[]): PostAsset[] {
  let arranged: PostAsset[] = [];
  assets.forEach((asset, index) => { arranged.push({ ...asset, layout: nearestFreeLayout(asset, arranged, index) }); });
  return arranged;
}

/**
 * Preserve every still-valid authored position, but move an item to the
 * nearest deterministic free slot when newly discovered source dimensions
 * make its old box collide. Feed collages deliberately use a different
 * overlapping layout helper and never call this function.
 */
export function reconcileProjectLayouts(assets: PostAsset[]): PostAsset[] {
  const normalized = ensureProjectLayouts(assets);
  const placed: PostAsset[] = [];
  normalized.forEach((asset, index) => {
    const box = projectAssetBox(asset);
    const canKeep = withinWorld(box) && placed.every((other) => !overlap(box, projectAssetBox(other)));
    placed.push(canKeep ? asset : { ...asset, layout: nearestFreeLayout(asset, placed, index) });
  });
  const byId = new Map(placed.map((asset) => [asset.id, asset]));
  return assets.map((asset) => byId.get(asset.id)!);
}

export function projectAssetBox(asset: PostAsset): AssetBox {
  const layout = asset.layout || defaultLayout(asset, 0);
  if (assetKind(asset) === "AUDIO") {
    const [width, height] = layout.sizePreset === "S" ? [280, 96] : layout.sizePreset === "L" ? [560, 128] : [420, 112];
    return { id: asset.id, x: layout.x, y: layout.y, width, height };
  }
  const edge = layout.sizePreset === "S" ? 240 : layout.sizePreset === "L" ? 520 : 360;
  const rawAspect = asset.width && asset.height ? asset.width / asset.height : assetKind(asset) === "VIDEO" ? 16 / 9 : 4 / 5;
  const aspect = clamp(rawAspect, .45, 2.6);
  const width = Math.max(1, Math.round(aspect >= 1 ? edge : edge * aspect));
  const height = Math.max(1, Math.round(aspect >= 1 ? edge / aspect : edge));
  return { id: asset.id, x: layout.x, y: layout.y, width, height };
}

export function moveProjectAsset(assets: PostAsset[], id: string, x: number, y: number): CollisionResult {
  const normalized = assets.every((asset) => Boolean(asset.layout)) ? assets : ensureProjectLayouts(assets);
  const original = normalized.find((asset) => asset.id === id);
  if (!original?.layout) return { assets: normalized, accepted: false };
  const next = normalized.map((asset) => asset.id === id ? { ...asset, layout: { ...asset.layout!, x: Math.round(x), y: Math.round(y) } } : asset);
  return resolveProjectCollisions(next, id, x - original.layout.x, y - original.layout.y);
}

export function resizeProjectAsset(assets: PostAsset[], id: string, sizePreset: AssetSizePreset): CollisionResult {
  const normalized = assets.every((asset) => Boolean(asset.layout)) ? assets : ensureProjectLayouts(assets);
  const next = normalized.map((asset) => asset.id === id ? { ...asset, layout: { ...asset.layout!, sizePreset } } : asset);
  return resolveProjectCollisions(next, id, 1, 0);
}

export function projectBounds(assets: PostAsset[]): { left: number; top: number; right: number; bottom: number } {
  const boxes = ensureProjectLayouts(assets).map(projectAssetBox);
  if (!boxes.length) return { left: 0, top: 0, right: 0, bottom: 0 };
  return {
    left: Math.min(...boxes.map((box) => box.x)),
    top: Math.min(...boxes.map((box) => box.y)),
    right: Math.max(...boxes.map((box) => box.x + box.width)),
    bottom: Math.max(...boxes.map((box) => box.y + box.height)),
  };
}

export function projectLayoutsValid(assets: PostAsset[]): boolean {
  const boxes = ensureProjectLayouts(assets).map(projectAssetBox);
  return boxes.every(withinWorld) && boxes.every((box, index) => boxes.slice(index + 1).every((other) => !overlap(box, other)));
}

function resolveProjectCollisions(assets: PostAsset[], movedId: string, dx: number, dy: number): CollisionResult {
  let working = assets.map((asset) => ({ ...asset, layout: asset.layout ? { ...asset.layout } : null }));
  const queue = [movedId];
  let iterations = 0;
  while (queue.length && iterations < 96) {
    iterations += 1;
    const currentId = queue.shift()!;
    const current = working.find((asset) => asset.id === currentId);
    if (!current?.layout) return { assets, accepted: false };
    const currentBox = projectAssetBox(current);
    const collisions = working
      .filter((asset) => asset.id !== currentId)
      .map((asset) => ({ asset, box: projectAssetBox(asset) }))
      .filter(({ box }) => overlap(currentBox, box))
      .sort((left, right) => left.asset.id.localeCompare(right.asset.id));
    for (const collision of collisions) {
      const pushed = pushOutside(currentBox, collision.box, dx, dy);
      working = working.map((asset) => asset.id === collision.asset.id ? {
        ...asset,
        layout: { ...asset.layout!, x: pushed.x, y: pushed.y },
      } : asset);
      queue.push(collision.asset.id);
    }
  }
  const boxes = working.map(projectAssetBox);
  if (iterations >= 96 || boxes.some((box) => !withinWorld(box)) || boxes.some((box, index) => boxes.slice(index + 1).some((other) => overlap(box, other)))) {
    return { assets, accepted: false };
  }
  return { assets: working, accepted: true };
}

function pushOutside(source: AssetBox, target: AssetBox, dx: number, dy: number): { x: number; y: number } {
  if (Math.abs(dx) >= Math.abs(dy) && dx !== 0) {
    return { x: dx > 0 ? source.x + source.width + PROJECT_ASSET_GAP : source.x - target.width - PROJECT_ASSET_GAP, y: target.y };
  }
  if (dy !== 0) {
    return { x: target.x, y: dy > 0 ? source.y + source.height + PROJECT_ASSET_GAP : source.y - target.height - PROJECT_ASSET_GAP };
  }
  const options = [
    { x: source.x + source.width + PROJECT_ASSET_GAP, y: target.y },
    { x: source.x - target.width - PROJECT_ASSET_GAP, y: target.y },
    { x: target.x, y: source.y + source.height + PROJECT_ASSET_GAP },
    { x: target.x, y: source.y - target.height - PROJECT_ASSET_GAP },
  ];
  return options.sort((a, b) => Math.abs(a.x - target.x) + Math.abs(a.y - target.y) - (Math.abs(b.x - target.x) + Math.abs(b.y - target.y)))[0];
}

function overlap(left: AssetBox, right: AssetBox): boolean {
  return left.x < right.x + right.width + PROJECT_ASSET_GAP
    && left.x + left.width + PROJECT_ASSET_GAP > right.x
    && left.y < right.y + right.height + PROJECT_ASSET_GAP
    && left.y + left.height + PROJECT_ASSET_GAP > right.y;
}

function withinWorld(box: AssetBox): boolean {
  return box.x >= PROJECT_WORLD_MIN && box.y >= PROJECT_WORLD_MIN
    && box.x + box.width <= PROJECT_WORLD_MAX && box.y + box.height <= PROJECT_WORLD_MAX;
}

function defaultLayout(asset: PostAsset, index: number): PostAssetLayout {
  return { assetId: asset.assetId || asset.id, x: (index % 4) * 600 - 900, y: Math.floor(index / 4) * 600 - 600, sizePreset: "M", layoutVersion: 1 };
}

function nearestFreeLayout(asset: PostAsset, placed: PostAsset[], seed: number): PostAssetLayout {
  const preset = asset.layout?.sizePreset || "M";
  for (let probe = 0; probe < 1800; probe += 1) {
    const ring = Math.ceil(Math.sqrt(probe));
    const angle = (seed * .83 + probe * 2.399963) % (Math.PI * 2);
    const candidate: PostAssetLayout = {
      assetId: asset.assetId || asset.id,
      x: Math.round(Math.cos(angle) * ring * 42),
      y: Math.round(Math.sin(angle) * ring * 42),
      sizePreset: preset,
      layoutVersion: 1,
    };
    const candidateBox = projectAssetBox({ ...asset, layout: candidate });
    if (withinWorld(candidateBox) && placed.every((other) => !overlap(candidateBox, projectAssetBox(other)))) return candidate;
  }
  return defaultLayout(asset, seed);
}

function clamp(value: number, min: number, max: number): number { return Math.max(min, Math.min(max, value)); }
