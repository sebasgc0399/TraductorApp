package com.sebas.traductorapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.AppContainer
import com.sebas.traductorapp.data.SeriesEntity

private val HOME_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** Limpia la URL escrita y agrega https:// si no trae esquema. */
private fun normalizeHomeUrl(input: String): String {
    val trimmed = input.trim()
    return if (HOME_SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

/** Último segmento del path (sin query ni fragmento), p. ej. "chapter-24". */
private fun homeLastSegment(url: String): String =
    url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenGlossary: (seriesKey: String) -> Unit,
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val recentFlow = remember { container.db.series().observeRecent() }
    val recent by recentFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var url by rememberSaveable { mutableStateOf("") }

    val submit: () -> Unit = {
        if (url.isNotBlank()) onOpenUrl(normalizeHomeUrl(url))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TraductorApp", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Manhwa en inglés → español",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenUsage) { Text("Consumo") }
                    TextButton(onClick = onOpenSettings) { Text("Ajustes") }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (settings?.hasApiKey == false) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Falta la API key de OpenAI", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Sin ella la app no puede traducir los capítulos.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenSettings) { Text("Configurar API key") }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("URL del capítulo") },
                        placeholder = { Text("vortexscans.org/series/…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { submit() },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Abrir") }
                }
            }

            item {
                Text(
                    "Recientes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (recent.isEmpty()) {
                item {
                    Text(
                        "Abre un capítulo para empezar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(recent, key = { it.key }) { series ->
                    Column {
                        RecentSeriesRow(
                            series = series,
                            onOpenUrl = onOpenUrl,
                            onOpenGlossary = onOpenGlossary,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSeriesRow(
    series: SeriesEntity,
    onOpenUrl: (String) -> Unit,
    onOpenGlossary: (String) -> Unit,
) {
    val hasUrl = series.lastUrl.isNotBlank()
    ListItem(
        modifier = Modifier.clickable(enabled = hasUrl) { onOpenUrl(series.lastUrl) },
        headlineContent = {
            Text(
                series.title.ifBlank { series.key },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                if (hasUrl) homeLastSegment(series.lastUrl) else "Sin capítulo guardado",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            TextButton(onClick = { onOpenGlossary(series.key) }) { Text("Glosario") }
        },
    )
}
