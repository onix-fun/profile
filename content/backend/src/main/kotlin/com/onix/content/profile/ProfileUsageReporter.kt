package com.onix.content.profile

import com.onix.content.domain.OwnerRef
import com.onix.content.v1.OwnerServiceUsageReport
import com.onix.content.v1.ProfileUsageGrpc
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder

interface ProfileUsageReporter : AutoCloseable {
    fun report(owner: OwnerRef, serviceKey: String, featureKey: String)

    override fun close() = Unit

    companion object {
        fun noop(): ProfileUsageReporter = object : ProfileUsageReporter {
            override fun report(owner: OwnerRef, serviceKey: String, featureKey: String) = Unit
        }
    }
}

class GrpcProfileUsageReporter(target: String?) : ProfileUsageReporter {
    private val channel: ManagedChannel?
    private val stub: ProfileUsageGrpc.ProfileUsageBlockingStub?

    init {
        if (target.isNullOrBlank()) {
            channel = null
            stub = null
        } else {
            val (host, port) = parseTarget(target)
            channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
            stub = ProfileUsageGrpc.newBlockingStub(channel)
        }
    }

    override fun report(owner: OwnerRef, serviceKey: String, featureKey: String) {
        val current = stub ?: return
        runCatching {
            current.reportOwnerServiceUsage(
                OwnerServiceUsageReport.newBuilder()
                    .setOwnerType(owner.ownerType.name)
                    .setOwnerId(owner.ownerId)
                    .setServiceKey(serviceKey)
                    .setFeatureKey(featureKey)
                    .build()
            )
        }.onFailure {
            System.err.println("Failed to report profile usage $serviceKey/$featureKey: ${it.message}")
        }
    }

    override fun close() {
        channel?.shutdown()
    }
}

private fun parseTarget(target: String): Pair<String, Int> {
    val parts = target.removePrefix("http://").removePrefix("https://").split(":", limit = 2)
    return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 9092)
}
