package io.github.pierrebog.lasermediaplayer

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.settings.ThemeMode
import io.github.pierrebog.lasermediaplayer.ui.home.HomeScreen
import io.github.pierrebog.lasermediaplayer.ui.home.HomeViewModel
import io.github.pierrebog.lasermediaplayer.ui.home.PlaybackRequest
import io.github.pierrebog.lasermediaplayer.ui.options.OptionsScreen
import io.github.pierrebog.lasermediaplayer.ui.player.PlayerScreen
import io.github.pierrebog.lasermediaplayer.ui.theme.DarkPalette
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserTheme
import io.github.pierrebog.lasermediaplayer.ui.theme.LightPalette

/**
 * The single activity. Three screens, switched on state: the home screen,
 * the options, and the player while a [PlaybackRequest] is set. Not
 * recreated on rotation (see android:configChanges), so playback survives
 * it; a change of language recreates it, from the options screen.
 *
 * An AppCompatActivity for AppCompat's per-app language, down to Android 5.
 */
class MainActivity : AppCompatActivity() {

    private val app get() = application as LaserApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by app.settings.theme.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            val home: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app))
            var playing by remember { mutableStateOf<PlaybackRequest?>(null) }
            var options by rememberSaveable { mutableStateOf(false) }

            /* The video player is always dark, so are its bars. */
            val video = playing?.let {
                it.row.kind != DiscKind.CD_AUDIO && it.row.kind != DiscKind.DVD_AUDIO
            } ?: false
            val darkBars = dark || video
            DisposableEffect(darkBars) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT)
                                     else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT)
                                         else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                onDispose {}
            }

            LaserTheme(if (dark) DarkPalette else LightPalette) {
                val request = playing
                when {
                    request != null -> PlayerScreen(request, app, onExit = {
                        playing = null
                        home.onPlaybackEnded()
                    })
                    options -> OptionsScreen(app.settings, onBack = { options = false })
                    else -> HomeScreen(home, onPlay = { playing = it },
                                       onOptions = { options = true })
                }
            }
        }
    }

    /* The drives' discs are followed while the app is shown. */
    override fun onStart() {
        super.onStart()
        app.drives.setForeground(true)
    }

    override fun onStop() {
        app.drives.setForeground(false)
        super.onStop()
    }

    /* Drives plugged or unplugged while the app was away; known drives keep
     * their classification. */
    override fun onResume() {
        super.onResume()
        app.drives.refresh()
    }

    /* A drive plugged while the app runs (singleTask): the system's prompt
     * has granted the permission. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) app.drives.refresh()
    }
}
