package com.sepehr.ikev2.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sepehr.ikev2.ui.theme.*
import com.sepehr.ikev2.vpn.VpnConnectionState

@Composable
fun OrbitalShieldLoader(
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    accentColor: Color = CobaltAccent
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbital")
    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing)
        ),
        label = "outerAngle"
    )
    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing)
        ),
        label = "innerAngle"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.22f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val stroke = 2.6.dp.toPx()
            drawArc(
                color = accentColor.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = accentColor,
                startAngle = outerAngle,
                sweepAngle = 115f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = EmeraldShield,
                startAngle = innerAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(this.size.width * 0.16f, this.size.height * 0.16f),
                size = Size(this.size.width * 0.68f, this.size.height * 0.68f),
                style = Stroke(width = stroke * 0.85f, cap = StrokeCap.Round)
            )
        }
        Box(
            modifier = Modifier
                .size(size * 0.50f)
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceElevated)
                .border(1.dp, BorderBright, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = EmeraldShield,
                modifier = Modifier.size(size * 0.28f)
            )
        }
    }
}

@Composable
fun AnimatedConnectRing(
    state: VpnConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connectRing")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
        label = "sweep"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val ringColor = when (state) {
        VpnConnectionState.CONNECTED -> EmeraldShield
        VpnConnectionState.CONNECTING -> CobaltAccent
        VpnConnectionState.ERROR -> CrimsonAlert
        VpnConnectionState.DISCONNECTED -> Color(0xFF334155)
    }

    Box(
        modifier = modifier.size(164.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
            Box(
                modifier = Modifier
                    .size(158.dp)
                    .scale(pulseScale)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(ringColor.copy(alpha = 0.28f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
        }

        Canvas(modifier = Modifier.size(144.dp)) {
            drawCircle(
                color = ringColor.copy(alpha = 0.22f),
                style = Stroke(width = 3.5.dp.toPx())
            )
            when (state) {
                VpnConnectionState.CONNECTING -> {
                    drawArc(
                        color = CobaltAccent,
                        startAngle = sweepAngle,
                        sweepAngle = 125f,
                        useCenter = false,
                        style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                VpnConnectionState.CONNECTED -> {
                    drawCircle(
                        color = EmeraldShield,
                        style = Stroke(width = 4.5.dp.toPx())
                    )
                }
                VpnConnectionState.ERROR -> {
                    drawCircle(
                        color = CrimsonAlert.copy(alpha = 0.8f),
                        style = Stroke(width = 3.5.dp.toPx())
                    )
                }
                else -> Unit
            }
        }

        val innerTop = when (state) {
            VpnConnectionState.CONNECTED -> Color(0xFF103329)
            VpnConnectionState.CONNECTING -> Color(0xFF132544)
            else -> SurfaceElevated
        }
        val innerBottom = when (state) {
            VpnConnectionState.CONNECTED -> Color(0xFF0A1D18)
            VpnConnectionState.CONNECTING -> Color(0xFF0B1426)
            else -> SurfaceCard
        }

        Box(
            modifier = Modifier
                .size(116.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(colors = listOf(innerTop, innerBottom)))
                .border(
                    width = 1.5.dp,
                    color = when (state) {
                        VpnConnectionState.CONNECTED -> EmeraldShield.copy(alpha = 0.55f)
                        VpnConnectionState.CONNECTING -> CobaltAccent.copy(alpha = 0.55f)
                        VpnConnectionState.ERROR -> CrimsonAlert.copy(alpha = 0.55f)
                        else -> BorderBright
                    },
                    shape = CircleShape
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = "اتصال یا قطع VPN",
                tint = when (state) {
                    VpnConnectionState.CONNECTED -> EmeraldShield
                    VpnConnectionState.CONNECTING -> CobaltAccent
                    VpnConnectionState.ERROR -> CrimsonAlert
                    else -> TextPrimary
                },
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

@Composable
fun SignalStrengthBars(
    latencyMs: Int?,
    modifier: Modifier = Modifier
) {
    val activeBars = when {
        latencyMs == null -> 0
        latencyMs < 140 -> 4
        latencyMs < 240 -> 3
        latencyMs < 380 -> 2
        else -> 1
    }
    val activeColor = when {
        latencyMs == null -> TextMuted
        latencyMs < 180 -> EmeraldShield
        latencyMs < 320 -> AmberWarn
        else -> CrimsonAlert
    }

    Canvas(modifier = modifier.size(width = 18.dp, height = 14.dp)) {
        val barWidth = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val heights = listOf(0.32f, 0.54f, 0.76f, 1.0f)
        heights.forEachIndexed { idx, frac ->
            val barHeight = size.height * frac
            val left = idx * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                color = if (idx < activeBars) activeColor else BorderBright,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
            )
        }
    }
}

@Composable
fun CountryListShimmer(count: Int = 6) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX"
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            SurfaceCard,
            SurfaceElevated,
            SurfaceCard
        ),
        start = Offset(translate, 0f),
        end = Offset(translate + 260f, 0f)
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(brush)
                    .border(1.dp, BorderHairline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(BorderHairline)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(BorderHairline)
                    )
                    Box(
                        modifier = Modifier
                            .width(74.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(BorderHairline.copy(alpha = 0.65f))
                    )
                }
            }
        }
    }
}
