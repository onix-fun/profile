package com.onix.profile.content

import com.onix.content.v1.ContentSearchGrpc
import com.onix.content.v1.ProfileContentRequest
import com.onix.content.v1.SearchRequest
import com.onix.content.v1.SuggestRequest
import com.onix.profile.config.AppConfig
import com.onix.profile.domain.*
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.StatusRuntimeException
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture

class ContentClient(config: AppConfig) : AutoCloseable {
    private val channels = config.contentGrpcUrls.map(::channel)
    private val stubs = channels.map(ContentSearchGrpc::newBlockingStub)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun profileContent(ownerType: String, ownerId: String, viewer: AccountUser, accessToken: String): ProfileContentSummary {
        if (stubs.isEmpty()) return ProfileContentSummary()
        val request = ProfileContentRequest.newBuilder()
            .setOwnerType(ownerType)
            .setOwnerId(ownerId)
            .setViewerType(viewer.ownerType)
            .setViewerId(viewer.id)
            .setPostLimit(500)
            .setStoryLimit(8)
            .build()
        val responses = parallelProviders { stub ->
            stub.withToken(accessToken).getProfileContent(request).toProfileContent()
        }
        return responses.fold(ProfileContentSummary()) { acc, next ->
            acc.copy(
                posts = acc.posts + next.posts,
                stories = acc.stories + next.stories,
                comments = acc.comments + next.comments,
                collections = acc.collections + next.collections
            )
        }
    }

    fun search(input: ProfileSearchInput, viewer: AccountUser, accessToken: String): SearchResponse {
        val responses = parallelSearchProviders { stub ->
            stub.withToken(accessToken).search(input.toGrpc(viewer)).toSearchItems()
        }
        val items = responses.flatMap { it.items }
            .distinctBy { "${it.type}:${it.id}" }
            .sortedWith(searchComparator(input.sort))
            .take(input.limit.coerceIn(1, 100))
        return SearchResponse(
            query = input.query,
            items = items,
            partialErrors = responses.flatMap { it.partialErrors }.distinct()
        )
    }

    fun suggest(query: String, limit: Int, viewer: AccountUser, accessToken: String): Pair<List<SearchSuggestion>, List<String>> {
        val request = SuggestRequest.newBuilder()
            .setQuery(query)
            .setLimit(limit.coerceIn(1, 20))
            .setViewerType(viewer.ownerType)
            .setViewerId(viewer.id)
            .build()
        val responses = parallelSuggestProviders { stub ->
            stub.withToken(accessToken).suggest(request).let { response ->
                ProviderSuggestResult(
                    suggestions = response.suggestionsList.map {
                        SearchSuggestion(type = it.type, value = it.value, label = it.label)
                    },
                    partialErrors = response.partialErrorsList
                )
            }
        }
        return responses.flatMap { it.suggestions }.distinctBy { "${it.type}:${it.value.lowercase()}" }.take(limit.coerceIn(1, 20)) to
            responses.flatMap { it.partialErrors }.distinct()
    }

    override fun close() {
        channels.forEach(ManagedChannel::shutdown)
    }

