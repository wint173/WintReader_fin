package com.example.wintreader

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** アプリの色の基調（テーマの色）。DYNAMIC は Android 12 以降の壁紙の色、CUSTOM は自分で選んだ色 */
enum class AccentPreset(val label: String, val color: Color?) {
    DYNAMIC("端末の色", null),
    WHITE("ホワイト", Color(0xFFF2F2F2)),
    CRIMSON("クリムゾン", Color(0xFFE53935)),
    OCEAN("オーシャン", Color(0xFF1E88E5)),
    VIOLET("バイオレット", Color(0xFF8E24AA)),
    EMERALD("エメラルド", Color(0xFF43A047)),
    AMBER("アンバー", Color(0xFFFB8C00)),
    ROSE("ローズ", Color(0xFFD81B60)),
    CUSTOM("カスタム", null),
}

/**
 * アプリの見た目（SET-01）。明るさ（端末に合わせる／ライト／ダーク）、テーマの色、AMOLED ブラックを設定で選べる。
 * 読書画面の色は表示設定で別に選ぶ。
 */
@Composable
fun WintTheme(prefs: AppPrefs, content: @Composable () -> Unit) {
    val dark = when (prefs.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val dynamicOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base: ColorScheme = when {
        prefs.accent == AccentPreset.DYNAMIC && dynamicOk ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            val seed = when (prefs.accent) {
                AccentPreset.CUSTOM -> Color(prefs.customColor)
                AccentPreset.DYNAMIC -> AccentPreset.OCEAN.color!! // Android 11 以前は壁紙の色を使えない
                else -> prefs.accent.color!!
            }
            seededScheme(seed, dark)
        }
    }
    val colors = if (dark && prefs.amoled) base.toAmoled() else base

    // ステータスバー・ナビゲーションバーのアイコンの色も、選んだ見た目に合わせる
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 画面が切り替わる一瞬に見える窓の下地も、テーマの背景の色にする（白く光らないように）
            window.decorView.setBackgroundColor(colors.background.toArgb())
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** 色の上に置く文字の色（明るい色なら黒、暗い色なら白） */
fun contentColorOn(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

/**
 * 1つの色（seed）から、アプリ全体の配色を作る。
 * 主役の色（primary）は seed、背景や面は灰色にほんの少しだけ seed の色を混ぜる（色味をそろえるため）
 */
fun seededScheme(seedIn: Color, dark: Boolean): ColorScheme {
    // ホワイトのような明るい灰色は、ライトでは見えないので濃い灰色にする
    val nearWhite = seedIn.luminance() > 0.8f
    val seed = if (!dark && nearWhite) Color(0xFF424242) else seedIn
    return if (dark) {
        val primary = if (nearWhite) seed else lerp(seed, Color.White, 0.25f)
        val bg = lerp(Color(0xFF121212), seed, 0.04f)
        val surface = bg
        darkColorScheme(
            primary = primary,
            onPrimary = contentColorOn(primary),
            primaryContainer = lerp(seed, Color.Black, 0.55f),
            onPrimaryContainer = lerp(seed, Color.White, 0.8f),
            inversePrimary = lerp(seed, Color.Black, 0.2f),
            secondary = lerp(primary, Color(0xFFBDBDBD), 0.5f),
            onSecondary = Color(0xFF1A1A1A),
            secondaryContainer = lerp(Color(0xFF2E2E2E), seed, 0.18f),
            onSecondaryContainer = lerp(Color.White, seed, 0.1f),
            tertiary = lerp(primary, Color(0xFF80CBC4), 0.35f),
            onTertiary = Color(0xFF102020),
            background = bg,
            onBackground = Color(0xFFE6E3E6),
            surface = surface,
            onSurface = Color(0xFFE6E3E6),
            surfaceVariant = lerp(Color(0xFF2B2B2B), seed, 0.08f),
            onSurfaceVariant = lerp(Color(0xFFC6C3C6), seed, 0.06f),
            surfaceTint = primary,
            outline = lerp(Color(0xFF8E8E8E), seed, 0.1f),
            outlineVariant = lerp(Color(0xFF444444), seed, 0.08f),
            surfaceContainerLowest = lerp(Color(0xFF0D0D0D), seed, 0.02f),
            surfaceContainerLow = lerp(Color(0xFF1A1A1A), seed, 0.04f),
            surfaceContainer = lerp(Color(0xFF1E1E1E), seed, 0.05f),
            surfaceContainerHigh = lerp(Color(0xFF282828), seed, 0.06f),
            surfaceContainerHighest = lerp(Color(0xFF333333), seed, 0.07f),
            surfaceBright = lerp(Color(0xFF393939), seed, 0.06f),
            surfaceDim = bg,
        )
    } else {
        val primary = if (seed.luminance() > 0.5f) lerp(seed, Color.Black, 0.35f) else seed
        val bg = lerp(Color(0xFFFCFCFC), seed, 0.03f)
        lightColorScheme(
            primary = primary,
            onPrimary = contentColorOn(primary),
            primaryContainer = lerp(seed, Color.White, 0.78f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.6f),
            inversePrimary = lerp(seed, Color.White, 0.4f),
            secondary = lerp(primary, Color(0xFF616161), 0.5f),
            onSecondary = Color.White,
            secondaryContainer = lerp(Color(0xFFE8E8E8), seed, 0.15f),
            onSecondaryContainer = lerp(Color(0xFF1A1A1A), seed, 0.2f),
            tertiary = lerp(primary, Color(0xFF00796B), 0.35f),
            onTertiary = Color.White,
            background = bg,
            onBackground = Color(0xFF1C1B1D),
            surface = bg,
            onSurface = Color(0xFF1C1B1D),
            surfaceVariant = lerp(Color(0xFFE6E6E6), seed, 0.08f),
            onSurfaceVariant = lerp(Color(0xFF4A4A4A), seed, 0.08f),
            surfaceTint = primary,
            outline = lerp(Color(0xFF7A7A7A), seed, 0.1f),
            outlineVariant = lerp(Color(0xFFCACACA), seed, 0.1f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = lerp(Color(0xFFF6F6F6), seed, 0.03f),
            surfaceContainer = lerp(Color(0xFFF0F0F0), seed, 0.04f),
            surfaceContainerHigh = lerp(Color(0xFFEAEAEA), seed, 0.05f),
            surfaceContainerHighest = lerp(Color(0xFFE4E4E4), seed, 0.06f),
            surfaceBright = bg,
            surfaceDim = lerp(Color(0xFFDADADA), seed, 0.04f),
        )
    }
}

/** AMOLED ブラック：背景と面を真っ黒にする（有機 EL の画面で電池が持ち、まぶしさも減る） */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF171717),
    surfaceContainerHighest = Color(0xFF1F1F1F),
)
