package com.noxsip.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ফোনের ডায়ালার থেকে SIP বাছার পর, আসল কল স্ক্রিন আসার আগের অল্প সময়ের জন্য এই পর্দা দেখায়,
 * যাতে অ্যাপের Dial/হিস্ট্রি স্ক্রিন ঝলকে না ওঠে। রং ও ধাঁচ কল স্ক্রিনের মতোই।
 */
@Composable
fun PendingCallScreen(number: String) {
    val clean = number.replace("sip:", "").substringBefore("@")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(com.noxsip.app.ui.theme.themed(Color(0xFF0A1428), Color(0xFF000000)), com.noxsip.app.ui.theme.themed(Color(0xFF13274F), Color(0xFF0E0E0E)), com.noxsip.app.ui.theme.themed(Color(0xFF0A1428), Color(0xFF000000)))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Surface(color = com.noxsip.app.ui.theme.themed(Color(0xFF1E3A6E), Color(0xFF1F1F1F)), shape = RoundedCornerShape(12.dp)) {
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
                text = clean,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Calling…",
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = Color(0xFFCBD5E1)
            )
        }
    }
}
