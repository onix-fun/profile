import { mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AppShell from "@/features/shell/AppShell.vue";
import StoryViewer from "@/features/stories/StoryViewer.vue";
import { buildFeedCanvasNodes } from "@/features/content/feedCanvasLayout";
import { buildCreatePostInput, emptyPostEditorState, extractHashtags, isPostEditorDirty } from "@/features/editor/postEditor";
import { emptyStoryComposerState, mergeSeenState, reduceStoryComposer, sortStoryRail } from "@/features/stories/storyState";
import { ProfileService } from "@/api/profileService";
import { ContentService } from "@/api/contentService";
import type { FeedItem, StoryRailItem } from "@/api/types";
import { router as appRouter } from "@/app/router";

vi.mock("@/api/profileService", () => ({
  ProfileService: {
    session: vi.fn(),
  },
}));

vi.mock("@/api/contentService", () => ({
	ContentService: {
	    story: vi.fn().mockResolvedValue({
	      id: "story-1",
	      authorId: "alice",
	      author: { username: "alice" },
	      visibility: "PUBLIC",
	      blocks: [
	        { id: "media", type: "IMAGE", data: { src: "https://example.test/story.jpg" } },
	        { id: "caption", type: "TEXT", data: { text: "A compact caption #travel", tags: ["travel"] } },
	      ],
	    }),
	    storyGroup: vi.fn().mockResolvedValue({
	      authorId: "alice",
	      authorName: "alice",
	      author: { username: "alice" },
	      archive: false,
	      startStoryId: "story-1",
	      stories: [{
	        id: "story-1",
	        authorId: "alice",
	        author: { username: "alice" },
	        visibility: "PUBLIC",
	        durationMs: 5000,
	        likeCount: 0,
	        likedByViewer: false,
	        remainingLifeSeconds: 3600,
	        blocks: [
	          { id: "media", type: "IMAGE", data: { src: "https://example.test/story.jpg" } },
	          { id: "caption", type: "TEXT", data: { text: "A compact caption #travel", tags: ["travel"] } },
	        ],
	      }],
	    }),
	    storiesFeed: vi.fn().mockResolvedValue([
	      { authorId: "alice", authorName: "alice", storyIds: ["story-1"], activeCount: 1, seen: false, closeFriends: false, latestAt: "2026-01-01T00:00:00Z" },
	    ]),
    mediaSource: vi.fn((block: { data: Record<string, unknown> }) => {
      const direct = block.data.previewUrl || block.data.url || block.data.src;
      if (typeof direct === "string") return direct;
      return typeof block.data.blobId === "string" ? `/content-media/${encodeURIComponent(block.data.blobId)}` : "";
    }),
    recordStoryView: vi.fn().mockResolvedValue(true),
    likeStory: vi.fn().mockResolvedValue({ storyId: "story-1", liked: true, likeCount: 1 }),
    unlikeStory: vi.fn().mockResolvedValue({ storyId: "story-1", liked: false, likeCount: 0 }),
  },
}));

function feedItem(id: string, score: number, type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" = "TEXT"): FeedItem {
  return {
    post: {
      id,
      authorId: "author",
      title: "Post",
      text: "Body",
      tags: ["design"],
      blocks: type === "TEXT" ? [{ id: `${id}-text`, type: "TEXT", data: { text: "Body" } }] : [{ id: `${id}-media`, type, data: { fileName: "media" } }],
      visibility: "PUBLIC",
    },
    score,
    reasons: ["ranked"],
  };
}

describe("feed canvas layout", () => {
  it("places ranked posts deterministically without persisted positions", () => {
    const first = buildFeedCanvasNodes([feedItem("a", 90, "VIDEO"), feedItem("b", 40)]);
    const second = buildFeedCanvasNodes([feedItem("a", 90, "VIDEO"), feedItem("b", 40)]);

    expect(first).toEqual(second);
    expect(first[0]).toMatchObject({ id: "a", mediaType: "VIDEO", emphasis: "hero" });
    expect(first[0].x).not.toBe(first[1].x);
  });
});

describe("markdown post editor", () => {
  it("maps markdown, hashtags and attachments to createPost input", () => {
    let state = emptyPostEditorState();
    state.markdown = "Hello **canvas** #Design\n\n[clip.webm](media:local-1)";
    state.allowComments = false;
    state.attachments = [{
      id: "local-1",
      file: new File(["video"], "clip.webm", { type: "video/webm" }),
      url: "blob:clip",
      type: "VIDEO",
    }];

    const input = buildCreatePostInput(state);

    expect(input.tags).toEqual(["design"]);
    expect(input.allowComments).toBe(false);
    expect(input.text).toContain("Hello **canvas**");
    expect(input.blocks.map((block) => block.type)).toEqual(["TEXT", "VIDEO"]);
  });

  it("extracts unique hashtags from markdown", () => {
    expect(extractHashtags("One #Design and #design plus #UX")).toEqual(["design", "ux"]);
  });

  it("detects dirty editor state before discard", () => {
    const state = emptyPostEditorState();
    expect(isPostEditorDirty(state)).toBe(false);
    state.markdown = "Draft";
    expect(isPostEditorDirty(state)).toBe(true);
  });

  it("builds same-origin media URLs from persisted blob ids", () => {
    expect(ContentService.mediaSource({
      id: "image",
      type: "IMAGE",
      data: { blobId: "blob/with spaces" },
    })).toBe("/content-media/blob%2Fwith%20spaces");
  });
});

describe("story rail state", () => {
  beforeEach(() => window.localStorage.clear());

  it("merges seen state and keeps unseen stories first", () => {
    const items: StoryRailItem[] = [
      { authorId: "old", authorName: "Old", storyIds: ["old-story"], activeCount: 1, seen: false, closeFriends: false, latestAt: "2026-01-01T00:00:00Z" },
      { authorId: "new", authorName: "New", storyIds: ["new-story"], activeCount: 1, seen: false, closeFriends: true, latestAt: "2026-01-02T00:00:00Z" },
    ];
    window.localStorage.setItem("story-seen:new-story", "true");

    const sorted = sortStoryRail(mergeSeenState(items));

    expect(sorted.map((item) => item.authorId)).toEqual(["old", "new"]);
    expect(sorted[1].seen).toBe(true);
  });
});

describe("story composer state", () => {
  it("moves from idle to recording to edit with one media item", () => {
    let state = emptyStoryComposerState();
    state = reduceStoryComposer(state, { type: "START_RECORDING" });
    expect(state.status).toBe("recording");

    state = reduceStoryComposer(state, { type: "STOP_RECORDING", mediaReady: true });

    expect(state.status).toBe("edit");
    expect(state.mediaReady).toBe(true);
  });
});

describe("story viewer caption", () => {
  it("renders caption as a collapsible bottom sheet", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/story/:storyId", component: StoryViewer }],
    });
    await router.push("/story/story-1");
    await router.isReady();

    const wrapper = mount(StoryViewer, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".caption-sheet").exists()).toBe(true));
    const sheet = wrapper.find(".caption-sheet");
    expect(sheet.text()).toContain("A compact caption #travel");
    await sheet.trigger("click");
    expect(sheet.classes()).toContain("open");

    wrapper.unmount();
  });

  it("likes the active story and updates the button state", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/story/:storyId", component: StoryViewer }],
    });
    await router.push("/story/story-1");
    await router.isReady();

    const wrapper = mount(StoryViewer, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".caption-sheet").exists()).toBe(true));
    await wrapper.find(".story-like").trigger("click");

    expect(ContentService.likeStory).toHaveBeenCalledWith("story-1");
    await vi.waitFor(() => expect(wrapper.find(".story-like").text()).toContain("1"));
    expect(wrapper.find(".story-like").classes()).toContain("active");

    wrapper.unmount();
  });
});

