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
  /** Public Content v2 media. Tags intentionally are not exposed in feed/post responses. */
  assets?: PostAsset[];
  tags: string[];
  allowComments?: boolean;
  likeCount?: number;
  likedByViewer?: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
  status?: "ACTIVE" | "ARCHIVED" | "DELETED" | "DRAFT" | "HIDDEN";
  contentVersion?: number;
}

export type PostPublicationState = "DRAFT" | "PENDING_SOURCE" | "PROCESSING_MEDIA" | "PENDING_MEDIA" | "ACTIVE" | "NEEDS_MEDIA_ACTION" | "CANCELLED";

export type MediaSourceStatus = "UPLOADING" | "VERIFYING" | "AVAILABLE" | "REJECTED";
export type MediaProcessingStatus = "NONE" | "WAITING_SOURCE" | "QUEUED" | "PROCESSING" | "READY" | "FAILED" | "CANCELLED";
export type MediaDeliveryStatus = "NONE" | "READY";

export interface MediaFailure {
  code: string;
  permanent: boolean;
  userMessage: string;
}

export interface PostPublication {
  draftId: string;
  revision: number;
  state: PostPublicationState;
  idempotencyKey: string;
  requestedAt: string;
  activatedAt?: string | null;
  failureAssetIds: string[];
  processingRunIds?: Record<string, string>;
  revisionId?: string | null;
}

export type PostRevisionState = "DRAFT" | "PENDING_SOURCE" | "PROCESSING_MEDIA" | "ACTIVE" | "NEEDS_ACTION" | "SUPERSEDED" | "CANCELLED";

export interface PostEditorDocument {
  revisionId: string;
  postId: string;
  revisionNo: number;
  editVersion: number;
  state: PostRevisionState;
  assets: PostAsset[];
  tags: string[];
  allowComments: boolean;
  layoutAdjustments: string[];
  updatedAt: string;
}

export interface SavePostEditorDocumentInput {
  revisionId: string;
  editVersion: number;
  assets: PostAsset[];
  tags: string[];
  allowComments: boolean;
}

export interface EditorMediaAssetResult {
  assetId: string;
  asset?: PostAsset | null;
  failureCode?: string | null;
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
  type: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "FILE" | "GALLERY" | "LINK_CARD" | "CALLOUT" | "QUOTE" | "DIVIDER" | "CODE" | "CHECKLIST" | "POLL" | "TRUSTED_EMBED";
  data: Record<string, unknown>;
}

export type PostBlock = ContentBlock;

/**
 * Content v2 is deliberately media-first.  `PostAsset` is also used by the
 * editor while an upload is in flight; consumers must therefore tolerate a
 * missing delivery URL until the status becomes READY.
 */
/** Content projects are upload-only media: direct URLs and embeds are never accepted. */
export type PostAssetKind = "IMAGE" | "VIDEO" | "AUDIO";
export type PostAssetSourceKind = "UPLOAD";
export type PostAssetMediaType = PostAssetKind;
export type PostAssetStatus = "UPLOADING" | "VERIFYING" | "AVAILABLE" | "PROCESSING" | "READY" | "FAILED" | "CANCELLED";
export type AssetSizePreset = "S" | "M" | "L";

export interface PostAssetLayout {
  assetId: string;
  x: number;
  y: number;
  sizePreset: AssetSizePreset;
  layoutVersion: 1;
}

export interface PostAsset {
  id: string;
  /** Client-only identity for a File that has not received an asset id yet. */
  clientId?: string;
  kind: PostAssetKind;
  sourceKind?: PostAssetSourceKind;
  /** Read compatibility for a pre-v2 API response; new clients use `kind`. */
  mediaType?: PostAssetMediaType;
  assetId?: string | null;
  /** MediaStore delivery URL for an uploaded asset. */
  url?: string | null;
  posterUrl?: string | null;
  waveformUrl?: string | null;
  status: PostAssetStatus;
  sourceStatus?: MediaSourceStatus | null;
  processingStatus?: MediaProcessingStatus;
  deliveryStatus?: MediaDeliveryStatus;
  failure?: MediaFailure | null;
  width?: number | null;
  height?: number | null;
  durationMs?: number | null;
  failureReason?: string | null;
  variants?: Array<{ url: string; name?: string | null; width?: number | null; height?: number | null; mimeType?: string | null }>;
  generation?: number | null;
  processingRunId?: string | null;
  deliveryContract?: "STABLE_V2" | string | null;
  layout?: PostAssetLayout | null;
  /** The editor's local object URL. Never persist or send this field. */
  previewUrl?: string | null;
}

/** A single presigned PUT target returned for an upload part. */
export interface MediaAssetUploadPart {
  /** One-based multipart part number. */
  partNumber: number;
  url: string;
  /** Headers are part of the signature and must be passed through unchanged. */
  headers?: Record<string, string> | null;
}

/** Result of reserving a Media asset before its bytes are uploaded directly. */
export interface MediaAssetUploadSession {
  asset: PostAsset;
  sessionId: string;
  parts: MediaAssetUploadPart[];
  expiresAt?: string | null;
}

/** The completion proof for one uploaded multipart part. */
export interface CompletedMediaAssetPart {
  partNumber: number;
  /** S3 multipart completion requires the ETag returned by the presigned PUT. */
  etag: string;
}

