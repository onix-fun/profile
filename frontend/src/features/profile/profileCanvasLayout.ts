import type { CanvasEdge, CanvasNode, ProfileCanvasResponse } from "@/api/types";

export type ProfileCanvasNodeId =
  | "avatar"
  | "displayName"
  | "username"
  | "bio"
  | "socialLinks"
  | "birthday"
  | "followers"
  | "following"
  | "posts"
  | "stories"
  | "comments"
  | "followAction"
  | "settingsAction";

export interface ProfileCanvasSize {
  width: number;
  height: number;
}

export interface ProfileCanvasPoint {
  x: number;
  y: number;
}

export interface PositionedProfileCanvasNode extends CanvasNode {
  id: ProfileCanvasNodeId;
  width: number;
  height: number;
  x: number;
  y: number;
  center: ProfileCanvasPoint;
}

export interface PositionedProfileCanvasEdge extends CanvasEdge {
  source: ProfileCanvasNodeId;
  target: ProfileCanvasNodeId;
  sourcePoint: ProfileCanvasPoint;
  targetPoint: ProfileCanvasPoint;
}

export interface ProfileCanvasLayout {
  nodes: PositionedProfileCanvasNode[];
  edges: PositionedProfileCanvasEdge[];
  stage: ProfileCanvasSize;
  avatarCenter: ProfileCanvasPoint;
  initialScrollLeft: number;
}

export const ORBIT_OFFSETS: Record<ProfileCanvasNodeId, ProfileCanvasPoint> = {
  avatar: { x: 0, y: 0 },
  displayName: { x: 0, y: -190 },
  username: { x: 260, y: -105 },
  bio: { x: 290, y: 55 },
  socialLinks: { x: 150, y: 220 },
  birthday: { x: -155, y: 220 },
  followers: { x: -285, y: 70 },
  following: { x: -265, y: -105 },
  posts: { x: 360, y: -190 },
  stories: { x: 420, y: 0 },
  comments: { x: 360, y: 190 },
  followAction: { x: 0, y: 180 },
  settingsAction: { x: 0, y: 180 },
};

const NODE_SIZES: Record<ProfileCanvasNodeId, ProfileCanvasSize> = {
  avatar: { width: 132, height: 132 },
  displayName: { width: 220, height: 54 },
  username: { width: 190, height: 50 },
  bio: { width: 280, height: 88 },
  socialLinks: { width: 250, height: 112 },
  birthday: { width: 170, height: 52 },
  followers: { width: 166, height: 74 },
  following: { width: 166, height: 74 },
  posts: { width: 176, height: 74 },
  stories: { width: 176, height: 74 },
  comments: { width: 176, height: 74 },
  followAction: { width: 172, height: 48 },
  settingsAction: { width: 172, height: 48 },
};

const MIN_STAGE_WIDTH_EXTRA = 420;
const MIN_STAGE_HEIGHT = 560;
const STAGE_PADDING_X = 180;
const STAGE_PADDING_Y = 70;

export function buildProfileCanvasLayout(
  response: ProfileCanvasResponse,
  viewport: ProfileCanvasSize,
): ProfileCanvasLayout {
  const visibleNodes = withSyntheticNodes(response.nodes.filter(isKnownNode), response);
  const visibleIds = new Set<ProfileCanvasNodeId>(visibleNodes.map((node) => node.id));
  const rawBounds = relativeBounds(visibleNodes);
  const contentWidth = rawBounds.maxX - rawBounds.minX + STAGE_PADDING_X * 2;
  const contentHeight = rawBounds.maxY - rawBounds.minY + STAGE_PADDING_Y * 2;
  const stage = {
    width: Math.ceil(Math.max(viewport.width + MIN_STAGE_WIDTH_EXTRA, contentWidth)),
    height: Math.ceil(Math.max(viewport.height, contentHeight, MIN_STAGE_HEIGHT)),
  };
  const avatarCenter = { x: stage.width / 2, y: stage.height / 2 };

  const nodes = visibleNodes.map((node) => positionNode(node, avatarCenter));
  const nodeMap = new Map(nodes.map((node) => [node.id, node]));
  const edges = response.edges
    .filter((edge) => visibleIds.has(edge.source as ProfileCanvasNodeId) && visibleIds.has(edge.target as ProfileCanvasNodeId))
    .map((edge) => positionEdge(edge, nodeMap))
    .filter((edge): edge is PositionedProfileCanvasEdge => Boolean(edge));

  return {
    nodes,
    edges: [...edges, ...syntheticEdges(nodeMap)],
    stage,
    avatarCenter,
    initialScrollLeft: clamp(avatarCenter.x - viewport.width / 2, 0, Math.max(0, stage.width - viewport.width)),
  };
}

