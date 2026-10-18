package com.sebas.traductorapp.translate

/**
 * Decide cuándo y cuántos globos mandar en cada llamada a la IA.
 *
 * Cada llamada paga un costo fijo (prefijo en caché + contexto + esquema ≈ 400 tokens equivalentes) y cada globo
 * agrega ~20 tokens de salida (5× más caros). Por eso:
 * - lo que está en pantalla sale de inmediato, en un lote acotado para que llegue rápido;
 * - lo que se adelanta se acumula hasta un lote grande (o hasta que el OCR termine la ventana / pase un tiempo máximo);
 * - los textos repetidos se mandan una sola vez.
 * El esquema de salida es fijo (forma parte del prefijo cacheado), así que cualquier tamaño de lote sirve.
 */
object BatchPlanner {

    /** Un globo pendiente, en orden de prioridad (el primero es el más cercano a la lectura). */
    data class Candidate<T>(
        val item: T,
        /** Clave de deduplicación (hash del texto normalizado). */
        val key: String,
        /** Está en la página que se está viendo: no puede esperar. */
        val urgent: Boolean,
        /** Capítulo al que pertenece: un lote nunca mezcla capítulos (el consumo se registra por capítulo). */
        val chapter: String?,
    )

    sealed interface Decision<out T> {
        data object Idle : Decision<Nothing>

        data class Wait(val millis: Long) : Decision<Nothing>

        /** [groups]: un grupo por texto único, con todos los globos que comparten ese texto. */
        data class Send<T>(val groups: List<List<T>>, val urgent: Boolean) : Decision<T>
    }

    const val URGENT_MAX = 12
    const val TARGET = 24
    const val MAX = 40
    const val MIN_AFTER_ERROR = 5
    const val MAX_WAIT_MS = 2500L

    /**
     * @param candidates pendientes en orden de prioridad.
     * @param ocrBusy el OCR todavía tiene páginas de la ventana por procesar (llegarán más globos pronto).
     * @param waitedMs tiempo desde que hay pendientes no urgentes esperando.
     * @param limit tope actual (baja tras errores de salida cortada o JSON inválido).
     */
    fun <T> plan(candidates: List<Candidate<T>>, ocrBusy: Boolean, waitedMs: Long, limit: Int = MAX): Decision<T> {
        if (candidates.isEmpty()) return Decision.Idle
        val chapter = candidates.first().chapter
        val sameChapter = candidates.filter { it.chapter == chapter }
        val unique = group(sameChapter)
        val cap = limit.coerceIn(1, MAX)

        val urgent = unique.any { g -> g.any { it.urgent } }
        if (urgent) {
            // Lo urgente primero; se completa con lo que ya está listo, sin pasar del tope urgente.
            val ordered = unique.sortedByDescending { g -> g.any { it.urgent } }
            return Decision.Send(take(ordered, minOf(URGENT_MAX, cap)), urgent = true)
        }

        val enough = unique.size >= minOf(TARGET, cap)
        if (enough || !ocrBusy || waitedMs >= MAX_WAIT_MS) {
            return Decision.Send(take(unique, cap), urgent = false)
        }
        return Decision.Wait((MAX_WAIT_MS - waitedMs).coerceIn(50L, MAX_WAIT_MS))
    }

    /** Líneas de contexto según el tamaño del lote: un lote grande ya trae su propia continuidad. */
    fun contextLinesFor(batchSize: Int): Int = when {
        batchSize <= 5 -> 15
        batchSize <= 15 -> 10
        else -> 6
    }

    private fun <T> group(candidates: List<Candidate<T>>): List<List<Candidate<T>>> {
        val byKey = LinkedHashMap<String, MutableList<Candidate<T>>>()
        for (c in candidates) byKey.getOrPut(c.key) { mutableListOf() } += c
        return byKey.values.toList()
    }

    private fun <T> take(groups: List<List<Candidate<T>>>, cap: Int): List<List<T>> =
        groups.take(cap).map { g -> g.map { it.item } }
}
