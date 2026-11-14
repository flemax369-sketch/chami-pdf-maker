package com.chami.pdfmaker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.R

/** Executive Emerald — exact spec §2 values (the only theme). */
object ChamiColors {
    val brightGreen: Color = Color(0xFF22C55E)
    val darkGreen: Color = Color(0xFF16A34A)
    val deepGreen: Color = Color(0xFF14532D)
    val lightGreen: Color = Color(0xFFDCFCE7)
    val darkModeBg: Color = Color(0xFF052E16)
    val bodyText: Color = Color(0xFF1A2B1F)
    val secondaryText: Color = Color(0xFF5B6B5F)
    val errorRed: Color = Color(0xFFB91C1C)
    val warningAmber: Color = Color(0xFFB45309)
}

/**
 * Design system — Professional Spec List §2–§3 (19-page spec, 2026-11-14).
 * Single source of truth for spacing, typography, colours, radii and touch targets.
 * Every screen must use these tokens — no literal dp/sp values outside this file
 * except where a component genuinely needs a one-off size (document it inline).
 *
 * Spacing scale (4dp grid): 4 / 8 / 12 / 16 / 20 / 24 / 32dp only.
 * Screen side margin: 16dp everywhere. Minimum touch target: 48x48dp.
 * Typography: title 20sp bold, section 16sp bold, tile/body 14sp,
 * button 15sp semibold, hint/meta 12sp, nav label 11sp. Line height 1.4x.
 */
object Ds {
    val s4: Dp = 4.dp
    val s8: Dp = 8.dp
    val s12: Dp = 12.dp
    val s16: Dp = 16.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    const val TITLE_SP = 20
    const val SECTION_SP = 16
    const val TILE_SP = 14
    const val BODY_SP = 14
    const val BUTTON_SP = 15
    const val HINT_SP = 12
    const val META_SP = 12
    const val NAV_SP = 11

    val tileRadius: Dp = 12.dp
    val buttonRadius: Dp = 10.dp
    val dialogRadius: Dp = 16.dp
    val primaryButtonHeight: Dp = 52.dp
    val secondaryButtonHeight: Dp = 48.dp
    val minTouch: Dp = 48.dp
    val slideHeight: Dp = 56.dp
    val slideHandle: Dp = 48.dp
    val appBarHeight: Dp = 56.dp
    val bottomNavHeight: Dp = 64.dp
    val thumbStripHeight: Dp = 72.dp
    val listRowMinHeight: Dp = 56.dp
}

/** Real pixel font (Press Start 2P, OFL) — replaces the old SansSerif alias.
 *  The font bytes live in [PixelFontData] (Base64) and are decoded into a
 *  platform Typeface at first use — the TTF is binary and cannot be pushed
 *  through the GitHub contents API, so it ships as text. If decoding ever
 *  fails, the system default is used rather than crashing. */
val PixelFontFamily: FontFamily by lazy { buildPixelFontFamily() }

private fun buildPixelFontFamily(): FontFamily {
  return try {
    val bytes = android.util.Base64.decode(PixelFontData.base64, android.util.Base64.DEFAULT)
    val buffer = java.nio.ByteBuffer.allocateDirect(bytes.size).apply {
      put(bytes)
      flip()
    }
    val font = android.graphics.fonts.Font.Builder(buffer).build()
    val family = android.graphics.fonts.FontFamily.Builder(font).build()
    val typeface = android.graphics.Typeface.CustomFallbackBuilder(family).build()
    FontFamily(typeface)
  } catch (_: Exception) {
    FontFamily.Default
  }
}

/** Headings / big numbers in the pixel font. Body text stays in the system sans
 *  (pixel font at body sizes is unreadable); use [PixelFontFamily] only for
 *  titles, group headings and the CHAMI PRO header, at 20sp / 16sp. */
@Composable
fun PixelTitle(text: String, sizeSp: Int = Ds.TITLE_SP, color: Color = ChamiColors.deepGreen) {
    Text(text, fontFamily = PixelFontFamily, fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4).sp, color = color)
}

@Composable
fun TitleText(text: String, color: Color = ChamiColors.deepGreen) {
    Text(text, fontSize = Ds.TITLE_SP.sp, fontWeight = FontWeight.Bold,
        lineHeight = (Ds.TITLE_SP * 1.4).sp, color = color)
}

@Composable
fun SectionText(text: String, color: Color = ChamiColors.deepGreen) {
    Text(text, fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold,
        lineHeight = (Ds.SECTION_SP * 1.4).sp, color = color)
}

@Composable
fun BodyText(text: String, color: Color = Color(0xFF1A2B1F)) {
    Text(text, fontSize = Ds.BODY_SP.sp, lineHeight = (Ds.BODY_SP * 1.4).sp, color = color)
}

@Composable
fun HintText(text: String, color: Color = Color(0xFF5B6B5F)) {
    Text(text, fontSize = Ds.HINT_SP.sp, lineHeight = (Ds.HINT_SP * 1.4).sp, color = color)
}

@Composable
fun MetaText(text: String, color: Color = Color(0xFF5B6B5F)) {
    Text(text, fontSize = Ds.META_SP.sp, lineHeight = (Ds.META_SP * 1.4).sp, color = color)
}

/** Option chip — always 48dp tall (spec §3), fully rounded, dark-green outline
 *  when selected, light-green fill when selected. Replaces Phase6Chip (~27dp). */
@Composable
fun ChamiChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = Ds.minTouch).clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) ChamiColors.lightGreen else Color.White,
        border = BorderStroke(1.5.dp, if (selected) ChamiColors.darkGreen else Color(0xFFCBD5E1)),
    ) {
        Box(Modifier.padding(horizontal = Ds.s16, vertical = Ds.s12), contentAlignment = Alignment.Center) {
            Text(label, fontSize = Ds.BODY_SP.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) ChamiColors.deepGreen else Color(0xFF1A2B1F), textAlign = TextAlign.Center)
        }
    }
}

/** 48dp icon button with a real vector icon (never emoji / text glyphs). */
@Composable
fun ChamiIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ChamiColors.deepGreen,
) {
    IconButton(onClick = onClick, modifier = modifier.size(Ds.minTouch)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Primary button — 52dp, bright green, white 15sp semibold (spec §5). */
@Composable
fun ChamiPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(Ds.primaryButtonHeight),
        shape = RoundedCornerShape(Ds.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = ChamiColors.brightGreen, disabledContainerColor = ChamiColors.lightGreen,
            contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.5f)),
    ) { Text(text, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
}

/** Secondary button — 48dp, white fill, dark-green outline (spec §5). */
@Composable
fun ChamiSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier.height(Ds.secondaryButtonHeight),
        shape = RoundedCornerShape(Ds.buttonRadius),
        border = BorderStroke(1.5.dp, ChamiColors.darkGreen),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ChamiColors.darkGreen),
    ) { Text(text, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
}

/** Circular 48dp icon tile used for the left-corner preview actions
 *  (Crop / Delete / Filter / Adjust) — icon + spoken label, never a glyph. */
@Composable
fun PreviewActionButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(Ds.minTouch).background(Color.White, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = ChamiColors.deepGreen, modifier = Modifier.size(24.dp)) }
}
