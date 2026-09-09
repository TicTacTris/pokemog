package dev.pokemog.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutoScanSettings {
    const val KEY = "auto_scan_appraisals"
    fun enabled(context: Context): Boolean = PokeMogAppearance.preferences(context).getBoolean(KEY, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        PokeMogAppearance.preferences(context).edit().putBoolean(KEY, enabled).apply()
    }
}

/** Session feedback only. This status is never persisted; the opt-in setting is. */
object AutoScanStatus {
    private val mutable = MutableStateFlow("Start an overlay session, then scan once.")
    val state: StateFlow<String> = mutable.asStateFlow()
    fun update(status: String) { mutable.value = status }
}
