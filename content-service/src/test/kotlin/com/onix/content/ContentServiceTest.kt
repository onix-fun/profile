package com.onix.content

import com.onix.content.domain.*
import com.onix.content.service.ContentService
import com.onix.content.service.InMemoryContentRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentServiceTest {
    private val user = SessionUser(id = "11111111-1111-1111-1111-111111111111", username = "alice")
    private val viewer = SessionUser(id = "22222222-2222-2222-2222-222222222222", username = "viewer")

    @Test
    fun `comments allow one reply level only`() {
        val service = service()
        val post = service.createPost(user, CreatePostInput(text = "Hello", tags = listOf("kotlin")))
        val root = service.createComment(viewer, CreateCommentInput(postId = post.id, text = "Root"))
        val reply = service.createComment(user, CreateCommentInput(postId = post.id, parentId = root.id, text = "Reply"))

        assertEquals(root.id, reply.parentId)
        assertFailsWith<IllegalArgumentException> {
            service.createComment(viewer, CreateCommentInput(postId = post.id, parentId = reply.id, text = "Nested"))
        }
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

    private fun service() = ContentService(
        repository = InMemoryContentRepository(),
        clock = Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC)
    )
}
