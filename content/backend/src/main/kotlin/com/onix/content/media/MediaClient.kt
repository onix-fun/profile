package com.onix.content.media

import com.onix.media.v1.CompleteUploadRequest
import com.onix.media.v1.CompleteAssetUploadRequest
import com.onix.media.v1.CreateReferenceRequest
import com.onix.media.v1.AssetStatus
import com.onix.media.v1.GetAssetRequest
import com.onix.media.v1.GetDownloadURLRequest
import com.onix.media.v1.GetUploadSessionRequest
import com.onix.media.v1.InitUploadRequest
import com.onix.media.v1.ListAssetLifecycleEventsRequest
import com.onix.media.v1.InitAssetUploadRequest
import com.onix.media.v1.MediaStoreGrpc
import com.onix.media.v1.ProcessingProfile
import com.onix.media.v1.RetryAssetProcessingRequest
import com.onix.media.v1.UploadPart
import com.onix.media.v2.MediaAssetsGrpc
import com.onix.media.v2.MediaKind
import com.onix.media.v2.SourceStatus
import com.onix.media.v2.ProcessingStatus
import com.onix.media.v2.BeginAssetUploadRequest
import com.onix.media.v2.CompleteAssetUploadRequest as CompleteAssetUploadRequestV2
import com.onix.media.v2.UploadPart as UploadPartV2
import com.onix.media.v2.GetAssetSourceRequest
import com.onix.media.v2.BatchGetAssetSourcesRequest
import com.onix.media.v2.RequestProcessingRequest
import com.onix.media.v2.GetDeliveryManifestRequest
import com.onix.media.v2.ResolveDeliveryRequest
import com.onix.media.v2.ResolveSourceRequest
import com.onix.media.v2.ReleaseSourceRequest
import com.onix.media.v2.RetryProcessingRequest
import com.onix.media.v2.CancelProcessingRequest
import com.onix.media.v2.ListLifecycleEventsRequest
import com.onix.content.service.RequestedMediaProcessing
import com.onix.content.domain.*
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

