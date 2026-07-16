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
import com.onix.content.service.UploadedAssetVerifier
import com.onix.content.service.MediaAssetProcessor
import com.onix.content.service.RequestedMediaProcessing
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentServiceTest {
    private val user = SessionUser(id = "11111111-1111-1111-1111-111111111111", username = "alice")
    private val viewer = SessionUser(id = "22222222-2222-2222-2222-222222222222", username = "viewer")

    @Test
    fun `reply keeps the root parent id`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Hello", tags = listOf("kotlin")))
        val root = service.createComment(viewer, CreateCommentInput(postId = post.id, text = "Root"))
        val reply = service.createComment(user, CreateCommentInput(postId = post.id, parentId = root.id, text = "Reply"))

        assertEquals(null, root.parentId)
        assertEquals(root.id, reply.parentId)
    }

    @Test
    fun `draft is private until its author publishes it`() {
        val service = service()
        val draft = service.savePostDraft(actor(user), SavePostDraftInput(text = "Набросок", blocks = listOf(textBlock("Набросок"))))

        assertEquals(ContentStatus.DRAFT, draft.status)
        assertTrue(service.listPostDrafts(actor(user)).any { it.id == draft.id })
        assertEquals(null, service.post(draft.id, viewer.id))

        val published = service.publishPostDraft(actor(user), draft.id)
        assertEquals(ContentStatus.ACTIVE, published.status)
        assertEquals(Visibility.PUBLIC, published.visibility)
        assertTrue(service.post(published.id, viewer.id) != null)
    }

    @Test
    fun `only meaningful content can be published while blank drafts remain valid`() {
        val service = service()

        assertFailsWith<IllegalArgumentException> {
            service.createPost(user, CreatePostInput(title = "Только заголовок", tags = listOf("design")))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createPost(user, CreatePostInput(text = ":::onix DIVIDER {}", blocks = listOf(
                ContentBlock(type = ContentBlockType.DIVIDER)
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createPost(user, CreatePostInput(blocks = listOf(ContentBlock(
                type = ContentBlockType.FILE,
                data = JsonObject(mapOf("markdownRef" to JsonPrimitive("media:pending-upload")))
            ))))
        }

        val blankDraft = service.savePostDraft(actor(user), SavePostDraftInput(title = "Набросок"))
        assertEquals(ContentStatus.DRAFT, blankDraft.status)
        assertFailsWith<IllegalArgumentException> { service.publishPostDraft(actor(user), blankDraft.id) }

        val legacy = service.createPost(user, CreatePostInput(text = "Совместимый Markdown"))
        val media = service.createPost(user, CreatePostInput(blocks = listOf(ContentBlock(
            type = ContentBlockType.IMAGE,
            data = JsonObject(mapOf("blobId" to JsonPrimitive("44444444-4444-4444-4444-444444444444")))
        ))))
        assertTrue(legacy.blocks.isNotEmpty())
        assertEquals(ContentBlockType.IMAGE, media.blocks.single().type)

        assertFailsWith<IllegalArgumentException> {
            service.updatePost(actor(user), UpdatePostInput(
                id = legacy.id,
                text = ":::onix DIVIDER {}",
                blocks = listOf(ContentBlock(type = ContentBlockType.DIVIDER))
            ))
        }
    }

    @Test
    fun `creator blocks validate trusted embed hosts`() {
        val service = service()
        val accepted = service.createPost(user, CreatePostInput(
            text = "Видео",
            blocks = listOf(ContentBlock(type = ContentBlockType.TRUSTED_EMBED, data = JsonObject(mapOf("url" to JsonPrimitive("https://www.youtube.com/embed/demo")))))
        ))
        assertEquals(ContentBlockType.TRUSTED_EMBED, accepted.blocks.single().type)
        assertEquals("https://www.youtube.com/embed/demo", accepted.blocks.single().data["url"]?.jsonPrimitive?.content)

        assertFailsWith<IllegalArgumentException> {
            service.createPost(user, CreatePostInput(
                text = "Небезопасно",
                blocks = listOf(ContentBlock(type = ContentBlockType.TRUSTED_EMBED, data = JsonObject(mapOf("url" to JsonPrimitive("https://example.test/embed")))))
            ))
        }
    }

    @Test
    fun `poll keeps one active vote per owner and author can close it`() {
        val service = service()
        val pollId = "poll-1"
        val post = service.createPost(user, CreatePostInput(
            text = "Опрос",
            blocks = listOf(ContentBlock(id = pollId, type = ContentBlockType.POLL, data = JsonObject(mapOf(
                "question" to JsonPrimitive("Что выбрать?"),
                "options" to JsonArray(listOf(
                    JsonObject(mapOf("id" to JsonPrimitive("a"), "label" to JsonPrimitive("А"))),
                    JsonObject(mapOf("id" to JsonPrimitive("b"), "label" to JsonPrimitive("Б")))
                ))
            ))))
        ))

        service.votePoll(actor(viewer), PollVoteInput(post.id, pollId, "a"))
        val changed = service.votePoll(actor(viewer), PollVoteInput(post.id, pollId, "b"))
        assertEquals(0L, changed.counts["a"] ?: 0L)
        assertEquals(1L, changed.counts["b"])

        val closed = service.closePoll(actor(user), post.id, pollId)
        assertTrue(closed.closed)
        assertFailsWith<IllegalArgumentException> { service.votePoll(actor(viewer), PollVoteInput(post.id, pollId, "a")) }
    }

    @Test
    fun `thread groups one level replies and report hides only for reporter`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Комменты"))
        val root = service.createComment(viewer, CreateCommentInput(postId = post.id, text = "Корень"))
        val reply = service.createComment(user, CreateCommentInput(postId = post.id, parentId = root.id, text = "Ответ"))
        val thread = service.commentThread(
            CommentThreadInput(postId = post.id, sort = CommentSort.TOP),
            OwnerRef(OwnerType.USER, viewer.id),
            { owner -> AccountVisibility(ownerId = owner.toVisibilityOwner().ownerId, viewerId = viewer.id) }
        )
        assertEquals(listOf(reply.id), thread.comments.single().replies.map { it.id })

        service.reportComment(actor(viewer), ReportCommentInput(root.id, "Спам в обсуждении"))
        val reporterThread = service.commentThread(CommentThreadInput(post.id), OwnerRef(OwnerType.USER, viewer.id), { AccountVisibility(ownerId = user.id, viewerId = viewer.id) })
        val authorThread = service.commentThread(CommentThreadInput(post.id), OwnerRef(OwnerType.USER, user.id), { AccountVisibility(ownerId = user.id, viewerId = user.id) })
        assertTrue(reporterThread.comments.isEmpty())
        assertEquals(root.id, authorThread.comments.single().id)
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
                        "blobId" to JsonPrimitive("44444444-4444-4444-4444-444444444444"),
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
        val clientBlockId = "33333333-3333-3333-3333-333333333333"
        val comment = service.createComment(viewer, CreateCommentInput(
            postId = post.id,
            text = "Nice ![[media:file-1|brief.pdf]]",
            blocks = listOf(
                textBlock("Nice ![[media:file-1|brief.pdf]]"),
                ContentBlock(
                    id = clientBlockId,
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
        assertNotEquals(clientBlockId, comment.blocks.last().id)
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

        val rawUpload = enrichUploadBlocks(JsonArray(emptyList()), listOf(UploadedMedia(
            fileName = "poster.png",
            mimeType = "image/png",
            size = 64,
            blobId = "55555555-5555-5555-5555-555555555555"
        ))).single().jsonObject
        assertEquals(ContentBlockType.IMAGE.name, rawUpload["type"]?.jsonPrimitive?.content)
        assertEquals("55555555-5555-5555-5555-555555555555", rawUpload["data"]?.jsonObject?.get("blobId")?.jsonPrimitive?.content)
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
    fun `recommendation placements are stable per viewer and keep protected node space`() {
        val service = service()
        val posts = (1..6).map { index ->
            service.createPost(user, CreatePostInput(text = "Post $index", tags = listOf("plastic")))
        }

        val initial = allRecommendationItems(service, viewer.id, "first")
        val repeated = allRecommendationItems(service, viewer.id, "another-session")
        assertEquals(posts.map { it.id }.toSet(), initial.keys)
        assertEquals(
            initial.mapValues { it.value.placement },
            repeated.mapValues { it.value.placement }
        )

        val placements = initial.values.map { requireNotNull(it.placement) }
        placements.forEachIndexed { index, placement ->
            placements.drop(index + 1).forEach { other ->
                assertFalse(recommendationBoxesConflict(placement, other))
            }
        }

        val anotherViewer = allRecommendationItems(service, "33333333-3333-3333-3333-333333333333", "first")
        assertNotEquals(initial[posts.first().id]?.placement, anotherViewer[posts.first().id]?.placement)
    }

    @Test
    fun `recommendation chooses the strongest affinity tag and exposes constellation anchors`() {
        val service = service()
        val multiTag = service.createPost(user, CreatePostInput(text = "Both", tags = listOf("design", "kotlin")))
        val kotlinPost = service.createPost(user, CreatePostInput(text = "Kotlin", tags = listOf("kotlin")))
        service.createPost(user, CreatePostInput(text = "Design", tags = listOf("design")))
        service.likePost(viewer, kotlinPost.id)

        val items = allRecommendationItems(service, viewer.id, "affinity")
        assertEquals("kotlin", items.getValue(multiTag.id).placement?.constellationKey)

        val response = service.recommendationFeed(viewer.id, RecommendationFeedInput(chunkX = 0, chunkY = 0, limit = 50))
        assertEquals(setOf("design", "kotlin"), response.constellations.map { it.key }.toSet())
        val anchors = response.constellations
        val dx = anchors[0].anchorX - anchors[1].anchorX
        val dy = anchors[0].anchorY - anchors[1].anchorY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue(distance in 520.0..760.0)
    }

    @Test
    fun `recommendation materializes only visible active posts`() {
        val service = service()
        val visible = service.createPost(user, CreatePostInput(text = "Visible", tags = listOf("public")))
        val closeFriends = service.createPost(user, CreatePostInput(text = "Friends", tags = listOf("private"), visibility = Visibility.CLOSE_FRIENDS))
        val deleted = service.createPost(user, CreatePostInput(text = "Deleted", tags = listOf("public")))
        service.deletePost(actor(user), deleted.id)

        val items = allRecommendationItems(service, viewer.id, "visibility")
        assertTrue(visible.id in items)
        assertFalse(closeFriends.id in items)
        assertFalse(deleted.id in items)
    }

    @Test
    fun `recommendation feed boosts authors from social graph`() {
        val service = service()
        val followed = SessionUser(id = "33333333-3333-3333-3333-333333333333", username = "followed")
        val followedPost = service.createPost(followed, CreatePostInput(text = "Social", tags = listOf("general")))
        service.createPost(user, CreatePostInput(text = "Other", tags = listOf("general")))

        val feed = allRecommendationItems(
            service,
            viewer.id,
            "stable",
            socialGraph = AccountSocialGraph(followingIds = listOf(followed.id))
        )

        assertTrue(feed.getValue(followedPost.id).reasons.contains("following"))
    }

    @Test
    fun `recommendation feed reserves exploration slots`() {
        val service = service()
        repeat(8) { index ->
            service.createPost(user, CreatePostInput(text = "Post $index", tags = listOf("tag-${index % 5}")))
        }

        val feed = allRecommendationItems(service, viewer.id, "stable")
        val explorationCount = feed.values.count { "explore" in it.reasons }

        assertEquals(1, explorationCount)
    }

    @Test
    fun `record post view contributes tag affinity and seen penalty`() {
        val service = service()
        val viewed = service.createPost(user, CreatePostInput(text = "Viewed Kotlin", tags = listOf("kotlin")))
        val freshMatch = service.createPost(user, CreatePostInput(text = "Fresh Kotlin", tags = listOf("kotlin")))
        service.createPost(user, CreatePostInput(text = "General", tags = listOf("general")))

        service.recordPostView(viewer, viewed.id, 1200)
        val feed = allRecommendationItems(service, viewer.id, "stable")

        assertTrue(feed.getValue(freshMatch.id).reasons.contains("tag-affinity"))
        assertTrue(feed.getValue(viewed.id).reasons.contains("seen-before"))
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
    fun `v2 story stores stable media identity and releases source after commit`() {
        val canonical = PostAsset(
            id = "story-image",
            kind = PostAssetKind.IMAGE,
            sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "story-image",
            status = MediaAssetStatus.READY,
            variants = listOf(AssetVariant(url = "https://minio.invalid/temporary", name = "image-1440", mimeType = "image/webp", width = 960, height = 1280)),
            generation = 4,
            deliveryContract = "STABLE_V2"
        )
        var released = false
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, _ -> canonical },
            mediaAssetProcessor = object : MediaAssetProcessor {
                override fun request(owner: String, assetId: String, kind: PostAssetKind, idempotencyKey: String) =
                    RequestedMediaProcessing("unused", 4)
                override fun releaseSource(owner: String, assetId: String, generation: Long) {
                    released = owner == user.id && assetId == "story-image" && generation == 4L
                }
            },
            clock = fixedClock()
        )

        val story = service.createStory(actor(user), CreateStoryInput(blocks = listOf(ContentBlock(
            type = ContentBlockType.IMAGE,
            data = JsonObject(mapOf(
                "assetId" to JsonPrimitive("story-image"),
                "fileName" to JsonPrimitive("must-not-leak.jpg")
            ))
        ))))

        val data = story.blocks.single().data
        assertEquals("/content-media/assets/story-image/4/image-1440", data["url"]?.jsonPrimitive?.content)
        assertEquals(null, data["fileName"])
        assertTrue(released)
        assertEquals(
            user.id,
            service.resolveStableAssetOwner(
                assetId = "story-image",
                generation = 4,
                source = false,
                viewer = OwnerRef(OwnerType.USER, viewer.id),
                visibilityResolver = { AccountVisibility(ownerId = user.id, viewerId = viewer.id) }
            )
        )
    }

    @Test
    fun `active legacy story media remains visible until expiry`() {
        val service = service()
        val blobId = "44444444-4444-4444-4444-444444444444"
        service.createStory(user, CreateStoryInput(blocks = listOf(ContentBlock(
            type = ContentBlockType.IMAGE,
            data = JsonObject(mapOf("blobId" to JsonPrimitive(blobId)))
        ))))

        assertTrue(service.canViewMedia(blobId) { ownerKey ->
            AccountVisibility(ownerId = ownerKey.toVisibilityOwner().ownerId, viewerId = viewer.id)
        })
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

    @Test
    fun `search returns presentation metadata and facets for visible results`() {
        val repository = InMemoryContentRepository()
        val setup = ContentService(repository = repository, clock = fixedClock())
        val post = setup.createPost(
            user,
            CreatePostInput(
                text = "Searchable media post",
                tags = listOf("design", "media"),
                blocks = listOf(
                    ContentBlock(
                        type = ContentBlockType.IMAGE,
                        data = JsonObject(mapOf("previewUrl" to JsonPrimitive("https://cdn.test/post.jpg")))
                    )
                )
            )
        )
        val service = ContentService(
            repository = repository,
            searchIndex = FakeSearchIndex(mapOf("posts" to listOf(post.id))),
            clock = fixedClock()
        )

        val response = service.search(
            viewer = OwnerRef(OwnerType.USER, viewer.id),
            input = ContentSearchInput(query = "media", types = listOf("posts"), limit = 10),
            visibilityResolver = { ownerKey ->
                val owner = ownerKey.toVisibilityOwner()
                AccountVisibility(ownerId = owner.ownerId, ownerType = owner.ownerType, viewerId = viewer.id)
            },
            authorResolver = {
                AccountUser(
                    id = user.id,
                    username = user.username,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    avatarUrl = user.avatarUrl
                )
            }
        )

        val item = response.items.single()
        assertEquals("content", item.providerKey)
        assertEquals("Content", item.providerLabel)
        assertEquals("Post", item.typeLabel)
        assertEquals("https://cdn.test/post.jpg", item.thumbnailUrl)
        assertTrue(item.highlights.isNotEmpty())
        assertTrue(response.facets.any { it.group == "type" && it.value == "posts" && it.count == 1 && it.selected })
        assertTrue(response.facets.any { it.group == "tag" && it.value == "design" && it.count == 1 })
        assertEquals("ok", response.providerStatuses.single().status)
    }

    private fun allRecommendationItems(
        service: ContentService,
        viewerId: String,
        sessionSeed: String,
        socialGraph: AccountSocialGraph = AccountSocialGraph()
    ): Map<String, RecommendationFeedItem> {
        val items = linkedMapOf<String, RecommendationFeedItem>()
        for (chunkX in -6..6) {
            for (chunkY in -6..6) {
                service.recommendationFeed(
                    viewerId,
                    RecommendationFeedInput(chunkX = chunkX, chunkY = chunkY, sessionSeed = sessionSeed, limit = 50),
                    socialGraph = socialGraph
                ).items.forEach { item -> items.putIfAbsent(item.post.id, item) }
            }
        }
        return items
    }

    private fun recommendationBoxesConflict(left: RecommendationPlacement, right: RecommendationPlacement): Boolean {
        val gap = 24.0
        fun dimensions(placement: RecommendationPlacement) = when (placement.sizePreset) {
            AssetSizePreset.S -> 288.0 to 230.0
            AssetSizePreset.L -> 432.0 to 344.0
            else -> 348.0 to 278.0
        }
        val (leftWidth, leftHeight) = dimensions(left)
        val (rightWidth, rightHeight) = dimensions(right)
        val separated = left.worldX + leftWidth + gap <= right.worldX ||
            right.worldX + rightWidth + gap <= left.worldX ||
            left.worldY + leftHeight + gap <= right.worldY ||
            right.worldY + rightHeight + gap <= left.worldY
        return !separated
    }

    @Test
    fun `media projects require ready assets and hidden tags while empty media drafts remain valid`() {
        val service = service()
        val image = PostAsset(
            id = "asset-image",
            kind = PostAssetKind.IMAGE,
            sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "media-image",
            status = MediaAssetStatus.READY
        )

        assertFailsWith<IllegalArgumentException> {
            service.createPost(user, CreatePostInput(assets = listOf(image)))
        }
        val processingService = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, assetId ->
                image.copy(id = assetId, assetId = assetId, status = MediaAssetStatus.PROCESSING)
            },
            clock = fixedClock()
        )
        // The browser's claimed state is not trusted: MediaStore is the
        // authority that blocks publishing while its asset is processing.
        assertFailsWith<IllegalArgumentException> {
            processingService.createPost(user, CreatePostInput(assets = listOf(image), tags = listOf("design")))
        }

        val draft = service.savePostDraft(actor(user), SavePostDraftInput(assets = emptyList(), tags = emptyList()))
        assertEquals(ContentStatus.DRAFT, draft.status)
        assertFailsWith<IllegalArgumentException> { service.publishPostDraft(actor(user), draft.id) }

        val post = service.createPost(user, CreatePostInput(
            title = "ignored",
            text = "ignored",
            blocks = listOf(textBlock("ignored")),
            assets = listOf(image),
            tags = listOf("#design")
        ))
        assertEquals(3, post.contentVersion)
        assertEquals(listOf(image.id), post.assets.map { it.id })
        assertEquals("", post.text)
        assertEquals(emptyList(), post.blocks)
        assertEquals(listOf("design"), service.post(post.id, OwnerRef(OwnerType.USER, user.id))?.tags)
        assertEquals(emptyList(), service.post(post.id, OwnerRef(OwnerType.USER, viewer.id))?.tags)
        assertEquals(emptyList(), allRecommendationItems(service, viewer.id, "media-v2").getValue(post.id).post.tags)
    }

    @Test
    fun `media projects keep only MediaStore delivery data`() {
        val canonical = PostAsset(
            id = "media-image",
            kind = PostAssetKind.IMAGE,
            sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "media-image",
            status = MediaAssetStatus.READY,
            variants = listOf(AssetVariant("https://media.onix.test/delivery.webp"))
        )
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, assetId -> canonical.copy(id = assetId, assetId = assetId) },
            clock = fixedClock()
        )
        val post = service.createPost(user, CreatePostInput(
            assets = listOf(canonical.copy(
                id = "project-item",
                url = "https://other.example.test/forged.jpg",
                variants = listOf(AssetVariant("https://other.example.test/forged.webp"))
            )),
            tags = listOf("design")
        ))
        val saved = post.assets.single()
        assertEquals("project-item", saved.id)
        assertEquals(null, saved.url)
        assertEquals(listOf("https://media.onix.test/delivery.webp"), saved.variants.map { it.url })
    }

    @Test
    fun `editor save verifies all media with one batch lookup`() {
        var singleLookups = 0
        var batchLookups = 0
        val canonical = { id: String -> PostAsset(
            id = id, assetId = id, kind = PostAssetKind.IMAGE,
            sourceKind = PostAssetSourceKind.UPLOAD, status = MediaAssetStatus.AVAILABLE,
            width = 800, height = 600
        ) }
        val verifier = object : UploadedAssetVerifier {
            override fun asset(owner: String, assetId: String): PostAsset? {
                singleLookups += 1
                return canonical(assetId)
            }

            override fun assets(owner: String, assetIds: List<String>): Map<String, PostAsset> {
                batchLookups += 1
                return assetIds.associateWith(canonical)
            }
        }
        val service = ContentService(repository = InMemoryContentRepository(), uploadedAssetVerifier = verifier, clock = fixedClock())
        val draft = service.createPostDraft(actor(user))
        val inputAssets = listOf(
            canonical("media-a").copy(id = "item-a", layout = PostAssetLayout("media-a", -500, 0)),
            canonical("media-b").copy(id = "item-b", layout = PostAssetLayout("media-b", 500, 0))
        )

        val saved = service.savePostEditorDocument(actor(user), SavePostEditorDocumentInput(
            revisionId = draft.revisionId, editVersion = draft.editVersion,
            assets = inputAssets, tags = listOf("design")
        ))

        assertEquals(2, saved.assets.size)
        assertEquals(1, batchLookups)
        assertEquals(0, singleLookups)
    }

    @Test
    fun `media draft publication activates asynchronously after every asset is ready`() {
        var status = MediaAssetStatus.PROCESSING
        val media = PostAsset(
            id = "media-image", kind = PostAssetKind.IMAGE,
            sourceKind = PostAssetSourceKind.UPLOAD, assetId = "media-image", status = status
        )
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, assetId -> media.copy(id = assetId, assetId = assetId, status = status) },
            clock = fixedClock()
        )
        val draft = service.savePostDraft(actor(user), SavePostDraftInput(assets = listOf(media), tags = listOf("design")))
        val queued = service.requestPostPublication(actor(user), RequestPostPublicationInput(draft.id, "request-key-123"))
        assertEquals(PostPublicationState.PROCESSING_MEDIA, queued.state)
        assertEquals(null, service.post(draft.id, OwnerRef(OwnerType.USER, viewer.id)))

        status = MediaAssetStatus.READY
        service.reconcilePendingPublications()

        assertEquals(PostPublicationState.ACTIVE, service.postPublication(actor(user), draft.id).state)
        assertEquals(draft.id, service.post(draft.id, OwnerRef(OwnerType.USER, viewer.id))?.id)
    }

    @Test
    fun `saving a media draft never requests conversion`() {
        var processingRequests = 0
        val media = PostAsset(
            id = "media-image", kind = PostAssetKind.IMAGE, sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "media-image", status = MediaAssetStatus.AVAILABLE, deliveryContract = "STABLE_V2"
        )
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, _ -> media },
            mediaAssetProcessor = object : MediaAssetProcessor {
                override fun request(owner: String, assetId: String, kind: PostAssetKind, idempotencyKey: String): RequestedMediaProcessing {
                    processingRequests++
                    return RequestedMediaProcessing("run-1", 1)
                }
            },
            clock = fixedClock()
        )
        val draft = service.savePostDraft(actor(user), SavePostDraftInput(assets = listOf(media), tags = listOf("design")))
        assertEquals(0, processingRequests)
        service.requestPostPublication(actor(user), RequestPostPublicationInput(draft.id, "request-key-456"))
        assertEquals(1, processingRequests)
    }

    @Test
    fun `v4 revision publishes atomically and keeps the active project stable while editing`() {
        val canonical = PostAsset(
            id = "media-image", kind = PostAssetKind.IMAGE, sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "media-image", status = MediaAssetStatus.READY,
            sourceStatus = MediaSourceStatus.AVAILABLE,
            processingStatus = MediaProcessingStatus.READY,
            deliveryStatus = MediaDeliveryStatus.READY,
            width = 1200, height = 800, generation = 1
        )
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, _ -> canonical },
            clock = fixedClock()
        )
        val draft = service.createPostDraft(actor(user))
        val saved = service.savePostEditorDocument(actor(user), SavePostEditorDocumentInput(
            revisionId = draft.revisionId,
            editVersion = draft.editVersion,
            assets = listOf(canonical.copy(id = "project-item")),
            tags = listOf("design")
        ))
        val publication = service.requestPostRevisionPublication(actor(user), saved.revisionId, "revision-key-123")
        assertEquals(PostPublicationState.ACTIVE, publication.state)
        val active = requireNotNull(service.post(saved.postId, OwnerRef(OwnerType.USER, viewer.id)))
        assertEquals(listOf("project-item"), active.assets.map { it.id })

        val edit = service.beginPostEdit(actor(user), active.id)
        service.savePostEditorDocument(actor(user), SavePostEditorDocumentInput(
            revisionId = edit.revisionId,
            editVersion = edit.editVersion,
            assets = edit.assets,
            tags = listOf("new-tag"),
            allowComments = false
        ))
        val stillActive = requireNotNull(service.post(active.id, OwnerRef(OwnerType.USER, user.id)))
        assertEquals(listOf("design"), stillActive.tags)
        assertTrue(stillActive.allowComments)
    }

    @Test
    fun `cancelling publication unfreezes its revision for editing`() {
        val canonical = PostAsset(
            id = "media-image", kind = PostAssetKind.IMAGE, sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "media-image", status = MediaAssetStatus.AVAILABLE,
            sourceStatus = MediaSourceStatus.AVAILABLE,
            processingStatus = MediaProcessingStatus.NONE,
            deliveryStatus = MediaDeliveryStatus.NONE,
            width = 1200, height = 800
        )
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, _ -> canonical },
            clock = fixedClock()
        )
        val draft = service.createPostDraft(actor(user))
        val saved = service.savePostEditorDocument(actor(user), SavePostEditorDocumentInput(
            revisionId = draft.revisionId,
            editVersion = draft.editVersion,
            assets = listOf(canonical.copy(id = "project-item")),
            tags = listOf("design")
        ))

        val requested = service.requestPostRevisionPublication(actor(user), saved.revisionId, "revision-key-cancel")
        assertEquals(PostPublicationState.PROCESSING_MEDIA, requested.state)
        assertFailsWith<IllegalArgumentException> {
            service.savePostEditorDocument(actor(user), SavePostEditorDocumentInput(
                revisionId = saved.revisionId,
                editVersion = saved.editVersion,
                assets = saved.assets,
                tags = saved.tags
            ))
        }

        val cancelled = service.cancelPostPublication(actor(user), saved.postId)
        assertEquals(PostPublicationState.DRAFT, cancelled.state)
        val editable = service.savePostEditorDocument(actor(user), SavePostEditorDocumentInput(
            revisionId = saved.revisionId,
            editVersion = saved.editVersion,
            assets = saved.assets,
            tags = listOf("design", "editable-again")
        ))
        assertEquals(PostRevisionState.DRAFT, editable.state)
        assertEquals(listOf("design", "editable-again"), editable.tags)
    }

    @Test
    fun `stable media responses never expose a presigned delivery url`() {
        val canonical = PostAsset(
            id = "media-image", kind = PostAssetKind.IMAGE, sourceKind = PostAssetSourceKind.UPLOAD,
            assetId = "media-image", status = MediaAssetStatus.READY,
            variants = listOf(AssetVariant(url = "https://minio.invalid/expired-signature", name = "image-960", mimeType = "image/webp")),
            generation = 3, deliveryContract = "STABLE_V2"
        )
        val service = ContentService(
            repository = InMemoryContentRepository(),
            uploadedAssetVerifier = UploadedAssetVerifier { _, _ -> canonical },
            clock = fixedClock()
        )
        val created = service.createPost(user, CreatePostInput(assets = listOf(canonical), tags = listOf("design")))
        val response = requireNotNull(service.post(created.id, OwnerRef(OwnerType.USER, viewer.id)))
        assertEquals("/content-media/assets/media-image/3/image-960", response.assets.single().variants.single().url)
    }

    @Test
    fun `threads collapse replies to one level paginate children tombstone and replace pin atomically`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Thread"))
        val root = service.createComment(user, CreateCommentInput(post.id, "root"))
        val sibling = service.createComment(viewer, CreateCommentInput(post.id, "sibling"))
        val child = service.createComment(viewer, CreateCommentInput(post.id, "child", parentId = root.id))
        val nested = service.createComment(user, CreateCommentInput(post.id, "nested", parentId = child.id))

        assertEquals(root.id, nested.parentId)
        assertEquals(child.id, nested.replyToId)
        val roots = service.commentThread(CommentThreadInput(post.id, limit = 1), null, { AccountVisibility(ownerId = user.id, viewerId = viewer.id) })
        assertEquals(2, roots.totalCount)
        assertTrue(roots.nextCursor != null)
        val secondPage = service.commentThread(
            CommentThreadInput(post.id, limit = 1, cursor = roots.nextCursor),
            null,
            { AccountVisibility(ownerId = user.id, viewerId = viewer.id) }
        )
        assertEquals(1, secondPage.comments.size)
        val children = service.commentThread(
            CommentThreadInput(post.id, parentId = root.id),
            null,
            { AccountVisibility(ownerId = user.id, viewerId = viewer.id) }
        )
        assertEquals(setOf(child.id, nested.id), children.comments.map { it.id }.toSet())

        service.pinComment(actor(user), root.id, true)
        service.pinComment(actor(user), sibling.id, true)
        val pinnedRoots = service.commentThread(CommentThreadInput(post.id), null, { AccountVisibility(ownerId = user.id, viewerId = viewer.id) })
        assertEquals(sibling.id, pinnedRoots.comments.first().id)
        assertEquals(sibling.id, service.post(post.id, OwnerRef(OwnerType.USER, user.id))?.pinnedCommentId)

        service.deleteComment(actor(viewer), child.id)
        val repliesAfterDelete = service.commentThread(
            CommentThreadInput(post.id, parentId = root.id),
            null,
            { AccountVisibility(ownerId = user.id, viewerId = viewer.id) }
        ).comments
        val deletedChild = repliesAfterDelete.first { it.id == child.id }
        assertEquals(ContentStatus.DELETED, deletedChild.status)
        assertEquals("", deletedChild.text)
        assertEquals(setOf(child.id, nested.id), repliesAfterDelete.map { it.id }.toSet())
    }

    @Test
    fun `post author hiding a comment removes it from the thread without deleting its branch`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Thread"))
        val root = service.createComment(viewer, CreateCommentInput(post.id, "keep descendants"))
        val child = service.createComment(user, CreateCommentInput(post.id, "child", parentId = root.id))

        service.hideComment(actor(user), root.id)

        val visibleRoots = service.commentThread(
            CommentThreadInput(post.id),
            OwnerRef(OwnerType.USER, viewer.id),
            { AccountVisibility(ownerId = user.id, viewerId = viewer.id) }
        ).comments
        assertTrue(visibleRoots.isEmpty())

        val descendants = service.commentThread(
            CommentThreadInput(post.id, parentId = root.id),
            OwnerRef(OwnerType.USER, viewer.id),
            { AccountVisibility(ownerId = user.id, viewerId = viewer.id) }
        )
        assertEquals(listOf(child.id), descendants.comments.map { it.id })
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

    override fun postDelete(postId: String) {
        events.add("post-delete:$postId")
    }

    override fun commentUpsert(comment: Comment) {
        events.add("comment-upsert:${comment.id}")
    }

    override fun commentDelete(commentId: String) {
        events.add("comment-delete:$commentId")
    }

    override fun collectionUpsert(collection: SavedCollection) {
        events.add("collection-upsert:${collection.id}")
    }

    override fun collectionDelete(collectionId: String) {
        events.add("collection-delete:$collectionId")
    }
}
