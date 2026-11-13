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

// Phase 6 — THEME LOCK: Executive Emerald is the only theme.
// current() always returns emerald_pro no matter what is saved, and
// set() forces emerald_pro instead of storing the requested id.
// The Obsidian theme object is kept so old references still compile,
// but it can no longer be selected.
class ThemeManager(ctx: Context) {
  private val p = ctx.getSharedPreferences("chami_theme", Context.MODE_PRIVATE)

  fun current(): ChamiTheme = ChamiThemes.EMERALD_PRO

  fun set(id: String) {
    // No-op by design (Phase 6): theme is locked to Executive Emerald.
    p.edit().putString("theme_id", "emerald_pro").apply()
  }

  // Phase 5: haptic is ALWAYS ON — there is no toggle in Settings any
  // more, so this always returns true (a user who turned it off in an
  // older version gets haptic back). setHaptic is kept only so old call
  // sites still compile; it intentionally does nothing now.
  fun hapticOn(): Boolean = true
  fun setHaptic(v: Boolean) {
    // No-op by design (Phase 5): haptic cannot be turned off.
  }

}
