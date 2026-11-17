package kr.ac.campusroute.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** Foreground-only stand-alone adapter. Does not touch A/E listeners, storage or servers. */
class AndroidLocationFeed(context: Context) : LocationFeed {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private var latest: MapFix? = null
    fun hasPermission() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || hasFinePermission()
    private fun hasFinePermission() = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun updates() = callbackFlow {
        var registered = false
        var available = false
        fun emitState() {
            if (!hasPermission()) { latest=null; trySend(MapLocationState(LocationCondition.PERMISSION_REQUIRED)); return }
            val enabled = manager?.let { it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } == true
            if (!enabled) { trySend(MapLocationState(LocationCondition.DISABLED, latest)); return }
            val value = latest
            trySend(if (value != null) classifyFix(value, SystemClock.elapsedRealtime()) else MapLocationState(if (available) LocationCondition.WAITING else LocationCondition.UNAVAILABLE))
        }
        fun accept(location: Location) {
            if (!hasPermission()) { emitState(); return }
            val fix = runCatching {
                MapFix(location.latitude, location.longitude,
                    if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    location.time, location.elapsedRealtimeNanos / 1_000_000,
                    if (location.hasBearing()) location.bearing.toDouble() else null,
                    !hasFinePermission(), location.provider ?: "device",
                    if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider)
            }.getOrNull() ?: return
            val old=latest
            if (old != null && fix.elapsedRealtimeMs <= old.elapsedRealtimeMs) return
            latest=fix
            emitState()
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = accept(location)
            override fun onProviderEnabled(provider: String) = emitState()
            override fun onProviderDisabled(provider: String) = emitState()
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = emitState()
        }
        fun subscribe() {
            runCatching { manager?.removeUpdates(listener) }
            available=false
            if (hasPermission() && manager != null) {
                val providers = listOfNotNull(if (hasFinePermission()) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
                providers.filter { it in manager.allProviders }.forEach { provider ->
                    try {
                        manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                        available=true
                        manager.getLastKnownLocation(provider)?.let(::accept)
                    } catch (_: SecurityException) { latest=null }
                      catch (_: IllegalArgumentException) { /* Provider absent on this device. */ }
                }
            }
            emitState()
        }
        val receiver=object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { subscribe() } }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        registered=true
        subscribe()
        val expiry = launch { while (true) { delay(1000); emitState() } }
        awaitClose {
            expiry.cancel()
            runCatching { manager?.removeUpdates(listener) }
            if (registered) app.unregisterReceiver(receiver)
        }
    }
}
