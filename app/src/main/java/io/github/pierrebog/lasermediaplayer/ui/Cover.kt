package io.github.pierrebog.lasermediaplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A disc's cover, decoded off the main thread; null until then, or if
 * there is none or it cannot be read. */
@Composable
fun rememberCover(file: File?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, file) {
    value = file?.let {
        withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull()
        }
    }
}
