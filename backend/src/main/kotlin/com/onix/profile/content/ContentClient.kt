package com.onix.profile.content

import com.onix.profile.config.AppConfig
import com.onix.profile.domain.*
import com.onix.profile.service.ProfileRepository
import com.onix.profile.service.ProviderActionResult
import com.onix.profile.service.ProviderGateway
import com.onix.profile.service.ProviderResolveResult
import com.onix.provider.v1.ItemRef as GrpcItemRef
import com.onix.provider.v1.OwnerRef as GrpcOwnerRef
import com.onix.provider.v1.OwnerSectionRequest
import com.onix.provider.v1.ProviderActionRequest
import com.onix.provider.v1.ResolveItemsRequest
import com.onix.provider.v1.SearchRequest
import com.onix.provider.v1.SuggestRequest
import com.onix.provider.v1.UnifiedProviderGrpc
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.StatusRuntimeException
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.CompletableFuture

class ContentClient(
    private val config: AppConfig,
    private val repository: ProfileRepository
) : AutoCloseable, ProviderGateway {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val channels = mutableMapOf<String, ManagedChannel>()
    private val stubs = mutableMapOf<String, UnifiedProviderGrpc.UnifiedProviderBlockingStub>()

    override fun profileContent(ownerType: String, ownerId: String, viewer: AccountUser, accessToken: String): ProfileContentSummary {
        val sections = capabilities("owner_section")
            .filter { it.capabilityKey == "posts" }
            .mapNotNull { capability ->
                providerStub(capability.serviceKey)?.let { stub ->
                    CompletableFuture.supplyAsync {
                        val request = OwnerSectionRequest.newBuilder()
                            .setServiceKey(capability.serviceKey)
                            .setCapabilityKey(capability.capabilityKey)
                            .setOwner(ownerType.grpcOwner(ownerId))
                            .setViewer(viewer.grpcOwner())
                            .setLimit(500)
                            .build()
                        stub.withToken(accessToken).listOwnerSection(request)
                    }
                }
            }
            .mapNotNull { future -> runCatching { future.get() }.getOrNull() }

        return ProfileContentSummary(
            posts = sections.flatMap { it.postsList.map { post -> post.toProfilePost() } }
                .distinctBy { it.id }
        )
    }

    override fun search(input: ProfileSearchInput, viewer: AccountUser, accessToken: String): SearchResponse {
        val providerLabels = repository.listProviders().associate { it.serviceKey to it.displayName }
        val requestedProviders = input.providers.map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()
        val responses = capabilities("search")
            .filter { requestedProviders.isEmpty() || it.serviceKey.lowercase() in requestedProviders }
            .map { capability ->
                val stub = providerStub(capability.serviceKey)
                if (stub == null) {
                    CompletableFuture.completedFuture(ProviderSearchResult(null, "provider ${capability.serviceKey} is not configured", capability.serviceKey, capability.capabilityKey))
                } else {
                    CompletableFuture.supplyAsync {
                        val request = SearchRequest.newBuilder()
                            .setServiceKey(capability.serviceKey)
                            .setCapabilityKey(capability.capabilityKey)
                            .setQuery(input.query)
                            .addAllTypes(input.types)
                            .addAllTags(input.tags)
                            .setAuthor(input.author.orEmpty())
                            .setDateFrom(input.dateFrom.orEmpty())
                            .setDateTo(input.dateTo.orEmpty())
                            .setSort(input.sort)
                            .setLimit(input.limit.coerceIn(1, 100))
                            .setCursor(input.cursor.orEmpty())
                            .setViewer(viewer.grpcOwner())
                            .build()
                        runCatching { stub.withToken(accessToken).search(request) }
                            .fold(
                                { ProviderSearchResult(it, null, capability.serviceKey, capability.capabilityKey) },
                                { ProviderSearchResult(null, providerError(it), capability.serviceKey, capability.capabilityKey) }
                            )
                    }
                }
            }
            .map { future -> future.get() }

        val items = responses.mapNotNull { it.response }
            .flatMap { it.itemsList.map { item -> item.toSearchItem() } }
            .distinctBy { "${it.type}:${it.id}" }
            .sortedWith(searchComparator(input.sort))
            .take(input.limit.coerceIn(1, 100))
        val responseErrors = responses.flatMap { result ->
            result.response?.partialErrorsList ?: listOfNotNull(result.error)
        }.distinct()
        val facets = mergeFacets(
            responses.mapNotNull { it.response }.flatMap { it.facetsList.map { facet -> facet.toSearchFacet() } },
            input,
            providerLabels
        )
        val providerStatuses = mergeProviderStatuses(responses, providerLabels)
        return SearchResponse(
            query = input.query,
            items = items,
            nextCursor = responses.asSequence()
                .mapNotNull { it.response?.nextCursor?.takeIf(String::isNotBlank) }
                .firstOrNull(),
            partialErrors = responseErrors,
            facets = facets,
            providerStatuses = providerStatuses
        )
    }

    override fun suggest(query: String, limit: Int, viewer: AccountUser, accessToken: String): Pair<List<SearchSuggestion>, List<String>> {
        val responses = capabilities("suggest")
            .map { capability ->
                val stub = providerStub(capability.serviceKey)
                if (stub == null) {
                    CompletableFuture.completedFuture(null to "provider ${capability.serviceKey} is not configured")
                } else {
                    CompletableFuture.supplyAsync {
                        val request = SuggestRequest.newBuilder()
                            .setServiceKey(capability.serviceKey)
                            .setCapabilityKey(capability.capabilityKey)
                            .setQuery(query)
                            .setLimit(limit.coerceIn(1, 20))
                            .setViewer(viewer.grpcOwner())
                            .build()
                        runCatching { stub.withToken(accessToken).suggest(request) }
                            .fold({ it to null }, { null to providerError(it) })
                    }
                }
            }
            .map { future -> future.get() }
        return responses.mapNotNull { it.first }
            .flatMap { it.suggestionsList.map { suggestion -> SearchSuggestion(suggestion.type, suggestion.value, suggestion.label) } }
            .distinctBy { "${it.type}:${it.value.lowercase()}" }
            .take(limit.coerceIn(1, 20)) to responses.flatMap { response ->
                response.first?.partialErrorsList ?: listOfNotNull(response.second)
            }.distinct()
    }

    override fun resolveItems(refs: List<ProfileCollectionItemRef>, viewer: AccountUser, accessToken: String): ProviderResolveResult {
        if (refs.isEmpty()) return ProviderResolveResult()
        val items = mutableListOf<ProfileCollectionItemView>()
        val errors = mutableListOf<String>()
        refs.groupBy { it.serviceKey }.forEach { (serviceKey, serviceRefs) ->
            val stub = providerStub(serviceKey)
            if (stub == null) {
                errors.add("provider $serviceKey is not configured")
                return@forEach
            }
            val response = runCatching {
                stub.withToken(accessToken).resolveItems(
                    ResolveItemsRequest.newBuilder()
                        .setServiceKey(serviceKey)
                        .setCapabilityKey("resolve_items")
                        .setViewer(viewer.grpcOwner())
                        .setLimit(serviceRefs.size)
                        .addAllRefs(serviceRefs.map { it.grpcRef() })
                        .build()
                )
            }.getOrElse {
                errors.add(providerError(it))
                return@forEach
            }
            items.addAll(response.itemsList.map { it.toCollectionItemView() })
            errors.addAll(response.partialErrorsList)
        }
        return ProviderResolveResult(items = items, partialErrors = errors.distinct())
    }

    override fun listOwnerSection(
        ownerType: String,
        ownerId: String,
        viewer: AccountUser,
        buttonKey: String,
        limit: Int,
        cursor: String?,
        accessToken: String
    ): OwnerSectionResponse {
        val button = repository.listNavButtons(ownerType, ownerId).firstOrNull { it.key == buttonKey }
            ?: return OwnerSectionResponse(buttonKey = buttonKey, partialErrors = listOf("Section $buttonKey is not configured"))
        val capabilityKey = button.capabilityKey ?: button.backendOperation ?: button.key
        val stub = providerStub(button.serviceKey)
            ?: return OwnerSectionResponse(buttonKey = buttonKey, partialErrors = listOf("provider ${button.serviceKey} is not configured"))
        val response = runCatching {
            stub.withToken(accessToken).listOwnerSection(
                OwnerSectionRequest.newBuilder()
                    .setServiceKey(button.serviceKey)
                    .setCapabilityKey(capabilityKey)
                    .setOwner(ownerType.grpcOwner(ownerId))
                    .setViewer(viewer.grpcOwner())
                    .setLimit(limit.coerceIn(1, 100))
                    .setCursor(cursor.orEmpty())
                    .build()
            )
        }.getOrElse {
            return OwnerSectionResponse(buttonKey = buttonKey, partialErrors = listOf(providerError(it)))
        }
        return OwnerSectionResponse(
            buttonKey = buttonKey,
            items = response.itemsList.map { it.toCollectionItemView() },
            posts = response.postsList.map { it.toProfilePost() },
            stories = response.storiesList.map { it.toProfileStoryDetail() },
            nextCursor = response.nextCursor.takeIf(String::isNotBlank),
            partialErrors = response.partialErrorsList
        )
    }

    override fun performAction(
        serviceKey: String,
        capabilityKey: String,
        actor: AccountUser,
        ref: ProfileCollectionItemRef?,
        params: Map<String, String>,
        accessToken: String
    ): ProviderActionResult {
        val stub = providerStub(serviceKey)
            ?: return ProviderActionResult(JsonObject(emptyMap()), listOf("provider $serviceKey is not configured"))
        val request = ProviderActionRequest.newBuilder()
            .setServiceKey(serviceKey)
            .setCapabilityKey(capabilityKey)
            .setActor(actor.grpcOwner())
            .setViewer(actor.grpcOwner())
            .putAllParams(params)
            .also { builder -> ref?.let { builder.setRef(it.grpcRef()) } }
            .build()
        val response = runCatching { stub.withToken(accessToken).performAction(request) }
            .getOrElse { return ProviderActionResult(JsonObject(emptyMap()), listOf(providerError(it))) }
        val result = response.resultJson.takeIf(String::isNotBlank)
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?: JsonObject(emptyMap())
        return ProviderActionResult(result = result, partialErrors = response.partialErrorsList, meta = response.metaMap)
    }

    fun recommendationFeed(input: JsonObject, actor: AccountUser, accessToken: String): JsonElement =
        performAction(
            serviceKey = "content",
            capabilityKey = "recommendations",
            actor = actor,
            ref = null,
            params = input.mapValues { it.value.toParamString() },
            accessToken = accessToken
        ).result

    fun likePost(postId: String, actor: AccountUser, accessToken: String): JsonElement =
        performAction(
            serviceKey = "content",
            capabilityKey = "post_like",
            actor = actor,
            ref = ProfileCollectionItemRef("content", "post", postId),
            params = emptyMap(),
            accessToken = accessToken
        ).result

    fun unlikePost(postId: String, actor: AccountUser, accessToken: String): JsonElement =
        performAction(
            serviceKey = "content",
            capabilityKey = "post_unlike",
            actor = actor,
            ref = ProfileCollectionItemRef("content", "post", postId),
            params = emptyMap(),
            accessToken = accessToken
        ).result

    override fun close() {
        channels.values.forEach(ManagedChannel::shutdown)
    }

    private fun capabilities(operation: String) =
        repository.listProviderCapabilities().filter { it.operation == operation }

    private fun providerStub(serviceKey: String): UnifiedProviderGrpc.UnifiedProviderBlockingStub? {
        stubs[serviceKey]?.let { return it }
        val provider = repository.listProviders().firstOrNull { it.serviceKey == serviceKey && it.enabled } ?: return null
        val target: String = provider.grpcTargetEnv?.let { config.env[it] }?.takeIf(String::isNotBlank)
            ?: (if (serviceKey == "content") config.contentGrpcUrls.firstOrNull() else null)
            ?: return null
        val channel = channels.getOrPut(serviceKey) { channel(target) }
        return UnifiedProviderGrpc.newBlockingStub(channel).also { stubs[serviceKey] = it }
    }

    private fun UnifiedProviderGrpc.UnifiedProviderBlockingStub.withToken(token: String): UnifiedProviderGrpc.UnifiedProviderBlockingStub {
        val headers = Metadata().apply {
            if (token.isNotBlank()) put(AUTHORIZATION_KEY, "Bearer $token")
            put(SERVICE_KEY, "profile")
        }
        return withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun providerError(error: Throwable): String {
        val cause = error.cause ?: error
        return if (cause is StatusRuntimeException) {
            "provider returned ${cause.status.code}: ${cause.status.description.orEmpty()}"
        } else {
            cause.message ?: "provider unavailable"
        }
    }

    private fun com.onix.provider.v1.ItemView.toCollectionItemView(): ProfileCollectionItemView =
        ProfileCollectionItemView(
            ref = ProfileCollectionItemRef(ref.serviceKey, ref.itemType, ref.itemId),
            title = title.takeIf(String::isNotBlank),
            text = text,
            previewBlocks = previewBlocksList.map { it.toContentBlock() },
            url = url.takeIf(String::isNotBlank),
            createdAt = createdAt.takeIf(String::isNotBlank),
            owner = owner.takeIf { it.id.isNotBlank() }?.toAccountUser(),
            post = post.takeIf { it.id.isNotBlank() }?.toProfilePost(),
            story = story.takeIf { it.id.isNotBlank() }?.toProfileStoryDetail(),
            meta = metaMap
        )

    private fun com.onix.provider.v1.PostCard.toProfilePost(): ProfileContentPost =
        ProfileContentPost(
            id = id,
            authorId = authorId,
            ownerType = ownerType.ifBlank { "USER" },
            ownerId = ownerId.ifBlank { authorId },
            author = author.takeIf { it.id.isNotBlank() }?.toAccountUser(),
            title = title.takeIf(String::isNotBlank),
            text = text,
            blocks = blocksList.map { it.toContentBlock() },
            tags = tagsList,
            likeCount = likeCount,
            likedByViewer = likedByViewer,
            createdAt = createdAt.takeIf(String::isNotBlank)
        )

    private fun com.onix.provider.v1.StoryCard.toProfileStoryDetail(): ProfileStoryDetail =
        ProfileStoryDetail(
            id = id,
            authorId = authorId,
            ownerType = ownerType.ifBlank { "USER" },
            ownerId = ownerId.ifBlank { authorId },
            author = author.takeIf { it.id.isNotBlank() }?.toAccountUser(),
            visibility = visibility,
            blocks = blocksList.map { it.toContentBlock() },
            durationMs = durationMs,
            mediaDurationMs = mediaDurationMs.takeIf { it > 0 },
            closeFriends = closeFriends,
            archived = archived,
            likeCount = likeCount,
            likedByViewer = likedByViewer,
            remainingLifeSeconds = remainingLifeSeconds.takeIf { it > 0 },
            createdAt = createdAt.takeIf(String::isNotBlank),
            expiresAt = expiresAt.takeIf(String::isNotBlank)
        )

    private fun com.onix.provider.v1.SearchItem.toSearchItem(): SearchItem =
        SearchItem(
            type = type,
            id = id,
            title = title.takeIf(String::isNotBlank),
            snippet = snippet.takeIf(String::isNotBlank),
            owner = owner.takeIf { it.id.isNotBlank() }?.toAccountUser(),
            url = url,
            score = score,
            createdAt = createdAt.takeIf(String::isNotBlank),
            postId = postId.takeIf(String::isNotBlank),
            commentId = commentId.takeIf(String::isNotBlank),
            tags = tagsList,
            meta = metaMap,
            providerKey = providerKey.takeIf(String::isNotBlank),
            providerLabel = providerLabel.takeIf(String::isNotBlank),
            typeLabel = typeLabel.takeIf(String::isNotBlank),
            thumbnailUrl = thumbnailUrl.takeIf(String::isNotBlank),
            highlights = highlightsList
        )

    private fun com.onix.provider.v1.SearchFacet.toSearchFacet(): SearchFacet =
        SearchFacet(
            group = group,
            value = value,
            label = label,
            count = count,
            selected = selected
        )

    private fun com.onix.provider.v1.ContentBlock.toContentBlock(): ContentBlock =
        ContentBlock(
            id = id.takeIf(String::isNotBlank),
            type = type,
            data = dataJson.takeIf(String::isNotBlank)
                ?.let { runCatching { json.decodeFromString(JsonObject.serializer(), it) }.getOrNull() }
                ?: JsonObject(emptyMap())
        )

    private fun com.onix.provider.v1.OwnerSummary.toAccountUser(): AccountUser =
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
        val SERVICE_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-onix-service", Metadata.ASCII_STRING_MARSHALLER)
    }
}

