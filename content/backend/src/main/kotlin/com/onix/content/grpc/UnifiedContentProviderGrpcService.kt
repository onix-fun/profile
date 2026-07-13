package com.onix.content.grpc

import com.onix.content.account.AccountClient
import com.onix.content.domain.*
import com.onix.content.service.ContentService
import com.onix.provider.v1.ContentBlock as GrpcContentBlock
import com.onix.provider.v1.ItemRef as GrpcItemRef
import com.onix.provider.v1.ItemView
import com.onix.provider.v1.OwnerRef as GrpcOwnerRef
import com.onix.provider.v1.OwnerSectionRequest
import com.onix.provider.v1.OwnerSectionResponse
import com.onix.provider.v1.OwnerSummary
import com.onix.provider.v1.PostCard
import com.onix.provider.v1.ProviderActionRequest
import com.onix.provider.v1.ProviderActionResponse
import com.onix.provider.v1.ResolveItemsRequest
import com.onix.provider.v1.ResolveItemsResponse
import com.onix.provider.v1.SearchItem as GrpcSearchItem
import com.onix.provider.v1.SearchRequest
import com.onix.provider.v1.SearchResponse
import com.onix.provider.v1.SearchSuggestion
import com.onix.provider.v1.StoryCard
import com.onix.provider.v1.SuggestRequest
import com.onix.provider.v1.SuggestResponse
import com.onix.provider.v1.UnifiedProviderGrpc
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class UnifiedContentProviderGrpcService(
    private val content: ContentService,
    private val account: AccountClient
) : UnifiedProviderGrpc.UnifiedProviderImplBase() {
    private val json = Json { encodeDefaults = true }

    override fun resolveItems(request: ResolveItemsRequest, observer: StreamObserver<ResolveItemsResponse>) =
        unary(observer) {
            val token = accessToken()
            val viewer = request.viewer.toDomain()
            val authorResolver = authorResolver(token)
            val visibilityResolver = visibilityResolver(viewer, token)
            val errors = mutableListOf<String>()
            val items = request.refsList.mapNotNull { ref ->
                if (ref.serviceKey != "content") {
                    errors.add("Unsupported provider ${ref.serviceKey}")
                    return@mapNotNull null
                }
                when (ref.itemType.lowercase()) {
                    "post" -> {
                        val post = content.post(ref.itemId, viewer, authorResolver) ?: return@mapNotNull null
                        val visibility = visibilityResolver(post.ownerRef().key())
                        if (!canViewPost(post, visibility)) null else post.toItemView(ref)
                    }
                    "story" -> {
                        val story = content.story(ref.itemId, viewer) ?: return@mapNotNull null
                        val visibility = visibilityResolver(story.ownerRef().key())
                        if (!canViewStory(story, visibility)) null else story.toItemView(ref)
                    }
                    "comment" -> {
                        val comment = content.comment(ref.itemId, viewer, authorResolver)
                        comment?.toItemView(ref)
                    }
                    else -> {
                        errors.add("Unsupported content item type ${ref.itemType}")
                        null
                    }
                }
            }.take(request.limit.takeIf { it > 0 } ?: Int.MAX_VALUE)
            ResolveItemsResponse.newBuilder()
                .addAllItems(items)
                .addAllPartialErrors(errors.distinct())
                .build()
        }

    override fun listOwnerSection(request: OwnerSectionRequest, observer: StreamObserver<OwnerSectionResponse>) =
        unary(observer) {
            val token = accessToken()
            val viewer = request.viewer.toDomain()
            val owner = request.owner.toDomain()
            val visibility = account.ownerVisibility(owner, viewer, token)
            val authorResolver = authorResolver(token)
            val limit = request.limit.takeIf { it > 0 } ?: 40
            when (request.capabilityKey.ifBlank { request.serviceKey }) {
                "posts" -> {
                    val posts = content.profileContent(
                        ownerId = owner.ownerId,
                        visibility = visibility,
                        postLimit = limit,
                        storyLimit = 1,
                        authorResolver = authorResolver,
                        visibilityResolver = visibilityResolver(viewer, token)
                    ).posts
                    OwnerSectionResponse.newBuilder()
                        .addAllPosts(posts.map { it.toPostCard() })
                        .addAllItems(posts.map { post ->
                            post.toItemView(
                                GrpcItemRef.newBuilder()
                                    .setServiceKey("content")
                                    .setItemType("post")
                                    .setItemId(post.id)
                                    .build()
                            )
                        })
                        .build()
                }
                "story_archive" -> {
                    val cursor = request.cursor.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val ownerAccount = authorResolver(owner.key())
                    val archive = content.storyArchive(owner.ownerId, visibility, ownerAccount, limit, cursor)
                    OwnerSectionResponse.newBuilder()
                        .addAllStories(archive.stories.map { it.toStoryCard() })
                        .setNextCursor(archive.nextCursor.orEmpty())
                        .build()
                }
                else -> OwnerSectionResponse.newBuilder()
                    .addPartialErrors("Unsupported section ${request.capabilityKey}")
                    .build()
            }
        }

    override fun search(request: SearchRequest, observer: StreamObserver<SearchResponse>) =
        unary(observer) {
            val token = accessToken()
            val viewer = request.viewer.toDomain()
            content.search(
                viewer = viewer,
                input = ContentSearchInput(
                    query = request.query,
                    types = request.typesList,
                    tags = request.tagsList,
                    author = request.author.takeIf(String::isNotBlank),
                    dateFrom = request.dateFrom.takeIf(String::isNotBlank),
                    dateTo = request.dateTo.takeIf(String::isNotBlank),
                    sort = request.sort.ifBlank { "relevance" },
                    limit = request.limit.takeIf { it > 0 } ?: 20,
                    cursor = request.cursor.takeIf(String::isNotBlank)
                ),
                visibilityResolver = visibilityResolver(viewer, token),
                authorResolver = authorResolver(token)
            ).toGrpc()
        }

    override fun suggest(request: SuggestRequest, observer: StreamObserver<SuggestResponse>) =
        unary(observer) {
            val token = accessToken()
            val viewer = request.viewer.toDomain()
            content.suggest(
                viewer = viewer,
                query = request.query,
                limit = request.limit.takeIf { it > 0 } ?: 10,
                visibilityResolver = visibilityResolver(viewer, token)
            ).toGrpc()
        }

    override fun performAction(request: ProviderActionRequest, observer: StreamObserver<ProviderActionResponse>) =
        unary(observer) {
            val token = accessToken() ?: throw Status.UNAUTHENTICATED.withDescription("Authorization bearer token is required").asRuntimeException()
            val actor = account.getCurrentActor(token)
            val activeOwner = OwnerRef(actor.activeOwner.ownerType, actor.activeOwner.id)
            val resultJson = when (request.capabilityKey) {
                "post_like" -> json.encodeToString(content.likePost(actor, request.ref.itemId))
                "post_unlike" -> json.encodeToString(content.unlikePost(actor, request.ref.itemId))
                "recommendations" -> {
                    val input = RecommendationFeedInput(
                        chunkX = request.paramsMap["chunkX"]?.toIntOrNull() ?: 0,
                        chunkY = request.paramsMap["chunkY"]?.toIntOrNull() ?: 0,
                        sessionSeed = request.paramsMap["sessionSeed"].orEmpty().ifBlank { "default" },
                        limit = request.paramsMap["limit"]?.toIntOrNull() ?: 12
                    )
                    val graph = account.ownerSocialGraph(activeOwner, token)
                    json.encodeToString(content.recommendationFeed(activeOwner, input, graph, authorResolver(token)))
                }
                else -> throw Status.INVALID_ARGUMENT
                    .withDescription("Unsupported action ${request.capabilityKey}")
                    .asRuntimeException()
            }
            ProviderActionResponse.newBuilder()
                .setResultJson(resultJson)
                .build()
        }

    private fun <T> unary(observer: StreamObserver<T>, block: () -> T) {
        try {
            observer.onNext(block())
            observer.onCompleted()
        } catch (error: StatusRuntimeException) {
            observer.onError(error)
        } catch (error: Throwable) {
            observer.onError(Status.INTERNAL.withDescription(error.message ?: "Content provider failed").asRuntimeException())
        }
    }

    private fun accessToken(): String? =
        AUTH_CONTEXT.get()?.removePrefix("Bearer ")?.takeIf(String::isNotBlank)

    private fun authorResolver(token: String?): (String) -> AccountUser? {
        val cache = mutableMapOf<String, AccountUser?>()
        return { ownerKey -> cache.getOrPut(ownerKey) { account.getOwner(ownerKey.toOwnerRef(), token) } }
    }

    private fun visibilityResolver(viewer: OwnerRef, token: String?): (String) -> AccountVisibility {
        val cache = mutableMapOf<String, AccountVisibility>()
        return { ownerKey -> cache.getOrPut(ownerKey) { account.ownerVisibility(ownerKey.toOwnerRef(), viewer, token) } }
    }

    private fun Post.toItemView(ref: GrpcItemRef): ItemView {
        val preview = blocks.filter { it.type == ContentBlockType.IMAGE || it.type == ContentBlockType.VIDEO }.take(3)
        return ItemView.newBuilder()
            .setRef(ref)
            .setTitle(title ?: text.lineSequence().firstOrNull()?.take(80).orEmpty())
            .setText(text)
            .addAllPreviewBlocks(preview.map { it.toGrpc() })
            .setUrl("/p/$id")
            .setCreatedAt(createdAt.toString())
            .setOwner(author?.toGrpc() ?: OwnerSummary.getDefaultInstance())
            .setPost(toPostCard())
            .putAllMeta(mapOf("visibility" to visibility.name, "likeCount" to likeCount.toString()))
            .build()
    }

    private fun Story.toItemView(ref: GrpcItemRef): ItemView =
        ItemView.newBuilder()
            .setRef(ref)
            .setTitle("Story")
            .addAllPreviewBlocks(blocks.map { it.toGrpc() })
            .setUrl("/story/$id")
            .setCreatedAt(createdAt.toString())
            .setOwner(author?.toGrpc() ?: OwnerSummary.getDefaultInstance())
            .setStory(toStoryCard())
            .putAllMeta(mapOf("visibility" to visibility.name, "archived" to archived.toString()))
            .build()

    private fun Comment.toItemView(ref: GrpcItemRef): ItemView =
        ItemView.newBuilder()
            .setRef(ref)
            .setTitle("Comment")
            .setText(text)
            .setUrl("/p/$postId?comment=$id")
            .setCreatedAt(createdAt.toString())
            .setOwner(author?.toGrpc() ?: OwnerSummary.getDefaultInstance())
            .putAllMeta(mapOf("postId" to postId, "likeCount" to likeCount.toString()))
            .build()

    private fun Post.toPostCard(): PostCard =
        PostCard.newBuilder()
            .setId(id)
            .setAuthorId(authorId)
            .setOwnerType(ownerType.name)
            .setOwnerId(ownerId)
            .setAuthor(author?.toGrpc() ?: OwnerSummary.getDefaultInstance())
            .setTitle(title.orEmpty())
            .setText(text)
            .addAllTags(tags)
            .setCreatedAt(createdAt.toString())
            .setLikeCount(likeCount)
            .setLikedByViewer(likedByViewer)
            .addAllBlocks(blocks.map { it.toGrpc() })
            .build()

    private fun Story.toStoryCard(): StoryCard =
        StoryCard.newBuilder()
            .setId(id)
            .setAuthorId(authorId)
            .setOwnerType(ownerType.name)
            .setOwnerId(ownerId)
            .setAuthor(author?.toGrpc() ?: OwnerSummary.getDefaultInstance())
            .setVisibility(visibility.name)
            .addAllBlocks(blocks.map { it.toGrpc() })
            .setDurationMs(durationMs)
            .setMediaDurationMs(mediaDurationMs ?: 0)
            .setCloseFriends(closeFriends)
            .setArchived(archived)
            .setLikeCount(likeCount)
            .setLikedByViewer(likedByViewer)
            .setRemainingLifeSeconds(remainingLifeSeconds ?: 0)
            .setCreatedAt(createdAt.toString())
            .setExpiresAt(expiresAt.toString())
            .build()

    private fun ContentBlock.toGrpc(): GrpcContentBlock =
        GrpcContentBlock.newBuilder()
            .setId(id.orEmpty())
            .setType(type.name)
            .setDataJson(json.encodeToString(data))
            .build()

    private fun ContentSearchResponse.toGrpc(): SearchResponse =
        SearchResponse.newBuilder()
            .addAllItems(items.map { it.toGrpc() })
            .setNextCursor(nextCursor.orEmpty())
            .addAllPartialErrors(partialErrors)
            .addAllFacets(facets.map { it.toGrpc() })
            .addAllProviderStatuses(providerStatuses.map { it.toGrpc() })
            .build()

    private fun ContentSearchItem.toGrpc(): GrpcSearchItem =
        GrpcSearchItem.newBuilder()
            .setType(type)
            .setId(id)
            .setTitle(title.orEmpty())
            .setSnippet(snippet.orEmpty())
            .setOwner(owner?.toGrpc() ?: OwnerSummary.getDefaultInstance())
            .setUrl(url)
            .setScore(score)
            .setCreatedAt(createdAt.orEmpty())
            .setPostId(postId.orEmpty())
            .setCommentId(commentId.orEmpty())
            .addAllTags(tags)
            .putAllMeta(meta)
            .setProviderKey(providerKey)
            .setProviderLabel(providerLabel)
            .setTypeLabel(typeLabel)
            .setThumbnailUrl(thumbnailUrl.orEmpty())
            .addAllHighlights(highlights)
            .build()

    private fun ContentSearchFacet.toGrpc(): com.onix.provider.v1.SearchFacet =
        com.onix.provider.v1.SearchFacet.newBuilder()
            .setGroup(group)
            .setValue(value)
            .setLabel(label)
            .setCount(count)
            .setSelected(selected)
            .build()

    private fun ContentProviderStatus.toGrpc(): com.onix.provider.v1.ProviderStatus =
        com.onix.provider.v1.ProviderStatus.newBuilder()
            .setProviderKey(providerKey)
            .setLabel(label)
            .setStatus(status)
            .setMessage(message.orEmpty())
            .build()

    private fun ContentSuggestResponse.toGrpc(): SuggestResponse =
        SuggestResponse.newBuilder()
            .addAllSuggestions(suggestions.map {
                SearchSuggestion.newBuilder()
                    .setType(it.type)
                    .setValue(it.value)
                    .setLabel(it.label)
                    .build()
            })
            .addAllPartialErrors(partialErrors)
            .build()
}

