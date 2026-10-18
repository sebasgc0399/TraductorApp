package com.sebas.traductorapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.AppContainer
import com.sebas.traductorapp.data.SeriesEntity
import com.sebas.traductorapp.model.GlossaryEntry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val GLOSSARY_LIST_SERIALIZER = ListSerializer(GlossaryEntry.serializer())

/** Fila editable; [id] es solo una clave estable para la lista. */
private data class GlossaryEditRow(val id: Long, val en: String, val es: String)

private fun decodeGlossaryJson(json: Json, raw: String?): List<GlossaryEntry> =
    if (raw.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(GLOSSARY_LIST_SERIALIZER, raw) }.getOrDefault(emptyList())
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(container: AppContainer, seriesKey: String, onBack: () -> Unit) {
    val dao = container.db.series()
    val seriesFlow = remember(seriesKey) { dao.observe(seriesKey) }
    val series by seriesFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ids = remember { AtomicLong() }
    val rows = remember(seriesKey) { mutableStateListOf<GlossaryEditRow>() }
    var initialEntries by remember(seriesKey) { mutableStateOf(emptyList<GlossaryEntry>()) }
    var summary by remember(seriesKey) { mutableStateOf("") }
    var loaded by remember(seriesKey) { mutableStateOf(false) }
    var exists by remember(seriesKey) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmRetranslate by remember { mutableStateOf(false) }

    // Se carga una sola vez para no pisar lo que el usuario está editando.
    LaunchedEffect(seriesKey) {
        val entity = seriesFlow.first()
        val entries = decodeGlossaryJson(container.json, entity?.glossaryJson)
        initialEntries = entries
        rows.clear()
        rows.addAll(entries.map { GlossaryEditRow(ids.incrementAndGet(), it.en, it.es) })
        summary = entity?.summary.orEmpty()
        exists = entity != null
        loaded = true
    }

    suspend fun persist() {
        // El orden se respeta tal cual (nunca ordenar): así el prefijo del prompt sigue en caché.
        val edited = rows.mapNotNull { r ->
            val en = r.en.trim()
            val es = r.es.trim()
            if (en.isEmpty() || es.isEmpty()) null else GlossaryEntry(en, es)
        }.distinctBy { it.en.lowercase() }
        val baseline = initialEntries
        val newSummary = summary.trim()
        val aiAdded = withContext(Dispatchers.IO) { container.seriesLock.withLock {
            val latest = dao.get(seriesKey) ?: SeriesEntity(key = seriesKey, title = seriesKey)
            // Términos que la IA agregó mientras se editaba: se conservan al final.
            val added = decodeGlossaryJson(container.json, latest.glossaryJson).filter { e ->
                baseline.none { it.en.equals(e.en, ignoreCase = true) } &&
                    edited.none { it.en.equals(e.en, ignoreCase = true) }
            }
            dao.upsert(
                latest.copy(
                    glossaryJson = container.json.encodeToString(GLOSSARY_LIST_SERIALIZER, edited + added),
                    summary = newSummary,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            added
        } }
        rows.addAll(aiAdded.map { GlossaryEditRow(ids.incrementAndGet(), it.en, it.es) })
        initialEntries = edited + aiAdded
    }

    val onSave: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                val ok = runCatching { persist() }.isSuccess
                saving = false
                snackbar.showSnackbar(if (ok) "Glosario guardado ✓" else "No se pudo guardar el glosario.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Glosario",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            series?.title?.takeIf { it.isNotBlank() } ?: seriesKey,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = loaded && exists && !saving) {
                        Text("Guardar")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when {
            !loaded -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            !exists -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("No se encontró esta serie.") }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "Los nombres y términos del glosario se respetan siempre al traducir. " +
                            "La IA agrega términos nuevos automáticamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        "Términos (${rows.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "Todavía no hay términos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(rows, key = { it.id }) { row ->
                    GlossaryRowEditor(
                        row = row,
                        onChange = { updated ->
                            val i = rows.indexOfFirst { it.id == updated.id }
                            if (i >= 0) rows[i] = updated
                        },
                        onRemove = {
                            val i = rows.indexOfFirst { it.id == row.id }
                            if (i >= 0) rows.removeAt(i)
                        },
                    )
                }
                item {
                    // Siempre al final: el orden importa para el caché del prompt.
                    OutlinedButton(
                        onClick = { rows.add(GlossaryEditRow(ids.incrementAndGet(), "", "")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Agregar término") }
                }
                item {
                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        label = { Text("Resumen de la serie") },
                        supportingText = { Text("Contexto breve para la IA: personajes, relaciones y tono.") },
                        minLines = 3,
                    )
                }
                item {
                    Button(
                        onClick = onSave,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (saving) "Guardando…" else "Guardar") }
                }
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Las traducciones guardadas de esta serie se borrarán y se pedirán de nuevo " +
                                "con el glosario actualizado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { confirmRetranslate = true },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Retraducir esta serie") }
                    }
                }
            }
        }
    }

    if (confirmRetranslate) {
        AlertDialog(
            onDismissRequest = { confirmRetranslate = false },
            title = { Text("¿Retraducir esta serie?") },
            text = {
                Text(
                    "Las traducciones guardadas de esta serie se borrarán y se pedirán de nuevo " +
                        "con el glosario actualizado. Antes se guardan los cambios del glosario.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetranslate = false
                    saving = true
                    scope.launch {
                        val ok = runCatching {
                            persist()
                            withContext(Dispatchers.IO) { container.db.textCache().clearSeries(seriesKey) }
                        }.isSuccess
                        saving = false
                        snackbar.showSnackbar(
                            if (ok) {
                                "Listo: se volverá a traducir al abrir los capítulos."
                            } else {
                                "No se pudo borrar las traducciones."
                            },
                        )
                    }
                }) { Text("Retraducir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetranslate = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun GlossaryRowEditor(
    row: GlossaryEditRow,
    onChange: (GlossaryEditRow) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = row.en,
            onValueChange = { onChange(row.copy(en = it)) },
            modifier = Modifier.weight(1f),
            label = { Text("EN") },
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = row.es,
            onValueChange = { onChange(row.copy(es = it)) },
            modifier = Modifier.weight(1f),
            label = { Text("ES") },
            singleLine = true,
        )
        TextButton(onClick = onRemove) { Text("Quitar") }
    }
}
