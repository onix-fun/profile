import { mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AppShell from "@/features/shell/AppShell.vue";
import FeedPage from "@/features/content/FeedPage.vue";
import StoryViewer from "@/features/stories/StoryViewer.vue";
import StoryArchivePage from "@/features/stories/StoryArchivePage.vue";
import ProfileFramePage from "@/features/profile/ProfileFramePage.vue";
import { buildRecommendationCanvasNodes, feedNodesOverlap, hasFeedContent, requiredFeedChunks, shouldKeepFeedChunk } from "@/features/content/feedCanvasLayout";
import { postAssets } from "@/features/mediaProject/mediaAssets";
import { buildCreatePostInput, emptyPostEditorState, extractHashtags, isPostEditorDirty } from "@/features/editor/postEditor";
import { attachmentMarkdown, mediaReferences } from "@/features/contentDocument/contentModel";
import { isFileLikeUrl, markdownLinks } from "@/features/display/markdown";
import { emptyStoryComposerState, mergeSeenState, reduceStoryComposer, sortStoryRail } from "@/features/stories/storyState";
import { ContentService } from "@/api/contentService";
import { isProfilePath, profileUrl } from "@/api/navigation";
import type { FeedItem, RecommendationFeedResponse, StoryRailItem } from "@/api/types";
import { router as appRouter } from "@/app/router";

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
    storyArchive: vi.fn().mockResolvedValue({
      ownerId: "alice",
      ownerType: "USER",
      owner: { id: "alice", username: "alice" },
      stories: [],
      nextCursor: null,
    }),
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
  vi.mocked(ContentService.currentActor).mockResolvedValue({
    user: { id: "viewer", username: "viewer" },
    activeOwner: { id: "viewer", ownerType: "USER", username: "viewer" },
  });
});

function feedItem(id: string, score: number, type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "FILE" = "TEXT"): FeedItem {
  const kind = type === "VIDEO" ? "VIDEO" : type === "AUDIO" ? "AUDIO" : "IMAGE";
  return {
    post: {
      id,
      authorId: "author",
      title: null,
      text: "",
      tags: [],
      blocks: [],
      assets: [{ id: `${id}-asset`, assetId: `${id}-asset`, kind, sourceKind: "UPLOAD", url: `https://delivery.example.test/${id}.${kind === "VIDEO" ? "mp4" : kind === "AUDIO" ? "m4a" : "jpg"}`, status: "READY" }],
      visibility: "PUBLIC",
    },
    score,
    reasons: ["ranked"],
  };
}