private fun canViewPost(post: Post, visibility: AccountVisibility): Boolean {
    if (visibility.isBlocked || post.status != ContentStatus.ACTIVE) return false
    if (post.ownerId == visibility.viewerId && post.ownerType == visibility.viewerType) return true
    if (visibility.ownerId != post.ownerId || visibility.ownerType != post.ownerType) return false
    return when (post.visibility) {
        Visibility.PUBLIC -> visibility.canSeePrivateContent
        Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
    }
}

private fun canViewStory(story: Story, visibility: AccountVisibility): Boolean {
    if (visibility.isBlocked || story.status != ContentStatus.ACTIVE) return false
    if (story.ownerId == visibility.viewerId && story.ownerType == visibility.viewerType) return true
    if (visibility.ownerId != story.ownerId || visibility.ownerType != story.ownerType) return false
    return when (story.visibility) {
        Visibility.PUBLIC -> visibility.canSeePrivateContent
        Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
    }
}

private fun AccountUser.toGrpc(): OwnerSummary =
    OwnerSummary.newBuilder()
        .setId(id)
        .setOwnerType(ownerType.name)
        .setUsername(username)
        .setDisplayName(displayName.orEmpty())
        .setFirstName(firstName.orEmpty())
        .setLastName(lastName.orEmpty())
        .setAvatarUrl(avatarUrl.orEmpty())
        .build()

private fun GrpcOwnerRef.toDomain(): OwnerRef =
    OwnerRef(ownerType.ownerType(), ownerId)

private fun String.ownerType(): OwnerType =
    if (this == OwnerType.ORGANIZATION.name) OwnerType.ORGANIZATION else OwnerType.USER

private fun OwnerRef.key(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Post.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun Story.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) OwnerRef(parts[0].ownerType(), parts[1]) else OwnerRef(OwnerType.USER, this)
}
