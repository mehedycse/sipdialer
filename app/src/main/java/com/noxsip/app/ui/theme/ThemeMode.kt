package com.noxsip.app.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * অ্যাপের থিম পছন্দ: Light / Dark / System। ডিফল্ট Light, যাতে আগের চেহারা বদলে না যায়।
 * Application.onCreate-এ load() ডাকতে হবে।
 */
object ThemeMode {
    private const val PREFS_NAME = "ui_prefs"
    private const val KEY_MODE = "theme_mode"

    const val LIGHT = 0
    const val DARK = 1
    const val SYSTEM = 2

    var mode by mutableIntStateOf(LIGHT)
        private set

    fun load(context: Context) {
        mode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, LIGHT)
    }

    fun set(context: Context, value: Int) {
        mode = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, value).apply()
    }
}

/** বর্তমান থিম ডার্ক কি না (SipDialerTheme বসিয়ে দেয়) */
val LocalDarkTheme = compositionLocalOf { false }

/** লাইট থিমে `light`, ডার্ক থিমে `dark` রং দেয় */
@Composable
fun themed(light: Color, dark: Color): Color = if (LocalDarkTheme.current) dark else light
