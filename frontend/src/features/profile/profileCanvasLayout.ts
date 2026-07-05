import type { CanvasEdge, CanvasNode, ProfileCanvasResponse, ProfileContentPost } from "@/api/types";

export type ProfileKnownNodeId =
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
  | "archive"
  | "comments"
  | "followAction";

export type ProfilePostNodeId = `post:${string}`;
export type ProfileCanvasNodeId = ProfileKnownNodeId | ProfilePostNodeId;

export interface ProfileCanvasLayoutOptions {
  hasArchive?: boolean;
  archiveCount?: number;
}

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

export const ORBIT_OFFSETS: Record<ProfileKnownNodeId, ProfileCanvasPoint> = {
  avatar: { x: 0, y: 0 },
  displayName: { x: 0, y: -230 },
  username: { x: 0, y: -158 },
  bio: { x: 0, y: 214 },
  socialLinks: { x: -250, y: 190 },
  birthday: { x: -292, y: 116 },
  followers: { x: -310, y: -56 },
  following: { x: -310, y: 52 },
  posts: { x: 360, y: -190 },
  stories: { x: -292, y: -162 },
  archive: { x: -210, y: -234 },
  comments: { x: -292, y: 162 },
  followAction: { x: 0, y: 142 },
};

const NODE_SIZES: Record<ProfileKnownNodeId, ProfileCanvasSize> = {
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
  archive: { width: 176, height: 74 },
  comments: { width: 176, height: 74 },
  followAction: { width: 172, height: 48 },
};

const POST_NODE_SIZE: ProfileCanvasSize = { width: 224, height: 138 };
const MIN_STAGE_WIDTH_EXTRA = 420;
const MIN_STAGE_HEIGHT = 560;
const STAGE_PADDING_X = 180;
const STAGE_PADDING_Y = 70;
const COLLISION_GAP = 18;

export function buildProfileCanvasLayout(
  response: ProfileCanvasResponse,
  viewport: ProfileCanvasSize,
  options: ProfileCanvasLayoutOptions = {},
): ProfileCanvasLayout {
  const visibleNodes = [
    ...response.nodes.filter(isKnownNode).filter((node) => node.id !== "posts"),
    ...(options.hasArchive ? [archiveNode(options.archiveCount || 0)] : []),
    ...postNodes(response.content?.posts || []),
  ];
  const visibleIds = new Set<ProfileCanvasNodeId>(visibleNodes.map((node) => node.id));
  const relativeNodes = resolveCollisions(visibleNodes.map(positionRelativeNode));
  const rawBounds = boundsFor(relativeNodes);
  const contentWidth = Math.max(Math.abs(rawBounds.minX), Math.abs(rawBounds.maxX)) * 2 + STAGE_PADDING_X * 2;
  const contentHeight = Math.max(Math.abs(rawBounds.minY), Math.abs(rawBounds.maxY)) * 2 + STAGE_PADDING_Y * 2;
  const stage = {
    width: Math.ceil(Math.max(viewport.width + MIN_STAGE_WIDTH_EXTRA, contentWidth)),
    height: Math.ceil(Math.max(viewport.height, contentHeight, MIN_STAGE_HEIGHT)),
  };
  const avatarCenter = { x: stage.width / 2, y: stage.height / 2 };

  const nodes = relativeNodes.map((node) => positionAbsoluteNode(node, avatarCenter));
  const nodeMap = new Map(nodes.map((node) => [node.id, node]));
  const edges = response.edges
    .filter((edge) => visibleIds.has(edge.source as ProfileCanvasNodeId) && visibleIds.has(edge.target as ProfileCanvasNodeId))
    .map((edge) => positionEdge(edge, nodeMap))
    .filter((edge): edge is PositionedProfileCanvasEdge => Boolean(edge));
  const postEdges = nodes
    .filter((node) => isPostNodeId(node.id))
    .map((node) => syntheticEdge("avatar", node.id, nodeMap))
    .filter((edge): edge is PositionedProfileCanvasEdge => Boolean(edge));
  const archiveEdge = options.hasArchive ? syntheticEdge("avatar", "archive", nodeMap) : null;

  return {
    nodes,
    edges: [...edges, ...postEdges, ...(archiveEdge ? [archiveEdge] : [])],
    stage,
    avatarCenter,
    initialScrollLeft: clamp(avatarCenter.x - viewport.width / 2, 0, Math.max(0, stage.width - viewport.width)),
  };
}

function isKnownNode(node: CanvasNode): node is CanvasNode & { id: ProfileKnownNodeId } {
  return Boolean(node.id) && node.id in ORBIT_OFFSETS;
}

