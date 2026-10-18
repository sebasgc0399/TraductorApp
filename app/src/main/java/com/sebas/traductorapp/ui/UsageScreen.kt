package com.sebas.traductorapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.AppContainer
import com.sebas.traductorapp.data.ChapterUsage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun usageUsd(value: Double): String = String.format(Locale.US, "%.4f USD", value)

private fun usageCount(value: Long): String = String.format(Locale.US, "%,d", value)

private fun usagePlural(n: Int, one: String, many: String): String =
    if (n == 1) "1 $one" else "${usageCount(n.toLong())} $many"

/** "vortexscans.org/series/solo-leveling" -> "Solo leveling". */
private fun usageSeriesLabel(seriesKey: String): String {
    val slug = seriesKey.trimEnd('/').substringAfterLast('/')
    val readable = slug.replace('-', ' ').replace('_', ' ').trim()
    return readable.replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { seriesKey }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(container: AppContainer, onBack: () -> Unit) {
    val chaptersFlow = remember { container.db.usage().observeByChapter() }
    val chapters by chaptersFlow.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }

    fun exportCsv() {
        if (exporting) return
        exporting = true
        scope.launch {
            val uri: Uri? = runCatching {
                withContext(Dispatchers.IO) {
                    val csv = UsageCsv.build(container.db.usage().all())
                    val file = File(context.cacheDir, "exports/consumo.csv")
                    file.parentFile?.mkdirs()
                    file.writeText(csv)
                    FileProvider.getUriForFile(context, context.packageName + ".files", file)
                }
            }.getOrNull()
            exporting = false
            if (uri == null) {
                snackbar.showSnackbar("No se pudo exportar el CSV.")
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, "Exportar consumo")) }
                .onFailure { snackbar.showSnackbar("No hay apps para compartir el archivo.") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consumo") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        val list = chapters
        when {
            list == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Aún no hay llamadas a la IA.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { UsageTotalsCard(list) }
                item {
                    Button(
                        onClick = { exportCsv() },
                        enabled = !exporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (exporting) "Exportando…" else "Exportar CSV") }
                }
                item {
                    Text(
                        "Por capítulo",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(list, key = { "${it.seriesKey}|${it.chapter}" }) { chapter ->
                    ChapterUsageCard(chapter)
                }
            }
        }
    }
}

@Composable
private fun UsageTotalsCard(list: List<ChapterUsage>) {
    val cost = list.sumOf { it.costUsd }
    val calls = list.sumOf { it.calls.toLong() }
    val input = list.sumOf { it.inputTokens.toLong() }
    val cached = list.sumOf { it.cachedTokens.toLong() }
    val output = list.sumOf { it.outputTokens.toLong() }
    val chapterCount = list.size
    val avgPerChapter = if (chapterCount > 0) cost / chapterCount else 0.0
    val cacheHitPct = if (input > 0) cached * 100.0 / input else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Total gastado", style = MaterialTheme.typography.labelLarge)
            Text(usageUsd(cost), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            UsageStatLine("Llamadas", usageCount(calls))
            UsageStatLine("Capítulos", usageCount(chapterCount.toLong()))
            UsageStatLine("Tokens de entrada", usageCount(input))
            UsageStatLine("Tokens en caché", usageCount(cached))
            UsageStatLine("Tokens de salida", usageCount(output))
            UsageStatLine("Aciertos de caché", String.format(Locale.US, "%.1f %%", cacheHitPct))
            UsageStatLine("Promedio por capítulo", usageUsd(avgPerChapter))
            if (avgPerChapter > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Con 10 USD alcanzan para ~${usageCount((10.0 / avgPerChapter).toLong())} capítulos",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun UsageStatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChapterUsageCard(c: ChapterUsage) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    usageSeriesLabel(c.seriesKey),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(usageUsd(c.costUsd), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Capítulo ${c.chapter ?: "—"} · ${usagePlural(c.calls, "llamada", "llamadas")} · " +
                    usagePlural(c.bubbles, "globo", "globos"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Tokens: ${usageCount(c.inputTokens.toLong())} entrada · " +
                    "${usageCount(c.cachedTokens.toLong())} caché · " +
                    "${usageCount(c.outputTokens.toLong())} salida",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
