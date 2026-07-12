package com.onix.content.grpc

import com.onix.content.account.AccountClient
import com.onix.content.domain.*
import com.onix.content.service.ContentService
import com.onix.content.v1.ContentSearchGrpc
import com.onix.content.v1.ContentBlock as GrpcContentBlock
import com.onix.content.v1.ProfileCollection
import com.onix.content.v1.ProfileComment
import com.onix.content.v1.ProfileContentRequest
import com.onix.content.v1.ProfileContentResponse as GrpcProfileContentResponse
import com.onix.content.v1.ProfileOwner
import com.onix.content.v1.ProfilePost
import com.onix.content.v1.ProfileStory
import com.onix.content.v1.SearchItem as GrpcSearchItem
import com.onix.content.v1.SearchRequest
import com.onix.content.v1.SearchResponse
import com.onix.content.v1.SearchSuggestion
import com.onix.content.v1.SuggestRequest
import com.onix.content.v1.SuggestResponse
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ContentSearchGrpcService(
    private val content: ContentService,
    private val account: AccountClient
) : ContentSearchGrpc.ContentSearchImplBase() {
    private val json = Json { encodeDefaults = true }

    override fun getProfileContent(request: ProfileContentRequest, observer: StreamObserver<GrpcProfileContentResponse>) =
        unary(observer) {
            val token = accessToken()
            val viewer = request.viewerRef()
            val owner = request.ownerRef()
            val visibility = account.ownerVisibility(owner, viewer, token)
            content.profileContent(
                ownerId = owner.ownerId,
                visibility = visibility,
                postLimit = request.postLimit.takeIf { it > 0 } ?: 500,
                storyLimit = request.storyLimit.takeIf { it > 0 } ?: 8,
                authorResolver = authorResolver(token),
                visibilityResolver = visibilityResolver(viewer, token)
            ).toGrpc()
        }

    override fun search(request: SearchRequest, observer: StreamObserver<SearchResponse>) =
        unary(observer) {
            val token = accessToken()
            val viewer = request.viewerRef()
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
            val viewer = request.viewerRef()
            content.suggest(
                viewer = viewer,
                query = request.query,
                limit = request.limit.takeIf { it > 0 } ?: 10,
                visibilityResolver = visibilityResolver(viewer, token)
            ).toGrpc()
        }

    private fun <T> unary(observer: StreamObserver<T>, block: () -> T) {
        try {
            observer.onNext(block())
            observer.onCompleted()
        } catch (error: StatusRuntimeException) {
            observer.onError(error)
        } catch (error: Throwable) {
            observer.onError(Status.INTERNAL.withDescription(error.message ?: "Content search failed").asRuntimeException())
        }
    }

    private fun accessToken(): String =
        AUTH_CONTEXT.get()?.removePrefix("Bearer ")?.takeIf(String::isNotBlank)
            ?: throw Status.UNAUTHENTICATED.withDescription("Authorization bearer token is required").asRuntimeException()

    private fun authorResolver(token: String): (String) -> AccountUser? {
        val cache = mutableMapOf<String, AccountUser?>()
        return { ownerKey ->
            cache.getOrPut(ownerKey) {
                account.getOwner(ownerKey.toOwnerRef(), token)
            }
        }
    }

    private fun visibilityResolver(viewer: OwnerRef, token: String): (String) -> AccountVisibility {
        val cache = mutableMapOf<String, AccountVisibility>()
        return { ownerKey ->
            cache.getOrPut(ownerKey) {
                account.ownerVisibility(ownerKey.toOwnerRef(), viewer, token)
            }
        }
    }

    private fun com.onix.content.domain.ProfileContentResponse.toGrpc(): GrpcProfileContentResponse =
        GrpcProfileContentResponse.newBuilder()
            .addAllPosts(posts.map { it.toGrpc() })
            .addAllStories(stories.map { it.toGrpc() })
            .addAllComments(comments.map { it.toGrpc() })
            .addAllCollections(collections.map { it.toGrpc() })
            .build()

    private fun Post.toGrpc(): ProfilePost =
        ProfilePost.newBuilder()
            .setId(id)
            .setAuthorId(authorId)
            .setOwnerType(ownerType.name)
            .setOwnerId(ownerId)
            .setAuthor(author?.toGrpc() ?: ProfileOwner.getDefaultInstance())
            .setTitle(title.orEmpty())
            .setText(text)
            .addAllTags(tags)
            .setCreatedAt(createdAt.toString())
            .setLikeCount(likeCount)
            .setLikedByViewer(likedByViewer)
            .addAllBlocks(blocks.map { it.toGrpc() })
            .build()

    private fun Story.toGrpc(): ProfileStory =
        ProfileStory.newBuilder()
            .setId(id)
            .setVisibility(visibility.name)
            .setExpiresAt(expiresAt.toString())
            .build()

    private fun Comment.toGrpc(): ProfileComment =
        ProfileComment.newBuilder()
            .setId(id)
            .setPostId(postId)
            .setText(text)
            .setCreatedAt(createdAt.toString())
            .build()

    private fun SavedCollection.toGrpc(): ProfileCollection =
        ProfileCollection.newBuilder()
            .setId(id)
            .setOwnerType(ownerType.name)
            .setOwnerId(ownerId)
            .setTitle(title)
            .setDescription(description.orEmpty())
            .setCoverJson(cover?.let { json.encodeToString(it) }.orEmpty())
            .setVisibility(visibility.name)
            .setItemCount(itemCount)
            .addAllPreviewBlocks(previewBlocks.map { it.toGrpc() })
            .setCreatedAt(createdAt.toString())
            .setUpdatedAt(updatedAt.toString())
            .build()

    private fun ContentBlock.toGrpc(): GrpcContentBlock =
        GrpcContentBlock.newBuilder()
            .setId(id.orEmpty())
            .setType(type.name)
            .setDataJson(json.encodeToString(data))
            .build()
}