data class MediaLifecycleEvent(
    val sequence: Long,
    val eventId: String,
    val type: String,
    val assetId: String,
    val generation: Long,
    val ownerKey: String,
    val failureCode: String
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
    private val assetStub: MediaAssetsGrpc.MediaAssetsBlockingStub? = channel?.let(MediaAssetsGrpc::newBlockingStub)
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

    fun initAssetUpload(ownerId: String, input: InitAssetUploadInput): InitAssetUploadResponse {
        require(ownerId.isNotBlank()) { "Owner is required" }
        require(input.mimeType.isNotBlank()) { "Media MIME type is required" }
        require(input.expectedSize in 1..MAX_ASSET_UPLOAD_BYTES) { "Media asset size is invalid" }
        require(input.partsCount in 1..MAX_ASSET_PARTS) { "Media upload part count is invalid" }
        require(input.sourcePolicyId in SOURCE_POLICIES) { "Media source policy is invalid" }
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        val response = callMedia {
            current.authedV2().beginAssetUpload(
                BeginAssetUploadRequest.newBuilder()
                    .setOwnerRef(ownerId)
                    .setDeclaredKind(input.kind.toMediaKind())
                    .setMimeType(input.mimeType.trim().substringBefore(';').lowercase())
                    .setExpectedSize(input.expectedSize)
                    .setPartsCount(input.partsCount)
                    .setSourcePolicyId(input.sourcePolicyId)
                .build()
            )
        }
        if (response.sessionId.isBlank() || response.source.assetId.isBlank()) {
            throw MediaUnavailable("media gRPC returned an incomplete upload session")
        }
        return InitAssetUploadResponse(
            asset = response.toPostAsset(),
            sessionId = response.sessionId,
            parts = orderedAssetUploadTargets(response.partsMap, input.partsCount),
            expiresAt = response.expiresAt.takeIf(String::isNotBlank)
        )
    }

    fun completeAssetUpload(ownerId: String, input: CompleteAssetUploadInput): PostAsset {
        require(ownerId.isNotBlank() && input.assetId.isNotBlank() && input.sessionId.isNotBlank()) { "Asset upload identifiers are required" }
        require(input.parts.isNotEmpty() && input.parts.size <= MAX_ASSET_PARTS) { "Upload parts are required" }
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia {
            current.authedV2().completeAssetUpload(
                CompleteAssetUploadRequestV2.newBuilder()
                    .setOwnerRef(ownerId)
                    .setAssetId(input.assetId)
                    .setSessionId(input.sessionId)
                    .addAllParts(input.parts.map { part ->
                        UploadPartV2.newBuilder().setPartNumber(part.partNumber).setEtag(part.etag).build()
                    })
                    .build()
            ).toPostAsset()
        }
    }

    fun getAssetForOwner(ownerId: String, assetId: String): PostAsset {
        require(ownerId.isNotBlank() && assetId.isNotBlank()) { "Asset identifier is required" }
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia {
            val response = current.authedV2().getAssetSource(GetAssetSourceRequest.newBuilder().setAssetId(assetId).setOwnerRef(ownerId).build())
            val manifest = response.latestRun.takeIf { it.status == ProcessingStatus.PROCESSING_STATUS_READY }?.let { run ->
                current.authedV2().getDeliveryManifest(GetDeliveryManifestRequest.newBuilder().setAssetId(assetId).setOwnerRef(ownerId).setGeneration(run.generation).build()).manifest
            }
            response.toPostAsset(manifest)
        }
    }

    fun getAssetsForOwner(ownerId: String, assetIds: List<String>): Map<String, PostAsset> {
        val ids = assetIds.map(String::trim).filter(String::isNotBlank).distinct()
        require(ids.size <= 50) { "At most 50 media assets can be read at once" }
        if (ids.isEmpty()) return emptyMap()
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia {
            val response = current.authedV2().batchGetAssetSources(
                BatchGetAssetSourcesRequest.newBuilder().setOwnerRef(ownerId).addAllAssetIds(ids).build()
            )
            response.sourcesList.associate { source ->
                val assetId = source.source.assetId
                val manifest = source.latestRun.takeIf { source.hasLatestRun() && it.status == ProcessingStatus.PROCESSING_STATUS_READY }?.let { run ->
                    current.authedV2().getDeliveryManifest(
                        GetDeliveryManifestRequest.newBuilder().setAssetId(assetId).setOwnerRef(ownerId).setGeneration(run.generation).build()
                    ).manifest
                }
                assetId to source.toPostAsset(manifest)
            }
        }
    }

    fun retryAssetProcessing(ownerId: String, assetId: String): PostAsset {
        require(ownerId.isNotBlank() && assetId.isNotBlank()) { "Asset identifier is required" }
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia {
            val source = current.authedV2().getAssetSource(GetAssetSourceRequest.newBuilder().setOwnerRef(ownerId).setAssetId(assetId).build())
            if (source.hasLatestRun()) {
                current.authedV2().retryProcessing(RetryProcessingRequest.newBuilder().setOwnerRef(ownerId).setRunId(source.latestRun.runId).setIdempotencyKey("retry-$assetId-${System.nanoTime()}").build())
            } else {
                val kind = when (source.source.kind) {
                    MediaKind.MEDIA_KIND_VIDEO -> PostAssetKind.VIDEO
                    MediaKind.MEDIA_KIND_AUDIO -> PostAssetKind.AUDIO
                    else -> PostAssetKind.IMAGE
                }
                current.authedV2().requestProcessing(RequestProcessingRequest.newBuilder().setOwnerRef(ownerId).setAssetId(assetId).setPipelineId(kind.pipelineId()).setIdempotencyKey("process-$assetId-${System.nanoTime()}").build())
            }
            getAssetForOwner(ownerId, assetId)
        }
    }

    fun requestProcessing(ownerId: String, assetId: String, kind: PostAssetKind, idempotencyKey: String): RequestedMediaProcessing {
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        val run = callMedia { current.authedV2().requestProcessing(RequestProcessingRequest.newBuilder()
            .setOwnerRef(ownerId).setAssetId(assetId).setPipelineId(kind.pipelineId()).setIdempotencyKey(idempotencyKey).build()).run }
        return RequestedMediaProcessing(run.runId, run.generation)
    }

    fun cancelProcessing(ownerId: String, runId: String) {
        val current = assetStub ?: return
        callMedia { current.authedV2().cancelProcessing(CancelProcessingRequest.newBuilder().setOwnerRef(ownerId).setRunId(runId).build()) }
    }

    fun resolveDelivery(ownerId: String, assetId: String, generation: Long, variantName: String): String {
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia { current.authedV2().resolveDelivery(ResolveDeliveryRequest.newBuilder().setOwnerRef(ownerId).setAssetId(assetId).setGeneration(generation).setVariantName(variantName).build()).url }
            .ifBlank { throw MediaUnavailable("media returned an empty delivery URL") }
    }

    fun resolveSource(ownerId: String, assetId: String): String {
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia { current.authedV2().resolveSource(ResolveSourceRequest.newBuilder().setOwnerRef(ownerId).setAssetId(assetId).build()).url }
            .ifBlank { throw MediaUnavailable("media returned an empty source URL") }
    }

    fun releaseSource(ownerId: String, assetId: String, generation: Long) {
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        callMedia { current.authedV2().releaseSource(ReleaseSourceRequest.newBuilder().setOwnerRef(ownerId).setAssetId(assetId).setGeneration(generation).build()) }
    }

    fun listAssetLifecycleEvents(afterSequence: Long, limit: Int = 100): List<MediaLifecycleEvent> {
        val current = assetStub ?: throw MediaUnavailable("media gRPC target is not configured")
        return callMedia {
            current.authedV2().listLifecycleEvents(
                ListLifecycleEventsRequest.newBuilder()
                    .setAfterSequence(afterSequence.coerceAtLeast(0))
                    .setLimit(limit.coerceIn(1, 500))
                    .build()
            ).eventsList.map { event ->
                MediaLifecycleEvent(
                    sequence = event.sequence,
                    eventId = event.eventId,
                    type = event.type,
                    assetId = event.assetId,
                    generation = event.generation,
                    ownerKey = event.ownerRef,
                    failureCode = event.failureCode
                )
            }
        }
    }

    /**
     * MediaStore validates the active owner through owner_key. A missing/failed
     * lookup deliberately returns null so Content refuses publication rather
     * than trusting client-supplied READY state.
     */
    fun assetStatusForOwner(ownerId: String, assetId: String): MediaAssetStatus? {
        return runCatching {
            getAssetForOwner(ownerId, assetId).status
        }.getOrNull()
    }

    override fun close() {
        channel?.shutdown()
    }

    private fun MediaStoreGrpc.MediaStoreBlockingStub.authed(ownerId: String? = null): MediaStoreGrpc.MediaStoreBlockingStub {
        val headers = Metadata().apply {
            put(AUTHORIZATION_KEY, "Bearer $apiKey")
            put(SERVICE_KEY, "content")
            ownerId?.takeIf(String::isNotBlank)?.let { put(USER_KEY, it) }
        }
        return withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }

    private fun MediaAssetsGrpc.MediaAssetsBlockingStub.authedV2(): MediaAssetsGrpc.MediaAssetsBlockingStub {
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
        const val MAX_ASSET_UPLOAD_BYTES = 5L * 1024 * 1024 * 1024
        const val MAX_ASSET_PARTS = 10_000
        val SOURCE_POLICIES = setOf("browser-native-v1", "browser-capture-v1")
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        val SERVICE_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-onix-service", Metadata.ASCII_STRING_MARSHALLER)
        val USER_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER)
    }
}

