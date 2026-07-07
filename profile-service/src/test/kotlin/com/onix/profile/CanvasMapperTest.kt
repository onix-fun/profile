package com.onix.profile

import com.onix.profile.api.CanvasMapper
import com.onix.profile.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasMapperTest {
    @Test
    fun `omits empty privacy-filtered fields from canvas`() {
        val response = CanvasMapper.toCanvas(
            profile = profile(bio = null, socialLinks = emptyList(), birthday = null),
            currentUser = viewer(),
        )

        val ids = response.nodes.map { it.id }
        assertFalse("bio" in ids)
        assertFalse("socialLinks" in ids)
        assertFalse("birthday" in ids)
        assertTrue("avatar" in ids)
        assertTrue("followAction" in ids)
    }

    @Test
    fun `owner does not receive follow action`() {
        val owner = viewer(id = "11111111-1111-1111-1111-111111111111", username = "alice")
        val response = CanvasMapper.toCanvas(
            profile = profile(id = owner.id, username = owner.username),
            currentUser = owner,
        )

        assertTrue(response.permissions.owner)
        assertFalse(response.permissions.canFollow)
        assertFalse(response.nodes.any { it.id == "followAction" })
    }

    @Test
    fun `uses automatic deterministic positions`() {
        val first = CanvasMapper.toCanvas(profile(), viewer()).nodes.associate { it.id to it.position }
        val second = CanvasMapper.toCanvas(profile(), viewer()).nodes.associate { it.id to it.position }

        assertEquals(first, second)
        assertEquals(CanvasPosition(0.0, 0.0), first["avatar"])
    }

    @Test
    fun `adds content summary nodes when content is visible`() {
        val response = CanvasMapper.toCanvas(
            profile = profile(),
            currentUser = viewer(),
            content = ProfileContentSummary(
                posts = listOf(ProfileContentPost(id = "post-1", text = "Hello")),
                stories = listOf(ProfileContentStory(id = "story-1", visibility = "PUBLIC")),
                comments = listOf(ProfileContentComment(id = "comment-1", postId = "post-1", text = "Nice"))
            )
        )

        val ids = response.nodes.map { it.id }
        assertTrue("social" in ids)
        assertFalse("followers" in ids)
        assertFalse("following" in ids)
        assertFalse("posts" in ids)
        assertFalse("stories" in ids)
        assertFalse("comments" in ids)
    }

    @Test
    fun `private inaccessible profile returns lock state without canvas nodes`() {
        val response = CanvasMapper.toCanvas(
            profile = profile(isPrivate = true, relationship = Relationship()),
            currentUser = viewer(),
        )

        assertEquals("PRIVATE", response.status)
        assertTrue(response.nodes.isEmpty())
        assertTrue(response.content.posts.isEmpty())
        assertTrue(response.permissions.canFollow)
    }

    private fun viewer(
        id: String = "22222222-2222-2222-2222-222222222222",
        username: String = "viewer"
    ) = SessionUser(id = id, username = username)

    private fun profile(
        id: String = "11111111-1111-1111-1111-111111111111",
        username: String = "alice",
        bio: String? = "Builder",
        socialLinks: List<SocialLink> = listOf(SocialLink("Site", "https://example.com")),
        birthday: BirthdayParts? = BirthdayParts(day = 4, month = 7),
        isPrivate: Boolean = false,
        relationship: Relationship = Relationship()
    ) = AccountProfile(
        id = id,
        username = username,
        firstName = "Alice",
        lastName = "Onix",
        bio = bio,
        birthday = birthday,
        socialLinks = socialLinks,
        followersCount = 10,
        followingCount = 5,
        isPrivate = isPrivate,
        relationship = relationship
    )
}
