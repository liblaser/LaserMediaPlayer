package io.github.pierrebog.lasermediaplayer.ui.options

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.ui.FormFactor
import io.github.pierrebog.lasermediaplayer.ui.theme.BodyFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import io.github.pierrebog.lasermediaplayer.ui.theme.keyNavigation
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserType

/**
 * Entering the TMDB key: a bottom sheet on a phone, a side panel on a TV,
 * like the track sheets. The field holds the key [current] - in full, to be
 * corrected - and pasting works; on a TV, the system's keyboard opens on OK.
 */
@Composable
fun BoxScope.KeySheet(
    current: String,
    form: FormFactor,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var text by rememberSaveable { mutableStateOf(current) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val save = { if (text.isNotBlank()) onSave(text) }

    Box(Modifier
        .matchParentSize()
        .background(LaserColors.Scrim)
        .clickable(interactionSource = remember { MutableInteractionSource() },
                   indication = null, onClick = onDismiss))

    val tv = form == FormFactor.TV
    val panel = if (tv)
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(520.dp)
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
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = if (tv) 32.dp else 20.dp, vertical = if (tv) 40.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!tv)
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp)
                .clip(RoundedCornerShape(2.dp)).background(LaserColors.Border))
        Text(stringResource(R.string.tmdb_key), style = LaserType.SheetTitle)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim() },
            label = { Text(stringResource(R.string.key_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = LaserColors.Text,
                unfocusedTextColor = LaserColors.Text,
                focusedBorderColor = LaserColors.Accent,
                unfocusedBorderColor = LaserColors.Border,
                focusedLabelColor = LaserColors.Accent,
                unfocusedLabelColor = LaserColors.Muted,
                cursorColor = LaserColors.Accent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        Text(stringResource(R.string.tmdb_key_hint), color = LaserColors.Muted, fontFamily = BodyFamily,
             fontSize = 14.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current.isNotEmpty())
                SheetButton(stringResource(R.string.key_delete), primary = false, enabled = true,
                            onClick = onDelete, modifier = Modifier.weight(1f))
            SheetButton(stringResource(R.string.key_save), primary = true, enabled = text.isNotBlank(),
                        onClick = save, modifier = Modifier.weight(1f))
        }
        Box(Modifier.height(if (tv) 0.dp else 16.dp))
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit,
                        modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    /* With the remote, only the focus fills a button. */
    val filled = focused || (primary && !keyNavigation())
    val background = if (filled) LaserColors.Text else LaserColors.Surface
    val content = if (filled) LaserColors.Background else LaserColors.Text
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .border(1.dp, if (filled) Color.Transparent else LaserColors.Border, shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                       role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontFamily = BodyFamily, fontSize = 17.sp,
             fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}
