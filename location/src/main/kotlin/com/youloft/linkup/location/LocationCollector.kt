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
 * **This is a subset of the original, on purpose.** The rest of `c()` (`x0.java:164-265`) classifies
 * stay / potential-stay / departure and stamps `type` 2 and 3; that state machine is the track-screen card
 * and needs its own truth table, not a paraphrase written while building an interface.
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

/**
 * The loop the app actually needs: follow a [LocationProvider], filter through [ReportGate], push accepted
 * fixes to a [LocationGateway], and keep the ones the network refused.
 *
 * Nothing in here names a vendor or a backend, which is the point: the same collector runs against the
 * framework provider today and against Baidu the day the AAR arrives.
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
) {
    private val gate = ReportGate(tuning)
    private val pending = ArrayDeque<LocationFix>()
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

    /** Feeds one fix through the gate; called by [listener], and by anything else that has a fix to offer. */
    fun accept(fix: LocationFix) {
        if (!gate.shouldReport(fix)) {
            onEvent("定位采集 过滤 ${fix.coord.lat},${fix.coord.lng} 上次上报后不足节律")
            return
        }
        if (pending.size >= tuning.pendingLimit) pending.removeFirst()
        pending.addLast(fix)
        scope.launch { flush() }
    }

    /** Sends what is queued, stopping at the first transport failure so order survives an outage. */
    suspend fun flush() {
        if (isFlushing) return
        isFlushing = true
        try {
            while (pending.isNotEmpty()) {
                val fix = pending.first()
                // The server has no notion of "this fix was already classified as a departure" until the
                // stay state machine exists, so everything goes out unclassified -- which is also what the
                // recovered bean carried when the SDK delivered it (type 0 at x0.java:304).
                runCatching { gateway.report(fix, ReportKind.UNCLASSIFIED) }
                    .onSuccess {
                        pending.removeFirst()
                        gate.reported(fix)
                        onEvent("定位上报 ok ${fix.coord.lat},${fix.coord.lng} 剩余=${pending.size}")
                    }
                    .onFailure {
                        onEvent("定位上报 失败 ${fix.coord.lat},${fix.coord.lng} 剩余=${pending.size} 原因=$it")
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
