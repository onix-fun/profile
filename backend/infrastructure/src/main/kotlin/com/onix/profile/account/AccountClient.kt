package com.onix.profile.account

import com.onix.profile.config.AppConfig
import com.onix.profile.domain.*
import com.onix.account.contract.CurrentUserRequest
import com.onix.account.contract.FollowRequest
import com.onix.account.contract.GetOrganizationByNameRequest
import com.onix.account.contract.GetProfileByUsernameRequest
import com.onix.account.contract.OwnerFollowRequest
import com.onix.account.contract.AccountServiceGrpc
import com.onix.account.contract.SearchUsersRequest
import com.onix.account.contract.UserPageRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import java.io.File
import java.util.concurrent.TimeUnit

class AccountClient(config: AppConfig) : AutoCloseable {
    private val channel = accountChannel(config)
    private val baseStub = AccountServiceGrpc.newBlockingStub(channel)

    fun getMe(accessToken: String): SessionUser =
        runGrpc { stub(accessToken).getCurrentUser(CurrentUserRequest.getDefaultInstance()).toSessionUser() }

    fun getCurrentActor(accessToken: String): CurrentActor =
        runGrpc { stub(accessToken).getCurrentActor(CurrentUserRequest.getDefaultInstance()).toCurrentActor() }

    fun getProfile(username: String, accessToken: String? = null): AccountProfile =
        runGrpc { stub(accessToken).getProfileByUsername(GetProfileByUsernameRequest.newBuilder().setUsername(username).build()).toAccountProfile() }

    fun getOrganizationProfile(orgName: String, accessToken: String? = null): AccountProfile =
        runGrpc { stub(accessToken).getOrganizationByName(GetOrganizationByNameRequest.newBuilder().setOrgName(orgName).build()).toAccountProfile() }

    fun follow(userId: String, accessToken: String): Relationship =
        runGrpc { stub(accessToken).follow(FollowRequest.newBuilder().setTargetId(userId).build()).toRelationship() }

    fun unfollow(userId: String, accessToken: String) {
        runGrpc { stub(accessToken).unfollow(FollowRequest.newBuilder().setTargetId(userId).build()) }
    }

    fun followOwner(ownerType: String, ownerId: String, accessToken: String): Relationship =
        runGrpc { stub(accessToken).followOwner(OwnerFollowRequest.newBuilder().setTarget(ownerRef(ownerType, ownerId)).build()).toRelationship() }

    fun unfollowOwner(ownerType: String, ownerId: String, accessToken: String) {
        runGrpc { stub(accessToken).unfollowOwner(OwnerFollowRequest.newBuilder().setTarget(ownerRef(ownerType, ownerId)).build()) }
    }

    fun searchUsers(query: String, limit: Int, accessToken: String): List<AccountSearchUser> {
        if (query.isBlank()) return emptyList()
        return runGrpc {
            stub(accessToken).searchUsers(SearchUsersRequest.newBuilder().setQuery(query.trim()).setLimit(limit.coerceIn(1, 50)).build())
                .usersList
                .map { it.toSearchUser() }
        }
    }

    fun searchOwners(query: String, limit: Int, accessToken: String): List<AccountSearchUser> {
        if (query.isBlank()) return emptyList()
        return runGrpc {
            stub(accessToken).searchOwners(SearchUsersRequest.newBuilder().setQuery(query.trim()).setLimit(limit.coerceIn(1, 50)).build())
                .ownersList
                .map { it.toSearchOwner() }
        }
    }

    fun followers(userId: String, page: Int, limit: Int, accessToken: String): UserPageResponse =
        userPage(stub(accessToken).listFollowers(pageRequest(userId, page, limit)))

    fun following(userId: String, page: Int, limit: Int, accessToken: String): UserPageResponse =
        userPage(stub(accessToken).listFollowing(pageRequest(userId, page, limit)))

    fun ownerFollowers(ownerType: String, ownerId: String, page: Int, limit: Int, accessToken: String? = null): UserPageResponse =
        ownerPage(stub(accessToken).listOwnerFollowers(ownerPageRequest(ownerType, ownerId, page, limit)))

    fun ownerFollowing(ownerType: String, ownerId: String, page: Int, limit: Int, accessToken: String? = null): UserPageResponse =
        ownerPage(stub(accessToken).listOwnerFollowing(ownerPageRequest(ownerType, ownerId, page, limit)))

