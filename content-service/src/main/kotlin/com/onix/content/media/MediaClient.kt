package com.onix.content.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class MediaReferenceRequest(val reference_type: String, val reference_id: String)

@Serializable
data class InitUploadRequest(val mimeType: String, val expectedSize: Long, val partsCount: Int = 1)

@Serializable
data class InitUploadResponse(val sessionId: String, val parts: Map<Int, String>)

@Serializable
data class CompleteUploadRequest(val parts: List<CompleteUploadPart>)

@Serializable
data class CompleteUploadPart(val partNumber: Int, val etag: String)

@Serializable
data class UploadSessionResponse(val status: String, val blobId: String? = null)

@Serializable
data class DownloadUrlResponse(val url: String)

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

class MediaClient(baseUrl: String, private val apiKey: String) {
    private val http = HttpClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val apiBaseUrl = baseUrl.trimEnd('/').let { url ->
        if (url.endsWith("/v1")) url else "$url/v1"
    }

    fun upload(fileName: String, mimeType: String, bytes: ByteArray): UploadedMedia {
        val init = initUpload(mimeType.ifBlank { "application/octet-stream" }, bytes.size.toLong())
        val uploadUrl = init.parts[1] ?: throw MediaUnavailable("media-service did not return part 1 upload URL")
        val etag = uploadPart(uploadUrl, mimeType, bytes)
        completeUpload(init.sessionId, etag)
        val blobId = waitForBlob(init.sessionId)
        return UploadedMedia(
            fileName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            size = bytes.size.toLong(),
            blobId = blobId
        )
    }

    fun createReference(blobId: String, ownerType: String, ownerId: String) {
        val body = json.encodeToString(MediaReferenceRequest.serializer(), MediaReferenceRequest(ownerType, ownerId))
        val request = HttpRequest.newBuilder(URI.create("$apiBaseUrl/blobs/$blobId/references"))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Service-Name", "content-service")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media-service reference returned ${response.statusCode()}")
        }
    }

    fun download(blobId: String): DownloadedMedia {
        val downloadUrl = downloadUrl(blobId)
        val request = HttpRequest.newBuilder(URI.create(downloadUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media-service blob download returned ${response.statusCode()}")
        }
        return DownloadedMedia(
            bytes = response.body(),
            mimeType = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
        )
    }

    private fun downloadUrl(blobId: String): String {
        val request = authed(URI.create("$apiBaseUrl/blobs/$blobId/download-url"))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media-service download URL returned ${response.statusCode()}")
        }
        return json.decodeFromString(DownloadUrlResponse.serializer(), response.body()).url
    }

    private fun initUpload(mimeType: String, size: Long): InitUploadResponse {
        val body = json.encodeToString(InitUploadRequest(mimeType = mimeType, expectedSize = size))
        val request = authed(URI.create("$apiBaseUrl/uploads/init"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media-service upload init returned ${response.statusCode()}")
        }
        return json.decodeFromString(InitUploadResponse.serializer(), response.body())
    }

    private fun uploadPart(url: String, mimeType: String, bytes: ByteArray): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", mimeType.ifBlank { "application/octet-stream" })
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media-service presigned upload returned ${response.statusCode()}")
        }
        return response.headers().firstValue("ETag").orElseThrow {
            MediaUnavailable("media-service presigned upload did not return ETag")
        }
    }

    private fun completeUpload(sessionId: String, etag: String) {
        val body = json.encodeToString(CompleteUploadRequest(listOf(CompleteUploadPart(partNumber = 1, etag = etag))))
        val request = authed(URI.create("$apiBaseUrl/uploads/$sessionId/complete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw MediaUnavailable("media-service upload complete returned ${response.statusCode()}")
        }
    }

    private fun waitForBlob(sessionId: String): String {
        repeat(20) {
            val request = authed(URI.create("$apiBaseUrl/uploads/$sessionId")).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw MediaUnavailable("media-service upload poll returned ${response.statusCode()}")
            }
            val session = json.decodeFromString(UploadSessionResponse.serializer(), response.body())
            if (session.status == "COMPLETED" && !session.blobId.isNullOrBlank()) {
                return session.blobId
            }
            Thread.sleep(500)
        }
        throw MediaUnavailable("media-service upload did not complete in time")
    }

    private fun authed(uri: URI): HttpRequest.Builder =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Service-Name", "content-service")
}

class MediaUnavailable(message: String) : RuntimeException(message)
