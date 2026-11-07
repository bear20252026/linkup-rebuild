package com.youloft.linkup.map

import android.content.Context
import android.view.View
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * Map provider selection, the location layer's own rules restated for the map seam: chain order decides,
 * the installed check is each provider's own answer, and an empty installed set means "keep the mask".
 */
class TrackMapProvidersTest {

    @Test
    fun `the first installed candidate wins the rest stay as fallbacks`() {
        val providers = TrackMapProviders(listOf(Fake("baidu", false), Fake("amap", true), Fake("system", true)))
        assertEquals("amap", providers.preferred()?.id)
        assertEquals(listOf("amap", "system"), providers.installed().map { it.id })
        assertEquals("baidu", providers.find("baidu")?.id, "find reaches candidates regardless of install state")
        assertNull(providers.find("tencent"))
    }

    @Test
    fun `nothing installed means no basemap`() {
        val providers = TrackMapProviders(listOf(Fake("baidu", false), Fake("amap", false)))
        assertNull(providers.preferred(), "the screen keeps its 尚未接入 state; no vendor view is ever built")
    }

    @Test
    fun `duplicate ids are a wiring mistake and fail loudly`() {
        val error = kotlin.runCatching {
            TrackMapProviders(listOf(Fake("baidu", true), Fake("baidu", false)))
        }.exceptionOrNull()
        assertEquals("provider ids must be unique, got [baidu, baidu]", error?.message)
    }

    private class Fake(override val id: String, override val isInstalled: Boolean) : TrackMapProvider {
        override fun create(context: Context): TrackMap = object : TrackMap {
            override val view: View get() = throw UnsupportedOperationException()
            override val id: String = this@Fake.id
            override fun onCreate(state: android.os.Bundle?) = Unit
            override fun onResume() = Unit
            override fun onPause() = Unit
            override fun onDestroy() = Unit
            override fun onSaveState(): android.os.Bundle? = null
            override fun showTrack(overlay: TrackOverlay) = Unit
            override fun focus(coord: MapLatLng) = Unit
        }
    }
}