private fun PostAssetKind.toProcessingProfile(): ProcessingProfile = when (this) {
    PostAssetKind.IMAGE -> ProcessingProfile.PROCESSING_PROFILE_CONTENT_IMAGE
    PostAssetKind.VIDEO -> ProcessingProfile.PROCESSING_PROFILE_CONTENT_VIDEO
    PostAssetKind.AUDIO -> ProcessingProfile.PROCESSING_PROFILE_CONTENT_AUDIO
}

private fun PostAssetKind.toMediaKind(): MediaKind = when (this) {
    PostAssetKind.IMAGE -> MediaKind.MEDIA_KIND_IMAGE
    PostAssetKind.VIDEO -> MediaKind.MEDIA_KIND_VIDEO
    PostAssetKind.AUDIO -> MediaKind.MEDIA_KIND_AUDIO
}

private fun PostAssetKind.pipelineId(): String = when (this) {
    PostAssetKind.IMAGE -> "image-responsive-web-v1"
    PostAssetKind.VIDEO -> "video-web-1080-v1"
    PostAssetKind.AUDIO -> "audio-web-v1"
}

private fun com.onix.media.v2.BeginAssetUploadResponse.toPostAsset(): PostAsset =
    com.onix.media.v2.AssetSourceResponse.newBuilder().setSource(source).build().toPostAsset()

