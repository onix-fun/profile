package com.onix.content.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.util.UUID

object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
enum class Visibility { PUBLIC, CLOSE_FRIENDS }

@Serializable
enum class ContentStatus { ACTIVE, ARCHIVED, DELETED, DRAFT, HIDDEN }

@Serializable
enum class CollectionVisibility { PUBLIC, PRIVATE }

@Serializable
enum class ContentBlockType {
    TEXT, IMAGE, VIDEO, AUDIO, FILE,
    GALLERY, LINK_CARD, CALLOUT, QUOTE, DIVIDER, CODE, CHECKLIST, POLL, TRUSTED_EMBED
}

/**
 * The v2 post surface is deliberately media-first.  These types are kept
 * independent from the legacy [ContentBlock] vocabulary because a media
 * project is not an ordered Markdown document.
 */
@Serializable
enum class PostAssetKind { IMAGE, VIDEO, AUDIO }

@Serializable
enum class PostAssetSourceKind { UPLOAD }

@Serializable
enum class AssetSizePreset { S, M, L }

/** Stable world-space placement for one asset on a project canvas. */
@Serializable
data class PostAssetLayout(
    val assetId: String,
    val x: Int,
    val y: Int,
    val sizePreset: AssetSizePreset = AssetSizePreset.M,
    val layoutVersion: Int = 1
)

@Serializable
enum class MediaAssetStatus { UPLOADING, VERIFYING, AVAILABLE, PROCESSING, READY, FAILED, CANCELLED }

@Serializable
enum class MediaSourceStatus { UPLOADING, VERIFYING, AVAILABLE, REJECTED }

@Serializable
enum class MediaProcessingStatus { NONE, WAITING_SOURCE, QUEUED, PROCESSING, READY, FAILED, CANCELLED }

@Serializable
enum class MediaDeliveryStatus { NONE, READY }

@Serializable
data class MediaFailure(
    val code: String,
    val permanent: Boolean = false,
    val userMessage: String
)

@Serializable
enum class PostPublicationState { DRAFT, PENDING_SOURCE, PROCESSING_MEDIA, PENDING_MEDIA, ACTIVE, NEEDS_MEDIA_ACTION, CANCELLED }

/** Owner-only state for an asynchronous v2 media publication request. */
@Serializable
data class PostPublication(
    val draftId: String,
    val revision: Long,
    val state: PostPublicationState,
    val idempotencyKey: String,
    @Serializable(with = InstantIsoSerializer::class)
    val requestedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val activatedAt: Instant? = null,
    val failureAssetIds: List<String> = emptyList()
    ,val processingRunIds: Map<String, String> = emptyMap(),
    val revisionId: String? = null
)

@Serializable
enum class PostRevisionState { DRAFT, PENDING_SOURCE, PROCESSING_MEDIA, ACTIVE, NEEDS_ACTION, SUPERSEDED, CANCELLED }

