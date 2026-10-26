package com.tsonglew.quodex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Finite Compose animations inherit Android's animator duration scale, including zero. */
@Composable
internal fun Modifier.revealOnEnter(replayKey: Any = Unit): Modifier {
    val reveal = remember(replayKey) { Animatable(0f) }
    LaunchedEffect(reveal) {
        reveal.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
    }
    // Read frames in the layer, so neither layout nor the page contents recompose per frame.
    return graphicsLayer {
        alpha = reveal.value
        translationY = 12.dp.toPx() * (1f - reveal.value)
    }
}

/** A single soft reflection and a tactile press, inspired by React Drops' Chromatic Button. */
@Composable
internal fun ConnectButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f), label = "connect-press")
    val reflection = remember { Animatable(0f) }
    LaunchedEffect(reflection) {
        reflection.animateTo(1f, tween(850, delayMillis = 120, easing = FastOutSlowInEasing))
    }
    val shape = RoundedCornerShape(28.dp)
    Button(onClick = onClick, interactionSource = interactions, shape = shape,
        modifier = modifier.heightIn(min = 52.dp).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }.clip(shape).drawWithContent {
            drawContent()
            val phase = reflection.value
            if (phase > 0f && phase < 1f) {
                val band = size.width * 0.45f
                val center = -band + (size.width + band * 2f) * phase
                drawRect(Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                    start = Offset(center - band, 0f), end = Offset(center + band, size.height),
                ))
            }
        }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(label)
    }
}
