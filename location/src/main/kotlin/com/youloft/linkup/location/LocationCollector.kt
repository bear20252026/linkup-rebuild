package com.youloft.linkup.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Decides whether a fix deserves a report, using the one rule the recovered app applied before it looked
 * at stay state at all: the cadence in `manager/x0.java:218-228`, which is
 * `cur.timePeriod - lastReported.timePeriod >= cadence(max(two speeds))`.
 *
 * Kept apart from [LocationCollector] because it is state-only and synchronous: the whole truth table can
 * be tested without a coroutine, a provider or a fake clock racing anything.
 *
 * This is the gate the collector uses **when no [StayLeaveClassifier] is configured** — the configuration
 * the interface round shipped with, where everything goes out [ReportKind.UNCLASSIFIED] because the stay
 * state machine had not been ported. With a classifier configured, the classifier's own gates take over
 * and this one only keeps its `lastReported` for symmetry with the flush path.
 */
class ReportGate(private val tuning: LocationTuning = LocationTuning()) {
    /** The fix that reached the server last, which is what the original compared against (`x0.java:914`). */
    var lastReported: LocationFix? = null
        private set

    /** True when this fix is far enough in the future (or past) to be worth sending. */
    fun shouldReport(fix: LocationFix): Boolean {
        if (fix.isInvalid) return false
        val previous = lastReported ?: return true
        val cadence = tuning.cadenceSeconds(
            maxOf(previous.speedMetersPerSecond, fix.speedMetersPerSecond),
        )
        return fix.epochSeconds - previous.epochSeconds >= cadence
    }

    /** Called only after the server took it, so a failed send stays retryable. */
    fun reported(fix: LocationFix) {
        lastReported = fix
    }

    fun reset() {
        lastReported = null
    }
}

/** One queued report: the fix and the reason it is being sent, kept together through a network outage. */
private data class QueuedFix(val fix: LocationFix, val kind: ReportKind)

/**
 * The loop the app actually needs: follow a [LocationProvider], filter, push accepted fixes to a
 * [LocationGateway], and keep the ones the network refused.
 *
 * Two filtering configurations, sharing one queue:
 *
 * - **with a [StayLeaveClassifier]** — the recovered app's shape. Each fix runs the intake pacer
 *   (foreground only; the dormancy request owns the interval while backgrounded), then the classifier's
 *   gate; only fixes the classifier reports are queued, already carrying their [ReportKind] (STAY/LEAVE).
 *   After the gateway takes one, the widget callback fires with the copy the original's `R()` would have
 *   pushed: the reported fix, with `type=4` substituted when a stay exists and the fix was unclassified.
 * - **without one** — the interface-round shape: [ReportGate]'s cadence decides and every fix goes out
 *   [ReportKind.UNCLASSIFIED]. Event strings in this mode are the ones this loop has always emitted.
 *
 * Nothing in here names a vendor or a backend: the same collector runs against the framework provider
 * today and against Baidu the day the AAR arrives.
 *
 * Failure handling differs from the original in one way that should not be mistaken for equivalence —
 * its `LinkedBlockingQueue` (`x0.java:63`) was unbounded, so a long outage grew memory; this queue is
 * bounded by [LocationTuning.pendingLimit] and drops the oldest fix, because a week-old position is not
 * worth a track row and the server buckets days by `timePeriod` anyway.
 */
