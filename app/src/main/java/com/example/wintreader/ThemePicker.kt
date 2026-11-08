package com.example.wintreader

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 設定の「テーマの色」：丸い色見本を並べて選ぶ（選んだものにチェックと輪）。
 * 先頭の鉛筆は自分で色を作る（カスタム）。Android 11 以前では「端末の色」を出さない
 */
@Composable
fun ThemeColorGrid(prefs: AppPrefs, onChange: (AppPrefs) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val dynamicOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val dynamicColor = if (dynamicOk) {
        (if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else null

    // 並び：カスタム → 端末の色 → 決まった色
    val items = buildList {
        add(AccentPreset.CUSTOM)
        if (dynamicOk) add(AccentPreset.DYNAMIC)
        addAll(AccentPreset.entries.filter { it.color != null })
    }
    val current = if (!dynamicOk && prefs.accent == AccentPreset.DYNAMIC) AccentPreset.OCEAN else prefs.accent

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    val color = when (p) {
                        AccentPreset.CUSTOM -> Color(prefs.customColor)
                        AccentPreset.DYNAMIC -> dynamicColor ?: Color.Gray
                        else -> p.color!!
                    }
                    ColorChip(
                        label = p.label,
                        color = color,
                        selected = current == p,
                        pencil = p == AccentPreset.CUSTOM,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (p == AccentPreset.CUSTOM) editing = true
                            else onChange(prefs.copy(accent = p))
                        },
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (editing) {
        CustomColorDialog(
            initial = Color(prefs.customColor),
            onCancel = { editing = false },
            onSave = { c ->
                editing = false
                onChange(prefs.copy(accent = AccentPreset.CUSTOM, customColor = c.toArgb()))
            },
        )
    }
}

@Composable
private fun ColorChip(
    label: String,
    color: Color,
    selected: Boolean,
    pencil: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .then(if (selected) Modifier.border(2.5.dp, ring, CircleShape) else Modifier)
                .padding(5.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val on = contentColorOn(color)
            when {
                selected -> Icon(WIcons.Check, contentDescription = "選択中", tint = on, modifier = Modifier.size(22.dp))
                pencil -> Icon(WIcons.Pencil, contentDescription = null, tint = on, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** AMOLED ブラックのスイッチ */
@Composable
fun AmoledRow(prefs: AppPrefs, onChange: (AppPrefs) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(prefs.copy(amoled = !prefs.amoled)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("AMOLED ブラック", style = MaterialTheme.typography.bodyLarge)
            Text(
                "ダークのとき、背景を真っ黒にします（有機ELの画面で電池が持ち、暗い所でまぶしくない）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = prefs.amoled, onCheckedChange = { onChange(prefs.copy(amoled = it)) })
    }
}

// ------------------------------------------------ カスタムの色

private val Swatches = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5C6BC0, 0xFF1E88E5,
    0xFF00ACC1, 0xFF43A047, 0xFFFB8C00, 0xFF795548,
).map { Color(it) }

/** 色 → 色相（0〜360）・彩度（0〜1）・明度（0〜1） */
private fun Color.toHsv(): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), out)
    return out
}

private fun hsv(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

private fun Color.toHex(): String = "%06X".format(toArgb() and 0xFFFFFF)

private fun parseHex(text: String): Color? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 || !t.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
    return Color(0xFF000000 or t.toLong(16))
}

/**
 * 自分で色を作るダイアログ。上に見本（色の帯と、枠だけのボタン）、色見本、
 * 色相・彩度・明るさのつまみ、16進数の入力（例：5C6BC0）。「保存」でこの色をテーマにする
 */
@Composable
fun CustomColorDialog(initial: Color, onCancel: () -> Unit, onSave: (Color) -> Unit) {
    val start = remember { initial.toHsv() }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    val color = hsv(h, s, v)
    var hexText by remember { mutableStateOf(color.toHex()) }

    fun setColor(c: Color) {
        val a = c.toHsv()
        h = a[0]; s = a[1]; v = a[2]
        hexText = c.toHex()
    }

    fun fromSliders(nh: Float = h, ns: Float = s, nv: Float = v) {
        h = nh; s = ns; v = nv
        hexText = hsv(nh, ns, nv).toHex()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("カスタムの色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 見本
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color),
                        contentAlignment = Alignment.Center,
                    ) { Text("この色", color = contentColorOn(color), style = MaterialTheme.typography.labelLarge) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.5.dp, color, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("ボタンの見え方", color = color, style = MaterialTheme.typography.labelLarge) }
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Swatches.forEach { c ->
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (c.toArgb() == color.toArgb()) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { setColor(c) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                HsvSlider(
                    "色相", "${h.toInt()}°", h / 360f,
                    Brush.horizontalGradient((0..6).map { hsv(it * 60f, 1f, 1f) }),
                ) { fromSliders(nh = it * 360f) }
                HsvSlider(
                    "彩度", "${(s * 100).toInt()}%", s,
                    Brush.horizontalGradient(listOf(hsv(h, 0f, v), hsv(h, 1f, v))),
                ) { fromSliders(ns = it) }
                HsvSlider(
                    "明るさ", "${(v * 100).toInt()}%", v,
                    Brush.horizontalGradient(listOf(Color.Black, hsv(h, s, 1f))),
                ) { fromSliders(nv = it) }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { t ->
                        val clean = t.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                        hexText = clean
                        parseHex(clean)?.let { c -> val a = c.toHsv(); h = a[0]; s = a[1]; v = a[2] }
                    },
                    label = { Text("16進数（例：5C6BC0）") },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = parseHex(hexText) == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(color) }) { Text("保存") } },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

@Composable
private fun HsvSlider(label: String, value: String, fraction: Float, track: Brush, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(contentAlignment = Alignment.Center) {
            // つまみの下に色の帯を敷く（Slider 自体の線は透明にする）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(track),
            )
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = onChange,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    thumbColor = Color.White,
                ),
            )
        }
    }
}
