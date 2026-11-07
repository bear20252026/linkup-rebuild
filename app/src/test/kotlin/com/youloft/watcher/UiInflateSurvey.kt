package com.youloft.watcher

import android.view.LayoutInflater
import android.widget.FrameLayout
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Inflates every layout that survived into :res166 and writes a machine-readable report, so
 * "how much of the recovered UI actually runs today" is a measured number rather than a guess.
 * The assertion covers the FastKit-backed sample; the ranked cause list is the planning artifact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = StorelessApp::class)
class UiInflateSurvey {

    private val marker = "Error inflating class "
    private val mergeHint = "can be used only with a valid ViewGroup root and attachToRoot=true"

    private fun causeOf(message: String): String {
        val i = message.indexOf(marker)
        return if (i < 0) message.lineSequence().first().take(60)
        else message.substring(i + marker.length).lineSequence().first().trim()
    }

    @Test
    fun `survey every recovered layout`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Watcher)
        val inflater = LayoutInflater.from(activity)

        val fields = Class.forName("com.youloft.watcher.res166.R\$layout").fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.name to it.getInt(null) }
            .sortedBy { it.first }

        val results = mutableMapOf<String, String?>()
        for ((name, id) in fields) {
            // <merge> layouts need attachToRoot = true; every other layout must not be attached
            // to the throwaway parent, or it would report success while silently re-parenting.
            var error = runCatching { inflater.inflate(id, FrameLayout(activity), false) }
                .exceptionOrNull()
                ?.let { e -> generateSequence<Throwable>(e) { it.cause }.last().message ?: e.toString() }
            if (error != null && error.contains(mergeHint)) {
                error = runCatching { inflater.inflate(id, FrameLayout(activity), true) }
                    .exceptionOrNull()
                    ?.let { e -> generateSequence<Throwable>(e) { it.cause }.last().message ?: e.toString() }
            }
            results[name] = error
        }

        val ok = results.values.count { it == null }
        val causes = results.values.filterNotNull()
            .map { causeOf(it) }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }

        val report = buildString {
            appendLine("total_layouts=${results.size}")
            appendLine("inflated_ok=$ok")
            appendLine("failed=${results.size - ok}")
            appendLine("distinct_causes=${causes.size}")
            appendLine()
            appendLine("== missing or broken view classes, most blocking first ==")
            causes.forEach { (cls, count) ->
                val example = results.entries.firstOrNull { (_, why) -> why != null && causeOf(why) == cls }?.key
                appendLine("%4d  %-56s  e.g. %s".format(count, cls.take(56), example))
            }
        }
        File("build/ui_inflate_per_layout.csv").apply {
            parentFile?.mkdirs()
            writeText(
                results.entries.joinToString(separator = "\n") { (name, why) ->
                    val flattened = (why ?: "").replace('\n', ' ').replace(",", ";")
                    "$name,${if (why == null) "ok" else "fail"},$flattened"
                },
            )
        }
        File("build/ui_inflate_report.txt").apply {
            parentFile?.mkdirs()
            writeText(report)
        }
        println("INFLATE_SURVEY total=${results.size} ok=$ok failed=${results.size - ok} causes=${causes.size}")

        listOf("item_friend_request", "activity_friend_request_list", "view_card_distance")
            .forEach { name ->
                assertTrue(results.containsKey(name), "$name is not in the bank")
                assertTrue(results[name] == null, "$name failed to inflate: ${results[name]}")
            }
    }
}