    private fun ContentSearchGrpc.ContentSearchBlockingStub.withToken(token: String): ContentSearchGrpc.ContentSearchBlockingStub {
        val headers = Metadata().apply { put(AUTHORIZATION_KEY, "Bearer $token") }
        return withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun <T> parallelProviders(block: (ContentSearchGrpc.ContentSearchBlockingStub) -> T): List<T> =
        stubs.map { stub -> CompletableFuture.supplyAsync { block(stub) } }
            .mapNotNull { future -> runCatching { future.get() }.getOrNull() }

    private fun parallelSearchProviders(block: (ContentSearchGrpc.ContentSearchBlockingStub) -> ProviderSearchResult): List<ProviderSearchResult> =
        stubs.map { stub -> CompletableFuture.supplyAsync { block(stub) } }
            .map { future -> runCatching { future.get() } }
            .map { result -> result.getOrElse { ProviderSearchResult(partialErrors = listOf(providerError(it))) } }

    private fun parallelSuggestProviders(block: (ContentSearchGrpc.ContentSearchBlockingStub) -> ProviderSuggestResult): List<ProviderSuggestResult> =
        stubs.map { stub -> CompletableFuture.supplyAsync { block(stub) } }
            .map { future -> runCatching { future.get() } }
            .map { result -> result.getOrElse { ProviderSuggestResult(partialErrors = listOf(providerError(it))) } }

    private fun providerError(error: Throwable): String {
        val cause = error.cause ?: error
        return if (cause is StatusRuntimeException) {
            "content provider returned ${cause.status.code}: ${cause.status.description.orEmpty()}"
        } else {
            cause.message ?: "content provider unavailable"
        }
    }

    private fun com.onix.content.v1.ProfileContentResponse.toProfileContent(): ProfileContentSummary =
        ProfileContentSummary(
            posts = postsList.map { post ->
                ProfileContentPost(
                    id = post.id,
                    authorId = post.authorId,
                    ownerType = post.ownerType.ifBlank { "USER" },
                    ownerId = post.ownerId,
                    author = post.author.takeIf { it.id.isNotBlank() }?.toAccountUser(),
                    title = post.title.takeIf(String::isNotBlank),
                    text = post.text,
                    blocks = post.blocksList.map { it.toContentBlock() },
                    tags = post.tagsList,
                    likeCount = post.likeCount,
                    likedByViewer = post.likedByViewer,
                    createdAt = post.createdAt.takeIf(String::isNotBlank)
                )
            },
            stories = storiesList.map { ProfileContentStory(it.id, it.visibility, it.expiresAt.takeIf(String::isNotBlank)) },
            comments = commentsList.map { ProfileContentComment(it.id, it.postId, it.text, it.createdAt.takeIf(String::isNotBlank)) },
            collections = collectionsList.map { collection ->
                ProfileContentCollection(
                    id = collection.id,
                    ownerType = collection.ownerType.ifBlank { "USER" },
                    ownerId = collection.ownerId,
                    title = collection.title,
                    description = collection.description.takeIf(String::isNotBlank),
                    cover = collection.coverJson.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(JsonObject.serializer(), it) }.getOrNull() },
                    visibility = collection.visibility,
                    itemCount = collection.itemCount,
                    previewBlocks = collection.previewBlocksList.map { it.toContentBlock() },
                    createdAt = collection.createdAt.takeIf(String::isNotBlank),
                    updatedAt = collection.updatedAt.takeIf(String::isNotBlank)
                )
            }
        )

    private fun com.onix.content.v1.SearchResponse.toSearchItems(): ProviderSearchResult =
        ProviderSearchResult(
            items = itemsList.map { item ->
                SearchItem(
                    type = item.type,
                    id = item.id,
                    title = item.title.takeIf(String::isNotBlank),
                    snippet = item.snippet.takeIf(String::isNotBlank),
                    owner = item.owner.takeIf { it.id.isNotBlank() }?.toAccountUser(),
                    url = item.url,
                    score = item.score,
                    createdAt = item.createdAt.takeIf(String::isNotBlank),
                    postId = item.postId.takeIf(String::isNotBlank),
                    commentId = item.commentId.takeIf(String::isNotBlank),
                    tags = item.tagsList,
                    meta = item.metaMap
                )
            },
            partialErrors = partialErrorsList
        )

    private fun com.onix.content.v1.ContentBlock.toContentBlock(): ContentBlock =
        ContentBlock(
            id = id.takeIf(String::isNotBlank),
            type = type,
            data = dataJson.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(JsonObject.serializer(), it) }.getOrNull() } ?: JsonObject(emptyMap())
        )

    private fun com.onix.content.v1.ProfileOwner.toAccountUser(): AccountUser =
        AccountUser(
            id = id,
            ownerType = ownerType.ifBlank { "USER" },
            username = username,
            displayName = displayName.takeIf(String::isNotBlank),
            firstName = firstName.takeIf(String::isNotBlank),
            lastName = lastName.takeIf(String::isNotBlank),
            avatarUrl = avatarUrl.takeIf(String::isNotBlank)
        )

    private companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

data class ProfileSearchInput(
    val query: String,
    val types: List<String>,
    val tags: List<String>,
    val author: String?,
    val dateFrom: String?,
    val dateTo: String?,
    val sort: String,
    val limit: Int,
    val cursor: String?
)

private data class ProviderSearchResult(
    val items: List<SearchItem> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

private data class ProviderSuggestResult(
    val suggestions: List<SearchSuggestion> = emptyList(),
    val partialErrors: List<String> = emptyList()
)

private fun ProfileSearchInput.toGrpc(viewer: AccountUser): SearchRequest =
    SearchRequest.newBuilder()
        .setQuery(query)
        .addAllTypes(types)
        .addAllTags(tags)
        .setAuthor(author.orEmpty())
        .setDateFrom(dateFrom.orEmpty())
        .setDateTo(dateTo.orEmpty())
        .setSort(sort)
        .setLimit(limit)
        .setCursor(cursor.orEmpty())
        .setViewerType(viewer.ownerType)
        .setViewerId(viewer.id)
        .build()

private fun searchComparator(sort: String): Comparator<SearchItem> =
    when (sort.lowercase()) {
        "new" -> compareByDescending { it.createdAt.orEmpty() }
        "popular" -> compareByDescending<SearchItem> { it.meta["likeCount"]?.toLongOrNull() ?: 0L }.thenByDescending { it.score }
        else -> compareByDescending<SearchItem> { it.score }.thenByDescending { it.createdAt.orEmpty() }
    }

private fun channel(target: String): ManagedChannel {
    val (host, port) = parseTarget(target)
    return NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
}

private fun parseTarget(target: String): Pair<String, Int> {
    val parts = target.removePrefix("http://").removePrefix("https://").split(":", limit = 2)
    return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 9091)
}
