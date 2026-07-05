import { describe, expect, it } from "vitest";
import { accountSettingsUrl } from "@/features/profile/accountLinks";
import { buildProfileCanvasLayout, ORBIT_OFFSETS } from "@/features/profile/profileCanvasLayout";
import type { ProfileCanvasResponse } from "@/api/types";

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
    expect(ORBIT_OFFSETS.followAction).toEqual({ x: 0, y: 180 });
    expect(ORBIT_OFFSETS.posts).toEqual({ x: 360, y: -190 });
  });

  it("computes a horizontal stage and centers the avatar initially", () => {
    const layout = buildProfileCanvasLayout(response([
      { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      { id: "followers", type: "stat", position: { x: 0, y: 0 }, data: {} },
      { id: "bio", type: "text", position: { x: 0, y: 0 }, data: {} },
    ]), { width: 640, height: 520 });
    const avatar = layout.nodes.find((node) => node.id === "avatar");
    const bio = layout.nodes.find((node) => node.id === "bio");

    expect(layout.stage.width).toBeGreaterThan(640);
    expect(layout.stage.height).toBeGreaterThanOrEqual(560);
    expect(layout.initialScrollLeft).toBeGreaterThan(0);
    expect(layout.initialScrollLeft).toBeLessThanOrEqual(layout.stage.width - 640);
    expect(avatar?.center.x).toBe(layout.avatarCenter.x);
    expect(bio?.center.x).toBeGreaterThan(layout.avatarCenter.x);
  });

  it("adds settings as a synthetic owner branch", () => {
    const layout = buildProfileCanvasLayout(response([
      { id: "avatar", type: "avatar", position: { x: 0, y: 0 }, data: {} },
      { id: "displayName", type: "label", position: { x: 0, y: 0 }, data: { label: "Alice" } },
    ], true), { width: 900, height: 620 });

    expect(layout.nodes.map((node) => node.id)).toContain("settingsAction");
    expect(layout.edges.map((edge) => edge.id)).toContain("avatar-settingsAction");
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