@Serializable
data class ProfileSearchInput(
    val query: String,
    val types: List<String>,
    val tags: List<String>,
    val providers: List<String> = emptyList(),
    val author: String?,
    val dateFrom: String?,
    val dateTo: String?,
    val sort: String,
    val limit: Int,
    val cursor: String?
)

private data class ProviderSearchResult(
    val response: com.onix.provider.v1.SearchResponse?,
    val error: String?,
    val serviceKey: String,
    val capabilityKey: String
)

private fun mergeFacets(
    facets: List<SearchFacet>,
    input: ProfileSearchInput,
    providerLabels: Map<String, String>
): List<SearchFacet> {
    val selectedTypes = input.types.map { it.lowercase() }.toSet()
    val selectedTags = input.tags.map { it.removePrefix("#").lowercase() }.toSet()
    val selectedProviders = input.providers.map { it.lowercase() }.toSet()
    val selectedAuthor = input.author?.lowercase()
    val selectedByGroup = mapOf(
        "type" to selectedTypes,
        "tag" to selectedTags,
        "provider" to selectedProviders,
        "owner" to setOfNotNull(selectedAuthor)
    )

    val providerFacets = providerLabels.map { (key, label) ->
        SearchFacet(
            group = "provider",
            value = key,
            label = label,
            count = facets.filter { it.group == "provider" && it.value == key }.sumOf { it.count },
            selected = selectedProviders.contains(key.lowercase())
        )
    }

    val merged = facets
        .groupBy { it.group to it.value }
        .map { (key, values) ->
            val first = values.first()
            val selected = selectedByGroup[first.group]?.contains(first.value.lowercase()) ?: values.any { it.selected }
            SearchFacet(
                group = first.group,
                value = first.value,
                label = first.label,
                count = values.sumOf { it.count },
                selected = selected
            )
        }

    return (merged + providerFacets)
        .distinctBy { "${it.group}:${it.value}" }
        .sortedWith(compareBy<SearchFacet>({ facetOrder(it.group) }, { it.label.lowercase() }))
}

