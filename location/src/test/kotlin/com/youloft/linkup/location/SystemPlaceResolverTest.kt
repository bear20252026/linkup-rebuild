package com.youloft.linkup.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import androidx.test.core.app.ApplicationProvider
import com.youloft.linkup.location.provider.system.SystemPlaceResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowGeocoder

/**
 * The framework reverse-geocoder adapter: the fallback the recovered app never needed, because Baidu put an
 * address inside the fix (`setIsNeedAddress(true)`, `manager/x0.java:689`) and only reached for
 * `utils/j0.java`'s own GeoCoder SDK in the `x0.java:785-797` branch.
 *
 * Machine facts established against Robolectric 4.11.1 / SDK 34 (all of them observed, not assumed):
 *  * `Geocoder.isPresent()` is **true** by default, so [SystemPlaceResolver.isAvailable] is true;
 *  * `ShadowGeocoder` intercepts `getFromLocation(double, double, int)` and returns an empty list when
 *    nothing is scripted, so a blank answer is reachable;
 *  * `getFromLocationName` is NOT shadowed and throws `IOException: Service not Available`, which the
 *    adapter swallows into null -- so the forward direction cannot be positively tested here;
 *  * `setFromLocation` is an *instance* method on the shadow, so the result has to be scripted onto the very
 *    `Geocoder` the resolver built. That instance is private and there is no seam to inject one, so
 *    [geocoderOf] reads it reflectively: the mapping under test stays the production one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemPlaceResolverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var resolver: SystemPlaceResolver

    @Before
    fun setUp() {
        ShadowGeocoder.reset()
        resolver = SystemPlaceResolver(context)
    }

    @After
    fun tearDown() {
        // isPresent is static on the shadow; leave it the way Robolectric found it for the next test class.
        ShadowGeocoder.reset()
    }

    // ------------------------------------------------------------------ availability

    @Test
    fun `Geocoder is present under Robolectric so the resolver is available`() {
        assertTrue(Geocoder.isPresent(), "ShadowGeocoder's default is present in 4.11.1")
        assertTrue(resolver.isAvailable)
    }

    @Test
    fun `when the Geocoder is not present both directions answer null`() {
        ShadowGeocoder.setIsPresent(false)

        assertFalse(resolver.isAvailable)
        runBlocking {
            assertNull(resolver.resolvePlace(Coord(39.90469, 116.40717, CoordSystem.WGS84)))
            assertNull(resolver.resolveCoord("北京市", "长安街1号"))
        }
    }

    // ------------------------------------------------------------------ resolvePlace

    @Test
    fun `a scripted address lands on the six documented Place fields`() {
        val address = Address(Locale.SIMPLIFIED_CHINESE).apply {
            setLatitude(39.90469)
            setLongitude(116.40717)
            adminArea = "北京市"
            locality = "北京"
            subLocality = "东城区"
            thoroughfare = "长安街"
            subThoroughfare = "1号"
            setFeatureName("不该出现在这六个字段里的名字")
        }
        shadowOf(geocoderOf(resolver)).setFromLocation(listOf(address))

        val place = runBlocking { resolver.resolvePlace(Coord(39.90469, 116.40717, CoordSystem.WGS84)) }
        val resolved = assertNotNull(place)
        assertEquals("北京市", resolved.province, "adminArea -> province")
        assertEquals("北京", resolved.city, "locality -> city")
        assertEquals("东城区", resolved.district, "subLocality -> district")
        assertEquals("长安街", resolved.street, "thoroughfare -> street")
        assertEquals("1号", resolved.streetNumber, "subThoroughfare -> streetNumber")
        assertNull(resolved.poiName, "Address has no POI and the framework cannot invent one (Place.kt)")
        assertFalse(resolved.isBlank)
        // com.youloft.common.bean.Address has exactly these six keys and no full-address field; the server
        // composes the display string (server/internal/location/placeOf), so a seventh value must not leak in.
        assertEquals(
            Place(
                province = "北京市",
                city = "北京",
                district = "东城区",
                street = "长安街",
                streetNumber = "1号",
                poiName = null,
            ),
            resolved,
        )
    }

    @Test
    fun `locality falls back to adminArea when the address has no city`() {
        val address = Address(Locale.SIMPLIFIED_CHINESE).apply {
            setLatitude(39.9)
            setLongitude(116.4)
            adminArea = "北京市"
            subLocality = "东城区"
        }
        shadowOf(geocoderOf(resolver)).setFromLocation(listOf(address))

        val place = assertNotNull(runBlocking { resolver.resolvePlace(Coord(39.9, 116.4)) })
        assertEquals("北京市", place.province)
        assertEquals("北京市", place.city, "match.locality ?: match.adminArea")
        assertEquals("东城区", place.district)
        assertNull(place.street)
        assertNull(place.streetNumber)
    }

    @Test
    fun `the first match is the one that is used`() {
        val scripted = listOf(
            Address(Locale.SIMPLIFIED_CHINESE).apply {
                adminArea = "第一省"
                locality = "第一市"
            },
            Address(Locale.SIMPLIFIED_CHINESE).apply {
                adminArea = "第二省"
                locality = "第二市"
            },
        )
        shadowOf(geocoderOf(resolver)).setFromLocation(scripted)

        val place = assertNotNull(runBlocking { resolver.resolvePlace(Coord(39.9, 116.4)) })
        assertEquals("第一省", place.province)
        assertEquals("第一市", place.city)
    }

    @Test
    fun `a blank address result yields null rather than a place of empty strings`() {
        // j0.java:67 refused an empty address string; Place.isBlank is the same refusal in this layer.
        shadowOf(geocoderOf(resolver)).setFromLocation(emptyList())
        assertNull(runBlocking { resolver.resolvePlace(Coord(39.9, 116.4)) }, "no match at all")

        val empty = Address(Locale.SIMPLIFIED_CHINESE).apply { setLatitude(39.9); setLongitude(116.4) }
        shadowOf(geocoderOf(resolver)).setFromLocation(listOf(empty))
        assertNull(
            runBlocking { resolver.resolvePlace(Coord(39.9, 116.4)) },
            "six empty fields are not a place; the original compared `address == null` at x0.java:785",
        )
    }

    @Test
    fun `all-blank strings are still a blank place`() {
        val blanks = Address(Locale.SIMPLIFIED_CHINESE).apply {
            adminArea = ""
            locality = ""
            subLocality = ""
            thoroughfare = ""
            subThoroughfare = ""
        }
        assertTrue(Place(province = "", city = "", district = "", street = "", streetNumber = "").isBlank)
        shadowOf(geocoderOf(resolver)).setFromLocation(listOf(blanks))
        assertNull(runBlocking { resolver.resolvePlace(Coord(39.9, 116.4)) })
    }

    @Test
    fun `the queried coordinate is normalised to WGS84 before the geocoder is asked`() {
        // The resolver takes the layer's currency, so a bd09 input must not be geocoded raw. ShadowGeocoder
        // hands back whatever was scripted regardless of the query, so the observable part is that a
        // non-WGS84 input still resolves through the same conversion the fixes use.
        shadowOf(geocoderOf(resolver)).setFromLocation(listOf(blankAddressForBeijing()))

        val asWgs = runBlocking { resolver.resolvePlace(Coord(39.9, 116.4)) }
        val asBd09 = runBlocking { resolver.resolvePlace(Coord(39.90115, 116.40702, CoordSystem.BD09)) }
        assertNotNull(asWgs)
        assertEquals(asWgs, asBd09, "whatever the answer is, it must not depend on which datum came in")
        val converted = bd09ToWgs84(Coord(39.90115, 116.40702, CoordSystem.BD09))
        assertFalse(
            converted.lat == 39.90115 && converted.lng == 116.40702,
            "a bd09 input is converted before it is asked about, not passed through",
        )
    }

    // ------------------------------------------------------------------ resolveCoord

    @Test
    fun `an empty city and address query is refused before the Geocoder is touched`() {
        assertNull(runBlocking { resolver.resolveCoord("", "") })
        assertNull(runBlocking { resolver.resolveCoord("  ", "") })
    }

    @Test
    fun `forward geocoding cannot be answered under Robolectric and comes back null`() {
        // Not a shortcut: getFromLocationName is unshadowed in 4.11.1 and throws
        // "IOException: Service not Available", which SystemPlaceResolver.kt:57 swallows. The production
        // rule that a (0,0) result is rejected (j0.java:37) is therefore not reachable here either, and is
        // reported as untestable rather than faked.
        assertNull(runBlocking { resolver.resolveCoord("北京市", "长安街1号") })
        assertTrue(resolver.isAvailable, "the failure is the service, not the availability gate")
    }

    // ------------------------------------------------------------------ helpers

    private fun blankAddressForBeijing(): Address = Address(Locale.SIMPLIFIED_CHINESE).apply {
        setLatitude(39.9)
        setLongitude(116.4)
        adminArea = "北京市"
        locality = "北京"
    }

    /**
     * The `Geocoder` the resolver created lazily. `ShadowGeocoder.setFromLocation` is per instance, and
     * `SystemPlaceResolver(context)` exposes no seam for the Geocoder it uses, so this reads the private
     * delegate instead of testing a copy of the mapping.
     *
     * Kotlin stores a `by lazy` property as a `<name>$delegate` field of type [Lazy], so the field is looked
     * up by delegate type rather than by an exact name -- with the names it prints in the message if that
     * ever changes again.
     */
    private fun geocoderOf(resolver: SystemPlaceResolver): Geocoder {
        val fields = SystemPlaceResolver::class.java.declaredFields
        val field = fields.singleOrNull { Lazy::class.java.isAssignableFrom(it.type) }
            ?: throw AssertionError(
                "expected exactly one lazy property on SystemPlaceResolver, found " +
                    fields.joinToString { "${it.name}:${it.type.simpleName}" },
            )
        field.isAccessible = true
        // The delegate itself is `internal`, so read it through the kotlin.Lazy interface rather than
        // reflectively calling its getValue.
        val delegate = field.get(resolver) as Lazy<*>
        return delegate.value as Geocoder
    }
}
