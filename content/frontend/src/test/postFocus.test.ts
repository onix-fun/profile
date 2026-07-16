import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import PostOverlay from "@/features/post/PostOverlay.vue";
import { ContentService } from "@/api/contentService";

vi.mock("primevue/usetoast", () => ({
  useToast: () => ({ add: vi.fn() }),
}));

vi.mock("@/api/contentService", () => ({
  ContentService: {
    post: vi.fn(),
    currentActor: vi.fn(),
    commentThread: vi.fn(),
    recordView: vi.fn().mockResolvedValue(true),
    textFromBlocks: vi.fn(() => ""),
  },
}));

describe("full project route", () => {
  it("renders the complete fixed-scale project canvas and keeps comments on their route", async () => {
    vi.mocked(ContentService.post).mockResolvedValue({
      id: "focus-1",
      authorId: "author-1",
      ownerId: "author-1",
      ownerType: "USER",
      authorName: "orbiter",
      title: "Пластиковый спутник",
      text: "",
      blocks: [{ id: "text", type: "TEXT", data: { text: "Исходный блок" } }],
      tags: [],
      visibility: "PUBLIC",
      likeCount: 0,
      likedByViewer: false,
      allowComments: true,
    });
    vi.mocked(ContentService.currentActor).mockResolvedValue({
      user: { id: "viewer", username: "viewer" },
      activeOwner: { id: "viewer", username: "viewer", ownerType: "USER" },
    });
    vi.mocked(ContentService.commentThread).mockClear();

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/p/:postId", component: PostOverlay }],
    });
    await router.push("/p/focus-1");
    await router.isReady();

    const wrapper = mount(PostOverlay, {
      global: {
        plugins: [router],
        stubs: { ContentDocument: { template: "<div class='content-document-stub' />" } },
      },
    });
    await vi.waitFor(() => expect(wrapper.find(".project-canvas").exists()).toBe(true));

    expect(ContentService.commentThread).not.toHaveBeenCalled();
    expect(wrapper.find(".comments-panel").exists()).toBe(false);
    expect(wrapper.text()).toContain("Пластиковый спутник");
    expect(wrapper.find("[aria-label='Комментарии']").exists()).toBe(true);
    expect(wrapper.find(".open-full-post").exists()).toBe(false);

    wrapper.unmount();
  });
});
