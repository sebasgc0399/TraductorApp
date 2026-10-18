package com.sebas.traductorapp.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebas.traductorapp.AppContainer
import kotlinx.coroutines.launch

internal const val OPENAI_API_KEYS_URL = "https://platform.openai.com/api-keys"
internal const val GITHUB_REPO_URL = "https://github.com/sebasgc0399/TraductorApp"
internal const val GITHUB_RELEASES_URL = "https://github.com/sebasgc0399/TraductorApp/releases"

/** Abre un enlace en el navegador. Devuelve false si no hay ninguna app que pueda abrirlo. */
internal fun openExternalUrl(context: Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.isSuccess

private const val ONBOARDING_STEPS = 3
private const val ONBOARDING_LAST_STEP = ONBOARDING_STEPS - 1

/** Bienvenida en 3 pasos: qué hace la app, la API key y cómo usarla. */
@Composable
fun OnboardingScreen(container: AppContainer, onFinish: () -> Unit) {
    val repo = container.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var step by rememberSaveable { mutableIntStateOf(0) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }

    val savedKey = settings?.apiKey.orEmpty()
    val hasSavedKey = savedKey.isNotBlank()
    val hasUnsavedKey = keyInput.isNotBlank() && keyInput.trim() != savedKey

    // Se leen los estados al momento de llamar, no los valores de la composición.
    fun saveKey() {
        val value = keyInput.trim()
        if (value.isEmpty() || value == settings?.apiKey) return
        focusManager.clearFocus()
        scope.launch { runCatching { repo.setApiKey(value) } }
    }

    fun finish() {
        if (finishing) return
        finishing = true
        val pending = keyInput.trim()
        val saved = settings?.apiKey.orEmpty()
        scope.launch {
            // Si quedó una key escrita sin guardar, no se pierde al terminar.
            if (pending.isNotEmpty() && pending != saved) runCatching { repo.setApiKey(pending) }
            val ok = runCatching { repo.setOnboardingDone(true) }.isSuccess
            if (ok) {
                onFinish()
            } else {
                finishing = false
            }
        }
    }

    BackHandler(enabled = step > 0 && !finishing) { step -= 1 }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Paso ${step + 1} de $ONBOARDING_STEPS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (step < ONBOARDING_LAST_STEP) {
                    TextButton(onClick = { finish() }, enabled = !finishing) { Text("Omitir") }
                }
            }

            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { w -> dir * w / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> -dir * w / 4 } + fadeOut())
                },
                label = "onboarding-step",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (current) {
                        0 -> OnboardingWhatStep()
                        1 -> OnboardingKeyStep(
                            keyInput = keyInput,
                            onKeyChange = { keyInput = it },
                            showKey = showKey,
                            onToggleShowKey = { showKey = !showKey },
                            hasSavedKey = hasSavedKey,
                            keySaved = hasSavedKey && (keyInput.isBlank() || keyInput.trim() == savedKey),
                            canSave = hasUnsavedKey,
                            onSave = { saveKey() },
                            linkFailed = linkFailed,
                            onCreateKey = { linkFailed = !openExternalUrl(context, OPENAI_API_KEYS_URL) },
                        )
                        else -> OnboardingHowToStep()
                    }
                }
            }

            OnboardingDots(
                current = step,
                count = ONBOARDING_STEPS,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }, enabled = !finishing) { Text("Atrás") }
                }
                Spacer(Modifier.weight(1f))
                when (step) {
                    0 -> Button(onClick = { step = 1 }) { Text("Siguiente") }
                    1 -> when {
                        hasUnsavedKey -> Button(onClick = {
                            saveKey()
                            step = 2
                        }) { Text("Guardar y seguir") }
                        hasSavedKey -> Button(onClick = { step = 2 }) { Text("Siguiente") }
                        else -> OutlinedButton(onClick = {
                            focusManager.clearFocus()
                            step = 2
                        }) { Text("Lo haré después") }
                    }
                    else -> Button(onClick = { finish() }, enabled = !finishing) { Text("Empezar") }
                }
            }
        }
    }
}

