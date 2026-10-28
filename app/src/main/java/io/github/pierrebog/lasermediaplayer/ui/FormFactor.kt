package io.github.pierrebog.lasermediaplayer.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** The three layouts of the mockups. */
enum class FormFactor { PORTRAIT, LANDSCAPE, TV }

@Composable
fun rememberFormFactor(): FormFactor {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tv = remember(context) { context.isTelevision() }
    return when {
        tv -> FormFactor.TV
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> FormFactor.LANDSCAPE
        else -> FormFactor.PORTRAIT
    }
}

fun Context.isTelevision(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
