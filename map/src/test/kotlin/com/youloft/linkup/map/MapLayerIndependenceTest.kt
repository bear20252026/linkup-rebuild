package com.youloft.linkup.map

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The architecture gate for `:map`, the same rule `:location`'s [com.youloft.linkup.location.LocationLayerIndependenceTest]
 * enforces one module over: the interface layer must not name a vendor, adapters live in their own
 * directories and may, and no source anywhere may import a vendor class — the map AARs are absent from
 * this tree, and the module has to keep building that way.
 */
class MapLayerIndependenceTest {

    private val repoRoot: File by lazy { locateRepoRoot() }
    private val moduleDir: File get() = File(repoRoot, "map")

    @Test
    fun `the module has real sources to inspect`() {
        assertTrue(mainSources.isNotEmpty(), "no Kotlin sources found under $MAIN_SOURCE_DIR")
        assertTrue(
            mainSources.size >= 5,
            "the interface layer shrank to ${mainSources.size} files; the scan would be vacuous",
        )
        assertTrue(File(moduleDir, "build.gradle.kts").isFile, "the module's own build file must be readable")
    }

    @Test
    fun `the interface layer names no vendor map package`() {
        val offenders = mainSources
            .filterNot { it.isVendorAdapter }
            .flatMap { source ->
                source.codeLines().mapIndexedNotNull { index, line ->
                    vendorPackages.firstOrNull { line.contains(it) }?.let { VendorHit(source, it, index + 1) }
                }
            }

        assertEquals(
            emptyList<String>(),
            offenders.map { "${it.relativePath}:${it.line} names ${it.packageName}" },
            "the abstraction leaked a vendor: $MAIN_SOURCE_DIR must not name $vendorPackages",
        )
    }

