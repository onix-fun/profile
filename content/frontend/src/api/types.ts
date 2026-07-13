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
  ownerType?: "USER" | "ORGANIZATION";
  username: string;
  displayName?: string | null;
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
  ownerType?: "USER" | "ORGANIZATION";
  username: string;
  displayName?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
}

export interface CurrentActor {
  user: SessionUser;
  activeOwner: AccountUser;
}

export interface ProfileContentPost {
  id: string;
  authorId?: string | null;
  ownerType?: "USER" | "ORGANIZATION";
  ownerId?: string | null;
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

export type CollectionVisibility = "PUBLIC" | "PRIVATE";

export interface SavedCollection {
  id: string;
  ownerType?: "USER" | "ORGANIZATION";
  ownerId: string;
  title: string;
  description?: string | null;
  cover?: Record<string, unknown> | null;
  visibility: CollectionVisibility;
  itemCount: number;
  previewBlocks: ContentBlock[];
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface CollectionDetail {
  collection: SavedCollection;
  posts: ProfileContentPost[];
}

export interface CreateCollectionInput {
  title: string;
  description?: string | null;
  cover?: Record<string, unknown> | null;
  visibility: CollectionVisibility;
}

export interface UpdateCollectionInput {
  id: string;
  title?: string;
  description?: string | null;
  cover?: Record<string, unknown> | null;
  visibility?: CollectionVisibility;
}

export interface PostCollectionsState {
  postId: string;
  collectionIds: string[];
}

export interface ProfileCanvasResponse {
  status: "OK" | "BLOCKED" | "PRIVATE";
  profile?: AccountProfile | null;
  content?: {
    posts: ProfileContentPost[];
    stories: ProfileContentStory[];
    comments: ProfileContentComment[];
    collections: SavedCollection[];
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
  ownerType?: "USER" | "ORGANIZATION";
  username: string;
  displayName?: string | null;
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

export interface UpdatePostInput {
  id: string;
  title?: string;
  text?: string;
  blocks?: PostBlock[];
  tags?: string[];
  allowComments?: boolean;
  visibility?: "PUBLIC" | "CLOSE_FRIENDS";
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

export interface FeedCell {
  q: number;
  r: number;
}

export type FeedEmphasis = "hero" | "standard" | "compact";

export interface RecommendationFeedInput {
  chunkX: number;
  chunkY: number;
  sessionSeed: string;
  limit?: number;
}

export interface RecommendationFeedItem extends FeedItem {
  cell: FeedCell;
  emphasis: FeedEmphasis;
}

export interface RecommendationFeedResponse {
  chunkX: number;
  chunkY: number;
  sessionSeed: string;
  items: RecommendationFeedItem[];
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
  chunkKey?: string;
  cell?: FeedCell;
  x: number;
  y: number;
  width: number;
  height: number;
  mediaType: ContentBlock["type"] | "TEXT";
  emphasis: FeedEmphasis;
}

export interface CommentItem {
  id: string;
  postId: string;
  authorId?: string;
  ownerType?: "USER" | "ORGANIZATION";
  ownerId?: string;
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

export interface UpdateCommentInput {
  id: string;
  text?: string;
  blocks?: ContentBlock[];
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
  ownerType?: "USER" | "ORGANIZATION";
  ownerId?: string;
  author?: {
    id?: string;
    ownerType?: "USER" | "ORGANIZATION";
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
  ownerType?: "USER" | "ORGANIZATION";
  ownerId?: string;
  authorName: string;
  author?: {
    id?: string;
    ownerType?: "USER" | "ORGANIZATION";
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
  ownerType?: "USER" | "ORGANIZATION";
  ownerId?: string;
  authorName: string;
  author?: Story["author"];
  avatarUrl?: string | null;
  stories: Story[];
  startStoryId?: string | null;
  archive: boolean;
}

export interface StoryArchiveResponse {
  ownerId: string;
  ownerType?: "USER" | "ORGANIZATION";
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
  ownerType?: "USER" | "ORGANIZATION";
  username: string;
  displayName?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  avatarUrl?: string | null;
  bio?: string | null;
}

export type UnifiedSearchType = "POST" | "COLLECTION" | "COMMENT" | "TAG";

export interface SearchItem {
  type: UnifiedSearchType;
  id: string;
  title?: string | null;
  snippet?: string | null;
  owner?: AccountUser | null;
  url: string;
  score: number;
  createdAt?: string | null;
  postId?: string | null;
  commentId?: string | null;
  tags: string[];
  meta: Record<string, string>;
}

export interface SearchResponse {
  query: string;
  items: SearchItem[];
  nextCursor?: string | null;
  partialErrors: string[];
}

export interface SearchSuggestion {
  type: "OWNER" | UnifiedSearchType | "TAG" | "RECENT";
  value: string;
  label: string;
  owner?: AccountSearchUser | null;
}

export interface SearchSuggestResponse {
  query: string;
  suggestions: SearchSuggestion[];
  partialErrors: string[];
}