export interface InitMediaAssetUploadInput {
  mimeType: string;
  expectedSize: number;
  partsCount: number;
  kind: PostAssetKind;
  sourcePolicyId?: "browser-native-v1" | "browser-capture-v1";
}

export interface MediaPostInput {
  assets?: PostAsset[];
}

export interface CreatePostInput {
  title?: string;
  /** Compatibility payloads are intentionally empty for media-first v2 posts. */
  text: string;
  blocks: PostBlock[];
  tags: string[];
  allowComments?: boolean;
  contentVersion?: number;
  assets?: PostAsset[];
}

export interface UpdatePostInput {
  id: string;
  title?: string;
  text?: string;
  blocks?: PostBlock[];
  tags?: string[];
  allowComments?: boolean;
  visibility?: "PUBLIC" | "CLOSE_FRIENDS";
  contentVersion?: number;
  assets?: PostAsset[];
}

export interface SavePostDraftInput {
  id?: string;
  title?: string;
  text: string;
  blocks: PostBlock[];
  tags: string[];
  allowComments: boolean;
  contentVersion?: number;
  assets?: PostAsset[];
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

/**
 * A stable, server-assigned place for a recommendation in the world canvas.
 * `cell` and `sessionSeed` remain optional legacy fields while older Content
 * servers are rolling out; new canvas code only consumes this placement.
 */
export interface RecommendationPlacement {
  constellationKey: string;
  salt: number;
  worldX: number;
  worldY: number;
  orbitOrder: number;
  sizePreset?: AssetSizePreset;
  placementVersion?: number;
}

export interface RecommendationConstellation {
  key: string;
  anchorX: number;
  anchorY: number;
  paletteKey?: string;
  postCount?: number;
}

export type FeedEmphasis = "hero" | "standard" | "compact";

export interface RecommendationFeedInput {
  chunkX: number;
  chunkY: number;
  /** Legacy compatibility only. The server placement is independent of it. */
  sessionSeed?: string;
  limit?: number;
}

export interface RecommendationFeedItem extends FeedItem {
  /** Legacy grid placement; never used by the redesigned canvas. */
  cell?: FeedCell;
  emphasis?: FeedEmphasis;
  placement?: RecommendationPlacement;
}

export interface RecommendationFeedResponse {
  chunkX: number;
  chunkY: number;
  /** Legacy compatibility only. */
  sessionSeed?: string;
  constellations?: RecommendationConstellation[];
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
  placement?: RecommendationPlacement;
  constellationKey?: string;
  orbitOrder?: number;
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
  replyToId?: string | null;
  text: string;
  document?: CommentDocumentV1 | null;
  blocks?: ContentBlock[];
  /** Comment attachments are image/video only and never include embeds or audio. */
  attachments?: PostAsset[];
  /** Legacy client alias; new Content responses use `attachments`. */
  assets?: PostAsset[];
  status?: "ACTIVE" | "ARCHIVED" | "DELETED" | "DRAFT" | "HIDDEN";
  likeCount?: number;
  likedByViewer?: boolean;
  createdAt?: string | null;
  replies?: CommentItem[];
  pinnedAt?: string | null;
  replyCount?: number;
  deletedAt?: string | null;
  hiddenAt?: string | null;
  editedAt?: string | null;
  /** Server emits a tombstone instead of dropping a deleted parent with replies. */
  tombstone?: boolean;
}

export type CommentSort = "TOP" | "NEWEST" | "OLDEST";

export interface CommentThreadResponse {
  comments: CommentItem[];
  totalCount: number;
  sort: CommentSort;
  parentId?: string | null;
  nextCursor?: string | null;
}

export interface CommentThreadInput {
  postId: string;
  parentId?: string | null;
  cursor?: string | null;
  sort?: CommentSort;
  limit?: number;
}

export interface UpdateCommentInput {
  id: string;
  text?: string;
  blocks?: ContentBlock[];
  attachments?: PostAsset[];
  document?: CommentDocumentV1;
}

export type CommentDocumentBlockType = "PARAGRAPH" | "HEADING" | "BULLET_LIST" | "ORDERED_LIST" | "CHECKLIST" | "QUOTE" | "CODE" | "DIVIDER" | "MEDIA";
export type CommentDocumentMarkType = "BOLD" | "ITALIC" | "STRIKE" | "INLINE_CODE" | "LINK" | "MENTION";

export interface CommentInlineMark {
  type: CommentDocumentMarkType;
  href?: string | null;
  ownerType?: "USER" | "ORGANIZATION" | null;
  ownerId?: string | null;
  label?: string | null;
}

export interface CommentInlineNode { text: string; marks?: CommentInlineMark[]; }

export interface CommentDocumentBlock {
  id: string;
  type: CommentDocumentBlockType;
  level?: 2 | 3 | null;
  content?: CommentInlineNode[];
  items?: string[];
  checked?: boolean[];
  assetId?: string | null;
  language?: string | null;
}

export interface CommentDocumentV1 { version: 1; blocks: CommentDocumentBlock[]; }

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

export interface StoryArchivePeriod {
  period: string;
  count: number;
  latestStoryId?: string | null;
}

export interface StoryArchivePeriodsResponse {
  ownerId: string;
  ownerType?: "USER" | "ORGANIZATION";
  periods: StoryArchivePeriod[];
}

export interface PollVoteState {
  postId: string;
  blockId: string;
  optionId: string;
  counts: Record<string, number>;
  closed: boolean;
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
