package com.chami.pdfmaker.theme

import android.content.Context
import androidx.compose.ui.graphics.Color

data class ChamiTheme(
  val id: String,
  val name: String,
  val subtitle: String,
  val bg: Color,
  val card: Color,
  val cardBorder: Color,
  val primary: Color,
  val primaryDark: Color,
  val text: Color,
  val textSecondary: Color,
  val textOnPrimary: Color,
  val accent: Color,
  val isDark: Boolean
)

object ChamiThemes {
  val EMERALD_PRO = ChamiTheme(
    id = "emerald_pro",
    name = "Executive Emerald",
    subtitle = "Clean light slate with forest emerald accents",
    bg = Color(0xFFF8FAFC),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E8F0),
    primary = Color(0xFF059669),
    primaryDark = Color(0xFF047857),
    text = Color(0xFF0F172A),
    textSecondary = Color(0xFF64748B),
    textOnPrimary = Color.White,
    accent = Color(0xFF10B981),
    isDark = false
  )

  val OBSIDIAN_AMOLED = ChamiTheme(
    id = "obsidian_amoled",
    name = "Obsidian AMOLED",
    subtitle = "Pitch black for OLED screens & battery saving",
    bg = Color(0xFF000000),
    card = Color(0xFF111113),
    cardBorder = Color(0xFF232326),
    primary = Color(0xFF10B981),
    primaryDark = Color(0xFF059669),
    text = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFA1A1AA),
    textOnPrimary = Color.Black,
    accent = Color(0xFF34D399),
    isDark = true
  )

  val ALL = listOf(
    EMERALD_PRO,
    OBSIDIAN_AMOLED
  )

  // Map any legacy or removed theme IDs cleanly to either Emerald Pro or Obsidian AMOLED
  fun byId(id: String): ChamiTheme {
    return when (id) {
      "obsidian_amoled", "dark_arcade", "sapphire_dark", "pixel_navy", "cyber_slate", "dark" -> OBSIDIAN_AMOLED
      else -> EMERALD_PRO
    }
  }
}

// ThemeManager - SharedPreferences only, fast, safe, survives app restarts.
class ThemeManager(ctx: Context) {
  private val p = ctx.getSharedPreferences("chami_theme", Context.MODE_PRIVATE)

  fun current(): ChamiTheme = ChamiThemes.byId(p.getString("theme_id", "emerald_pro") ?: "emerald_pro")

  fun set(id: String) {
    p.edit().putString("theme_id", id).apply()
  }

  fun hapticOn(): Boolean = p.getBoolean("haptic", true)
  fun setHaptic(v: Boolean) {
    p.edit().putBoolean("haptic", v).apply()
  }

  fun askSaveLocation(): Boolean = p.getBoolean("ask_save", true)
  fun setAskSaveLocation(v: Boolean) {
    p.edit().putBoolean("ask_save", v).apply()
  }
}
