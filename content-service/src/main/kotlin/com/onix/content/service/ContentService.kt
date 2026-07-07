package com.onix.content.service

import com.onix.content.domain.*
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
    private val clock: Clock = Clock.systemUTC()
) {
    private data class ScoredRecommendation(
        val post: Post,
        val score: Double,
        val reasons: List<String>,
        val emphasis: FeedEmphasis
    )

    fun createPost(author: SessionUser, input: CreatePostInput): Post {
        val normalizedTags = input.tags.map { it.trim().lowercase() }.filter(String::isNotBlank).distinct().take(20)
        val blocks = input.blocks.ifEmpty { if (input.text.isBlank()) emptyList() else listOf(textBlock(input.text)) }
        val text = input.text.ifBlank { blocks.joinToString(" ") { it.searchText() }.trim() }
        require(text.isNotBlank() || blocks.isNotEmpty()) { "Post must contain text or media" }
        val now = Instant.now(clock)
        val post = repository.savePost(
            Post(
                id = UUID.randomUUID().toString(),
                authorId = author.id,
                author = author.asAccountUser(),
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
        return post
    }

    fun createStory(author: SessionUser, input: CreateStoryInput): Story {
        require(input.blocks.isNotEmpty()) { "Story must contain at least one block" }
        val now = Instant.now(clock)
        val blocks = input.blocks.map(::normalizeStoryBlock)
        return repository.saveStory(
            Story(
                id = UUID.randomUUID().toString(),
                authorId = author.id,
                author = author.asAccountUser(),
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
    }

    fun createComment(author: SessionUser, input: CreateCommentInput): Comment {
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
                authorId = author.id,
                author = author.asAccountUser(),
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

    fun profileContent(
        ownerId: String,
        visibility: AccountVisibility,
        postLimit: Int,
        storyLimit: Int,
        authorResolver: (String) -> AccountUser? = { null }
    ): ProfileContentResponse {
        if (!visibility.canSeePrivateContent) {
            return ProfileContentResponse()
        }
        val now = Instant.now(clock)
        val posts = repository.listPostsByAuthor(ownerId, postLimit.coerceIn(1, 500))
            .map { withViewerState(it, visibility.viewerId) }
            .map { withAuthor(it, authorResolver) }
        val stories = repository.listActiveStoriesByAuthor(ownerId, now, storyLimit.coerceIn(1, 50))
            .filter { canViewStory(it, visibility, includeArchived = false) }
            .map { enrichStory(it, now = now, viewerId = visibility.viewerId) }
        val comments = posts.flatMap { repository.listCommentsForPost(it.id, 3) }
            .map { withCommentViewerState(it, visibility.viewerId, authorResolver) }
        return ProfileContentResponse(posts = posts, stories = stories, comments = comments)
    }

    fun feed(
        viewerId: String,
        tagAffinity: Set<String>,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<FeedItem> {
        return repository.listRecentPosts(limit.coerceIn(1, 100) * 3)
            .map { withViewerState(it, viewerId) }
            .map { withAuthor(it, authorResolver) }
            .map { post ->
                val tagScore = post.tags.count { it in tagAffinity } * 4.0
                val likeScore = post.likeCount.coerceAtMost(30) * 0.15
                val ageHours = java.time.Duration.between(post.createdAt, Instant.now(clock)).toHours().coerceAtLeast(0)
                val recencyScore = 1.0 / (1 + ageHours).toDouble()
                val ownPenalty = if (post.authorId == viewerId) -2.0 else 0.0
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
    ): RecommendationFeedResponse {
        val pageLimit = input.limit.coerceIn(1, 50)
        val seed = input.sessionSeed.ifBlank { "default" }
        val blockedIds = socialGraph.blockedIds.toSet()
        val tagAffinity = repository.listViewerTagAffinity(viewerId, 80).toSet()
        val now = Instant.now(clock)
        val candidates = repository.listRecentPosts(500)
            .filter { it.authorId !in blockedIds }
            .map { withViewerState(it, viewerId) }
            .map { withAuthor(it, authorResolver) }
            .map { post -> scoreRecommendation(post, viewerId, tagAffinity, socialGraph, now) }

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
        repository.findPost(id)
            ?.let { withViewerState(it, viewerId) }
            ?.let { withAuthor(it, authorResolver) }

    fun likePost(user: SessionUser, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, user.id, true)
        return PostReactionState(postId = postId, liked = true, likeCount = repository.countPostLikes(postId))
    }

    fun unlikePost(user: SessionUser, postId: String): PostReactionState {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.setPostLike(postId, user.id, false)
        return PostReactionState(postId = postId, liked = false, likeCount = repository.countPostLikes(postId))
    }

    fun likeStory(user: SessionUser, storyId: String): StoryReactionState {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.setStoryLike(storyId, user.id, true)
        return StoryReactionState(storyId = storyId, liked = true, likeCount = repository.countStoryLikes(storyId))
    }

    fun unlikeStory(user: SessionUser, storyId: String): StoryReactionState {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.setStoryLike(storyId, user.id, false)
        return StoryReactionState(storyId = storyId, liked = false, likeCount = repository.countStoryLikes(storyId))
    }

    fun likeComment(user: SessionUser, commentId: String): CommentReactionState {
        repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        repository.setCommentLike(commentId, user.id, true)
        return CommentReactionState(commentId = commentId, liked = true, likeCount = repository.countCommentLikes(commentId))
    }

    fun unlikeComment(user: SessionUser, commentId: String): CommentReactionState {
        repository.findComment(commentId) ?: throw IllegalArgumentException("Comment not found")
        repository.setCommentLike(commentId, user.id, false)
        return CommentReactionState(commentId = commentId, liked = false, likeCount = repository.countCommentLikes(commentId))
    }

    fun story(id: String, viewerId: String? = null): Story? =
        repository.findStory(id)?.let { enrichStory(it, now = Instant.now(clock), viewerId = viewerId) }

    fun comments(
        postId: String,
        limit: Int,
        viewerId: String? = null,
        authorResolver: (String) -> AccountUser? = { null }
    ): List<Comment> =
        repository.listCommentsForPost(postId, limit.coerceIn(1, 100))
            .map { withCommentViewerState(it, viewerId, authorResolver) }

    fun storiesFeed(
        viewerId: String,
        limit: Int,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewerId) }
    ): List<StoryRailItem> {
        val now = Instant.now(clock)
        return repository.listActiveStories(now, limit.coerceIn(1, 100))
            .filter { story -> canViewStory(story, visibilityResolver(story.authorId), includeArchived = false) }
            .groupBy { it.authorId }
            .values
            .map { stories ->
                val latest = stories.maxBy { it.createdAt }
                val oldest = stories.minBy { it.createdAt }
                val author = authorResolver(latest.authorId)
                StoryRailItem(
                    authorId = latest.authorId,
                    authorName = author?.username ?: if (latest.authorId == viewerId) "You" else "User",
                    author = author,
                    avatarUrl = author?.avatarUrl,
                    storyIds = stories.sortedBy { it.createdAt }.map { it.id },
                    activeCount = stories.size,
                    seen = stories.all { repository.isStoryViewed(it.id, viewerId) },
                    closeFriends = stories.any { it.visibility == Visibility.CLOSE_FRIENDS },
                    isViewer = latest.authorId == viewerId,
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
        startStoryId: String?,
        authorResolver: (String) -> AccountUser? = { null },
        visibilityResolver: (String) -> AccountVisibility = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewerId) },
        archive: Boolean = false
    ): StoryGroup {
        val now = Instant.now(clock)
        val visibility = visibilityResolver(authorId)
        val rawStories = if (archive) {
            repository.listArchivedStoriesByAuthor(authorId, now, 100)
        } else {
            repository.listActiveStoriesByAuthor(authorId, now, 100)
        }
        val author = authorResolver(authorId)
        val stories = rawStories
            .filter { canViewStory(it, visibility, includeArchived = archive) }
            .sortedBy { it.createdAt }
            .map { enrichStory(it, author, now, viewerId) }
        return StoryGroup(
            authorId = authorId,
            authorName = author?.username ?: if (authorId == viewerId) "You" else "User",
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
            .map { enrichStory(it, author, now, visibility.viewerId) }
        val page = stories.take(pageLimit)
        return StoryArchiveResponse(
            ownerId = ownerId,
            owner = author,
            stories = page,
            cursor = cursor?.toString(),
            nextCursor = stories.getOrNull(pageLimit)?.createdAt?.toString()
        )
    }

    fun recordStoryView(user: SessionUser, storyId: String): Boolean {
        repository.findStory(storyId) ?: throw IllegalArgumentException("Story not found")
        repository.recordStoryView(storyId, user.id, Instant.now(clock))
        return true
    }

    fun recordPostView(user: SessionUser, postId: String, durationMs: Long = 0): Boolean {
        repository.findPost(postId) ?: throw IllegalArgumentException("Post not found")
        repository.recordPostView(postId, user.id, durationMs, Instant.now(clock))
        return true
    }

    private fun scoreRecommendation(
        post: Post,
        viewerId: String,
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
        val ownPenalty = if (post.authorId == viewerId) -4.0 else 0.0
        val viewerViews = repository.countPostViewsByUser(post.id, viewerId)
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
        if (story.authorId == visibility.viewerId) return true
        if (visibility.ownerId != story.authorId) return false
        return when (story.visibility) {
            Visibility.PUBLIC -> visibility.canSeePrivateContent
            Visibility.CLOSE_FRIENDS -> visibility.isCloseFriend
        }
    }

    private fun enrichStory(story: Story, author: AccountUser? = story.author, now: Instant, viewerId: String? = null): Story =
        story.copy(
            author = author,
            durationMs = storyDurationMs(story.blocks),
            mediaDurationMs = storyMediaDurationMs(story.blocks),
            closeFriends = story.visibility == Visibility.CLOSE_FRIENDS,
            archived = story.status == ContentStatus.ARCHIVED || !story.expiresAt.isAfter(now),
            likeCount = repository.countStoryLikes(story.id),
            likedByViewer = viewerId?.let { repository.isStoryLikedBy(story.id, it) } ?: false,
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

    private fun SessionUser.asAccountUser(): AccountUser =
        AccountUser(id = id, username = username, firstName = firstName, lastName = lastName, avatarUrl = avatarUrl)

    private fun withViewerState(post: Post, viewerId: String?): Post =
        post.copy(
            likeCount = repository.countPostLikes(post.id),
            likedByViewer = viewerId?.let { repository.isPostLikedBy(post.id, it) } ?: false
        )

    private fun withAuthor(post: Post, authorResolver: (String) -> AccountUser?): Post =
        post.copy(author = post.author ?: authorResolver(post.authorId))

    private fun withCommentViewerState(
        comment: Comment,
        viewerId: String?,
        authorResolver: (String) -> AccountUser?
    ): Comment =
        comment.copy(
            author = comment.author ?: authorResolver(comment.authorId),
            likeCount = repository.countCommentLikes(comment.id),
            likedByViewer = viewerId?.let { repository.isCommentLikedBy(comment.id, it) } ?: false,
            blocks = comment.blocks.ifEmpty { if (comment.text.isBlank()) emptyList() else listOf(textBlock(comment.text)) }
        )

    companion object {
        const val STORY_VIDEO_MAX_MS = 60_000L
        const val STORY_IMAGE_DURATION_MS = 5_000L
        private const val FEED_CELL_COLUMNS = 3
        private const val EXPLORATION_INTERVAL = 8
    }
}
