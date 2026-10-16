package com.example.functiongemmavoice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Thinking Orbs state representation reflecting real runtime lifecycle events.
 * Inspired by thinkingorbs.com, implemented in 100% native Jetpack Compose.
 */
enum class OrbState(val userLabel: String) {
    IDLE("Ready"),
    REASONING("Thinking…"),
    WORKING("Running action…"),
    SEARCHING("Searching…"),
    USING("Interacting…"),
    RETRYING("Retrying…"),
    COMPACTING("Managing context…"),
    WAITING("Waiting for you")
}

/**
 * A lightweight, native Jetpack Compose Thinking Orb animation.
 *
 * @param state The current runtime lifecycle state of the assistant agent.
 * @param modifier Compose modifier.
 * @param size Target diameter of the orb (default 20.dp).
 * @param speed Animation speed multiplier (default 1.0f).
 * @param tint Primary accent tint (default BrandBlue).
 * @param animated Whether animations are active (set false for reduced motion).
 * @param contentDescription Accessibility description.
 */
@Composable
fun ThinkingOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    speed: Float = 1.0f,
    tint: Color = Color(0xFF2563EB),
    animated: Boolean = true,
    contentDescription: String = state.userLabel
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingOrbTransition")

    // Rotation angle for orbits, sweeping beams, and gear arcs
    val rotation by if (animated) {
        val duration = (1800 / speed).toInt().coerceAtLeast(400)
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "OrbRotation"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    // Breathing / Pulse scale
    val pulse by if (animated) {
        val duration = (1200 / speed).toInt().coerceAtLeast(300)
        infiniteTransition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OrbPulse"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }
    }

    // Secondary phase oscillation for sparkles and wave ripples
    val wavePhase by if (animated) {
        val duration = (1500 / speed).toInt().coerceAtLeast(400)
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "OrbWavePhase"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f

            when (state) {
                OrbState.IDLE -> drawIdleOrb(center, baseRadius, pulse, tint)
                OrbState.REASONING -> drawReasoningOrb(center, baseRadius, rotation, pulse, tint)
                OrbState.WORKING -> drawWorkingOrb(center, baseRadius, rotation, pulse, tint)
                OrbState.SEARCHING -> drawSearchingOrb(center, baseRadius, rotation, tint)
                OrbState.USING -> drawUsingOrb(center, baseRadius, wavePhase, tint)
                OrbState.RETRYING -> drawRetryingOrb(center, baseRadius, rotation, pulse)
                OrbState.COMPACTING -> drawCompactingOrb(center, baseRadius, pulse, tint)
                OrbState.WAITING -> drawWaitingOrb(center, baseRadius, pulse)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Canvas Drawing Primitives for each Orb State (Zero Object Allocations in loop)
// -----------------------------------------------------------------------------

private fun DrawScope.drawIdleOrb(center: Offset, radius: Float, pulse: Float, tint: Color) {
    val coreRadius = radius * 0.65f * pulse
    // Subtle ambient glow
    drawCircle(
        color = tint.copy(alpha = 0.15f),
        radius = radius * 0.95f,
        center = center
    )
    // Core orb
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, tint.copy(alpha = 0.9f), tint),
            center = center - Offset(radius * 0.15f, radius * 0.15f),
            radius = coreRadius
        ),
        radius = coreRadius,
        center = center
    )
}

private fun DrawScope.drawReasoningOrb(
    center: Offset,
    radius: Float,
    rotation: Float,
    pulse: Float,
    tint: Color
) {
    val coreRadius = radius * 0.55f * pulse
    // Ambient halo
    drawCircle(
        color = tint.copy(alpha = 0.20f),
        radius = radius * 0.9f,
        center = center
    )
    // Central core
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Color(0xFF60A5FA), tint),
            center = center,
            radius = coreRadius
        ),
        radius = coreRadius,
        center = center
    )

    // Moving spark particle orbiting on circumference
    val rad = Math.toRadians(rotation.toDouble())
    val orbitDistance = radius * 0.78f
    val sparkPos = Offset(
        x = (center.x + orbitDistance * cos(rad)).toFloat(),
        y = (center.y + orbitDistance * sin(rad)).toFloat()
    )

    // Spark glow
    drawCircle(
        color = Color(0xFF93C5FD).copy(alpha = 0.5f),
        radius = radius * 0.28f,
        center = sparkPos
    )
    // Spark bright core
    drawCircle(
        color = Color.White,
        radius = radius * 0.14f,
        center = sparkPos
    )
}

