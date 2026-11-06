package com.youloft.linkup.location

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Provider selection is the one place that answers "which SDK is in this build", so its rules are pinned
 * here rather than left to the call sites: list order is the priority order, `isInstalled` is the only gate
 * that counts, ids are unique, and an empty candidate list is a configuration error rather than a null.
 */
class LocationProvidersTest {

    private val system = StubProvider("system", CoordSystem.WGS84, inlinePlace = false, poiList = false)
    private val baidu = StubProvider("baidu", CoordSystem.BD09, inlinePlace = true, poiList = true)
    private val amap = StubProvider("amap", CoordSystem.GCJ02, inlinePlace = true, poiList = true)

    @Test
    fun `preferred takes the first installed candidate in list order`() {
        val both = LocationProviders(listOf(baidu, system))
        assertEquals(baidu, both.preferred(), "Baidu first because that is what shipped")

        val frameworkOnly = LocationProviders(listOf(StubProvider("baidu", CoordSystem.BD09, installed = false), system))
        assertEquals(system, frameworkOnly.preferred(), "the keyless build degrades to the framework")

        val nothing = LocationProviders(listOf(StubProvider("baidu", CoordSystem.BD09, installed = false)))
        assertNull(nothing.preferred(), "no source at all is a real state the UI has to show")
    }

    @Test
    fun `installed keeps the order and only the candidates that can run`() {
        val providers = LocationProviders(listOf(baidu, StubProvider("system", CoordSystem.WGS84, installed = false), amap))
        amap.installed = false

        assertEquals(listOf("baidu"), providers.installed().map { it.id }, "checked at call time, not at build time")
        assertEquals(baidu, providers.preferred())
        assertTrue(system in LocationProviders(listOf(baidu, system)).installed())
    }

    @Test
    fun `find answers by id whether the candidate is installed or not`() {
        val providers = LocationProviders(listOf(baidu, system))

        assertEquals(baidu, providers.find("baidu"))
        assertEquals(system, providers.find("system"))
        assertNull(providers.find("huawei"), "an id this build never had is not an error")
        assertNull(providers.find(""))
        assertEquals("baidu", assertNotNull(providers.preferred()).id)
    }

    @Test
    fun `describe lists every candidate with the capabilities a status screen shows`() {
        val providers = LocationProviders(listOf(baidu, system))

        val text = providers.describe()
        val lines = text.split("; ")
        assertEquals(2, lines.size, text)
        assertTrue(lines[0].startsWith("baidu installed=true"), lines[0])
        assertTrue(lines[1].startsWith("system installed=true"), lines[1])

        // One line per candidate, installed or not, in list order: that is what makes an on-device vendor
        // comparison possible without adding a second reporting path.
        assertEquals(
            "baidu installed=true datum=BD09 inlinePlace=true poi=true runtimeInterval=true; " +
                "system installed=true datum=WGS84 inlinePlace=false poi=false runtimeInterval=false",
            text,
        )

        val degraded = LocationProviders(listOf(StubProvider("baidu", CoordSystem.BD09, installed = false), system))
        assertTrue(degraded.describe().contains("baidu installed=false"), degraded.describe())
    }

    @Test
    fun `an empty candidate list is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> { LocationProviders(emptyList()) }
        assertTrue(
            assertNotNull(failure.message).contains("no candidates"),
            "the message has to say why: ${failure.message}",
        )
    }

    @Test
    fun `duplicate provider ids are refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LocationProviders(listOf(system, StubProvider("system", CoordSystem.BD09, installed = false)))
        }
        assertTrue(assertNotNull(failure.message).contains("unique"), failure.message ?: "")
        assertTrue(failure.message!!.contains("system"), "and it names the ids it was given: ${failure.message}")
    }

    @Test
    fun `the order of the list is the only priority rule`() {
        // Same two candidates, opposite order, opposite answer: nothing else decides.
        assertEquals(baidu, LocationProviders(listOf(baidu, system)).preferred())
        assertEquals(system, LocationProviders(listOf(system, baidu)).preferred())
        assertEquals(
            listOf("amap", "baidu", "system"),
            LocationProviders(listOf(amap, baidu, system)).installed().map { it.id },
        )
    }

    /** A candidate with no Android and no SDK behind it, i.e. exactly what this seam needs. */
    private class StubProvider(
        override val id: String,
        datum: CoordSystem,
        val inlinePlace: Boolean = true,
        val poiList: Boolean = true,
        var installed: Boolean = true,
    ) : LocationProvider {
        override val capabilities = LocationCapabilities(
            nativeCoordSystem = datum,
            inlinePlace = inlinePlace,
            poiList = poiList,
            needsVendorKey = id != "system",
            supportsRuntimeInterval = id == "baidu",
        )
        override val isInstalled: Boolean get() = installed
        override fun start(request: LocationRequest, listener: LocationListener) = installed
        override fun update(request: LocationRequest) = false
        override fun stop() = Unit
        override suspend fun singleFix(request: LocationRequest): LocationResult =
            LocationResult.Failed(LocationFault(LocationFailure.SERVICE_UNAVAILABLE))
        override fun lastFix(): LocationFix? = null
        override fun close() = Unit
    }
}
