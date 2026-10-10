package com.thelightphone.sample

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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// THE VIEWMODEL: the "brain" of the screen. It keeps track of the time and
// whether the run is ready, running, or paused. It knows nothing about how
// things look; it just holds the facts.
// ---------------------------------------------------------------------------
class HomeScreenViewModel : LightViewModel<Unit>() {

    // The three situations the stopwatch can be in.
    enum class RunState { Ready, Running, Paused }

    private val _runState = MutableStateFlow(RunState.Ready)
    val runState: StateFlow<RunState> = _runState

    // How long the run has lasted so far, in milliseconds.
    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis

    private var accumulatedMillis = 0L // time banked before the latest pause
    private var startedAt = 0L         // clock reading when we last pressed Start/Resume
    private var ticker: Job? = null    // the loop that refreshes the display

    fun start() {
        if (_runState.value == RunState.Running) return
        startedAt = SystemClock.elapsedRealtime()
        _runState.value = RunState.Running
        ticker = viewModelScope.launch {
            while (isActive) {
                _elapsedMillis.value =
                    accumulatedMillis + (SystemClock.elapsedRealtime() - startedAt)
                delay(200) // refresh five times a second
            }
        }
    }

    fun pause() {
        if (_runState.value != RunState.Running) return
        ticker?.cancel()
        accumulatedMillis += SystemClock.elapsedRealtime() - startedAt
        _elapsedMillis.value = accumulatedMillis
        _runState.value = RunState.Paused
    }

    fun reset() {
        ticker?.cancel()
        accumulatedMillis = 0L
        _elapsedMillis.value = 0L
        _runState.value = RunState.Ready
    }
}

// ---------------------------------------------------------------------------
// THE SCREEN: what you see. It reads the facts from the ViewModel and draws
// them, and it tells the ViewModel when a button is tapped.
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
        val themeColors by LightThemeController.colors.collectAsState()

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

                // The big clock.
                LightText(
                    text = formatElapsed(elapsed),
                    variant = LightTextVariant.Title,
                    modifier = Modifier.padding(bottom = 40.dp),
                )

                // The buttons change depending on the situation.
                Row {
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

// Turns milliseconds into "05:42" or, past an hour, "1:05:42".
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