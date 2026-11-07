package com.youloft.watcher.data

import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.LocationCapabilities
import com.youloft.linkup.location.LocationFault
import com.youloft.linkup.location.LocationFailure
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationListener
import com.youloft.linkup.location.LocationProvider
import com.youloft.linkup.location.LocationProviders
import com.youloft.linkup.location.LocationRequest
import com.youloft.linkup.location.LocationResult
import com.youloft.watcher.data.local.InMemoryKeyValueStores
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * The provider-catalog selection: which of the configured candidates actually takes the job, and how the
 * `locationProviderId` setting overrides the chain without being able to break it.
 *
 * Uses hand-written provider fakes rather than the real adapters, because what is under test here is the
 * *selection rule*, not any vendor binding — those have their own suites in `:location`.
 */
class LocationProviderSelectionTest {

    @Test
    fun `a configured provider wins only when it can actually run`() {
        val providers = LocationProviders(
            listOf(Fake("baidu", false), Fake("amap", false), Fake("system", true)),
        )

        assertEquals(
            "system",
            providers.preferredProvider(null)?.id,
            "no preference: the chain's own order decides",
        )
        assertEquals(
            "system",
            providers.preferredProvider("amap")?.id,
            "a preference for a provider missing its AAR must degrade to the chain, not take the app down",
        )
        assertEquals(
            "amap",
            LocationProviders(listOf(Fake("baidu", false), Fake("amap", true), Fake("system", false)))
                .preferredProvider("amap")
                ?.id,
            "a preference for an installed provider beats the chain order",
        )
    }

    @Test
    fun `an unknown configured id falls through to the chain`() {
        val providers = LocationProviders(listOf(Fake("baidu", false), Fake("system", true)))
        assertEquals("system", providers.preferredProvider("tencent")?.id)
    }

    @Test
    fun `no candidate can run means null, whoever is configured`() {
        val providers = LocationProviders(listOf(Fake("baidu", false), Fake("amap", false)))
        assertNull(providers.preferredProvider("baidu"))
        assertNull(providers.preferredProvider(null))
    }

    @Test
    fun `the settings key round-trips and blank means no preference`() {
        val settings = LocationProviderSettings(InMemoryKeyValueStores().shared)
        assertNull(settings.preferredId, "fresh store: the chain order decides")

        settings.setPreferred("amap")
        assertEquals("amap", settings.preferredId)

        settings.setPreferred("")
        assertNull(settings.preferredId, "blank is stored as nothing, not as an id that can never match")
    }

    /** Minimal provider double: only the identity and the install answer matter here. */
    private class Fake(override val id: String, override val isInstalled: Boolean) : LocationProvider {
        override val capabilities = LocationCapabilities(
            nativeCoordSystem = CoordSystem.WGS84,
            inlinePlace = false,
            poiList = false,
            needsVendorKey = false,
            supportsRuntimeInterval = false,
        )

        override fun start(request: LocationRequest, listener: LocationListener): Boolean = isInstalled

        override fun update(request: LocationRequest): Boolean = false

        override fun stop() = Unit

        override suspend fun singleFix(request: LocationRequest): LocationResult =
            LocationResult.Failed(LocationFault(LocationFailure.SERVICE_UNAVAILABLE))

        override fun lastFix(): LocationFix? = null

        override fun close() = Unit
    }
}
