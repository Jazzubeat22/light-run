package com.thelightphone.sample

import android.Manifest
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

// Ignore GPS readings less accurate than this (in meters).
private const val MAX_ACCURACY_METERS = 30.0

// Ignore movements smaller than this, so GPS "jitter" while standing still
// doesn't slowly add fake distance.
private const val MIN_STEP_METERS = 3.0

private const val METERS_PER_MILE = 1609.344

// ---------------------------------------------------------------------------
// THE VIEWMODEL: the "brain". It tracks time, distance, and run state.
// ---------------------------------------------------------------------------
class HomeScreenViewModel : LightViewModel<Unit>() {

    enum class RunState { Ready, Running, Paused }

    private val _runState = MutableStateFlow(RunState.Ready)
    val runState: StateFlow<RunState> = _runState

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis

    private val _distanceMeters = MutableStateFlow(0.0)
    val distanceMeters: StateFlow<Double> = _distanceMeters

    // null = still checking, true = allowed, false = not allowed yet
    private val _hasLocationPermission = MutableStateFlow<Boolean?>(null)
    val hasLocationPermission: StateFlow<Boolean?> = _hasLocationPermission

    // Whether we've received a usable GPS position since pressing Start/Resume.
    private val _hasGpsFix = MutableStateFlow(false)
    val hasGpsFix: StateFlow<Boolean> = _hasGpsFix

    // --- clock bookkeeping ---
    private var accumulatedMillis = 0L
    private var startedAt = 0L
    private var ticker: Job? = null

    // --- GPS bookkeeping ---
    private var trackingJob: Job? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastFixTimestamp: Long? = null

    // Called by the SDK every time this screen appears, including when you come
    // back from the "allow location?" prompt. So we re-check permission here.
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            val granted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                .getOrNull()
                ?.permissionResult == LightServiceMethod.GetPermission.Result.Granted
            _hasLocationPermission.value = granted
            // If permission was just granted mid-run, start measuring distance now.
            if (granted && _runState.value == RunState.Running && trackingJob?.isActive != true) {
                startTracking()
            }
        }
    }

    fun start() {
        if (_runState.value == RunState.Running) return
        startedAt = SystemClock.elapsedRealtime()
        _runState.value = RunState.Running
        ticker = viewModelScope.launch {
            while (isActive) {
                _elapsedMillis.value =
                    accumulatedMillis + (SystemClock.elapsedRealtime() - startedAt)
                delay(200)
            }
        }
        startTracking()
    }

    fun pause() {
        if (_runState.value != RunState.Running) return
        ticker?.cancel()
        trackingJob?.cancel()
        accumulatedMillis += SystemClock.elapsedRealtime() - startedAt
        _elapsedMillis.value = accumulatedMillis
        _runState.value = RunState.Paused
    }

    fun reset() {
        ticker?.cancel()
        trackingJob?.cancel()
        accumulatedMillis = 0L
        _elapsedMillis.value = 0L
        _distanceMeters.value = 0.0
        _runState.value = RunState.Ready
    }

    // Asks LightOS to keep the GPS on, and checks the latest position every
    // 2 seconds. Stops (and lets LightOS turn the GPS off) when cancelled.
    private fun startTracking() {
        if (_hasLocationPermission.value != true) return
        // Forget the last point, so the gap during a pause isn't counted.
        lastLat = null
        lastLon = null
        _hasGpsFix.value = false

        trackingJob = viewModelScope.launch {
            coroutineScope {
                val keepGpsOn = launch {
                    while (isActive) {
                        callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
                        delay(20.seconds) // renew before LightOS's ~30-second limit
                    }
                }
                try {
                    while (isActive) {
                        val fix = callRemoteServiceMethod(
                            LightServiceMethod.GetCurrentLocation, Unit
                        ).getOrNull()
                        if (fix != null) handleFix(fix)
                        delay(2.seconds)
                    }
                } finally {
                    keepGpsOn.cancel()
                    withContext(NonCancellable) {
                        callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
                    }
                }
            }
        }
    }

    private fun handleFix(fix: LightServiceMethod.GetCurrentLocation.Response) {
        val lat = fix.latitude ?: return
        val lon = fix.longitude ?: return
        val accuracy = fix.accuracyMeters ?: return
        val timestamp = fix.timestampMs ?: return

        if (timestamp == lastFixTimestamp) return // same reading as last time
        lastFixTimestamp = timestamp
        if (accuracy > MAX_ACCURACY_METERS) return // too fuzzy to trust

        _hasGpsFix.value = true

        val prevLat = lastLat
        val prevLon = lastLon
        if (prevLat == null || prevLon == null) {
            // First good point: nothing to measure from yet.
            lastLat = lat
            lastLon = lon
            return
        }

        val step = distanceBetween(prevLat, prevLon, lat, lon)
        if (step < MIN_STEP_METERS) return // probably jitter; wait for real movement

        _distanceMeters.value += step
        lastLat = lat
        lastLon = lon
    }
}

