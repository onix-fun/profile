package com.onix.profile.service

import com.onix.profile.domain.ProfileCollectionItemRef
import com.onix.profile.domain.ProfileContentCollection
import java.time.Instant

class InMemoryProfileRepository : ProfileRepository {
    private val collections = linkedMapOf<String, ProfileContentCollection>()
    private val items = linkedMapOf<String, LinkedHashMap<String, Pair<ProfileCollectionItemRef, Instant>>>()
    private val usage = linkedSetOf<String>()
    private val providers = listOf(
        StoredProvider("profile", "Profile", null, null, true),
        StoredProvider("content", "Content", "PROFILE_PROVIDER_CONTENT_GRPC_URL", "PROFILE_CONTENT_FRONTEND_URL", true)
    )

    override fun findPublicProfile(ownerType: String, ownerId: String) = null

    override fun updatePublicProfile(ownerType: String, ownerId: String, username: String, displayName: String, bio: String, socialLinksJson: String, expectedRevision: Long) =
        error("Public profile persistence is not used by this fake")

    override fun setAvatar(ownerType: String, ownerId: String, avatarAssetId: String, expectedRevision: Long) =
        error("Public profile persistence is not used by this fake")
    private val capabilities = listOf(
        StoredProviderCapability("content", "owner_contribution", "owner_section", listOf("post"), """{"buttonKey":"posts"}""", true),
        StoredProviderCapability("content", "story_archive", "redirect", listOf("story"), """{"targetPathTemplate":"/stories/archive?ownerType={ownerType}&ownerId={ownerId}"}""", true),
        StoredProviderCapability("content", "content_search", "search", listOf("post", "comment"), "{}", true),
        StoredProviderCapability("content", "content_suggest", "suggest", listOf("post", "comment", "tag"), "{}", true),
        StoredProviderCapability("content", "post_like", "action", listOf("post"), """{"action":"likePost"}""", true),
        StoredProviderCapability("content", "post_unlike", "action", listOf("post"), """{"action":"unlikePost"}""", true),
        StoredProviderCapability("content", "recommendations", "action", listOf("post"), """{"action":"recommendationFeed"}""", true)
    )
    private val navButtons = listOf(
        StoredNavButton("collections", "profile", "collections", "collections", "Collections", "pi pi-bookmark", "#111827", "canvas", "collections", null, null, null, "collections", 10, false),
        StoredNavButton("posts", "content", "posts", "owner_contribution", "Posts", "pi pi-th-large", "#111827", "canvas", "section", null, null, null, "owner_contribution", 20, true),
        StoredNavButton("story_archive", "content", "story_archive", "story_archive", "Archive", "pi pi-history", "#22c55e", "redirect", "redirect", null, "content", "/stories/archive?ownerType={ownerType}&ownerId={ownerId}", "story_archive", 30, true)
    )

    override fun saveCollection(collection: ProfileContentCollection): ProfileContentCollection {
        collections[collection.id] = collection
        return collection
    }

    override fun updateCollection(collection: ProfileContentCollection): ProfileContentCollection {
        collections[collection.id] = collection
        return collection
    }

    override fun deleteCollection(collectionId: String) {
        collections.remove(collectionId)
        items.remove(collectionId)
    }

    override fun findCollection(collectionId: String): ProfileContentCollection? =
        collections[collectionId]?.withCounts()

    override fun listCollectionsByOwner(ownerType: String, ownerId: String, limit: Int): List<ProfileContentCollection> =
        collections.values
            .filter { it.ownerType == ownerType && it.ownerId == ownerId }
            .sortedByDescending { it.updatedAt.orEmpty() }
            .take(limit)
            .map { it.withCounts() }

    override fun listCollectionItems(collectionId: String, limit: Int): List<ProfileCollectionItemRef> =
        items[collectionId].orEmpty().values
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    override fun listItemCollectionIds(ownerType: String, ownerId: String, ref: ProfileCollectionItemRef): List<String> =
        collections.values
            .filter { it.ownerType == ownerType && it.ownerId == ownerId }
            .filter { items[it.id]?.containsKey(ref.key()) == true }
            .sortedByDescending { it.updatedAt.orEmpty() }
            .map { it.id }

    override fun addItemToCollection(collectionId: String, ref: ProfileCollectionItemRef, addedAt: Instant) {
        items.getOrPut(collectionId) { linkedMapOf() }[ref.key()] = ref to addedAt
        collections[collectionId]?.let { collections[collectionId] = it.copy(updatedAt = addedAt.toString()) }
    }

    override fun removeItemFromCollection(collectionId: String, ref: ProfileCollectionItemRef) {
        items[collectionId]?.remove(ref.key())
        val now = Instant.now()
        collections[collectionId]?.let { collections[collectionId] = it.copy(updatedAt = now.toString()) }
    }

    override fun listProviders(): List<StoredProvider> =
        providers.filter { it.enabled }

    override fun listProviderCapabilities(): List<StoredProviderCapability> =
        capabilities.filter { it.enabled && providers.any { provider -> provider.serviceKey == it.serviceKey && provider.enabled } }

    override fun listNavButtons(ownerType: String, ownerId: String): List<StoredNavButton> =
        navButtons
            .filter { button -> button.serviceKey == "profile" || providers.any { it.serviceKey == button.serviceKey && it.enabled } }
            .filter { button -> button.serviceKey == "profile" || capabilities.any { it.serviceKey == button.serviceKey && it.capabilityKey == button.capabilityKey && it.enabled } }
            .filter { !it.requiresUsage || usage.contains("$ownerType:$ownerId:${it.serviceKey}:${it.featureKey}") }
            .sortedBy { it.sortOrder }

    override fun recordUsage(ownerType: String, ownerId: String, serviceKey: String, featureKey: String, usedAt: Instant) {
        usage.add("$ownerType:$ownerId:$serviceKey:$featureKey")
    }

    private fun ProfileContentCollection.withCounts(): ProfileContentCollection =
        copy(itemCount = items[id]?.size ?: 0)

    private fun ProfileCollectionItemRef.key(): String = "$serviceKey:$itemType:$itemId"
}
