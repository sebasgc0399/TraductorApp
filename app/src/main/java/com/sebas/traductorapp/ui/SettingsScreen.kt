package com.sebas.traductorapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.AppContainer
import com.sebas.traductorapp.data.AppSettings
import com.sebas.traductorapp.data.SpanishVariant
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SETTINGS_MODEL_SUGGESTIONS = listOf("gpt-6-luna", "gpt-5.6-luna", "gpt-5-mini")

/** Acción destructiva pendiente de confirmar. */
private class SettingsConfirmAction(
    val title: String,
    val message: String,
    val doneMessage: String,
    val op: suspend () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val current by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        val s = current
        if (s == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            SettingsContent(
                container = container,
                s = s,
                snackbar = snackbar,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun SettingsContent(
    container: AppContainer,
    s: AppSettings,
    snackbar: SnackbarHostState,
    modifier: Modifier,
) {
    val repo = container.settings
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf(s.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var modelInput by remember { mutableStateOf(s.model) }
    var prefetch by remember { mutableFloatStateOf(s.prefetchAhead.coerceIn(2, 20).toFloat()) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var cacheVersion by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<SettingsConfirmAction?>(null) }

    // El tamaño de la caché se calcula fuera del hilo principal.
    LaunchedEffect(cacheVersion) {
        cacheBytes = withContext(Dispatchers.IO) { container.imageCache.sizeBytes() }
    }

    fun runOp(doneMessage: String, op: suspend () -> Unit) {
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { op() } }.isSuccess
            cacheVersion++
            snackbar.showSnackbar(if (ok) doneMessage else "No se pudo completar la operación.")
        }
    }

    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- API key ---
        SettingsSectionTitle("API key de OpenAI")
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Ocultar" else "Ver")
                }
            },
            supportingText = { Text("Se guarda cifrada en el teléfono (Android Keystore).") },
        )
        val keySaved = s.hasApiKey && keyInput.trim() == s.apiKey
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val value = keyInput
                    scope.launch { repo.setApiKey(value) }
                },
                enabled = keyInput.trim() != s.apiKey,
            ) { Text("Guardar") }
            Spacer(Modifier.width(12.dp))
            when {
                keySaved -> Text("Guardada ✓", color = MaterialTheme.colorScheme.primary)
                !s.hasApiKey && keyInput.isBlank() -> Text(
                    "Sin API key",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text("Sin guardar", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        // --- Modelo ---
        SettingsSectionTitle("Modelo")
        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Modelo de OpenAI") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SETTINGS_MODEL_SUGGESTIONS.forEach { id ->
                FilterChip(
                    selected = s.model == id,
                    onClick = {
                        modelInput = id
                        scope.launch { repo.setModel(id) }
                    },
                    label = {
                        Text(if (id == AppSettings.DEFAULT_MODEL) "$id · recomendado" else id)
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val value = modelInput
                    scope.launch { repo.setModel(value) }
                },
                enabled = modelInput.isNotBlank() && modelInput.trim() != s.model,
            ) { Text("Guardar") }
            if (modelInput.trim() == s.model) {
                Spacer(Modifier.width(12.dp))
                Text("Guardado ✓", color = MaterialTheme.colorScheme.primary)
            }
        }

        HorizontalDivider()

        // --- Variante de español ---
        SettingsSectionTitle("Variante de español")
        Column(Modifier.selectableGroup()) {
            SpanishVariant.entries.forEach { variant ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = s.variant == variant,
                            onClick = { scope.launch { repo.setVariant(variant) } },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.variant == variant, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(variant.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        HorizontalDivider()

        // --- Traducción por adelantado ---
        SettingsSectionTitle("Traducción por adelantado")
        Text("Páginas a traducir por adelantado: ${prefetch.roundToInt()}")
        Slider(
            value = prefetch,
            onValueChange = { prefetch = it },
            valueRange = 2f..20f,
            steps = 17,
            onValueChangeFinished = {
                val n = prefetch.roundToInt()
                scope.launch { repo.setPrefetchAhead(n) }
            },
        )
        Text(
            "Más páginas: la traducción llega antes, pero puede gastar tokens en páginas que no llegues a leer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // --- Opciones ---
        SettingsSectionTitle("Opciones")
        SettingsSwitchRow("Traducir onomatopeyas", s.translateSfx) { enabled ->
            scope.launch { repo.setTranslateSfx(enabled) }
        }
        SettingsSwitchRow("Modo depuración (ver cajas del OCR)", s.debugBoxes) { enabled ->
            scope.launch { repo.setDebugBoxes(enabled) }
        }

        HorizontalDivider()

        // --- Caché ---
        SettingsSectionTitle("Caché")
        Text(
            "Imágenes guardadas: " + (cacheBytes?.let { formatSettingsMb(it) } ?: "calculando…"),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { runOp("Imágenes borradas") { container.imageCache.clear() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Borrar imágenes") }
        OutlinedButton(
            onClick = { runOp("OCR guardado borrado") { container.db.pageCache().clear() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Borrar OCR guardado") }
        OutlinedButton(
            onClick = {
                confirm = SettingsConfirmAction(
                    title = "¿Borrar traducciones guardadas?",
                    message = "Se borrarán las traducciones de todas las series. " +
                        "Al volver a leer se pedirán de nuevo a la IA (con costo).",
                    doneMessage = "Traducciones borradas",
                    op = { container.db.textCache().clear() },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Borrar traducciones guardadas") }
        OutlinedButton(
            onClick = {
                confirm = SettingsConfirmAction(
                    title = "¿Borrar historial de consumo?",
                    message = "Se perderá el registro de tokens y costos de todas las llamadas a la IA. " +
                        "Esta acción no se puede deshacer.",
                    doneMessage = "Historial de consumo borrado",
                    op = { container.db.usage().clear() },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Borrar historial de consumo") }

        HorizontalDivider()

        Text(
            "gpt-6-luna: ~0.002 USD por capítulo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    runOp(action.doneMessage, action.op)
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsSwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun formatSettingsMb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
