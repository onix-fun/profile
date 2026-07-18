import { describe, expect, it } from "vitest";
import { ContentService } from "@/shared/api/contentService";

describe("profile content media URLs", () => {
  it("routes stable Content media paths to the Content origin", () => {
    const source = ContentService.mediaSource({
      type: "IMAGE",
      data: { previewUrl: "/content-media/assets/asset/1/image-480" },
    });

    expect(source).toBe("http://content.onix.localhost:8088/content-media/assets/asset/1/image-480");
  });
});
