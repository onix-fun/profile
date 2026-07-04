export interface Position {
  x: number;
  y: number;
}

export interface CanvasNode {
  id: string;
  type: string;
  position: Position;
  data: Record<string, unknown>;
}

export interface CanvasEdge {
  id: string;
  source: string;
  target: string;
}

export interface CanvasViewport {
  x: number;
  y: number;
  zoom: number;
}

export interface Relationship {
  isFollowing: boolean;
  isFollowedBy: boolean;
  isFriend: boolean;
  isBlocked: boolean;
  hasPendingRequest: boolean;
}

export interface AccountProfile {
  id: string;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
  bio?: string | null;
  birthday?: { day: number; month: number } | null;
  socialLinks: Array<{ label: string; url: string }>;
  avatarUrl?: string | null;
  followersCount: number;
  followingCount: number;
  isPrivate: boolean;
  relationship: Relationship;
}

export interface ProfileCanvasResponse {
  status: "OK" | "BLOCKED";
  profile?: AccountProfile | null;
  relationship?: Relationship | null;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  permissions: {
    owner: boolean;
    canFollow: boolean;
  };
  viewport: CanvasViewport;
}

export interface SessionUser {
  id: string;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
}
