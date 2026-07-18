package com.onix.profile.service

import com.onix.profile.domain.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

class CollectionService(
    private val repository: ProfileRepository,
    private val providers: ProviderGateway,
    private val clock: Clock = Clock.systemUTC()
) {
    fun createCollection(owner: AccountUser, input: CreateCollectionInput): ProfileContentCollection =
        repository.saveCollection(newCollection(owner, input, Instant.now(clock), UuidV7.generate().toString()))

    fun updateCollection(owner: AccountUser, collectionId: String, input: UpdateCollectionInput): ProfileContentCollection {
        val current = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        require(current.ownerMatches(owner)) { "Collection not found" }
        return repository.updateCollection(updatedCollection(current, input, Instant.now(clock)))
    }

    fun deleteCollection(owner: AccountUser, collectionId: String) {
        val current = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        require(current.ownerMatches(owner)) { "Collection not found" }
        repository.deleteCollection(collectionId)
    }

    fun collections(ownerType: String, ownerId: String, viewer: AccountUser, limit: Int): List<ProfileContentCollection> =
        repository.listCollectionsByOwner(ownerType, ownerId, limit.coerceIn(1, 100))
            .filter { it.canView(viewer) }

    fun collection(collectionId: String, viewer: AccountUser, accessToken: String, limit: Int): ProfileCollectionDetail {
        val collection = repository.findCollection(collectionId) ?: throw IllegalArgumentException("Collection not found")
        require(collection.canView(viewer)) { "Collection not found" }
        val refs = repository.listCollectionItems(collectionId, limit.coerceIn(1, 500))
        val resolved = providers.resolveItems(refs, viewer, accessToken)
        val posts = resolved.items.mapNotNull { it.post }
        val visibleCollection = collection.copy(
            itemCount = resolved.items.size,
            previewBlocks = resolved.items.flatMap { it.previewBlocks }.take(3)
        )
        return ProfileCollectionDetail(
            collection = visibleCollection,
            items = resolved.items,
            posts = posts,
            partialErrors = resolved.partialErrors
        )
    }

    fun itemCollections(owner: AccountUser, ref: ProfileCollectionItemRef): ItemCollectionsState =
        ItemCollectionsState(
            ref = ref,
            collectionIds = repository.listItemCollectionIds(owner.ownerType.ifBlank { "USER" }, owner.id, ref)
        )

    fun setItemCollections(owner: AccountUser, ref: ProfileCollectionItemRef, collectionIds: List<String>): ItemCollectionsState {
        validateRef(ref)
        val desired = collectionIds.distinct()
        val desiredCollections = desired.map { id ->
            (repository.findCollection(id) ?: throw IllegalArgumentException("Collection not found")).also {
                require(it.ownerMatches(owner)) { "Collection not found" }
            }
        }
        val current = repository.listItemCollectionIds(owner.ownerType.ifBlank { "USER" }, owner.id, ref).toSet()
        val desiredSet = desiredCollections.map { it.id }.toSet()
        val now = Instant.now(clock)
        (desiredSet - current).forEach { repository.addItemToCollection(it, ref, now) }
        (current - desiredSet).forEach { repository.removeItemFromCollection(it, ref) }
        return itemCollections(owner, ref)
    }

    private fun validateRef(ref: ProfileCollectionItemRef) {
        require(ref.serviceKey.isNotBlank()) { "serviceKey is required" }
        require(ref.itemType.isNotBlank()) { "itemType is required" }
        require(runCatching { UUID.fromString(ref.itemId) }.isSuccess) { "itemId must be a UUID" }
    }
}

class ProfileNavigationService(private val repository: ProfileRepository, private val env: Map<String, String> = System.getenv()) {
    fun navigation(ownerType: String, ownerId: String, ownerSlug: String? = null, serviceFilter: Set<String> = emptySet()): List<ProfileNavButton> {
        val prefix = if (ownerType == "ORGANIZATION") "o" else "u"
        val slug = ownerSlug ?: ownerId
        val providers = repository.listProviders().associateBy { it.serviceKey }
        return repository.listNavButtons(ownerType, ownerId)
            .filter { button -> serviceFilter.isEmpty() || button.serviceKey == "profile" || button.serviceKey in serviceFilter }
            .map { button ->
            val route = button.frontendRouteTemplate
                ?.replace("{ownerPrefix}", prefix)
                ?.replace("{ownerSlug}", slug)
                ?.replace("{ownerType}", ownerType)
                ?.replace("{ownerId}", ownerId)
            val targetPath = button.targetPathTemplate
                ?.replace("{ownerPrefix}", prefix)
                ?.replace("{ownerSlug}", slug)
                ?.replace("{ownerType}", ownerType)
                ?.replace("{ownerId}", ownerId)
            ProfileNavButton(
                key = button.key,
                serviceKey = button.serviceKey,
                featureKey = button.featureKey,
                label = button.label,
                icon = button.icon,
                color = button.color,
                mode = button.mode,
                kind = button.kind,
                route = route,
                targetService = button.targetService,
                targetPath = targetPath,
                targetUrl = targetPath?.let { path ->
                    providers[button.targetService ?: button.serviceKey]
                        ?.frontendBaseUrlEnv
                        ?.let { env[it] ?: it }
                        ?.takeIf(String::isNotBlank)
                        ?.trimEnd('/')
                        ?.let { base -> "$base${if (path.startsWith("/")) path else "/$path"}" }
                },
                backendOperation = button.backendOperation
            )
        }
    }

    fun recordUsage(ownerType: String, ownerId: String, serviceKey: String, featureKey: String) {
        val normalizedOwnerType = if (ownerType == "ORGANIZATION") "ORGANIZATION" else "USER"
        repository.recordUsage(normalizedOwnerType, ownerId, serviceKey, featureKey, Instant.now())
    }
}
