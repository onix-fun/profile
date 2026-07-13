import type { CanvasEdge, CanvasNode, ProfileCanvasResponse, ProfileContentPost, SavedCollection } from "@/api/types";

export type ProfileKnownNodeId =
  | "avatar"
  | "displayName"
  | "username"
  | "bio"
  | "socialLinks"
  | "birthday"
  | "social"
  | "archive"
  | "followAction";

export type ProfilePostNodeId = `post:${string}`;
export type ProfileCollectionNodeId = `collection:${string}`;
export type ProfileCanvasNodeId = ProfileKnownNodeId | ProfilePostNodeId | ProfileCollectionNodeId;

export interface ProfileCanvasLayoutOptions {
  hasArchive?: boolean;
  archiveCount?: number;
  mode?: "posts" | "collections";
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
  socialLinks: { x: -310, y: 202 },
  birthday: { x: -292, y: 116 },
  social: { x: -310, y: -50 },
  archive: { x: -210, y: -234 },
  followAction: { x: 0, y: 142 },
};

const NODE_SIZES: Record<ProfileKnownNodeId, ProfileCanvasSize> = {
  avatar: { width: 132, height: 132 },
  displayName: { width: 220, height: 54 },
  username: { width: 190, height: 50 },
  bio: { width: 280, height: 88 },
  socialLinks: { width: 332, height: 184 },
  birthday: { width: 170, height: 52 },
  social: { width: 214, height: 94 },
  archive: { width: 176, height: 74 },
  followAction: { width: 172, height: 48 },
};

const POST_NODE_SIZE: ProfileCanvasSize = { width: 136, height: 136 };
const COLLECTION_NODE_SIZE: ProfileCanvasSize = { width: 176, height: 146 };
const MIN_STAGE_WIDTH_EXTRA = 420;
const STAGE_PADDING_X = 180;
const COLLISION_GAP = 18;
const POST_GRID_START_X = 346;
const POST_GRID_COLUMN_GAP = 46;
const POST_GRID_ROW_GAP = 20;

