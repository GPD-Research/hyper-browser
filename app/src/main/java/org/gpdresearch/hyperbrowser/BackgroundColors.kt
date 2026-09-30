package org.gpdresearch.hyperbrowser

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * How the file tree is coloured. [flat] selects a solid colour behind the tree in place of the
 * stored image, which stays on disk so switching back costs nothing. The colours themselves are
 * kept whichever way that switch is set: [foreground] recolours text and icons over an image just
 * as much as over the flat colour, and [background] is waiting when the flat colour returns.
 *
 * Colours are stored as ARGB ints; null means "whatever the theme would have used", which is what
 * a plain reset leaves behind so the grey follows the theme if that changes later.
 */
data class BackgroundColors(
    val flat: Boolean = false,
    val background: Color? = null,
    val foreground: Color? = null,
) {
    fun backgroundFor(darkTheme: Boolean): Color =
        background ?: if (darkTheme) DEFAULT_DARK_BACKGROUND else DEFAULT_LIGHT_BACKGROUND

    fun foregroundFor(themeForeground: Color): Color = foreground ?: themeForeground

    companion object {
        private val DEFAULT_LIGHT_BACKGROUND = Color(0xFFE0E0E0)
        private val DEFAULT_DARK_BACKGROUND = Color(0xFF3A3A3A)

        private const val MODE_PREF = "background_mode"
        private const val BACKGROUND_PREF = "background_color"
        private const val FOREGROUND_PREF = "background_text_color"
        private const val MODE_COLOR = "color"

        fun load(prefs: SharedPreferences): BackgroundColors = BackgroundColors(
            flat = prefs.getString(MODE_PREF, null) == MODE_COLOR,
            background = colorPref(prefs, BACKGROUND_PREF),
            foreground = colorPref(prefs, FOREGROUND_PREF),
        )

        fun save(prefs: SharedPreferences, colors: BackgroundColors) {
            prefs.edit().apply {
                if (colors.flat) putString(MODE_PREF, MODE_COLOR) else remove(MODE_PREF)
                putColor(BACKGROUND_PREF, colors.background)
                putColor(FOREGROUND_PREF, colors.foreground)
            }.apply()
        }

        private fun colorPref(prefs: SharedPreferences, key: String): Color? =
            if (prefs.contains(key)) Color(prefs.getInt(key, 0)) else null

        private fun SharedPreferences.Editor.putColor(key: String, color: Color?) {
            if (color == null) remove(key) else putInt(key, color.toArgb())
        }
    }
}

/**
 * Picks one colour and sends it to either the background or the text and icons. The two may not
 * be given the same value: a tree drawn in one colour on itself is invisible, and that is easier
 * to stop here than to explain afterwards.
 */
@Composable
fun BackgroundColorDialog(
    background: Color,
    foreground: Color,
    onSetBackground: (Color) -> Unit,
    onSetForeground: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    var red by rememberSaveable { mutableIntStateOf(background.channel { it.red }) }
    var green by rememberSaveable { mutableIntStateOf(background.channel { it.green }) }
    var blue by rememberSaveable { mutableIntStateOf(background.channel { it.blue }) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = Color(red, green, blue)

    fun apply(other: Color, set: (Color) -> Unit) {
        if (chosen.toArgb() == other.toArgb()) {
            error = "That colour is already used by the other setting. Select a different colour."
            return
        }
        error = null
        set(chosen)
    }

    HyperDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background colour") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                ColorPreview(background = background, foreground = foreground)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Selected", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = chosen.hex(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(chosen)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
                    ) {
                        Text(" ", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ChannelSlider("R", red) { red = it }
                ChannelSlider("G", green) { green = it }
                ChannelSlider("B", blue) { blue = it }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { apply(foreground, onSetBackground) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Set background")
                    }
                    Button(
                        onClick = { apply(background, onSetForeground) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Set text/icons")
                    }
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/** A file row as it will look, so the pairing can be judged before it is applied. */
@Composable
private fun ColorPreview(background: Color, foreground: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = foreground)
        Text("Photos", color = foreground, style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Filled.Image, contentDescription = null, tint = foreground)
        Text("IMG_0417.jpg", color = foreground, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString().padStart(3),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
        )
    }
}

private fun Color.channel(pick: (Color) -> Float): Int = (pick(this) * 255f + 0.5f).toInt().coerceIn(0, 255)

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
