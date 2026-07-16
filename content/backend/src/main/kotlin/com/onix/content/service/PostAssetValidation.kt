package com.onix.content.service

import com.onix.content.domain.MediaAssetStatus
import com.onix.content.domain.AssetSizePreset
import com.onix.content.domain.PostAsset
import com.onix.content.domain.PostAssetLayout
import com.onix.content.domain.PostAssetKind
import com.onix.content.domain.PostAssetSourceKind
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canonical asset lookup supplied by MediaStore. Content never trusts a URL,
 * variant, kind, or processing state provided by the browser for a project.
 */
fun interface UploadedAssetVerifier {
    fun asset(owner: String, assetId: String): PostAsset?

    fun assets(owner: String, assetIds: List<String>): Map<String, PostAsset> =
        assetIds.distinct().mapNotNull { id -> asset(owner, id)?.let { id to it } }.toMap()

    fun status(owner: String, assetId: String): MediaAssetStatus? =
        asset(owner, assetId)?.status

    companion object {
        /** Useful only in isolated domain tests; production wires MediaClient. */
        fun permissive(): UploadedAssetVerifier = UploadedAssetVerifier { _, assetId ->
            PostAsset(
                id = assetId,
                kind = PostAssetKind.IMAGE,
                sourceKind = PostAssetSourceKind.UPLOAD,
                assetId = assetId,
                status = MediaAssetStatus.READY
            )
        }
    }
}

data class RequestedMediaProcessing(val runId: String, val generation: Long)

interface MediaAssetProcessor {
    fun request(owner: String, assetId: String, kind: PostAssetKind, idempotencyKey: String): RequestedMediaProcessing
    fun cancel(owner: String, runId: String) {}
    fun releaseSource(owner: String, assetId: String, generation: Long) {}

    companion object {
        fun noop(): MediaAssetProcessor = object : MediaAssetProcessor {
            override fun request(owner: String, assetId: String, kind: PostAssetKind, idempotencyKey: String) =
                RequestedMediaProcessing(runId = "$assetId-noop", generation = 1)
        }
    }
}

data class ProjectAssetBox(val x: Int, val y: Int, val width: Int, val height: Int)

fun normalizeAndValidateProjectLayout(assets: List<PostAsset>): List<PostAsset> {
    val normalized = assets.mapIndexed { index, asset ->
        val layout = asset.layout?.copy(assetId = asset.assetId ?: asset.layout.assetId)
            ?: defaultProjectAssetLayout(asset, index)
        require(layout.layoutVersion == 1) { "Project layout version is not supported" }
        asset.copy(layout = layout)
    }
    val boxes = normalized.map { asset -> asset to projectAssetBox(asset) }
    boxes.forEach { (_, box) ->
        require(box.x >= PROJECT_WORLD_MIN && box.y >= PROJECT_WORLD_MIN && box.x + box.width <= PROJECT_WORLD_MAX && box.y + box.height <= PROJECT_WORLD_MAX) {
            "Project media must stay inside the canvas"
        }
    }
    boxes.forEachIndexed { index, (asset, box) ->
        boxes.drop(index + 1).forEach { (other, otherBox) ->
            require(!projectBoxesOverlap(box, otherBox, PROJECT_ASSET_GAP)) {
                "Project media ${asset.id} overlaps ${other.id}"
            }
        }
    }
    return normalized
}

/** Repairs geometry that became stale after Media discovered canonical source
 * dimensions. Valid authored positions are preserved; only the first item that
 * no longer fits is moved to a deterministic nearby free slot. */
