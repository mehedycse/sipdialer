@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.noxsip.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noxsip.app.ui.theme.Green500
import com.noxsip.app.ui.theme.Red500

@Composable
fun CallControls(
    isMuted: Boolean,
    isSpeaker: Boolean,
    isOnHold: Boolean,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onHoldToggle: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CallControlButton(
                icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = "Mute",
                isActive = isMuted,
                onClick = onMuteToggle
            )
            CallControlButton(
                icon = if (isSpeaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                label = "Speaker",
                isActive = isSpeaker,
                onClick = onSpeakerToggle
            )
            CallControlButton(
                icon = if (isOnHold) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                label = if (isOnHold) "Resume" else "Hold",
                isActive = isOnHold,
                onClick = onHoldToggle
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        FloatingActionButton(
            onClick = onEndCall,
            containerColor = Red500,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CallEnd,
                contentDescription = "End Call",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * ইনকামিং কলের ভার্টিক্যাল স্লাইড কন্ট্রোল: লম্বা ট্র্যাকের মাঝখানে সাদা হ্যান্ডেল।
 * ওপরে টেনে ছাড়লে Accept, নিচে টেনে ছাড়লে Reject। হ্যান্ডেল আঙুলের সাথে চলে (রং ও আইকন বদলায়),
 * না পৌঁছালে মাঝে ফিরে আসে। হ্যান্ডেলের চারপাশের রিং-এনিমেশন শুধু একবার চলে (বারবার নয়)।
 */
@Composable
fun IncomingCallControls(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleSize = 54.dp
    val inset = 5.dp
    val handlePx = with(density) { handleSize.toPx() }
    val insetPx = with(density) { inset.toPx() }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    val maxTravel = ((trackHeightPx - handlePx - 2f * insetPx) / 2f).coerceAtLeast(1f)
    // offset: ওপরে (Accept) = ঋণাত্মক, নিচে (Reject) = ধনাত্মক
    val offset = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    // মাঝের রিং-এনিমেশন: একবারই চলে
    LaunchedEffect(Unit) { pulse.animateTo(1f, tween(1100)) }

    // হ্যান্ডেল চালু থাকার ইঙ্গিত: কিছুক্ষণ পর পর ওপরে-নিচে হালকা নড়ে (আঙুল না লাগালে), আর শেভ্রন মিটমিট করে
    val hint = rememberInfiniteTransition(label = "hint")
    val nudge by hint.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3000
                0f at 0
                -1f at 380
                1f at 980
                0f at 1360
            }
        ),
        label = "nudge"
    )
    val chevronAlpha by hint.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "chevron"
    )
    val nudgePx = with(density) { 9.dp.toPx() }

    val frac = (offset.value / maxTravel).coerceIn(-1f, 1f)
    val absFrac = if (frac < 0f) -frac else frac
    val handleColor = when {
        frac < 0f -> lerp(Color.White, Green500, -frac)
        frac > 0f -> lerp(Color.White, Red500, frac)
        else -> Color.White
    }
    val iconColor = lerp(Color(0xFF1E3A8A), Color.White, absFrac)
    val labelAlpha = 1f - 0.75f * absFrac
    val trackShape = RoundedCornerShape(42.dp)

    Box(
        modifier = modifier
            .width(84.dp)
            .height(260.dp)
            .clip(trackShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), trackShape)
            .onSizeChanged { trackHeightPx = it.height },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 14.dp)
                .graphicsLayer { alpha = labelAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Green500.copy(alpha = chevronAlpha),
                    modifier = Modifier.size(22.dp)
                )
                Text("Accept", color = Green500.copy(alpha = 0.95f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Reject", color = Red500.copy(alpha = 0.95f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Red500.copy(alpha = chevronAlpha),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // একবারের রিং-পালস
        Box(
            modifier = Modifier
                .size(handleSize)
                .graphicsLayer {
                    val s = 1f + 0.6f * pulse.value
                    scaleX = s
                    scaleY = s
                    alpha = 0.35f * (1f - pulse.value)
                }
                .background(Color.White, CircleShape)
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(0, (offset.value + if (dragging || done) 0f else nudge * nudgePx).roundToInt()) }
                .size(handleSize)
                .clip(CircleShape)
                .background(handleColor)
                .pointerInput(maxTravel) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            scope.launch {
                                if (done) return@launch
                                when {
                                    offset.value <= -maxTravel * 0.7f -> {
                                        done = true
                                        offset.animateTo(-maxTravel, tween(120))
                                        onAnswer()
                                    }
                                    offset.value >= maxTravel * 0.7f -> {
                                        done = true
                                        offset.animateTo(maxTravel, tween(120))
                                        onDecline()
                                    }
                                    else -> offset.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { if (!done) offset.animateTo(0f, spring()) }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        if (!done) {
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-maxTravel, maxTravel))
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (frac > 0.15f) Icons.Filled.CallEnd else Icons.Filled.Call,
                contentDescription = "Slide up to accept, down to reject",
                tint = iconColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledIconToggleButton(
            checked = isActive,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                checkedContainerColor = Color(0xFF3B82F6),
                checkedContentColor = Color.White,
                containerColor = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = Color.White,
            maxLines = 1
        )
    }
}
