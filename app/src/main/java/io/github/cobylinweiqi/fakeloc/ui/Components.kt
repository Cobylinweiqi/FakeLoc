package io.github.cobylinweiqi.fakeloc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Rounded surface used for every block on the home screen.
 *
 * [glow] (0f…1f) drives a pulsing halo around the card plus a brighter border.
 * It is drawn *outside* the card bounds on purpose — a halo that lives inside
 * the border just reads as a thick border. Callers leave vertical breathing
 * room; a non-clipping `Column` lets it render.
 */
@Composable
fun FakeLocCard(
    modifier: Modifier = Modifier,
    glow: Float = 0f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val active = glow > 0.01f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(3.dp)
            .drawBehind {
                if (!active) return@drawBehind
                val unit = 1.6.dp.toPx()
                // Two concentric fading rings read as a soft bloom rather than a
                // hard outline.
                repeat(2) { ring ->
                    val width = unit * (ring + 1)
                    val alpha = glow * (0.30f / (ring + 1))
                    drawRoundRect(
                        color = accent.copy(alpha = alpha),
                        topLeft = Offset(-width, -width),
                        size = Size(size.width + width * 2, size.height + width * 2),
                        cornerRadius = CornerRadius(CardRadius.toPx() + width),
                        style = Stroke(width = width),
                    )
                }
            },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CardRadius),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.30f + 0.45f * glow) else outline,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                content = content,
            )
        }
    }
}

/** Small all-caps label that opens a card. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The three states a pill can report, and they are genuinely three.
 *
 * [ACTIVE] and [READY] used to collapse into one `Boolean`, which is how the
 * home screen ended up claiming "module not active" whenever spoofing was
 * merely switched off — a sentence that is both alarming and false, since a
 * connected module with the switch off is exactly the normal idle state.
 * [WARN] is the one state that needs the user to do something, and it is
 * deliberately a colour, not just a word: "not connected" is the only pill a
 * user should ever be pulled towards.
 */
enum class PillTone { ACTIVE, READY, WARN }

/**
 * Dot + text chip signalling module / run state.
 *
 * [onClick] adds a trailing info glyph and makes the pill the way into the
 * explanation dialog. That is the whole reason the pill is allowed to say "not
 * active" at all: the state is worth naming only if the naming can be followed
 * by the fix.
 */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tint = when (tone) {
        PillTone.ACTIVE -> MaterialTheme.colorScheme.primary
        PillTone.READY -> MaterialTheme.colorScheme.onSurfaceVariant
        PillTone.WARN -> MaterialTheme.colorScheme.onErrorContainer
    }
    val container = when (tone) {
        PillTone.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        PillTone.READY -> MaterialTheme.colorScheme.surfaceVariant
        PillTone.WARN -> MaterialTheme.colorScheme.errorContainer
    }

    Surface(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(tint, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = tint,
            )
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = tint.copy(alpha = 0.75f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Icon + title + subtitle on the left, a switch on the right. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A titled slider with its current value shown on the right.
 *
 * The value is coerced into [range] before being handed to `Slider`: Material3
 * throws if it is out of bounds, and a config written by an older build can
 * easily sit outside a range that has since been narrowed.
 *
 * [enabled] exists because the switch above each of these used to *remove* its
 * slider instead of disabling it. Removing it made a toggle look like a mode
 * switch (one shape or another) when it is really "this value is not being sent
 * right now" — and it hid the number, which is the thing the user is deciding
 * whether to send. A greyed, unpushable slider with the value still legible says
 * exactly that.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    unit: String = "",
    decimals: Int = 1,
    enabled: Boolean = true,
) {
    val safeValue = value.coerceIn(range.start, range.endInclusive)
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = labelColor,
            )
            Text(
                text = formatValue(safeValue, decimals) + unit,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
        Slider(
            value = safeValue,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            // ~10 stops per unit of range: smooth to drag, coarse enough to land
            // on tidy numbers. Clamped at 0 because Material3 rejects a negative
            // step count and a narrow range would produce one.
            steps = (((range.endInclusive - range.start) * 10).roundToInt() - 1).coerceIn(0, 90),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Locale-pinned formatting.
 *
 * The platform default separator is a comma across much of Europe and South
 * America, which would render a slider readout as `1,4 m/s` — harmless here, but
 * the same mistake in [io.github.cobylinweiqi.fakeloc.core.Geo.format] would produce
 * coordinates the editor could not parse back.
 */
private fun formatValue(value: Float, decimals: Int): String = when (decimals) {
    0 -> value.roundToInt().toString()
    2 -> String.format(Locale.US, "%.2f", value)
    else -> String.format(Locale.US, "%.1f", value)
}

/** Thin rule used to separate rows inside a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
