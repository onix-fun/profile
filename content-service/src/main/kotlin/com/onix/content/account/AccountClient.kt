package com.onix.content.account

import com.onix.content.config.AppConfig
import com.onix.content.domain.AccountRelationship
import com.onix.content.domain.AccountSocialGraph
import com.onix.content.domain.AccountUser
import com.onix.content.domain.AccountVisibility
import com.onix.content.domain.CurrentActor
import com.onix.content.domain.OwnerRef
import com.onix.content.domain.OwnerType
import com.onix.content.domain.SessionUser
import com.unlim.profile.grpc.v1.ActivateNotificationServiceForUserRequest
import com.unlim.profile.grpc.v1.CurrentUserRequest
import com.unlim.profile.grpc.v1.GetUserByIdRequest
import com.unlim.profile.grpc.v1.LocalizedText
import com.unlim.profile.grpc.v1.NotificationTypeRegistration
import com.unlim.profile.grpc.v1.ProfileServiceGrpc
import com.unlim.profile.grpc.v1.GetOwnerByRefRequest
import com.unlim.profile.grpc.v1.GetOrganizationByNameRequest
import com.unlim.profile.grpc.v1.AuthorizeOwnerActionRequest
import com.unlim.profile.grpc.v1.OwnerAction
import com.unlim.profile.grpc.v1.OwnerVisibilityRequest
import com.unlim.profile.grpc.v1.PublishUserActivityRequest
import com.unlim.profile.grpc.v1.RegisterNotificationCatalogRequest
import com.unlim.profile.grpc.v1.UserIdRequest
import com.unlim.profile.grpc.v1.UserActivityType
import com.unlim.profile.grpc.v1.VisibilityRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import java.io.File

class AccountClient(config: AppConfig) : AutoCloseable {
    private val channel = accountChannel(config)
    private val baseStub = ProfileServiceGrpc.newBlockingStub(channel)

    fun registerContentNotificationCatalog() {
        runGrpc {
            baseStub.registerNotificationCatalog(
                RegisterNotificationCatalogRequest.newBuilder()
                    .setServiceKey("content")
                    .setName(text("Контент", "Content"))
                    .setDescription(text("Публикации, истории, комментарии и упоминания", "Posts, stories, comments, and mentions"))
                    .setIcon("pi pi-send")
                    .setDisplayOrder(20)
                    .addTypes(type("post_published", "Публикации", "Publications", "Новые публикации подписок", "New publications from following", "pi pi-send", 10))
                    .addTypes(type("story_published", "Истории", "Stories", "Новые истории подписок", "New stories from following", "pi pi-bolt", 20))
                    .addTypes(type("author_mention", "Упоминания автора", "Author mentions", "Когда вас добавляют как автора", "When you are added as an author", "pi pi-at", 30))
                    .addTypes(type("post_comment", "Комментарии", "Comments", "Комментарии к публикациям", "Comments on posts", "pi pi-comments", 40))
                    .build()
            )
        }
    }

    fun activateContentForUser(userId: String, accessToken: String) {
        runGrpc {
            stub(accessToken).activateNotificationServiceForUser(
                ActivateNotificationServiceForUserRequest.newBuilder()
                    .setUserId(userId)
                    .setServiceKey("content")
                    .build()
            )
        }
    }

    fun publishUserActivity(
        sourceEventId: String,
        actorId: String,
        activityType: UserActivityType,
        entityType: String,
        entityId: String,
        accessToken: String,
        metadataJson: String = "{}"
    ) {
        runGrpc {
            stub(accessToken).publishUserActivity(
                PublishUserActivityRequest.newBuilder()
                    .setSourceEventId(sourceEventId)
                    .setActorId(actorId)
                    .setActivityType(activityType)
                    .setEntityType(entityType)
                    .setEntityId(entityId)
                    .setMetadataJson(metadataJson)
                    .build()
            )
        }
    }

    fun getMe(accessToken: String): SessionUser =
        runGrpc { stub(accessToken).getCurrentUser(CurrentUserRequest.getDefaultInstance()).toSessionUser() }