class LocationCollector(
    private val provider: LocationProvider,
    private val gateway: LocationGateway,
    private val tuning: LocationTuning = LocationTuning(),
    private val scope: CoroutineScope,
    private val onEvent: (String) -> Unit = {},
    /** The recovered stay/leave gate; null keeps the cadence-only shape (and its event strings). */
    private val classifier: StayLeaveClassifier? = null,
    /** The widget stream the original's `R()` fed; default swallows, the service layer wires the real one. */
    private val onWidget: (LocationFix, ReportKind) -> Unit = { _, _ -> },
) {
    private val gate = ReportGate(tuning)
    private val pending = ArrayDeque<QueuedFix>()
    private var backgrounded = false
    private var isFlushing = false

    private val listener = object : LocationListener {
        override fun onFix(fix: LocationFix) = accept(fix)

        override fun onFailure(fault: LocationFault) {
            onEvent("定位失败 provider=${provider.id} reason=${fault.reason} vendor=${fault.vendorCode} detail=${fault.detail}")
        }
    }

    /** Starts following. False means this provider cannot take the job; try the next [LocationProviders] candidate. */
    fun start(): Boolean {
        val started = provider.start(requestForCurrentState(), listener)
        onEvent("定位采集 start provider=${provider.id} ok=$started backgrounded=$backgrounded")
        return started
    }

    fun stop() {
        provider.stop()
        onEvent("定位采集 stop provider=${provider.id}")
    }

    /**
     * Foreground/back-foreground switch. The original did this by rewriting `scanSpan` on the live option
     * object (`x0.java:619-648`, and `s0.java:810` which also flips to Battery_Saving); the neutral version
     * is "ask the provider to move to the other [LocationRequest]".
     *
     * @return true when the provider was reconfigured, false when it already had this request.
     */
    fun setBackgrounded(backgrounded: Boolean): Boolean {
        if (this.backgrounded == backgrounded) return false
        this.backgrounded = backgrounded
        val changed = provider.update(requestForCurrentState())
        onEvent("定位采集 切换后台=$backgrounded 已重配=$changed interval=${requestForCurrentState().intervalMillis}ms")
        return changed
    }

    /** Feeds one fix through the configured gate; called by [listener], and by anything else with a fix. */
    fun accept(fix: LocationFix) {
        val kind: ReportKind = if (classifier != null) {
            if (fix.isInvalid) {
                onEvent("定位采集 过滤 ${fix.coord.lat},${fix.coord.lng} 无效点")
                return
            }
            // Intake order is the original's: the pacer runs before the gate, and only owns the interval
            // in the foreground — backgrounded, the dormancy request decides (x0.java:316-330 vs A()).
            if (!backgrounded) {
                classifier.pace(fix)?.let { suggested ->
                    val applied = provider.update(tuning.followingRequest().copy(intervalMillis = suggested))
                    onEvent("定位采集 节奏调整 interval=${suggested}ms 已重配=$applied")
                }
            }
            val decision = classifier.classify(fix)
            if (!decision.report) {
                onEvent("定位采集 过滤 ${fix.coord.lat},${fix.coord.lng} ${decision.reason}")
                return
            }
            onEvent("定位采集 分类 ${fix.coord.lat},${fix.coord.lng} type=${decision.kind.wireType} ${decision.reason}")
            decision.kind
        } else {
            if (!gate.shouldReport(fix)) {
                onEvent("定位采集 过滤 ${fix.coord.lat},${fix.coord.lng} 上次上报后不足节律")
                return
            }
            // No classifier: everything goes out unclassified, which is also what the recovered bean
            // carried when the SDK delivered it (type 0 at x0.java:304).
            ReportKind.UNCLASSIFIED
        }
        if (pending.size >= tuning.pendingLimit) pending.removeFirst()
        pending.addLast(QueuedFix(fix, kind))
        scope.launch { flush() }
    }

    /** Sends what is queued, stopping at the first transport failure so order survives an outage. */
    suspend fun flush() {
        if (isFlushing) return
        isFlushing = true
        try {
            while (pending.isNotEmpty()) {
                val entry = pending.first()
                runCatching { gateway.report(entry.fix, entry.kind) }
                    .onSuccess {
                        pending.removeFirst()
                        // Both gates learn the fix landed: the cadence one for its own comparisons, the
                        // classifier because `lastReported` is the anchor of its spacing rules (line 914).
                        gate.reported(entry.fix)
                        classifier?.onReported(entry.fix)
                        onWidget(entry.fix, classifier?.widgetCopyKind(entry.kind) ?: entry.kind)
                        onEvent("定位上报 ok ${entry.fix.coord.lat},${entry.fix.coord.lng} 剩余=${pending.size}")
                    }
                    .onFailure {
                        onEvent("定位上报 失败 ${entry.fix.coord.lat},${entry.fix.coord.lng} 剩余=${pending.size} 原因=$it")
                        return
                    }
            }
        } finally {
            isFlushing = false
        }
    }

    /** Fixes waiting for the network, oldest first. */
    fun pendingCount(): Int = pending.size

    private fun requestForCurrentState(): LocationRequest =
        if (backgrounded) tuning.dormantRequest() else tuning.followingRequest()
}
