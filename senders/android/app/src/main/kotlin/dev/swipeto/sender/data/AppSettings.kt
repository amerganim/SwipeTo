package dev.swipeto.sender.data

import android.content.Context
import dev.swipeto.sender.aim.Sensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings: sensitivity, camera targeting, raise-to-aim on/off, debug overlay, first run seen. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("swipeto_settings", Context.MODE_PRIVATE)

    private val _sensitivity = MutableStateFlow(
        runCatching { Sensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, null) ?: "") }.getOrDefault(Sensitivity.NORMAL),
    )
    val sensitivity: StateFlow<Sensitivity> = _sensitivity.asStateFlow()

    private val _raiseToAim = MutableStateFlow(prefs.getBoolean(KEY_RAISE, true))
    val raiseToAim: StateFlow<Boolean> = _raiseToAim.asStateFlow()

    private val _cameraTargeting = MutableStateFlow(prefs.getBoolean(KEY_CAMERA, true))
    val cameraTargeting: StateFlow<Boolean> = _cameraTargeting.asStateFlow()

    private val _debugOverlay = MutableStateFlow(prefs.getBoolean(KEY_DEBUG, false))
    val debugOverlay: StateFlow<Boolean> = _debugOverlay.asStateFlow()

    /** Keep the last-used screen loading what plays, so a swipe starts at once (costs that screen Wi-Fi). */
    private val _prepareScreen = MutableStateFlow(prefs.getBoolean(KEY_PREPARE, true))
    val prepareScreen: StateFlow<Boolean> = _prepareScreen.asStateFlow()

    fun setPrepareScreen(value: Boolean) {
        _prepareScreen.value = value
        prefs.edit().putBoolean(KEY_PREPARE, value).apply()
    }

    /** The first-run walkthrough was seen. */
    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setOnboarded(value: Boolean) {
        _onboarded.value = value
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
    }

    fun setSensitivity(value: Sensitivity) {
        _sensitivity.value = value
        prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()
    }

    fun setRaiseToAim(value: Boolean) {
        _raiseToAim.value = value
        prefs.edit().putBoolean(KEY_RAISE, value).apply()
    }

    fun setCameraTargeting(value: Boolean) {
        _cameraTargeting.value = value
        prefs.edit().putBoolean(KEY_CAMERA, value).apply()
    }

    fun setDebugOverlay(value: Boolean) {
        _debugOverlay.value = value
        prefs.edit().putBoolean(KEY_DEBUG, value).apply()
    }

    private companion object {
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_RAISE = "raise_to_aim"
        const val KEY_CAMERA = "camera_targeting"
        const val KEY_DEBUG = "debug_overlay"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_PREPARE = "prepare_screen"
    }
}
