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
enum class ContentStatus { ACTIVE, ARCHIVED, DELETED }

@Serializable
enum class CollectionVisibility { PUBLIC, PRIVATE }

@Serializable
enum class ContentBlockType { TEXT, IMAGE, VIDEO, AUDIO, FILE }

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
data class Post(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val ownerType: OwnerType = OwnerType.USER,
    val ownerId: String = authorId,
    val author: AccountUser? = null,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ContentStatus = ContentStatus.ACTIVE,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
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
    val text: String,
    val blocks: List<ContentBlock> = emptyList(),
    val status: ContentStatus = ContentStatus.ACTIVE,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant = createdAt
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
    val emphasis: FeedEmphasis = FeedEmphasis.compact
)

@Serializable
data class RecommendationFeedResponse(
    val chunkX: Int,
    val chunkY: Int,
    val sessionSeed: String,
    val items: List<RecommendationFeedItem> = emptyList()
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
    val partialErrors: List<String> = emptyList()
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
    val meta: Map<String, String> = emptyMap()
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
    val tags: List<String> = emptyList(),
    val allowComments: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC
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
    val parentId: String? = null,
)

fun textBlock(text: String): ContentBlock = ContentBlock(
    type = ContentBlockType.TEXT,
    data = JsonObject(mapOf("text" to JsonPrimitive(text)))
)

fun ContentBlock.searchText(): String = when (val value: JsonElement? = data["text"]) {
    is JsonPrimitive -> value.content
    else -> ""
}
