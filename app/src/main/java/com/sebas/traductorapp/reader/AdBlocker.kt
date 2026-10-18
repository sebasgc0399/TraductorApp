package com.sebas.traductorapp.reader

/**
 * Lista corta de hosts de anuncios, popunders y rastreadores habituales en sitios de scans.
 * Coincide por sufijo de dominio: "a.b.doubleclick.net" queda bloqueado por "doubleclick.net".
 */
object AdBlocker {
    // Nunca se bloquean (verificación de Cloudflare, recursos estáticos de Google).
    private val ALLOWED = setOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "gstatic.com",
        "recaptcha.net",
    )

    private val BLOCKED = hashSetOf(
        // Google Ads
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "googletagservices.com",
        "google-analytics.com",
        // Popunders y redirecciones
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "onclickads.net",
        "onclickalgo.com",
        "onclickperformance.com",
        "monetag.com",
        "adsterra.com",
        "adsterratools.com",
        "highperformanceformat.com",
        "profitablecpmrate.com",
        "hilltopads.net",
        "hilltopads.com",
        "clickadu.com",
        "galaksion.com",
        "adcash.com",
        "ad-maven.com",
        "popmyads.com",
        "richads.com",
        "evadav.com",
        "a-ads.com",
        "bidvertiser.com",
        "yllix.com",
        "clickaine.com",
        "adspyglass.com",
        // Redes para adultos / tráfico
        "exoclick.com",
        "exosrv.com",
        "realsrv.com",
        "magsrv.com",
        "juicyads.com",
        "jads.co",
        "trafficjunky.net",
        "trafficstars.com",
        "tsyndicate.com",
        // Nativos / recomendaciones
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "adskeeper.com",
        "revcontent.com",
        "zergnet.com",
        // Exchanges
        "adnxs.com",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "adform.net",
        "smartadserver.com",
        "casalemedia.com",
        "sharethrough.com",
        "adsrvr.org",
        "bidswitch.net",
        "teads.tv",
        // Rastreadores y notificaciones push
        "histats.com",
        "statcounter.com",
        "scorecardresearch.com",
        "quantserve.com",
        "dtscout.com",
        "dtscdn.com",
        "mc.yandex.ru",
        "onesignal.com",
        "notix.io",
        "pushengage.com",
        "izooto.com",
    )

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        var h = host.trim().trimEnd('.').lowercase()
        if (ALLOWED.any { h == it || h.endsWith(".$it") }) return false
        while (true) {
            if (h in BLOCKED) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndex) return false
            h = h.substring(dot + 1)
        }
    }
}
