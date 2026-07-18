import type { AccountProfile, RelatedUser } from "@/shared/api/types";

export interface SocialCanvasSize {
  width: number;
  height: number;
}

export interface SocialCanvasPoint {
  x: number;
  y: number;
}

export interface SocialCanvasUserNode {
  id: string;
  user: RelatedUser;
  x: number;
  y: number;
  width: number;
  height: number;
  center: SocialCanvasPoint;
}

export interface SocialCanvasOwnerNode {
  owner: AccountProfile;
  x: number;
  y: number;
  width: number;
  height: number;
  center: SocialCanvasPoint;
}

export interface SocialCanvasLayout {
  ownerNode: SocialCanvasOwnerNode;
  userNodes: SocialCanvasUserNode[];
  stage: SocialCanvasSize;
  center: SocialCanvasPoint;
  initialScrollLeft: number;
}

const OWNER_SIZE: SocialCanvasSize = { width: 148, height: 148 };
const USER_SIZE: SocialCanvasSize = { width: 236, height: 82 };
const STAGE_PADDING_X = 260;
const STAGE_MIN_EXTRA_X = 680;
const VERTICAL_PADDING = 108;
const COLLISION_GAP = 16;

export function buildSocialCanvasLayout(
  owner: AccountProfile,
  users: RelatedUser[],
  viewport: SocialCanvasSize,
): SocialCanvasLayout {
  const relativeUsers = resolveUserCollisions(users.map(positionRelativeUser), viewport);
  const bounds = boundsFor(relativeUsers);
  const contentWidth = Math.max(Math.abs(bounds.minX), Math.abs(bounds.maxX)) * 2 + STAGE_PADDING_X * 2;
  const stage = {
    width: Math.ceil(Math.max(viewport.width + STAGE_MIN_EXTRA_X, contentWidth)),
    height: Math.max(420, viewport.height),
  };
  const center = { x: stage.width / 2, y: stage.height / 2 };
  const ownerNode = {
    owner,
    width: OWNER_SIZE.width,
    height: OWNER_SIZE.height,
    x: center.x - OWNER_SIZE.width / 2,
    y: center.y - OWNER_SIZE.height / 2,
    center,
  };
  const userNodes = relativeUsers.map((node) => {
    const nextCenter = { x: center.x + node.center.x, y: center.y + node.center.y };
    return {
      ...node,
      x: nextCenter.x - node.width / 2,
      y: nextCenter.y - node.height / 2,
      center: nextCenter,
    };
  });

  return {
    ownerNode,
    userNodes,
    stage,
    center,
    initialScrollLeft: clamp(center.x - viewport.width / 2, 0, Math.max(0, stage.width - viewport.width)),
  };
}

function positionRelativeUser(user: RelatedUser, index: number): SocialCanvasUserNode {
  const side = index % 2 === 0 ? 1 : -1;
  const lane = Math.floor(index / 2);
  const column = Math.floor(lane / 4);
  const row = lane % 4;
  const baseX = 230 + column * 268 + (row % 2) * 34;
  const rowY = [-150, -48, 62, 160][row];
  const yJitter = ((stableHash(user.id || user.username) % 45) - 22);
  const center = {
    x: side * baseX,
    y: rowY + yJitter,
  };
  return {
    id: user.id,
    user,
    width: USER_SIZE.width,
    height: USER_SIZE.height,
    x: center.x - USER_SIZE.width / 2,
    y: center.y - USER_SIZE.height / 2,
    center,
  };
}

function resolveUserCollisions(nodes: SocialCanvasUserNode[], viewport: SocialCanvasSize): SocialCanvasUserNode[] {
  const placed: SocialCanvasUserNode[] = [];
  const maxY = Math.max(90, viewport.height / 2 - VERTICAL_PADDING);

  for (const node of nodes) {
    let next = clampUserY(node, maxY);
    let attempt = 0;
    while (placed.some((item) => overlaps(item, next)) && attempt < 140) {
      attempt += 1;
      const radius = Math.ceil(attempt / 8) * COLLISION_GAP;
      const angle = attempt * 1.37;
      const horizontalBias = next.center.x >= 0 ? 1 : -1;
      next = moveUserNode(node, Math.cos(angle) * radius + horizontalBias * radius * 0.85, Math.sin(angle) * radius * 0.72);
      next = clampUserY(next, maxY);
    }
    placed.push(next);
  }
  return placed;
}

function moveUserNode(node: SocialCanvasUserNode, dx: number, dy: number): SocialCanvasUserNode {
  const center = { x: node.center.x + dx, y: node.center.y + dy };
  return {
    ...node,
    x: center.x - node.width / 2,
    y: center.y - node.height / 2,
    center,
  };
}

function clampUserY(node: SocialCanvasUserNode, maxY: number): SocialCanvasUserNode {
  const y = clamp(node.center.y, -maxY, maxY);
  return moveUserNode(node, 0, y - node.center.y);
}

export function socialNodesOverlap(a: Pick<SocialCanvasUserNode, "x" | "y" | "width" | "height">, b: Pick<SocialCanvasUserNode, "x" | "y" | "width" | "height">): boolean {
  return a.x < b.x + b.width + COLLISION_GAP
    && a.x + a.width + COLLISION_GAP > b.x
    && a.y < b.y + b.height + COLLISION_GAP
    && a.y + a.height + COLLISION_GAP > b.y;
}

function overlaps(a: SocialCanvasUserNode, b: SocialCanvasUserNode): boolean {
  return socialNodesOverlap(a, b);
}

function boundsFor(nodes: SocialCanvasUserNode[]) {
  return nodes.reduce(
    (bounds, node) => ({
      minX: Math.min(bounds.minX, node.x),
      maxX: Math.max(bounds.maxX, node.x + node.width),
    }),
    { minX: -OWNER_SIZE.width / 2, maxX: OWNER_SIZE.width / 2 },
  );
}

function stableHash(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash);
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
