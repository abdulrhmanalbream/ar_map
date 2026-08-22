package com.sarab.vision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.SignMatch

private val Panel = Color(0xF2101822)
private val Accent = Color(0xFF4FC3F7)
private val Good = Color(0xFF66D9AF)
private val Warn = Color(0xFFFFB300)
private val Muted = Color(0xFF9FB3C8)

/**
 * Shows what the camera read off the building's name plaque.
 *
 * ## Why this is presented as information, not action
 *
 * The colleges on this campus are built to one template. Getting the wrong one
 * is easy, and a wrong answer delivered confidently sends someone into the
 * wrong building without ever giving them a reason to doubt it. So a confident
 * read is stated plainly, an uncertain one says it is uncertain, and neither
 * ever changes the destination on its own -- retargeting is a button the user
 * presses, not something that happens to them.
 */
@Composable
fun SignOverlay(
    match: SignMatch?,
    currentTargetId: String?,
    onNavigateTo: (Landmark) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = match != null && match !is SignMatch.NoText

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            when (match) {
                is SignMatch.Found -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(9.dp).background(Good, CircleShape)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "قرأت اللافتة",
                            color = Good,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        match.landmark.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 23.sp
                    )

                    if (match.landmark.id == currentTargetId) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "هذا هو المبنى الذي تقصده — وصلت.",
                            color = Good,
                            fontSize = 12.sp
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "التوجّه إلى هذا المبنى",
                            color = Color(0xFF0B1520),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Accent, RoundedCornerShape(11.dp))
                                .clickable { onNavigateTo(match.landmark) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }

                is SignMatch.Unsure -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(9.dp).background(Warn, CircleShape)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "لست متأكداً",
                            color = Warn,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        match.reason,
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    // The best guess is offered as a question, never as an
                    // answer, so a wrong one costs a glance rather than a walk.
                    match.bestGuess?.let { guess ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "هل تقصد: ${guess.name}؟",
                            color = Accent,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1B2836), RoundedCornerShape(11.dp))
                                .clickable { onNavigateTo(guess) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}
