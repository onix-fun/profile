import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const post = vi.fn();
const interceptors = {
  request: { use: vi.fn() },
  response: { use: vi.fn() },
};

vi.mock("axios", () => ({
  default: {
    create: vi.fn(() => ({
      post,
      interceptors,
    })),
    isAxiosError: vi.fn(() => false),
  },
}));

describe("ContentService GraphQL transport", () => {
  beforeEach(() => {
    post.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts to the configured GraphQL base URL without appending graphql twice", async () => {
    const { ContentService } = await import("@/api/contentService");
    post.mockResolvedValueOnce({
      data: {
        data: {
          currentActor: {
            user: { id: "viewer", username: "viewer" },
            activeOwner: { id: "viewer", ownerType: "USER", username: "viewer" },
          },
        },
      },
    });

    await ContentService.currentActor();

    expect(post).toHaveBeenCalledWith("", {
      operationName: "currentActor",
      variables: {},
    }, {
      headers: { "X-Onix-Optional-Auth": "1" },
    });
  });

  it("refreshes editor assets with one owner-scoped batch operation", async () => {
    const { ContentService } = await import("@/api/contentService");
    post.mockResolvedValueOnce({ data: { data: { editorMediaAssets: [
      { assetId: "a", asset: { id: "a", assetId: "a", kind: "IMAGE", sourceKind: "UPLOAD", status: "AVAILABLE" } },
      { assetId: "b", failureCode: "MEDIA_UNAVAILABLE" },
    ] } } });

    const result = await ContentService.editorMediaAssets(["a", "a", "b"]);

    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]?.[1]).toMatchObject({ operationName: "editorMediaAssets", variables: { assetIds: ["a", "b"] } });
    expect(result).toHaveLength(2);
  });

  it("uploads a browser file to presigned parts before completing the media asset", async () => {
    const { ContentService } = await import("@/api/contentService");
    const put = vi.fn().mockResolvedValue({ ok: true, headers: new Headers({ etag: '"etag-1"' }) });
    vi.stubGlobal("fetch", put);
    post
      .mockResolvedValueOnce({
        data: {
          data: {
            initMediaAssetUpload: {
              asset: { id: "asset-1", assetId: "asset-1", kind: "IMAGE", sourceKind: "UPLOAD", status: "UPLOADING" },
              sessionId: "session-1",
              parts: [{ partNumber: 1, url: "https://uploads.example.test/part-1", headers: { "x-upload-token": "token" } }],
            },
          },
        },
      })
      .mockResolvedValueOnce({
        data: {
          data: {
            completeMediaAssetUpload: {
              id: "asset-1",
              assetId: "asset-1",
              kind: "IMAGE",
              sourceKind: "UPLOAD",
              status: "PROCESSING",
            },
          },
        },
      });

    const local = { id: "local-1", clientId: "local-1", kind: "IMAGE" as const, sourceKind: "UPLOAD" as const, status: "UPLOADING" as const, previewUrl: "blob:preview" };
    const uploaded = await ContentService.uploadMediaAsset(new File(["image-bytes"], "art.png", { type: "image/png" }), local);

    expect(put).toHaveBeenCalledWith("https://uploads.example.test/part-1", expect.objectContaining({
      method: "PUT",
      credentials: "omit",
    }));
    expect(post.mock.calls[0]?.[1]).toMatchObject({
      operationName: "initMediaAssetUpload",
      variables: { input: { mimeType: "image/png", expectedSize: 11, partsCount: 1, kind: "IMAGE" } },
    });
    expect(post.mock.calls[1]?.[1]).toMatchObject({
      operationName: "completeMediaAssetUpload",
      variables: { input: { assetId: "asset-1", sessionId: "session-1", parts: [{ partNumber: 1, etag: '"etag-1"' }] } },
    });
    expect(uploaded).toMatchObject({ id: "local-1", assetId: "asset-1", status: "PROCESSING", previewUrl: "blob:preview" });
  });

  it("stops a direct upload when bucket CORS does not expose the multipart ETag", async () => {
    const { ContentService } = await import("@/api/contentService");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, headers: new Headers() }));
    post.mockResolvedValueOnce({
      data: {
        data: {
          initMediaAssetUpload: {
            asset: { id: "asset-1", assetId: "asset-1", kind: "IMAGE", sourceKind: "UPLOAD", status: "UPLOADING" },
            sessionId: "session-1",
            parts: [{ partNumber: 1, url: "https://uploads.example.test/part-1" }],
          },
        },
      },
    });

    await expect(ContentService.uploadMediaAsset(new File(["image"], "art.png", { type: "image/png" })))
      .rejects.toThrow("ETag");
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("reports the storage HTTP status when a signed part is rejected", async () => {
    const { ContentService } = await import("@/api/contentService");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 403, headers: new Headers() }));
    post.mockResolvedValueOnce({
      data: {
        data: {
          initMediaAssetUpload: {
            asset: { id: "asset-1", assetId: "asset-1", kind: "IMAGE", sourceKind: "UPLOAD", status: "UPLOADING" },
            sessionId: "session-1",
            parts: [{ partNumber: 1, url: "https://uploads.example.test/part-1" }],
          },
        },
      },
    });

    await expect(ContentService.uploadMediaAsset(new File(["image"], "art.png", { type: "image/png" })))
      .rejects.toThrow("HTTP 403");
    expect(post).toHaveBeenCalledTimes(1);
  });
});
