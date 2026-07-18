package com.onix.profile.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class AccountSearchUser(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null
)

@Serializable
data class RelatedUser(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
    val relationship: Relationship? = null
)

@Serializable
data class UserPageResponse(
    val items: List<RelatedUser> = emptyList(),
    val totalCount: Int = 0
)

@Serializable
data class SocialCanvasResponse(
    val owner: AccountProfile,
    val filter: String,
    val items: List<RelatedUser> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val limit: Int = 40
)

@Serializable
data class AccountUser(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class CurrentActor(
    val user: SessionUser,
    val activeOwner: AccountUser
)

@Serializable
data class Relationship(
    val isFollowing: Boolean = false,
    val isFollowedBy: Boolean = false,
    val isFriend: Boolean = false,
    val isBlocked: Boolean = false,
    val hasPendingRequest: Boolean = false
)

@Serializable
data class BirthdayParts(val day: Int, val month: Int)

@Serializable
data class SocialLink(val label: String, val url: String)

@Serializable
data class ContentBlock(
    val id: String? = null,
    val type: String,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class AccountProfile(
    val id: String,
    val ownerType: String = "USER",
    val username: String,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val bio: String? = null,
    val birthday: BirthdayParts? = null,
    val socialLinks: List<SocialLink> = emptyList(),
    val avatarUrl: String? = null,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val isPrivate: Boolean = false,
    val relationship: Relationship = Relationship()
)

@Serializable
data class ProfileContentPost(
    val id: String,
    val authorId: String? = null,
    val ownerType: String = "USER",
    val ownerId: String? = authorId,
    val author: AccountUser? = null,
    val title: String? = null,
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    val createdAt: String? = null
)

@Serializable
data class ProfileContentStory(
    val id: String,
    val visibility: String,
    val expiresAt: String? = null
)

@Serializable
data class ProfileContentComment(
    val id: String,
    val postId: String,
    val text: String,
    val createdAt: String? = null
)

@Serializable
data class ProfileContentCollection(
    val id: String,
    val ownerType: String = "USER",
    val ownerId: String,
    val title: String,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: String = "PRIVATE",
    val itemCount: Int = 0,
    val previewBlocks: List<ContentBlock> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class ProfileCollectionItemRef(
    val serviceKey: String,
    val itemType: String,
    val itemId: String
)

@Serializable
data class ProfileCollectionItemView(
    val ref: ProfileCollectionItemRef,
    val title: String? = null,
    val text: String = "",
    val previewBlocks: List<ContentBlock> = emptyList(),
    val url: String? = null,
    val createdAt: String? = null,
    val owner: AccountUser? = null,
    val post: ProfileContentPost? = null,
    val story: ProfileStoryDetail? = null,
    val meta: Map<String, String> = emptyMap()
)

@Serializable
data class ProfileStoryDetail(
    val id: String,
    val authorId: String,
    val ownerType: String = "USER",
    val ownerId: String = authorId,
    val author: AccountUser? = null,
    val visibility: String,
    val blocks: List<ContentBlock> = emptyList(),
    val durationMs: Long = 5_000,
    val mediaDurationMs: Long? = null,
    val closeFriends: Boolean = false,
    val archived: Boolean = false,
    val likeCount: Long = 0,
    val likedByViewer: Boolean = false,
    val remainingLifeSeconds: Long? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class ProfileCollectionDetail(
    val collection: ProfileContentCollection,
    val items: List<ProfileCollectionItemView> = emptyList(),
    val posts: List<ProfileContentPost> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

@Serializable
data class CreateCollectionInput(
    val title: String,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: String = "PRIVATE"
)

@Serializable
data class UpdateCollectionInput(
    val title: String? = null,
    val description: String? = null,
    val cover: JsonObject? = null,
    val visibility: String? = null
)

@Serializable
data class ItemCollectionsState(
    val ref: ProfileCollectionItemRef,
    val collectionIds: List<String> = emptyList()
)

@Serializable
data class SetItemCollectionsInput(
    val collectionIds: List<String> = emptyList()
)

@Serializable
data class ProfileNavButton(
    val key: String,
    val serviceKey: String,
    val featureKey: String,
    val label: String,
    val icon: String,
    val color: String,
    val mode: String = "canvas",
    val kind: String,
    val route: String? = null,
    val targetService: String? = null,
    val targetPath: String? = null,
    val targetUrl: String? = null,
    val backendOperation: String? = null
)

@Serializable
data class OwnerSectionResponse(
    val buttonKey: String,
    val items: List<ProfileCollectionItemView> = emptyList(),
    val posts: List<ProfileContentPost> = emptyList(),
    val stories: List<ProfileStoryDetail> = emptyList(),
    val nextCursor: String? = null,
    val partialErrors: List<String> = emptyList()
)

@Serializable
data class ProfileContentSummary(
    val posts: List<ProfileContentPost> = emptyList(),
    val stories: List<ProfileContentStory> = emptyList(),
    val comments: List<ProfileContentComment> = emptyList(),
    val collections: List<ProfileContentCollection> = emptyList()
)

@Serializable
data class CanvasPosition(val x: Double, val y: Double)

@Serializable
data class CanvasNode(
    val id: String,
    val type: String,
    val position: CanvasPosition,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class CanvasEdge(
    val id: String,
    val source: String,
    val target: String
)

@Serializable
data class CanvasViewport(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0
)

@Serializable
data class ProfilePermissions(
    val owner: Boolean,
    val canFollow: Boolean
)

@Serializable
data class ProfileCanvasResponse(
    val status: String,
    val profile: AccountProfile? = null,
    val content: ProfileContentSummary = ProfileContentSummary(),
    val relationship: Relationship? = null,
    val navigation: List<ProfileNavButton> = emptyList(),
    val nodes: List<CanvasNode> = emptyList(),
    val edges: List<CanvasEdge> = emptyList(),
    val permissions: ProfilePermissions = ProfilePermissions(owner = false, canFollow = false),
    val viewport: CanvasViewport = CanvasViewport()
)

@Serializable
data class SessionMeResponse(
    val user: SessionUser
)

@Serializable
data class CurrentActorResponse(
    val actor: CurrentActor
)

@Serializable
data class AuthRequiredResponse(
    val code: String = "AUTH_REQUIRED",
    val loginUrl: String
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)

@Serializable
data class FollowResponse(
    val relationship: Relationship
)

@Serializable
data class SearchResponse(
    val query: String,
    val items: List<SearchItem> = emptyList(),
    val nextCursor: String? = null,
    val partialErrors: List<String> = emptyList(),
    val facets: List<SearchFacet> = emptyList(),
    val providerStatuses: List<SearchProviderStatus> = emptyList()
)

@Serializable
data class SearchItem(
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
    val providerKey: String? = null,
    val providerLabel: String? = null,
    val typeLabel: String? = null,
    val thumbnailUrl: String? = null,
    val highlights: List<String> = emptyList()
)

@Serializable
data class SearchFacet(
    val group: String,
    val value: String,
    val label: String,
    val count: Int,
    val selected: Boolean = false
)

@Serializable
data class SearchProviderStatus(
    val providerKey: String,
    val label: String,
    val status: String,
    val message: String? = null
)

@Serializable
data class SearchSuggestResponse(
    val query: String,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

@Serializable
data class SearchSuggestion(
    val type: String,
    val value: String,
    val label: String,
    val owner: AccountSearchUser? = null
)
