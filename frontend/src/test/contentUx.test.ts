import { mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AppShell from "@/features/shell/AppShell.vue";
import FeedPage from "@/features/content/FeedPage.vue";
import StoryViewer from "@/features/stories/StoryViewer.vue";
import { buildFeedCanvasNodes, feedNodesOverlap, requiredFeedChunks, shouldKeepFeedChunk } from "@/features/content/feedCanvasLayout";
import { buildCreatePostInput, emptyPostEditorState, extractHashtags, isPostEditorDirty } from "@/features/editor/postEditor";
import { attachmentMarkdown, mediaReferences } from "@/features/contentDocument/contentModel";
import { isFileLikeUrl, markdownLinks } from "@/features/display/markdown";
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

vi.mock("primevue/usetoast", () => ({
  useToast: () => ({ add: vi.fn() }),
}));

vi.mock("@/api/contentService", () => ({
	ContentService: {
    currentActor: vi.fn().mockResolvedValue({
      user: { id: "viewer", username: "viewer" },
      activeOwner: { id: "viewer", ownerType: "USER", username: "viewer" },
    }),
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
	      stories: [
          {
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
          },
          {
            id: "story-2",
            authorId: "alice",
            author: { username: "alice" },
            visibility: "CLOSE_FRIENDS",
            durationMs: 4000,
            likeCount: 0,
            likedByViewer: false,
            remainingLifeSeconds: 2400,
            blocks: [
              { id: "media-2", type: "IMAGE", data: { src: "https://example.test/story-2.jpg" } },
              { id: "caption-2", type: "TEXT", data: { text: "Second circular story #orbit", tags: ["orbit"] } },
            ],
          },
        ],
	    }),
	    storiesFeed: vi.fn().mockResolvedValue([
	      { authorId: "alice", authorName: "alice", storyIds: ["story-1", "story-2"], activeCount: 2, seen: false, closeFriends: false, latestAt: "2026-01-01T00:00:00Z" },
	    ]),
    recommendationFeed: vi.fn(),
    likePost: vi.fn().mockResolvedValue({ postId: "clickable", liked: true, likeCount: 1 }),
    unlikePost: vi.fn().mockResolvedValue({ postId: "clickable", liked: false, likeCount: 0 }),
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

function ensureLocalStorage() {
  if (window.localStorage) return;
  const storage = new Map<string, string>();
  Object.defineProperty(window, "localStorage", {
    configurable: true,
    value: {
      clear: () => storage.clear(),
      getItem: (key: string) => storage.get(key) ?? null,
      removeItem: (key: string) => storage.delete(key),
      setItem: (key: string, value: string) => storage.set(key, String(value)),
    },
  });
}

function ensureResizeObserver() {
  if (globalThis.ResizeObserver) return;
  Object.defineProperty(globalThis, "ResizeObserver", {
    configurable: true,
    value: class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  });
}

beforeEach(() => {
  ensureLocalStorage();
  ensureResizeObserver();
  vi.clearAllMocks();
});

function feedItem(id: string, score: number, type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "FILE" = "TEXT"): FeedItem {
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

  it("keeps packed cloud nodes from overlapping", () => {
    const nodes = buildFeedCanvasNodes(Array.from({ length: 18 }, (_, index) => (
      feedItem(`post-${index}`, 100 - index, index % 5 === 0 ? "IMAGE" : index % 4 === 0 ? "FILE" : "TEXT")
    )));

    for (let outer = 0; outer < nodes.length; outer += 1) {
      for (let inner = outer + 1; inner < nodes.length; inner += 1) {
        expect(feedNodesOverlap(nodes[outer], nodes[inner])).toBe(false);
      }
    }
  });

  it("keeps nearby chunks and evicts distant canvas chunks", () => {
    const chunks = requiredFeedChunks({ x: 20, y: 20 });

    expect(chunks).toHaveLength(9);
    expect(chunks).toContainEqual({ x: 0, y: 0 });
    expect(shouldKeepFeedChunk("0:0", { x: 20, y: 20 })).toBe(true);
    expect(shouldKeepFeedChunk("4:0", { x: 20, y: 20 })).toBe(false);
  });

  it("keeps post clicks interactive without starting canvas drag", async () => {
    vi.mocked(ContentService.recommendationFeed).mockResolvedValue({
      chunkX: 0,
      chunkY: 0,
      sessionSeed: "test",
      items: [{
        ...feedItem("clickable", 50),
        cell: { q: 0, r: 0 },
        emphasis: "compact",
      }],
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: FeedPage },
        { path: "/p/:postId", component: { template: "<div />" } },
      ],
    });
    await router.push("/");
    await router.isReady();

    const wrapper = mount(FeedPage, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".canvas-post").exists()).toBe(true));
    await wrapper.find(".canvas-post").trigger("pointerdown");

    expect(wrapper.find(".canvas-viewport").classes()).not.toContain("dragging");
    await wrapper.find(".post-cloud__body").trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/p/clickable"));

    wrapper.unmount();
  });
});

