import { describe, expect, it } from "vitest";
import type { FeedItem } from "@/api/types";
import { buildContentBlocks, stripCreatorDirectives } from "@/features/contentDocument/contentModel";
import { composePostScene, postScenePalette } from "@/features/content/postScene";
import { buildFeedProjectPreview } from "@/features/mediaProject/feedProjectPreview";
import { autoArrangeProject, projectLayoutsValid, reconcileProjectLayouts } from "@/features/mediaProject/projectLayout";
import type { PostAsset } from "@/api/types";

const post: FeedItem["post"] = {
  id: "aurora-post",
  authorId: "alice",
  text: "# Яркая идея #дизайн",
  tags: ["дизайн", "motion"],
  visibility: "PUBLIC",
  blocks: [
    { id: "text", type: "TEXT", data: { text: "# Яркая идея #дизайн" } },
    { id: "quote", type: "QUOTE", data: { quote: "Двигайся смелее" } },
    { id: "poll", type: "POLL", data: { question: "Продолжаем?", options: ["Да", "Ещё бы"] } },
  ],
};

describe("creator redesign primitives", () => {
  it("composes the same post scene and palette deterministically", () => {
    expect(composePostScene(post)).toEqual(composePostScene(post));
    expect(postScenePalette(post).colors).toEqual(postScenePalette(post).colors);
    expect(composePostScene(post)).toHaveLength(3);
  });

  it("keeps legacy markdown editable while extracting creator directives", () => {
    const markdown = "Вступление\n\n:::onix POLL {\"question\":\"Выбор?\",\"options\":[\"A\",\"B\"]}\n\nФинал";
    const blocks = buildContentBlocks(markdown, []);
    expect(blocks.map((block) => block.type)).toEqual(["TEXT", "POLL"]);
    expect(blocks[0].data.text).toContain("Вступление");
    expect(stripCreatorDirectives(markdown)).not.toContain(":::onix");
  });

  it("overlaps media only in feed collage and keeps the full project collision free", () => {
    const assets: PostAsset[] = [0, 1, 2, 3].map((index) => ({ id:`asset-${index}`,assetId:`asset-${index}`,kind:"IMAGE",sourceKind:"UPLOAD",status:"READY",width:1200,height:800 }));
    const collage = buildFeedProjectPreview(assets, 6);
    const overlaps = collage.some((left, index) => collage.slice(index + 1).some((right) => left.left < right.left + right.width && left.left + left.width > right.left && left.top < right.top + right.height && left.top + left.height > right.top));
    expect(overlaps).toBe(true);
    expect(projectLayoutsValid(autoArrangeProject(assets))).toBe(true);
  });

  it("repairs a project when verified source proportions invalidate a fallback layout", () => {
    const assets: PostAsset[] = [
      {
        id: "portrait", assetId: "portrait", kind: "IMAGE", sourceKind: "UPLOAD", status: "AVAILABLE",
        width: 564, height: 909,
        layout: { assetId: "portrait", x: 0, y: 0, sizePreset: "L", layoutVersion: 1 },
      },
      {
        id: "square", assetId: "square", kind: "IMAGE", sourceKind: "UPLOAD", status: "AVAILABLE",
        width: 338, height: 338,
        layout: { assetId: "square", x: -324, y: 439, sizePreset: "M", layoutVersion: 1 },
      },
    ];
    expect(projectLayoutsValid(assets)).toBe(false);
    const repaired = reconcileProjectLayouts(assets);
    expect(projectLayoutsValid(repaired)).toBe(true);
    expect(repaired[0].layout).toEqual(assets[0].layout);
    expect(repaired[1].layout).not.toEqual(assets[1].layout);
  });
});