    fun getCurrentActor(accessToken: String): CurrentActor =
        runGrpc {
            val response = stub(accessToken).getCurrentActor(CurrentUserRequest.getDefaultInstance())
            CurrentActor(
                user = response.user.toSessionUser(),
                activeOwner = response.activeOwner.toAccountOwner()
            )
        }

    fun authorize(owner: OwnerRef, action: OwnerAction, accessToken: String): Boolean =
        runGrpc {
            stub(accessToken).authorizeOwnerAction(
                AuthorizeOwnerActionRequest.newBuilder()
                    .setOwner(owner.toGrpc())
                    .setAction(action)
                    .build()
            ).allowed
        }

    fun getUser(userId: String, accessToken: String): AccountUser? =
        try {
            runGrpc { stub(accessToken).getUserById(GetUserByIdRequest.newBuilder().setUserId(userId).build()).toAccountUser() }
        } catch (_: AccountNotFound) {
            null
        }

    fun getOwner(owner: OwnerRef, accessToken: String): AccountUser? =
        try {
            runGrpc {
                stub(accessToken).getOwnerByRef(GetOwnerByRefRequest.newBuilder().setOwner(owner.toGrpc()).build())
                    .owner
                    .toAccountOwner()
            }
        } catch (_: AccountNotFound) {
            null
        }

    fun visibility(ownerId: String, viewerId: String?, accessToken: String): AccountVisibility =
        runGrpc {
            val response = stub(accessToken).getVisibility(
                VisibilityRequest.newBuilder()
                    .setOwnerId(ownerId)
                    .setViewerId(viewerId.orEmpty())
                    .build()
            )
            AccountVisibility(
                ownerId = response.ownerId,
                viewerId = response.viewerId.takeIf(String::isNotBlank),
                isPrivate = response.isPrivate,
                relationship = response.relationship.toRelationship(),
                isBlocked = response.isBlocked,
                isCloseFriend = response.isCloseFriend
            )
        }

    fun ownerVisibility(owner: OwnerRef, viewer: OwnerRef, accessToken: String): AccountVisibility =
        runGrpc {
            val response = stub(accessToken).getOwnerVisibility(
                OwnerVisibilityRequest.newBuilder()
                    .setOwner(owner.toGrpc())
                    .setViewer(viewer.toGrpc())
                    .build()
            )
            AccountVisibility(
                ownerId = response.ownerId,
                ownerType = owner.ownerType,
                viewerId = response.viewerId.takeIf(String::isNotBlank),
                viewerType = viewer.ownerType,
                isPrivate = response.isPrivate,
                relationship = response.relationship.toRelationship(),
                isBlocked = response.isBlocked,
                isCloseFriend = response.isCloseFriend
            )
        }

    fun socialGraph(viewerId: String, accessToken: String): AccountSocialGraph =
        runGrpc {
            val response = stub(accessToken).getSocialGraph(UserIdRequest.newBuilder().setUserId(viewerId).build())
            AccountSocialGraph(
                followingIds = response.followingIdsList,
                friendIds = response.friendIdsList,
                blockedIds = response.blockedIdsList
            )
        }

    fun ownerSocialGraph(owner: OwnerRef, accessToken: String): AccountSocialGraph =
        runGrpc {
            val response = stub(accessToken).getOwnerSocialGraph(owner.toGrpc())
            AccountSocialGraph(
                followingIds = response.followingIdsList,
                friendIds = response.friendIdsList,
                blockedIds = response.blockedIdsList
            )
        }

