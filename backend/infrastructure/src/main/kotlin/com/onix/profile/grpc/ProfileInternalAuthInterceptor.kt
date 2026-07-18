package com.onix.profile.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import java.security.MessageDigest

class ProfileInternalAuthInterceptor(secret: String) : ServerInterceptor {
    private val expected = secret.toByteArray()

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val supplied = headers[TOKEN]?.toByteArray() ?: byteArrayOf()
        if (!MessageDigest.isEqual(expected, supplied)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Internal service token is invalid"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }

    private companion object {
        val TOKEN: Metadata.Key<String> = Metadata.Key.of("x-onix-internal-token", Metadata.ASCII_STRING_MARSHALLER)
    }
}
