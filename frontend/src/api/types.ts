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

export interface ProfileContentPost {
  id: string;
  title?: string | null;
  text: string;
  tags: string[];
  createdAt?: string | null;
}

export interface ProfileContentStory {
  id: string;
  visibility: string;
  expiresAt?: string | null;
}

export interface ProfileContentComment {
  id: string;
  postId: string;
  text: string;
  createdAt?: string | null;
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

export interface ContentBlock {
  id?: string;
  type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO";
  data: Record<string, unknown>;
}

export type PostBlock = ContentBlock;

export interface CreatePostInput {
  title?: string;
  text: string;
  blocks: PostBlock[];
  tags: string[];
  allowComments?: boolean;
}

export interface FeedItem {
  post: ProfileContentPost & {
    authorId: string;
    blocks: ContentBlock[];
    visibility: "PUBLIC" | "CLOSE_FRIENDS";
  };
  score: number;
  reasons: string[];
}

export interface CanvasPostNode {
  id: string;
  item: FeedItem;
  x: number;
  y: number;
  width: number;
  height: number;
  mediaType: ContentBlock["type"] | "TEXT";
  emphasis: "hero" | "standard" | "compact";
}

export interface CommentItem {
  id: string;
  postId: string;
  authorId?: string;
  authorName?: string;
  parentId?: string | null;
  text: string;
  createdAt?: string | null;
  replies?: CommentItem[];
}

export interface StoryBlock {
  id?: string;
  type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO";
  data: Record<string, unknown>;
}

export interface StorySlide {
  id: string;
  blocks: StoryBlock[];
  durationMs: number;
  background?: string;
  caption?: string;
  tags?: string[];
}

export interface Story {
  id: string;
  authorId: string;
  visibility: "PUBLIC" | "CLOSE_FRIENDS";
  blocks: StoryBlock[];
  slides?: StorySlide[];
  createdAt?: string | null;
  expiresAt?: string | null;
}

export interface StoryRailItem {
  authorId: string;
  authorName: string;
  avatarUrl?: string | null;
  storyIds: string[];
  activeCount: number;
  seen: boolean;
  closeFriends: boolean;
  latestAt?: string | null;
}

export interface CreateStoryInput {
  blocks: StoryBlock[];
  caption?: string;
  tags?: string[];
  visibility: "PUBLIC" | "CLOSE_FRIENDS";
}

export interface SessionUser {
  id: string;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
}
