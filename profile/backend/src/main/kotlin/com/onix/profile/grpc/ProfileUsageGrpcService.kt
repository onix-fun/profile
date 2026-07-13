package com.onix.profile.grpc

import com.onix.content.v1.OwnerServiceUsageAck
import com.onix.content.v1.OwnerServiceUsageReport
import com.onix.content.v1.ProfileUsageGrpc
import com.onix.profile.service.ProfileNavigationService
import io.grpc.Status
import io.grpc.stub.StreamObserver

class ProfileUsageGrpcService(
    private val navigation: ProfileNavigationService
) : ProfileUsageGrpc.ProfileUsageImplBase() {
    override fun reportOwnerServiceUsage(
        request: OwnerServiceUsageReport,
        observer: StreamObserver<OwnerServiceUsageAck>
    ) {
        try {
            navigation.recordUsage(
                ownerType = request.ownerType,
                ownerId = request.ownerId,
                serviceKey = request.serviceKey,
                featureKey = request.featureKey
            )
            observer.onNext(OwnerServiceUsageAck.newBuilder().setRecorded(true).build())
            observer.onCompleted()
        } catch (error: Throwable) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(error.message ?: "Invalid usage report").asRuntimeException())
        }
    }
}
