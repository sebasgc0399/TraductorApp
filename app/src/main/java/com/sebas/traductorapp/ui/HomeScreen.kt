package com.sebas.traductorapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.AppContainer
import com.sebas.traductorapp.data.SeriesEntity
import com.sebas.traductorapp.series.SeriesResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private val HOME_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** Limpia la URL escrita y agrega https:// si no trae esquema. */
private fun normalizeHomeUrl(input: String): String {
    val trimmed = input.trim()
    return if (HOME_SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

/** Último segmento del path (sin query ni fragmento), p. ej. "chapter-24". */
private fun homeLastSegment(url: String): String =
    url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')

/** "Capítulo 24" si la URL trae el número; si no, el último segmento tal cual. */
private fun homeChapterLabel(url: String): String =
    runCatching { SeriesResolver.resolve(url, null).chapter }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { "Capítulo $it" }
        ?: homeLastSegment(url)

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
    val libraryFlow = remember { container.db.series().observeLibrary() }
    val library by libraryFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }

    val all = library.orEmpty()
    // Lo último que se leyó con un capítulo guardado, sea o no favorita.
    val continueSeries = remember(all) {
        all.filter { it.lastUrl.isNotBlank() }.maxByOrNull { it.updatedAt }
    }
    val favorites = remember(all) { all.filter { it.favorite } }
    val recents = remember(all) { all.filterNot { it.favorite } }

    val submit: () -> Unit = {
        if (url.isNotBlank()) onOpenUrl(normalizeHomeUrl(url))
    }

    val toggleFavorite: (SeriesEntity) -> Unit = { series ->
        scope.launch(Dispatchers.IO) {
            // Con el candado de la serie, para no pisar una escritura del glosario en curso.
            container.seriesLock.withLock {
                container.db.series().setFavorite(series.key, !series.favorite)
            }
        }
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
                item(key = "api-key-warning") {
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

            continueSeries?.let { series ->
                item(key = "continue-reading") {
                    ContinueReadingCard(
                        series = series,
                        onOpen = { onOpenUrl(series.lastUrl) },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }

            item(key = "url-field") {
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

            // Mientras la biblioteca carga no se muestra el mensaje de "vacía".
            if (library != null) {
                if (all.isEmpty()) {
                    item(key = "library-empty") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Tu biblioteca está vacía", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Pega arriba el enlace de un capítulo o, desde Chrome, usa Compartir → TraductorApp. " +
                                    "Las series que leas aparecerán aquí.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (favorites.isNotEmpty()) {
                    item(key = "header-favorites") { LibrarySectionTitle("Favoritas") }
                    items(favorites, key = { "series:" + it.key }) { series ->
                        Column(Modifier.animateItem()) {
                            LibrarySeriesRow(
                                series = series,
                                onOpenUrl = onOpenUrl,
                                onOpenGlossary = onOpenGlossary,
                                onToggleFavorite = { toggleFavorite(series) },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                if (recents.isNotEmpty()) {
                    item(key = "header-recents") { LibrarySectionTitle("Recientes") }
                    items(recents, key = { "series:" + it.key }) { series ->
                        Column(Modifier.animateItem()) {
                            LibrarySeriesRow(
                                series = series,
                                onOpenUrl = onOpenUrl,
                                onOpenGlossary = onOpenGlossary,
                                onToggleFavorite = { toggleFavorite(series) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(series: SeriesEntity, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val chapter = remember(series.lastUrl) { homeChapterLabel(series.lastUrl) }
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Seguir leyendo", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    series.title.ifBlank { series.key },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    chapter,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("→", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun LibrarySectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun LibrarySeriesRow(
    series: SeriesEntity,
    onOpenUrl: (String) -> Unit,
    onOpenGlossary: (String) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val hasUrl = series.lastUrl.isNotBlank()
    val chapter = remember(series.lastUrl) { if (hasUrl) homeChapterLabel(series.lastUrl) else "Sin capítulo guardado" }
    ListItem(
        modifier = Modifier.clickable(enabled = hasUrl) { onOpenUrl(series.lastUrl) },
        leadingContent = {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics {
                    contentDescription = if (series.favorite) "Quitar de favoritas" else "Marcar como favorita"
                },
            ) {
                Text(
                    if (series.favorite) "★" else "☆",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (series.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        },
        headlineContent = {
            Text(
                series.title.ifBlank { series.key },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                chapter,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            TextButton(onClick = { onOpenGlossary(series.key) }) { Text("Glosario") }
        },
    )
}
