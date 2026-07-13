import { beforeEach, describe, expect, it, vi } from "vitest";

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
});
