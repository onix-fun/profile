package com.onix.profile.grpc

import com.onix.profile.contract.GetPublicProfileRequest
import com.onix.profile.contract.OwnerType
import com.onix.profile.contract.ProfileServiceGrpc
import com.onix.profile.contract.PublicProfile
import com.onix.profile.contract.SetAvatarRequest
import com.onix.profile.contract.SocialLink
import com.onix.profile.contract.UpdatePublicProfileRequest
import com.onix.profile.contract.*
import com.onix.profile.domain.AccountUser
import com.onix.profile.domain.CreateCollectionInput
import com.onix.profile.domain.ProfileCollectionItemRef
import com.onix.profile.domain.UpdateCollectionInput
import com.onix.profile.service.CollectionService
import com.onix.profile.service.ProfileRepository
import com.onix.profile.service.StoredPublicProfile
import com.onix.profile.service.canView
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileGrpcService(private val repository: ProfileRepository, private val collections: CollectionService) : ProfileServiceGrpc.ProfileServiceImplBase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun getPublicProfile(request: GetPublicProfileRequest, observer: StreamObserver<PublicProfile>) = unary(observer) {
        repository.findPublicProfile(request.owner.type.dbName(), request.owner.id)?.toGrpc()
            ?: throw Status.NOT_FOUND.withDescription("Public profile not found").asRuntimeException()
    }

    override fun updatePublicProfile(request: UpdatePublicProfileRequest, observer: StreamObserver<PublicProfile>) = unary(observer) {
        repository.updatePublicProfile(
            request.owner.type.dbName(), request.owner.id, request.username, request.displayName, request.bio,
            json.encodeToString(request.socialLinksList.map { StoredSocialLink(it.platform, it.url) }), request.expectedRevision
        ).toGrpc()
    }

    override fun setAvatar(request: SetAvatarRequest, observer: StreamObserver<PublicProfile>) = unary(observer) {
        repository.setAvatar(request.owner.type.dbName(), request.owner.id, request.mediaAssetId, request.expectedRevision).toGrpc()
    }

    override fun listCollections(request: ListCollectionsRequest, observer: StreamObserver<ListCollectionsResponse>) = unary(observer) {
        ListCollectionsResponse.newBuilder().addAllCollections(
            collections.collections(request.owner.type.dbName(), request.owner.id, request.viewer.actor(), request.limit).map { it.toGrpc() }
        ).build()
    }

    override fun getCollection(request: GetCollectionRequest, observer: StreamObserver<com.onix.profile.contract.CollectionDetail>) = unary(observer) {
        val collection = repository.findCollection(request.collectionId) ?: throw Status.NOT_FOUND.asRuntimeException()
        check(collection.canView(request.viewer.actor())) { "Collection not found" }
        com.onix.profile.contract.CollectionDetail.newBuilder().setCollection(collection.toGrpc()).addAllItems(
            repository.listCollectionItems(collection.id, request.limit.coerceIn(1, 500)).map { it.toGrpc() }
        ).build()
    }

    override fun createCollection(request: CreateCollectionRequest, observer: StreamObserver<com.onix.profile.contract.Collection>) = unary(observer) {
        collections.createCollection(request.owner.actor(), CreateCollectionInput(
            request.title, request.description.takeIf(String::isNotBlank), request.coverJson.jsonObjectOrNull(), request.visibility
        )).toGrpc()
    }

    override fun updateCollection(request: UpdateCollectionRequest, observer: StreamObserver<com.onix.profile.contract.Collection>) = unary(observer) {
        collections.updateCollection(request.owner.actor(), request.collectionId, UpdateCollectionInput(
            request.title.takeIf { request.hasTitle() }, request.description.takeIf { request.hasDescription() },
            request.coverJson.takeIf { request.hasCoverJson() }?.jsonObjectOrNull(), request.visibility.takeIf { request.hasVisibility() }
        )).toGrpc()
    }

    override fun deleteCollection(request: DeleteCollectionRequest, observer: StreamObserver<DeleteCollectionResponse>) = unary(observer) {
        collections.deleteCollection(request.owner.actor(), request.collectionId)
        DeleteCollectionResponse.newBuilder().setDeleted(true).build()
    }

    override fun getItemCollections(request: GetItemCollectionsRequest, observer: StreamObserver<ItemCollectionsResponse>) = unary(observer) {
        collections.itemCollections(request.owner.actor(), request.item.toDomain()).toGrpc()
    }

    override fun setItemCollections(request: SetItemCollectionsRequest, observer: StreamObserver<ItemCollectionsResponse>) = unary(observer) {
        collections.setItemCollections(request.owner.actor(), request.item.toDomain(), request.collectionIdsList).toGrpc()
    }

    private fun StoredPublicProfile.toGrpc(): PublicProfile {
        val links = runCatching { json.decodeFromString<List<StoredSocialLink>>(socialLinksJson) }.getOrDefault(emptyList())
        return PublicProfile.newBuilder()
            .setOwner(com.onix.profile.contract.OwnerRef.newBuilder().setType(if (ownerType == "ORGANIZATION") OwnerType.OWNER_TYPE_ORGANIZATION else OwnerType.OWNER_TYPE_USER).setId(ownerId))
            .setUsername(username).setDisplayName(displayName).setBio(bio).setAvatarAssetId(avatarAssetId.orEmpty())
            .addAllSocialLinks(links.map { SocialLink.newBuilder().setPlatform(it.platform).setUrl(it.url).build() })
            .setRevision(revision).setUpdatedAt(updatedAt.toString()).build()
    }

    private fun OwnerType.dbName(): String = if (this == OwnerType.OWNER_TYPE_ORGANIZATION) "ORGANIZATION" else "USER"

    private fun OwnerRef.actor() = AccountUser(id = id, ownerType = type.dbName(), username = id)
    private fun com.onix.profile.domain.ProfileContentCollection.toGrpc() = com.onix.profile.contract.Collection.newBuilder()
        .setId(id).setOwner(OwnerRef.newBuilder().setType(if (ownerType == "ORGANIZATION") OwnerType.OWNER_TYPE_ORGANIZATION else OwnerType.OWNER_TYPE_USER).setId(ownerId))
        .setTitle(title).setDescription(description.orEmpty()).setCoverJson(cover?.toString().orEmpty()).setVisibility(visibility)
        .setItemCount(itemCount).setCreatedAt(createdAt.orEmpty()).setUpdatedAt(updatedAt.orEmpty()).build()
    private fun ProfileCollectionItemRef.toGrpc() = com.onix.profile.contract.CollectionItemRef.newBuilder().setProviderKey(serviceKey).setItemType(itemType).setItemId(itemId).build()
    private fun com.onix.profile.contract.CollectionItemRef.toDomain() = ProfileCollectionItemRef(providerKey, itemType, itemId)
    private fun com.onix.profile.domain.ItemCollectionsState.toGrpc() = ItemCollectionsResponse.newBuilder().setItem(ref.toGrpc()).addAllCollectionIds(collectionIds).build()
    private fun String.jsonObjectOrNull() = takeIf(String::isNotBlank)?.let { runCatching { json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }

    private fun <T> unary(observer: StreamObserver<T>, block: () -> T) {
        try { observer.onNext(block()); observer.onCompleted() }
        catch (error: Throwable) { observer.onError(if (error is io.grpc.StatusRuntimeException) error else Status.FAILED_PRECONDITION.withDescription(error.message).withCause(error).asRuntimeException()) }
    }
}

@Serializable
private data class StoredSocialLink(val platform: String, val url: String)
