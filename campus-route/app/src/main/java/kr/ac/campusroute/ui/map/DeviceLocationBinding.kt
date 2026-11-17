package kr.ac.campusroute.ui.map

import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.ac.campusroute.location.*

data class DeviceLocationBinding(val state: MapLocationState, val denied: Boolean, val requestPermission: () -> Unit, val openSettings: () -> Unit, val headingDegrees: Double? = null)

/** Standalone host adapter; D-1 can instead supply the existing A/B state to CampusMapPanel. */
@Composable
fun rememberDeviceLocation(): DeviceLocationBinding {
    val context=LocalContext.current
    val feed=remember(context) { AndroidLocationFeed(context) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(MapLocationState(LocationCondition.PERMISSION_REQUIRED)) }
    var denied by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        denied=grants.values.none { it }
        permissionRevision++
    }
    LaunchedEffect(feed,lifecycle,permissionRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { feed.updates().collect { state=it } }
            finally { state=if (feed.hasPermission()) state.copy(condition=LocationCondition.PAUSED) else MapLocationState(LocationCondition.PERMISSION_REQUIRED) }
        }
    }
    val heading = rememberDeviceHeading(state)
    return DeviceLocationBinding(state,denied,
        requestPermission={ launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) },
        openSettings={
            val intent=if (state.condition==LocationCondition.DISABLED) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:${context.packageName}".toUri())
            context.startActivity(intent)
        }, headingDegrees=heading)
}


/** Display-only phone heading, corrected from magnetic north; separate from GPS course. */
@Composable
private fun rememberDeviceHeading(location: kr.ac.campusroute.location.MapLocationState): Double? {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var heading by remember { mutableStateOf<Double?>(null) }
    val latest by rememberUpdatedState(location)
    DisposableEffect(context,lifecycle) {
        val tracker=kr.ac.campusroute.location.HeadingTracker(context, { value ->
            val fix=latest.fix
            heading=if(value.degrees!=null && latest.canFollow && fix!=null) {
                val declination=android.hardware.GeomagneticField(fix.latitude.toFloat(),fix.longitude.toFloat(),0f,fix.measuredAtUtcMs).declination
                (value.degrees+declination+360)%360
            } else null
        }, { (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation })
        fun update() {
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tracker.start(0.0)
            else { tracker.stop(); heading=null }
        }
        val observer=LifecycleEventObserver { _,_ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); tracker.stop() }
    }
    return if(location.canFollow) heading else null
}