// ---------------------------------------------------------------------------
// THE SCREEN: what you see.
// ---------------------------------------------------------------------------
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel() = HomeScreenViewModel()

    @Composable
    override fun Content() {
        val runState by viewModel.runState.collectAsState()
        val elapsed by viewModel.elapsedMillis.collectAsState()
        val distance by viewModel.distanceMeters.collectAsState()
        val hasPermission by viewModel.hasLocationPermission.collectAsState()
        val hasGpsFix by viewModel.hasGpsFix.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val permissionLauncher =
            rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "Run",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 24.dp),
                )

                LightText(
                    text = formatElapsed(elapsed),
                    variant = LightTextVariant.Title,
                )

                LightText(
                    text = formatMiles(distance),
                    variant = LightTextVariant.Subtitle,
                    modifier = Modifier.padding(top = 8.dp),
                )

                LightText(
                    text = "Avg pace  ${formatPace(elapsed, distance)}",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 8.dp),
                )

                // A small status line about the GPS.
                val status = when {
                    hasPermission == false -> null
                    runState != HomeScreenViewModel.RunState.Running -> null
                    hasGpsFix -> "GPS ready"
                    else -> "Waiting for GPS…"
                }
                status?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (hasPermission == false) {
                    LightText(
                        text = "Distance needs location access.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    ActionText("Allow location") { permissionLauncher?.launch() }
                }

                Row(modifier = Modifier.padding(top = 32.dp)) {
                    when (runState) {
                        HomeScreenViewModel.RunState.Ready ->
                            ActionText("Start") { viewModel.start() }

                        HomeScreenViewModel.RunState.Running ->
                            ActionText("Pause") { viewModel.pause() }

                        HomeScreenViewModel.RunState.Paused -> {
                            ActionText("Resume") { viewModel.start() }
                            ActionText("Reset") { viewModel.reset() }
                        }
                    }
                }
            }
        }
    }
}

// A tappable word, used as a button.
@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Button,
        modifier = Modifier
            .padding(end = 32.dp)
            .lightClickable { onClick() }
            .padding(vertical = 12.dp),
    )
}

// ---------------------------------------------------------------------------
// Helpers: math and formatting.
// ---------------------------------------------------------------------------

// Distance in meters between two GPS points ("haversine" formula: it accounts
// for the earth being round).
private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * earthRadius * asin(sqrt(a))
}

// "05:42" or, past an hour, "1:05:42".
private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

// "1.24 mi"
private fun formatMiles(meters: Double): String =
    "%.2f mi".format(meters / METERS_PER_MILE)

// Average minutes per mile, e.g. "9:45 /mi". Shows dashes until there's
// enough distance for the number to mean anything.
private fun formatPace(elapsedMillis: Long, meters: Double): String {
    val miles = meters / METERS_PER_MILE
    if (miles < 0.05) return "--:-- /mi"
    val secondsPerMile = (elapsedMillis / 1000.0 / miles).toLong()
    return "%d:%02d /mi".format(secondsPerMile / 60, secondsPerMile % 60)
}