class ContentGrpcAuthInterceptor : ServerInterceptor {
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val context = Context.current().withValue(AUTH_CONTEXT, headers[AUTHORIZATION_KEY])
        return Contexts.interceptCall(context, call, headers, next)
    }
}

private val AUTH_CONTEXT: Context.Key<String> = Context.key("authorization")
private val AUTHORIZATION_KEY: Metadata.Key<String> =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

private fun ProfileContentRequest.ownerRef(): OwnerRef =
    OwnerRef(ownerType.ownerType(), ownerId)

private fun ProfileContentRequest.viewerRef(): OwnerRef =
    OwnerRef(viewerType.ownerType(), viewerId)

private fun SearchRequest.viewerRef(): OwnerRef =
    OwnerRef(viewerType.ownerType(), viewerId)

private fun SuggestRequest.viewerRef(): OwnerRef =
    OwnerRef(viewerType.ownerType(), viewerId)

private fun String.ownerType(): OwnerType =
    if (this == OwnerType.ORGANIZATION.name) OwnerType.ORGANIZATION else OwnerType.USER

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) OwnerRef(parts[0].ownerType(), parts[1]) else OwnerRef(OwnerType.USER, this)
}

private fun AccountUser.toGrpc(): ProfileOwner =
    ProfileOwner.newBuilder()
        .setId(id)
        .setOwnerType(ownerType.name)
        .setUsername(username)
        .setDisplayName(displayName.orEmpty())
        .setFirstName(firstName.orEmpty())
        .setLastName(lastName.orEmpty())
        .setAvatarUrl(avatarUrl.orEmpty())
        .build()

private fun ContentSearchResponse.toGrpc(): SearchResponse =
    SearchResponse.newBuilder()
        .addAllItems(items.map { it.toGrpc() })
        .setNextCursor(nextCursor.orEmpty())
        .addAllPartialErrors(partialErrors)
        .build()

private fun ContentSearchItem.toGrpc(): GrpcSearchItem =
    GrpcSearchItem.newBuilder()
        .setType(type)
        .setId(id)
        .setTitle(title.orEmpty())
        .setSnippet(snippet.orEmpty())
        .setOwner(owner?.toGrpc() ?: ProfileOwner.getDefaultInstance())
        .setUrl(url)
        .setScore(score)
        .setCreatedAt(createdAt.orEmpty())
        .setPostId(postId.orEmpty())
        .setCommentId(commentId.orEmpty())
        .addAllTags(tags)
        .putAllMeta(meta)
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
