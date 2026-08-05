package ai.withmurph.companion.ui.components

import ai.withmurph.companion.R
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MurphLogo(
    modifier: Modifier = Modifier,
    painter: Painter = painterResource(R.drawable.murph_logo),
) {
    Image(
        painter = painter,
        contentDescription = "Murph",
        modifier = modifier.height(36.dp).width(161.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun MurphMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.murph_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun MurphPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MurphColors.SageDark,
            contentColor = Color.White,
            disabledContainerColor = MurphColors.SageDark.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun MurphOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MurphColors.Slate.copy(alpha = 0.2f)),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MurphColors.Slate,
            disabledContentColor = MurphColors.SlateMuted.copy(alpha = 0.5f),
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun MurphGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MurphColors.Slate,
            disabledContentColor = MurphColors.SlateMuted.copy(alpha = 0.5f),
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun MurphLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MurphColors.SageDark,
            disabledContentColor = MurphColors.SageDark.copy(alpha = 0.5f),
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun MurphCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MurphColors.Card.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, MurphColors.BorderWarm),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun MurphTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(56.dp)
            .semantics { contentDescription = label }
            .clip(shape)
            .drawWithContent {
                val radius = 22.dp.toPx()
                drawRoundRect(
                    color = MurphColors.Card.copy(alpha = 0.9f),
                    cornerRadius = CornerRadius(radius),
                )
                if (focused) {
                    val ringWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = MurphColors.Sage.copy(alpha = 0.55f),
                        topLeft = Offset(ringWidth / 2f, ringWidth / 2f),
                        size = Size(size.width - ringWidth, size.height - ringWidth),
                        cornerRadius = CornerRadius(radius - ringWidth / 2f),
                        style = Stroke(ringWidth),
                    )
                    val borderWidth = 1.dp.toPx()
                    val inset = ringWidth + borderWidth / 2f
                    drawRoundRect(
                        color = MurphColors.SageDark,
                        topLeft = Offset(inset, inset),
                        size = Size(
                            width = size.width - (inset * 2f),
                            height = size.height - (inset * 2f),
                        ),
                        cornerRadius = CornerRadius(radius - inset),
                        style = Stroke(borderWidth),
                    )
                } else {
                    val borderWidth = 1.dp.toPx()
                    drawRoundRect(
                        color = MurphColors.BorderWarm,
                        topLeft = Offset(borderWidth / 2f, borderWidth / 2f),
                        size = Size(size.width - borderWidth, size.height - borderWidth),
                        cornerRadius = CornerRadius(radius - borderWidth / 2f),
                        style = Stroke(borderWidth),
                    )
                }
                drawContent()
            }
            .onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MurphColors.Slate,
            fontSize = 17.sp,
            lineHeight = 22.sp,
        ),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(MurphColors.SageDark),
        decorationBox = { field ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                        color = MurphColors.SlateMuted,
                    )
                }
                field()
            }
        },
    )
}

enum class MurphIconKind {
    HealthCard,
    Refresh,
    CheckCircle,
    Clock,
    Gear,
    GearFilled,
    Home,
    External,
    Bell,
    Sparkles,
    Checklist,
    Shield,
    Envelope,
    Trash,
    SignOut,
}

