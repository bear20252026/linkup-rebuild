package com.youloft.linkup.location

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The architecture gate for `:location`.
 *
 * The module exists because the Baidu location AAR cannot be obtained offline, and it only buys anything
 * if the abstraction never has to know who is behind it: an interface layer that names `com.baidu` cannot
 * be swapped for AMAP, the framework, or a test double without editing the interface. So this test reads
 * the module's own `src/main` sources and fails on a vendor name in the code -- not in prose, which is
 * where the recovery evidence legitimately lives: comments are stripped first, because every file in this
 * module cites the decompiled class it was reconstructed from and those citations name vendors by design.
 *
 * The adapter files under `location/provider/baidu/` and `location/provider/amap/` are deliberately
 * exempt: naming the SDK is the adapter's whole job. The exemption is checked, not assumed -- the
 * [only the vendor adapters name a vendor][only-the-vendor-adapters-name-a-vendor] test asserts the exempt
 * set is exactly those files and that both binding files still exist, so a new vendor-naming file anywhere
 * else fails, a vendor name creeping into an import fails everywhere, and a deleted adapter fails loudly.
 */
class LocationLayerIndependenceTest {

    private val repoRoot: File by lazy { locateRepoRoot() }
    private val moduleDir: File get() = File(repoRoot, "location")

    @Test
    fun `the module has real sources to inspect`() {
        assertTrue(mainSources.isNotEmpty(), "no Kotlin sources found under $MAIN_SOURCE_DIR")
        assertTrue(
            mainSources.size >= 13,
            "the interface layer shrank to ${mainSources.size} files; the scan would be vacuous",
        )
        assertTrue(File(moduleDir, "build.gradle.kts").isFile, "the module's own build file must be readable")
    }

    @Test
    fun `the interface layer names no vendor location or map package`() {
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
            "the abstraction leaked a vendor: $MAIN_SOURCE_DIR must not name $vendorPackages -- callers " +
                "have to compile against an SDK-free interface",
        )
    }

    @Test
    fun `no source anywhere in the module imports a vendor class`() {
        // The Baidu adapter reaches its SDK reflectively (BaiduSdk.kt: CLASS/OPTION/LISTENER are strings),
        // so even the adapter itself has no compile-time vendor reference. That is the property that lets
        // this module build in a tree where the AAR does not exist.
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
                "the adapter binds by reflection",
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
            "these files name a vendor SDK outside the adapter packages, so the interface layer now depends " +
                "on a vendor: $vendorPackages",
        )
        assertTrue(
            naming.any { it.relativePath.endsWith("BaiduSdk.kt") },
            "BaiduSdk.kt holds the reflection constants (com.baidu.location.*); if it no longer appears " +
                "here, either the adapter is gone or this scan stopped working",
        )
        assertTrue(
            naming.any { it.relativePath.endsWith("AmapSdk.kt") },
            "AmapSdk.kt holds the reflection constants (com.amap.api.location.*); if it no longer appears " +
                "here, either the second adapter is gone or this scan stopped working",
        )
        assertTrue(
            naming.isNotEmpty(),
            "the scan found no vendor reference at all, which would mean the reflection binding " +
                "disappeared and this test is now vacuous",
        )
    }

    @Test
    fun `the module declares no third-party location or map dependency`() {
        val buildFile = File(moduleDir, "build.gradle.kts")
        val text = buildFile.readText()

        vendorPackages.forEach { packageName ->
            assertTrue(
                !text.contains(packageName),
                "$buildFile declares $packageName: :location must stay SDK-free so it builds without the AAR",
            )
        }
        listOf("baidu", "amap", "autonavi", "tencentmap").forEach { hint ->
            val dependencyLine = dependencyCoordinates(text).firstOrNull { it.contains(hint, ignoreCase = true) }
            assertTrue(dependencyLine == null, "$buildFile pulls a third-party map artifact: $dependencyLine")
        }

        // The runtime side of the seam: everything declared outside the test source set is Kotlin or
        // AndroidX, which is what "the interface does not need the SDK" means in a build file. A new
        // coordinate that is neither has to be argued for here, not quietly added.
        val production = productionCoordinates(text)
        assertTrue(
            production.isNotEmpty(),
            "$buildFile declares no runtime dependency at all: the scan is vacuous, check the regex",
        )
        assertEquals(
            emptyList<String>(),
            production.filterNot { it.substringBefore(':') in ALLOWED_RUNTIME_GROUPS },
            ":location picked up a third-party runtime dependency: $production -- the allowed groups are " +
                "$ALLOWED_RUNTIME_GROUPS, and a vendor SDK is never one of them",
        )
    }

    @Test
    fun `the vendor strings the gate looks for are the ones that would actually hurt`() {
        // Guards the gate itself: an empty or mangled deny-list would make every other test pass trivially.
        assertEquals(
            listOf(
                "com.baidu",
                "com.amap",
                "com.tencent",
                "com.google.android.gms.location",
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
            mainSources.any { it.relativePath.contains("/provider/system/") },
            "the framework adapter moved, so the layer layout changed",
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

        /** The file's source with comments removed, one entry per original line. */
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
            "no settings.gradle.kts above ${File("").absolutePath}: this test needs the repository root to " +
                "reach location/src/main, and a source tree that cannot be found is not a passing build",
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

    private fun productionCoordinates(text: String): List<String> =
        Regex("""(implementation|api|compileOnly|runtimeOnly)\s*\(\s*"([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[2] }
            .toList()

    companion object {
        private const val MAIN_SOURCE_DIR = "src/main/kotlin"

        /** Coordinates on the runtime classpath may only come from these groups. */
        private val ALLOWED_RUNTIME_GROUPS = setOf("androidx", "org.jetbrains.kotlinx", "org.jetbrains")

        /** Every Chinese location/map vendor the app could have shipped, plus Google's location API. */
        private val vendorPackages = listOf(
            "com.baidu",
            "com.amap",
            "com.tencent",
            "com.google.android.gms.location",
            "com.huawei",
        )
    }
}

/**
 * Drops comments, one entry per source line so a hit can be reported with its real line number. String
 * literals are kept: a vendor name in a string is a reflection binding or a leak, not prose. File-local so
 * [LocationLayerIndependenceTest.SourceFile] can use it -- recovery prose naming a vendor is evidence and
 * must not fail the gate, while code naming one is exactly what the gate is for.
 */
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
