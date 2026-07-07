import { describe, expect, it } from "vitest";
import { accountSettingsUrl } from "@/features/profile/accountLinks";
import { buildProfileCanvasLayout, ORBIT_OFFSETS } from "@/features/profile/profileCanvasLayout";
import { buildSocialCanvasLayout, socialNodesOverlap } from "@/features/profile/socialCanvasLayout";
import type { AccountProfile, ProfileCanvasResponse, RelatedUser } from "@/api/types";

function response(nodes: ProfileCanvasResponse["nodes"], owner = false): ProfileCanvasResponse {
  return {
    status: "OK",
    profile: null,
    relationship: null,
    nodes,
    edges: [
      { id: "avatar-bio", source: "avatar", target: "bio" },
      { id: "avatar-private", source: "avatar", target: "private" },
    ],
    content: { posts: [], stories: [], comments: [] },
    permissions: { owner, canFollow: false },
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

describe("profile canvas layout", () => {
  it("keeps only known visible nodes and matching edges", () => {
    const layout = buildProfileCanvasLayout(response([
      { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      { id: "bio", type: "text", position: { x: 10, y: 20 }, data: { label: "Visible" } },
      { id: "private", type: "text", position: { x: 1, y: 1 }, data: {} },
    ]), { width: 900, height: 620 });

    expect(layout.nodes.map((node) => node.id)).toEqual(["avatar", "bio"]);
    expect(layout.edges.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target }))).toEqual([
      { id: "avatar-bio", source: "avatar", target: "bio" },
    ]);
  });

  it("uses deterministic orbit positions for automatic layout", () => {
    expect(ORBIT_OFFSETS.avatar).toEqual({ x: 0, y: 0 });
    expect(ORBIT_OFFSETS.followAction).toEqual({ x: 0, y: 142 });
    expect(ORBIT_OFFSETS.social).toEqual({ x: -310, y: -50 });
  });

  it("computes a horizontal stage and centers the avatar initially", () => {
    const layout = buildProfileCanvasLayout(response([
      { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      { id: "social", type: "social", position: { x: 0, y: 0 }, data: {} },
      { id: "bio", type: "text", position: { x: 0, y: 0 }, data: {} },
    ]), { width: 640, height: 520 });
    const avatar = layout.nodes.find((node) => node.id === "avatar");
    const bio = layout.nodes.find((node) => node.id === "bio");

    expect(layout.stage.width).toBeGreaterThan(640);
    expect(layout.stage.height).toBe(520);
    expect(layout.initialScrollLeft).toBeGreaterThan(0);
    expect(layout.initialScrollLeft).toBeLessThanOrEqual(layout.stage.width - 640);
    expect(avatar?.center.x).toBe(layout.avatarCenter.x);
    expect(bio?.center.y).toBeGreaterThan(layout.avatarCenter.y);
  });

  it("does not add settings as a synthetic canvas branch", () => {
    const layout = buildProfileCanvasLayout(response([
      { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      { id: "displayName", type: "label", position: { x: 0, y: 0 }, data: { label: "Alice" } },
    ], true), { width: 900, height: 620 });

    expect(layout.nodes.map((node) => node.id)).not.toContain("settingsAction");
    expect(layout.edges.map((edge) => edge.id)).not.toContain("avatar-settingsAction");
  });

  it("places profile posts on the right without graph edges", () => {
    const layout = buildProfileCanvasLayout({
      ...response([
        { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
        { id: "social", type: "social", position: { x: 0, y: 0 }, data: { label: "Social" } },
      ]),
      content: {
        posts: [
          { id: "post-new", text: "New post", tags: [], createdAt: "2026-07-05T00:00:00Z" },
          { id: "post-old", text: "Old post", tags: [], createdAt: "2026-07-01T00:00:00Z" },
        ],
        stories: [],
        comments: [],
      },
    }, { width: 900, height: 620 });

    const postNodes = layout.nodes.filter((node) => node.type === "post");
    const avatar = layout.nodes.find((node) => node.id === "avatar");

    expect(layout.nodes.map((node) => node.id)).not.toContain("posts");
    expect(postNodes).toHaveLength(2);
    expect(postNodes.every((node) => avatar && node.center.x > avatar.center.x)).toBe(true);
    expect(layout.edges.filter((edge) => edge.target.startsWith("post:"))).toHaveLength(0);
  });

  it("reflows profile posts horizontally when viewport height is tight", () => {
    const layout = buildProfileCanvasLayout({
      ...response([
        { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      ]),
      content: {
        posts: Array.from({ length: 10 }, (_, index) => ({
          id: `post-${index}`,
          text: `Post ${index}`,
          tags: [],
          createdAt: `2026-07-${String(index + 1).padStart(2, "0")}T00:00:00Z`,
        })),
        stories: [],
        comments: [],
      },
    }, { width: 720, height: 420 });

    const postNodes = layout.nodes.filter((node) => node.type === "post");
    expect(postNodes).toHaveLength(10);
    expect(postNodes.every((node) => node.y >= 0 && node.y + node.height <= layout.stage.height)).toBe(true);
    expect(new Set(postNodes.map((node) => node.center.x)).size).toBeGreaterThan(3);
    for (let outer = 0; outer < postNodes.length; outer += 1) {
      for (let inner = outer + 1; inner < postNodes.length; inner += 1) {
        expect(postNodes[outer].x < postNodes[inner].x + postNodes[inner].width + 18
          && postNodes[outer].x + postNodes[outer].width + 18 > postNodes[inner].x
          && postNodes[outer].y < postNodes[inner].y + postNodes[inner].height + 18
          && postNodes[outer].y + postNodes[outer].height + 18 > postNodes[inner].y).toBe(false);
      }
    }
  });

  it("adds archive as an upper-left avatar branch when archived stories exist", () => {
    const layout = buildProfileCanvasLayout(response([
      { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      { id: "displayName", type: "label", position: { x: 0, y: 0 }, data: { label: "Alice" } },
    ]), { width: 900, height: 620 }, { hasArchive: true, archiveCount: 3 });

    const archive = layout.nodes.find((node) => node.id === "archive");
    const avatar = layout.nodes.find((node) => node.id === "avatar");

    expect(archive?.type).toBe("archive");
    expect(archive?.data.label).toBe("3");
    expect(avatar && archive && archive.center.x < avatar.center.x && archive.center.y < avatar.center.y).toBe(true);
    expect(layout.edges.map((edge) => edge.id)).toContain("avatar-archive");
  });
});

describe("social canvas layout", () => {
  it("packs user nodes without overlap around the owner anchor", () => {
    const owner: AccountProfile = {
      id: "owner",
      username: "alice",
      firstName: "Alice",
      socialLinks: [],
      followersCount: 0,
      followingCount: 0,
      isPrivate: false,
      relationship: {
        isFollowing: false,
        isFollowedBy: false,
        isFriend: false,
        isBlocked: false,
        hasPendingRequest: false,
      },
    };
    const users: RelatedUser[] = Array.from({ length: 44 }, (_, index) => ({
      id: `user-${index}`,
      username: `user${index}`,
      firstName: `User ${index}`,
    }));

    const layout = buildSocialCanvasLayout(owner, users, { width: 900, height: 560 });

    expect(layout.userNodes).toHaveLength(44);
    expect(layout.stage.width).toBeGreaterThan(900);
    for (let index = 0; index < layout.userNodes.length; index += 1) {
      const node = layout.userNodes[index];
      expect(node.y).toBeGreaterThanOrEqual(0);
      expect(node.y + node.height).toBeLessThanOrEqual(layout.stage.height);
      for (let nextIndex = index + 1; nextIndex < layout.userNodes.length; nextIndex += 1) {
        expect(socialNodesOverlap(node, layout.userNodes[nextIndex])).toBe(false);
      }
    }
  });
});

describe("account links", () => {
  it("targets account with the current profile as redirect", () => {
    const url = new URL(accountSettingsUrl("https://account.onix.fun", "https://profile.onix.fun/u/alice?theme=dark"));

    expect(url.origin).toBe("https://account.onix.fun");
    expect(url.searchParams.has("view")).toBe(false);
    expect(url.searchParams.get("redirect")).toBe("https://profile.onix.fun/u/alice?theme=dark");
  });
});
