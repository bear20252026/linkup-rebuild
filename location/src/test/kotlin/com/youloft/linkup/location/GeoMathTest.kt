package com.youloft.linkup.location

import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Pins [GeoMath] to `recovered/geo_golden_156.txt`, which was produced by running the ORIGINAL decompiled
 * coordinate arithmetic (`_raw/out_jadx_156/sources/com/youloft/watcher/utils/h0.java`) on a JDK, so a
 * reconstruction of the conversions cannot drift away from what the shipped app computed.
 *
 * Every row of that file is examined: the six transform families, the `out_of_china` booleans and the
 * `haversine_like_e` metre value. Tolerances are the golden file's own precision -- the rows are printed
 * with `%.9f`, so 1e-9 degrees (~0.11 m) for coordinates and 1e-3 m for the distance -- and a row that
 * stops matching fails the sweep rather than being reclassified.
 *
 * One family is named but not ported, and says so: `bd09_inverse` is `h0.h`, a 0.01-degree bisection over
 * `h0.n`. Nothing in this layer needs an inverse-by-search (only `h0.g`'s closed form is used, at
 * `x0.java:290`), so [the bisection inverse family is not ported] checks that the two golden rows agree
 * with each other to the precision the original's own iteration achieves, and that the port exposes no
 * such function. An unknown family in the evidence file fails loudly instead of being skipped, and so does
 * a missing file.
 */
class GeoMathTest {

    // ---------------------------------------------------------------- golden plumbing

    private data class Row(
        val family: String,
        val inputText: String,
        val inputs: List<Double>,
        val expected: String,
    ) {
        val label: String get() = "$family($inputText)"
        val expectedPoint: Pair<Double, Double>
            get() = expected.split(',').let { Pair(it[0].toDouble(), it[1].toDouble()) }
    }

    /** Families [GeoMath] implements, i.e. families whose rows must reproduce bit for bit. */
    private val portedPointFamilies = setOf(
        "bd09_to_gcj02",
        "bd09_to_wgs84",
        "gcj02_to_wgs84",
        "wgs84_to_gcj02",
        "wgs84_to_bd09",
        "gcj02_to_bd09",
    )

    private val scalarFamilies = setOf("out_of_china", "haversine_like_e")

    /** Present in the evidence file but deliberately not ported; named, never skipped. */
    private val unportedFamilies = setOf("bd09_inverse")

    private val rowPattern = Regex("""^([a-z0-9_]+)\(([^)]*)\)\s*=\s*(.+)$""")

    private val goldenFile: File by lazy { locateGoldenFile() }