    @Test
    fun `no source anywhere in the module imports a vendor class`() {
        val imports = mainSources
            .flatMap { source ->
                source.codeLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        vendorPackages.firstOrNull { trimmed.contains(it) }?.let { VendorHit(source, it, index + 1) }
                    } else {
                        null
                    }
                }
            }

        assertEquals(
            emptyList<String>(),
            imports.map { "${it.relativePath}:${it.line} ${it.packageName}" },
            "a vendor import would make the module uncompilable without the AAR, which is the whole reason " +
                "the adapters bind by reflection",
        )
    }

    @Test
    fun `only the vendor adapters name a vendor, and they all live under provider slash`() {
        val naming = mainSources.filter { source ->
            val code = source.codeLines()
            vendorPackages.any { packageName -> code.any { it.contains(packageName) } }
        }

        assertEquals(
            emptyList<String>(),
            naming.filterNot { it.isVendorAdapter }.map { it.relativePath },
            "these files name a vendor SDK outside the adapter packages: $vendorPackages",
        )
        assertTrue(
            naming.any { it.relativePath.endsWith("BaiduMapSdk.kt") },
            "BaiduMapSdk.kt holds the reflection constants (com.baidu.mapapi.*); if it no longer appears " +
                "here, either the adapter is gone or this scan stopped working",
        )
        assertTrue(
            naming.any { it.relativePath.endsWith("AmapMapSdk.kt") },
            "AmapMapSdk.kt holds the reflection constants (com.amap.api.maps.*); if it no longer appears " +
                "here, either the second adapter is gone or this scan stopped working",
        )
        assertTrue(
            naming.isNotEmpty(),
            "the scan found no vendor reference at all, which would mean the scan is vacuous",
        )
    }

    @Test
    fun `the module declares no third-party map dependency`() {
        val buildFile = File(moduleDir, "build.gradle.kts")
        val text = buildFile.readText()

        vendorPackages.forEach { packageName ->
            assertTrue(
                !text.contains(packageName),
                "$buildFile declares $packageName: :map must stay SDK-free so it builds without the AAR",
            )
        }
        listOf("baidu", "amap", "autonavi", "tencentmap").forEach { hint ->
            val dependencyLine = dependencyCoordinates(text).firstOrNull { it.contains(hint, ignoreCase = true) }
            assertTrue(dependencyLine == null, "$buildFile pulls a third-party map artifact: $dependencyLine")
        }

        // Runtime coordinates are the platform, :location (for the datum conversions), and nothing else.
        val production = productionCoordinates(text)
        assertTrue(
            production.isNotEmpty(),
            "$buildFile declares no runtime dependency at all: the scan is vacuous, check the regex",
        )
        assertEquals(
            emptyList<String>(),
            production.filterNot { it in ALLOWED_RUNTIME },
            ":map picked up an unexpected runtime dependency: $production",
        )
    }

    @Test
    fun `the vendor strings the gate looks for are the ones that would actually hurt`() {
        assertEquals(
            listOf(
                "com.baidu",
                "com.amap",
                "com.tencent",
                "com.google.android.gms.maps",
                "com.huawei",
            ),
            vendorPackages,
        )
        assertTrue(
            mainSources.any { it.relativePath.contains("/provider/baidu/") },
            "the baidu adapter moved, so the exemption rule above needs updating",
        )
        assertTrue(
            mainSources.any { it.relativePath.contains("/provider/amap/") },
            "the amap adapter moved, so the exemption rule above needs updating",
        )
        assertTrue(
            mainSources.filter { it.isVendorAdapter }.all { it.file.isFile },
            "the adapter exemption points at files that do not exist",
        )
    }

    // ------------------------------------------------------------------ helpers

    private class SourceFile(val file: File, val relativePath: String) {
        /** Adapters may name the SDK they adapt; nothing else in the module may. */
        val isVendorAdapter: Boolean
            get() = relativePath.contains("/provider/baidu/") || relativePath.contains("/provider/amap/")

        fun codeLines(): List<String> = stripComments(file.readText())

        override fun toString(): String = relativePath
    }

    private class VendorHit(val file: SourceFile, val packageName: String, val line: Int) {
        val relativePath: String get() = file.relativePath
    }

    private fun locateRepoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        throw IllegalStateException(
            "no settings.gradle.kts above ${File("").absolutePath}: this test needs the repository root",
        )
    }

    private val mainSources: List<SourceFile> by lazy {
        val root = File(moduleDir, MAIN_SOURCE_DIR)
        assertTrue(root.isDirectory, "expected Kotlin sources under ${root.absolutePath}")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { SourceFile(it, it.relativePathToRepo()) }
            .toList()
            .sortedBy { it.relativePath }
    }

    private fun File.relativePathToRepo(): String =
        path.removePrefix(repoRoot.path + File.separator).replace(File.separatorChar, '/')

    private fun dependencyCoordinates(text: String): List<String> =
        Regex("""(implementation|api|compileOnly|runtimeOnly|testImplementation)\s*\(\s*"([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[2] }
            .toList()

    /** Both string coordinates and `project(":module")` references. */
    private fun productionCoordinates(text: String): List<String> =
        Regex("""(implementation|api|compileOnly|runtimeOnly)\s*\(\s*(?:"([^"]+)"|project\("([^"]+)"\))""")
            .findAll(text)
            .map { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            .toList()

    companion object {
        private const val MAIN_SOURCE_DIR = "src/main/kotlin"

        /** What the map layer may sit on: its own platform, and the datum conversions from :location. */
        private val ALLOWED_RUNTIME = setOf("androidx", "org.jetbrains.kotlinx", "org.jetbrains", ":location")

        /** Every Chinese map vendor the app could have shipped, plus Google's map API. */
        private val vendorPackages = listOf(
            "com.baidu",
            "com.amap",
            "com.tencent",
            "com.google.android.gms.maps",
            "com.huawei",
        )
    }
}

/** Drops comments, one entry per source line; string literals are kept — a vendor name in a string is a binding. */
private fun stripComments(source: String): List<String> {
    val kept = mutableListOf<String>()
    var inBlock = false
    for (original in source.lines()) {
        var text = original
        if (inBlock) {
            val end = text.indexOf("*/")
            if (end < 0) {
                kept += ""
                continue
            }
            inBlock = false
            text = text.substring(end + 2)
        }
        val blockStart = text.indexOf("/*")
        if (blockStart >= 0) {
            val after = text.substring(blockStart + 2)
            val end = after.indexOf("*/")
            if (end < 0) {
                inBlock = true
                text = text.substring(0, blockStart)
            } else {
                text = text.substring(0, blockStart) + after.substring(end + 2)
            }
        }
        val lineComment = text.indexOf("//")
        if (lineComment >= 0) text = text.substring(0, lineComment)
        kept += text
    }
    return kept
}