describe("route auth guard", () => {
  it("checks session before entering app routes", async () => {
    vi.mocked(ProfileService.session).mockResolvedValue({
      id: "1",
      username: "alice",
      firstName: "Alice",
    });

    await appRouter.push("/search");
    await appRouter.isReady();

    expect(ProfileService.session).toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).toBe("/search");
  });

  it("aborts navigation when the session request redirects to Account", async () => {
    vi.mocked(ProfileService.session).mockRejectedValue(new Error("AUTH_REQUIRED"));

    const result = await appRouter.push("/post/new");

    expect(result).toBeTruthy();
    expect(appRouter.currentRoute.value.path).not.toBe("/post/new");
  });
});

describe("app shell", () => {
  it("hides the home logo and renders avatar menu navigation", async () => {
    vi.mocked(ProfileService.session).mockResolvedValue({
      id: "1",
      username: "alice",
      firstName: "Alice",
      avatarUrl: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div />" } },
        { path: "/u/me", component: { template: "<div />" } },
        { path: "/search", component: { template: "<div />" } },
      ],
    });
    router.push("/");
    await router.isReady();

    const wrapper = mount(AppShell, {
      global: { plugins: [router] },
      slots: { default: "<div>page</div>" },
    });
    await Promise.resolve();
    await wrapper.find(".avatar-menu-button").trigger("click");

    expect(wrapper.find(".brand-mark").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("Guest");
    expect(wrapper.text()).toContain("Feed");
    expect(wrapper.text()).toContain("Create post");
    expect(wrapper.text()).toContain("Create story");
    expect(wrapper.text()).toContain("Settings");
    expect(wrapper.text()).not.toContain("Logout");
  });

  it("keeps the logo on secondary app routes", async () => {
    vi.mocked(ProfileService.session).mockResolvedValue({
      id: "1",
      username: "alice",
      firstName: "Alice",
      avatarUrl: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div />" } },
        { path: "/search", component: { template: "<div />" } },
      ],
    });
    router.push("/search");
    await router.isReady();

    const wrapper = mount(AppShell, {
      global: { plugins: [router] },
      slots: { default: "<div>search</div>" },
    });
    await Promise.resolve();

    expect(wrapper.find(".brand-mark").exists()).toBe(true);
    expect(wrapper.text()).toContain("Onix");
  });

  it("hides shell chrome on focused creation routes", async () => {
    vi.mocked(ProfileService.session).mockResolvedValue({
      id: "1",
      username: "alice",
      firstName: "Alice",
      avatarUrl: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/post/new", name: "CreatePost", component: { template: "<div />" } },
        { path: "/story/new", name: "CreateStory", component: { template: "<div />" } },
      ],
    });
    router.push("/post/new");
    await router.isReady();

    const wrapper = mount(AppShell, {
      global: { plugins: [router] },
      slots: { default: "<div>editor</div>" },
    });
    await Promise.resolve();

    expect(wrapper.find(".brand-mark").exists()).toBe(false);
    expect(wrapper.find(".avatar-menu-button").exists()).toBe(false);
    expect(wrapper.text()).toContain("editor");
  });
});
