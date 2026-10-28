package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.ui.FormFactor
import io.github.pierrebog.lasermediaplayer.ui.theme.FocusColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserType

data class TrackOption(val label: String, val detail: String?)

/**
 * The track choice: a bottom sheet on a phone, a side panel on a TV, over a
 * dimmed disc screen - "Choix de la piste audio" in the mockups.
 *
 * Drawn in the screen's own tree rather than as a dialog window, so that the
 * remote's focus stays in one place; [BackHandler] closes it.
 */
@Composable
fun BoxScope.TrackSheet(
    title: String,
    options: List<TrackOption>,
    selected: Int,
    form: FormFactor,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier
        .matchParentSize()
        .background(LaserColors.Scrim)
        .clickable(interactionSource = remember { MutableInteractionSource() },
                   indication = null, onClick = onDismiss)
        .semantics { contentDescription = title })

    val tv = form == FormFactor.TV
    val panel = if (tv)
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp)
            .background(LaserColors.Sheet)
    else
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(LaserColors.Sheet)

    Column(
        modifier = panel
            /* Swallows clicks, so that they do not reach the scrim. */
            .clickable(interactionSource = remember { MutableInteractionSource() },
                       indication = null, onClick = {})
            /* The sheet's colour runs under the navigation bar, its rows
             * stay above it. */
            .navigationBarsPadding()
            .padding(horizontal = if (tv) 32.dp else 20.dp, vertical = if (tv) 40.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!tv)
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp)
                .clip(RoundedCornerShape(2.dp)).background(LaserColors.Border))
        Text(title, style = LaserType.SheetTitle)
        Column(
            modifier = Modifier
                .selectableGroup()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { i, option ->
                TrackRow(
                    option = option,
                    selected = i == selected,
                    onClick = { onPick(i) },
                    modifier = if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(firstFocus)
                               else Modifier,
                )
            }
            Spacer(Modifier.height(if (tv) 16.dp else 32.dp))
        }
    }
}

@Composable
private fun TrackRow(
    option: TrackOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(when {
                focused -> LaserColors.Text
                selected -> LaserColors.AccentSurface
                else -> LaserColors.Sheet
            })
            .border(1.dp, when {
                focused -> Color.Transparent
                selected -> LaserColors.Accent
                else -> LaserColors.Track
            }, shape)
            .selectable(selected = selected, interactionSource = interaction,
                        indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FocusColors(focused) {
            Box(Modifier.size(20.dp)) {
                if (selected)
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                         tint = LaserColors.Accent)
            }
            Text(option.label, style = LaserType.Body,
                 fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                 modifier = Modifier.weight(1f))
            if (option.detail != null) Text(option.detail, style = LaserType.Small)
        }
    }
}
