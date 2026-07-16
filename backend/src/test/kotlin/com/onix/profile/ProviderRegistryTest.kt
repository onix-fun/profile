package com.onix.profile

import com.onix.profile.service.InMemoryProfileRepository
import com.onix.profile.service.ProfileNavigationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderRegistryTest {
    @Test
    fun `profile registry exposes content capabilities from seed`() {
        val repository = InMemoryProfileRepository()

        val providers = repository.listProviders().map { it.serviceKey }
        val capabilities = repository.listProviderCapabilities().associateBy { it.capabilityKey }

        assertTrue("content" in providers)
        assertEquals("PROFILE_PROVIDER_CONTENT_GRPC_URL", repository.listProviders().first { it.serviceKey == "content" }.grpcTargetEnv)
        assertEquals("owner_section", capabilities["posts"]?.operation)
        assertEquals("search", capabilities["content_search"]?.operation)
        assertEquals("action", capabilities["post_like"]?.operation)
    }

    @Test
    fun `navigation uses usage gated provider capabilities`() {
        val repository = InMemoryProfileRepository()
        val navigation = ProfileNavigationService(repository, mapOf("PROFILE_CONTENT_FRONTEND_URL" to "http://content.test"))
        val ownerId = "11111111-1111-1111-1111-111111111111"

        assertEquals(listOf("collections"), navigation.navigation("USER", ownerId, "alice").map { it.key })

        navigation.recordUsage("USER", ownerId, "content", "posts")
        navigation.recordUsage("USER", ownerId, "content", "story_archive")

        val buttons = navigation.navigation("USER", ownerId, "alice").associateBy { it.key }
        assertEquals("canvas", buttons["posts"]?.mode)
        assertEquals("redirect", buttons["story_archive"]?.mode)
        assertEquals("content", buttons["story_archive"]?.targetService)
        assertEquals("/stories/archive?ownerType=USER&ownerId=$ownerId", buttons["story_archive"]?.targetPath)
        assertEquals("http://content.test/stories/archive?ownerType=USER&ownerId=$ownerId", buttons["story_archive"]?.targetUrl)
        assertEquals(listOf("collections"), navigation.navigation("USER", ownerId, "alice", setOf("media")).map { it.key })
        assertEquals(listOf("collections", "posts", "story_archive"), navigation.navigation("USER", ownerId, "alice", setOf("content")).map { it.key })
    }
}
