package com.youloft.linkup.map

import android.content.Context
import android.os.Bundle
import android.view.View

/**
 * One decoded track, in the currency the app stores: **WGS84**.
 *
 * The adapters convert to their vendor's datum on the way to the screen (Baidu draws BD09, AMap GCJ02) —
 * which is why this type carries no vendor classes and no datum field of its own: a fix that reaches here
 * has already been normalized once, at the provider boundary, and the map is the second consumer of the
 * same number the track list renders.
 */
data class MapLatLng(val lat: Double, val lng: Double)

/** A stay point on the track — the `tracks[].type == 2` rows the server marks as arrivals. */
data class StayMark(val coord: MapLatLng, val timePeriod: Long)

/** Everything one day's track draws: the walked path and its stay points. */
data class TrackOverlay(val path: List<MapLatLng>, val stays: List<StayMark>)

/**
 * One map, attached to a screen. Everything above this — the track page, the widgets — compiles without
 * knowing whether a basemap came from Baidu, AMap, or nothing at all; with no vendor installed the screen
 * keeps its own "尚未接入" state and never constructs one of these.
 *
 * The lifecycle mirrors the vendor map views' own contract, forwarded verbatim from the hosting
 * activity/fragment: [onCreate] before first display, the pair pairs around visibility, [onSaveState] at
 * the same moment the host saves its own state, [onDestroy] last.
 */
interface TrackMap {
    /** The vendor view; add it to a container with match_parent before [onCreate]. */
    val view: View

    val id: String

    fun onCreate(state: Bundle?)

    fun onResume()

    fun onPause()

    fun onDestroy()

    fun onSaveState(): Bundle?

    /** Draws one day's track, replacing whatever was on the map. An empty overlay clears. */
    fun showTrack(overlay: TrackOverlay)

    /** Centers the camera on one point at the track-viewing zoom. */
    fun focus(coord: MapLatLng)
}

/**
 * Where a [TrackMap] comes from, mirroring the location layer's provider shape: `isInstalled` answers
 * "can this build show a basemap at all" (AAR present, privacy agreed) — not whether the user granted
 * anything, which the screen answers separately.
 */
interface TrackMapProvider {
    val id: String

    val isInstalled: Boolean

    fun create(context: Context): TrackMap
}

/**
 * Ordered provider selection, the location layer's own shape: candidates in list order, the first
 * installed one wins, [find] reaches a specific vendor for the debug screen. Nothing here instantiates a
 * vendor class — the caller does, because only the caller knows whether the AAR was added.
 */
class TrackMapProviders(private val candidates: List<TrackMapProvider>) {
    init {
        require(candidates.isNotEmpty()) { "a map layer with no candidates cannot answer anything" }
        val ids = candidates.map { it.id }
        require(ids.distinct().size == ids.size) { "provider ids must be unique, got $ids" }
    }

    /** Candidates that can run in this build at all (SDK present, privacy agreed). */
    fun installed(): List<TrackMapProvider> = candidates.filter { it.isInstalled }

    /** The provider to build a map from; null means this build has no basemap and the screen keeps its mask. */
    fun preferred(): TrackMapProvider? = installed().firstOrNull()

    /** By id, whether installed or not — used by the status screen and by tests. */
    fun find(id: String): TrackMapProvider? = candidates.firstOrNull { it.id == id }

    /** One line per candidate, installed or not, for logs. */
    fun describe(): String = candidates.joinToString("; ") { "${it.id} installed=${it.isInstalled}" }
}