@Serializable
data class PostEditorDocument(
    val revisionId: String,
    val postId: String,
    val revisionNo: Long,
    val editVersion: Long,
    val state: PostRevisionState,
    val assets: List<PostAsset>,
    val tags: List<String>,
    val allowComments: Boolean,
    val layoutAdjustments: List<String> = emptyList(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = Instant.now()
)

@Serializable
data class SavePostEditorDocumentInput(
    val revisionId: String,
    val editVersion: Long,
    val assets: List<PostAsset>,
    val tags: List<String>,
    val allowComments: Boolean = true
)

@Serializable
data class EditorMediaAssetResult(
    val assetId: String,
    val asset: PostAsset? = null,
    val failureCode: String? = null
)

@Serializable
data class AssetVariant(
    val url: String = "",
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null
)

/**
 * A renderable item in a v2 project.  [assetId] is the Media service asset
 * identifier for uploads. Projects accept only MediaStore-owned files.
 */
@Serializable
data class PostAsset(
    val id: String = UUID.randomUUID().toString(),
    val kind: PostAssetKind,
    val sourceKind: PostAssetSourceKind = PostAssetSourceKind.UPLOAD,
    val assetId: String? = null,
    val url: String? = null,
    val provider: String? = null,
    val status: MediaAssetStatus = MediaAssetStatus.READY,
    val sourceStatus: MediaSourceStatus? = null,
    val processingStatus: MediaProcessingStatus = MediaProcessingStatus.NONE,
    val deliveryStatus: MediaDeliveryStatus = MediaDeliveryStatus.NONE,
    val failure: MediaFailure? = null,
    val variants: List<AssetVariant> = emptyList(),
    val posterUrl: String? = null,
    val waveformUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val failureReason: String? = null
    ,val generation: Long? = null
    ,val processingRunId: String? = null
    ,val deliveryContract: String? = null,
    val layout: PostAssetLayout? = null
)

@Serializable
enum class CommentBlockType { PARAGRAPH, HEADING, BULLET_LIST, ORDERED_LIST, CHECKLIST, QUOTE, CODE, DIVIDER, MEDIA }

@Serializable
enum class CommentMarkType { BOLD, ITALIC, STRIKE, INLINE_CODE, LINK, MENTION }

@Serializable
data class CommentInlineMark(
    val type: CommentMarkType,
    val href: String? = null,
    val ownerType: OwnerType? = null,
    val ownerId: String? = null,
    val label: String? = null
)

@Serializable
data class CommentInlineNode(
    val text: String,
    val marks: List<CommentInlineMark> = emptyList()
)

@Serializable
data class CommentDocumentBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: CommentBlockType,
    val level: Int? = null,
    val content: List<CommentInlineNode> = emptyList(),
    val items: List<String> = emptyList(),
    val checked: List<Boolean> = emptyList(),
    val assetId: String? = null,
    val language: String? = null
)

@Serializable
data class CommentDocumentV1(
    val version: Int = 1,
    val blocks: List<CommentDocumentBlock> = emptyList()
)

@Serializable
data class AssetUploadPartInput(
    val partNumber: Int,
    val etag: String
)

@Serializable
data class InitAssetUploadInput(
    val mimeType: String,
    val expectedSize: Long,
    val partsCount: Int = 1,
    val kind: PostAssetKind,
    val sourcePolicyId: String = "browser-native-v1"
)

/**
 * A browser-facing multipart destination. The MediaStore gRPC contract uses
 * an integer-keyed map, but maps become JSON objects after Content brokers
 * the response. Keeping the public API as an ordered list means clients can
 * reliably iterate every signed part URL.
 */