fun repairProjectLayout(assets: List<PostAsset>): List<PostAsset> {
    val normalized = assets.mapIndexed { index, asset ->
        asset.copy(layout = asset.layout?.copy(assetId = asset.assetId ?: asset.layout.assetId)
            ?: defaultProjectAssetLayout(asset, index))
    }
    val placed = mutableListOf<PostAsset>()
    normalized.forEachIndexed { index, asset ->
        val box = projectAssetBox(asset)
        val valid = projectBoxWithinWorld(box) && placed.none { projectBoxesOverlap(box, projectAssetBox(it), PROJECT_ASSET_GAP) }
        if (valid) {
            placed += asset
        } else {
            placed += asset.copy(layout = nearestFreeProjectLayout(asset, placed, index))
        }
    }
    return normalizeAndValidateProjectLayout(placed)
}

fun projectAssetBox(asset: PostAsset): ProjectAssetBox {
    val layout = requireNotNull(asset.layout) { "Project media layout is required" }
    if (asset.kind == PostAssetKind.AUDIO) {
        val (width, height) = when (layout.sizePreset) {
            AssetSizePreset.S -> 280 to 96
            AssetSizePreset.M -> 420 to 112
            AssetSizePreset.L -> 560 to 128
        }
        return ProjectAssetBox(layout.x, layout.y, width, height)
    }
    val edge = when (layout.sizePreset) {
        AssetSizePreset.S -> 240
        AssetSizePreset.M -> 360
        AssetSizePreset.L -> 520
    }
    val aspect = if ((asset.width ?: 0) > 0 && (asset.height ?: 0) > 0) {
        (asset.width!!.toDouble() / asset.height!!.toDouble()).coerceIn(.45, 2.6)
    } else if (asset.kind == PostAssetKind.VIDEO) 16.0 / 9.0 else 4.0 / 5.0
    val width = if (aspect >= 1) edge else (edge * aspect).toInt().coerceAtLeast(1)
    val height = if (aspect >= 1) (edge / aspect).toInt().coerceAtLeast(1) else edge
    return ProjectAssetBox(layout.x, layout.y, width, height)
}

private fun projectBoxesOverlap(left: ProjectAssetBox, right: ProjectAssetBox, gap: Int): Boolean =
    left.x < right.x + right.width + gap && left.x + left.width + gap > right.x &&
        left.y < right.y + right.height + gap && left.y + left.height + gap > right.y

private fun projectBoxWithinWorld(box: ProjectAssetBox): Boolean =
    box.x >= PROJECT_WORLD_MIN && box.y >= PROJECT_WORLD_MIN &&
        box.x + box.width <= PROJECT_WORLD_MAX && box.y + box.height <= PROJECT_WORLD_MAX

private fun nearestFreeProjectLayout(asset: PostAsset, placed: List<PostAsset>, seed: Int): PostAssetLayout {
    val preset = asset.layout?.sizePreset ?: AssetSizePreset.M
    repeat(1_800) { probe ->
        val ring = ceil(sqrt(probe.toDouble()))
        val angle = (seed * .83 + probe * (PI * (3.0 - sqrt(5.0)))) % (PI * 2)
        val candidate = PostAssetLayout(
            assetId = asset.assetId ?: asset.id,
            x = (cos(angle) * ring * 42).toInt(),
            y = (sin(angle) * ring * 42).toInt(),
            sizePreset = preset,
            layoutVersion = 1
        )
        val candidateBox = projectAssetBox(asset.copy(layout = candidate))
        if (projectBoxWithinWorld(candidateBox) && placed.none {
                projectBoxesOverlap(candidateBox, projectAssetBox(it), PROJECT_ASSET_GAP)
            }) return candidate
    }
    error("Could not repair project media layout")
}

private fun defaultProjectAssetLayout(asset: PostAsset, index: Int): PostAssetLayout = PostAssetLayout(
    assetId = asset.assetId ?: asset.id,
    x = (index % 4) * 600 - 900,
    y = (index / 4) * 600 - 600,
    sizePreset = AssetSizePreset.M
)

const val PROJECT_WORLD_MIN = -2048
const val PROJECT_WORLD_MAX = 2048
const val PROJECT_ASSET_GAP = 24
