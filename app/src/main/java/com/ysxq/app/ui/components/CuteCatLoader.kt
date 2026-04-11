package com.ysxq.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CuteCatLoader(modifier: Modifier = Modifier.size(100.dp)) {
    val infiniteTransition = rememberInfiniteTransition(label = "cat")

    val bounceY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    val tailSwing by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tail"
    )

    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(80),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(2000)
        ),
        label = "blink"
    )

    val sparkleRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkle"
    )

    val bodyColor = Color(0xFFFFE0EC)
    val earInnerColor = Color(0xFFFFB6C1)
    val eyeColor = Color(0xFF333333)
    val blushColor = Color(0xFFFF8FAE)

    Box(
        modifier = modifier
            .graphicsLayer { translationY = bounceY },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 8f
            val headRadius = size.width * 0.3f

            drawArc(
                color = bodyColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(cx - headRadius * 1.1f, cy - headRadius * 0.1f),
                size = Size(headRadius * 2.2f, headRadius * 1.4f)
            )

            drawCircle(
                color = bodyColor,
                radius = headRadius,
                center = Offset(cx, cy)
            )

            val earSize = headRadius * 0.65f
            val leftEarX = cx - headRadius * 0.65f
            val rightEarX = cx + headRadius * 0.65f
            val earY = cy - headRadius * 0.95f

            val leftEar = Path().apply {
                moveTo(leftEarX - earSize * 0.5f, earY + earSize)
                lineTo(leftEarX, earY - earSize * 0.3f)
                lineTo(leftEarX + earSize * 0.5f, earY + earSize)
                close()
            }
            drawPath(leftEar, bodyColor)

            val rightEar = Path().apply {
                moveTo(rightEarX - earSize * 0.5f, earY + earSize)
                lineTo(rightEarX, earY - earSize * 0.3f)
                lineTo(rightEarX + earSize * 0.5f, earY + earSize)
                close()
            }
            drawPath(rightEar, bodyColor)

            val innerLeftEar = Path().apply {
                val scale = 0.55f
                moveTo(leftEarX - earSize * 0.5f * scale + earSize * 0.15f, earY + earSize * scale + earSize * 0.05f)
                lineTo(leftEarX, earY - earSize * 0.3f * scale + earSize * 0.15f)
                lineTo(leftEarX + earSize * 0.5f * scale - earSize * 0.15f, earY + earSize * scale + earSize * 0.05f)
                close()
            }
            drawPath(innerLeftEar, earInnerColor)

            val innerRightEar = Path().apply {
                val scale = 0.55f
                moveTo(rightEarX - earSize * 0.5f * scale + earSize * 0.15f, earY + earSize * scale + earSize * 0.05f)
                lineTo(rightEarX, earY - earSize * 0.3f * scale + earSize * 0.15f)
                lineTo(rightEarX + earSize * 0.5f * scale - earSize * 0.15f, earY + earSize * scale + earSize * 0.05f)
                close()
            }
            drawPath(innerRightEar, earInnerColor)

            val eyeSpacing = headRadius * 0.38f
            val eyeY = cy - headRadius * 0.08f
            val eyeRadius = headRadius * 0.12f

            drawCircle(
                color = eyeColor.copy(alpha = blinkAlpha),
                radius = eyeRadius,
                center = Offset(cx - eyeSpacing, eyeY)
            )
            drawCircle(
                color = eyeColor.copy(alpha = blinkAlpha),
                radius = eyeRadius,
                center = Offset(cx + eyeSpacing, eyeY)
            )

            if (blinkAlpha < 0.3f) {
                drawLine(
                    color = eyeColor,
                    start = Offset(cx - eyeSpacing - eyeRadius, eyeY),
                    end = Offset(cx - eyeSpacing + eyeRadius, eyeY),
                    strokeWidth = 2.5f
                )
                drawLine(
                    color = eyeColor,
                    start = Offset(cx + eyeSpacing - eyeRadius, eyeY),
                    end = Offset(cx + eyeSpacing + eyeRadius, eyeY),
                    strokeWidth = 2.5f
                )
            }

            drawCircle(
                color = Color.White,
                radius = eyeRadius * 0.45f,
                center = Offset(cx - eyeSpacing + eyeRadius * 0.35f, eyeY - eyeRadius * 0.35f)
            )
            drawCircle(
                color = Color.White,
                radius = eyeRadius * 0.45f,
                center = Offset(cx + eyeSpacing + eyeRadius * 0.35f, eyeY - eyeRadius * 0.35f)
            )

            drawCircle(
                color = blushColor.copy(alpha = 0.5f),
                radius = headRadius * 0.14f,
                center = Offset(cx - headRadius * 0.58f, cy + headRadius * 0.15f)
            )
            drawCircle(
                color = blushColor.copy(alpha = 0.5f),
                radius = headRadius * 0.14f,
                center = Offset(cx + headRadius * 0.58f, cy + headRadius * 0.15f)
            )

            val noseY = cy + headRadius * 0.15f
            val nosePath = Path().apply {
                moveTo(cx, noseY - headRadius * 0.05f)
                lineTo(cx - headRadius * 0.06f, noseY + headRadius * 0.03f)
                lineTo(cx + headRadius * 0.06f, noseY + headRadius * 0.03f)
                close()
            }
            drawPath(nosePath, Color(0xFFFF6B8A))

            drawLine(
                color = Color(0xFF888888),
                start = Offset(cx, noseY + headRadius * 0.03f),
                end = Offset(cx, cy + headRadius * 0.32f),
                strokeWidth = 1.8f
            )

            drawArc(
                color = Color(0xFF888888),
                startAngle = 0f,
                sweepAngle = -30f,
                useCenter = false,
                topLeft = Offset(cx - headRadius * 0.2f, cy + headRadius * 0.22f),
                size = Size(headRadius * 0.2f, headRadius * 0.15f),
                style = Stroke(width = 1.8f)
            )
            drawArc(
                color = Color(0xFF888888),
                startAngle = 210f,
                sweepAngle = 30f,
                useCenter = false,
                topLeft = Offset(cx, cy + headRadius * 0.22f),
                size = Size(headRadius * 0.2f, headRadius * 0.15f),
                style = Stroke(width = 1.8f)
            )

            val tailBaseX = cx + headRadius * 0.9f
            val tailBaseY = cy + headRadius * 0.7f
            val tailPath = Path().apply {
                moveTo(tailBaseX, tailBaseY)
                val swingOffset = tailSwing * 0.5f
                quadraticBezierTo(
                    tailBaseX + headRadius * 0.6f + swingOffset,
                    tailBaseY - headRadius * 0.5f,
                    tailBaseX + headRadius * 0.3f + swingOffset * 1.2f,
                    tailBaseY - headRadius * 1.1f
                )
            }
            drawPath(
                tailPath,
                bodyColor,
                style = Stroke(width = 6f, cap = StrokeCap.Round)
            )

            val sparkleCx = cx - headRadius * 1.4f
            val sparkleCy = cy - headRadius * 0.6f
            val sparkleSize = 6f
            rotate(sparkleRotation, Offset(sparkleCx, sparkleCy)) {
                drawLine(Color(0xFFFFD700), Offset(sparkleCx - sparkleSize, sparkleCy), Offset(sparkleCx + sparkleSize, sparkleCy), strokeWidth = 2f)
                drawLine(Color(0xFFFFD700), Offset(sparkleCx, sparkleCy - sparkleSize), Offset(sparkleCx, sparkleCy + sparkleSize), strokeWidth = 2f)
                drawLine(Color(0xFFFFD700), Offset(sparkleCx - sparkleSize * 0.6f, sparkleCy - sparkleSize * 0.6f), Offset(sparkleCx + sparkleSize * 0.6f, sparkleCy + sparkleSize * 0.6f), strokeWidth = 1.5f)
                drawLine(Color(0xFFFFD700), Offset(sparkleCx + sparkleSize * 0.6f, sparkleCy - sparkleSize * 0.6f), Offset(sparkleCx - sparkleSize * 0.6f, sparkleCy + sparkleSize * 0.6f), strokeWidth = 1.5f)
            }

            val sparkle2Cx = cx + headRadius * 1.5f
            val sparkle2Cy = cy - headRadius * 0.3f
            rotate(-sparkleRotation * 0.7f, Offset(sparkle2Cx, sparkle2Cy)) {
                drawLine(Color(0xFFFFD700), Offset(sparkle2Cx - sparkleSize * 0.7f, sparkle2Cy), Offset(sparkle2Cx + sparkleSize * 0.7f, sparkle2Cy), strokeWidth = 1.5f)
                drawLine(Color(0xFFFFD700), Offset(sparkle2Cx, sparkle2Cy - sparkleSize * 0.7f), Offset(sparkle2Cx, sparkle2Cy + sparkleSize * 0.7f), strokeWidth = 1.5f)
            }
        }
    }
}
