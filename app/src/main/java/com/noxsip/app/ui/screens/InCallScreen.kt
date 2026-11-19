package com.noxsip.app.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Call
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noxsip.app.sip.CallQualityStats
import com.noxsip.app.sip.SipCallState
import com.noxsip.app.ui.components.CallControls
import com.noxsip.app.ui.components.DialPad
import com.noxsip.app.ui.components.IncomingCallControls
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(
    callState: SipCallState,
    isMuted: Boolean,
    isSpeaker: Boolean,
    callDurationProvider: () -> Int,
    quality: CallQualityStats? = null,
    contacts: List<com.noxsip.app.util.DeviceContact> = emptyList(),
    accountUser: String? = null,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onEndCall: () -> Unit,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onHoldToggle: () -> Unit,
    onDtmf: (Char) -> Unit,
    isRecording: Boolean = false,
    onRecordToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDialpad by remember { mutableStateOf(false) }
    var callDuration by remember { mutableIntStateOf(0) }
    // কানের কাছে নিলে স্ক্রিন বন্ধ (স্পিকার বা ডায়ালপ্যাড খোলা থাকলে নয়)
    com.noxsip.app.util.ProximityScreenOffEffect(
        active = (callState is SipCallState.Connected || callState is SipCallState.Outgoing) && !isSpeaker && !showDialpad
    )

    LaunchedEffect(callState) {
        if (callState is SipCallState.Connected) {
            while (true) {
                callDuration = callDurationProvider()
                delay(1000)
            }
        }
    }

    val remoteAddress = when (callState) {
        is SipCallState.Incoming -> callState.remoteAddress
        is SipCallState.Outgoing -> callState.remoteAddress
        is SipCallState.Connected -> callState.remoteAddress
        is SipCallState.OnHold -> callState.remoteAddress
        is SipCallState.Ended -> callState.remoteAddress
        else -> ""
    }

    val cleanName = remoteAddress.replace("sip:", "").split("@").firstOrNull() ?: remoteAddress
    // সংরক্ষিত নম্বর হলে নাম দেখাই, নম্বর নিচে ছোট করে
    val contactName = remember(cleanName, contacts) {
        com.noxsip.app.util.ContactsHelper.nameFor(cleanName, contacts)
    }

    val statusText = when (callState) {
        is SipCallState.Incoming -> "Incoming call via ${accountUser ?: "SIP"}"
        is SipCallState.Outgoing -> "Calling via ${accountUser ?: "SIP"}"
        is SipCallState.Connected -> "Connected • ${formatDuration(callDuration)}"
        is SipCallState.OnHold -> "Call On Hold"
        is SipCallState.Ended -> if (callState.reason.startsWith("Call failed")) callState.reason else "Call Ended"
        else -> ""
    }

    val isOnHold = callState is SipCallState.OnHold

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        com.noxsip.app.ui.theme.themed(Color(0xFF0A1428), Color(0xFF000000)),
                        com.noxsip.app.ui.theme.themed(Color(0xFF13274F), Color(0xFF0E0E0E)),
                        com.noxsip.app.ui.theme.themed(Color(0xFF0A1428), Color(0xFF000000))
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Caller Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 36.dp)
            ) {
                Surface(
                    color = com.noxsip.app.ui.theme.themed(Color(0xFF1E3A6E), Color(0xFF1F1F1F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "SIP VOIP HD AUDIO",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = contactName ?: cleanName,
                    fontSize = if (contactName != null) 32.sp else 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                if (contactName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cleanName,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = statusText,
                    fontSize = 16.sp,
                    // কানেক্টেড হলে "Connected" ও টাইমার সবুজ
                    color = if (callState is SipCallState.Connected) Color(0xFF22C55E) else Color(0xFFCBD5E1),
                    fontWeight = if (callState is SipCallState.Connected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )

                val recCtx = androidx.compose.ui.platform.LocalContext.current
                if (callState is SipCallState.Connected && isRecording) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
                    val dotAlpha by pulse.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.25f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(700),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "recAlpha"
                    )
                    Surface(color = Color(0x33EF4444), shape = RoundedCornerShape(50)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444).copy(alpha = dotAlpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "REC",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                    }
                }

                if (callState is SipCallState.Connected && quality != null && quality.codec.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // লস/RTT/জিটার অনুযায়ী সবুজ, হলুদ বা লাল
                    val qualityColor = when {
                        quality.lossPercent > 5f || quality.rttMs > 400 || quality.jitterMs > 80 -> Color(0xFFEF4444)
                        quality.lossPercent > 1f || quality.rttMs > 200 || quality.jitterMs > 40 -> Color(0xFFF59E0B)
                        else -> Color(0xFF22C55E)
                    }
                    var showDetails by remember { mutableStateOf(true) }
                    val qualityLabel = when (qualityColor) {
                        Color(0xFF22C55E) -> "Good connection"
                        Color(0xFFF59E0B) -> "Fair connection"
                        else -> "Poor connection"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showDetails = !showDetails }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(qualityColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (showDetails) {
                                "${quality.codec} • RTT ${quality.rttMs}ms • Jitter ${quality.jitterMs}ms • " +
                                    "Loss ${"%.1f".format(quality.lossPercent)}%"
                            } else {
                                "$qualityLabel • ${quality.codec}"
                            },
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1),
                            textAlign = TextAlign.Center
                        )
                    }
                    if (showDetails) {
                        // ডায়াগনস্টিক: কথা বললে Mic ভ্যালু বাড়ার কথা। সবসময় -90 বা কম হলে মাইক সাইলেন্ট।
                        Text(
                            text = "Mic ${quality.micDb.toInt()} dB • ${if (quality.micEnabled) "on" else "MUTED"} • " +
                                "in: ${quality.inputDevice.ifEmpty { "NONE" }} • ↑${quality.upKbps} ↓${quality.downKbps} kbps",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // মাঝের ফাঁকা জায়গায় কল যাওয়া/আসার অ্যানিমেশন (ঢেউ)
            if (!(showDialpad && callState is SipCallState.Connected)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (callState is SipCallState.Outgoing) {
                        CallingRipples()
                    }
                    // ইনকামিং: স্ক্রিনের মাঝখানে ভার্টিক্যাল স্লাইডার (ওপরে Accept, নিচে Reject)
                    if (callState is SipCallState.Incoming) {
                        IncomingCallControls(
                            onAnswer = onAnswer,
                            onDecline = onDecline
                        )
                    }
                }
            }

            // Optional DTMF Keypad
            if (showDialpad && callState is SipCallState.Connected) {
                DialPad(
                    onDigitPressed = { digit ->
                        onDtmf(digit.first())
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Bottom Control Dock
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (callState is SipCallState.Connected) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        InCallActionButton(
                            label = "Keypad",
                            active = showDialpad,
                            activeColor = Color(0xFF3B82F6),
                            onClick = { showDialpad = !showDialpad }
                        ) { KeypadGlyph(Color.White) }
                        InCallActionButton(
                            label = if (isRecording) "Rec On" else "Rec Off",
                            active = isRecording,
                            activeColor = Color(0xFFEF4444),
                            onClick = onRecordToggle
                        ) { RecordGlyph(isRecording) }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                when (callState) {
                    is SipCallState.Outgoing,
                    is SipCallState.Connected,
                    is SipCallState.OnHold -> {
                        CallControls(
                            isMuted = isMuted,
                            isSpeaker = isSpeaker,
                            isOnHold = isOnHold,
                            onMuteToggle = onMuteToggle,
                            onSpeakerToggle = onSpeakerToggle,
                            onHoldToggle = onHoldToggle,
                            onEndCall = onEndCall
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}

/** কল যাওয়ার সময় ঢেউয়ের মতো ছড়িয়ে পড়া তিনটি রিং ও মাঝে ফোন আইকন */
@Composable
private fun CallingRipples() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "ripples")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(2400, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "ripplesProgress"
    )
    val iconPulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "iconPulse"
    )
    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val minR = 34.dp.toPx()
            val maxR = size.minDimension / 2f
            for (i in 0 until 3) {
                val p = (progress + i / 3f) % 1f
                drawCircle(
                    color = Color(0xFF3B82F6).copy(alpha = 0.35f * (1f - p)),
                    radius = minR + (maxR - minR) * p,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
            }
        }
        Box(
            modifier = Modifier
                .size((64 * iconPulse).dp)
                .clip(CircleShape)
                .background(Color(0xFF2563EB)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

/** কল স্ক্রিনের গোল অ্যাকশন বাটন (আইকন + নিচে লেখা) */
@Composable
private fun InCallActionButton(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (active) activeColor else Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { content() }
        Spacer(modifier = Modifier.height(5.dp))
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFFCBD5E1))
    }
}

/** নাম্বারপ্যাডের আইকন: ৩x৪ বিন্দুর গ্রিড (কিপ্যাডের মতো) */
@Composable
private fun KeypadGlyph(color: Color) {
    Canvas(modifier = Modifier.size(width = 24.dp, height = 30.dp)) {
        val r = size.width / 11f
        for (row in 0..3) {
            for (col in 0..2) {
                drawCircle(
                    color = color,
                    radius = r,
                    center = Offset(size.width * (col * 2 + 1) / 6f, size.height * (row * 2 + 1) / 8f)
                )
            }
        }
    }
}

/** রেকর্ড আইকন: চালু = ভরাট সাদা বিন্দু, বন্ধ = ফাঁপা রিং */
@Composable
private fun RecordGlyph(on: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .then(
                if (on) Modifier.background(Color.White, CircleShape)
                else Modifier.border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            )
    )
}
