package com.music.zenith.ui.screens

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.music.zenith.sharedui.resources.*
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

/** ZF logo viewport: 950 x 484. */
private const val WORDMARK_ASPECT = 950f / 484f

private const val WORDMARK_INK_BOTTOM = 0.96f
private const val WORDMARK_TEXT_LIFT = 0.03f

@Composable
internal fun Wordmark(
    logoHeight: Dp,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    gap: Dp = 6.dp,
) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }

    Layout(
        content = {
            Icon(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .height(logoHeight)
                    .aspectRatio(WORDMARK_ASPECT),
            )

            Text(
                text = "MUSIC",
                color = color,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->

        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val logo = measurables[0].measure(loose)
        val word = measurables[1].measure(loose)

        val baseline = word[FirstBaseline].let {
            if (it == androidx.compose.ui.layout.AlignmentLine.Unspecified) {
                word.height
            } else it
        }

        val inkBottom = (logo.height * WORDMARK_INK_BOTTOM).roundToInt()
        val lift = (word.height * WORDMARK_TEXT_LIFT).roundToInt()

        val logoY = baseline - inkBottom + lift
        val shift = maxOf(0, -logoY)

        val logoTop = logoY + shift
        val wordTop = shift

        val width = logo.width + gapPx + word.width
        val height = maxOf(
            word.height + wordTop,
            logoTop + logo.height
        )

        layout(width, height) {
            logo.placeRelative(0, logoTop)
            word.placeRelative(logo.width + gapPx, wordTop)
        }
    }
}