private fun DrawScope.drawWorkingOrb(
    center: Offset,
    radius: Float,
    rotation: Float,
    pulse: Float,
    tint: Color
) {
    val coreRadius = radius * 0.45f
    // Core orb
    drawCircle(
        color = tint.copy(alpha = 0.85f),
        radius = coreRadius,
        center = center
    )

    // Animated segmented outer ring
    val strokeWidth = radius * 0.22f
    val arcRadius = radius - strokeWidth / 2f
    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
    val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)

    drawArc(
        color = tint,
        startAngle = rotation,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawArc(
        color = tint.copy(alpha = 0.4f),
        startAngle = rotation + 180f,
        sweepAngle = 80f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawSearchingOrb(
    center: Offset,
    radius: Float,
    rotation: Float,
    tint: Color
) {
    // Outer lens ring
    drawCircle(
        color = tint.copy(alpha = 0.25f),
        radius = radius * 0.9f,
        center = center,
        style = Stroke(width = radius * 0.18f)
    )

    // Sweeping radar beam
    val strokeWidth = radius * 0.15f
    val arcRadius = radius * 0.75f
    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
    val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)

    drawArc(
        brush = Brush.sweepGradient(
            listOf(Color.Transparent, tint.copy(alpha = 0.9f)),
            center = center
        ),
        startAngle = rotation,
        sweepAngle = 70f,
        useCenter = true,
        topLeft = topLeft,
        size = arcSize
    )
    // Small inner pupil
    drawCircle(
        color = tint,
        radius = radius * 0.25f,
        center = center
    )
}

private fun DrawScope.drawUsingOrb(
    center: Offset,
    radius: Float,
    wavePhase: Float,
    tint: Color
) {
    val coreRadius = radius * 0.5f
    drawCircle(color = tint, radius = coreRadius, center = center)

    // Expanding interaction pulse ring
    val rippleRadius = coreRadius + (radius - coreRadius) * wavePhase
    val alpha = (1f - wavePhase).coerceIn(0f, 1f) * 0.6f
    drawCircle(
        color = tint.copy(alpha = alpha),
        radius = rippleRadius,
        center = center,
        style = Stroke(width = radius * 0.14f)
    )
}

private fun DrawScope.drawRetryingOrb(
    center: Offset,
    radius: Float,
    rotation: Float,
    pulse: Float
) {
    val amber = Color(0xFFF59E0B)
    val coreRadius = radius * 0.5f * pulse
    drawCircle(color = amber, radius = coreRadius, center = center)

    // Counter-rotating warning arc
    val strokeWidth = radius * 0.20f
    val arcRadius = radius - strokeWidth / 2f
    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
    val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)

    drawArc(
        color = amber.copy(alpha = 0.75f),
        startAngle = -rotation,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawCompactingOrb(
    center: Offset,
    radius: Float,
    pulse: Float,
    tint: Color
) {
    // Squeezing inward gravitational animation
    val compactRadius = radius * 0.45f * (2f - pulse)
    drawCircle(
        color = tint.copy(alpha = 0.2f),
        radius = radius * 0.9f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx())
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Color(0xFF818CF8), Color(0xFF4F46E5)),
            center = center,
            radius = compactRadius
        ),
        radius = compactRadius,
        center = center
    )
}

private fun DrawScope.drawWaitingOrb(center: Offset, radius: Float, pulse: Float) {
    val gold = Color(0xFFF59E0B)
    val coreRadius = radius * 0.6f * pulse
    drawCircle(
        color = gold.copy(alpha = 0.20f),
        radius = radius * 0.95f,
        center = center
    )
    drawCircle(
        color = gold,
        radius = coreRadius,
        center = center
    )
}
