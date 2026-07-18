import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ProfileService } from "@/shared/api/profileService";
import SearchPage from "@/pages/search/SearchPage.vue";

vi.mock("primevue/usetoast", () => ({
  useToast: () => ({ add: vi.fn() }),
}));

vi.mock("@/shared/api/contentService", () => ({
  ContentService: {
    recommendationFeed: vi.fn().mockResolvedValue({ items: [] }),
  },
}));

vi.mock("@/shared/api/profileService", () => ({
  ProfileService: {
    searchSuggest: vi.fn().mockResolvedValue({ query: "media", suggestions: [], partialErrors: [] }),
    search: vi.fn().mockResolvedValue({
      query: "media",
      items: [
        {
          type: "POST",
          id: "post-1",
          title: "Media search result",
          snippet: "A compact result summary",
          owner: { id: "owner-1", ownerType: "USER", username: "alice", displayName: "Alice" },
          url: "/p/post-1",
          score: 1,
          createdAt: "2026-07-12T12:00:00Z",
          postId: "post-1",
          commentId: null,
          tags: ["media"],
          meta: { likeCount: "4" },
          providerKey: "content",
          providerLabel: "Content",
          typeLabel: "Post",
          thumbnailUrl: "https://cdn.test/post.jpg",
          highlights: ["A compact result summary"],
        },
      ],
      nextCursor: "cursor-2",
      partialErrors: ["comments unavailable"],
      facets: [
        { group: "type", value: "posts", label: "Posts", count: 1, selected: false },
        { group: "provider", value: "content", label: "Content", count: 1, selected: false },
        { group: "tag", value: "media", label: "#media", count: 1, selected: false },
      ],
      providerStatuses: [
        { providerKey: "content", label: "Content", status: "partial", message: "comments unavailable" },
      ],
    }),
  },
}));

beforeEach(() => {
  vi.clearAllMocks();
  if (!window.localStorage) {
    const store = new Map<string, string>();
    Object.defineProperty(window, "localStorage", {
      value: {
        getItem: (key: string) => store.get(key) || null,
        setItem: (key: string, value: string) => store.set(key, value),
        removeItem: (key: string) => store.delete(key),
        clear: () => store.clear(),
      },
      configurable: true,
    });
  }
  window.localStorage.clear();
});

describe("SearchPage", () => {
  it("renders command center search with facets, provider status, and result previews", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/search", component: SearchPage }],
    });
    await router.push("/search?q=media");
    await router.isReady();

    const wrapper = mount(SearchPage, {
      global: { plugins: [router] },
    });
    await flushPromises();

    expect(ProfileService.search).toHaveBeenCalledWith(expect.objectContaining({ q: "media", limit: 30 }));
    expect(wrapper.find(".search-layout").exists()).toBe(true);
    expect(wrapper.find(".filter-sidebar").text()).toContain("Content type");
    expect(wrapper.find(".provider-strip").text()).toContain("Content partial");
    expect(wrapper.find(".result-row").text()).toContain("Media search result");
    expect(wrapper.find(".result-thumb img").attributes("src")).toBe("https://cdn.test/post.jpg");
    expect(wrapper.find(".load-more").exists()).toBe(true);
  });
});