private fun com.onix.media.v2.AssetSourceResponse.toPostAsset(
    manifest: com.onix.media.v2.DeliveryManifest? = null
): PostAsset {
    val run = latestRun.takeIf { hasLatestRun() }
    val kind = when (source.kind) {
        MediaKind.MEDIA_KIND_VIDEO -> PostAssetKind.VIDEO
        MediaKind.MEDIA_KIND_AUDIO -> PostAssetKind.AUDIO
        else -> PostAssetKind.IMAGE
    }
    val status = if (source.status == SourceStatus.SOURCE_STATUS_REJECTED) {
        MediaAssetStatus.FAILED
    } else when (run?.status) {
        ProcessingStatus.PROCESSING_STATUS_WAITING_SOURCE -> MediaAssetStatus.VERIFYING
        ProcessingStatus.PROCESSING_STATUS_QUEUED,
        ProcessingStatus.PROCESSING_STATUS_PROCESSING -> MediaAssetStatus.PROCESSING
        ProcessingStatus.PROCESSING_STATUS_READY -> MediaAssetStatus.READY
        ProcessingStatus.PROCESSING_STATUS_FAILED -> MediaAssetStatus.FAILED
        ProcessingStatus.PROCESSING_STATUS_CANCELLED -> MediaAssetStatus.CANCELLED
        else -> when (source.status) {
            SourceStatus.SOURCE_STATUS_UPLOADING -> MediaAssetStatus.UPLOADING
            SourceStatus.SOURCE_STATUS_VERIFYING -> MediaAssetStatus.VERIFYING
            SourceStatus.SOURCE_STATUS_AVAILABLE -> MediaAssetStatus.AVAILABLE
            SourceStatus.SOURCE_STATUS_REJECTED -> MediaAssetStatus.FAILED
            else -> MediaAssetStatus.FAILED
        }
    }
    return PostAsset(
        id = source.assetId,
        kind = kind,
        sourceKind = PostAssetSourceKind.UPLOAD,
        assetId = source.assetId,
        status = status,
        sourceStatus = when (source.status) {
            SourceStatus.SOURCE_STATUS_UPLOADING -> MediaSourceStatus.UPLOADING
            SourceStatus.SOURCE_STATUS_VERIFYING -> MediaSourceStatus.VERIFYING
            SourceStatus.SOURCE_STATUS_AVAILABLE -> MediaSourceStatus.AVAILABLE
            SourceStatus.SOURCE_STATUS_REJECTED -> MediaSourceStatus.REJECTED
            else -> null
        },
        processingStatus = when (run?.status) {
            ProcessingStatus.PROCESSING_STATUS_WAITING_SOURCE -> MediaProcessingStatus.WAITING_SOURCE
            ProcessingStatus.PROCESSING_STATUS_QUEUED -> MediaProcessingStatus.QUEUED
            ProcessingStatus.PROCESSING_STATUS_PROCESSING -> MediaProcessingStatus.PROCESSING
            ProcessingStatus.PROCESSING_STATUS_READY -> MediaProcessingStatus.READY
            ProcessingStatus.PROCESSING_STATUS_FAILED -> MediaProcessingStatus.FAILED
            ProcessingStatus.PROCESSING_STATUS_CANCELLED -> MediaProcessingStatus.CANCELLED
            else -> MediaProcessingStatus.NONE
        },
        deliveryStatus = if (run?.status == ProcessingStatus.PROCESSING_STATUS_READY && manifest != null) MediaDeliveryStatus.READY else MediaDeliveryStatus.NONE,
        variants = manifest?.variantsList.orEmpty().map { variant ->
            AssetVariant(name = variant.name, width = variant.width.takeIf { it > 0 }, height = variant.height.takeIf { it > 0 }, mimeType = variant.mimeType)
        },
        // Source geometry is known during verification, before conversion is
        // requested. This keeps an authored layout stable when variants later
        // become READY. Older Media v2 servers still fall back to manifest.
        width = source.width.takeIf { it > 0 }
            ?: manifest?.variantsList?.maxByOrNull { it.width }?.width?.takeIf { it > 0 },
        height = source.height.takeIf { it > 0 }
            ?: manifest?.variantsList?.maxByOrNull { it.height }?.height?.takeIf { it > 0 },
        durationMs = source.durationMs.takeIf { it > 0 }
            ?: manifest?.variantsList?.maxOfOrNull { it.durationMs }?.takeIf { it > 0 },
        failureReason = (run?.failureCode ?: source.failureCode).takeIf(String::isNotBlank),
        failure = (run?.failureCode ?: source.failureCode).takeIf(String::isNotBlank)?.let(::mediaFailure),
        generation = run?.generation,
        processingRunId = run?.runId,
        deliveryContract = "STABLE_V2"
    )
}

