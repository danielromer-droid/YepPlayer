package com.yepplayer.app

import android.content.SharedPreferences
import android.graphics.Color

/** A ready-made colour set. */
data class ThemePreset(val name: String, val accent: Int, val onAccent: Int, val highlight: Int)
data class Backdrop(val name: String, val bg: Int, val rail: Int, val panel: Int, val panel2: Int, val border: Int, val line: Int)
data class AccentChoice(val name: String, val color: Int, val onColor: Int)

/** Current colours of the app. Defaults match the YepPlayer sofa icon; users can change them in Settings › Colours. */
object C {
    private fun c(hex: String) = Color.parseColor(hex)

    val presets = listOf(
        ThemePreset("YepPlayer sofa", c("#F28C3C"), c("#1A1208"), c("#3FC1C9")),
        ThemePreset("Ocean blue", c("#2F7BFF"), c("#FFFFFF"), c("#FF9F43")),
        ThemePreset("Emerald", c("#2BB673"), c("#0B1A12"), c("#FFC23D")),
        ThemePreset("Purple night", c("#8B5CF6"), c("#FFFFFF"), c("#3FC1C9")),
        ThemePreset("Cinema red", c("#E5484D"), c("#FFFFFF"), c("#FFC23D")),
        ThemePreset("Gold", c("#F5B82E"), c("#1A1405"), c("#3FC1C9"))
    )
    val backdrops = listOf(
        Backdrop("Navy", c("#0B1622"), c("#0E1C29"), c("#132433"), c("#1B3142"), c("#24404F"), c("#1D3443")),
        Backdrop("Black", c("#050608"), c("#0A0C10"), c("#121418"), c("#1B1E24"), c("#2A2E36"), c("#1A1D22")),
        Backdrop("Slate", c("#161A21"), c("#1B2029"), c("#222834"), c("#2C3442"), c("#3A4352"), c("#2A303B"))
    )
    val accents = listOf(
        AccentChoice("Orange", c("#F28C3C"), c("#1A1208")), AccentChoice("Blue", c("#2F7BFF"), c("#FFFFFF")),
        AccentChoice("Teal", c("#14B8A6"), c("#04211D")), AccentChoice("Green", c("#2BB673"), c("#0B1A12")),
        AccentChoice("Purple", c("#8B5CF6"), c("#FFFFFF")), AccentChoice("Pink", c("#EC4899"), c("#FFFFFF")),
        AccentChoice("Red", c("#E5484D"), c("#FFFFFF")), AccentChoice("Gold", c("#F5B82E"), c("#1A1405"))
    )

    var themeIndex = 0; private set
    var accentIndex = -1; private set
    var backdropIndex = 0; private set

    var bg = 0; private set
    var rail = 0; private set
    var panel = 0; private set
    var panel2 = 0; private set
    var border = 0; private set
    var line = 0; private set
    var accent = 0; private set
    var onAccent = 0; private set
    var highlight = 0; private set
    /** Lighter version of the accent for small icons and section labels. */
    var soft = 0; private set
    var accentTint = 0; private set

    val gold = c("#FFC23D")
    val red = c("#E5484D")
    val text = c("#F2F5FA")
    val text2 = c("#C9D3E1")
    val muted = c("#9AA8BC")
    val faint = c("#7A889C")

    init { set(0, -1, 0) }

    fun load(prefs: SharedPreferences) = set(prefs.getInt("theme", 0), prefs.getInt("theme_accent", -1), prefs.getInt("theme_bg", 0))

    fun save(prefs: SharedPreferences, theme: Int, accentChoice: Int, backdrop: Int) {
        prefs.edit().putInt("theme", theme).putInt("theme_accent", accentChoice).putInt("theme_bg", backdrop).apply()
        set(theme, accentChoice, backdrop)
    }

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun set(theme: Int, accentChoice: Int, backdrop: Int) {
        themeIndex = theme.coerceIn(0, presets.lastIndex)
        accentIndex = if (accentChoice in accents.indices) accentChoice else -1
        backdropIndex = backdrop.coerceIn(0, backdrops.lastIndex)
        val p = presets[themeIndex]; val b = backdrops[backdropIndex]
        bg = b.bg; rail = b.rail; panel = b.panel; panel2 = b.panel2; border = b.border; line = b.line
        if (accentIndex >= 0) { accent = accents[accentIndex].color; onAccent = accents[accentIndex].onColor }
        else { accent = p.accent; onAccent = p.onAccent }
        highlight = p.highlight
        soft = mix(accent, Color.WHITE, 0.25f)
        accentTint = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent))
    }
}
