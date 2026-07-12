package com.onix.content

import com.onix.content.api.enrichUploadBlocks
import com.onix.content.domain.*
import com.onix.content.media.UploadedMedia
import com.onix.content.search.SearchEventPublisher
import com.onix.content.search.SearchIndexClient
import com.onix.content.search.SearchIndexHit
import com.onix.content.search.SearchIndexResult
import com.onix.content.service.ContentService
import com.onix.content.service.InMemoryContentRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentServiceTest {
    private val user = SessionUser(id = "11111111-1111-1111-1111-111111111111", username = "alice")
    private val viewer = SessionUser(id = "22222222-2222-2222-2222-222222222222", username = "viewer")

    @Test
    fun `comments are linear even when legacy parent id is sent`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Hello", tags = listOf("kotlin")))
        val root = service.createComment(viewer, CreateCommentInput(postId = post.id, text = "Root"))
        val reply = service.createComment(user, CreateCommentInput(postId = post.id, parentId = root.id, text = "Reply"))

        assertEquals(null, root.parentId)
        assertEquals(null, reply.parentId)
    }

    @Test
    fun `comments can be disabled per post`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "No replies", allowComments = false))

        val error = assertFailsWith<IllegalArgumentException> {
            service.createComment(viewer, CreateCommentInput(postId = post.id, text = "Blocked"))
        }

        assertEquals("Comments are disabled for this post", error.message)
    }

    @Test
    fun `file blocks are accepted for posts`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(
            text = "[brief.pdf](media:file-1)",
            blocks = listOf(
                textBlock("[brief.pdf](media:file-1)"),
                ContentBlock(
                    id = "33333333-3333-3333-3333-333333333333",
                    type = ContentBlockType.FILE,
                    data = JsonObject(mapOf(
                        "fileName" to JsonPrimitive("brief.pdf"),
                        "mimeType" to JsonPrimitive("application/pdf"),
                        "markdownRef" to JsonPrimitive("media:file-1")
                    ))
                )
            )
        ))

        assertEquals(ContentBlockType.FILE, post.blocks.last().type)
        assertEquals("brief.pdf", post.blocks.last().data["fileName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `comments accept markdown blocks and files`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Hello"))
        val comment = service.createComment(viewer, CreateCommentInput(
            postId = post.id,
            text = "Nice ![[media:file-1|brief.pdf]]",
            blocks = listOf(
                textBlock("Nice ![[media:file-1|brief.pdf]]"),
                ContentBlock(
                    id = "33333333-3333-3333-3333-333333333333",
                    type = ContentBlockType.FILE,
                    data = JsonObject(mapOf(
                        "fileName" to JsonPrimitive("brief.pdf"),
                        "mimeType" to JsonPrimitive("application/pdf"),
                        "markdownRef" to JsonPrimitive("media:file-1")
                    ))
                )
            )
        ))

        assertEquals(2, comment.blocks.size)
        assertEquals(ContentBlockType.FILE, comment.blocks.last().type)
    }

    @Test
    fun `multipart upload enrichment fills file block metadata`() {
        val blocks = JsonArray(listOf(
            textBlock("See [brief.pdf](media:file-1)").let {
                JsonObject(mapOf(
                    "id" to JsonPrimitive(it.id),
                    "type" to JsonPrimitive(it.type.name),
                    "data" to it.data
                ))
            },
            JsonObject(mapOf(
                "id" to JsonPrimitive("33333333-3333-3333-3333-333333333333"),
                "type" to JsonPrimitive(ContentBlockType.FILE.name),
                "data" to JsonObject(mapOf("markdownRef" to JsonPrimitive("media:file-1")))
            ))
        ))

        val enriched = enrichUploadBlocks(blocks, listOf(UploadedMedia(
            fileName = "brief.pdf",
            mimeType = "application/pdf",
            size = 42,
            blobId = "44444444-4444-4444-4444-444444444444"
        )))

        val fileData = enriched.last().jsonObject["data"]!!.jsonObject
        assertEquals("44444444-4444-4444-4444-444444444444", fileData["blobId"]?.jsonPrimitive?.content)
        assertEquals("brief.pdf", fileData["fileName"]?.jsonPrimitive?.content)
        assertEquals("application/pdf", fileData["mimeType"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(42L), fileData["size"])
    }

    @Test
    fun `private profile returns no content for non follower`() {
        val service = service()
        service.createPost(user, CreatePostInput(text = "Private post"))

        val response = service.profileContent(
            ownerId = user.id,
            visibility = AccountVisibility(ownerId = user.id, viewerId = viewer.id, isPrivate = true),
            postLimit = 10,
            storyLimit = 10
        )

        assertTrue(response.posts.isEmpty())
    }

    @Test
    fun `close friends stories require close friend visibility`() {
        val service = service()
        service.createStory(user, CreateStoryInput(blocks = listOf(textBlock("Story")), visibility = Visibility.CLOSE_FRIENDS))

        val hidden = service.profileContent(
            ownerId = user.id,
            visibility = AccountVisibility(ownerId = user.id, viewerId = viewer.id, isPrivate = false, isCloseFriend = false),
            postLimit = 10,
            storyLimit = 10
        )
        val visible = service.profileContent(
            ownerId = user.id,
            visibility = AccountVisibility(ownerId = user.id, viewerId = viewer.id, isPrivate = false, isCloseFriend = true),
            postLimit = 10,
            storyLimit = 10
        )

        assertTrue(hidden.stories.isEmpty())
        assertEquals(1, visible.stories.size)
    }

    @Test
    fun `feed boosts posts matching tag affinity`() {
        val service = service()
        service.createPost(user, CreatePostInput(text = "General", tags = listOf("general")))
        val matched = service.createPost(user, CreatePostInput(text = "Kotlin", tags = listOf("kotlin")))

        val feed = service.feed(viewer.id, setOf("kotlin"), 2)

        assertEquals(matched.id, feed.first().post.id)
        assertTrue(feed.first().reasons.contains("tag-affinity"))
    }

    @Test
    fun `recommendation feed is deterministic for the same chunk and seed`() {
        val service = service()
        repeat(18) { index ->
            service.createPost(user, CreatePostInput(text = "Post $index", tags = listOf("tag-${index % 3}")))
        }

        val input = RecommendationFeedInput(chunkX = 0, chunkY = 0, sessionSeed = "stable", limit = 9)
        val first = service.recommendationFeed(viewer.id, input)
        val second = service.recommendationFeed(viewer.id, input)

        assertEquals(first, second)
        assertEquals(first.items.map { it.cell }, first.items.mapIndexed { index, _ -> FeedCell(index % 3, index / 3) })
    }

    @Test
    fun `recommendation neighbor chunks do not duplicate posts`() {
        val service = service()
        repeat(36) { index ->
            service.createPost(user, CreatePostInput(text = "Post $index", tags = listOf("tag-${index % 4}")))
        }

        val first = service.recommendationFeed(viewer.id, RecommendationFeedInput(chunkX = 0, chunkY = 0, sessionSeed = "stable", limit = 6))
        val neighbor = service.recommendationFeed(viewer.id, RecommendationFeedInput(chunkX = 0, chunkY = -1, sessionSeed = "stable", limit = 6))

        assertTrue(first.items.map { it.post.id }.intersect(neighbor.items.map { it.post.id }.toSet()).isEmpty())
    }

    @Test
    fun `recommendation feed boosts authors from social graph`() {
        val service = service()
        val followed = SessionUser(id = "33333333-3333-3333-3333-333333333333", username = "followed")
        val followedPost = service.createPost(followed, CreatePostInput(text = "Social", tags = listOf("general")))
        service.createPost(user, CreatePostInput(text = "Other", tags = listOf("general")))

        val feed = service.recommendationFeed(
            viewer.id,
            RecommendationFeedInput(chunkX = 0, chunkY = 0, sessionSeed = "stable", limit = 6),
            socialGraph = AccountSocialGraph(followingIds = listOf(followed.id))
        )

        assertEquals(followedPost.id, feed.items.first().post.id)
        assertTrue(feed.items.first().reasons.contains("following"))
    }

    @Test
    fun `recommendation feed reserves exploration slots`() {
        val service = service()
        repeat(80) { index ->
            service.createPost(user, CreatePostInput(text = "Post $index", tags = listOf("tag-${index % 5}")))
        }

        val feed = service.recommendationFeed(viewer.id, RecommendationFeedInput(chunkX = 0, chunkY = 0, sessionSeed = "stable", limit = 40))
        val explorationCount = feed.items.count { "explore" in it.reasons }

        assertTrue(explorationCount in 4..6)
    }

    @Test
    fun `record post view contributes tag affinity and seen penalty`() {
        val service = service()
        val viewed = service.createPost(user, CreatePostInput(text = "Viewed Kotlin", tags = listOf("kotlin")))
        val freshMatch = service.createPost(user, CreatePostInput(text = "Fresh Kotlin", tags = listOf("kotlin")))
        service.createPost(user, CreatePostInput(text = "General", tags = listOf("general")))

        service.recordPostView(viewer, viewed.id, 1200)
        val feed = service.recommendationFeed(viewer.id, RecommendationFeedInput(chunkX = 0, chunkY = 0, sessionSeed = "stable", limit = 6))

        assertEquals(freshMatch.id, feed.items.first().post.id)
        assertTrue(feed.items.first().reasons.contains("tag-affinity"))
        assertTrue(feed.items.first { it.post.id == viewed.id }.reasons.contains("seen-before"))
    }

    @Test
    fun `stories feed enriches authors and filters close friends visibility`() {
        val service = service()
        service.createStory(user, CreateStoryInput(blocks = listOf(textBlock("Public")), visibility = Visibility.PUBLIC))
        service.createStory(viewer, CreateStoryInput(blocks = listOf(textBlock("Close")), visibility = Visibility.CLOSE_FRIENDS))

        val feed = service.storiesFeed(
            viewerId = user.id,
            limit = 10,
            authorResolver = { id ->
                when (id) {
                    user.id -> AccountUser(id = user.id, username = user.username, avatarUrl = "/api/avatars/alice")
                    viewer.id -> AccountUser(id = viewer.id, username = viewer.username, avatarUrl = "/api/avatars/viewer")
                    else -> null
                }
            },
            visibilityResolver = { ownerId ->
                AccountVisibility(ownerId = ownerId, viewerId = user.id, isCloseFriend = ownerId == viewer.id)
            }
        )

        val byAuthor = feed.associateBy { it.authorName }
        assertEquals(setOf("viewer", "alice"), byAuthor.keys)
        assertEquals("/api/avatars/viewer", byAuthor.getValue("viewer").avatarUrl)
        assertTrue(byAuthor.getValue("viewer").closeFriends)
    }

    @Test
    fun `stories feed puts viewer first and keeps author stories oldest first`() {
        val repo = InMemoryContentRepository()
        val service = ContentService(repository = repo, clock = fixedClock())
        val now = Instant.parse("2026-07-05T00:00:00Z")
        val first = repo.saveStory(Story(authorId = user.id, blocks = listOf(textBlock("First")), createdAt = now.minusSeconds(120), expiresAt = now.plusSeconds(600)))
        val second = repo.saveStory(Story(authorId = user.id, blocks = listOf(textBlock("Second")), createdAt = now.minusSeconds(60), expiresAt = now.plusSeconds(600)))
        repo.saveStory(Story(authorId = viewer.id, blocks = listOf(textBlock("Viewer")), createdAt = now.minusSeconds(30), expiresAt = now.plusSeconds(600)))

        val feed = service.storiesFeed(
            viewerId = viewer.id,
            limit = 10,
            authorResolver = { id -> AccountUser(id = id, username = if (id == viewer.id) viewer.username else user.username) },
            visibilityResolver = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewer.id) }
        )

        assertEquals(viewer.id, feed.first().authorId)
        assertEquals(listOf(first.id, second.id), feed.first { it.authorId == user.id }.storyIds)
    }

    @Test
    fun `expired stories remain queryable through archive`() {
        val repo = InMemoryContentRepository()
        val service = ContentService(repository = repo, clock = fixedClock())
        val now = Instant.parse("2026-07-05T00:00:00Z")
        repo.saveStory(Story(authorId = user.id, blocks = listOf(textBlock("Expired")), createdAt = now.minusSeconds(90_000), expiresAt = now.minusSeconds(3_600)))

        val feed = service.storiesFeed(
            viewerId = viewer.id,
            limit = 10,
            visibilityResolver = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewer.id) }
        )
        val archive = service.storyArchive(
            ownerId = user.id,
            visibility = AccountVisibility(ownerId = user.id, viewerId = viewer.id),
            author = AccountUser(id = user.id, username = user.username),
            limit = 10
        )

        assertTrue(feed.none { it.authorId == user.id })
        assertEquals(1, archive.stories.size)
        assertTrue(archive.stories.first().archived)
    }

    @Test
    fun `record story view updates feed seen state`() {
        val service = service()
        val story = service.createStory(user, CreateStoryInput(blocks = listOf(textBlock("Seen"))))

        service.recordStoryView(viewer, story.id)
        val feed = service.storiesFeed(
            viewerId = viewer.id,
            limit = 10,
            visibilityResolver = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewer.id) }
        )

        assertTrue(feed.first { it.authorId == user.id }.seen)
    }

    @Test
    fun `video story duration is capped to one minute`() {
        val service = service()
        val story = service.createStory(user, CreateStoryInput(blocks = listOf(ContentBlock(
            type = ContentBlockType.VIDEO,
            data = JsonObject(mapOf("durationMs" to JsonPrimitive(120_000), "fileName" to JsonPrimitive("clip.mp4")))
        ))))

        assertEquals(60_000L, story.durationMs)
        assertEquals(JsonPrimitive(60_000L), story.blocks.first().data["trimEndMs"])
    }

    @Test
    fun `post likes are idempotent and returned with viewer state`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Likeable"))

        val liked = service.likePost(viewer, post.id)
        service.likePost(viewer, post.id)
        val loaded = service.post(post.id, viewer.id)
        val unliked = service.unlikePost(viewer, post.id)

        assertTrue(liked.liked)
        assertEquals(1L, liked.likeCount)
        assertEquals(1L, loaded?.likeCount)
        assertEquals(true, loaded?.likedByViewer)
        assertEquals(false, unliked.liked)
        assertEquals(0L, unliked.likeCount)
    }

    @Test
    fun `comment likes are idempotent and returned with viewer state`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Likeable"))
        val comment = service.createComment(user, CreateCommentInput(postId = post.id, text = "Comment"))

        val liked = service.likeComment(viewer, comment.id)
        service.likeComment(viewer, comment.id)
        val loaded = service.comments(post.id, 10, viewer.id).first()
        val unliked = service.unlikeComment(viewer, comment.id)

        assertTrue(liked.liked)
        assertEquals(1L, liked.likeCount)
        assertEquals(1L, loaded.likeCount)
        assertEquals(true, loaded.likedByViewer)
        assertEquals(false, unliked.liked)
        assertEquals(0L, unliked.likeCount)
    }

    @Test
    fun `posts and comments are enriched with account authors`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Authored"))
        service.createComment(viewer, CreateCommentInput(postId = post.id, text = "Hello"))
        val resolver: (String) -> AccountUser? = { id ->
            when (id) {
                user.id -> AccountUser(id = user.id, username = "alice", avatarUrl = "/alice.png")
                viewer.id -> AccountUser(id = viewer.id, username = "viewer", avatarUrl = "/viewer.png")
                else -> null
            }
        }

        val feed = service.feed(viewer.id, emptySet(), 10, resolver)
        val comments = service.comments(post.id, 10, viewer.id, resolver)

        assertEquals("alice", feed.first().post.author?.username)
        assertEquals("viewer", comments.first().author?.username)
    }

    @Test
    fun `story likes are idempotent and returned with viewer state`() {
        val service = service()
        val story = service.createStory(user, CreateStoryInput(blocks = listOf(textBlock("Likeable story"))))

        val liked = service.likeStory(viewer, story.id)
        service.likeStory(viewer, story.id)
        val loaded = service.story(story.id, viewer.id)
        val group = service.storyGroup(
            viewerId = viewer.id,
            authorId = user.id,
            startStoryId = story.id,
            visibilityResolver = { ownerId -> AccountVisibility(ownerId = ownerId, viewerId = viewer.id) }
        )
        val unliked = service.unlikeStory(viewer, story.id)

        assertTrue(liked.liked)
        assertEquals(1L, liked.likeCount)
        assertEquals(1L, loaded?.likeCount)
        assertEquals(true, loaded?.likedByViewer)
        assertEquals(1L, group.stories.first().likeCount)
        assertEquals(true, group.stories.first().likedByViewer)
        assertEquals(false, unliked.liked)
        assertEquals(0L, unliked.likeCount)
    }

    @Test
    fun `collection title is required and collections are scoped to active owner`() {
        val service = service()
        val error = assertFailsWith<IllegalArgumentException> {
            service.createCollection(actor(viewer), CreateCollectionInput(title = " "))
        }

        val collection = service.createCollection(actor(viewer), CreateCollectionInput(title = "Saved", visibility = CollectionVisibility.PRIVATE))

        assertEquals("Collection title is required", error.message)
        assertEquals(viewer.id, collection.ownerId)
        assertEquals(CollectionVisibility.PRIVATE, collection.visibility)
    }

    @Test
    fun `private collections are visible only to owner while public follows profile access`() {
        val service = service()
        service.createCollection(actor(user), CreateCollectionInput(title = "Private", visibility = CollectionVisibility.PRIVATE))
        service.createCollection(actor(user), CreateCollectionInput(title = "Public", visibility = CollectionVisibility.PUBLIC))

        val ownerView = service.collections(
            OwnerRef(OwnerType.USER, user.id),
            AccountVisibility(ownerId = user.id, viewerId = user.id),
            10
        )
        val viewerView = service.collections(
            OwnerRef(OwnerType.USER, user.id),
            AccountVisibility(ownerId = user.id, viewerId = viewer.id),
            10
        )
        val lockedView = service.collections(
            OwnerRef(OwnerType.USER, user.id),
            AccountVisibility(ownerId = user.id, viewerId = viewer.id, isPrivate = true),
            10
        )

        assertEquals(setOf("Private", "Public"), ownerView.map { it.title }.toSet())
        assertEquals(listOf("Public"), viewerView.map { it.title })
        assertTrue(lockedView.isEmpty())
    }

    @Test
    fun `post cannot be saved when viewer cannot access it`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Close", visibility = Visibility.CLOSE_FRIENDS))
        val collection = service.createCollection(actor(viewer), CreateCollectionInput(title = "Saved"))

        val error = assertFailsWith<IllegalArgumentException> {
            service.setPostCollections(
                actor = actor(viewer),
                input = SetPostCollectionsInput(postId = post.id, collectionIds = listOf(collection.id)),
                visibilityResolver = { ownerKey ->
                    val owner = ownerKey.toVisibilityOwner()
                    AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer.id, isCloseFriend = false)
                }
            )
        }

        assertEquals("Post is not available", error.message)
    }

    @Test
    fun `set post collections is idempotent and supports multiple collections`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Visible"))
        val first = service.createCollection(actor(viewer), CreateCollectionInput(title = "First"))
        val second = service.createCollection(actor(viewer), CreateCollectionInput(title = "Second"))
        val visibility: (String) -> AccountVisibility = { ownerKey ->
            val owner = ownerKey.toVisibilityOwner()
            AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer.id)
        }

        val saved = service.setPostCollections(actor(viewer), SetPostCollectionsInput(post.id, listOf(first.id, second.id)), visibility)
        val savedAgain = service.setPostCollections(actor(viewer), SetPostCollectionsInput(post.id, listOf(second.id, first.id)), visibility)
        val reduced = service.setPostCollections(actor(viewer), SetPostCollectionsInput(post.id, listOf(second.id)), visibility)

        assertEquals(setOf(first.id, second.id), saved.collectionIds.toSet())
        assertEquals(setOf(first.id, second.id), savedAgain.collectionIds.toSet())
        assertEquals(listOf(second.id), reduced.collectionIds)
    }

    @Test
    fun `collection preview uses up to three visible media posts`() {
        val service = service()
        val collection = service.createCollection(actor(viewer), CreateCollectionInput(title = "Media", visibility = CollectionVisibility.PUBLIC))
        val posts = (1..4).map { index ->
            service.createPost(user, CreatePostInput(
                text = "Media $index",
                blocks = listOf(ContentBlock(
                    id = "33333333-3333-3333-3333-33333333333$index",
                    type = if (index % 2 == 0) ContentBlockType.VIDEO else ContentBlockType.IMAGE,
                    data = JsonObject(mapOf("src" to JsonPrimitive("https://example.test/$index.jpg")))
                ))
            ))
        }
        val visibility: (String) -> AccountVisibility = { ownerKey ->
            val owner = ownerKey.toVisibilityOwner()
            AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer.id)
        }
        posts.forEach {
            service.addPostToCollection(actor(viewer), collection.id, it.id, visibility)
        }

        val detail = service.collection(collection.id, OwnerRef(OwnerType.USER, viewer.id), visibility)

        assertEquals(4, detail.collection.itemCount)
        assertEquals(3, detail.collection.previewBlocks.size)
        assertFalse(detail.collection.previewBlocks.any { it.type == ContentBlockType.TEXT })
    }

    @Test
    fun `collections publish search index events when changed`() {
        val publisher = RecordingSearchEvents()
        val service = ContentService(repository = InMemoryContentRepository(), searchEvents = publisher, clock = fixedClock())
        val post = service.createPost(user, CreatePostInput(text = "Saved post"))
        val collection = service.createCollection(actor(user), CreateCollectionInput(title = "References"))
        val visibility: (String) -> AccountVisibility = { ownerKey ->
            val owner = ownerKey.toVisibilityOwner()
            AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = user.id)
        }

        service.updateCollection(actor(user), UpdateCollectionInput(id = collection.id, description = "Updated"))
        service.addPostToCollection(actor(user), collection.id, post.id, visibility)
        service.removePostFromCollection(actor(user), collection.id, post.id)
        service.deleteCollection(actor(user), collection.id)

        assertTrue(publisher.events.contains("collection-upsert:${collection.id}"))
        assertTrue(publisher.events.count { it == "collection-upsert:${collection.id}" } >= 4)
        assertTrue(publisher.events.contains("collection-delete:${collection.id}"))
    }

    @Test
    fun `search filters private collections and close friends posts by visibility`() {
        val repository = InMemoryContentRepository()
        val setup = ContentService(repository = repository, clock = fixedClock())
        val hiddenPost = setup.createPost(user, CreatePostInput(text = "Hidden", visibility = Visibility.CLOSE_FRIENDS))
        val publicCollection = setup.createCollection(actor(user), CreateCollectionInput(title = "Public references", visibility = CollectionVisibility.PUBLIC))
        val privateCollection = setup.createCollection(actor(user), CreateCollectionInput(title = "Private references", visibility = CollectionVisibility.PRIVATE))
        val service = ContentService(
            repository = repository,
            searchIndex = FakeSearchIndex(
                mapOf(
                    "posts" to listOf(hiddenPost.id),
                    "collections" to listOf(publicCollection.id, privateCollection.id)
                )
            ),
            clock = fixedClock()
        )
        val visibility: (String) -> AccountVisibility = { ownerKey ->
            val owner = ownerKey.toVisibilityOwner()
            AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer.id, isCloseFriend = false)
        }

        val response = service.search(
            viewer = OwnerRef(OwnerType.USER, viewer.id),
            input = ContentSearchInput(query = "references", types = listOf("posts", "collections"), limit = 10),
            visibilityResolver = visibility
        )

        assertEquals(listOf(publicCollection.id), response.items.map { it.id })
    }

    private fun service() = ContentService(
        repository = InMemoryContentRepository(),
        clock = fixedClock()
    )

    private fun actor(user: SessionUser, ownerType: OwnerType = OwnerType.USER) =
        CurrentActor(
            user = user,
            activeOwner = AccountUser(
                id = user.id,
                ownerType = ownerType,
                username = user.username,
                firstName = user.firstName,
                lastName = user.lastName,
                avatarUrl = user.avatarUrl
            )
        )

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC)
}

private fun String.toVisibilityOwner(): OwnerRef {
    val parts = split(":", limit = 2)
    return if (parts.size == 2) OwnerRef(OwnerType.valueOf(parts[0]), parts[1]) else OwnerRef(OwnerType.USER, this)
}

private class FakeSearchIndex(private val idsByCollection: Map<String, List<String>>) : SearchIndexClient {
    override fun search(collection: String, query: String, limit: Int): SearchIndexResult =
        SearchIndexResult(idsByCollection[collection].orEmpty().take(limit).mapIndexed { index, id ->
            SearchIndexHit(id = id, score = 1.0 - index * 0.01)
        })
}

private class RecordingSearchEvents : SearchEventPublisher {
    val events = mutableListOf<String>()

    override fun postUpsert(post: Post) {
        events.add("post-upsert:${post.id}")
    }

    override fun commentUpsert(comment: Comment) {
        events.add("comment-upsert:${comment.id}")
    }

    override fun collectionUpsert(collection: SavedCollection) {
        events.add("collection-upsert:${collection.id}")
    }

    override fun collectionDelete(collectionId: String) {
        events.add("collection-delete:$collectionId")
    }
}