private fun mediaFailure(code: String): MediaFailure {
    val permanent = code in setOf(
        "UNSUPPORTED_OR_MALFORMED_MEDIA", "SOURCE_REJECTED", "UNSUPPORTED_CODEC",
        "PIXEL_LIMIT_EXCEEDED", "DURATION_LIMIT_EXCEEDED", "INVALID_PIPELINE"
    )
    val message = when (code) {
        "UNSUPPORTED_OR_MALFORMED_MEDIA", "SOURCE_REJECTED" -> "Файл повреждён или имеет неподдерживаемый формат"
        "UNSUPPORTED_CODEC" -> "Кодек файла не поддерживается"
        "PIXEL_LIMIT_EXCEEDED" -> "Изображение имеет слишком большое разрешение"
        "DURATION_LIMIT_EXCEEDED" -> "Медиа превышает допустимую длительность"
        "TIMEOUT" -> "Обработка заняла слишком много времени"
        "STORAGE_UNAVAILABLE" -> "Хранилище временно недоступно"
        else -> "Не удалось подготовить медиа"
    }
    return MediaFailure(code = code, permanent = permanent, userMessage = message)
}

private fun com.onix.media.v1.MediaAsset.toPostAsset(): PostAsset = PostAsset(
    id = assetId,
    kind = when (profile) {
        ProcessingProfile.PROCESSING_PROFILE_CONTENT_VIDEO -> PostAssetKind.VIDEO
        ProcessingProfile.PROCESSING_PROFILE_CONTENT_AUDIO -> PostAssetKind.AUDIO
        else -> PostAssetKind.IMAGE
    },
    sourceKind = PostAssetSourceKind.UPLOAD,
    assetId = assetId,
    status = when (status) {
        AssetStatus.ASSET_STATUS_UPLOADING -> MediaAssetStatus.UPLOADING
        AssetStatus.ASSET_STATUS_PROCESSING -> MediaAssetStatus.PROCESSING
        AssetStatus.ASSET_STATUS_READY -> MediaAssetStatus.READY
        AssetStatus.ASSET_STATUS_FAILED -> MediaAssetStatus.FAILED
        else -> MediaAssetStatus.FAILED
    },
    variants = variantsList.mapNotNull { variant ->
        variant.url.takeIf(String::isNotBlank)?.let { url ->
            AssetVariant(
                url = url,
                width = variant.width.takeIf { it > 0 },
                height = variant.height.takeIf { it > 0 },
                mimeType = variant.mimeType.takeIf(String::isNotBlank)
            )
        }
    },
    posterUrl = poster.url.takeIf(String::isNotBlank),
    waveformUrl = waveform.url.takeIf(String::isNotBlank),
    width = width.takeIf { it > 0 },
    height = height.takeIf { it > 0 },
    durationMs = durationMs.takeIf { it > 0 },
    failureReason = failureReason.takeIf(String::isNotBlank)
)

class MediaUnavailable(message: String) : RuntimeException(message)

/**
 * gRPC exposes multipart destinations as a map. Do not leak that shape to
 * the browser: JSON maps have no `length`, while the upload client needs a
 * stable, ordered target for every requested part.
 */
internal fun orderedAssetUploadTargets(
    parts: Map<Int, String>,
    expectedParts: Int
): List<AssetUploadTarget> {
    if (expectedParts < 1) throw MediaUnavailable("media upload requested no parts")
    return (1..expectedParts).map { partNumber ->
        val url = parts[partNumber]?.trim()
        if (url.isNullOrEmpty()) {
            throw MediaUnavailable("media gRPC did not return upload URL for part $partNumber")
        }
        AssetUploadTarget(partNumber = partNumber, url = url)
    }
}

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