function isKnownNode(node: CanvasNode): node is CanvasNode & { id: ProfileCanvasNodeId } {
  return Boolean(node.id) && node.id in ORBIT_OFFSETS;
}

function withSyntheticNodes(
  nodes: Array<CanvasNode & { id: ProfileCanvasNodeId }>,
  response: ProfileCanvasResponse,
): Array<CanvasNode & { id: ProfileCanvasNodeId }> {
  if (!response.permissions.owner || nodes.some((node) => node.id === "settingsAction")) {
    return nodes;
  }

  return [
    ...nodes,
    {
      id: "settingsAction",
      type: "settings",
      position: { x: 0, y: 0 },
      data: { label: "Settings" },
    },
  ];
}

function positionNode(
  node: CanvasNode & { id: ProfileCanvasNodeId },
  avatarCenter: ProfileCanvasPoint,
): PositionedProfileCanvasNode {
  const size = NODE_SIZES[node.id];
  const offset = ORBIT_OFFSETS[node.id];
  const center = {
    x: avatarCenter.x + offset.x,
    y: avatarCenter.y + offset.y,
  };

  return {
    ...node,
    width: size.width,
    height: size.height,
    x: center.x - size.width / 2,
    y: center.y - size.height / 2,
    center,
  };
}

function positionEdge(
  edge: CanvasEdge,
  nodes: Map<ProfileCanvasNodeId, PositionedProfileCanvasNode>,
): PositionedProfileCanvasEdge | null {
  const source = nodes.get(edge.source as ProfileCanvasNodeId);
  const target = nodes.get(edge.target as ProfileCanvasNodeId);
  if (!source || !target) return null;

  return {
    ...edge,
    source: source.id,
    target: target.id,
    sourcePoint: source.center,
    targetPoint: target.center,
  };
}

function syntheticEdges(nodes: Map<ProfileCanvasNodeId, PositionedProfileCanvasNode>): PositionedProfileCanvasEdge[] {
  const avatar = nodes.get("avatar");
  const settings = nodes.get("settingsAction");
  if (!avatar || !settings) return [];

  return [{
    id: "avatar-settingsAction",
    source: avatar.id,
    target: settings.id,
    sourcePoint: avatar.center,
    targetPoint: settings.center,
  }];
}

function relativeBounds(nodes: Array<CanvasNode & { id: ProfileCanvasNodeId }>) {
  return nodes.reduce(
    (bounds, node) => {
      const size = NODE_SIZES[node.id];
      const offset = ORBIT_OFFSETS[node.id];
      return {
        minX: Math.min(bounds.minX, offset.x - size.width / 2),
        minY: Math.min(bounds.minY, offset.y - size.height / 2),
        maxX: Math.max(bounds.maxX, offset.x + size.width / 2),
        maxY: Math.max(bounds.maxY, offset.y + size.height / 2),
      };
    },
    { minX: -NODE_SIZES.avatar.width / 2, minY: -NODE_SIZES.avatar.height / 2, maxX: NODE_SIZES.avatar.width / 2, maxY: NODE_SIZES.avatar.height / 2 },
  );
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
