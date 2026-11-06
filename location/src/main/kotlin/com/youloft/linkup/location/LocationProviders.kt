package com.youloft.linkup.location

/**
 * Ordered provider selection, so "which SDK is in this build" is asked in exactly one place.
 *
 * Candidates are tried in list order and the first one that reports itself installed wins; the rest stay
 * as fallbacks. Nothing here instantiates a vendor class — the caller does, because only the caller knows
 * whether the AAR was added. `LocationProviders.default` in `:app` is that one place, and its order is a
 * deliberate fidelity choice: Baidu first because that is what shipped, framework second because the
 * framework is the only thing guaranteed to be there.
 */
class LocationProviders(private val candidates: List<LocationProvider>) {
    init {
        require(candidates.isNotEmpty()) { "a location layer with no candidates cannot answer anything" }
        val ids = candidates.map { it.id }
        require(ids.distinct().size == ids.size) { "provider ids must be unique, got $ids" }
    }

    /** Candidates that can run in this build at all (SDK present, key configured). */
    fun installed(): List<LocationProvider> = candidates.filter { it.isInstalled }

    /** The provider to hand a [LocationCollector]; null means the device has no location source. */
    fun preferred(): LocationProvider? = installed().firstOrNull()

    /** By id, whether installed or not — used by the status screen and by tests. */
    fun find(id: String): LocationProvider? = candidates.firstOrNull { it.id == id }

    /** One line per candidate, installed or not, for logs and for the on-device vendor comparison. */
    fun describe(): String = candidates.joinToString("; ") { candidate ->
        "${candidate.id} installed=${candidate.isInstalled} datum=${candidate.capabilities.nativeCoordSystem}" +
            " inlinePlace=${candidate.capabilities.inlinePlace} poi=${candidate.capabilities.poiList}" +
            " runtimeInterval=${candidate.capabilities.supportsRuntimeInterval}"
    }
}