@Composable
fun MurphIcon(
    kind: MurphIconKind,
    modifier: Modifier = Modifier,
    tint: Color = MurphColors.SageDark,
    backgroundColor: Color = Color.Transparent,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Canvas(modifier = semanticsModifier) {
        val unit = size.minDimension
        val stroke = unit * 0.075f
        val outline = Stroke(
            width = stroke,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        when (kind) {
            MurphIconKind.HealthCard -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(unit * 0.16f, unit * 0.06f),
                    size = Size(unit * 0.68f, unit * 0.88f),
                    cornerRadius = CornerRadius(unit * 0.1f),
                    style = outline,
                )
                val heart = Path().apply {
                    moveTo(unit * 0.5f, unit * 0.42f)
                    cubicTo(unit * 0.28f, unit * 0.29f, unit * 0.34f, unit * 0.16f, unit * 0.5f, unit * 0.27f)
                    cubicTo(unit * 0.66f, unit * 0.16f, unit * 0.72f, unit * 0.29f, unit * 0.5f, unit * 0.42f)
                    close()
                }
                drawPath(heart, tint)
                drawLine(tint, Offset(unit * 0.32f, unit * 0.61f), Offset(unit * 0.68f, unit * 0.61f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.32f, unit * 0.76f), Offset(unit * 0.58f, unit * 0.76f), stroke, StrokeCap.Round)
            }

            MurphIconKind.Refresh -> {
                drawArc(
                    color = tint,
                    startAngle = -52f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(unit * 0.13f, unit * 0.13f),
                    size = Size(unit * 0.74f, unit * 0.74f),
                    style = outline,
                )
                drawArc(
                    color = tint,
                    startAngle = 128f,
                    sweepAngle = 170f,
                    useCenter = false,
                    topLeft = Offset(unit * 0.13f, unit * 0.13f),
                    size = Size(unit * 0.74f, unit * 0.74f),
                    style = outline,
                )
                drawLine(tint, Offset(unit * 0.72f, unit * 0.08f), Offset(unit * 0.87f, unit * 0.18f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.87f, unit * 0.18f), Offset(unit * 0.82f, unit * 0.34f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.28f, unit * 0.92f), Offset(unit * 0.13f, unit * 0.82f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.13f, unit * 0.82f), Offset(unit * 0.18f, unit * 0.66f), stroke, StrokeCap.Round)
            }

            MurphIconKind.CheckCircle -> {
                drawCircle(tint, unit * 0.47f, Offset(unit * 0.5f, unit * 0.5f))
                drawLine(Color.White, Offset(unit * 0.28f, unit * 0.51f), Offset(unit * 0.44f, unit * 0.68f), stroke, StrokeCap.Round)
                drawLine(Color.White, Offset(unit * 0.44f, unit * 0.68f), Offset(unit * 0.75f, unit * 0.31f), stroke, StrokeCap.Round)
            }

            MurphIconKind.Clock -> {
                drawCircle(tint, unit * 0.39f, Offset(unit * 0.5f, unit * 0.5f), style = outline)
                drawLine(tint, Offset(unit * 0.5f, unit * 0.5f), Offset(unit * 0.5f, unit * 0.28f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.5f, unit * 0.5f), Offset(unit * 0.67f, unit * 0.61f), stroke, StrokeCap.Round)
            }

            MurphIconKind.Gear -> drawGearOutline(tint, unit, stroke)

            MurphIconKind.GearFilled -> drawGearFilled(
                color = tint,
                backgroundColor = backgroundColor,
                unit = unit,
            )

            MurphIconKind.Home -> {
                val house = Path().apply {
                    moveTo(unit * 0.1f, unit * 0.46f)
                    lineTo(unit * 0.5f, unit * 0.16f)
                    lineTo(unit * 0.9f, unit * 0.46f)
                    lineTo(unit * 0.8f, unit * 0.46f)
                    lineTo(unit * 0.8f, unit * 0.86f)
                    lineTo(unit * 0.2f, unit * 0.86f)
                    lineTo(unit * 0.2f, unit * 0.46f)
                    close()
                }
                drawPath(house, tint)
                drawRect(
                    color = tint,
                    topLeft = Offset(unit * 0.68f, unit * 0.2f),
                    size = Size(unit * 0.1f, unit * 0.25f),
                )
                drawRect(
                    color = backgroundColor,
                    topLeft = Offset(unit * 0.43f, unit * 0.64f),
                    size = Size(unit * 0.14f, unit * 0.22f),
                )
            }

            MurphIconKind.External -> {
                drawLine(tint, Offset(unit * 0.25f, unit * 0.75f), Offset(unit * 0.75f, unit * 0.25f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.45f, unit * 0.25f), Offset(unit * 0.75f, unit * 0.25f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.75f, unit * 0.25f), Offset(unit * 0.75f, unit * 0.55f), stroke, StrokeCap.Round)
            }

            MurphIconKind.Bell -> {
                val bell = Path().apply {
                    moveTo(unit * 0.24f, unit * 0.68f)
                    cubicTo(unit * 0.34f, unit * 0.55f, unit * 0.3f, unit * 0.29f, unit * 0.5f, unit * 0.25f)
                    cubicTo(unit * 0.7f, unit * 0.29f, unit * 0.66f, unit * 0.55f, unit * 0.76f, unit * 0.68f)
                    lineTo(unit * 0.24f, unit * 0.68f)
                }
                drawPath(bell, tint, style = outline)
                drawLine(tint, Offset(unit * 0.43f, unit * 0.8f), Offset(unit * 0.57f, unit * 0.8f), stroke, StrokeCap.Round)
            }

            MurphIconKind.Sparkles -> {
                drawSparkle(tint, Offset(unit * 0.53f, unit * 0.53f), unit * 0.34f)
                drawSparkle(tint, Offset(unit * 0.2f, unit * 0.24f), unit * 0.14f)
                drawSparkle(tint, Offset(unit * 0.8f, unit * 0.18f), unit * 0.1f)
            }

            MurphIconKind.Checklist -> {
                drawCircle(tint, unit * 0.09f, Offset(unit * 0.22f, unit * 0.28f), style = outline)
                drawCircle(tint, unit * 0.09f, Offset(unit * 0.22f, unit * 0.7f), style = outline)
                drawLine(tint, Offset(unit * 0.42f, unit * 0.28f), Offset(unit * 0.82f, unit * 0.28f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.42f, unit * 0.7f), Offset(unit * 0.72f, unit * 0.7f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.16f, unit * 0.28f), Offset(unit * 0.21f, unit * 0.34f), stroke * 0.7f, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.21f, unit * 0.34f), Offset(unit * 0.3f, unit * 0.2f), stroke * 0.7f, StrokeCap.Round)
            }

            MurphIconKind.Shield -> {
                val shield = Path().apply {
                    moveTo(unit * 0.5f, unit * 0.1f)
                    lineTo(unit * 0.82f, unit * 0.25f)
                    lineTo(unit * 0.76f, unit * 0.66f)
                    quadraticTo(unit * 0.66f, unit * 0.83f, unit * 0.5f, unit * 0.9f)
                    quadraticTo(unit * 0.34f, unit * 0.83f, unit * 0.24f, unit * 0.66f)
                    lineTo(unit * 0.18f, unit * 0.25f)
                    close()
                }
                drawPath(shield, tint, style = outline)
            }

            MurphIconKind.Envelope -> {
                drawRoundRect(
                    tint,
                    Offset(unit * 0.12f, unit * 0.22f),
                    Size(unit * 0.76f, unit * 0.56f),
                    CornerRadius(unit * 0.08f),
                    style = outline,
                )
                drawLine(tint, Offset(unit * 0.16f, unit * 0.29f), Offset(unit * 0.5f, unit * 0.55f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.84f, unit * 0.29f), Offset(unit * 0.5f, unit * 0.55f), stroke, StrokeCap.Round)
            }

            MurphIconKind.Trash -> {
                drawRoundRect(
                    tint,
                    Offset(unit * 0.28f, unit * 0.27f),
                    Size(unit * 0.44f, unit * 0.58f),
                    CornerRadius(unit * 0.04f),
                    style = outline,
                )
                drawLine(tint, Offset(unit * 0.21f, unit * 0.24f), Offset(unit * 0.79f, unit * 0.24f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.4f, unit * 0.14f), Offset(unit * 0.6f, unit * 0.14f), stroke, StrokeCap.Round)
            }

            MurphIconKind.SignOut -> {
                drawRoundRect(
                    tint,
                    Offset(unit * 0.12f, unit * 0.14f),
                    Size(unit * 0.48f, unit * 0.72f),
                    CornerRadius(unit * 0.06f),
                    style = outline,
                )
                drawLine(tint, Offset(unit * 0.42f, unit * 0.5f), Offset(unit * 0.88f, unit * 0.5f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.88f, unit * 0.5f), Offset(unit * 0.72f, unit * 0.34f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(unit * 0.88f, unit * 0.5f), Offset(unit * 0.72f, unit * 0.66f), stroke, StrokeCap.Round)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.gearPath(unit: Float): Path {
    val center = Offset(unit * 0.5f, unit * 0.5f)
    val path = Path()
    repeat(32) { index ->
        val pointWithinTooth = index % 4
        val radius = if (pointWithinTooth == 1 || pointWithinTooth == 2) {
            unit * 0.45f
        } else {
            unit * 0.34f
        }
        val angle = Math.toRadians((index * 11.25) - 90.0)
        val point = Offset(
            center.x + kotlin.math.cos(angle).toFloat() * radius,
            center.y + kotlin.math.sin(angle).toFloat() * radius,
        )
        if (index == 0) {
            path.moveTo(point.x, point.y)
        } else {
            path.lineTo(point.x, point.y)
        }
    }
    path.close()
    return path
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGearOutline(
    color: Color,
    unit: Float,
    strokeWidth: Float,
) {
    val center = Offset(unit * 0.5f, unit * 0.5f)
    val outline = Stroke(
        width = strokeWidth,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    drawPath(gearPath(unit), color, style = outline)
    drawCircle(color, unit * 0.13f, center, style = outline)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGearFilled(
    color: Color,
    backgroundColor: Color,
    unit: Float,
) {
    drawPath(gearPath(unit), color)
    drawCircle(
        color = backgroundColor,
        radius = unit * 0.14f,
        center = Offset(unit * 0.5f, unit * 0.5f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkle(
    color: Color,
    center: Offset,
    radius: Float,
) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + radius * 0.15f, center.y - radius * 0.15f, center.x + radius, center.y)
        quadraticTo(center.x + radius * 0.15f, center.y + radius * 0.15f, center.x, center.y + radius)
        quadraticTo(center.x - radius * 0.15f, center.y + radius * 0.15f, center.x - radius, center.y)
        quadraticTo(center.x - radius * 0.15f, center.y - radius * 0.15f, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

@Composable
fun SettingsRow(
    title: String,
    icon: MurphIconKind,
    detail: String? = null,
    actionLabel: String? = null,
    showsExternalLink: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MurphIcon(
            kind = icon,
            modifier = Modifier.size(24.dp),
            contentDescription = null,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.Slate,
            )
            if (detail != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MurphColors.SlateMuted,
                )
            }
        }
        if (actionLabel != null) {
            Text(
                text = actionLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MurphColors.SageDark else MurphColors.SlateMuted,
            )
        }
        if (showsExternalLink) {
            MurphIcon(
                kind = MurphIconKind.External,
                modifier = Modifier.size(18.dp),
                tint = MurphColors.SlateMuted,
                contentDescription = "Opens externally",
            )
        }
    }
}

@Composable
fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(MurphColors.BorderWarm),
    )
}
