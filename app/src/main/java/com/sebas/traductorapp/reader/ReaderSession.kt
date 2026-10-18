package com.sebas.traductorapp.reader

import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.webkit.JavaScriptReplyProxy
import com.sebas.traductorapp.AppContainer
import com.sebas.traductorapp.data.AppSettings
import com.sebas.traductorapp.data.PageCacheEntity
import com.sebas.traductorapp.data.SeriesEntity
import com.sebas.traductorapp.data.SpanishVariant
import com.sebas.traductorapp.data.TextCacheEntity
import com.sebas.traductorapp.data.UsageLogEntity
import com.sebas.traductorapp.model.ChapterInfo
import com.sebas.traductorapp.model.GlossaryEntry
import com.sebas.traductorapp.model.PageAnalysis
import com.sebas.traductorapp.model.SourceLine
import com.sebas.traductorapp.model.TokenUsage
import com.sebas.traductorapp.pipeline.TextFilter
import com.sebas.traductorapp.series.SeriesResolver
import com.sebas.traductorapp.translate.BatchPlanner
import com.sebas.traductorapp.translate.LocalDictionary
import com.sebas.traductorapp.translate.OutputText
import com.sebas.traductorapp.translate.Pricing
import com.sebas.traductorapp.translate.PromptBuilder
import com.sebas.traductorapp.translate.SummaryRequest
import com.sebas.traductorapp.translate.TranslateParams
import com.sebas.traductorapp.translate.TranslatorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request

data class ReaderUiState(
    val url: String = "",
    val title: String = "",
    val loadProgress: Int = 0,
    val chapter: ChapterInfo? = null,
    val pagesTotal: Int = 0,
    val pagesDone: Int = 0,
    val currentPage: Int = 0,
    val chapterCostUsd: Double = 0.0,
    val translating: Boolean = false,
    val message: String? = null,
    val needsApiKey: Boolean = false,
    val showOriginal: Boolean = false,
    /** Modo inmersivo: las barras se ocultan al bajar y vuelven al subir. */
    val barsVisible: Boolean = true,
    /** El siguiente capítulo ya está descargado, con OCR y traducido (al menos sus primeras páginas). */
    val nextReady: Boolean = false,
)

/**
 * Orquesta una sesión de lectura: recibe las imágenes que reporta reader.js, corre el OCR en orden de cercanía
 * a la posición de lectura, traduce en lotes planificados por [BatchPlanner] con glosario + contexto y devuelve
 * los globos al DOM. También precarga el siguiente capítulo y mantiene el resumen de la serie.
 *
 * Todo el estado se muta en el hilo principal; el trabajo pesado va en withContext(IO/Default) y nunca lee ni
 * escribe ese estado. Los resultados asíncronos se descartan si el documento cambió ([generation]).
 */
