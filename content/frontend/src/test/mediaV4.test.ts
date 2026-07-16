import { describe, expect, it } from "vitest";
import { reactive } from "vue";
import { acceptedMediaFiles, assetNeedsAction, mediaPublishability, mergeCanonicalEditorAssets } from "@/features/editor/mediaEditor";
import { mediaDraftSnapshot } from "@/features/editor/mediaDraftRecovery";

describe("media v4 drafts", () => {
  it("accepts only browser-native sources that need no draft conversion", () => {
    const files = [
      new File(["jpeg"], "photo.jpg", { type: "image/jpeg" }),
      new File(["heic"], "photo.heic", { type: "image/heic" }),
      new File(["mov"], "clip.mov", { type: "video/quicktime" }),
      new File(["mp4"], "clip.mp4", { type: "video/mp4" }),
    ];
    expect(acceptedMediaFiles(files).map((file) => file.name)).toEqual(["photo.jpg", "clip.mp4"]);
  });

  it("allows publication intent while source verification is still asynchronous", () => {
    expect(mediaPublishability({
      assets: [{ id: "asset", assetId: "asset", kind: "IMAGE", sourceKind: "UPLOAD", status: "VERIFYING" }],
      tags: ["design"],
      allowComments: true,
    })).toBeNull();
  });

  it("does not confuse a cancelled old processing run with a broken source", () => {
    const asset = {
      id: "asset",
      assetId: "asset",
      kind: "IMAGE" as const,
      sourceKind: "UPLOAD" as const,
      status: "CANCELLED" as const,
      sourceStatus: "AVAILABLE" as const,
      processingStatus: "CANCELLED" as const,
      deliveryStatus: "NONE" as const,
    };
    expect(mediaPublishability({ assets: [asset], tags: ["design"], allowComments: true })).toBeNull();
    expect(assetNeedsAction(asset)).toBe(false);
  });

  it("shows processing failures as an action on the exact media", () => {
    const asset = {
      id: "asset",
      assetId: "asset",
      kind: "VIDEO" as const,
      sourceKind: "UPLOAD" as const,
      status: "FAILED" as const,
      sourceStatus: "AVAILABLE" as const,
      processingStatus: "FAILED" as const,
      deliveryStatus: "NONE" as const,
      failure: { code: "TRANSCODE_FAILED", permanent: false, userMessage: "Не удалось подготовить видео." },
    };
    expect(assetNeedsAction(asset)).toBe(true);
  });

  it("keeps the local object URL when autosave returns canonical media", () => {
    const local = [{
      id: "item", assetId: "asset", kind: "IMAGE" as const, sourceKind: "UPLOAD" as const,
      status: "VERIFYING" as const, previewUrl: "blob:http://content/local-preview",
    }];
    const canonical = [{
      id: "item", assetId: "asset", kind: "IMAGE" as const, sourceKind: "UPLOAD" as const,
      status: "AVAILABLE" as const, url: "/content-media/assets/asset/source", width: 1200, height: 800,
    }];
    expect(mergeCanonicalEditorAssets(local, canonical)[0]).toMatchObject({
      status: "AVAILABLE",
      width: 1200,
      previewUrl: "blob:http://content/local-preview",
    });
  });

  it("turns reactive editor state into an IndexedDB-cloneable snapshot", () => {
    const state = reactive({
      assets: [{
        id: "item", assetId: "asset", kind: "IMAGE" as const, sourceKind: "UPLOAD" as const,
        status: "AVAILABLE" as const, previewUrl: "blob:http://content/local-preview",
        layout: { assetId: "asset", x: 1, y: 2, sizePreset: "M" as const, layoutVersion: 1 as const },
      }],
      tags: ["design"], allowComments: true,
    });
    const snapshot = mediaDraftSnapshot(state);
    expect(() => structuredClone(snapshot)).not.toThrow();
    expect(snapshot.assets[0].previewUrl).toBeUndefined();
  });
});
