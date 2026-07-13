package com.onix.content.service

import com.onix.content.domain.*
import com.onix.content.profile.ProfileUsageReporter
import com.onix.content.search.SearchIndexClient
import com.onix.content.search.SearchEventPublisher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

class ContentService(
    private val repository: ContentRepository,
    private val searchEvents: SearchEventPublisher = SearchEventPublisher.noop(),
    private val searchIndex: SearchIndexClient = SearchIndexClient.noop(),
    private val profileUsage: ProfileUsageReporter = ProfileUsageReporter.noop(),
    private val clock: Clock = Clock.systemUTC()
) {
    private data class ScoredRecommendation(
        val post: Post,
        val score: Double,
        val reasons: List<String>,
        val emphasis: FeedEmphasis
    )

    fun createPost(author: SessionUser, input: CreatePostInput): Post =
        createPost(CurrentActor(author, author.asAccountUser()), input)

    fun createPost(actor: CurrentActor, input: CreatePostInput): Post {
        val normalizedTags = normalizeTags(input.tags)
        val blocks = input.blocks.ifEmpty { if (input.text.isBlank()) emptyList() else listOf(textBlock(input.text)) }
        val text = input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        require(text.isNotBlank() || blocks.isNotEmpty()) { "Post must contain text or media" }
        val now = Instant.now(clock)
        val post = repository.savePost(
            Post(
                id = UUID.randomUUID().toString(),
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                title = input.title?.trim()?.takeIf(String::isNotBlank),
                text = text,
                blocks = blocks,
                tags = normalizedTags,
                allowComments = input.allowComments,
                visibility = input.visibility,
                createdAt = now,
                updatedAt = now
            )
        )
        searchEvents.postUpsert(post)
        profileUsage.report(actor.activeOwner.ref(), "content", "posts")
        return post
    }

    fun updatePost(actor: CurrentActor, input: UpdatePostInput): Post {
        val current = repository.findPost(input.id) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        val blocks = input.blocks ?: current.blocks
        val textSource = input.text ?: current.text
        val text = textSource.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        require(text.isNotBlank() || blocks.isNotEmpty()) { "Post must contain text or media" }
        val next = current.copy(
            title = if (input.title != null) input.title.trim().takeIf(String::isNotBlank) else current.title,
            text = text,
            blocks = blocks.ifEmpty { if (text.isBlank()) emptyList() else listOf(textBlock(text)) },
            tags = input.tags?.let(::normalizeTags) ?: current.tags,
            allowComments = input.allowComments ?: current.allowComments,
            visibility = input.visibility ?: current.visibility,
            updatedAt = Instant.now(clock)
        )
        val saved = repository.updatePost(next)
        searchEvents.postUpsert(saved)
        return saved
    }

    fun deletePost(actor: CurrentActor, postId: String) {
        val current = repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        repository.deletePost(postId)
        searchEvents.postDelete(postId)
    }

    fun createStory(author: SessionUser, input: CreateStoryInput): Story =
        createStory(CurrentActor(author, author.asAccountUser()), input)

    fun createStory(actor: CurrentActor, input: CreateStoryInput): Story {
        require(input.blocks.isNotEmpty()) { "Story must contain at least one block" }
        val now = Instant.now(clock)
        val blocks = input.blocks.map(::normalizeStoryBlock)
        val story = repository.saveStory(
            Story(
                id = UUID.randomUUID().toString(),
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                blocks = blocks,
                visibility = input.visibility,
                durationMs = storyDurationMs(blocks),
                mediaDurationMs = storyMediaDurationMs(blocks),
                closeFriends = input.visibility == Visibility.CLOSE_FRIENDS,
                archived = false,
                remainingLifeSeconds = 24 * 60 * 60,
                createdAt = now,
                expiresAt = now.plusSeconds(24 * 60 * 60)
            )
        )
        profileUsage.report(actor.activeOwner.ref(), "content", "story_archive")
        return story
    }

    fun createComment(author: SessionUser, input: CreateCommentInput): Comment =
        createComment(CurrentActor(author, author.asAccountUser()), input)

    fun createComment(actor: CurrentActor, input: CreateCommentInput): Comment {
        require(input.text.isNotBlank() || input.blocks.isNotEmpty()) { "Comment text is required" }
        val post = repository.findPost(input.postId) ?: throw IllegalArgumentException("Post not found")
        require(post.allowComments) { "Comments are disabled for this post" }
        val blocks = input.blocks.ifEmpty { listOf(textBlock(input.text.trim())) }
        val text = input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        val now = Instant.now(clock)
        val comment = repository.saveComment(
            Comment(
                id = UUID.randomUUID().toString(),
                postId = input.postId,
                authorId = actor.activeOwner.id,
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                author = actor.activeOwner,
                parentId = null,
                text = text,
                blocks = blocks,
                createdAt = now,
                updatedAt = now
            )
        )
        searchEvents.commentUpsert(comment)
        return comment
    }

    fun updateComment(actor: CurrentActor, input: UpdateCommentInput): Comment {
        val current = repository.findComment(input.id) ?: throw IllegalArgumentException("Comment not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        val blocks = input.blocks ?: current.blocks
        val textSource = input.text ?: current.text
        val text = textSource.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        require(text.isNotBlank() || blocks.isNotEmpty()) { "Comment text is required" }
        val next = current.copy(
            text = text,
            blocks = blocks.ifEmpty { if (text.isBlank()) emptyList() else listOf(textBlock(text)) },
            updatedAt = Instant.now(clock)
        )
        val saved = repository.updateComment(next)
        searchEvents.commentUpsert(saved)
        return saved
    }

    fun deleteComment(actor: CurrentActor, commentId: String) {
        val current = repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        repository.deleteComment(commentId)
        searchEvents.commentDelete(commentId)
    }

    fun deleteStory(actor: CurrentActor, storyId: String) {
        val current = repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        requireOwnsContent(actor.activeOwner.ref(), current.ownerRef())
        repository.deleteStory(storyId)
    }

    fun recordMediaReference(ownerType: String, ownerId: String, blobId: String, profile: String? = null) {
        repository.saveMediaReference(ContentMediaReference(ownerType = ownerType, ownerId = ownerId, blobId = blobId, profile = profile, createdAt = Instant.now(clock)))
    }

    fun canViewMedia(blobId: String, visibilityResolver: (String) -> AccountVisibility): Boolean =
        (repository.listMediaReferences(blobId) + repository.findLegacyMediaReferences(blobId)).any { ref ->
            when (ref.ownerType) {
                "post" -> repository.findPost(ref.ownerId)
                    ?.let { canViewPost(it, visibilityResolver(it.ownerRef().key())) } == true
                "comment" -> repository.findComment(ref.ownerId)
                    ?.let { comment ->
                        repository.findPost(comment.postId)
                            ?.let { canViewPost(it, visibilityResolver(it.ownerRef().key())) } == true
                    } == true
                "story" -> repository.findStory(ref.ownerId)
                    ?.let { canViewStory(it, visibilityResolver(it.ownerRef().key()), includeArchived = true) } == true
                else -> false
            }
        }

    fun profileContent(
        ownerId: String,
        visibility: AccountVisibility,
        postLimit: Int,
        storyLimit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { visibility }
    ): ProfileContentResponse {
        val now = Instant.now(clock)
        val owner = OwnerRef(visibility.ownerType, ownerId)
        val posts = repository.listPostsByOwner(owner, postLimit.coerceIn(1, 500))
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            .map { withViewerState(it, visibility.viewerRef()) }
            .map { withAuthor(it, authorResolver) }
        val stories = repository.listActiveStoriesByOwner(owner, now, storyLimit.coerceIn(1, 50))
            .filter { canViewStory(it, visibility, includeArchived = false) }
            .map { enrichStory(it, now = now, viewer = visibility.viewerRef()) }
        val comments = posts.flatMap { repository.listCommentsForPost(it.id, 3) }
            .map { withCommentViewerState(it, visibility.viewerRef(), authorResolver) }
        val collections = collections(owner, visibility, 80, visibilityResolver)
        return ProfileContentResponse(posts = posts, stories = stories, comments = comments, collections = collections)
    }

    fun createCollection(actor: CurrentActor, input: CreateCollectionInput): SavedCollection {
        val title = normalizeCollectionTitle(input.title)
        val now = Instant.now(clock)
        val collection = repository.saveCollection(
            SavedCollection(
                id = UUID.randomUUID().toString(),
                ownerType = actor.activeOwner.ownerType,
                ownerId = actor.activeOwner.id,
                title = title,
                description = normalizeCollectionDescription(input.description),
                cover = input.cover,
                visibility = input.visibility,
                createdAt = now,
                updatedAt = now
            )
        )
        searchEvents.collectionUpsert(collection)
        return collection
    }

    fun updateCollection(actor: CurrentActor, input: UpdateCollectionInput): SavedCollection {
        val current = repository.findCollection(input.id) ?: throw IllegalArgumentException("Collection not found")
        requireOwnsCollection(actor.activeOwner.ref(), current)
        val next = current.copy(
            title = input.title?.let(::normalizeCollectionTitle) ?: current.title,
            description = if (input.description != null) normalizeCollectionDescription(input.description) else current.description,
            cover = input.cover ?: current.cover,
            visibility = input.visibility ?: current.visibility,
            updatedAt = Instant.now(clock)
        )
        val saved = repository.updateCollection(next)
        searchEvents.collectionUpsert(saved)
        return saved
    }

    fun deleteCollection(actor: CurrentActor, collectionId: String) {
        val current = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        requireOwnsCollection(actor.activeOwner.ref(), current)
        repository.deleteCollection(collectionId)
        searchEvents.collectionDelete(collectionId)
    }

    fun collections(
        owner: OwnerRef,
        visibility: AccountVisibility,
        limit: Int,
        visibilityResolver: (String) -> AccountVisibility = { visibility }
    ): List<SavedCollection> {
        return repository.listCollectionsByOwner(owner, limit.coerceIn(1, 100))
            .filter { canViewCollection(it, visibility) }
            .map { enrichCollectionForViewer(it, visibilityResolver) }
    }

    fun collection(
        id: String,
        viewer: OwnerRef,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null },
        limit: Int = 200
    ): CollectionDetail {
        val collection = repository.findCollection(id) ?: throw IllegalArgumentException("Collection not found")
        val ownerVisibility = visibilityResolver(collection.ownerRef().key())
        require(canViewCollection(collection, ownerVisibility)) { "Collection not found" }
        val posts = repository.listCollectionPosts(collection.id, limit.coerceIn(1, 500))
            .filter { post -> canViewPost(post, visibilityResolver(post.ownerRef().key())) }
            .map { withViewerState(it, viewer) }
            .map { withAuthor(it, authorResolver) }
        return CollectionDetail(
            collection = collection.copy(
                itemCount = posts.size,
                previewBlocks = previewBlocks(posts)
            ),
            posts = posts
        )
    }

    fun postCollections(actor: CurrentActor, postId: String): PostCollectionsState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        return PostCollectionsState(postId = postId, collectionIds = repository.listPostCollectionIds(actor.activeOwner.ref(), postId))
    }

    fun setPostCollections(
        actor: CurrentActor,
        input: SetPostCollectionsInput,
        visibilityResolver: (String) -> AccountVisibility
    ): PostCollectionsState {
        val post = repository.findPost(input.postId) ?: throw IllegalArgumentException("Post not found")
        require(canViewPost(post, visibilityResolver(post.ownerRef().key()))) { "Post is not available" }
        val owner = actor.activeOwner.ref()
        val desired = input.collectionIds.distinct()
        val desiredCollections = desired.map { id ->
            (repository.findCollection(id) ?: throw IllegalArgumentException("Collection not found")).also {
                requireOwnsCollection(owner, it)
            }
        }
        val current = repository.listPostCollectionIds(owner, input.postId).toSet()
        val desiredSet = desiredCollections.map { it.id }.toSet()
        val now = Instant.now(clock)
        (desiredSet - current).forEach { repository.addPostToCollection(it, input.postId, now) }
        (current - desiredSet).forEach { repository.removePostFromCollection(it, input.postId) }
        (desiredSet + current).forEach { id ->
            repository.findCollection(id)?.let(searchEvents::collectionUpsert)
        }
        return PostCollectionsState(postId = input.postId, collectionIds = repository.listPostCollectionIds(owner, input.postId))
    }

    fun addPostToCollection(
        actor: CurrentActor,
        collectionId: String,
        postId: String,
        visibilityResolver: (String) -> AccountVisibility
    ): PostCollectionsState {
        val current = postCollections(actor, postId).collectionIds.toMutableSet()
        current.add(collectionId)
        return setPostCollections(actor, SetPostCollectionsInput(postId, current.toList()), visibilityResolver)
    }

    fun removePostFromCollection(actor: CurrentActor, collectionId: String, postId: String): PostCollectionsState {
        val collection = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        requireOwnsCollection(actor.activeOwner.ref(), collection)
        repository.removePostFromCollection(collectionId, postId)
        repository.findCollection(collectionId)?.let(searchEvents::collectionUpsert)
        return PostCollectionsState(postId = postId, collectionIds = repository.listPostCollectionIds(actor.activeOwner.ref(), postId))
    }

    fun search(
        viewer: OwnerRef,
        input: ContentSearchInput,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): ContentSearchResponse {
        val query = input.query.trim()
        if (query.isBlank() && input.tags.isEmpty()) return ContentSearchResponse()
        val requested = normalizeSearchTypes(input.types)
        val limit = input.limit.coerceIn(1, 100)
        val errors = mutableListOf<String>()
        val items = mutableListOf<ContentSearchItem>()
        val postItems = mutableListOf<ContentSearchItem>()

        if ("posts" in requested || "tags" in requested) {
            val result = searchIndex.search("posts", query.ifBlank { input.tags.joinToString(" ") }, limit * 3)
            result.error?.let(errors::add)
            result.hits.mapNotNull { hit ->
                repository.findPost(hit.id)
                    ?.takeIf { post -> matchesSearchFilters(post, input) }
                    ?.takeIf { post -> canViewPost(post, visibilityResolver(post.ownerRef().key())) }
                    ?.let { post ->
                        val enriched = withAuthor(withViewerState(post, viewer), authorResolver)
                        postSearchItem(enriched, hit.score, hit.snippet)
                    }
            }.also { postItems.addAll(it) }
            if ("posts" in requested) items.addAll(postItems)
        }

        if ("collections" in requested) {
            val result = searchIndex.search("collections", query, limit * 3)
            result.error?.let(errors::add)
            items.addAll(result.hits.mapNotNull { hit ->
                repository.findCollection(hit.id)
                    ?.takeIf { collection -> matchesSearchFilters(collection, input) }
                    ?.let { collection ->
                        val visibility = visibilityResolver(collection.ownerRef().key())
                        collection.takeIf { canViewCollection(it, visibility) }
                    }
                    ?.let { collection ->
                        collectionSearchItem(
                            collection = enrichCollectionForViewer(collection, visibilityResolver),
                            owner = authorResolver(collection.ownerRef().key()),
                            score = hit.score,
                            snippet = hit.snippet
                        )
                    }
            })
        }

        if ("comments" in requested) {
            val result = searchIndex.search("comments", query, limit * 3)
            result.error?.let(errors::add)
            items.addAll(result.hits.mapNotNull { hit ->
                repository.findComment(hit.id)
                    ?.takeIf { comment -> matchesSearchFilters(comment, input) }
                    ?.let { comment ->
                        val post = repository.findPost(comment.postId) ?: return@mapNotNull null
                        if (!canViewPost(post, visibilityResolver(post.ownerRef().key()))) return@mapNotNull null
                        commentSearchItem(
                            comment = withCommentViewerState(comment, viewer, authorResolver),
                            post = withAuthor(post, authorResolver),
                            score = hit.score,
                            snippet = hit.snippet
                        )
                    }
            })
        }

        if ("tags" in requested) {
            val existing = items.asSequence().flatMap { it.tags.asSequence() }.toSet()
            val tags = (postItems.asSequence().flatMap { it.tags.asSequence() } + input.tags.asSequence())
                .filter(String::isNotBlank)
                .distinct()
                .filter { it !in existing || requested == setOf("tags") }
                .take(limit)
                .map { tag ->
                    ContentSearchItem(
                        type = "TAG",
                        id = tag,
                        title = "#$tag",
                        snippet = "Posts tagged #$tag",
                        url = "/search?tag=$tag",
                        score = 0.5,
                        tags = listOf(tag),
                        meta = mapOf("tag" to tag)
                    )
                }
            items.addAll(tags)
        }

        val visibleItems = items.distinctBy { "${it.type}:${it.id}" }
        val facets = searchFacets(visibleItems, input)
        val sorted = sortSearchItems(visibleItems, input.sort)
            .take(limit)
        val partialErrors = errors.distinct()
        return ContentSearchResponse(
            items = sorted,
            partialErrors = partialErrors,
            facets = facets,
            providerStatuses = listOf(
                ContentProviderStatus(
                    providerKey = "content",
                    label = "Content",
                    status = if (partialErrors.isEmpty()) "ok" else "partial",
                    message = partialErrors.firstOrNull()
                )
            )
        )
    }

    fun suggest(
        viewer: OwnerRef,
        query: String,
        limit: Int,
        visibilityResolver: (String) -> AccountVisibility
    ): ContentSuggestResponse {
        val normalized = query.trim()
        if (normalized.isBlank()) return ContentSuggestResponse()
        val response = search(
            viewer = viewer,
            input = ContentSearchInput(query = normalized, types = listOf("posts", "collections", "comments", "tags"), limit = limit.coerceIn(1, 20)),
            visibilityResolver = visibilityResolver
        )
        val suggestions = response.items
            .flatMap { item ->
                buildList {
                    item.title?.takeIf(String::isNotBlank)?.let { add(ContentSuggestion(item.type, it, it)) }
                    item.tags.forEach { tag -> add(ContentSuggestion("TAG", tag, "#$tag")) }
                }
            }
            .distinctBy { "${it.type}:${it.value.lowercase()}" }
            .take(limit.coerceIn(1, 20))
        return ContentSuggestResponse(suggestions = suggestions, partialErrors = response.partialErrors)
    }

    fun feed(
        viewerId: String,
        tagAffinity: Set<String>,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<FeedItem> =
        feed(OwnerRef(OwnerType.USER, viewerId), tagAffinity, limit, authorResolver)

    fun feed(
        viewer: OwnerRef,
        tagAffinity: Set<String>,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) }
    ): List<FeedItem> {
        return repository.listRecentPosts(limit.coerceIn(1, 100) * 3)
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            .map { withViewerState(it, viewer) }
            .map { withAuthor(it, authorResolver) }
            .map { post ->
                val tagScore = post.tags.count { it in tagAffinity } * 4.0
                val likeScore = post.likeCount.coerceAtMost(30) * 0.15
                val ageHours = java.time.Duration.between(post.createdAt, Instant.now(clock)).toHours().coerceAtLeast(0)
                val recencyScore = 1.0 / (1 + ageHours).toDouble()
                val ownPenalty = if (post.ownerType == viewer.ownerType && post.ownerId == viewer.ownerId) -2.0 else 0.0
                FeedItem(
                    post = post,
                    score = tagScore + likeScore + recencyScore + ownPenalty,
                    reasons = buildList {
                        if (tagScore > 0) add("tag-affinity")
                        if (post.likeCount > 0) add("liked")
                        add("recent")
                    }
                )
            }
            .sortedWith(compareByDescending<FeedItem> { it.score }.thenByDescending { it.post.createdAt })
            .take(limit.coerceIn(1, 100))
    }

    fun recommendationFeed(
        viewerId: String,
        input: RecommendationFeedInput,
        socialGraph: AccountSocialGraph = AccountSocialGraph(),
        authorResolver: (String) -> AccountUser? = { null }
    ): RecommendationFeedResponse =
        recommendationFeed(OwnerRef(OwnerType.USER, viewerId), input, socialGraph, authorResolver)

    fun recommendationFeed(
        viewer: OwnerRef,
        input: RecommendationFeedInput,
        socialGraph: AccountSocialGraph = AccountSocialGraph(),
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) }
    ): RecommendationFeedResponse {
        val pageLimit = input.limit.coerceIn(1, 50)
        val seed = input.sessionSeed.ifBlank { "default" }
        val blockedIds = socialGraph.blockedIds.toSet()
        val tagAffinity = repository.listViewerTagAffinity(viewer, 80).toSet()
        val now = Instant.now(clock)
        val candidates = repository.listRecentPosts(500)
            .filter { it.ownerId !in blockedIds }
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            .map { withViewerState(it, viewer) }
            .map { withAuthor(it, authorResolver) }
            .map { post -> scoreRecommendation(post, viewer, tagAffinity, socialGraph, now) }

        val ordered = stableRecommendationOrder(candidates, seed)
        val offset = chunkOrdinal(input.chunkX, input.chunkY) * pageLimit
        val page = if (offset >= ordered.size) emptyList() else ordered.drop(offset).take(pageLimit)

        return RecommendationFeedResponse(
            chunkX = input.chunkX,
            chunkY = input.chunkY,
            sessionSeed = seed,
            items = page.mapIndexed { index, scored ->
                RecommendationFeedItem(
                    post = scored.post,
                    score = scored.score,
                    reasons = scored.reasons,
                    cell = FeedCell(q = index % FEED_CELL_COLUMNS, r = index / FEED_CELL_COLUMNS),
                    emphasis = scored.emphasis
                )
            }
        )
    }

    fun post(
        id: String,
        viewerId: String? = null,
        authorResolver: (String) -> AccountUser? = { null }
    ): Post? =
        post(id, viewerId?.let { OwnerRef(OwnerType.USER, it) }, authorResolver)

    fun post(
        id: String,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser? = { null }
    ): Post? =
        repository.findPost(id)
            ?.let { withViewerState(it, viewer) }
            ?.let { withAuthor(it, authorResolver) }

    fun post(
        id: String,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): Post? =
        repository.findPost(id)
            ?.takeIf { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
            ?.let { withViewerState(it, viewer) }
            ?.let { withAuthor(it, authorResolver) }

    fun likePost(user: SessionUser, postId: String): PostReactionState =
        likePost(CurrentActor(user, user.asAccountUser()), postId)

    fun likePost(actor: CurrentActor, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, actor.activeOwner.ref(), true)
        return PostReactionState(postId = postId, liked = true, likeCount = repository.countPostLikes(postId))
    }

    fun unlikePost(user: SessionUser, postId: String): PostReactionState =
        unlikePost(CurrentActor(user, user.asAccountUser()), postId)

    fun unlikePost(actor: CurrentActor, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, actor.activeOwner.ref(), false)
        return PostReactionState(postId = postId, liked = false, likeCount = repository.countPostLikes(postId))
    }

    fun likeStory(user: SessionUser, storyId: String): StoryReactionState =
        likeStory(CurrentActor(user, user.asAccountUser()), storyId)

    fun likeStory(actor: CurrentActor, storyId: String): StoryReactionState {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.setStoryLike(storyId, actor.activeOwner.ref(), true)
        return StoryReactionState(storyId = storyId, liked = true, likeCount = repository.countStoryLikes(storyId))
    }

    fun unlikeStory(user: SessionUser, storyId: String): StoryReactionState =
        unlikeStory(CurrentActor(user, user.asAccountUser()), storyId)

    fun unlikeStory(actor: CurrentActor, storyId: String): StoryReactionState {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.setStoryLike(storyId, actor.activeOwner.ref(), false)
        return StoryReactionState(storyId = storyId, liked = false, likeCount = repository.countStoryLikes(storyId))
    }

    fun likeComment(user: SessionUser, commentId: String): CommentReactionState =
        likeComment(CurrentActor(user, user.asAccountUser()), commentId)

    fun likeComment(actor: CurrentActor, commentId: String): CommentReactionState {
        repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        repository.setCommentLike(commentId, actor.activeOwner.ref(), true)
        return CommentReactionState(commentId = commentId, liked = true, likeCount = repository.countCommentLikes(commentId))
    }

    fun unlikeComment(user: SessionUser, commentId: String): CommentReactionState =
        unlikeComment(CurrentActor(user, user.asAccountUser()), commentId)

    fun unlikeComment(actor: CurrentActor, commentId: String): CommentReactionState {
        repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        repository.setCommentLike(commentId, actor.activeOwner.ref(), false)
        return CommentReactionState(commentId = commentId, liked = false, likeCount = repository.countCommentLikes(commentId))
    }

    fun story(id: String, viewerId: String? = null): Story? =
        story(id, viewerId?.let { OwnerRef(OwnerType.USER, it) })

    fun story(id: String, viewer: OwnerRef?): Story? =
        repository.findStory(id)?.let { enrichStory(it, now = Instant.now(clock), viewer = viewer) }

    fun story(id: String, viewer: OwnerRef?, visibilityResolver: (String) -> AccountVisibility): Story? =
        repository.findStory(id)
            ?.takeIf { canViewStory(it, visibilityResolver(it.ownerRef().key()), includeArchived = true) }
            ?.let { enrichStory(it, now = Instant.now(clock), viewer = viewer) }

    fun comments(
        postId: String,
        limit: Int,
        viewerId: String? = null,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> =
        comments(postId, limit, viewerId?.let { OwnerRef(OwnerType.USER, it) }, authorResolver)

    fun comments(
        postId: String,
        limit: Int,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> =
        repository.listCommentsForPost(postId, limit.coerceIn(1, 100))
            .map { withCommentViewerState(it, viewer, authorResolver) }

    fun comments(
        postId: String,
        limit: Int,
        viewer: OwnerRef?,
        visibilityResolver: (String) -> AccountVisibility,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> {
        val post = repository.findPost(postId) ?: return emptyList()
        if (!canViewPost(post, visibilityResolver(post.ownerRef().key()))) return emptyList()
        return repository.listCommentsForPost(postId, limit.coerceIn(1, 100))
            .map { withCommentViewerState(it, viewer, authorResolver) }
    }

    fun comment(
        id: String,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser? = { null }
    ): Comment? =
        repository.findComment(id)
            ?.takeIf { it.status == ContentStatus.ACTIVE }
            ?.let { withCommentViewerState(it, viewer, authorResolver) }

    fun storiesFeed(
        viewerId: String,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewerId) }
    ): List<StoryRailItem> =
        storiesFeed(OwnerRef(OwnerType.USER, viewerId), limit, authorResolver, visibilityResolver)

    fun storiesFeed(
        viewer: OwnerRef,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) }
    ): List<StoryRailItem> {
        val now = Instant.now(clock)
        return repository.listActiveStories(now, limit.coerceIn(1, 100))
            .filter { story -> canViewStory(story, visibilityResolver(story.ownerKey()), includeArchived = false) }
            .groupBy { it.ownerKey() }
            .values
            .map { stories ->
                val latest = stories.maxBy { it.createdAt }
                val oldest = stories.minBy { it.createdAt }
                val author = authorResolver(latest.ownerKey())
                StoryRailItem(
                    authorId = latest.authorId,
                    ownerType = latest.ownerType,
                    ownerId = latest.ownerId,
                    authorName = author?.username ?: if (latest.ownerType == viewer.ownerType && latest.ownerId == viewer.ownerId) "You" else "User",
                    author = author,
                    avatarUrl = author?.avatarUrl,
                    storyIds = stories.sortedBy { it.createdAt }.map { it.id },
                    activeCount = stories.size,
                    seen = stories.all { repository.isStoryViewed(it.id, viewer) },
                    closeFriends = stories.any { it.visibility == Visibility.CLOSE_FRIENDS },
                    isViewer = latest.ownerType == viewer.ownerType && latest.ownerId == viewer.ownerId,
                    oldestAt = oldest.createdAt,
                    latestAt = latest.createdAt
                )
            }
            .sortedWith(compareByDescending<StoryRailItem> { it.isViewer }.thenByDescending { it.latestAt })
            .take(limit.coerceIn(1, 100))
    }

    fun storyGroup(
        viewerId: String,
        authorId: String,
        ownerType: OwnerType = OwnerType.USER,
        startStoryId: String?,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewerId) },
        archive: Boolean = false
    ): StoryGroup =
        storyGroup(OwnerRef(OwnerType.USER, viewerId), authorId, ownerType, startStoryId, authorResolver, visibilityResolver, archive)

    fun storyGroup(
        viewer: OwnerRef,
        authorId: String,
        ownerType: OwnerType = OwnerType.USER,
        startStoryId: String?,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerKey -> AccountVisibility(ownerId = ownerKey.toOwnerRef().ownerId, ownerType = ownerKey.toOwnerRef().ownerType, viewerId = viewer.ownerId, viewerType = viewer.ownerType) },
        archive: Boolean = false
    ): StoryGroup {
        val now = Instant.now(clock)
        val owner = OwnerRef(ownerType, authorId)
        val ownerKey = owner.key()
        val visibility = visibilityResolver(ownerKey)
        val rawStories = if (archive) {
            repository.listArchivedStoriesByOwner(owner, now, 100, null)
        } else {
            repository.listActiveStoriesByOwner(owner, now, 100)
        }
        val author = authorResolver(ownerKey)
        val stories = rawStories
            .filter { canViewStory(it, visibility, includeArchived = archive) }
            .sortedBy { it.createdAt }
            .map { enrichStory(it, author, now, viewer) }
        return StoryGroup(
            authorId = authorId,
            ownerType = ownerType,
            ownerId = authorId,
            authorName = author?.username ?: if (owner.ownerType == viewer.ownerType && owner.ownerId == viewer.ownerId) "You" else "User",
            author = author,
            avatarUrl = author?.avatarUrl,
            stories = stories,
            startStoryId = startStoryId?.takeIf { id -> stories.any { it.id == id } } ?: stories.firstOrNull()?.id,
            archive = archive
        )
    }

    fun storyArchive(
        ownerId: String,
        visibility: AccountVisibility,
        author: AccountUser?,
        limit: Int,
        cursor: Instant? = null
    ): StoryArchiveResponse {
        val now = Instant.now(clock)
        if (visibility.isBlocked || !visibility.canSeePrivateContent) {
            return StoryArchiveResponse(ownerId = ownerId, owner = author)
        }
        val pageLimit = limit.coerceIn(1, 80)
        val stories = repository.listArchivedStoriesByAuthor(ownerId, now, pageLimit + 1, cursor)
            .filter { canViewStory(it, visibility, includeArchived = true) }
            .map { enrichStory(it, author, now, visibility.viewerRef()) }
        val page = stories.take(pageLimit)
        return StoryArchiveResponse(
            ownerId = ownerId,
            ownerType = visibility.ownerType,
            owner = author,
            stories = page,
            cursor = cursor?.toString(),
            nextCursor = stories.getOrNull(pageLimit)?.createdAt?.toString()
        )
    }

    fun recordStoryView(user: SessionUser, storyId: String): Boolean =
        recordStoryView(CurrentActor(user, user.asAccountUser()), storyId)

    fun recordStoryView(actor: CurrentActor, storyId: String): Boolean {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.recordStoryView(storyId, actor.activeOwner.ref(), Instant.now(clock))
        return true
    }

    fun recordPostView(user: SessionUser, postId: String, durationMs: Long = 0): Boolean =
        recordPostView(CurrentActor(user, user.asAccountUser()), postId, durationMs)

    fun recordPostView(actor: CurrentActor, postId: String, durationMs: Long = 0): Boolean {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.recordPostView(postId, actor.activeOwner.ref(), durationMs, Instant.now(clock))
        return true
    }

    private fun scoreRecommendation(
        post: Post,
        viewer: OwnerRef,
        tagAffinity: Set<String>,
        socialGraph: AccountSocialGraph,
        now: Instant
    ): ScoredRecommendation {
        val tagScore = post.tags.count { it in tagAffinity } * 4.0
        val friendScore = if (post.authorId in socialGraph.friendIds) 5.0 else 0.0
        val followingScore = if (post.authorId in socialGraph.followingIds) 3.0 else 0.0
        val likeScore = post.likeCount.coerceAtMost(40) * 0.18
        val popularityScore = repository.countPostViews(post.id).coerceAtMost(80) * 0.04
        val ageHours = java.time.Duration.between(post.createdAt, now).toHours().coerceAtLeast(0)
        val recencyScore = 2.0 / (1 + ageHours).toDouble()
        val ownPenalty = if (post.ownerType == viewer.ownerType && post.ownerId == viewer.ownerId) -4.0 else 0.0
        val viewerViews = repository.countPostViewsByUser(post.id, viewer)
        val viewPenalty = viewerViews.coerceAtMost(5) * -1.2
        val score = tagScore + friendScore + followingScore + likeScore + popularityScore + recencyScore + ownPenalty + viewPenalty

        return ScoredRecommendation(
            post = post,
            score = score,
            reasons = buildList {
                if (friendScore > 0) add("friend")
                else if (followingScore > 0) add("following")
                if (tagScore > 0) add("tag-affinity")
                if (post.likeCount > 0) add("popular")
                if (popularityScore > 0) add("viewed-by-others")
                if (viewerViews > 0) add("seen-before")
                add("recent")
            },
            emphasis = when {
                post.blocks.any { it.type == ContentBlockType.VIDEO } || score >= 8.0 -> FeedEmphasis.hero
                post.blocks.any { it.type == ContentBlockType.IMAGE } || score >= 3.0 -> FeedEmphasis.standard
                else -> FeedEmphasis.compact
            }
        )
    }

    private fun stableRecommendationOrder(candidates: List<ScoredRecommendation>, seed: String): List<ScoredRecommendation> {
        val ranked = candidates
            .sortedWith(compareByDescending<ScoredRecommendation> { it.score }.thenByDescending { it.post.createdAt }.thenBy { stableHash("${seed}:rank:${it.post.id}") })
        val explore = candidates.sortedBy { stableHash("${seed}:explore:${it.post.id}") }
        val used = mutableSetOf<String>()
        var rankedIndex = 0
        var exploreIndex = 0
        val ordered = mutableListOf<ScoredRecommendation>()

        while (ordered.size < candidates.size) {
            val useExplore = (ordered.size + 1) % EXPLORATION_INTERVAL == 0
            val next = if (useExplore) {
                nextUnused(explore, used, exploreIndex).also { exploreIndex = it.second }.first
            } else {
                nextUnused(ranked, used, rankedIndex).also { rankedIndex = it.second }.first
            } ?: nextUnused(ranked, used, rankedIndex).also { rankedIndex = it.second }.first
                ?: nextUnused(explore, used, exploreIndex).also { exploreIndex = it.second }.first
                ?: break

            used.add(next.post.id)
            ordered.add(if (useExplore) next.copy(reasons = (next.reasons + "explore").distinct()) else next)
        }
        return ordered
    }

    private fun nextUnused(
        items: List<ScoredRecommendation>,
        used: Set<String>,
        startIndex: Int
    ): Pair<ScoredRecommendation?, Int> {
        var index = startIndex
        while (index < items.size) {
            val item = items[index]
            index += 1
            if (item.post.id !in used) return item to index
        }
        return null to index
    }

    private fun chunkOrdinal(x: Int, y: Int): Int {
        val radius = max(abs(x), abs(y))
        if (radius == 0) return 0
        val previousRingSize = (2 * radius - 1) * (2 * radius - 1)
        val side = radius * 2
        val offset = when {
            y == -radius -> x + radius
            x == radius -> side + y + radius
            y == radius -> side * 2 + (radius - x)
            else -> side * 3 + (radius - y)
        }
        return previousRingSize + offset
    }

    private fun stableHash(value: String): Int {
        var result = 2166136261u
        value.forEach { char ->
            result = result xor char.code.toUInt()
            result *= 16777619u
        }
        return result.toInt() and Int.MAX_VALUE
    }

    private fun canViewStory(story: Story, visibility: AccountVisibility, includeArchived: Boolean): Boolean {
        if (visibility.isBlocked) return false
        if (!includeArchived && !story.expiresAt.isAfter(Instant.now(clock))) return false
        if (includeArchived && story.expiresAt.isAfter(Instant.now(clock)) && story.status != ContentStatus.ARCHIVED) return false
        if (story.status == ContentStatus.DELETED) return false
        if (story.ownerId == visibility.viewerId && story.ownerType == visibility.viewerType) return true
        if (visibility.ownerId != story.ownerId || visibility.ownerType != story.ownerType) return false
        return when (story.visibility) {
            Visibility.PUBLIC -> visibility.canSeePrivateContent
            Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
        }
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

    private fun canViewCollection(collection: SavedCollection, visibility: AccountVisibility): Boolean {
        if (visibility.isBlocked || !visibility.canSeePrivateContent) return false
        if (collection.ownerId != visibility.ownerId || collection.ownerType != visibility.ownerType) return false
        val owner = collection.ownerId == visibility.viewerId && collection.ownerType == visibility.viewerType
        return owner || collection.visibility == CollectionVisibility.PUBLIC
    }

    private fun enrichCollectionForViewer(collection: SavedCollection, visibilityResolver: (String) -> AccountVisibility): SavedCollection {
        val posts = repository.listCollectionPosts(collection.id, 500)
            .filter { canViewPost(it, visibilityResolver(it.ownerRef().key())) }
        return collection.copy(
            itemCount = posts.size,
            previewBlocks = previewBlocks(posts)
        )
    }

    private fun previewBlocks(posts: List<Post>): List<ContentBlock> =
        posts.flatMap { post ->
            post.blocks.filter { it.type == ContentBlockType.IMAGE || it.type == ContentBlockType.VIDEO }
        }.take(3)

    private fun normalizeCollectionTitle(title: String): String {
        val normalized = title.trim()
        require(normalized.isNotBlank()) { "Collection title is required" }
        return normalized.take(80)
    }

    private fun normalizeCollectionDescription(description: String?): String? =
        description?.trim()?.takeIf(String::isNotBlank)?.take(280)

    private fun requireOwnsCollection(owner: OwnerRef, collection: SavedCollection) {
        require(collection.ownerType == owner.ownerType && collection.ownerId == owner.ownerId) { "Collection not found" }
    }

    private fun requireOwnsContent(actor: OwnerRef, owner: OwnerRef) {
        require(actor.ownerType == owner.ownerType && actor.ownerId == owner.ownerId) { "Content not found" }
    }

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase() }.filter(String::isNotBlank).distinct().take(20)

    private fun enrichStory(story: Story, author: AccountUser? = story.author, now: Instant, viewer: OwnerRef? = null): Story =
        story.copy(
            author = author,
            durationMs = storyDurationMs(story.blocks),
            mediaDurationMs = storyMediaDurationMs(story.blocks),
            closeFriends = story.visibility == Visibility.CLOSE_FRIENDS,
            archived = story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(now),
            likeCount = repository.countStoryLikes(story.id),
            likedByViewer = viewer?.let { repository.isStoryLikedBy(story.id, it) } ?: false,
            remainingLifeSeconds = if (story.expiresAt.isAfter(now)) java.time.Duration.between(now, story.expiresAt).seconds else 0
        )

    private fun normalizeStoryBlock(block: ContentBlock): ContentBlock {
        if (block.type != ContentBlockType.VIDEO && block.type != ContentBlockType.AUDIO) return block
        val mediaDuration = block.data.longValue("mediaDurationMs") ?: block.data.longValue("durationMs")
        val cappedDuration = (mediaDuration ?: STORY_VIDEO_MAX_MS).coerceAtMost(STORY_VIDEO_MAX_MS).coerceAtLeast(1_000)
        return block.copy(data = JsonObject(block.data + mapOf(
            "durationMs" to JsonPrimitive(cappedDuration),
            "mediaDurationMs" to JsonPrimitive(mediaDuration ?: cappedDuration),
            "trimStartMs" to JsonPrimitive(0),
            "trimEndMs" to JsonPrimitive(cappedDuration)
        )))
    }

    private fun storyDurationMs(blocks: List<ContentBlock>): Long =
        blocks.firstNotNullOfOrNull { block ->
            when (block.type) {
                ContentBlockType.VIDEO, ContentBlockType.AUDIO ->
                    (block.data.longValue("durationMs") ?: block.data.longValue("mediaDurationMs") ?: STORY_VIDEO_MAX_MS)
                        .coerceAtMost(STORY_VIDEO_MAX_MS)
                        .coerceAtLeast(1_000)
                ContentBlockType.IMAGE -> STORY_IMAGE_DURATION_MS
                ContentBlockType.TEXT, ContentBlockType.FILE -> null
            }
        } ?: STORY_IMAGE_DURATION_MS

    private fun storyMediaDurationMs(blocks: List<ContentBlock>): Long? =
        blocks.firstNotNullOfOrNull { block ->
            if (block.type == ContentBlockType.VIDEO || block.type == ContentBlockType.AUDIO) {
                block.data.longValue("mediaDurationMs") ?: block.data.longValue("durationMs")
            } else {
                null
            }
        }

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun ContentBlock.mediaSource(): String? =
        data.mediaSource()

    private fun JsonObject.mediaSource(): String? {
        val direct = listOf("previewUrl", "thumbnailUrl", "coverUrl", "url", "src")
            .firstNotNullOfOrNull { key -> stringValue(key)?.takeIf(String::isNotBlank) }
        if (direct != null) return direct
        val blobId = stringValue("blobId")?.takeIf(String::isNotBlank) ?: return null
        return "/content-media/$blobId"
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

private fun SessionUser.asAccountUser(): AccountUser =
    AccountUser(id = id, ownerType = OwnerType.USER, username = username, displayName = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { username }, firstName = firstName, lastName = lastName, avatarUrl = avatarUrl)

private fun AccountOwner.ref(): OwnerRef = OwnerRef(ownerType = ownerType, ownerId = id)

    private fun withViewerState(post: Post, viewer: OwnerRef?): Post =
        post.copy(
            likeCount = repository.countPostLikes(post.id),
            likedByViewer = viewer?.let { repository.isPostLikedBy(post.id, it) } ?: false
        )

    private fun withAuthor(post: Post, authorResolver: (String) -> AccountUser?): Post =
        post.copy(author = post.author ?: authorResolver(post.ownerKey()))

    private fun withCommentViewerState(
        comment: Comment,
        viewer: OwnerRef?,
        authorResolver: (String) -> AccountUser?
    ): Comment =
        comment.copy(
            author = comment.author ?: authorResolver(comment.ownerKey()),
            likeCount = repository.countCommentLikes(comment.id),
            likedByViewer = viewer?.let { repository.isCommentLikedBy(comment.id, it) } ?: false,
            blocks = comment.blocks.ifEmpty { if (comment.text.isBlank()) emptyList() else listOf(textBlock(comment.text)) }
        )

    private fun normalizeSearchTypes(types: List<String>): Set<String> {
        val allowed = setOf("posts", "collections", "comments", "tags")
        val normalized = types.map { it.trim().lowercase() }.filter { it in allowed }.toSet()
        return normalized.ifEmpty { allowed }
    }

    private fun matchesSearchFilters(post: Post, input: ContentSearchInput): Boolean {
        val tags = input.tags.map { it.trim().removePrefix("#").lowercase() }.filter(String::isNotBlank).toSet()
        if (tags.isNotEmpty() && post.tags.none { it.lowercase() in tags }) return false
        val author = input.author?.trim()?.removePrefix("@")?.lowercase()?.takeIf(String::isNotBlank)
        if (author != null) {
            val authorText = listOf(post.author?.username, post.author?.displayName, post.ownerId, post.authorId)
                .filterNotNull()
                .joinToString(" ")
                .lowercase()
            if (!authorText.contains(author)) return false
        }
        return isWithinSearchDate(post.createdAt, input)
    }

    private fun matchesSearchFilters(comment: Comment, input: ContentSearchInput): Boolean =
        isWithinSearchDate(comment.createdAt, input)

    private fun matchesSearchFilters(collection: SavedCollection, input: ContentSearchInput): Boolean =
        isWithinSearchDate(collection.updatedAt, input)

    private fun isWithinSearchDate(value: Instant, input: ContentSearchInput): Boolean {
        val from = input.dateFrom?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val to = input.dateTo?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (from != null && value.isBefore(from)) return false
        if (to != null && value.isAfter(to)) return false
        return true
    }

    private fun postSearchItem(post: Post, score: Double, snippet: String?): ContentSearchItem =
        ContentSearchItem(
            type = "POST",
            id = post.id,
            title = post.title ?: post.text.lineSequence().firstOrNull()?.take(80),
            snippet = snippet ?: post.text.take(180),
            owner = post.author,
            url = "/p/${post.id}",
            score = score,
            createdAt = post.createdAt.toString(),
            postId = post.id,
            tags = post.tags,
            meta = mapOf(
                "likeCount" to post.likeCount.toString(),
                "visibility" to post.visibility.name
            ),
            typeLabel = "Post",
            thumbnailUrl = post.blocks.firstNotNullOfOrNull { it.mediaSource() },
            highlights = listOfNotNull(snippet?.takeIf(String::isNotBlank), post.text.take(180).takeIf(String::isNotBlank)).distinct()
        )

    private fun collectionSearchItem(
        collection: SavedCollection,
        owner: AccountUser?,
        score: Double,
        snippet: String?
    ): ContentSearchItem =
        ContentSearchItem(
            type = "COLLECTION",
            id = collection.id,
            title = collection.title,
            snippet = snippet ?: collection.description,
            owner = owner,
            url = owner?.let { "/${if (it.ownerType == OwnerType.ORGANIZATION) "o" else "u"}/${it.username}/collections/${collection.id}" }
                ?: "/collections/${collection.id}",
            score = score,
            createdAt = collection.updatedAt.toString(),
            meta = mapOf(
                "itemCount" to collection.itemCount.toString(),
                "visibility" to collection.visibility.name,
                "ownerType" to collection.ownerType.name,
                "ownerId" to collection.ownerId
            ),
            typeLabel = "Collection",
            thumbnailUrl = collection.cover?.mediaSource(),
            highlights = listOfNotNull(snippet?.takeIf(String::isNotBlank), collection.description?.takeIf(String::isNotBlank)).distinct()
        )

    private fun commentSearchItem(comment: Comment, post: Post, score: Double, snippet: String?): ContentSearchItem =
        ContentSearchItem(
            type = "COMMENT",
            id = comment.id,
            title = post.title ?: post.text.take(80),
            snippet = snippet ?: comment.text.take(180),
            owner = comment.author,
            url = "/p/${post.id}?comment=${comment.id}",
            score = score,
            createdAt = comment.createdAt.toString(),
            postId = post.id,
            commentId = comment.id,
            tags = post.tags,
            meta = mapOf("postId" to post.id),
            typeLabel = "Comment",
            thumbnailUrl = post.blocks.firstNotNullOfOrNull { it.mediaSource() },
            highlights = listOfNotNull(snippet?.takeIf(String::isNotBlank), comment.text.take(180).takeIf(String::isNotBlank)).distinct()
        )

    private fun searchFacets(items: List<ContentSearchItem>, input: ContentSearchInput): List<ContentSearchFacet> {
        val selectedTypes = input.types.map { it.trim().lowercase() }.toSet()
        val selectedTags = input.tags.map { it.trim().removePrefix("#").lowercase() }.toSet()
        val selectedAuthor = input.author?.trim()?.removePrefix("@")?.lowercase()

        val typeFacets = listOf(
            typeFacet("posts", "Posts", items.count { it.type == "POST" }, selectedTypes),
            typeFacet("collections", "Collections", items.count { it.type == "COLLECTION" }, selectedTypes),
            typeFacet("comments", "Comments", items.count { it.type == "COMMENT" }, selectedTypes),
            typeFacet("tags", "Tags", items.count { it.type == "TAG" }, selectedTypes)
        )

        val tagFacets = items.asSequence()
            .flatMap { it.tags.asSequence() }
            .map { it.trim().removePrefix("#").lowercase() }
            .filter(String::isNotBlank)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(12)
            .map { (tag, count) ->
                ContentSearchFacet("tag", tag, "#$tag", count, selectedTags.contains(tag))
            }

        val ownerFacets = items.asSequence()
            .mapNotNull { item ->
                item.owner?.let { owner ->
                    val value = owner.id
                    val label = owner.displayName ?: "@${owner.username}"
                    value to label
                }
            }
            .groupBy({ it.first }, { it.second })
            .map { (ownerId, labels) ->
                ContentSearchFacet("owner", ownerId, labels.first(), labels.size, selectedAuthor == ownerId.lowercase() || selectedAuthor == labels.first().lowercase())
            }
            .sortedWith(compareByDescending<ContentSearchFacet> { it.count }.thenBy { it.label.lowercase() })
            .take(10)

        val dateFacets = dateRangeFacets(items)

        val providerFacets = listOf(ContentSearchFacet("provider", "content", "Content", items.size))

        return (typeFacets + providerFacets + tagFacets + ownerFacets + dateFacets)
            .filter { it.count > 0 }
    }

    private fun typeFacet(value: String, label: String, count: Int, selected: Set<String>): ContentSearchFacet =
        ContentSearchFacet("type", value, label, count, selected.contains(value))

    private fun dateRangeFacets(items: List<ContentSearchItem>): List<ContentSearchFacet> {
        val now = Instant.now(clock)
        val parsed = items.mapNotNull { item -> item.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } }
        return listOf(
            ContentSearchFacet("dateRange", "day", "Past day", parsed.count { it.isAfter(now.minusSeconds(24 * 60 * 60)) }),
            ContentSearchFacet("dateRange", "week", "Past week", parsed.count { it.isAfter(now.minusSeconds(7 * 24 * 60 * 60)) }),
            ContentSearchFacet("dateRange", "month", "Past month", parsed.count { it.isAfter(now.minusSeconds(30 * 24 * 60 * 60)) })
        ).filter { it.count > 0 }
    }

    private fun sortSearchItems(items: List<ContentSearchItem>, sort: String): List<ContentSearchItem> =
        when (sort.lowercase()) {
            "new" -> items.sortedByDescending { it.createdAt.orEmpty() }
            "popular" -> items.sortedWith(compareByDescending<ContentSearchItem> { it.meta["likeCount"]?.toLongOrNull() ?: 0L }.thenByDescending { it.score })
            else -> items.sortedWith(compareByDescending<ContentSearchItem> { it.score }.thenByDescending { it.createdAt.orEmpty() })
        }

    companion object {
        const val STORY_VIDEO_MAX_MS = 60_000L
        const val STORY_IMAGE_DURATION_MS = 5_000L
        private const val FEED_CELL_COLUMNS = 3
        private const val EXPLORATION_INTERVAL = 8
    }
}

private fun Post.ownerKey(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Post.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun Comment.ownerKey(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Comment.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun Story.ownerKey(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun Story.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun OwnerRef.key(): String =
    if (ownerType == OwnerType.USER) ownerId else "${ownerType.name}:$ownerId"

private fun SavedCollection.ownerRef(): OwnerRef =
    OwnerRef(ownerType, ownerId)

private fun String.toOwnerRef(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) OwnerRef(OwnerType.valueOf(parts[0]), parts[1]) else OwnerRef(OwnerType.USER, this)
}

private fun AccountVisibility.viewerRef(): OwnerRef? =
    viewerId?.let { OwnerRef(viewerType, it) }