describe("feed canvas layout", () => {
  it("uses only deterministic server placements for media projects", () => {
    const response: RecommendationFeedResponse = {
      chunkX: 0,
      chunkY: 0,
      items: [
        { ...feedItem("a", 90, "VIDEO"), placement: { constellationKey: "hidden", salt: 0, worldX: 340, worldY: 280, orbitOrder: 0 } },
        { ...feedItem("b", 40), placement: { constellationKey: "hidden", salt: 1, worldX: 940, worldY: 520, orbitOrder: 1 } },
      ],
    };
    const first = buildRecommendationCanvasNodes([response]);
    const second = buildRecommendationCanvasNodes([{ ...response, items: [...response.items].reverse() }]);

    expect(first).toEqual(second);
    expect(first[0]).toMatchObject({ id: "a", mediaType: "VIDEO", emphasis: "hero" });
    expect(first[0].x).not.toBe(first[1].x);
  });

  it("keeps server-spaced media nodes from overlapping", () => {
    const response: RecommendationFeedResponse = {
      chunkX: 0,
      chunkY: 0,
      items: Array.from({ length: 18 }, (_, index) => ({
        ...feedItem(`post-${index}`, 100 - index, index % 4 === 0 ? "VIDEO" : "IMAGE"),
        placement: { constellationKey: "hidden", salt: index, worldX: index * 620, worldY: (index % 2) * 520, orbitOrder: index },
      })),
    };
    const nodes = buildRecommendationCanvasNodes([response]);

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

  it("uses stable server placements without drawing constellation orbits", () => {
    const response: RecommendationFeedResponse = {
      chunkX: 0,
      chunkY: 0,
      constellations: [
        { key: "design", anchorX: 900, anchorY: 600, paletteKey: "cobalt" },
        { key: "music", anchorX: 2200, anchorY: 1500, paletteKey: "violet" },
      ],
      items: [
        { ...feedItem("b", 40), placement: { constellationKey: "design", salt: 1, worldX: 900, worldY: 600, orbitOrder: 1 } },
        { ...feedItem("c", 40), placement: { constellationKey: "music", salt: 0, worldX: 2200, worldY: 1500, orbitOrder: 0 } },
        { ...feedItem("a", 80), placement: { constellationKey: "design", salt: 0, worldX: 340, worldY: 280, orbitOrder: 0 } },
      ],
    };

    const nodes = buildRecommendationCanvasNodes([response]);
    const repeated = buildRecommendationCanvasNodes([{ ...response, items: [...response.items].reverse() }]);

    expect(nodes.map((node) => [node.id, node.x, node.y])).toEqual([
      ["a", 340, 280],
      ["b", 900, 600],
      ["c", 2200, 1500],
    ]);
    expect(repeated).toEqual(nodes);
    expect(nodes).toHaveLength(3);
  });

  it("filters non-media and malformed projects from the canvas", () => {
    const empty = feedItem("empty", 40);
    empty.post.assets = [];
    const failed = feedItem("failed", 40);
    failed.post.assets = [{ id: "failed-asset", kind: "IMAGE", sourceKind: "UPLOAD", assetId: "asset", status: "FAILED" }];

    expect(hasFeedContent(empty)).toBe(false);
    expect(hasFeedContent(failed)).toBe(false);
  });

  it("does not render stale URL or embed assets", () => {
    const stale = feedItem("stale", 40);
    (stale.post as unknown as { assets: unknown[] }).assets = [{
      id: "remote",
      kind: "EMBED",
      sourceKind: "EMBED",
      url: "https://example.test/embed",
      status: "READY",
    }];

    expect(postAssets(stale.post)).toEqual([]);
    expect(hasFeedContent(stale)).toBe(false);
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
        placement: { constellationKey: "hidden", salt: 0, worldX: 0, worldY: 0, orbitOrder: 0 },
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
    expect(wrapper.find(".story-rail").exists()).toBe(true);
    await wrapper.find(".canvas-post").trigger("pointerdown");

    expect(wrapper.find(".canvas-viewport").classes()).not.toContain("dragging");
    expect(wrapper.find(".canvas-post").attributes("style")).not.toContain("scale(");
    await wrapper.find(".post-cloud__body").trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/p/clickable"));
    expect(sessionStorage.getItem("postFocusOrigin")).toBeNull();
    expect(wrapper.find(".feed-orbit").exists()).toBe(false);
    expect(wrapper.find(".post-cloud__body").text()).toBe("");

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

  it("renders video stories as playable orb media", async () => {
    vi.mocked(ContentService.storyGroup).mockResolvedValueOnce({
      authorId: "alice",
      authorName: "alice",
      author: { username: "alice" },
      archive: false,
      startStoryId: "story-video",
      stories: [{
        id: "story-video",
        authorId: "alice",
        author: { username: "alice" },
        visibility: "PUBLIC",
        durationMs: 5000,
        likeCount: 0,
        likedByViewer: false,
        remainingLifeSeconds: 3600,
        blocks: [
          { id: "video", type: "VIDEO", data: { src: "https://example.test/story.webm" } },
          { id: "caption", type: "TEXT", data: { text: "Video update #motion" } },
        ],
      }],
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/story/:storyId", component: StoryViewer }],
    });
    await router.push("/story/story-video");
    await router.isReady();

    const wrapper = mount(StoryViewer, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".story-orb video").exists()).toBe(true));
    const video = wrapper.find(".story-orb video");

    expect(video.attributes("src")).toBe("https://example.test/story.webm");
    expect(video.attributes()).toMatchObject({
      autoplay: "",
      loop: "",
      controls: "",
      playsinline: "",
      preload: "metadata",
    });

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

describe("story archive page", () => {
  it("loads a Content-owned archive and renders the empty state", async () => {
    vi.mocked(ContentService.storyArchive).mockResolvedValueOnce({
      ownerId: "owner-1",
      ownerType: "USER",
      owner: { id: "owner-1", username: "alice" },
      stories: [],
      nextCursor: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/stories/archive", component: StoryArchivePage }],
    });
    await router.push("/stories/archive?ownerType=USER&ownerId=owner-1");
    await router.isReady();

    const wrapper = mount(StoryArchivePage, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.text()).toContain("Archive is empty"));

    expect(ContentService.storyArchive).toHaveBeenCalledWith("owner-1", null, 80, "USER");
    expect(wrapper.text()).toContain("Stories will appear here after they expire.");

    wrapper.unmount();
  });

  it("opens archived stories through the StoryViewer archive route", async () => {
    vi.mocked(ContentService.storyArchive).mockResolvedValueOnce({
      ownerId: "owner-1",
      ownerType: "USER",
      owner: { id: "owner-1", username: "alice" },
      stories: [{
        id: "story-archived",
        authorId: "owner-1",
        author: { id: "owner-1", username: "alice" },
        visibility: "PUBLIC",
        blocks: [{ id: "caption", type: "TEXT", data: { text: "Archived launch" } }],
        createdAt: "2026-01-01T00:00:00Z",
      }],
      nextCursor: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/stories/archive", component: StoryArchivePage },
        { path: "/story/:storyId", component: { template: "<div />" } },
      ],
    });
    await router.push("/stories/archive?ownerType=USER&ownerId=owner-1");
    await router.isReady();

    const wrapper = mount(StoryArchivePage, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".archive-card").exists()).toBe(true));
    await wrapper.find(".archive-card").trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/story/story-archived"));

    expect(router.currentRoute.value.query.archive).toBe("1");
    expect(router.currentRoute.value.query.author).toBe("owner-1");
    expect(router.currentRoute.value.query.ownerType).toBe("USER");
    expect(String(router.currentRoute.value.query.from)).toContain("/stories/archive");

    wrapper.unmount();
  });

  it("keeps archived video previews idle until the story is opened", async () => {
    vi.mocked(ContentService.storyArchive).mockResolvedValueOnce({
      ownerId: "owner-1",
      ownerType: "USER",
      owner: { id: "owner-1", username: "alice" },
      stories: [{
        id: "story-video",
        authorId: "owner-1",
        author: { id: "owner-1", username: "alice" },
        visibility: "PUBLIC",
        blocks: [{ id: "video", type: "VIDEO", data: { src: "https://example.test/story.webm" } }],
        createdAt: "2026-01-01T00:00:00Z",
      }],
      nextCursor: null,
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/stories/archive", component: StoryArchivePage }],
    });
    await router.push("/stories/archive?ownerType=USER&ownerId=owner-1");
    await router.isReady();

    const wrapper = mount(StoryArchivePage, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(wrapper.find(".archive-card video").exists()).toBe(true));

    expect(wrapper.find(".archive-card video").attributes()).toMatchObject({
      playsinline: "",
      preload: "none",
    });
    expect(wrapper.find(".archive-card video").attributes("autoplay")).toBeUndefined();
    expect(wrapper.find(".archive-card img").exists()).toBe(false);

    wrapper.unmount();
  });
});

describe("route auth guard", () => {
  it("reserves p namespace for projects and makes every legacy post route a real 404", () => {
    expect(appRouter.resolve("/p/project-1").name).toBe("PostPage");
    expect(appRouter.resolve("/p/project-1/comments/comment-1").name).toBe("PostCommentThread");
    expect(appRouter.resolve("/post/project-1").name).toBe("RemovedPostRoute");
  });

  it("keeps profile-owned paths inside Content without checking the content session", async () => {
    await appRouter.push("/");
    const result = await appRouter.push("/u/alice?tab=posts");

    expect(result).toBeUndefined();
    expect(ContentService.currentActor).not.toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).toBe("/u/alice");
  });

  it("checks current actor before entering app routes", async () => {
    vi.mocked(ContentService.currentActor).mockResolvedValue({
      user: { id: "1", username: "alice", firstName: "Alice" },
      activeOwner: { id: "1", ownerType: "USER", username: "alice" },
    });

    await appRouter.push("/p/new");
    await appRouter.isReady();

    expect(ContentService.currentActor).toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).toBe("/p/new");
  });

  it("aborts navigation when the session request redirects to Account", async () => {
    vi.mocked(ContentService.currentActor).mockRejectedValue(new Error("AUTH_REQUIRED"));

    const result = await appRouter.push("/story/new");

    expect(result).toBeTruthy();
    expect(appRouter.currentRoute.value.path).not.toBe("/story/new");
  });
});

