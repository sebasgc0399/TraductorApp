package com.sebas.traductorapp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sebas.traductorapp.reader.ReaderHolder
import com.sebas.traductorapp.ui.GlossaryScreen
import com.sebas.traductorapp.ui.HomeScreen
import com.sebas.traductorapp.ui.ReaderScreen
import com.sebas.traductorapp.ui.SettingsScreen
import com.sebas.traductorapp.ui.TraductorTheme
import com.sebas.traductorapp.ui.UsageScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val sharedUrl = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Permite inspeccionar el lector desde chrome://inspect en la PC.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        sharedUrl.value = extractUrl(intent)
        val container = (application as TraductorApplication).container
        setContent {
            TraductorTheme {
                AppNav(container, sharedUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrl(intent)?.let { sharedUrl.value = it }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }
}

private sealed interface Route {
    data object Home : Route
    data class Reader(val url: String) : Route
    data object Settings : Route
    data object Usage : Route
    data class Glossary(val seriesKey: String) : Route
}

@Composable
private fun AppNav(container: AppContainer, sharedUrl: MutableStateFlow<String?>) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var holder by remember { mutableStateOf<ReaderHolder?>(null) }

    fun push(route: Route) {
        stack.add(route)
    }

    fun openReader(url: String) {
        holder?.destroy()
        holder = ReaderHolder(container, url)
        stack.removeAll { it !is Route.Home }
        stack.add(Route.Reader(url))
    }

    fun pop() {
        if (stack.size <= 1) return
        val removed = stack.removeAt(stack.lastIndex)
        if (removed is Route.Reader) {
            holder?.destroy()
            holder = null
        }
    }

    val shared by sharedUrl.collectAsState()
    LaunchedEffect(shared) {
        shared?.let {
            openReader(it)
            sharedUrl.value = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { holder?.destroy() }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val route = stack.last()) {
        Route.Home -> HomeScreen(
            container = container,
            onOpenUrl = { openReader(it) },
            onOpenSettings = { push(Route.Settings) },
            onOpenUsage = { push(Route.Usage) },
            onOpenGlossary = { push(Route.Glossary(it)) },
        )
        is Route.Reader -> holder?.let { h ->
            // Un lector nuevo necesita su propio AndroidView: sin key, Compose reutilizaría el WebView ya destruido.
            key(h) {
                ReaderScreen(
                    holder = h,
                    onBack = { pop() },
                    onOpenGlossary = { push(Route.Glossary(it)) },
                    onOpenSettings = { push(Route.Settings) },
                )
            }
        }
        Route.Settings -> SettingsScreen(container = container, onBack = { pop() })
        Route.Usage -> UsageScreen(container = container, onBack = { pop() })
        is Route.Glossary -> GlossaryScreen(container = container, seriesKey = route.seriesKey, onBack = { pop() })
    }
}