private fun mergeProviderStatuses(
    responses: List<ProviderSearchResult>,
    providerLabels: Map<String, String>
): List<SearchProviderStatus> =
    responses
        .groupBy { it.serviceKey }
        .map { (serviceKey, results) ->
            val providerStatuses = results.mapNotNull { result ->
                result.response?.providerStatusesList?.firstOrNull()?.let { status ->
                    SearchProviderStatus(
                        providerKey = status.providerKey,
                        label = status.label,
                        status = status.status,
                        message = status.message.takeIf(String::isNotBlank)
                    )
                }
            }
            if (providerStatuses.isNotEmpty()) {
                val status = providerStatuses.firstOrNull { it.status == "down" }
                    ?: providerStatuses.firstOrNull { it.status == "partial" }
                    ?: providerStatuses.first()
                status.copy(
                    providerKey = status.providerKey.ifBlank { serviceKey },
                    label = status.label.ifBlank { providerLabels[serviceKey] ?: serviceKey }
                )
            } else {
                val errors = results.mapNotNull { it.error }.ifEmpty {
                    results.flatMap { it.response?.partialErrorsList.orEmpty() }
                }
                val hasResponse = results.any { it.response != null }
                SearchProviderStatus(
                    providerKey = serviceKey,
                    label = providerLabels[serviceKey] ?: serviceKey,
                    status = if (errors.isEmpty()) "ok" else if (hasResponse) "partial" else "down",
                    message = errors.firstOrNull()
                )
            }
        }
        .sortedBy { it.label.lowercase() }

private fun facetOrder(group: String): Int =
    when (group) {
        "type" -> 0
        "provider" -> 1
        "tag" -> 2
        "owner" -> 3
        "dateRange" -> 4
        else -> 9
    }

private fun String.grpcOwner(ownerId: String): GrpcOwnerRef =
    GrpcOwnerRef.newBuilder()
        .setOwnerType(if (this == "ORGANIZATION") "ORGANIZATION" else "USER")
        .setOwnerId(ownerId)
        .build()

private fun AccountUser.grpcOwner(): GrpcOwnerRef =
    ownerType.grpcOwner(id)

private fun ProfileCollectionItemRef.grpcRef(): GrpcItemRef =
    GrpcItemRef.newBuilder()
        .setServiceKey(serviceKey)
        .setItemType(itemType)
        .setItemId(itemId)
        .build()

private fun JsonElement.toParamString(): String =
    when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        else -> toString()
    }

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