describe("navigation helpers", () => {
  it("builds local profile links so Content can embed Profile routes", () => {
    expect(profileUrl("/search?tag=design")).toBe("/search?tag=design");
    expect(profileUrl("/u/alice", true)).toBe("/u/alice");
  });

  it("keeps the Content archive route owned by Content", () => {
    expect(isProfilePath("/me")).toBe(true);
    expect(isProfilePath("/stories/archive")).toBe(false);
    expect(isProfilePath("/stories/archive?ownerType=USER&ownerId=owner-1")).toBe(false);
  });
});

describe("profile iframe bridge", () => {
  it("builds embedded Profile iframe src with default content service filter", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/u/:nickname", component: ProfileFramePage }],
    });
    await router.push("/u/alice?tab=posts");
    await router.isReady();

    const wrapper = mount(ProfileFramePage, { global: { plugins: [router] } });
    const src = new URL(wrapper.find("iframe").attributes("src") || "");

    expect(src.origin).toBe("http://profile.onix.localhost:8088");
    expect(src.pathname).toBe("/u/alice");
    expect(src.searchParams.get("tab")).toBe("posts");
    expect(src.searchParams.get("embed")).toBe("1");
    expect(src.searchParams.get("from")).toBe("content");
    expect(src.searchParams.get("parentOrigin")).toBe(window.location.origin);

    wrapper.unmount();
  });

  it("resolves /u/me to the active owner before loading the Profile iframe", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/u/me", component: ProfileFramePage },
        { path: "/u/:nickname", component: ProfileFramePage },
      ],
    });
    await router.push("/u/me");
    await router.isReady();

    const wrapper = mount(ProfileFramePage, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/u/viewer"));
    const src = new URL(wrapper.find("iframe").attributes("src") || "");

    expect(src.pathname).toBe("/u/viewer");
    expect(src.searchParams.get("embed")).toBe("1");

    wrapper.unmount();
  });

  it("resolves /me to the active owner before loading the Profile iframe", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/me", component: ProfileFramePage },
        { path: "/u/:nickname", component: ProfileFramePage },
      ],
    });
    await router.push("/me");
    await router.isReady();

    const wrapper = mount(ProfileFramePage, { global: { plugins: [router] } });
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/u/viewer"));
    const src = new URL(wrapper.find("iframe").attributes("src") || "");

    expect(src.pathname).toBe("/u/viewer");
    expect(src.searchParams.get("embed")).toBe("1");

    wrapper.unmount();
  });

  it("preserves explicit service filters in embedded Profile iframe src", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/search", component: ProfileFramePage }],
    });
    await router.push("/search?q=alice&from=content,media");
    await router.isReady();

    const wrapper = mount(ProfileFramePage, { global: { plugins: [router] } });
    const src = new URL(wrapper.find("iframe").attributes("src") || "");

    expect(src.pathname).toBe("/search");
    expect(src.searchParams.get("q")).toBe("alice");
    expect(src.searchParams.get("from")).toBe("content,media");

    wrapper.unmount();
  });

  it("accepts navigation messages only from the Profile origin", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/u/:nickname", component: ProfileFramePage },
        { path: "/p/:postId", component: { template: "<div />" } },
      ],
    });
    await router.push("/u/alice");
    await router.isReady();

    const wrapper = mount(ProfileFramePage, { global: { plugins: [router] } });
    window.dispatchEvent(new MessageEvent("message", {
      origin: "http://evil.test",
      data: { type: "onix:navigate", serviceKey: "content", path: "/p/evil" },
    }));
    expect(router.currentRoute.value.path).toBe("/u/alice");

    window.dispatchEvent(new MessageEvent("message", {
      origin: "http://profile.onix.localhost:8088",
      data: { type: "onix:navigate", serviceKey: "content", path: "/p/post-1" },
    }));
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/p/post-1"));

    wrapper.unmount();
  });

  it("syncs Profile iframe route messages back to the Content URL without injected embed params", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/u/:nickname", component: ProfileFramePage },
        { path: "/u/:nickname/social", component: ProfileFramePage },
      ],
    });
    await router.push("/u/alice");
    await router.isReady();

    const wrapper = mount(ProfileFramePage, { global: { plugins: [router] } });
    window.dispatchEvent(new MessageEvent("message", {
      origin: "http://profile.onix.localhost:8088",
      data: { type: "onix:profile-route", path: "/u/alice/social?filter=friends&embed=1&from=content&parentOrigin=http%3A%2F%2Flocalhost" },
    }));
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe("/u/alice/social?filter=friends"));

    wrapper.unmount();
  });
});

describe("app shell", () => {
  it("hides the home logo and renders avatar menu navigation", async () => {
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
    expect(wrapper.find('a[href^="/u/"]').exists()).toBe(true);
    expect(wrapper.find('a[href="/search"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain("Logout");
  });

  it("keeps the logo on secondary app routes", async () => {
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
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/p/new", name: "CreatePost", component: { template: "<div />" } },
        { path: "/story/new", name: "CreateStory", component: { template: "<div />" } },
      ],
    });
    router.push("/p/new");
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