@Serializable
data class AssetUploadTarget(
    val partNumber: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class InitAssetUploadResponse(
    val asset: PostAsset,
    val sessionId: String,
    val parts: List<AssetUploadTarget> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
data class CompleteAssetUploadInput(
    val assetId: String,
    val sessionId: String,
    val parts: List<AssetUploadPartInput>
)

@Serializable
enum class OwnerType { USER, ORGANIZATION }

@Serializable
data class OwnerRef(
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String
)

@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class AccountOwner(
    val id: String,
    val ownerType: OwnerType = OwnerType.USER,
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

typealias AccountUser = AccountOwner

@Serializable
data class CurrentActor(
    val user: SessionUser,
    val activeOwner: AccountOwner
)

@Serializable
data class AccountRelationship(
    val isFollowing: Boolean = false,
    val isFollowedBy: Boolean = false,
    val isFriend: Boolean = false,
    val isBlocked: Boolean = false,
    val hasPendingRequest: Boolean = false
)

@Serializable
data class AccountVisibility(
    val ownerId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val viewerId: String?,
    val viewerType: OwnerType = OwnerType.USER,
    val isPrivate: Boolean = false,
    val relationship: AccountRelationship = AccountRelationship(),
    val isBlocked: Boolean = false,
    val isCloseFriend: Boolean = false
) {
    val canSeePrivateContent: Boolean
        get() = !isBlocked && (viewerId == ownerId || !isPrivate || relationship.isFollowing)
}

@Serializable
data class AccountSocialGraph(
    val followingIds: List<String> = emptyList(),
    val friendIds: List<String> = emptyList(),
    val blockedIds: List<String> = emptyList()
)

@Serializable
data class ContentBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: ContentBlockType,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ContentMediaReference(
    val ownerType: String,
    val ownerId: String,
    val blobId: String,
    val profile: String? = null,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now()
)

@Serializable
data class Post(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String = authorId,
    val author: AccountUser? = null,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    /** V2 media project items.  Legacy posts retain [blocks]. */
    val assets: List<PostAsset> = emptyList(),
    val contentVersion: Int = 1,
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    /** Exactly one root comment may be pinned for a project. */
    val pinnedCommentId: String? = null,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt
)

@Serializable
data class Story(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String = authorId,
    val author: AccountUser? = null,
    val blocks: List<ContentBlock> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    val durationMs: Long = 5_000,
    val mediaDurationMs: Long? = null,
    val closeFriends: Boolean = visibility == Visibility.CLOSE_FRIENDS,
    val archived: Boolean = status == ContentStatus.ARCHIVED,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    val remainingLifeSeconds: Long? = null,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant = createdAt.plusSeconds(24 * 60 * 60)
)

@Serializable
data class Comment(
    val id: String = UUID.randomUUID().toString(),
    val postId: String,
    val authorId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String = authorId,
    val author: AccountUser? = null,
    val parentId: String? = null,
    /** Exact addressed comment; parentId always remains the root thread id. */
    val replyToId: String? = null,
    val text: String,
    val document: CommentDocumentV1? = null,
    val blocks: List<ContentBlock> = emptyList(),
    /** V2 comments accept local image/video assets only. */
    val attachments: List<PostAsset> = emptyList(),
    val status: ContentStatus = ContentStatus.ACTIVE,
    @Serializable(with = InstantIsoSerializer::class)
    val pinnedAt: Instant? = null,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    /** Direct child count, used for lazy Threads-style expansion. */
    val replyCount: Int = 0,
    val replies: List<Comment> = emptyList(),
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt,
    @Serializable(with = InstantIsoSerializer::class)
    val editedAt: Instant? = null
)

@Serializable
enum class CommentSort { TOP, NEWEST, OLDEST }

@Serializable
data class CommentThreadResponse(
    val comments: List<Comment> = emptyList(),
    val totalCount: Int = 0,
    val sort: CommentSort = CommentSort.TOP,
    /** Parent whose direct children were requested; null means root. */
    val parentId: String? = null,
    /** Opaque cursor for the next direct-child page. */
    val nextCursor: String? = null
)

@Serializable
data class CommentReport(
    val commentId: String,
    val actor: OwnerRef,
    val reason: String,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now()
)

/** Search stores one privacy-filtered discussion projection per post. Comment
 * identities and authors never leave Content through this contract. */
data class PostSearchProjection(
    val postId: String,
    val discussion: String,
    val commentCount: Int,
    val revision: Long,
    val updatedAt: Instant
)

@Serializable
data class FeedItem(
    val post: Post,
    val score: Double,
    val reasons: List<String> = emptyList()
)

@Serializable
data class FeedCell(
    val q: Int,
    val r: Int
)

/**
 * A stable, per-viewer anchor for one recommendation constellation.  Anchors
 * are scene coordinates, while [RecommendationPlacement.worldX] and worldY
 * describe the top-left corner of an individual post node.
 */
@Serializable
data class RecommendationConstellation(
    val key: String,
    val anchorX: Double,
    val anchorY: Double,
    val paletteKey: String = key
)

@Serializable
data class RecommendationPlacement(
    val constellationKey: String,
    val salt: Int,
    val worldX: Double,
    val worldY: Double,
    val orbitOrder: Int,
    val sizePreset: AssetSizePreset = AssetSizePreset.M,
    val placementVersion: Int = 3
)

@Serializable
enum class FeedEmphasis { hero, standard, compact }

@Serializable
data class RecommendationFeedInput(
    val chunkX: Int = 0,
    val chunkY: Int = 0,
    val sessionSeed: String = "default",
    val limit: Int = 12
)

@Serializable
data class RecommendationFeedItem(
    val post: Post,
    val score: Double,
    val reasons: List<String> = emptyList(),
    val cell: FeedCell,
    val emphasis: FeedEmphasis = FeedEmphasis.compact,
    val placement: RecommendationPlacement? = null
)

@Serializable
data class RecommendationFeedResponse(
    val chunkX: Int,
    val chunkY: Int,
    val sessionSeed: String,
    val items: List<RecommendationFeedItem> = emptyList(),
    val constellations: List<RecommendationConstellation> = emptyList()
)

@Serializable
data class PostReactionState(
    val postId: String,
    val liked: Boolean,
    val likeCount: Long
)

@Serializable
data class StoryReactionState(
    val storyId: String,
    val liked: Boolean,
    val likeCount: Long
)

@Serializable
data class CommentReactionState(
    val commentId: String,
    val liked: Boolean,
    val likeCount: Long
)

@Serializable
data class ProfileContentResponse(
    val posts: List<Post> = emptyList(),
    val stories: List<Story> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val collections: List<SavedCollection> = emptyList()
)

@Serializable
data class StoryRailItem(
    val authorId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String = authorId,
    val authorName: String,
    val author: AccountUser? = null,
    val avatarUrl: String? = null,
    val storyIds: List<String>,
    val activeCount: Int,
    val seen: Boolean = false,
    val closeFriends: Boolean = false,
    val isViewer: Boolean = false,
    @Serializable(with = InstantIsoSerializer::class)
    val oldestAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val latestAt: Instant
)

@Serializable
data class StoryGroup(
    val authorId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String = authorId,
    val authorName: String,
    val author: AccountUser? = null,
    val avatarUrl: String? = null,
    val stories: List<Story> = emptyList(),
    val startStoryId: String? = null,
    val archive: Boolean = false
)

@Serializable
data class StoryArchiveResponse(
    val ownerId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val owner: AccountUser? = null,
    val stories: List<Story> = emptyList(),
    val cursor: String? = null,
    val nextCursor: String? = null
)

@Serializable
data class StoryArchivePeriod(
    val period: String,
    val count: Int,
    val latestStoryId: String? = null
)

@Serializable
data class StoryArchivePeriodsResponse(
    val ownerId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val periods: List<StoryArchivePeriod> = emptyList()
)

@Serializable
data class SavedCollection(
    val id: String = UUID.randomUUID().toString(),
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String,
    val title: String,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: CollectionVisibility = CollectionVisibility.PRIVATE,
    val itemCount: Int = 0,
    val previewBlocks: List<ContentBlock> = emptyList(),
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt
)

@Serializable
data class CollectionDetail(
    val collection: SavedCollection,
    val posts: List<Post> = emptyList()
)

@Serializable
data class CreateCollectionInput(
    val title: String,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: CollectionVisibility = CollectionVisibility.PRIVATE
)

@Serializable
data class UpdateCollectionInput(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: CollectionVisibility? = null
)

@Serializable
data class SetPostCollectionsInput(
    val postId: String,
    val collectionIds: List<String> = emptyList()
)

@Serializable
data class PostCollectionsState(
    val postId: String,
    val collectionIds: List<String> = emptyList()
)

@Serializable
data class ContentSearchInput(
    val query: String = "",
    val types: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val sort: String = "relevance",
    val limit: Int = 20,
    val cursor: String? = null
)

@Serializable
data class ContentSearchResponse(
    val items: List<ContentSearchItem> = emptyList(),
    val nextCursor: String? = null,
    val partialErrors: List<String> = emptyList(),
    val facets: List<ContentSearchFacet> = emptyList(),
    val providerStatuses: List<ContentProviderStatus> = emptyList()
)

@Serializable
data class ContentSearchItem(
    val type: String,
    val id: String,
    val title: String? = null,
    val snippet: String? = null,
    val owner: AccountUser? = null,
    val url: String,
    val score: Double = 0.0,
    val createdAt: String? = null,
    val postId: String? = null,
    val commentId: String? = null,
    val tags: List<String> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
    val providerKey: String = "content",
    val providerLabel: String = "Content",
    val typeLabel: String = type.lowercase().replaceFirstChar { it.uppercase() },
    val thumbnailUrl: String? = null,
    val highlights: List<String> = emptyList()
)

@Serializable
data class ContentSearchFacet(
    val group: String,
    val value: String,
    val label: String,
    val count: Int,
    val selected: Boolean = false
)

@Serializable
data class ContentProviderStatus(
    val providerKey: String,
    val label: String,
    val status: String,
    val message: String? = null
)

@Serializable
data class ContentSuggestion(
    val type: String,
    val value: String,
    val label: String
)

@Serializable
data class ContentSuggestResponse(
    val suggestions: List<ContentSuggestion> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

@Serializable
data class CreatePostInput(
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    /** Null means legacy input.  An explicit list selects the v2 media model. */
    val assets: List<PostAsset>? = null,
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC,
    val contentVersion: Int = 2
)

@Serializable
data class UpdatePostInput(
    val id: String,
    val title: String? = null,
    val text: String? = null,
    val blocks: List<ContentBlock>? = null,
    /** Null preserves the existing asset list; an explicit empty list clears it. */
    val assets: List<PostAsset>? = null,
    val tags: List<String>? = null,
    val allowComments: Boolean? = null,
    val visibility: Visibility? = null,
    val contentVersion: Int? = null
)

@Serializable
data class SavePostDraftInput(
    val id: String? = null,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    /** Empty v2 drafts are valid; publish validation happens separately. */
    val assets: List<PostAsset>? = null,
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
    val contentVersion: Int = 2
)

@Serializable
data class RequestPostPublicationInput(
    val draftId: String,
    val idempotencyKey: String
)

@Serializable
data class CreateStoryInput(
    val blocks: List<ContentBlock> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC
)

@Serializable
data class CreateCommentInput(
    val postId: String,
    val text: String,
    val blocks: List<ContentBlock> = emptyList(),
    val attachments: List<PostAsset> = emptyList(),
    val parentId: String? = null,
    val replyToId: String? = null,
    val document: CommentDocumentV1? = null,
)

@Serializable
data class UpdateCommentInput(
    val id: String,
    val text: String? = null,
    val blocks: List<ContentBlock>? = null,
    val attachments: List<PostAsset>? = null,
    val document: CommentDocumentV1? = null
)

@Serializable
data class CommentThreadInput(
    val postId: String,
    val limit: Int = 100,
    val sort: CommentSort = CommentSort.TOP,
    val parentId: String? = null,
    val cursor: String? = null
)

@Serializable
data class ReportCommentInput(
    val commentId: String,
    val reason: String
)

@Serializable
data class PollVoteInput(
    val postId: String,
    val blockId: String,
    val optionId: String
)

@Serializable
data class PollVoteState(
    val postId: String,
    val blockId: String,
    val optionId: String,
    val counts: Map<String, Long> = emptyMap(),
    val closed: Boolean = false
)

fun textBlock(text: String): ContentBlock = ContentBlock(
    type = ContentBlockType.TEXT,
    data = JsonObject(mapOf("text" to JsonPrimitive(text)))
)

fun ContentBlock.searchText(): String = listOf("text", "caption", "title", "code", "url", "question")
    .mapNotNull { key -> (data[key] as? JsonPrimitive)?.contentOrNull }
    .joinToString(" ")