@Composable
private fun OnboardingWhatStep() {
    OnboardingHeader(emoji = "💬", title = "Traduce manhwas mientras lees")
    Text(
        "Abre un capítulo en inglés y léelo en español sin salir de la app.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    OnboardingPoint(
        icon = "📱",
        title = "Lector integrado",
        body = "Abres la página del capítulo dentro de la app, igual que en el navegador.",
    )
    OnboardingPoint(
        icon = "🗨️",
        title = "El español aparece sobre los globos",
        body = "El texto se reconoce en tu teléfono y la traducción se dibuja encima de cada globo.",
    )
    OnboardingPoint(
        icon = "⏩",
        title = "Traduce por adelantado",
        body = "Mientras lees, prepara las páginas siguientes para que casi no tengas que esperar.",
    )
    OnboardingPoint(
        icon = "📚",
        title = "Glosario por serie",
        body = "Recuerda nombres y términos para que se traduzcan siempre igual en toda la serie.",
    )
}

@Composable
private fun OnboardingKeyStep(
    keyInput: String,
    onKeyChange: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    hasSavedKey: Boolean,
    keySaved: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    linkFailed: Boolean,
    onCreateKey: () -> Unit,
) {
    OnboardingHeader(emoji = "🔑", title = "Tu API key de OpenAI")
    Text(
        "TraductorApp usa tu propia API key de OpenAI. No hay ningún servidor intermedio: " +
            "nadie más paga tus traducciones ni ve lo que lees.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Entra con tu cuenta de OpenAI, crea una key nueva, cópiala y pégala aquí. " +
            "Tu cuenta necesita algo de saldo (se recarga en la sección Billing).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCreateKey, modifier = Modifier.fillMaxWidth()) {
        Text("Crear una API key")
    }
    if (linkFailed) {
        Text(
            "No se encontró un navegador. Abre $OPENAI_API_KEYS_URL a mano.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val looksOdd = keyInput.isNotBlank() && !keyInput.trim().startsWith("sk-")
    OutlinedTextField(
        value = keyInput,
        onValueChange = onKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API key") },
        placeholder = { Text("sk-…") },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
        trailingIcon = {
            TextButton(onClick = onToggleShowKey) { Text(if (showKey) "Ocultar" else "Ver") }
        },
        supportingText = if (looksOdd) {
            { Text("Las keys de OpenAI suelen empezar con «sk-».") }
        } else {
            null
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSave, enabled = canSave) { Text("Guardar") }
        Spacer(Modifier.width(12.dp))
        when {
            keySaved && keyInput.isBlank() -> Text(
                "Ya tienes una API key guardada ✓",
                color = MaterialTheme.colorScheme.primary,
            )
            keySaved -> Text("Guardada ✓", color = MaterialTheme.colorScheme.primary)
            !hasSavedKey && keyInput.isBlank() -> Text(
                "Puedes agregarla después en Ajustes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    OnboardingNote(
        icon = "💵",
        text = "≈ 0,001 USD por capítulo con gpt-6-luna (10 USD alcanzan para miles de capítulos).",
    )
    OnboardingNote(
        icon = "🔒",
        text = "La key se guarda cifrada en tu teléfono. A OpenAI solo se envía el texto de los globos, " +
            "nunca las imágenes.",
    )
}

@Composable
private fun OnboardingHowToStep() {
    OnboardingHeader(emoji = "📖", title = "Cómo usarla")
    OnboardingPoint(
        icon = "🔗",
        title = "Abre un capítulo",
        body = "Pega el enlace del capítulo en la pantalla de inicio, o desde Chrome usa " +
            "Compartir → TraductorApp.",
    )
    OnboardingPoint(
        icon = "🔤",
        title = "Botón ES / EN",
        body = "Cambia entre la traducción y el texto original cuando quieras.",
    )
    OnboardingPoint(
        icon = "👆",
        title = "Mantén presionado un globo",
        body = "Para ver el texto original o corregir la traducción a tu gusto.",
    )
    OnboardingPoint(
        icon = "📚",
        title = "Glosario",
        body = "Revisa y edita cómo se traducen los nombres y términos de cada serie.",
    )
    OnboardingPoint(
        icon = "📊",
        title = "Consumo",
        body = "Mira cuánto llevas gastado, por capítulo y por serie.",
    )
    Text(
        "Puedes volver a ver esta guía en Ajustes → Acerca de.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OnboardingHeader(emoji: String, title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 56.sp)
        }
        Spacer(Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OnboardingPoint(icon: String, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnboardingNote(icon: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Text(icon, fontSize = 20.sp, modifier = Modifier.width(36.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OnboardingDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val active = i == current
            val dotWidth by animateDpAsState(if (active) 24.dp else 8.dp, label = "onboarding-dot")
            Box(
                Modifier
                    .size(width = dotWidth, height = 8.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}