describe("markdown post editor", () => {
  it("maps markdown, hashtags and attachments to createPost input", () => {
    let state = emptyPostEditorState();
    state.markdown = "# Launch notes\n\nHello **canvas** #Design\n\n![[media:local-1|clip.webm]]";
    state.allowComments = false;
    state.attachments = [{
      id: "local-1",
      file: new File(["video"], "clip.webm", { type: "video/webm" }),
      url: "blob:clip",
      name: "clip.webm",
      mimeType: "video/webm",
      size: 5,
      type: "VIDEO",
    }];

    const input = buildCreatePostInput(state);

    expect(input.title).toBe("Launch notes");
    expect(input.tags).toEqual(["design"]);
    expect(input.allowComments).toBe(false);
    expect(input.text).toContain("Hello **canvas**");
    expect(input.blocks.map((block) => block.type)).toEqual(["TEXT", "VIDEO"]);
  });

  it("maps ordinary uploaded files to file blocks", () => {
    let state = emptyPostEditorState();
    state.markdown = "![[media:file-1|brief.pdf]]";
    state.attachments = [{
      id: "file-1",
      file: new File(["pdf"], "brief.pdf", { type: "application/pdf" }),
      url: "blob:brief",
      name: "brief.pdf",
      mimeType: "application/pdf",
      size: 3,
      type: "FILE",
    }];

    const input = buildCreatePostInput(state);

    expect(input.blocks.map((block) => block.type)).toEqual(["TEXT", "FILE"]);
    expect(input.blocks[1].data.markdownRef).toBe("media:file-1");
  });

  it("detects file-like markdown links for capsule rendering", () => {
    const links = markdownLinks("Read [brief.pdf](https://cdn.test/brief.pdf) and [site](https://example.test) plus ![[media:file-1|clip.mov]]");

    expect(isFileLikeUrl("media:file-1", "clip.mov")).toBe(true);
    expect(links.map((link) => link.fileLike)).toEqual([true, false, true]);
    expect(attachmentMarkdown({ id: "file-1", name: "clip.mov" })).toBe("![[media:file-1|clip.mov]]");
    expect(mediaReferences("![[media:file-1|clip.mov]]")[0]).toMatchObject({ id: "file-1", label: "clip.mov" });
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

describe("story viewer orbit UI", () => {
  it("renders caption as a collapsible branch node", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/story/:storyId", component: StoryViewer }],
    });
    await router.push("/story/story-1");
    await router.isReady();

    const wrapper = mount(StoryViewer, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".story-node--caption").exists()).toBe(true));
    expect(wrapper.find(".story-orb").exists()).toBe(true);
    expect(wrapper.find(".story-progress-ring__value").exists()).toBe(true);
    expect(wrapper.findAll(".story-orbit-dot")).toHaveLength(2);
    const sheet = wrapper.find(".story-caption-toggle");
    expect(sheet.text()).toContain("A compact caption #travel");
    await sheet.trigger("click");
    expect(wrapper.find(".story-node--caption").classes()).toContain("open");

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
    await vi.waitFor(() => expect(wrapper.find(".story-node--caption").exists()).toBe(true));
    await wrapper.find(".story-like").trigger("click");

    expect(ContentService.likeStory).toHaveBeenCalledWith("story-1");
    await vi.waitFor(() => expect(wrapper.find(".story-like").text()).toContain("1"));
    expect(wrapper.find(".story-like").classes()).toContain("active");

    wrapper.unmount();
  });

  it("switches stories through the orbit selector and records the new view", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/story/:storyId", component: StoryViewer }],
    });
    await router.push("/story/story-1");
    await router.isReady();

    const wrapper = mount(StoryViewer, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.findAll(".story-orbit-dot")).toHaveLength(2));
    vi.mocked(ContentService.recordStoryView).mockClear();

    await wrapper.findAll(".story-orbit-dot")[1].trigger("click");

    await vi.waitFor(() => expect(wrapper.find(".story-caption-toggle").text()).toContain("Second circular story"));
    expect(ContentService.recordStoryView).toHaveBeenCalledWith("story-2");

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

  it("keeps the avatar menu available on profile canvas routes", async () => {
    vi.mocked(ProfileService.session).mockResolvedValue({
      id: "1",
      username: "alice",
      firstName: "Alice",
      avatarUrl: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/u/:nickname", name: "Profile", component: { template: "<div />" } },
      ],
    });
    router.push("/u/alice");
    await router.isReady();

    const wrapper = mount(AppShell, {
      global: { plugins: [router] },
      slots: { default: "<div>profile</div>" },
    });
    await Promise.resolve();

    expect(wrapper.find(".avatar-menu-button").exists()).toBe(true);
    expect(wrapper.find(".brand-mark").exists()).toBe(false);
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