export function buildProfileCanvasLayout(
  response: ProfileCanvasResponse,
  viewport: ProfileCanvasSize,
  options: ProfileCanvasLayoutOptions = {},
): ProfileCanvasLayout {
  const visibleNodes = [
    ...response.nodes.filter(isKnownNode),
    ...(options.hasArchive ? [archiveNode(options.archiveCount || 0)] : []),
    ...(options.mode === "collections" ? collectionNodes(response.content?.collections || []) : postNodes(response.content?.posts || [])),
  ];
  const visibleIds = new Set<ProfileCanvasNodeId>(visibleNodes.map((node) => node.id));
  const verticalScale = Math.min(1, Math.max(0.58, (viewport.height / 2 - 62) / 300));
  const itemCount = options.mode === "collections" ? response.content?.collections?.length || 0 : response.content?.posts?.length || 0;
  const itemOffsets = profileItemOffsets(itemCount, viewport, options.mode === "collections" ? COLLECTION_NODE_SIZE : POST_NODE_SIZE);
  const relativeNodes = resolveCollisions(visibleNodes.map((node) => positionRelativeNode(node, verticalScale, itemOffsets)), viewport);
  const rawBounds = boundsFor(relativeNodes);
  const contentWidth = Math.max(Math.abs(rawBounds.minX), Math.abs(rawBounds.maxX)) * 2 + STAGE_PADDING_X * 2;
  const stage = {
    width: Math.ceil(Math.max(viewport.width + MIN_STAGE_WIDTH_EXTRA, contentWidth)),
    height: Math.ceil(viewport.height),
  };
  const avatarCenter = { x: stage.width / 2, y: stage.height / 2 };

  const nodes = relativeNodes.map((node) => positionAbsoluteNode(node, avatarCenter));
  const nodeMap = new Map(nodes.map((node) => [node.id, node]));
  const edges = response.edges
    .filter((edge) => !edge.target.startsWith("post:"))
    .filter((edge) => visibleIds.has(edge.source as ProfileCanvasNodeId) && visibleIds.has(edge.target as ProfileCanvasNodeId))
    .map((edge) => positionEdge(edge, nodeMap))
    .filter((edge): edge is PositionedProfileCanvasEdge => Boolean(edge));
  const archiveEdge = options.hasArchive ? syntheticEdge("avatar", "archive", nodeMap) : null;

  return {
    nodes,
    edges: [...edges, ...(archiveEdge ? [archiveEdge] : [])],
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

function isCollectionNodeId(id: ProfileCanvasNodeId): id is ProfileCollectionNodeId {
  return id.startsWith("collection:");
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

function collectionNodes(collections: SavedCollection[]): Array<CanvasNode & { id: ProfileCollectionNodeId }> {
  return [...collections]
    .sort((a, b) => Date.parse(b.updatedAt || b.createdAt || "") - Date.parse(a.updatedAt || a.createdAt || ""))
    .map((collection, index) => ({
      id: `collection:${collection.id}` as ProfileCollectionNodeId,
      type: "collection",
      position: { x: 0, y: 0 },
      data: {
        collectionId: collection.id,
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
  node: (CanvasNode & { id: ProfileKnownNodeId }) | (CanvasNode & { id: ProfilePostNodeId }) | (CanvasNode & { id: ProfileCollectionNodeId }),
  verticalScale: number,
  itemOffsets: ProfileCanvasPoint[],
): PositionedProfileCanvasNode {
  const isRightItem = isPostNodeId(node.id) || isCollectionNodeId(node.id);
  const size = isCollectionNodeId(node.id) ? COLLECTION_NODE_SIZE : isPostNodeId(node.id) ? POST_NODE_SIZE : NODE_SIZES[node.id];
  const offset = isRightItem
    ? itemOffsets[Number(node.data.index || 0)] || itemOffsets[0] || { x: POST_GRID_START_X, y: 0 }
    : ORBIT_OFFSETS[node.id as ProfileKnownNodeId];
  const center = {
    x: offset.x,
    y: isRightItem ? offset.y : offset.y * verticalScale,
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

function profileItemOffsets(count: number, viewport: ProfileCanvasSize, size: ProfileCanvasSize): ProfileCanvasPoint[] {
  if (count <= 0) return [];
  const maxY = Math.max(110, viewport.height / 2 - size.height / 2 - 18);
  const rowPitch = size.height + POST_GRID_ROW_GAP;
  const maxRows = Math.max(1, Math.floor((maxY * 2 + POST_GRID_ROW_GAP) / rowPitch));
  const rowCount = Math.max(1, Math.min(5, maxRows));
  const columnPitch = size.width + POST_GRID_COLUMN_GAP;
  const offsets: ProfileCanvasPoint[] = [];
  let column = 0;

  while (offsets.length < count) {
    const rowsInColumn = column % 2 === 1 && rowCount > 2 ? rowCount - 1 : rowCount;
    const yStart = -((rowsInColumn - 1) * rowPitch) / 2;
    for (let row = 0; row < rowsInColumn && offsets.length < count; row += 1) {
      offsets.push({
        x: POST_GRID_START_X + column * columnPitch,
        y: yStart + row * rowPitch,
      });
    }
    column += 1;
  }

  return offsets;
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

function resolveCollisions(nodes: PositionedProfileCanvasNode[], viewport: ProfileCanvasSize): PositionedProfileCanvasNode[] {
  const placed: PositionedProfileCanvasNode[] = [];
  const maxY = Math.max(110, viewport.height / 2 - 68);
  for (const node of nodes) {
    let next = clampNodeY(node, maxY);
    let attempt = 0;
    while (placed.some((item) => overlaps(item, next)) && attempt < 80) {
      attempt += 1;
      const radius = Math.ceil(attempt / 8) * COLLISION_GAP;
      const angle = attempt * 0.72;
      next = moveNode(node, Math.cos(angle) * radius, Math.sin(angle) * radius);
      next = clampNodeY(next, maxY);
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

function clampNodeY(node: PositionedProfileCanvasNode, maxY: number): PositionedProfileCanvasNode {
  const nextY = clamp(node.center.y, -maxY, maxY);
  return moveNode(node, 0, nextY - node.center.y);
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
