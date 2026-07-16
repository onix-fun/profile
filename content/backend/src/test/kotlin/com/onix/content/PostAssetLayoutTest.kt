package com.onix.content

import com.onix.content.domain.*
import com.onix.content.service.normalizeAndValidateProjectLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostAssetLayoutTest {
    private fun asset(id: String, x: Int, y: Int) = PostAsset(
        id=id, assetId=id, kind=PostAssetKind.IMAGE, sourceKind=PostAssetSourceKind.UPLOAD,
        status=MediaAssetStatus.READY, width=1200, height=800,
        layout=PostAssetLayout(id,x,y,AssetSizePreset.M)
    )

    @Test fun `project media cannot overlap`() {
        assertFailsWith<IllegalArgumentException> { normalizeAndValidateProjectLayout(listOf(asset("one",0,0),asset("two",100,100))) }
    }

    @Test fun `legacy project fallback is collision free`() {
        val assets=(0 until 12).map { index -> asset("asset-$index",0,0).copy(layout=null,kind=if(index%3==0)PostAssetKind.AUDIO else PostAssetKind.IMAGE) }
        val normalized=normalizeAndValidateProjectLayout(assets)
        assertEquals(12,normalized.size)
    }
}