function isPostNodeId(id: ProfileCanvasNodeId): id is ProfilePostNodeId {
  return id.startsWith("post:");
}

function postNodes(posts: ProfileContentPost[]): Array<CanvasNode & { id: ProfilePostNodeId }> {
  return [...posts]
    .sort((a, b) => Date.parse(b.createdAt || "") - Date.parse(a.createdAt || ""))
    .map((post, index) => ({
      id: `post:${post.id}` as ProfilePostNodeId,
      type: "post",
      position: { x: 0, y: 0 },
      data: {
        postId: post.id,
        index,
      },
    }));
}

function archiveNode(count: number): CanvasNode & { id: "archive" } {
  return {
    id: "archive",
    type: "archive",
    position: { x: 0, y: 0 },
    data: {
      label: count > 0 ? String(count) : "Archive",
      caption: "Archive",
    },
  };
}

function positionRelativeNode(
  node: (CanvasNode & { id: ProfileKnownNodeId }) | (CanvasNode & { id: ProfilePostNodeId }),
): PositionedProfileCanvasNode {
  const size = isPostNodeId(node.id) ? POST_NODE_SIZE : NODE_SIZES[node.id];
  const offset = isPostNodeId(node.id) ? postOffset(Number(node.data.index || 0)) : ORBIT_OFFSETS[node.id];
  const center = {
    x: offset.x,
    y: offset.y,
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

function positionAbsoluteNode(
  node: PositionedProfileCanvasNode,
  avatarCenter: ProfileCanvasPoint,
): PositionedProfileCanvasNode {
  const center = {
    x: avatarCenter.x + node.center.x,
    y: avatarCenter.y + node.center.y,
  };
  return {
    ...node,
    x: center.x - node.width / 2,
    y: center.y - node.height / 2,
    center,
  };
}

function postOffset(index: number): ProfileCanvasPoint {
  const column = Math.floor(index / 4);
  const row = index % 4;
  const x = 370 + column * 260;
  const y = [-156, -48, 68, 184][row] + column * 18;
  return { x, y };
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

function syntheticEdge(
  sourceId: ProfileCanvasNodeId,
  targetId: ProfileCanvasNodeId,
  nodes: Map<ProfileCanvasNodeId, PositionedProfileCanvasNode>,
): PositionedProfileCanvasEdge | null {
  const source = nodes.get(sourceId);
  const target = nodes.get(targetId);
  if (!source || !target) return null;
  return {
    id: `${sourceId}-${targetId}`,
    source: source.id,
    target: target.id,
    sourcePoint: source.center,
    targetPoint: target.center,
  };
}

function resolveCollisions(nodes: PositionedProfileCanvasNode[]): PositionedProfileCanvasNode[] {
  const placed: PositionedProfileCanvasNode[] = [];
  for (const node of nodes) {
    let next = node;
    let attempt = 0;
    while (placed.some((item) => overlaps(item, next)) && attempt < 80) {
      attempt += 1;
      const radius = Math.ceil(attempt / 8) * COLLISION_GAP;
      const angle = attempt * 0.72;
      next = moveNode(node, Math.cos(angle) * radius, Math.sin(angle) * radius);
    }
    placed.push(next);
  }
  return placed;
}

function moveNode(node: PositionedProfileCanvasNode, dx: number, dy: number): PositionedProfileCanvasNode {
  const center = { x: node.center.x + dx, y: node.center.y + dy };
  return {
    ...node,
    x: center.x - node.width / 2,
    y: center.y - node.height / 2,
    center,
  };
}

function overlaps(a: PositionedProfileCanvasNode, b: PositionedProfileCanvasNode): boolean {
  return a.x < b.x + b.width + COLLISION_GAP
    && a.x + a.width + COLLISION_GAP > b.x
    && a.y < b.y + b.height + COLLISION_GAP
    && a.y + a.height + COLLISION_GAP > b.y;
}

function boundsFor(nodes: PositionedProfileCanvasNode[]) {
  return nodes.reduce(
    (bounds, node) => {
      return {
        minX: Math.min(bounds.minX, node.x),
        minY: Math.min(bounds.minY, node.y),
        maxX: Math.max(bounds.maxX, node.x + node.width),
        maxY: Math.max(bounds.maxY, node.y + node.height),
      };
    },
    { minX: -NODE_SIZES.avatar.width / 2, minY: -NODE_SIZES.avatar.height / 2, maxX: NODE_SIZES.avatar.width / 2, maxY: NODE_SIZES.avatar.height / 2 },
  );
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
