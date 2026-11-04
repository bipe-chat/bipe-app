// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** Spec govbr-verify, guide (bipe-chat/bipe#1007): the pages in order. */
private enum class GovbrGuidePage { OPEN, CHOOSE, SIGN, AUTHORIZE, SAVE }

/**
 * Five example gov.br screens that the user swipes, each above its caption. The app draws them in Bipe colours with
 * gov.br's own labels. They never show a name, a CPF, a photo or a code.
 */
@Composable
fun GovbrGuide(fileName: String, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    val pages = GovbrGuidePage.entries
    val pager = rememberPagerState { pages.size }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
            val page = pages[index]
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(200.dp).height(260.dp).clearAndSetSemantics {}) { MockScreen(page, fileName) }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    Text((index + 1).toString(), style = MaterialTheme.typography.titleSmall, color = colors.inkSecondary, modifier = Modifier.width(24.dp))
                    Text(caption(page, fileName), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.Center) {
            pages.indices.forEach { i ->
                Box(Modifier.padding(4.dp).size(7.dp).background(if (i == pager.currentPage) colors.inkPrimary else colors.lineStrong, CircleShape))
            }
        }
    }
}

@Composable
private fun caption(page: GovbrGuidePage, fileName: String): String = when (page) {
    GovbrGuidePage.OPEN -> stringResource(R.string.bipe_govbr_guide_open)
    GovbrGuidePage.CHOOSE -> stringResource(R.string.bipe_govbr_guide_choose, fileName)
    GovbrGuidePage.SIGN -> stringResource(R.string.bipe_govbr_guide_sign)
    GovbrGuidePage.AUTHORIZE -> stringResource(R.string.bipe_govbr_guide_authorize)
    GovbrGuidePage.SAVE -> stringResource(R.string.bipe_govbr_guide_save)
}

/** One example screen. The highlighted part is what the user taps. */
@Composable
private fun MockScreen(page: GovbrGuidePage, fileName: String) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().height(260.dp).background(colors.surfaceBase, shape).border(1.dp, colors.lineStrong, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (page) {
            GovbrGuidePage.OPEN -> {
                Column(
                    Modifier.fillMaxWidth().height(44.dp).background(colors.accentFill, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                ) {
                    MockLine(60, colors.inkOnAccent)
                    MockLine(90, colors.inkOnAccent.copy(alpha = 0.6f))
                }
                Box(Modifier.fillMaxWidth().height(20.dp).border(1.dp, colors.lineStrong, CircleShape))
                MockRow()
                MockRow()
                Highlight {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MockLabel(stringResource(R.string.bipe_govbr_mock_service), Modifier.weight(1f))
                        Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = colors.inkPrimary, modifier = Modifier.size(10.dp))
                    }
                }
                MockRow()
            }
            GovbrGuidePage.CHOOSE -> {
                MockTitle(stringResource(R.string.bipe_govbr_mock_sign_file))
                MockSteps(active = 1)
                Highlight { MockLabel(stringResource(R.string.bipe_govbr_mock_choose), Modifier.fillMaxWidth()) }
                MockFile(fileName)
            }
            GovbrGuidePage.SIGN -> {
                Column(
                    Modifier.fillMaxWidth().height(150.dp).background(colors.surfaceRaised, RoundedCornerShape(4.dp))
                        .border(1.dp, colors.lineSubtle, RoundedCornerShape(4.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MockLine(110)
                    MockLine(140)
                    MockLine(90)
                    MockLine(120)
                    Spacer(Modifier.weight(1f))
                    val accent = colors.accentFill
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).width(90.dp).height(30.dp)
                            .background(colors.accentWash, RoundedCornerShape(4.dp))
                            .drawBehind {
                                drawRoundRect(accent, cornerRadius = CornerRadius(4.dp.toPx()),
                                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                            },
                    )
                }
                MockButton(stringResource(R.string.bipe_govbr_mock_sign))
            }
            GovbrGuidePage.AUTHORIZE -> {
                MockLine(140)
                MockLine(110)
                MockTitle(stringResource(R.string.bipe_govbr_mock_code), Modifier.padding(top = 8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp).border(1.dp, colors.lineStrong, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(6) { Box(Modifier.size(6.dp).background(colors.inkSecondary, CircleShape)) }
                }
                MockButton(stringResource(R.string.bipe_govbr_mock_authorize))
            }
            GovbrGuidePage.SAVE -> {
                MockTitle(stringResource(R.string.bipe_govbr_mock_sign_file))
                MockSteps(active = 3)
                MockLine(140)
                MockLine(100)
                MockFile(stringResource(R.string.bipe_govbr_mock_signed_name, fileName.removeSuffix(".pdf")))
                Highlight {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(PhosphorIcon.DOWNLOAD_SIMPLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(12.dp))
                        MockLabel(stringResource(R.string.bipe_govbr_mock_download), Modifier.padding(start = 6.dp), colors.accentInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun MockLine(width: Int, color: Color = BipeTheme.colors.lineStrong) {
    Box(Modifier.width(width.dp).height(6.dp).background(color, CircleShape))
}

@Composable
private fun MockRow() {
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(14.dp).background(BipeTheme.colors.lineSubtle, CircleShape))
        MockLine(100)
    }
}

@Composable
private fun MockTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = BipeTheme.colors.inkPrimary, modifier = modifier)
}

@Composable
private fun MockLabel(text: String, modifier: Modifier = Modifier, color: Color = BipeTheme.colors.inkPrimary) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 2, modifier = modifier)
}

/** The part to tap: accent wash with an accent outline. */
@Composable
private fun Highlight(content: @Composable ColumnScope.() -> Unit) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().background(colors.accentWash, shape).border(BorderStroke(2.dp, colors.accentFill), shape)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        content = content,
    )
}

@Composable
private fun MockSteps(active: Int) {
    val colors = BipeTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        (1..3).forEach { step ->
            val on = step == active
            Box(
                Modifier.size(20.dp).background(if (on) colors.accentFill else colors.surfaceBase, CircleShape)
                    .border(1.dp, if (on) colors.accentFill else colors.lineStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(step.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (on) colors.inkOnAccent else colors.inkSecondary)
            }
            if (step < 3) Box(Modifier.weight(1f).height(1.dp).background(colors.lineStrong))
        }
    }
}

@Composable
private fun MockFile(name: String) {
    val colors = BipeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(PhosphorIcon.FILE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(14.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MockButton(title: String) {
    val colors = BipeTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 26.dp).background(colors.accentFill, CircleShape).border(2.dp, colors.accentFill.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.inkOnAccent)
    }
}