class ReaderSession(private val container: AppContainer) : ReaderWebListener {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Error no controlado en la sesión de lectura", e) },
    )
    private val db = container.db
    private val json = container.json
    private val glossarySerializer = ListSerializer(GlossaryEntry.serializer())

    @Volatile
    var userAgent: String = ""

    @Volatile
    private var pageUrl: String? = null

    val interceptor = ImageInterceptor(container.http, container.imageCache) { userAgent }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private enum class Status { QUEUED, WORKING, READY, NOT_PAGE, FAILED }

    private class PageSlot(val url: String, var idx: Int, val next: Boolean = false) {
        var status = Status.QUEUED
        var analysis: PageAnalysis? = null
        val translations = HashMap<Int, String>()
        val pending = LinkedHashSet<Int>()
        val attempts = HashMap<Int, Int>()
        val host: String = NextChapterScanner.hostOf(url)
    }

    private class BatchItem(val slot: PageSlot, val bubbleId: Int, val text: String, val key: String)

    private val pages = LinkedHashMap<String, PageSlot>()
    private val nextPages = LinkedHashMap<String, PageSlot>()
    private val prefetching = HashSet<String>()
    private var readingIdx = 0
    private var pageLoaded = false
    private var replyProxy: JavaScriptReplyProxy? = null
    private var settings = AppSettings()
    private var chapter: ChapterInfo? = null
    private var series: SeriesEntity? = null
    private val glossary = mutableListOf<GlossaryEntry>()

    /** Últimas líneas en español del capítulo actual, en orden de lectura. */
    private val recentContext = ArrayDeque<String>()

    /** Ídem para el siguiente capítulo que se precarga (no contamina el contexto del actual). */
    private val nextContext = ArrayDeque<String>()

    /** Traducciones de esta serie ya conocidas en la sesión (clave de caché → español): evita reenviar repetidos. */
    private val memo = HashMap<String, String>()

    private var translationPaused = false
    private var batchLimit = BatchPlanner.MAX
    private var pendingSince = 0L
    private var transientFailures = 0

    private var nextUrl: String? = null
    private var nextInfo: ChapterInfo? = null
    private var nextRequested = false

    /** Sube en cada cambio de documento: descarta resultados asíncronos de un capítulo anterior. */
    private var generation = 0

    /** Líneas traducidas por la IA en este capítulo (no las que vinieron de caché), para el resumen. */
    private val freshLines = mutableListOf<String>()
    private val summarized = HashSet<String>()
    private var summaryJob: Job? = null

    private var scrollAccum = 0

    private val ocrWake = Channel<Unit>(Channel.CONFLATED)
    private val translateWake = Channel<Unit>(Channel.CONFLATED)

    /** Corta la espera de reintento cuando el usuario toca "Reintentar". */
    private val retryWake = Channel<Unit>(Channel.CONFLATED)

    /** Corta la espera de reintento cuando cambia el documento (otro capítulo merece su propio intento). */
    private val navWake = Channel<Unit>(Channel.CONFLATED)
    private var costJob: Job? = null
    private var seriesJob: Job? = null
    private var textCacheJob: Job? = null

    /** Contexto del capítulo precargado, para heredarlo si el usuario entra a ese capítulo. */
    private var carriedContext: Pair<String?, List<String>>? = null

    init {
        scope.launch {
            container.settings.settings.collect { s ->
                val old = settings
                settings = s
                _state.update { it.copy(needsApiKey = !s.hasApiKey) }
                if (old.debugBoxes != s.debugBoxes) sendMode()
                if (old.variant != s.variant) memo.clear()
                if (old.apiKey != s.apiKey || old.model != s.model) {
                    translationPaused = false
                    transientFailures = 0
                    _state.update { it.copy(message = null) }
                    translateWake.trySend(Unit)
                }
                if (old.prefetchAhead != s.prefetchAhead) ocrWake.trySend(Unit)
            }
        }
        scope.launch { ocrLoop() }
        scope.launch { translateLoop() }
    }

    fun close() {
        scope.cancel()
    }

    // ---------- Eventos del WebView ----------

    override fun onPageStarted(url: String) {
        scope.launch {
            pageUrl = url
            replyProxy = null
            pageLoaded = false
            resetPages()
            _state.update { it.copy(url = url, loadProgress = 0, barsVisible = true) }
        }
    }

    override fun onPageFinished(url: String) {
        scope.launch {
            pageLoaded = true
            _state.update { it.copy(loadProgress = 100) }
            maybePrefetchNextChapter()
        }
    }

    override fun onTitle(title: String) {
        scope.launch { _state.update { it.copy(title = title) } }
    }

    override fun onProgress(progress: Int) {
        scope.launch { _state.update { it.copy(loadProgress = progress) } }
    }

    override fun onImageCached(url: String) {
        scope.launch {
            val slot = pages[url] ?: nextPages[url] ?: return@launch
            if (slot.status == Status.FAILED) {
                slot.status = Status.QUEUED
                ocrWake.trySend(Unit)
            }
        }
    }

    override fun currentPageUrl(): String? = pageUrl

    /** Scroll del WebView (hilo principal): oculta las barras al bajar y las muestra al subir. */
    fun onScroll(dy: Int, scrollY: Int) {
        if (scrollY < BARS_TOP_PX) {
            scrollAccum = 0
            setBars(true)
            return
        }
        scrollAccum = if ((dy > 0) == (scrollAccum > 0)) scrollAccum + dy else dy
        if (scrollAccum > BARS_THRESHOLD_PX) setBars(false) else if (scrollAccum < -BARS_THRESHOLD_PX) setBars(true)
    }

    private fun setBars(visible: Boolean) {
        if (_state.value.barsVisible != visible) _state.update { it.copy(barsVisible = visible) }
    }

    /** Mensajes de reader.js. Llega en el hilo principal. */
    fun onJsMessage(data: String?, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (!isMainFrame || data == null) return
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (obj.str("type")) {
            "hello" -> onHello(obj, proxy)
            "images" -> onImages(obj)
            "pos" -> onPosition(obj)
            "next" -> onNextLink(obj)
        }
    }

    fun toggleOriginal() {
        _state.update { it.copy(showOriginal = !it.showOriginal) }
        sendMode()
    }

    /** Reintenta todo lo que quedó sin traducir (por ejemplo, tras un corte de red o recargar saldo). */
    fun retry() {
        translationPaused = false
        transientFailures = 0
        batchLimit = BatchPlanner.MAX
        _state.update { it.copy(message = null) }
        for (slot in pages.values + nextPages.values) {
            slot.attempts.clear()
            if (slot.status == Status.FAILED) slot.status = Status.QUEUED
            // Los globos descartados tras varios intentos vuelven a la cola.
            slot.analysis?.bubbles?.forEach { b -> if (b.id !in slot.translations) slot.pending += b.id }
        }
        ocrWake.trySend(Unit)
        translateWake.trySend(Unit)
        retryWake.trySend(Unit)
    }

    private fun onHello(obj: JsonObject, proxy: JavaScriptReplyProxy) {
        replyProxy = proxy
        val url = obj.str("url") ?: pageUrl ?: return
        val title = obj.str("title").orEmpty()
        val info = SeriesResolver.resolve(url, title)
        val changed = info.seriesKey != chapter?.seriesKey || info.chapter != chapter?.chapter
        // Navegación SPA (sin recarga): mismo documento, otro capítulo. Un cambio de URL que no cambia el capítulo
        // (replaceState al hacer scroll, quitar ?utm) no reinicia nada.
        if (changed && pages.isNotEmpty()) {
            resetPages()
            postToJs(buildJsonObject { put("type", "rescan") })
        }
        pageUrl = url
        _state.update { it.copy(url = url, title = title.ifBlank { it.title }) }
        sendMode()
        pages.values.filter { it.status == Status.READY }.forEach(::render)
        if (changed) scope.launch { enterChapter(info, url) }
    }

    private suspend fun enterChapter(info: ChapterInfo, url: String) {
        val sameSeries = info.seriesKey == chapter?.seriesKey
        chapter = info
        freshLines.clear()
        if (!sameSeries) {
            recentContext.clear()
            memo.clear()
        }
        val carried = carriedContext
        carriedContext = null
        if (sameSeries && carried != null && carried.first == info.chapter && carried.second.isNotEmpty()) {
            recentContext.clear()
            recentContext.addAll(carried.second)
        }
        val entity = withContext(Dispatchers.IO) {
            val existing = db.series().get(info.seriesKey)
            val e = (existing ?: SeriesEntity(key = info.seriesKey, title = info.seriesTitle))
                .copy(lastUrl = url, updatedAt = System.currentTimeMillis())
            db.series().upsert(e)
            e
        }
        if (chapter !== info) return
        applySeries(entity)
        _state.update { it.copy(chapter = info) }
        costJob?.cancel()
        costJob = scope.launch {
            db.usage().observeChapterCost(info.seriesKey, info.chapter).collect { cost ->
                _state.update { it.copy(chapterCostUsd = cost) }
            }
        }
        // El glosario y el resumen se editan en otra pantalla: la sesión siempre usa lo guardado.
        seriesJob?.cancel()
        seriesJob = scope.launch {
            db.series().observe(info.seriesKey).collect { e ->
                if (e != null && chapter?.seriesKey == info.seriesKey) applySeries(e)
            }
        }
        textCacheJob?.cancel()
        textCacheJob = scope.launch {
            var last = -1
            db.textCache().observeCount(info.seriesKey).collect { count ->
                if (count == 0 && last > 0 && chapter?.seriesKey == info.seriesKey) retranslateAll()
                last = count
            }
        }
        ocrWake.trySend(Unit)
        translateWake.trySend(Unit)
    }

    /** "Retraducir" en el glosario: se olvidan las traducciones de la IA y se piden de nuevo con el glosario actual. */
    private fun retranslateAll() {
        memo.clear()
        for (slot in pages.values + nextPages.values) {
            val analysis = slot.analysis ?: continue
            slot.attempts.clear()
            for (b in analysis.bubbles) {
                if (LocalDictionary.translate(b.text, settings.variant) != null) continue
                slot.translations.remove(b.id)
                slot.pending += b.id
            }
            if (!slot.next) render(slot)
        }
        Log.i(TAG, "Traducciones de la serie borradas: se vuelven a pedir con el glosario actual")
        refreshCounters()
        translateWake.trySend(Unit)
    }

    private fun applySeries(entity: SeriesEntity) {
        series = entity
        glossary.clear()
        glossary += decodeGlossary(entity.glossaryJson)
    }

    private fun decodeGlossary(raw: String): List<GlossaryEntry> =
        runCatching { json.decodeFromString(glossarySerializer, raw) }.getOrDefault(emptyList())

    private fun onImages(obj: JsonObject) {
        val items = obj["items"]?.jsonArray ?: return
        for (item in items) {
            val o = item.jsonObject
            val url = o.str("url") ?: continue
            if (!url.startsWith("http")) continue
            val idx = o["idx"]?.jsonPrimitive?.intOrNull ?: continue
            val nw = o["nw"]?.jsonPrimitive?.intOrNull ?: 0
            val nh = o["nh"]?.jsonPrimitive?.intOrNull ?: 0
            val slot = pages.getOrPut(url) { PageSlot(url, idx) }
            slot.idx = idx
            if (slot.status == Status.QUEUED && (nw in 1 until MIN_PAGE_WIDTH || nh in 1 until MIN_PAGE_HEIGHT)) {
                slot.status = Status.NOT_PAGE
            }
        }
        refreshCounters()
        ocrWake.trySend(Unit)
    }

    private fun onPosition(obj: JsonObject) {
        // Se ubica la página por su URL: los índices del DOM cambian si el sitio inserta imágenes más arriba.
        val idx = obj.str("url")?.let { pages[it]?.idx } ?: obj["idx"]?.jsonPrimitive?.intOrNull ?: return
        if (idx == readingIdx) return
        readingIdx = idx
        refreshCounters()
        maybePrefetchNextChapter()
        ocrWake.trySend(Unit)
        translateWake.trySend(Unit)
    }

    private fun onNextLink(obj: JsonObject) {
        val url = obj.str("url")?.takeIf { it.startsWith("http") } ?: return
        if (url == nextUrl) return
        nextUrl = url
        maybePrefetchNextChapter()
    }

    private fun resetPages() {
        generation++
        navWake.trySend(Unit)
        carriedContext = if (nextContext.isNotEmpty()) nextInfo?.chapter to nextContext.toList() else null
        pages.clear()
        nextPages.clear()
        prefetching.clear()
        nextContext.clear()
        readingIdx = 0
        pendingSince = 0L
        nextUrl = null
        nextInfo = null
        nextRequested = false
        _state.update { it.copy(pagesTotal = 0, pagesDone = 0, currentPage = 0, nextReady = false) }
    }

    // ---------- Selección por cercanía a la lectura ----------

    /** Host que sirve la mayoría de las páginas; las imágenes de otros hosts (anuncios) se ignoran. */
    private fun dominantHost(): String? {
        val counts = pages.values.filter { it.status != Status.NOT_PAGE }.groupingBy { it.host }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return null
        return if (best.value >= 3) best.key else null
    }

    private fun eligibleSlots(): List<PageSlot> {
        val host = dominantHost()
        return pages.values
            .filter { it.status != Status.NOT_PAGE && (host == null || it.host == host) }
            .sortedBy { it.idx }
    }

    private fun readingPosition(ordered: List<PageSlot>): Int =
        ordered.indexOfFirst { it.idx >= readingIdx }.let { if (it < 0) ordered.lastIndex else it }

    /** Páginas en orden de prioridad: la actual, las siguientes y luego 2 anteriores. */
    private fun windowSlots(): List<PageSlot> {
        val ordered = eligibleSlots()
        if (ordered.isEmpty()) return emptyList()
        val pos = readingPosition(ordered)
        val ahead = ordered.subList(pos, minOf(ordered.size, pos + settings.prefetchAhead + 1))
        val behind = ordered.subList(maxOf(0, pos - 2), pos).reversed()
        return ahead + behind
    }

    /** Las páginas en pantalla: lo suyo no espera a juntar un lote grande. */
    private fun visibleSlots(): Set<PageSlot> {
        val ordered = eligibleSlots()
        if (ordered.isEmpty()) return emptySet()
        val pos = readingPosition(ordered)
        return ordered.subList(pos, minOf(ordered.size, pos + VISIBLE_PAGES)).toSet()
    }

    private fun ocrBusy(): Boolean =
        (windowSlots() + nextPages.values).any { it.status == Status.QUEUED || it.status == Status.WORKING }

    private fun refreshCounters() {
        val ordered = eligibleSlots()
        val pos = ordered.indexOfFirst { it.idx >= readingIdx }
        val nextReady = nextPages.isNotEmpty() &&
            nextPages.values.all { it.status != Status.QUEUED && it.status != Status.WORKING && it.pending.isEmpty() }
        _state.update {
            it.copy(
                pagesTotal = ordered.size,
                pagesDone = ordered.count { s -> s.status == Status.READY && s.pending.isEmpty() },
                currentPage = if (ordered.isEmpty()) 0 else (if (pos < 0) ordered.size else pos + 1),
                nextReady = nextReady,
            )
        }
    }

    private fun chapterLabel(slot: PageSlot): String? = if (slot.next) nextInfo?.chapter else chapter?.chapter

    /** Clave de la caché de traducciones: el texto normalizado y, si no es la variante por defecto, la variante. */
    private fun cacheKey(text: String): String =
        if (settings.variant == SpanishVariant.LATINO) TextFilter.hashKey(text)
        else TextFilter.hashKey("${settings.variant.name}|$text")

    // ---------- Siguiente capítulo ----------

    /** Al acercarse al final, descarga, hace OCR y traduce las primeras páginas del siguiente capítulo. */
    private fun maybePrefetchNextChapter() {
        val url = nextUrl ?: return
        if (nextRequested || chapter == null || !pageLoaded) return
        val ordered = eligibleSlots()
        // Con pocas páginas conocidas todavía no se sabe dónde está el final (pero un capítulo corto también cuenta).
        if (ordered.size < minOf(settings.prefetchAhead + 3, MIN_PAGES_FOR_NEXT)) return
        if (readingPosition(ordered) < ordered.size - settings.prefetchAhead - 1) return
        nextRequested = true
        scope.launch { fetchNextChapter(url) }
    }

    private suspend fun fetchNextChapter(url: String) {
        val current = chapter ?: return
        val gen = generation
        val info = SeriesResolver.resolve(url, null)
        if (info.seriesKey != current.seriesKey || info.chapter == current.chapter) return
        val referer = pageUrl
        val ua = userAgent
        val html = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .apply { referer?.let { header("Referer", it) } }
                    .apply { CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) } }
                    .build()
                container.http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) null else r.body.string().take(MAX_HTML_CHARS)
                }
            }.getOrNull()
        } ?: return
        if (chapter !== current || generation != gen) return // el usuario ya cambió de capítulo
        val picked = NextChapterScanner.pickPages(NextChapterScanner.extractImageUrls(html, url), dominantHost())
            .take(settings.prefetchAhead + 2)
        if (picked.isEmpty()) {
            Log.i(TAG, "Siguiente capítulo sin imágenes en el HTML (se arma con JS): no se precarga")
            return
        }
        nextInfo = info
        nextContext.clear()
        picked.forEachIndexed { i, u -> nextPages[u] = PageSlot(u, NEXT_IDX_BASE + i, next = true) }
        Log.i(TAG, "Precargando capítulo ${info.chapter}: ${picked.size} páginas")
        ocrWake.trySend(Unit)
    }

    // ---------- OCR ----------

    private suspend fun ocrLoop() {
        while (scope.isActive) {
            try {
                ocrStep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error en el ciclo de OCR", e)
                delay(LOOP_ERROR_DELAY_MS)
            }
        }
    }

    private suspend fun ocrStep() {
        if (chapter == null) {
            ocrWake.receive()
            return
        }
        val window = windowSlots()
        val current = window.firstOrNull { it.status == Status.QUEUED }
        val slot = current ?: nextPages.values.firstOrNull { it.status == Status.QUEUED }
        if (slot == null) {
            ocrWake.receive()
            return
        }
        prefetchAround(if (current != null) window else nextPages.values.toList(), slot)
        slot.status = Status.WORKING
        val analysis = try {
            analyze(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OCR falló en ${slot.url}", e)
            null.also { slot.status = Status.FAILED }
        }
        if (pages[slot.url] !== slot && nextPages[slot.url] !== slot) return // cambió el capítulo
        when {
            analysis != null -> onAnalyzed(slot, analysis)
            slot.status == Status.WORKING -> slot.status = Status.NOT_PAGE
        }
        refreshCounters()
        maybePrefetchNextChapter()
    }

    /** Descarga en paralelo las 2 páginas siguientes mientras se hace el OCR de la actual. */
    private fun prefetchAround(list: List<PageSlot>, current: PageSlot) {
        val next = list.filter { it.status == Status.QUEUED && it !== current }.take(2)
        for (s in next) {
            if (!prefetching.add(s.url)) continue
            val referer = pageUrl
            scope.launch(Dispatchers.IO) {
                if (container.imageCache.get(s.url) == null) interceptor.fetchToCache(s.url, referer)
            }
        }
    }

    private suspend fun analyze(slot: PageSlot): PageAnalysis? {
        val info = chapter ?: return null
        val sfx = settings.translateSfx
        withContext(Dispatchers.IO) { db.pageCache().get(slot.url) }?.let { cached ->
            runCatching { json.decodeFromString(PageAnalysis.serializer(), cached.analysisJson) }.getOrNull()
                ?.takeIf { it.version >= PageAnalysis.CURRENT_VERSION && it.sfx == sfx }
                ?.let { return it }
        }
        val file = container.imageCache.get(slot.url)
            ?: interceptor.fetchToCache(slot.url, pageUrl)
            ?: run {
                slot.status = Status.FAILED
                return null
            }
        val analysis = container.pageProcessor.process(slot.url, file, sfx)?.copy(sfx = sfx)
        if (analysis == null) {
            slot.status = Status.NOT_PAGE
            return null
        }
        val label = chapterLabel(slot)
        withContext(Dispatchers.IO) {
            db.pageCache().upsert(
                PageCacheEntity(
                    url = slot.url,
                    seriesKey = info.seriesKey,
                    chapter = label,
                    analysisJson = json.encodeToString(PageAnalysis.serializer(), analysis),
                )
            )
        }
        return analysis
    }

    private suspend fun onAnalyzed(slot: PageSlot, analysis: PageAnalysis) {
        val info = chapter ?: return
        val gen = generation
        slot.analysis = analysis
        slot.status = Status.READY
        val unresolved = mutableListOf<Triple<Int, String, String>>()
        for (b in analysis.bubbles) {
            val local = LocalDictionary.translate(b.text, settings.variant)
            val key = cacheKey(b.text)
            when {
                local != null -> slot.translations[b.id] = local
                memo[key] != null -> slot.translations[b.id] = memo.getValue(key)
                else -> unresolved += Triple(b.id, b.text, key)
            }
        }
        if (unresolved.isNotEmpty()) {
            val keys = unresolved.map { it.third }.distinct()
            val cached = withContext(Dispatchers.IO) { db.textCache().getMany(info.seriesKey, keys) }
            if (generation != gen) return
            val byKey = cached.associate { it.textHash to OutputText.clean(it.translated) }
            for ((id, _, key) in unresolved) {
                val hit = byKey[key]
                if (hit != null) {
                    slot.translations[id] = hit
                    memo[key] = hit
                } else {
                    slot.pending += id
                }
            }
        }
        if (!slot.next) render(slot)
        if (slot.pending.isNotEmpty()) translateWake.trySend(Unit)
    }

    // ---------- Traducción ----------

    private suspend fun translateLoop() {
        while (scope.isActive) {
            try {
                translateStep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error en el ciclo de traducción", e)
                delay(LOOP_ERROR_DELAY_MS)
            }
        }
    }

    private suspend fun translateStep() {
        val info = chapter
        if (info == null || translationPaused) {
            _state.update { it.copy(translating = false) }
            translateWake.receive()
            return
        }
        resolvePendingFromMemo()
        val candidates = buildCandidates()
        if (candidates.isEmpty()) {
            pendingSince = 0L
            _state.update { it.copy(translating = false) }
            refreshCounters()
            maybeSummarize(info)
            translateWake.receive()
            return
        }
        if (!settings.hasApiKey) {
            _state.update { it.copy(translating = false, needsApiKey = true) }
            translateWake.receive()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (pendingSince == 0L) pendingSince = now
        when (val decision = BatchPlanner.plan(candidates, ocrBusy(), now - pendingSince, batchLimit)) {
            BatchPlanner.Decision.Idle -> translateWake.receive()
            is BatchPlanner.Decision.Wait -> withTimeoutOrNull(decision.millis) { translateWake.receive() }
            is BatchPlanner.Decision.Send -> {
                _state.update { it.copy(translating = true) }
                val sentAt = SystemClock.elapsedRealtime()
                translateGroups(info, decision.groups)
                // Lo que quedó pendiente ya esperó mientras duraba esta llamada.
                pendingSince = sentAt
                refreshCounters()
            }
        }
    }

    /** Pendientes cuyo texto ya se tradujo en la sesión (p. ej. llegó mientras su lote estaba en vuelo). */
    private fun resolvePendingFromMemo() {
        for (slot in windowSlots() + nextPages.values) {
            val analysis = slot.analysis ?: continue
            if (slot.pending.isEmpty()) continue
            var changed = false
            for (b in analysis.bubbles) {
                if (b.id !in slot.pending) continue
                val es = memo[cacheKey(b.text)] ?: continue
                slot.pending.remove(b.id)
                slot.translations[b.id] = es
                changed = true
            }
            if (changed && !slot.next) render(slot)
        }
    }

    /** Globos pendientes en orden de prioridad: el capítulo actual primero, el siguiente al final. */
    private fun buildCandidates(): List<BatchPlanner.Candidate<BatchItem>> {
        val out = mutableListOf<BatchPlanner.Candidate<BatchItem>>()
        val visible = visibleSlots()
        for (slot in windowSlots() + nextPages.values) {
            val analysis = slot.analysis ?: continue
            if (slot.pending.isEmpty()) continue
            val byId = analysis.bubbles.associateBy { it.id }
            for (id in slot.pending) {
                val b = byId[id] ?: continue
                val key = cacheKey(b.text)
                out += BatchPlanner.Candidate(
                    item = BatchItem(slot, id, b.text, key),
                    key = key,
                    urgent = slot in visible,
                    chapter = chapterLabel(slot),
                )
            }
        }
        return out
    }

    private suspend fun translateGroups(info: ChapterInfo, chosen: List<List<BatchItem>>) {
        val gen = generation
        // El modelo recibe los globos en orden de lectura (página, globo): así corrige el OCR y une frases partidas.
        val groups = chosen.sortedWith(
            compareBy<List<BatchItem>>({ g -> g.first().slot.next }, { g -> g.minOf { it.slot.idx } }, { g -> g.minOf { it.bubbleId } }),
        )
        val forNext = groups.first().first().slot.next
        val label = chapterLabel(groups.first().first().slot)
        val context = if (forNext) nextContext.ifEmpty { recentContext } else recentContext
        val lines = groups.mapIndexed { i, g -> SourceLine(i + 1, g.first().text) }
        val prompt = PromptBuilder.build(
            variant = settings.variant,
            glossary = glossary.toList(),
            summary = series?.summary.orEmpty(),
            context = context.toList().takeLast(BatchPlanner.contextLinesFor(lines.size)),
            lines = lines,
        )
        val params = params(PromptBuilder.maxOutputTokensFor(lines), info)
        val result = try {
            container.translator.translate(prompt, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslatorException) {
            onBatchFailed(info, label, groups, e, gen)
            return
        } catch (e: Exception) {
            // Los errores de red ya llegan como TranslatorException: esto es configuración o un bug, no se insiste.
            Log.e(TAG, "Fallo inesperado al traducir", e)
            onBatchFailed(info, label, groups, TranslatorException("Error inesperado: ${e.message ?: e.javaClass.simpleName}"), gen)
            return
        }
        transientFailures = 0
        batchLimit = BatchPlanner.MAX

        val stale = generation != gen || chapter !== info
        val cacheRows = mutableListOf<TextCacheEntity>()
        var bubbleCount = 0
        groups.forEachIndexed { i, group ->
            val es = result.translations[i + 1]
            val key = group.first().key
            if (es == null) {
                if (!stale) {
                    // El modelo omitió este id: se reintenta en otro lote, máximo 2 veces.
                    for (item in group) {
                        val n = (item.slot.attempts[item.bubbleId] ?: 0) + 1
                        item.slot.attempts[item.bubbleId] = n
                        if (n >= MAX_ATTEMPTS) item.slot.pending.remove(item.bubbleId)
                    }
                }
                return@forEachIndexed
            }
            bubbleCount += group.size
            cacheRows += TextCacheEntity(
                seriesKey = info.seriesKey,
                textHash = key,
                sourceText = group.first().text,
                translated = es,
                model = result.model,
            )
            // Aunque el documento haya cambiado, si es la misma serie la traducción sirve (p. ej. tras recargar).
            if (chapter?.seriesKey == info.seriesKey) memo[key] = es
            if (stale) return@forEachIndexed
            for (item in group) {
                item.slot.pending.remove(item.bubbleId)
                item.slot.translations[item.bubbleId] = es
            }
            if (es.isNotBlank()) {
                val target = if (forNext) nextContext else recentContext
                target.addLast(es)
                while (target.size > MAX_CONTEXT_LINES) target.removeFirst()
                if (!forNext) freshLines += es
            }
        }
        if (!stale) {
            groups.flatten().map { it.slot }.distinct().forEach { if (!it.next && pages[it.url] === it) render(it) }
            if (!forNext) _state.update { it.copy(message = null) }
        }

        val cost = Pricing.costUsd(result.model, result.usage)
        val newTerms = result.newGlossary
        val updated = withContext(Dispatchers.IO) {
            if (cacheRows.isNotEmpty()) db.textCache().insertAll(cacheRows)
            logUsage(info, label, result.model, bubbleCount, result.usage, result.latencyMs, cost)
            if (newTerms.isEmpty()) null else mergeGlossary(info, newTerms)
        }
        // La serie guardada manda (el observador también la aplica); solo si sigue siendo la misma serie.
        if (updated != null && chapter?.seriesKey == info.seriesKey) applySeries(updated)
        Log.i(
            TAG,
            "Lote ${groups.size} textos/$bubbleCount globos (cap. $label): in=${result.usage.inputTokens} " +
                "cached=${result.usage.cachedTokens} write=${result.usage.cacheWriteTokens} " +
                "out=${result.usage.outputTokens} ${result.latencyMs}ms costo=${"%.6f".format(cost)}" +
                if (stale) " (resultado tardío: solo se guardó en caché)" else "",
        )
    }

    private suspend fun onBatchFailed(
        info: ChapterInfo,
        label: String?,
        groups: List<List<BatchItem>>,
        e: TranslatorException,
        gen: Int,
    ) {
        // Si OpenAI cobró la respuesta fallida, igual se registra: el consumo mostrado debe cuadrar con el real.
        e.usage?.let { usage ->
            val model = e.model ?: settings.model
            val cost = Pricing.costUsd(model, usage)
            withContext(Dispatchers.IO) { logUsage(info, label, model, 0, usage, 0L, cost) }
        }
        val message = e.message ?: "Error al traducir"
        if (!e.retryable) {
            translationPaused = true
            _state.update { it.copy(message = message, translating = false) }
            return
        }
        if (e.badOutput) {
            // Salida cortada, JSON inválido o rechazo: cuenta como intento y se prueba con lotes más chicos.
            if (generation == gen) {
                for (item in groups.flatten()) {
                    val n = (item.slot.attempts[item.bubbleId] ?: 0) + 1
                    item.slot.attempts[item.bubbleId] = n
                    if (n > MAX_ATTEMPTS) item.slot.pending.remove(item.bubbleId)
                }
            }
            batchLimit = maxOf(BatchPlanner.MIN_AFTER_ERROR, batchLimit / 2)
            _state.update { it.copy(message = message, translating = false) }
            delay(RETRY_DELAY_MS)
            return
        }
        // Red, límite de velocidad o servidor caído: no se descarta nada, se espera cada vez más.
        val wait = minOf(MAX_BACKOFF_MS, RETRY_DELAY_MS shl minOf(transientFailures, 4))
        transientFailures++
        _state.update { it.copy(message = "$message · reintento en ${wait / 1000} s", translating = false) }
        retryWake.tryReceive()
        navWake.tryReceive()
        withTimeoutOrNull(wait) {
            select<Unit> {
                retryWake.onReceive { }
                navWake.onReceive { }
            }
        }
        _state.update { it.copy(message = null) }
    }

    /**
     * Al terminar casi todo el capítulo, actualiza el resumen de la serie (quién es quién, cómo habla cada uno).
     * Una llamada barata por capítulo, en segundo plano para no demorar ninguna traducción.
     */
    private fun maybeSummarize(info: ChapterInfo) {
        if (!settings.hasApiKey || freshLines.size < SUMMARY_MIN_LINES) return
        if (summaryJob?.isActive == true) return
        val key = info.seriesKey + "#" + (info.chapter ?: pageUrl.orEmpty())
        if (key in summarized) return
        val ordered = eligibleSlots()
        if (ordered.isEmpty()) return
        val done = ordered.count { it.status == Status.READY && it.pending.isEmpty() }
        if (done < ordered.size * SUMMARY_PROGRESS) return
        summarized += key
        val request = SummaryRequest(
            variant = settings.variant,
            seriesTitle = info.seriesTitle,
            currentSummary = series?.summary.orEmpty(),
            recentLines = freshLines.takeLast(SUMMARY_MAX_LINES),
        )
        freshLines.clear()
        val params = params(SUMMARY_MAX_TOKENS, info)
        summaryJob = scope.launch {
            val result = try {
                container.translator.summarize(request, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo actualizar el resumen", e)
                (e as? TranslatorException)?.usage?.let { usage ->
                    val model = e.model ?: params.model
                    withContext(Dispatchers.IO) { logUsage(info, info.chapter, model, 0, usage, 0L, Pricing.costUsd(model, usage)) }
                }
                return@launch
            }
            val cost = Pricing.costUsd(result.model, result.usage)
            val updated = withContext(Dispatchers.IO) {
                logUsage(info, info.chapter, result.model, 0, result.usage, result.latencyMs, cost)
                if (result.summary.isBlank()) null else updateSeries(info) { it.copy(summary = result.summary.trim()) }
            }
            if (updated != null && chapter?.seriesKey == info.seriesKey) applySeries(updated)
            Log.i(TAG, "Resumen actualizado (cap. ${info.chapter}): costo=${"%.6f".format(cost)}")
        }
    }

    private fun params(maxOutputTokens: Int, info: ChapterInfo) = TranslateParams(
        apiKey = settings.apiKey,
        model = settings.model,
        maxOutputTokens = maxOutputTokens,
        cacheKey = "tr-" + Integer.toHexString(info.seriesKey.hashCode()),
    )

    /**
     * Agrega términos nuevos al glosario GUARDADO (lo que el usuario haya editado se respeta) y al final de la
     * lista, para no romper el prefijo cacheado del prompt. Corre en IO y no toca el estado de la sesión.
     */
    private suspend fun mergeGlossary(info: ChapterInfo, terms: List<GlossaryEntry>): SeriesEntity? =
        updateSeries(info) { current ->
            val stored = decodeGlossary(current.glossaryJson)
            val fresh = terms
                .filter { t -> t.en.isNotBlank() && t.es.isNotBlank() && stored.none { it.en.equals(t.en, ignoreCase = true) } }
                .distinctBy { it.en.lowercase() }
            if (fresh.isEmpty()) current else current.copy(glossaryJson = json.encodeToString(glossarySerializer, stored + fresh))
        }

    /** Lectura-modificación-escritura de la serie en la base. Corre en IO. */
    private suspend fun updateSeries(info: ChapterInfo, change: (SeriesEntity) -> SeriesEntity): SeriesEntity? =
        container.seriesLock.withLock {
            val current = db.series().get(info.seriesKey) ?: return@withLock null
            val changed = change(current)
            if (changed == current) return@withLock current
            val updated = changed.copy(updatedAt = System.currentTimeMillis())
            db.series().upsert(updated)
            updated
        }

    private suspend fun logUsage(
        info: ChapterInfo,
        label: String?,
        model: String,
        bubbles: Int,
        usage: TokenUsage,
        latencyMs: Long,
        cost: Double,
    ) {
        db.usage().insert(
            UsageLogEntity(
                timestamp = System.currentTimeMillis(),
                seriesKey = info.seriesKey,
                chapter = label,
                model = model,
                bubbles = bubbles,
                inputTokens = usage.inputTokens,
                cachedTokens = usage.cachedTokens,
                cacheWriteTokens = usage.cacheWriteTokens,
                outputTokens = usage.outputTokens,
                reasoningTokens = usage.reasoningTokens,
                latencyMs = latencyMs,
                costUsd = cost,
            )
        )
    }

    // ---------- Puente hacia reader.js ----------

    private fun postToJs(msg: JsonObject) {
        val proxy = replyProxy ?: return
        runCatching { proxy.postMessage(msg.toString()) }
    }

    private fun sendMode() {
        postToJs(
            buildJsonObject {
                put("type", "mode")
                put("showOriginal", _state.value.showOriginal)
                put("debug", settings.debugBoxes)
            }
        )
    }

    private fun render(slot: PageSlot) {
        if (replyProxy == null) return
        val a = slot.analysis ?: return
        if (a.width <= 0 || a.height <= 0) return
        val w = a.width.toDouble()
        val h = a.height.toDouble()
        val msg = buildJsonObject {
            put("type", "render")
            put("url", slot.url)
            putJsonArray("bubbles") {
                for (b in a.bubbles) {
                    val es = slot.translations[b.id]
                    addJsonObject {
                        put("id", b.id)
                        put("x", b.left / w)
                        put("y", b.top / h)
                        put("w", (b.right - b.left) / w)
                        put("h", (b.bottom - b.top) / h)
                        put("lh", b.lineHeight / h)
                        put("bg", hex(b.bg))
                        put("fg", hex(b.fg))
                        put("uni", b.uniform)
                        put("en", b.text)
                        put("es", es.orEmpty())
                        put("st", if (!es.isNullOrBlank()) "ok" else if (es != null) "skip" else "pending")
                        put("conf", b.confidence.toDouble())
                    }
                }
            }
        }
        postToJs(msg)
    }

    private fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private const val TAG = "ReaderSession"
        private const val MIN_PAGE_WIDTH = 500
        private const val MIN_PAGE_HEIGHT = 300
        private const val VISIBLE_PAGES = 2
        private const val MAX_CONTEXT_LINES = 15
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 2500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val LOOP_ERROR_DELAY_MS = 1000L
        private const val NEXT_IDX_BASE = 100_000
        private const val MIN_PAGES_FOR_NEXT = 6
        private const val MAX_HTML_CHARS = 3_000_000
        private const val SUMMARY_MIN_LINES = 30
        private const val SUMMARY_MAX_LINES = 80
        private const val SUMMARY_PROGRESS = 0.8
        private const val SUMMARY_MAX_TOKENS = 400
        private const val BARS_TOP_PX = 48
        private const val BARS_THRESHOLD_PX = 60
    }
}
