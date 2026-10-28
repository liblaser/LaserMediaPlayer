package io.github.pierrebog.lasermediaplayer.ui.home

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.storage.AacsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system's file picker, for libaacs's key file: a KEYDB.cfg, or a zip
 * archive holding one. Any type is shown - a .cfg has none of its own -
 * and AacsKeys tells. [onLoading] once a file is chosen, while it is copied;
 * [onResult] then, or with null if the user cancelled.
 */
@Composable
fun rememberKeyFilePicker(onLoading: () -> Unit, onResult: (AacsKeys.Result?) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val loading by rememberUpdatedState(onLoading)
    val result by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            result(null)
        } else {
            loading()
            scope.launch {
                result(withContext(Dispatchers.IO) { AacsKeys.load(context, uri) })
            }
        }
    }
    return {
        try {
            launcher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            result(AacsKeys.Result.NO_PICKER)
        }
    }
}

/** The key file is being copied: a moment for a large one. */
@Composable
fun KeyFileLoadingDialog() = AlertDialog(
    onDismissRequest = {},
    text = { Text(stringResource(R.string.keys_loading)) },
    confirmButton = {},
)

/** Why the key file chosen was not taken. */
@Composable
fun KeyFileErrorDialog(result: AacsKeys.Result, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.keys_title)) },
    text = {
        Text(stringResource(when (result) {
            AacsKeys.Result.NO_PICKER -> R.string.keys_no_picker
            AacsKeys.Result.NOT_KEYDB -> R.string.keys_not_keydb
            else -> R.string.keys_unreadable
        }))
    },
    confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
    },
)
