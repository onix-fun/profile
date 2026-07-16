import { describe, expect, it } from "vitest";
import type { AssetSizePreset, PostAsset } from "@/api/types";
import { buildFeedProjectPreview } from "@/features/mediaProject/feedProjectPreview";
import { ensureProjectLayouts, projectAssetBox, projectLayoutsValid, PROJECT_ASSET_GAP } from "@/features/mediaProject/projectLayout";

function image(id: string, x: number, y: number, preset: AssetSizePreset = "M", width = 1200, height = 800): PostAsset {
  return {
    id,
    assetId: id,
    kind: "IMAGE",
    sourceKind: "UPLOAD",
    status: "READY",
    width,
    height,
    layout: { assetId: id, x, y, sizePreset: preset, layoutVersion: 1 },
  };
}

function overlapDepth(left: ReturnType<typeof buildFeedProjectPreview>[number], right: ReturnType<typeof buildFeedProjectPreview>[number]) {
  const overlapX = Math.min(left.left + left.width, right.left + right.width) - Math.max(left.left, right.left);
  const overlapY = Math.min(left.top + left.height, right.top + right.height) - Math.max(left.top, right.top);
  if (overlapX <= 0 || overlapY <= 0) return 0;
  return Math.min(overlapX / Math.min(left.width, right.width), overlapY / Math.min(left.height, right.height));
}

function connected(items: ReturnType<typeof buildFeedProjectPreview>) {
  if (items.length <= 1) return true;
  const visited = new Set([0]);
  const queue = [0];
  while (queue.length) {
    const index = queue.shift()!;
    items.forEach((candidate, candidateIndex) => {
      if (visited.has(candidateIndex) || overlapDepth(items[index], candidate) < .119) return;
      visited.add(candidateIndex);
      queue.push(candidateIndex);
    });
  }
  return visited.size === items.length;
}

describe("feed project preview projection", () => {
  const composition = [
    image("a", -800, -300), image("b", -400, -300, "S"), image("c", 0, -300, "L", 800, 1200), image("d", 400, -300),
    image("e", -800, 300), image("f", -400, 300, "L"), image("g", 0, 300, "S", 700, 1200), image("h", 400, 300),
  ];

  it("selects the six media nearest the full composition center", () => {
    const boxes = composition.map(projectAssetBox);
    const left = Math.min(...boxes.map((box) => box.x));
    const right = Math.max(...boxes.map((box) => box.x + box.width));
    const top = Math.min(...boxes.map((box) => box.y));
    const bottom = Math.max(...boxes.map((box) => box.y + box.height));
    const centerX = (left + right) / 2;
    const centerY = (top + bottom) / 2;
    const expected = boxes.map((box, index) => ({
      id: box.id,
      index,
      distance: (box.x + box.width / 2 - centerX) ** 2 + (box.y + box.height / 2 - centerY) ** 2,
    })).sort((a, b) => a.distance - b.distance || a.index - b.index).slice(0, 6).map((item) => item.id).sort();

    expect(buildFeedProjectPreview(composition).map((item) => item.asset.id).sort()).toEqual(expected);
  });

  it("preserves authored direction and uniform size ratios without rotation", () => {
    const preview = buildFeedProjectPreview(composition);
    const first = preview[0];
    const second = preview[1];
    const firstSource = projectAssetBox(first.asset);
    const secondSource = projectAssetBox(second.asset);
    const sourceDirection = Math.sign((firstSource.x + firstSource.width / 2) - (secondSource.x + secondSource.width / 2));
    const previewDirection = Math.sign((first.left + first.width / 2) - (second.left + second.width / 2));

    expect(previewDirection).toBe(sourceDirection);
    expect(first.width / second.width).toBeCloseTo(firstSource.width / secondSource.width, 3);
    preview.forEach((item) => expect(item).not.toHaveProperty("rotate"));
  });

  it("creates one connected overlap graph and keeps every item inside six percent padding", () => {
    const preview = buildFeedProjectPreview(composition);
    expect(preview).toHaveLength(6);
    expect(connected(preview)).toBe(true);
    preview.forEach((item) => {
      expect(item.left).toBeGreaterThanOrEqual(6 - .001);
      expect(item.top).toBeGreaterThanOrEqual(6 - .001);
      expect(item.left + item.width).toBeLessThanOrEqual(94.001);
      expect(item.top + item.height).toBeLessThanOrEqual(94.001);
    });
  });

  it("keeps the earliest selected media above later author-order items", () => {
    const preview = buildFeedProjectPreview(composition);
    const earliest = [...preview].sort((a, b) => a.index - b.index)[0];
    expect(earliest.zIndex).toBe(Math.max(...preview.map((item) => item.zIndex)));
  });

  it("uses a deterministic collision-free fallback for missing legacy layouts", () => {
    const legacy = composition.slice(0, 4).map((asset) => ({ ...asset, layout: null }));
    const normalized = ensureProjectLayouts(legacy);
    expect(projectLayoutsValid(normalized)).toBe(true);
    expect(PROJECT_ASSET_GAP).toBe(24);
    expect(buildFeedProjectPreview(legacy)).toEqual(buildFeedProjectPreview(legacy));
    expect(connected(buildFeedProjectPreview(legacy))).toBe(true);
  });
});