    private fun stub(accessToken: String?): AccountServiceGrpc.AccountServiceBlockingStub {
        val headers = Metadata().apply {
            if (!accessToken.isNullOrBlank()) put(AUTHORIZATION_KEY, "Bearer $accessToken")
        }
        return baseStub
            .withWaitForReady()
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun pageRequest(userId: String, page: Int, limit: Int): UserPageRequest =
        UserPageRequest.newBuilder()
            .setUserId(userId)
            .setPage(page.coerceAtLeast(1))
            .setLimit(limit.coerceIn(1, 100))
            .build()

    private fun ownerPageRequest(ownerType: String, ownerId: String, page: Int, limit: Int): com.onix.account.contract.OwnerPageRequest =
        com.onix.account.contract.OwnerPageRequest.newBuilder()
            .setOwner(ownerRef(ownerType, ownerId))
            .setPage(page.coerceAtLeast(1))
            .setLimit(limit.coerceIn(1, 100))
            .build()

    private fun ownerRef(ownerType: String, ownerId: String): com.onix.account.contract.OwnerRef =
        com.onix.account.contract.OwnerRef.newBuilder()
            .setOwnerType(if (ownerType == "ORGANIZATION") com.onix.account.contract.OwnerType.ORGANIZATION else com.onix.account.contract.OwnerType.USER)
            .setOwnerId(ownerId)
            .build()

    private fun userPage(response: com.onix.account.contract.UserPageResponse): UserPageResponse =
        UserPageResponse(
            items = response.itemsList.map {
                val user = it.user
                RelatedUser(
                    id = user.id,
                    ownerType = "USER",
                    username = user.username,
                    displayName = listOf(user.firstName, user.lastName).filter(String::isNotBlank).joinToString(" ").blankToNull(),
                    firstName = user.firstName.blankToNull(),
                    lastName = user.lastName.blankToNull(),
                    avatarUrl = user.avatarUrl.blankToNull(),
                    relationship = it.relationship.toRelationship()
                )
            },
            totalCount = response.totalCount
        )

    private fun ownerPage(response: com.onix.account.contract.OwnerPageResponse): UserPageResponse =
        UserPageResponse(
            items = response.itemsList.map {
                val profile = it.owner
                val owner = profile.owner
                val ownerType = owner.ref.ownerType.name
                RelatedUser(
                    id = owner.ref.ownerId,
                    ownerType = ownerType,
                    username = owner.username,
                    displayName = owner.displayName.blankToNull(),
                    firstName = if (ownerType == "USER") owner.displayName.blankToNull() else null,
                    avatarUrl = owner.avatarUrl.blankToNull(),
                    relationship = it.relationship.toRelationship()
                )
            },
            totalCount = response.totalCount
        )

    private fun <T> runGrpc(block: () -> T): T =
        try {
            block()
        } catch (e: StatusRuntimeException) {
            throw when (e.status.code) {
                Status.Code.UNAUTHENTICATED -> AccountUnauthorized()
                Status.Code.PERMISSION_DENIED -> AccountForbidden()
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

private fun com.onix.account.contract.AccountUser.toSessionUser(): SessionUser =
    SessionUser(id = id, username = username, firstName = firstName.blankToNull(), lastName = lastName.blankToNull(), avatarUrl = avatarUrl.blankToNull())

private fun com.onix.account.contract.CurrentActor.toCurrentActor(): CurrentActor =
    CurrentActor(user = user.toSessionUser(), activeOwner = activeOwner.toAccountUser())

private fun com.onix.account.contract.OwnerIdentity.toAccountUser(): AccountUser =
    AccountUser(
        id = ref.ownerId,
        ownerType = ref.ownerType.name,
        username = username,
        displayName = displayName.blankToNull(),
        avatarUrl = avatarUrl.blankToNull()
    )

private fun com.onix.account.contract.AccountUser.toSearchUser(): AccountSearchUser =
    AccountSearchUser(
        id = id,
        ownerType = "USER",
        username = username,
        displayName = listOf(firstName, lastName).filter(String::isNotBlank).joinToString(" ").blankToNull(),
        firstName = firstName.blankToNull(),
        lastName = lastName.blankToNull(),
        avatarUrl = avatarUrl.blankToNull(),
        bio = bio.blankToNull()
    )

private fun com.onix.account.contract.OwnerProfile.toSearchOwner(): AccountSearchUser =
    AccountSearchUser(
        id = owner.ref.ownerId,
        ownerType = owner.ref.ownerType.name,
        username = owner.username,
        displayName = owner.displayName.blankToNull(),
        firstName = if (owner.ref.ownerType.name == "USER") owner.displayName.blankToNull() else null,
        avatarUrl = owner.avatarUrl.blankToNull(),
        bio = bio.blankToNull()
    )

private fun com.onix.account.contract.AccountProfile.toAccountProfile(): AccountProfile =
    AccountProfile(
        id = user.id,
        ownerType = "USER",
        username = user.username,
        displayName = listOf(user.firstName, user.lastName).filter(String::isNotBlank).joinToString(" ").blankToNull(),
        firstName = user.firstName.blankToNull(),
        lastName = user.lastName.blankToNull(),
        bio = user.bio.blankToNull(),
        birthday = if (user.hasBirthday()) BirthdayParts(user.birthday.day, user.birthday.month) else null,
        socialLinks = user.socialLinksList.map { SocialLink(it.label, it.url) },
        avatarUrl = user.avatarUrl.blankToNull(),
        followersCount = followersCount,
        followingCount = followingCount,
        isPrivate = isPrivate,
        relationship = relationship.toRelationship()
    )

private fun com.onix.account.contract.OwnerProfile.toAccountProfile(): AccountProfile =
    AccountProfile(
        id = owner.ref.ownerId,
        ownerType = owner.ref.ownerType.name,
        username = owner.username,
        displayName = owner.displayName.blankToNull() ?: owner.username,
        firstName = owner.displayName.blankToNull(),
        bio = bio.blankToNull(),
        socialLinks = socialLinksList.map { SocialLink(it.label, it.url) },
        avatarUrl = owner.avatarUrl.blankToNull(),
        followersCount = followersCount,
        followingCount = followingCount,
        isPrivate = isPrivate,
        relationship = relationship.toRelationship()
    )

private fun com.onix.account.contract.RelResponse.toRelationship(): Relationship =
    Relationship(
        isFollowing = isFollowing,
        isFollowedBy = isFollowedBy,
        isFriend = isFriend,
        isBlocked = isBlocked,
        hasPendingRequest = hasPendingRequest
    )

private fun String.blankToNull(): String? = takeIf { it.isNotBlank() }

class AccountUnauthorized : RuntimeException()
class AccountForbidden : RuntimeException()
class AccountNotFound : RuntimeException()
class AccountUnavailable(message: String) : RuntimeException(message)