    private val rows: List<Row> by lazy {
        goldenFile.readLines().asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val match = rowPattern.matchEntire(line)
                    ?: throw IllegalStateException("unparseable row in $goldenFile: <<$line>>")
                Row(
                    family = match.groupValues[1],
                    inputText = match.groupValues[2],
                    inputs = match.groupValues[2].split(',').map { it.trim().toDouble() },
                    expected = match.groupValues[3].trim(),
                )
            }
            .toList()
    }

    private fun locateGoldenFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "recovered/geo_golden_156.txt")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw IllegalStateException(
            "recovered/geo_golden_156.txt is not reachable from ${File("").absolutePath}; this suite is " +
                "worth nothing without the original package's arithmetic to compare against",
        )
    }

    /** The module's answer for a point row, or null when the family is not a ported point transform. */
    private fun portPoint(family: String, lat: Double, lng: Double): Coord? = when (family) {
        "bd09_to_gcj02" -> bd09ToGcj02(Coord(lat, lng, CoordSystem.BD09))
        "bd09_to_wgs84" -> bd09ToWgs84(Coord(lat, lng, CoordSystem.BD09))
        "gcj02_to_wgs84" -> gcj02ToWgs84(Coord(lat, lng, CoordSystem.GCJ02))
        "wgs84_to_gcj02" -> wgs84ToGcj02(Coord(lat, lng, CoordSystem.WGS84))
        "wgs84_to_bd09" -> wgs84ToBd09(Coord(lat, lng, CoordSystem.WGS84))
        "gcj02_to_bd09" -> gcj02ToBd09(Coord(lat, lng, CoordSystem.GCJ02))
        else -> null
    }

    /** Which datum each family is supposed to come back tagged with. */
    private val expectedSystemFor = mapOf(
        "bd09_to_gcj02" to CoordSystem.GCJ02,
        "bd09_to_wgs84" to CoordSystem.WGS84,
        "gcj02_to_wgs84" to CoordSystem.WGS84,
        "wgs84_to_gcj02" to CoordSystem.GCJ02,
        "wgs84_to_bd09" to CoordSystem.BD09,
        "gcj02_to_bd09" to CoordSystem.BD09,
    )

    // ---------------------------------------------------------------- the sweep

    @Test
    fun `the golden evidence file is found and every family in it is accounted for`() {
        assertTrue(goldenFile.isFile, "$goldenFile disappeared")
        assertTrue(rows.isNotEmpty(), "no rows parsed out of $goldenFile")
        assertTrue(rows.size > 30, "expected the whole recovered sample table, got ${rows.size} rows")

        val families = rows.map { it.family }.toSet()
        val known = portedPointFamilies + scalarFamilies + unportedFamilies
        assertTrue((families - known).isEmpty(), "golden rows from families this test does not know: ${families - known}")

        // A silently truncated evidence file must not be able to pass the suite: each family the port
        // claims has to still be represented.
        for (required in portedPointFamilies + scalarFamilies + unportedFamilies) {
            assertTrue(rows.any { it.family == required }, "the golden file no longer carries $required rows")
        }
        assertEquals(6, rows.count { it.family == "bd09_to_gcj02" }, "six polar-transform samples")
        assertEquals(6, rows.count { it.family == "out_of_china" }, "one bbox row per sample point")
        assertEquals(1, rows.count { it.family == "haversine_like_e" })
        assertEquals(1, rows.count { it.family == "bd09_inverse" })
    }

    @Test
    fun `every ported golden row is reproduced at the file tolerance`() {
        var reproduced = 0
        for (row in rows.filter { it.family in portedPointFamilies || it.family in scalarFamilies }) {
            when (row.family) {
                "out_of_china" -> {
                    val actual = outOfChina(row.inputs[0], row.inputs[1])
                    assertEquals(
                        row.expected,
                        actual.toString(),
                        "${row.label}: the recovered bbox guard (h0.i) is a boolean, it has no slack",
                    )
                }

                "haversine_like_e" -> {
                    val actual = coarseDistanceMeters(
                        Coord(row.inputs[0], row.inputs[1], CoordSystem.WGS84),
                        Coord(row.inputs[2], row.inputs[3], CoordSystem.WGS84),
                    )
                    assertEquals(
                        row.expected.toDouble(),
                        actual,
                        1e-3,
                        "${row.label}: h0.e is kept verbatim, so a metre of drift is already a change",
                    )
                }

                else -> {
                    val ported = portPoint(row.family, row.inputs[0], row.inputs[1])
                        ?: throw IllegalStateException("no port binding for ${row.family}")
                    val actual = Pair(ported.lat, ported.lng)
                    val expected = row.expectedPoint
                    assertEquals(expected.first, actual.first, COORD_TOLERANCE, "${row.label}: latitude")
                    assertEquals(expected.second, actual.second, COORD_TOLERANCE, "${row.label}: longitude")
                    assertEquals(
                        expectedSystemFor.getValue(row.family),
                        ported.system,
                        "${row.label}: the port has to tag the datum it produced, not just compute it",
                    )
                }
            }
            reproduced++
        }
        assertEquals(
            rows.count { it.family in portedPointFamilies || it.family in scalarFamilies },
            reproduced,
            "a golden row went unexamined",
        )
        assertEquals(rows.size - 1, reproduced, "only bd09_inverse may be outside the sweep")
    }

    @Test
    fun `the six transform families each round-trip through the ported chain`() {
        // h0.c = a then g, h0.m = n then f: the composite families are not independent code paths, and the
        // golden rows only agree if the port composes them the same way.
        for (row in rows.filter { it.family == "bd09_to_wgs84" }) {
            val lat = row.inputs[0]
            val lng = row.inputs[1]
            val viaChain = gcj02ToWgs84(bd09ToGcj02(Coord(lat, lng, CoordSystem.BD09)))
            assertEquals(bd09ToWgs84(Coord(lat, lng, CoordSystem.BD09)), viaChain, row.label)
        }
        for (row in rows.filter { it.family == "wgs84_to_bd09" }) {
            val viaChain = gcj02ToBd09(wgs84ToGcj02(Coord(row.inputs[0], row.inputs[1], CoordSystem.WGS84)))
            assertEquals(wgs84ToBd09(Coord(row.inputs[0], row.inputs[1])), viaChain, row.label)
        }
    }

    @Test
    fun `the bisection inverse family is not ported and agrees with the closed form to its own precision`() {
        // `h0.h` (h0.java:84-114) bisects `h0.n` to 1e-9 per component; GeoMath carries only the closed
        // form `h0.g`. The two golden rows for the same input therefore have to agree with EACH OTHER, not
        // bit for bit, and the port must not quietly grow a second inverse implementation.
        val inverse = rows.single { it.family == "bd09_inverse" }
        val closedForm = rows.single { it.family == "gcj02_to_wgs84" && it.inputText == inverse.inputText }
        val a = inverse.expectedPoint
        val b = closedForm.expectedPoint
        assertTrue(abs(a.first - b.first) <= 1e-6, "h0.h vs h0.g latitude: ${a.first} vs ${b.first}")
        assertTrue(abs(a.second - b.second) <= 1e-6, "h0.h vs h0.g longitude: ${a.second} vs ${b.second}")
        assertNull(portPoint("bd09_inverse", inverse.inputs[0], inverse.inputs[1]))
    }

    // ---------------------------------------------------------------- the rest of the port

    @Test
    fun `asWgs84 round-trips a Beijing point within a millionth of a degree`() {
        // The datum step the layer actually performs: gcj02 -> wgs84 -> gcj02. Measured residual is
        // 3.5e-7 deg in latitude and 8.4e-7 deg in longitude (~0.04 m / 0.09 m), which is what the
        // closed-form inverse of h0 costs: forward and inverse evaluate the delta one lattice apart.
        val beijing = Coord(39.90469, 116.40717, CoordSystem.GCJ02)
        val roundTrip = wgs84ToGcj02(beijing.asWgs84())
        assertEquals(CoordSystem.WGS84, beijing.asWgs84().system)
        assertTrue(abs(roundTrip.lat - beijing.lat) < 1e-6, "latitude drifted to ${roundTrip.lat}")
        assertTrue(abs(roundTrip.lng - beijing.lng) < 1e-6, "longitude drifted to ${roundTrip.lng}")
    }

    @Test
    fun `the bd09 pair adds its own first-order residue on top of the datum round trip`() {
        // h0.a / h0.f are an offset-polar approximation, not an exact inverse: for Beijing the pair alone
        // is self-inverse to 3.6e-8 deg, but composing bd09 -> wgs84 -> bd09 stacks it on the 1e-6 datum
        // residue and lands at 1.3e-6 deg (~0.15 m). That is the original algorithm's own limit, so this
        // asserts the measured precision rather than pretending 1e-6 covers it.
        val beijing = Coord(39.90469, 116.40717, CoordSystem.BD09)
        val roundTrip = beijing.asWgs84().asBd09()
        assertTrue(abs(roundTrip.lat - beijing.lat) < 1e-5, "bd09 round trip latitude ${roundTrip.lat}")
        assertTrue(abs(roundTrip.lng - beijing.lng) < 1e-5, "bd09 round trip longitude ${roundTrip.lng}")

        val polarPair = gcj02ToBd09(bd09ToGcj02(beijing))
        assertTrue(abs(polarPair.lat - beijing.lat) < 1e-6, "the polar pair alone: ${polarPair.lat}")
        assertTrue(abs(polarPair.lng - beijing.lng) < 1e-6, "the polar pair alone: ${polarPair.lng}")
    }

    @Test
    fun `the China guard makes an out-of-China round trip exact`() {
        // h0.java:71 / h0.java:142 -- dropping that guard would offset every overseas fix, and it is the
        // reason a gcj02 vendor can be plugged in without moving Tokyo by 500 m.
        val tokyo = Coord(35.68121, 139.76712, CoordSystem.GCJ02)
        val thereAndBack = wgs84ToGcj02(gcj02ToWgs84(tokyo))
        assertEquals(tokyo.lat, thereAndBack.lat, 0.0, "outside the box the two datums are one datum")
        assertEquals(tokyo.lng, thereAndBack.lng, 0.0)
        assertEquals(CoordSystem.GCJ02, thereAndBack.system)

        val asWgs = tokyo.asWgs84()
        assertEquals(35.68121, asWgs.lat, COORD_TOLERANCE)
        assertEquals(139.76712, asWgs.lng, COORD_TOLERANCE)

        val already = Coord(39.90469, 116.40717, CoordSystem.WGS84)
        assertEquals(already, already.asWgs84(), "a WGS84 coord comes back untouched")
        assertEquals(already, Coord(already.asWgs84().lat, already.asWgs84().lng))
    }

    @Test
    fun `distanceMeters answers null where the original used its -1_0 sentinel`() {
        val beijing = Coord(39.90469, 116.40717, CoordSystem.WGS84)
        // `x0.java:786` branches on `dG != -1.0d`; the port spells the same case as null.
        assertNull(distanceMeters(beijing, null))
        assertNull(distanceMeters(null, beijing))
        assertNull(distanceMeters(null, null))
        assertNull(
            distanceMeters(beijing, Coord(0.0, 116.40717, CoordSystem.WGS84)),
            "a placeholder end point is the original's other -1 case",
        )
        assertNull(
            distanceMeters(Coord(39.90469, Double.MIN_VALUE), beijing),
            "and Double.MIN_VALUE is a placeholder on either axis",
        )
    }

    @Test
    fun `distanceMeters between identical points is exactly zero`() {
        val beijing = Coord(39.90469, 116.40717, CoordSystem.WGS84)
        assertEquals(0.0, distanceMeters(beijing, beijing)!!, 0.0)
        assertEquals(0.0, coarseDistanceMeters(beijing, beijing), 1e-9)
        val shanghai = Coord(31.23037, 121.47370, CoordSystem.WGS84)
        assertEquals(
            distanceMeters(beijing, shanghai)!!,
            distanceMeters(shanghai, beijing)!!,
            1e-6,
            "the metres between two points may not depend on which one is asked first",
        )
    }

    @Test
    fun `distanceMeters agrees with the coarse spherical law of cosines over a Beijing-Shanghai baseline`() {
        val beijing = Coord(39.90469, 116.40717, CoordSystem.WGS84)
        val shanghai = Coord(31.23037, 121.47370, CoordSystem.WGS84)
        val precise = distanceMeters(beijing, shanghai)!!
        val coarse = coarseDistanceMeters(beijing, shanghai)
        val relative = abs(precise - coarse) / coarse
        assertTrue(
            relative < 0.005,
            "haversine ${precise}m vs h0.e ${coarse}m differ by " +
                "${String.format(java.util.Locale.US, "%.4f", relative * 100)}%, and the port promised " +
                "to stay inside half a percent",
        )
        // The golden metre row belongs to h0.e, i.e. to coarseDistanceMeters, not to the haversine.
        val golden = rows.single { it.family == "haversine_like_e" }.expected.toDouble()
        assertEquals(golden, coarse, 1e-3, "coarseDistanceMeters drifted from h0.e")
        assertTrue(abs(precise - golden) / golden < 0.005, "haversine $precise vs golden $golden")
    }

    @Test
    fun `CoordSystem parse reads the spellings the SDKs actually use`() {
        assertEquals(CoordSystem.BD09, CoordSystem.parse("bd09ll"))
        assertEquals(CoordSystem.BD09, CoordSystem.parse("BD09LL"), "some builds upper-case it")
        assertEquals(CoordSystem.BD09, CoordSystem.parse(" bd09 "))
        assertEquals(CoordSystem.GCJ02, CoordSystem.parse("gcj02"))
        assertEquals(CoordSystem.GCJ02, CoordSystem.parse("gcj02ll"))
        assertEquals(CoordSystem.WGS84, CoordSystem.parse(null), "no tag means the layer's own datum")
        assertEquals(CoordSystem.WGS84, CoordSystem.parse("wgs84"))
        assertEquals(CoordSystem.WGS84, CoordSystem.parse(""), "an unknown spelling stays WGS84")
        assertEquals(CoordSystem.WGS84, CoordSystem.parse("wgs-84"), "and so does a near miss")
    }

    @Test
    fun `placeholder coordinates are flagged the way the original logged them`() {
        // `x0.java:284` rejects 0 and Double.MIN_VALUE on either axis before anything else happens.
        assertTrue(Coord(0.0, 116.4).isPlaceholder)
        assertTrue(Coord(39.9, 0.0).isPlaceholder)
        assertTrue(Coord(Double.MIN_VALUE, 116.4).isPlaceholder)
        assertTrue(Coord(39.9, Double.MIN_VALUE).isPlaceholder)
        assertFalse(Coord(39.90469, 116.40717).isPlaceholder)
        assertFalse(Coord(-33.86, 151.21).isPlaceholder, "a real southern-hemisphere fix is not a placeholder")
        assertFalse(Coord(0.0001, 0.0001).isPlaceholder, "a tiny coordinate is still a position")
    }

    @Test
    fun `outOfChina is the recovered bbox and nothing else`() {
        // h0.java:117: lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
        assertFalse(outOfChina(39.90469, 116.40717))
        assertTrue(outOfChina(35.68121, 139.76712), "Tokyo sits east of the box")
        assertTrue(outOfChina(39.9, 72.003), "just west of the box")
        assertTrue(outOfChina(39.9, 137.835), "just east of the box")
        assertTrue(outOfChina(55.828, 100.0), "north of the box")
        assertTrue(outOfChina(0.829, 100.0), "south of the box")
        assertFalse(outOfChina(0.8293, 72.004), "the comparisons are strict, so the edges are inside")
        assertFalse(outOfChina(55.8271, 137.8347), "the comparisons are strict, so the edges are inside")
    }

    @Test
    fun `the polar bd09 pair is self inverse to its documented precision`() {
        // h0.f after h0.a, on the golden gcj02 row for Beijing.
        val gcj = Coord(39.898340568, 116.400788325, CoordSystem.GCJ02)
        val roundTrip = bd09ToGcj02(gcj02ToBd09(gcj))
        assertTrue(abs(roundTrip.lat - gcj.lat) < 1e-6, "got ${roundTrip.lat}")
        assertTrue(abs(roundTrip.lng - gcj.lng) < 1e-6, "got ${roundTrip.lng}")
        assertEquals(CoordSystem.GCJ02, roundTrip.system)
        assertEquals(CoordSystem.BD09, gcj02ToBd09(gcj).system)
    }

    companion object {
        /** The golden file prints `%.9f`, so a matching port must agree to 1e-9 degrees. */
        private const val COORD_TOLERANCE = 1e-9
    }
}
