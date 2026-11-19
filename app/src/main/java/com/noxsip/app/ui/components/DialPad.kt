package com.noxsip.app.ui.components

import com.noxsip.app.ui.theme.themed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DialPad(
    onDigitPressed: (String) -> Unit,
    onLongPressZero: (() -> Unit)? = null,
    flat: Boolean = false,
    modifier: Modifier = Modifier
) {
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (flat) 2.dp else 12.dp)
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { (digit, letters) ->
                    DialPadButton(
                        digit = digit,
                        letters = letters,
                        flat = flat,
                        onClick = { onDigitPressed(digit) },
                        onLongClick = if (digit == "0" && onLongPressZero != null) {
                            onLongPressZero
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialPadButton(
    digit: String,
    letters: String,
    flat: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current

    // Flat, clean circular button without muddy elevation shadows
    Box(
        modifier = Modifier
            .size(if (flat) 58.dp else 74.dp)
            .clip(CircleShape)
            .background(if (flat) Color.Transparent else themed(Color(0xFFF0F3FA), Color(0xFF1A1A1A)))
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = {
                    if (onLongClick != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                fontSize = 30.sp,
                fontWeight = FontWeight.Normal,
                color = themed(Color(0xFF1F2833), Color(0xFFF1F5F9))
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                    color = if (flat) Color(0xFF4FA36B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