    private fun stub(accessToken: String): ProfileServiceGrpc.ProfileServiceBlockingStub {
        val headers = Metadata().apply {
            put(AUTHORIZATION_KEY, "Bearer $accessToken")
        }
        return baseStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun <T> runGrpc(block: () -> T): T =
        try {
            block()
        } catch (e: StatusRuntimeException) {
            throw when (e.status.code) {
                Status.Code.UNAUTHENTICATED -> AccountUnauthorized()
                Status.Code.NOT_FOUND -> AccountNotFound()
                else -> AccountUnavailable("Account gRPC returned ${e.status.code}: ${e.status.description.orEmpty()}")
            }
        }

    override fun close() {
        channel.shutdown()
    }

    private companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

private fun accountChannel(config: AppConfig): ManagedChannel {
    val (host, port) = parseTarget(config.accountGrpcUrl)
    val builder = NettyChannelBuilder.forAddress(host, port)
    if (config.accountGrpcTls) {
        val ssl = GrpcSslContexts.forClient()
        config.accountGrpcTrustCert?.let { ssl.trustManager(File(it)) }
        if (!config.accountGrpcClientCert.isNullOrBlank() && !config.accountGrpcClientKey.isNullOrBlank()) {
            ssl.keyManager(File(config.accountGrpcClientCert), File(config.accountGrpcClientKey))
        }
        builder.sslContext(ssl.build())
    } else {
        builder.usePlaintext()
    }
    return builder.build()
}

private fun parseTarget(target: String): Pair<String, Int> {
    val parts = target.removePrefix("http://").removePrefix("https://").split(":", limit = 2)
    return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 9097)
}

private fun type(typeKey: String, ruName: String, enName: String, ruDescription: String, enDescription: String, icon: String, order: Int): NotificationTypeRegistration =
    NotificationTypeRegistration.newBuilder()
        .setTypeKey(typeKey)
        .setName(text(ruName, enName))
        .setDescription(text(ruDescription, enDescription))
        .setIcon(icon)
        .setDefaultEnabled(true)
        .setDisplayOrder(order)
        .build()

private fun text(ru: String, en: String): LocalizedText =
    LocalizedText.newBuilder().setRu(ru).setEn(en).build()

private fun com.unlim.profile.grpc.v1.AccountUser.toSessionUser(): SessionUser =
    SessionUser(id = id, username = username, firstName = firstName.blankToNull(), lastName = lastName.blankToNull(), avatarUrl = avatarUrl.blankToNull())

private fun com.unlim.profile.grpc.v1.AccountUser.toAccountUser(): AccountUser =
    AccountUser(id = id, ownerType = OwnerType.USER, username = username, displayName = listOf(firstName, lastName).filter(String::isNotBlank).joinToString(" ").blankToNull(), firstName = firstName.blankToNull(), lastName = lastName.blankToNull(), avatarUrl = avatarUrl.blankToNull())

private fun com.unlim.profile.grpc.v1.OwnerIdentity.toAccountOwner(): AccountUser =
    AccountUser(
        id = ref.ownerId,
        ownerType = ref.ownerType.toDomain(),
        username = username,
        displayName = displayName.blankToNull() ?: username,
        avatarUrl = avatarUrl.blankToNull()
    )

private fun OwnerRef.toGrpc(): com.unlim.profile.grpc.v1.OwnerRef =
    com.unlim.profile.grpc.v1.OwnerRef.newBuilder()
        .setOwnerType(ownerType.toGrpc())
        .setOwnerId(ownerId)
        .build()

private fun OwnerType.toGrpc(): com.unlim.profile.grpc.v1.OwnerType =
    when (this) {
        OwnerType.USER -> com.unlim.profile.grpc.v1.OwnerType.USER
        OwnerType.ORGANIZATION -> com.unlim.profile.grpc.v1.OwnerType.ORGANIZATION
    }

private fun com.unlim.profile.grpc.v1.OwnerType.toDomain(): OwnerType =
    when (this) {
        com.unlim.profile.grpc.v1.OwnerType.ORGANIZATION -> OwnerType.ORGANIZATION
        else -> OwnerType.USER
    }

private fun com.unlim.profile.grpc.v1.RelResponse.toRelationship(): AccountRelationship =
    AccountRelationship(
        isFollowing = isFollowing,
        isFollowedBy = isFollowedBy,
        isFriend = isFriend,
        isBlocked = isBlocked,
        hasPendingRequest = hasPendingRequest
    )

private fun String.blankToNull(): String? = takeIf { it.isNotBlank() }

class AccountUnauthorized : RuntimeException()
class AccountNotFound : RuntimeException()
class AccountUnavailable(message: String) : RuntimeException(message)
