package com.onix.content.media

import com.onix.content.domain.InitAssetUploadResponse
import com.onix.content.domain.MediaAssetStatus
import com.onix.content.domain.PostAsset
import com.onix.content.domain.PostAssetKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaUploadResponseTest {
    @Test
    fun `asset upload response exposes ordered JSON part targets rather than a map`() {
        val parts = orderedAssetUploadTargets(
            mapOf(2 to "https://media.example.test/upload/2", 1 to "https://media.example.test/upload/1"),
            expectedParts = 2
        )
        val response = InitAssetUploadResponse(
            asset = PostAsset(
                id = "asset-1",
                assetId = "asset-1",
                kind = PostAssetKind.IMAGE,
                status = MediaAssetStatus.UPLOADING
            ),
            sessionId = "session-1",
            parts = parts
        )

        val json = Json.parseToJsonElement(Json.encodeToString(response)).jsonObject
        val targets = json.getValue("parts").jsonArray

        assertEquals(2, targets.size)
        assertEquals(1, targets[0].jsonObject.getValue("partNumber").jsonPrimitive.content.toInt())
        assertEquals("https://media.example.test/upload/1", targets[0].jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals(2, targets[1].jsonObject.getValue("partNumber").jsonPrimitive.content.toInt())
    }

    @Test
    fun `missing signed part is rejected before returning a broken browser session`() {
        val error = assertFailsWith<MediaUnavailable> {
            orderedAssetUploadTargets(mapOf(1 to "https://media.example.test/upload/1"), expectedParts = 2)
        }

        assertEquals("media gRPC did not return upload URL for part 2", error.message)
    }
}
