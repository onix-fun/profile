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

export interface AccountUser {
  id: string;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
}

export interface ProfileContentPost {
  id: string;
  authorId?: string | null;
  author?: AccountUser | null;
  authorName?: string | null;
  title?: string | null;
  text: string;
  blocks?: ContentBlock[];
  tags: string[];
  allowComments?: boolean;
  likeCount?: number;
  likedByViewer?: boolean;
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
  status: "OK" | "BLOCKED" | "PRIVATE";
  profile?: AccountProfile | null;
  content?: {
    posts: ProfileContentPost[];
    stories: ProfileContentStory[];
    comments: ProfileContentComment[];
  };
  relationship?: Relationship | null;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  permissions: {
    owner: boolean;
    canFollow: boolean;
  };
  viewport: CanvasViewport;
}

export type SocialFilter = "friends" | "subscribers" | "subscriptions";

export interface RelatedUser {
  id: string;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
  relationship?: Relationship | null;
}

export interface SocialCanvasResponse {
  owner: AccountProfile;
  filter: SocialFilter;
  items: RelatedUser[];
  totalCount: number;
  page: number;
  limit: number;
}

export interface ContentBlock {
  id?: string;
  type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "FILE";
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

export interface PostReactionState {
  postId: string;
  liked: boolean;
  likeCount: number;
}

export interface StoryReactionState {
  storyId: string;
  liked: boolean;
  likeCount: number;
}

export interface CommentReactionState {
  commentId: string;
  liked: boolean;
  likeCount: number;
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
  author?: AccountUser | null;
  authorName?: string;
  parentId?: string | null;
  text: string;
  blocks?: ContentBlock[];
  likeCount?: number;
  likedByViewer?: boolean;
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
  author?: {
    id?: string;
    username: string;
    firstName?: string | null;
    lastName?: string | null;
    avatarUrl?: string | null;
  } | null;
  visibility: "PUBLIC" | "CLOSE_FRIENDS";
  blocks: StoryBlock[];
  slides?: StorySlide[];
  durationMs?: number;
  mediaDurationMs?: number | null;
  closeFriends?: boolean;
  archived?: boolean;
  likeCount?: number;
  likedByViewer?: boolean;
  remainingLifeSeconds?: number | null;
  createdAt?: string | null;
  expiresAt?: string | null;
}

export interface StoryRailItem {
  authorId: string;
  authorName: string;
  author?: {
    id?: string;
    username: string;
    firstName?: string | null;
    lastName?: string | null;
    avatarUrl?: string | null;
  } | null;
  avatarUrl?: string | null;
  storyIds: string[];
  activeCount: number;
  seen: boolean;
  closeFriends: boolean;
  isViewer?: boolean;
  oldestAt?: string | null;
  latestAt?: string | null;
}

export interface StoryGroup {
  authorId: string;
  authorName: string;
  author?: Story["author"];
  avatarUrl?: string | null;
  stories: Story[];
  startStoryId?: string | null;
  archive: boolean;
}

export interface StoryArchiveResponse {
  ownerId: string;
  owner?: Story["author"];
  stories: Story[];
  cursor?: string | null;
  nextCursor?: string | null;
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

export interface AccountSearchUser {
  id: string;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
  bio?: string | null;
}
