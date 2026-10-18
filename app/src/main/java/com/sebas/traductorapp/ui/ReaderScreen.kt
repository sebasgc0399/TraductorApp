package com.sebas.traductorapp.ui

import androidx.activity.compose.BackHandler
import com.sebas.traductorapp.reader.EditRequest
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.reader.ReaderHolder
import com.sebas.traductorapp.reader.ReaderUiState
import java.util.Locale

/**
 * Lector a pantalla completa. Las barras flotan sobre el WebView (no lo redimensionan, así la página no salta)
 * y se ocultan al bajar; vuelven al subir o si hay algo que atender.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    holder: ReaderHolder,
    onBack: () -> Unit,
    onOpenGlossary: (seriesKey: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by holder.session.state.collectAsStateWithLifecycle()
    val needsAttention = state.needsApiKey || state.message != null || !holder.bridgeSupported

    BackHandler {
        val wv = holder.webView()
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AndroidView(
            factory = { ctx -> holder.obtainWebView(ctx) },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        )

        AnimatedVisibility(
            visible = state.barsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.chapter?.seriesTitle ?: state.title.ifBlank { "Cargando…" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            state.chapter?.chapter?.let {
                                Text("Capítulo $it", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                    actions = {
                        TextButton(onClick = holder.session::toggleOriginal) {
                            Text(if (state.showOriginal) "EN" else "ES")
                        }
                        state.chapter?.let { info ->
                            TextButton(onClick = { onOpenGlossary(info.seriesKey) }) { Text("Glosario") }
                        }
                    },
                    // Opaca: si fuera translúcida, el encabezado del sitio se ve detrás del título.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                if (state.loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { state.loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.barsVisible || needsAttention,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderStatusBar(
                state = state,
                bridgeSupported = holder.bridgeSupported,
                onRetry = holder.session::retry,
                onOpenSettings = onOpenSettings,
                onNext = state.nextUrl?.let { url -> { holder.webView()?.loadUrl(url) } },
            )
        }
    }

    state.editing?.let { req ->
        EditTranslationDialog(
            request = req,
            onSave = holder.session::saveEdit,
            onDismiss = holder.session::dismissEdit,
        )
    }
}

/** Corrección manual de un globo: se guarda en la caché de la serie y se mantiene al releer. */
@Composable
private fun EditTranslationDialog(request: EditRequest, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(request) { mutableStateOf(request.es) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corregir traducción") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Original", style = MaterialTheme.typography.labelMedium)
                Text(
                    request.en,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Traducción") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Se aplica a todos los globos con el mismo texto de esta serie. \"Retraducir\" en el glosario la borra.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ReaderStatusBar(
    state: ReaderUiState,
    bridgeSupported: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onNext: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (text, isError) = when {
                !bridgeSupported -> "Actualiza \"Android System WebView\" desde Play Store" to true
                state.needsApiKey -> "Falta la API key de OpenAI" to true
                state.message != null -> state.message to true
                state.pagesTotal == 0 -> "Buscando páginas del capítulo…" to false
                else -> buildString {
                    append("Pág. ${state.currentPage}/${state.pagesTotal}")
                    append(" · ${state.pagesDone} listas")
                    append(" · ${String.format(Locale.US, "%.4f", state.chapterCostUsd)} USD")
                    if (state.translating) append(" · traduciendo…")
                    if (state.nextReady) append(" · sig. capítulo listo ✓")
                } to false
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                state.needsApiKey -> TextButton(onClick = onOpenSettings) { Text("Configurar") }
                state.message != null -> TextButton(onClick = onRetry) { Text("Reintentar") }
                onNext != null -> TextButton(onClick = onNext) { Text("Siguiente →") }
            }
        }
    }
}
