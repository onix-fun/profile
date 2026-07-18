package com.onix.profile.service

import com.onix.profile.domain.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class StoredNavButton(
    val key: String,
    val serviceKey: String,
    val featureKey: String,
    val capabilityKey: String?,
    val label: String,
    val icon: String,
    val color: String,
    val mode: String,
    val kind: String,
    val frontendRouteTemplate: String?,
    val targetService: String?,
    val targetPathTemplate: String?,
    val backendOperation: String?,
    val sortOrder: Int,
    val requiresUsage: Boolean
)

data class StoredProvider(
    val serviceKey: String,
    val displayName: String,
    val grpcTargetEnv: String?,
    val frontendBaseUrlEnv: String?,
    val enabled: Boolean,
    val timeoutMillis: Long = 750
)

data class StoredProviderCapability(
    val serviceKey: String,
    val capabilityKey: String,
    val operation: String,
    val itemTypes: List<String>,
    val configJson: String,
    val enabled: Boolean
)

fun StoredProviderCapability.isPostOwnerSection(): Boolean =
    enabled && operation == "owner_section" && "post" in itemTypes

interface ProfileRepository {
    fun findPublicProfile(ownerType: String, ownerId: String): StoredPublicProfile?
    fun updatePublicProfile(ownerType: String, ownerId: String, username: String, displayName: String, bio: String, socialLinksJson: String, expectedRevision: Long): StoredPublicProfile
    fun setAvatar(ownerType: String, ownerId: String, avatarAssetId: String, expectedRevision: Long): StoredPublicProfile
    fun saveCollection(collection: ProfileContentCollection): ProfileContentCollection
    fun updateCollection(collection: ProfileContentCollection): ProfileContentCollection
    fun deleteCollection(collectionId: String)
    fun findCollection(collectionId: String): ProfileContentCollection?
    fun listCollectionsByOwner(ownerType: String, ownerId: String, limit: Int): List<ProfileContentCollection>
    fun listCollectionItems(collectionId: String, limit: Int): List<ProfileCollectionItemRef>
    fun listItemCollectionIds(ownerType: String, ownerId: String, ref: ProfileCollectionItemRef): List<String>
    fun addItemToCollection(collectionId: String, ref: ProfileCollectionItemRef, addedAt: Instant)
    fun removeItemFromCollection(collectionId: String, ref: ProfileCollectionItemRef)
    fun listProviders(): List<StoredProvider>
    fun listProviderCapabilities(): List<StoredProviderCapability>
    fun listNavButtons(ownerType: String, ownerId: String): List<StoredNavButton>
    fun recordUsage(ownerType: String, ownerId: String, serviceKey: String, featureKey: String, usedAt: Instant)
}

data class StoredPublicProfile(
    val ownerType: String,
    val ownerId: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarAssetId: String?,
    val socialLinksJson: String,
    val revision: Long,
    val updatedAt: Instant
)

fun ProfileContentCollection.ownerMatches(owner: AccountUser): Boolean =
    ownerType == (owner.ownerType.ifBlank { "USER" }) && ownerId == owner.id

fun ProfileContentCollection.canView(viewer: AccountUser): Boolean =
    ownerMatches(viewer) || visibility == "PUBLIC"

fun normalizeCollectionVisibility(value: String?): String =
    when (value?.trim()?.uppercase()) {
        "PUBLIC" -> "PUBLIC"
        else -> "PRIVATE"
    }

fun normalizeCollectionTitle(title: String): String {
    val normalized = title.trim()
    require(normalized.isNotBlank()) { "Collection title is required" }
    return normalized.take(80)
}

fun normalizeCollectionDescription(description: String?): String? =
    description?.trim()?.takeIf(String::isNotBlank)?.take(280)

fun emptyPreview(): List<ContentBlock> = emptyList()

fun newCollection(
    owner: AccountUser,
    input: CreateCollectionInput,
    now: Instant,
    id: String
): ProfileContentCollection =
    ProfileContentCollection(
        id = id,
        ownerType = owner.ownerType.ifBlank { "USER" },
        ownerId = owner.id,
        title = normalizeCollectionTitle(input.title),
        description = normalizeCollectionDescription(input.description),
        cover = input.cover,
        visibility = normalizeCollectionVisibility(input.visibility),
        itemCount = 0,
        previewBlocks = emptyPreview(),
        createdAt = now.toString(),
        updatedAt = now.toString()
    )

fun updatedCollection(
    current: ProfileContentCollection,
    input: UpdateCollectionInput,
    now: Instant
): ProfileContentCollection =
    current.copy(
        title = input.title?.let(::normalizeCollectionTitle) ?: current.title,
        description = if (input.description != null) normalizeCollectionDescription(input.description) else current.description,
        cover = input.cover ?: current.cover,
        visibility = input.visibility?.let(::normalizeCollectionVisibility) ?: current.visibility,
        updatedAt = now.toString()
    )
