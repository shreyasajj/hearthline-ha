package dev.pixelhome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shared native template primitives. No remote layout or reference-image assets are used. */
@Composable internal fun templateSage(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < .4f) Color(0xFF35432B) else Color(0xFFE2E9C9)

@Composable internal fun TemplateCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = if (highlighted) templateSage() else MaterialTheme.colorScheme.surface,
        border = if (highlighted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable internal fun TemplateSupport(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 22.sp, lineHeight = 29.sp)
}

@Composable internal fun TemplateHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
}

@Composable internal fun HeroMeasurement(amount: String, unit: String = "") {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(amount, Modifier.alignByBaseline(), fontSize = if (amount.length > 3) 64.sp else 88.sp,
            lineHeight = 96.sp, fontWeight = FontWeight.SemiBold)
        if (unit.isNotEmpty()) Text(unit, Modifier.alignByBaseline(), fontSize = 36.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable internal fun TemplatePill(text: String, icon: ImageVector? = null) {
    Surface(color = templateSage(), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable internal fun TemplateFooter(onHome: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        FilledTonalButton(onClick = onHome, modifier = Modifier.heightIn(min = 56.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = templateSage(), contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(10.dp)); Text("Home", fontSize = 22.sp)
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.Close, null); Spacer(Modifier.width(10.dp)); Text("Dismiss", fontSize = 22.sp)
        }
    }
}

internal fun templateNumber(value: Double?, digits: Int = 0): String = value?.let {
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = digits }.format(it)
} ?: "—"

internal fun templateTime(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(at)

internal fun conditionName(condition: String): String = when (condition) {
    "partlycloudy" -> "Partly cloudy"
    "clear-night" -> "Clear night"
    "windy-variant" -> "Windy and cloudy"
    else -> condition.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

/** Original, code-drawn weather marks. The adjacent text supplies the accessible condition. */
@Composable internal fun WeatherIcon(condition: String, modifier: Modifier = Modifier.size(64.dp)) {
    val ink = MaterialTheme.colorScheme.onSurface
    val sun = if (MaterialTheme.colorScheme.surface.luminance() < .4f) Color(0xFFE7CB79) else Color(0xFFE3BD57)
    val cloud = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(w * x, h * y)
        if (condition == "clear-night") {
            drawCircle(sun, w * .30f, point(.48f, .45f))
            drawCircle(templateCanvasBackground(cloud), w * .27f, point(.63f, .30f))
        } else if (condition == "sunny" || condition == "partlycloudy") {
            drawCircle(sun, w * .23f, point(.65f, .32f))
            for (i in 0 until 8) {
                val angle = i * Math.PI / 4
                val cx = .65f + .34f * kotlin.math.cos(angle).toFloat()
                val cy = .32f + .34f * kotlin.math.sin(angle).toFloat()
                drawLine(ink, point(cx, cy), point(cx + .05f * kotlin.math.cos(angle).toFloat(), cy + .05f * kotlin.math.sin(angle).toFloat()), w * .025f)
            }
        }
        if (condition !in setOf("sunny", "clear-night")) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .18f, h * .76f)
                cubicTo(w * -.02f, h * .72f, w * .05f, h * .43f, w * .24f, h * .48f)
                cubicTo(w * .27f, h * .17f, w * .67f, h * .22f, w * .69f, h * .48f)
                cubicTo(w * .94f, h * .43f, w * 1.02f, h * .77f, w * .78f, h * .78f)
                close()
            }
            drawPath(path, cloud)
            drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(w * .025f))
            if (condition in setOf("rainy", "pouring", "lightning-rainy")) {
                for (x in listOf(.3f, .5f, .7f)) drawLine(ink, point(x, .84f), point(x - .04f, .98f), w * .03f)
            }
            if (condition in setOf("snowy", "snowy-rainy", "hail")) {
                for (x in listOf(.3f, .5f, .7f)) drawCircle(ink, w * .025f, point(x, .91f))
            }
        }
    }
}
private fun templateCanvasBackground(color: Color) = color
