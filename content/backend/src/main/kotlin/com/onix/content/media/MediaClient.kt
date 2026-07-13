package com.onix.content.media

import com.onix.media.v1.CompleteUploadRequest
import com.onix.media.v1.CreateReferenceRequest
import com.onix.media.v1.GetDownloadURLRequest
import com.onix.media.v1.GetUploadSessionRequest
import com.onix.media.v1.InitUploadRequest
import com.onix.media.v1.MediaStoreGrpc
import com.onix.media.v1.UploadPart
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.StatusRuntimeException
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class UploadedMedia(
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val blobId: String
)

data class DownloadedMedia(
    val bytes: ByteArray,
    val mimeType: String
)

class MediaClient(
    target: String?,
    private val apiKey: String,
    private val tls: Boolean = false,
    private val trustCert: String? = null,
    private val clientCert: String? = null,
    private val clientKey: String? = null
) : AutoCloseable {
    private val channel: ManagedChannel? = target?.takeIf(String::isNotBlank)?.let { channel(it, tls, trustCert, clientCert, clientKey) }
    private val stub: MediaStoreGrpc.MediaStoreBlockingStub? = channel?.let(MediaStoreGrpc::newBlockingStub)
    private val http = HttpClient.newBuilder().build()

    fun upload(fileName: String, mimeType: String, bytes: ByteArray): UploadedMedia {
        val current = stub ?: throw MediaUnavailable("media gRPC target is not configured")
        val normalizedMime = mimeType.ifBlank { "application/octet-stream" }
        val init = callMedia {
            current.authed().initUpload(
                InitUploadRequest.newBuilder()
                    .setMimeType(normalizedMime)
                    .setExpectedSize(bytes.size.toLong())
                    .setPartsCount(1)
                    .build()
            )
        }
        val uploadUrl = init.partsMap[1] ?: throw MediaUnavailable("media gRPC did not return part 1 upload URL")
        val etag = uploadPart(uploadUrl, normalizedMime, bytes)
        callMedia {
            current.authed().completeUpload(
                CompleteUploadRequest.newBuilder()
                    .setSessionId(init.sessionId)
                    .addParts(UploadPart.newBuilder().setPartNumber(1).setEtag(etag).build())
                    .build()
            )
        }
        val blobId = waitForBlob(current, init.sessionId)
        return UploadedMedia(
            fileName = fileName,
            mimeType = normalizedMime,
            size = bytes.size.toLong(),
            blobId = blobId
        )
    }

    fun createReference(blobId: String, ownerType: String, ownerId: String) {
        val current = stub ?: throw MediaUnavailable("media gRPC target is not configured")
        callMedia {
            current.authed().createReference(
                CreateReferenceRequest.newBuilder()
                    .setBlobId(blobId)
                    .setReferenceType(ownerType)
                    .setReferenceId(ownerId)
                    .build()
            )
        }
    }

    fun downloadUrl(blobId: String): String {
        val current = stub ?: throw MediaUnavailable("media gRPC target is not configured")
        val response = callMedia {
            current.authed().getDownloadURL(GetDownloadURLRequest.newBuilder().setBlobId(blobId).build())
        }
        return response.url.ifBlank { throw MediaUnavailable("media gRPC returned an empty download URL") }
    }

    override fun close() {
        channel?.shutdown()
    }

    private fun MediaStoreGrpc.MediaStoreBlockingStub.authed(): MediaStoreGrpc.MediaStoreBlockingStub {
        val headers = Metadata().apply {
            put(AUTHORIZATION_KEY, "Bearer $apiKey")
            put(SERVICE_KEY, "content")
        }
        return withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun <T> callMedia(block: () -> T): T =
        try {
            block()
        } catch (error: StatusRuntimeException) {
            throw MediaUnavailable("media gRPC returned ${error.status.code}: ${error.status.description.orEmpty()}")
        }

    private fun uploadPart(url: String, mimeType: String, bytes: ByteArray): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", mimeType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media presigned upload returned ${response.statusCode()}")
        }
        return response.headers().firstValue("ETag").orElseThrow {
            MediaUnavailable("media presigned upload did not return ETag")
        }
    }

    private fun waitForBlob(stub: MediaStoreGrpc.MediaStoreBlockingStub, sessionId: String): String {
        repeat(20) {
            val response = callMedia {
                stub.authed().getUploadSession(GetUploadSessionRequest.newBuilder().setSessionId(sessionId).build())
            }
            if (response.status == "COMPLETED" && response.blobId.isNotBlank()) {
                return response.blobId
            }
            Thread.sleep(500)
        }
        throw MediaUnavailable("media upload did not complete in time")
    }

    private companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        val SERVICE_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-onix-service", Metadata.ASCII_STRING_MARSHALLER)
    }
}

class MediaUnavailable(message: String) : RuntimeException(message)

private fun channel(target: String, tls: Boolean, trustCert: String?, clientCert: String?, clientKey: String?): ManagedChannel {
    val (host, port) = parseTarget(target)
    val builder = NettyChannelBuilder.forAddress(host, port)
    if (!tls) return builder.usePlaintext().build()
    val ssl = GrpcSslContexts.forClient()
    trustCert?.let { ssl.trustManager(File(it)) }
    if (!clientCert.isNullOrBlank() && !clientKey.isNullOrBlank()) {
        ssl.keyManager(File(clientCert), File(clientKey))
    }
    return builder.sslContext(ssl.build()).build()
}

private fun parseTarget(target: String): Pair<String, Int> {
    val parts = target.removePrefix("http://").removePrefix("https://").split(":", limit = 2)
    return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 9093)
}
