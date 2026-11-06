package com.youloft.linkup.location.provider.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.youloft.linkup.location.Coord
import com.youloft.linkup.location.CoordSystem
import com.youloft.linkup.location.LocationCapabilities
import com.youloft.linkup.location.LocationFault
import com.youloft.linkup.location.LocationFailure
import com.youloft.linkup.location.LocationFix
import com.youloft.linkup.location.LocationListener as NeutralListener
import com.youloft.linkup.location.LocationPermission
import com.youloft.linkup.location.LocationProvider
import com.youloft.linkup.location.LocationRequest
import com.youloft.linkup.location.LocationResult
import com.youloft.linkup.location.canLocate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * The provider that works in this build today: the platform location service — no AAR, no key, no privacy
 * agreement. It is not a placeholder but the fallback every other candidate degrades to, and the reference
 * a vendor SDK has to beat before it is worth the dependency.
 *
 * Two things the recovered app leaned on cannot be answered here, and [capabilities] says so instead of
 * pretending: `inlinePlace = false` (an `android.location.Location` carries no address, so the
 * `x0.java:785` branch needs a [com.youloft.linkup.location.PlaceResolver]) and `poiList = false` (nothing
 * in the framework fills `Place.poiName`, which Baidu took from its first POI at `x0.java:299-304`).
 *
 * Speed needs no `/3.6` here: `Location.getSpeed()` is already metres per second, while Baidu reported
 * km/h (`x0.java:288`). Getting that unit wrong would change how often the app talks to the server by 3.6x,
 * because the cadence divides a metre budget by this number.
 */
class SystemLocationProvider(
    context: Context,
    private val permissions: LocationPermissions = LocationPermissions.of(context),
) : LocationProvider {
    private val manager: LocationManager? =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var request = LocationRequest.FOREGROUND

    /** The framework listener currently registered; null means this provider is not following. */
    private var target: LocationListener? = null
    private var last: LocationFix? = null

    override val id = "system"

    override val capabilities = LocationCapabilities(
        nativeCoordSystem = CoordSystem.WGS84,
        inlinePlace = false,
        poiList = false,
        needsVendorKey = false,
        // Re-registering the same listener is cheap, so a dormancy switch does not need stop+start.
        supportsRuntimeInterval = true,
    )

    override val isInstalled: Boolean
        get() = manager != null

    override fun start(request: LocationRequest, listener: NeutralListener): Boolean {
        val manager = this.manager
            ?: return refuse(listener, LocationFailure.SERVICE_UNAVAILABLE, "本设备没有 location service")
        val permission = permissions.state()
        if (!permission.canLocate()) return refuse(listener, LocationFailure.NO_PERMISSION, permission.name)
        val providers = manager.enabledProviders()
        if (providers.isEmpty()) {
            return refuse(listener, LocationFailure.SERVICE_UNAVAILABLE, "位置服务全部关闭: ${manager.allProviders}")
        }
        stop()
        target = listener.asFrameworkListener().also { framework ->
            providers.forEach { manager.register(it, framework, request.intervalMillis) }
        }
        this.request = request
        return true
    }

    override fun update(request: LocationRequest): Boolean {
        val manager = this.manager ?: return false
        val framework = target ?: return false
        if (request == this.request) return false
        val providers = manager.enabledProviders()
        if (providers.isEmpty()) return false
        // The caller's listener is unchanged, so only the framework registration is swapped -- this is what
        // `supportsRuntimeInterval = true` promises the collector, whose dormancy switch depends on it.
        manager.removeUpdates(framework)
        providers.forEach { manager.register(it, framework, request.intervalMillis) }
        this.request = request
        return true
    }

    override fun stop() {
        target?.let { manager?.removeUpdates(it) }
        target = null
    }

    override suspend fun singleFix(request: LocationRequest): LocationResult {
        val permission = permissions.state()
        if (!permission.canLocate()) {
            return LocationResult.Failed(LocationFault(LocationFailure.NO_PERMISSION, detail = permission.name))
        }
        val manager = this.manager
            ?: return LocationResult.Failed(
                LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "本设备没有 location service"),
            )
        val providers = manager.enabledProviders()
        if (providers.isEmpty()) {
            return LocationResult.Failed(LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "位置服务全部关闭"))
        }
        var framework: LocationListener? = null
        val outcome = try {
            withTimeout(request.singleFixTimeoutMillis) {
                suspendCancellableCoroutine<LocationResult> { continuation ->
                    val once = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            val fix = location.toNeutralFix()
                            last = fix
                            if (continuation.isActive) continuation.resume(LocationResult.Located(fix))
                        }
                    }
                    framework = once
                    providers.forEach { manager.register(it, once, 0L) }
                    continuation.invokeOnCancellation { manager.removeUpdates(once) }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            return LocationResult.Failed(
                LocationFault(LocationFailure.TIMEOUT, detail = "${request.singleFixTimeoutMillis}ms 内没有定位结果"),
            )
        } finally {
            // Every exit path, not only cancellation: a *successful* one-shot would otherwise leave one
            // 0 ms registration per enabled provider calling back forever, and stop()/close() cannot see it.
            framework?.let { manager.removeUpdates(it) }
        }
        return outcome
    }

    override fun lastFix(): LocationFix? = last

    override fun close() = stop()

    private fun NeutralListener.asFrameworkListener() = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = location.toNeutralFix()
            last = fix
            onFix(fix)
        }

        override fun onProviderDisabled(provider: String) {
            onFailure(LocationFault(LocationFailure.SERVICE_UNAVAILABLE, detail = "provider=$provider 已关闭"))
        }
    }

    private fun refuse(listener: NeutralListener, reason: LocationFailure, detail: String): Boolean {
        listener.onFailure(LocationFault(reason, detail = detail))
        return false
    }

    /** The framework reports WGS84 directly, so no datum conversion happens on this path. */
    private fun Location.toNeutralFix() = LocationFix(
        coord = Coord(latitude, longitude, CoordSystem.WGS84),
        epochSeconds = time / 1000,
        accuracyMeters = accuracy,
        speedMetersPerSecond = if (hasSpeed()) speed else 0f,
        bearingDegrees = if (hasBearing()) bearing else null,
        // Which platform provider produced it matters for a track row; `Location.getId()` is not visible at
        // compileSdk 34, so the name the fix itself carries is what we can honestly record.
        providerId = this.provider ?: id,
    )

    private fun LocationManager.register(provider: String, listener: LocationListener, intervalMillis: Long) {
        requestLocationUpdates(provider, intervalMillis, 0f, listener, Looper.getMainLooper())
    }

    private fun LocationManager.enabledProviders(): List<String> = allProviders.filter { isProviderEnabled(it) }
}

/**
 * Location permission as this layer needs it. Behind a type because the three answers (fine / coarse only
 * / none) drive different UI, and a JVM test has to be able to drive all three.
 */
interface LocationPermissions {
    fun state(): LocationPermission

    companion object {
        fun of(context: Context): LocationPermissions = DeviceLocationPermissions(context)
    }
}

private class DeviceLocationPermissions(context: Context) : LocationPermissions {
    private val appContext = context.applicationContext

    override fun state(): LocationPermission {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> LocationPermission.GRANTED_FINE
            coarse -> LocationPermission.GRANTED_COARSE_ONLY
            else -> LocationPermission.DENIED
        }
    }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
