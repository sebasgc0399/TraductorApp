/*
 * TraductorApp: puente con Kotlin y overlay de traducciones.
 * Se inyecta al inicio de cada documento; solo actúa en el frame principal.
 */
(function () {
  'use strict';
  try {
    if (window.top !== window) return;
    if (window.__tr) return;
  } catch (e) {
    return;
  }

  var MIN_NW = 500;
  var LAZY_ATTRS = ['data-src', 'data-lazy-src', 'data-original', 'data-url'];
  var WATCH_ATTRS = ['src', 'srcset', 'href'].concat(LAZY_ATTRS);
  var PLACEHOLDER_RE = /(placeholder|lazy|loading|blank|transparen|spacer|pixel|1x1)/i;
  var COLOR_RE = /^#[0-9a-f]{3,8}$/i;
  var FONT = "'TrComic','Comic Neue','Comic Sans MS',system-ui,sans-serif";
  // La app sirve esta fuente desde sus assets (ver ReaderWebViewClient).
  var FONT_URL = 'https://appassets.androidplatform.net/fonts/ComicNeue-Bold.ttf';
  // Los globos se agrandan una vez si el texto no cabe con una letra legible.
  var GROW_BELOW_PX = 10;
  var MAX_RENDERS = 400;
  // Mantener pulsado un globo muestra el original.
  var PRESS_MS = 450;
  var PRESS_SLOP = 10;
  // Globos con altura de línea original parecida (±25 %) usan la misma letra.
  var SIMILAR_LH = 1.25;

  // Enlace al capítulo siguiente.
  var NEXT_TEXT_RE = /\b(next|siguiente|pr[oó]ximo)\b|›|»|→/i;
  var NEXT_CLASS_RE = /(^|[\s_-])next/i;
  var CHAP_URL_RE = /chap|cap[ií]tulo|episod|(^|[^a-z])(ch|ep)[-_]|\/\d+/i;
  var CHAP_NUM_RE = /(?:^|[^a-z])(?:chapter|chap|ch|episode|episodio|ep|cap[ií]tulo|cap)[-_.\/ ]*(\d+)/i;
  var CHAP_Q_RE = /[?&](?:episode_no|episode|chapter_no|chapter|ch|ep|no)=(\d+)/i;
  // Contenedores cuyos enlaces no cuentan: comentarios, anuncios y ventanas emergentes.
  var BAD_BOX_RE = /comment|disqus|popup|pop-up|adsbygoogle|advert|sponsor|(^|[\s_-])ads?(?=$|[\s_-])|(^|[\s_])ad-/i;
  var REL_NEXT_RE = /(^|\s)next(\s|$)/i;

  // Referencias tomadas antes de que corran los scripts del sitio.
  var stringify = JSON.stringify;
  var parseJson = JSON.parse;

  var S = {
    bridge: null,
    queue: [],
    waitTimer: 0,
    waitTries: 0,
    pageKey: '',
    lastTitle: null,
    sent: new Map(), // url -> nw ya enviado
    candidates: [], // [{img, url, idx}] del último escaneo
    lastPos: '',
    renders: new Map(), // url (sin #) -> último payload render
    layers: new Set(),
    mode: { showOriginal: false, debug: false },
    scanTimer: 0,
    nextSent: '', // último enlace "siguiente" enviado...
    nextKey: '', // ...y la página a la que pertenece
    press: null // pulsación larga en curso
  };

  try {
    Object.defineProperty(window, '__tr', { value: { v: 1, state: S }, enumerable: false });
  } catch (e) {
    window.__tr = { v: 1 };
  }

  function safe(fn) {
    return function () {
      try {
        return fn.apply(this, arguments);
      } catch (e) {
        return undefined;
      }
    };
  }

  function throttle(fn, ms) {
    var timer = 0;
    var pending = false;
    var run = safe(fn);
    function tick() {
      timer = 0;
      if (pending) {
        pending = false;
        run();
        timer = setTimeout(tick, ms);
      }
    }
    return function () {
      if (timer) {
        pending = true;
        return;
      }
      run();
      timer = setTimeout(tick, ms);
    };
  }

  function css(el, props) {
    var s = el.style;
    for (var k in props) {
      if (Object.prototype.hasOwnProperty.call(props, k)) s.setProperty(k, props[k], 'important');
    }
  }

  function byNum(a, b) {
    return a - b;
  }

  // ---------- Puente ----------

  function getBridge() {
    if (S.bridge) return S.bridge;
    var b = window.TrBridge;
    if (b && typeof b.postMessage === 'function') {
      S.bridge = b;
      try {
        b.onmessage = safe(onMessage);
      } catch (e) {}
      return b;
    }
    return null;
  }

  function flush() {
    var b = getBridge();
    if (!b) return false;
    var q = S.queue;
    S.queue = [];
    for (var i = 0; i < q.length; i++) {
      try {
        b.postMessage(q[i]);
      } catch (e) {}
    }
    return true;
  }

  function waitBridge() {
    if (S.waitTimer || S.waitTries > 150) return;
    S.waitTimer = setTimeout(function () {
      S.waitTimer = 0;
      S.waitTries++;
      if (!flush()) waitBridge();
    }, 100);
  }

  function post(obj) {
    var s;
    try {
      s = stringify(obj);
    } catch (e) {
      return;
    }
    var b = getBridge();
    if (!b) {
      if (S.queue.length < 100) S.queue.push(s);
      waitBridge();
      return;
    }
    if (S.queue.length) flush();
    try {
      b.postMessage(s);
    } catch (e) {}
  }

  function onMessage(ev) {
    var d = ev && ev.data;
    if (typeof d !== 'string') return;
    var m = parseJson(d);
    if (!m || typeof m !== 'object') return;
    if (m.type === 'mode') applyMode(m);
    else if (m.type === 'render') applyRender(m);
    else if (m.type === 'rescan') {
      // Kotlin reinició el capítulo (navegación SPA detectada por el título): se reportan de nuevo las imágenes.
      S.sent.clear();
      S.lastPos = '';
      scheduleScan();
    }
  }

  // ---------- Página ----------

  function noHash(u) {
    return typeof u === 'string' ? u.split('#')[0] : '';
  }

  function checkHello(force) {
    var url = location.href;
    var key = noHash(url);
    var title = document.title || '';
    if (key !== S.pageKey) {
      var first = !S.pageKey;
      S.pageKey = key;
      S.lastTitle = title;
      S.nextSent = '';
      if (!first) {
        // navegación SPA: se reenvía la lista completa de imágenes
        S.sent.clear();
        S.lastPos = '';
        scheduleScan();
      }
      post({ type: 'hello', url: url, title: title });
      return;
    }
    if (force || title !== S.lastTitle) {
      S.lastTitle = title;
      post({ type: 'hello', url: url, title: title });
    }
  }

  // ---------- Capítulo siguiente ----------

  // Dominio registrable aproximado: "storage.sitio.org" y "www.sitio.org" son el mismo sitio.
  function siteOf(host) {
    var h = String(host || '').toLowerCase().replace(/^www\./, '');
    var p = h.split('.');
    if (p.length <= 2) return h;
    var n = p[p.length - 1].length === 2 && p[p.length - 2].length <= 3 ? 3 : 2;
    return p.slice(-n).join('.');
  }

  function decodePath(p) {
    try {
      return decodeURIComponent(p);
    } catch (e) {
      return p;
    }
  }

  // Número de capítulo de una URL, o -1 si no se reconoce.
  function chapNum(u) {
    var p = decodePath(u.pathname);
    var m = CHAP_NUM_RE.exec(p) || CHAP_Q_RE.exec(u.search) || /\/(\d+)\/?$/.exec(p);
    return m ? parseInt(m[1], 10) : -1;
  }

  // true si b es a con uno o más números incrementados en 1 (y todo lo demás igual).
  function numInc(a, b) {
    var ta = a.split(/(\d+)/);
    var tb = b.split(/(\d+)/);
    if (ta.length !== tb.length || ta.length < 3) return false;
    var inc = false;
    for (var i = 0; i < ta.length; i++) {
      if (i % 2 === 0) {
        if (ta[i] !== tb[i]) return false;
      } else {
        var x = parseInt(ta[i], 10);
        var y = parseInt(tb[i], 10);
        if (y === x + 1) inc = true;
        else if (y !== x) return false;
      }
    }
    return inc;
  }

  // chapter-24 → chapter-25, /24/ → /25/, episode_no=12 → 13 (con la ruta y la consulta, o solo la ruta).
  function isNextOf(cur, u) {
    if (u.hostname.replace(/^www\./, '') !== cur.hostname.replace(/^www\./, '')) return false;
    var pc = cur.pathname.replace(/\/+$/, '');
    var pu = u.pathname.replace(/\/+$/, '');
    return numInc(pc + cur.search, pu + u.search) || numInc(pc, pu);
  }

  function inBadBox(el) {
    var n = el;
    for (var d = 0; n && n.nodeType === 1 && d < 25; d++) {
      if (n === document.body || n === document.documentElement) break;
      if (n.nodeName === 'DIALOG') return true;
      var id = n.getAttribute('id');
      var cls = n.getAttribute('class');
      if ((id && BAD_BOX_RE.test(id)) || (cls && BAD_BOX_RE.test(cls))) return true;
      if (n.getAttribute('role') === 'dialog') return true;
      n = n.parentNode;
    }
    return false;
  }

  function nextSignal(a) {
    var t = a.textContent || '';
    if (t.length <= 60) {
      if (NEXT_TEXT_RE.test(t)) return true;
      if (!t.trim()) {
        var im = a.querySelector('img[alt]');
        if (im && NEXT_TEXT_RE.test(im.getAttribute('alt') || '')) return true;
      }
    }
    var l = a.getAttribute('aria-label');
    if (l && l.length <= 80 && NEXT_TEXT_RE.test(l)) return true;
    l = a.getAttribute('title');
    if (l && l.length <= 80 && NEXT_TEXT_RE.test(l)) return true;
    return NEXT_CLASS_RE.test((a.getAttribute('class') || '') + ' ' + (a.getAttribute('id') || ''));
  }

  // Solo lee atributos y texto: no fuerza layout.
  function findNext() {
    var cur;
    try {
      cur = new URL(location.href);
    } catch (e) {
      return null;
    }
    var curKey = noHash(cur.href);
    var curSite = siteOf(cur.hostname);
    function valid(href) {
      if (!href) return null;
      var u;
      try {
        u = new URL(href, document.baseURI);
      } catch (e) {
        return null;
      }
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
      if (siteOf(u.hostname) !== curSite) return null;
      if (noHash(u.href) === curKey) return null;
      return u;
    }

    var link = document.querySelector('link[rel~="next"][href]');
    if (link) {
      var lu = valid(link.getAttribute('href'));
      if (lu) return noHash(lu.href);
    }

    var curNum = chapNum(cur);
    var as = document.links;
    var relHit = null;
    var incHit = null;
    var cands = new Map(); // href -> {n, inc, first}
    for (var i = 0; i < as.length && i < 3000; i++) {
      var a = as[i];
      if (a.nodeName !== 'A' || isOwn(a)) continue;
      var raw = a.getAttribute('href');
      if (!raw || raw.charAt(0) === '#') continue;
      var u = valid(a.href);
      if (!u) continue;
      var isRel = REL_NEXT_RE.test(a.getAttribute('rel') || '');
      var sig = isRel || nextSignal(a);
      var inc = isNextOf(cur, u);
      if (!sig && !inc) continue;
      if (inBadBox(a)) continue;
      var h = noHash(u.href);
      if (isRel) {
        if (!relHit) relHit = h;
        continue;
      }
      if (sig && (inc || CHAP_URL_RE.test(u.pathname + u.search))) {
        // un "siguiente" que apunta a un capítulo anterior (o al mismo) no es el siguiente
        var n = chapNum(u);
        if (curNum >= 0 && n >= 0 && curNum < 10000 && n < 10000 && n <= curNum) continue;
        var c = cands.get(h);
        if (!c) {
          c = { n: 0, inc: inc, seg: sharedSegs(cur.pathname, u.pathname), first: i };
          cands.set(h, c);
        }
        c.n++;
      } else if (inc && !incHit) {
        incHit = h;
      }
    }
    if (relHit) return relHit;
    // preferencia: número +1 exacto, misma serie (más segmentos de ruta en común), más repeticiones, el primero
    var best = null;
    var bc = null;
    cands.forEach(function (c, h) {
      var d = !bc ? 1 : (c.inc ? 1 : 0) - (bc.inc ? 1 : 0) || c.seg - bc.seg || c.n - bc.n || bc.first - c.first;
      if (d > 0) {
        best = h;
        bc = c;
      }
    });
    return best || incHit;
  }

  function sharedSegs(a, b) {
    var pa = a.split('/');
    var pb = b.split('/');
    var n = 0;
    for (var i = 1; i < pa.length && i < pb.length - 1; i++) {
      if (!pa[i] || pa[i] !== pb[i]) break;
      n++;
    }
    return n;
  }

  function checkNext() {
    if (!document.documentElement) return;
    checkHello(false); // el "hello" de la página va siempre antes que su "next"
    var u = findNext();
    if (!u) return;
    if (u === S.nextSent && S.nextKey === S.pageKey) return;
    S.nextSent = u;
    S.nextKey = S.pageKey;
    post({ type: 'next', url: u });
  }

  var checkNextSoon = throttle(checkNext, 1000);

  // ---------- Imágenes ----------

  function absUrl(v) {
    try {
      var u = new URL(v, document.baseURI);
      if (u.protocol === 'http:' || u.protocol === 'https:') return u.href;
    } catch (e) {}
    return null;
  }

  function lazyUrl(img) {
    for (var i = 0; i < LAZY_ATTRS.length; i++) {
      var v = img.getAttribute(LAZY_ATTRS[i]);
      if (!v) continue;
      v = v.trim();
      if (!v || /^(data|blob):/i.test(v)) continue;
      var a = absUrl(v);
      if (a) return a;
    }
    return null;
  }

  function isEmptySrc(u) {
    if (!u || /^(data|blob|about|javascript):/i.test(u)) return true;
    // src="" se resuelve a la URL del documento
    return u === location.href || u === noHash(location.href) || u === document.baseURI;
  }

  function imgInfo(img) {
    var cur = img.currentSrc || img.src || '';
    var lz = lazyUrl(img);
    var loaded = img.complete && img.naturalWidth > 0;
    var url = cur;
    var fromLazy = false;
    if (isEmptySrc(cur)) {
      url = lz;
      fromLazy = true;
    } else if (lz && lz !== cur) {
      // el src actual es un placeholder (pequeño o con nombre típico) y la imagen real está en data-*
      if ((loaded && img.naturalWidth < MIN_NW) || (!loaded && PLACEHOLDER_RE.test(cur))) {
        url = lz;
        fromLazy = true;
      }
    }
    if (!url) return null;
    return { url: url, lz: lz, fromLazy: fromLazy, loaded: loaded && !fromLazy };
  }

  function urlOf(img) {
    var i = imgInfo(img);
    return i ? i.url : null;
  }

  function scan() {
    S.scanTimer = 0;
    checkHello(false);
    var imgs = document.images;
    var iw = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
    var items = [];
    var cands = [];
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      var inf = imgInfo(img);
      if (!inf) continue;
      var nw = inf.fromLazy ? 0 : img.naturalWidth || 0;
      var nh = inf.fromLazy ? 0 : img.naturalHeight || 0;
      var rw = img.offsetWidth || 0;
      var ok =
        nw >= MIN_NW ||
        (iw > 0 && rw >= iw * 0.6 && !(inf.loaded && nw > 0 && nw < 100)) ||
        (!!inf.lz && (rw === 0 || rw >= iw * 0.4));
      if (!ok) continue;
      cands.push({ img: img, url: inf.url, idx: i });
      if (S.sent.get(inf.url) !== nw) {
        S.sent.set(inf.url, nw);
        items.push({ url: inf.url, idx: i, nw: nw, nh: nh, lazy: !inf.loaded });
      }
    }
    S.candidates = cands;
    if (items.length) post({ type: 'images', items: items });
    reconcile();
    syncAll(false);
    checkPos();
    checkNextSoon();
  }

  function scheduleScan() {
    if (!S.scanTimer) S.scanTimer = setTimeout(safe(scan), 300);
  }

  function checkPos() {
    var c = S.candidates;
    var ih = window.innerHeight || 0;
    for (var i = 0; i < c.length; i++) {
      var img = c[i].img;
      if (!img.isConnected) continue;
      var r = img.getBoundingClientRect();
      if (r.height > 0 && r.bottom > 0 && r.top < ih) {
        var key = c[i].url + '|' + c[i].idx;
        if (key !== S.lastPos) {
          S.lastPos = key;
          post({ type: 'pos', url: c[i].url, idx: c[i].idx });
        }
        return;
      }
    }
  }

  // ---------- Overlay ----------

  var LAYER_CSS = {
    position: 'absolute',
    left: '0px',
    top: '0px',
    width: '0px',
    height: '0px',
    margin: '0',
    padding: '0',
    border: '0',
    'box-sizing': 'border-box',
    display: 'block',
    overflow: 'visible',
    background: 'transparent',
    'pointer-events': 'none',
    'z-index': '2147483000',
    transform: 'none',
    'min-width': '0',
    'min-height': '0',
    'max-width': 'none',
    'max-height': 'none',
    'float': 'none',
    opacity: '1',
    filter: 'none',
    direction: 'ltr',
    contain: 'layout style'
  };

  // 'all: initial' va primero: aísla los globos de los estilos del sitio (dirección rtl, fuentes, transformaciones).
  var BUBBLE_CSS = {
    all: 'initial',
    position: 'absolute',
    display: 'flex',
    'flex-direction': 'column',
    // 'safe' evita que el desborde quede oculto arriba/izquierda, así la medición del ajuste es fiable
    'justify-content': 'safe center',
    'align-items': 'stretch',
    overflow: 'hidden',
    'pointer-events': 'auto',
    'box-sizing': 'border-box',
    padding: '0',
    margin: '0',
    border: '0',
    'border-radius': '14px',
    direction: 'ltr',
    'unicode-bidi': 'isolate',
    'writing-mode': 'horizontal-tb',
    // Desenfoca lo que queda debajo: borra el inglés sin que el parche se note sobre fondos con degradado.
    'backdrop-filter': 'blur(7px)',
    '-webkit-backdrop-filter': 'blur(7px)',
    'mask-image': 'linear-gradient(to right, transparent 0, #000 6px, #000 calc(100% - 6px), transparent 100%),' +
      ' linear-gradient(to bottom, transparent 0, #000 5px, #000 calc(100% - 5px), transparent 100%)',
    'mask-composite': 'intersect',
    'user-select': 'none',
    '-webkit-user-select': 'none',
    '-webkit-touch-callout': 'none',
    '-webkit-tap-highlight-color': 'transparent',
    cursor: 'default',
    opacity: '1',
    transition: 'opacity 120ms ease-out'
  };

  // Globo de fondo liso: la caja ya es el interior del globo, así que se pinta opaco como si estuviera impreso.
  var UNI_CSS = {
    'backdrop-filter': 'none',
    '-webkit-backdrop-filter': 'none',
    'mask-image': 'linear-gradient(to right, transparent 0, #000 2px, #000 calc(100% - 2px), transparent 100%),' +
      ' linear-gradient(to bottom, transparent 0, #000 2px, #000 calc(100% - 2px), transparent 100%)',
    'border-radius': '40% / 50%',
    // las esquinas redondeadas no recortan letras: fuera del relleno sigue estando el fondo liso del globo
    overflow: 'visible'
  };

  var TEXT_CSS = {
    all: 'initial',
    display: 'block',
    width: '100%',
    'box-sizing': 'border-box',
    padding: '0 6%',
    margin: '0',
    'text-align': 'center',
    direction: 'ltr',
    'unicode-bidi': 'plaintext',
    'font-family': FONT,
    'font-weight': '700',
    'font-style': 'normal',
    'font-size': '12px',
    'line-height': '1.12',
    'letter-spacing': '0.01em',
    'text-transform': 'none',
    'text-indent': '0',
    'text-decoration': 'none',
    // En cómics no se parten palabras: si no caben, se achica la letra.
    hyphens: 'manual',
    '-webkit-hyphens': 'manual',
    'text-wrap': 'balance',
    'word-break': 'normal',
    'overflow-wrap': 'normal',
    // pre-line: respeta los saltos de las ventanas del sistema (un campo por línea)
    'white-space': 'pre-line',
    'pointer-events': 'none',
    'user-select': 'none',
    '-webkit-user-select': 'none',
    '-webkit-font-smoothing': 'antialiased'
  };

  var DEBUG_CSS = {
    position: 'absolute',
    display: 'block',
    'box-sizing': 'border-box',
    margin: '0',
    padding: '0',
    border: '0',
    outline: '1px solid #ff1744',
    'outline-offset': '0',
    background: 'transparent',
    'pointer-events': 'none',
    overflow: 'visible',
    color: '#ff1744',
    font: '600 8px/1.15 system-ui,sans-serif',
    'text-align': 'left',
    'text-shadow': '0 0 2px #fff,0 0 2px #fff',
    'white-space': 'normal',
    'overflow-wrap': 'anywhere',
    'word-break': 'normal'
  };

  var ro = null;
  try {
    ro = new ResizeObserver(
      safe(function (entries) {
        var list = [];
        for (var i = 0; i < entries.length; i++) {
          var l = entries[i].target.__trLayer;
          if (l && S.layers.has(l)) list.push(l);
        }
        if (list.length) syncLayers(list, false);
      })
    );
  } catch (e) {
    ro = null;
  }

  function pct(v) {
    v = +v;
    if (!isFinite(v)) v = 0;
    return (v * 100).toFixed(3) + '%';
  }

  function color(c, dflt) {
    return typeof c === 'string' && COLOR_RE.test(c) ? c : dflt;
  }

  function rgba(hex, a) {
    var h = hex.replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h.slice(0, 6), 16);
    if (!isFinite(n)) return hex;
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }

  function ensureFont() {
    if (S.fontDone) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    S.fontDone = true;
    try {
      var st = document.createElement('style');
      st.__trOwn = true;
      st.textContent = "@font-face{font-family:'TrComic';src:url('" + FONT_URL + "') format('truetype');" +
        'font-weight:100 900;font-display:swap}';
      root.appendChild(st);
    } catch (e) {}
    // Los primeros globos se ajustan con la fuente de respaldo: al llegar la de cómic se reajusta todo.
    try {
      if (document.fonts && typeof document.fonts.load === 'function') {
        document.fonts.load("700 16px 'TrComic'").then(
          safe(function (list) {
            if (list && list.length) syncAll(true);
          }),
          function () {}
        );
      }
    } catch (e) {}
  }

  // Posición de la cubierta: la caja más unos px de margen para el borde difuminado
  // (casi nada en globos lisos, cuya caja ya es el interior y no debe tapar el contorno).
  function placeBubble(el, g) {
    var mx = el.__trUni ? 1 : 4;
    var my = el.__trUni ? 1 : 3;
    css(el, {
      left: 'calc(' + pct(g.x) + ' - ' + mx + 'px)',
      top: 'calc(' + pct(g.y) + ' - ' + my + 'px)',
      width: 'calc(' + pct(g.w) + ' + ' + 2 * mx + 'px)',
      height: 'calc(' + pct(g.h) + ' + ' + 2 * my + 'px)'
    });
  }

  // Margen interior del texto en globos lisos, en px del globo (los % del padding siempre van sobre el ancho).
  // Aleja el texto de las esquinas redondeadas; si la letra queda muy chica se reduce (ver fitAll).
  function padText(el) {
    var t = el.__trText;
    var L = el.parentNode;
    if (!el.__trUni || !t || !L) return;
    var g = el.__trGeom;
    var fv = el.__trGrown ? 0.03 : 0.08;
    var fh = el.__trGrown ? 0.05 : 0.11;
    var pv = Math.round(g.h * (L.__trH || 0) * fv);
    var ph = Math.round(g.w * (L.__trW || 0) * fh);
    var key = pv + 'px ' + ph + 'px';
    if (el.__trPad === key) return;
    el.__trPad = key;
    t.style.setProperty('padding', key, 'important');
  }

  // Si la imagen está dentro de <picture> o de un enlace/span en línea, la capa va al lado de ese contenedor.
  function anchorOf(img) {
    var a = img;
    var p = img.parentNode;
    for (var k = 0; k < 2 && p && p.nodeType === 1 && p !== document.body; k++) {
      var tag = p.nodeName;
      var inlineWrap = false;
      if (tag === 'PICTURE') inlineWrap = true;
      else if (tag === 'A' || tag === 'SPAN') {
        try {
          inlineWrap = getComputedStyle(p).display === 'inline';
        } catch (e) {}
      }
      if (!inlineWrap) break;
      a = p;
      p = p.parentNode;
    }
    return a;
  }

  function ensurePositioned(parent) {
    try {
      if (getComputedStyle(parent).position === 'static') parent.style.position = 'relative';
    } catch (e) {}
  }

  function attach(img, payload) {
    var anchor = anchorOf(img);
    var parent = anchor.parentNode;
    if (!parent || parent.nodeType !== 1) return null;
    ensurePositioned(parent);
    var layer = document.createElement('div');
    layer.className = '__tr-layer';
    layer.__trOwn = true;
    layer.__trImg = img;
    layer.__trAnchor = anchor;
    layer.__trKey = noHash(payload.url);
    layer.__trFitW = -1;
    layer.__trFitH = -1;
    css(layer, LAYER_CSS);
    build(layer, payload);
    applyVisibility(layer);
    parent.insertBefore(layer, anchor.nextSibling);
    img.__trLayer = layer;
    S.layers.add(layer);
    if (ro) {
      try {
        ro.observe(img);
      } catch (e) {}
    }
    return layer;
  }

  function dropLayer(layer) {
    S.layers.delete(layer);
    var img = layer.__trImg;
    if (img && img.__trLayer === layer) {
      img.__trLayer = null;
      if (ro) {
        try {
          ro.unobserve(img);
        } catch (e) {}
      }
    }
    if (layer.parentNode) {
      try {
        layer.parentNode.removeChild(layer);
      } catch (e) {}
    }
  }

  // Quita capas huérfanas y vuelve a poner las que el sitio borró (p. ej. al re-renderizar en una SPA).
  function reconcile() {
    S.layers.forEach(function (layer) {
      var img = layer.__trImg;
      var anchor = layer.__trAnchor;
      if (
        !img ||
        !img.isConnected ||
        !anchor ||
        !anchor.isConnected ||
        (anchor !== img && !anchor.contains(img)) ||
        noHash(urlOf(img)) !== layer.__trKey
      ) {
        dropLayer(layer);
        return;
      }
      if (!layer.isConnected || layer.parentNode !== anchor.parentNode) {
        try {
          ensurePositioned(anchor.parentNode);
          anchor.parentNode.insertBefore(layer, anchor.nextSibling);
          layer.__trFitW = -1;
        } catch (e) {
          dropLayer(layer);
        }
      }
    });
    if (!S.renders.size) return;
    var imgs = document.images;
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      var l = img.__trLayer;
      if (l && S.layers.has(l)) continue;
      var p = S.renders.get(noHash(urlOf(img)));
      if (p) attach(img, p);
    }
  }

  function build(layer, p) {
    if (S.press && S.press.el.parentNode === layer) cancelPress();
    layer.textContent = '';
    var bs = (p && Array.isArray(p.bubbles) && p.bubbles) || [];
    var i;
    for (i = 0; i < bs.length; i++) {
      var b = bs[i];
      if (b && b.st === 'ok' && typeof b.es === 'string' && b.es.trim()) layer.appendChild(makeBubble(b));
    }
    if (S.mode.debug) {
      for (i = 0; i < bs.length; i++) {
        if (bs[i]) layer.appendChild(makeDebug(bs[i]));
      }
    }
    layer.__trFitW = -1;
    layer.__trFitH = -1;
  }

  function makeBubble(b) {
    ensureFont();
    var el = document.createElement('div');
    el.__trOwn = true;
    el.__trBubble = true;
    el.__trUni = b.uni === true;
    el.__trLh = +b.lh || 0;
    el.__trEs = String(b.es);
    el.__trEn = typeof b.en === 'string' ? b.en : '';
    el.__trShowEn = false;
    el.__trMax = 12;
    el.__trFit = 0; // tamaño ajustado del español (px), base de la normalización
    el.__trSize = 0; // tamaño aplicado (px)
    el.__trGrown = false;
    el.__trGeom = { x: +b.x || 0, y: +b.y || 0, w: +b.w || 0, h: +b.h || 0 };
    var bg = color(b.bg, '#ffffff');
    var fg = color(b.fg, '#111111');
    css(el, BUBBLE_CSS);
    if (el.__trUni) {
      css(el, UNI_CSS);
      // sombra del mismo color: suaviza también el borde de las esquinas redondeadas
      css(el, { background: bg, 'box-shadow': '0 0 2px 1px ' + bg });
    } else {
      css(el, { background: rgba(bg, 0.9) });
    }
    placeBubble(el, el.__trGeom);
    var t = document.createElement('div');
    t.__trOwn = true;
    css(t, TEXT_CSS);
    css(t, { color: fg, 'text-shadow': '0 0 2px ' + bg + ', 0 0 5px ' + bg });
    t.setAttribute('lang', 'es');
    t.textContent = el.__trEs;
    el.appendChild(t);
    el.__trText = t;
    bindPress(el);
    return el;
  }

  function makeDebug(b) {
    var d = document.createElement('div');
    d.__trOwn = true;
    css(d, DEBUG_CSS);
    css(d, { left: pct(b.x), top: pct(b.y), width: pct(b.w), height: pct(b.h) });
    var lab = document.createElement('b');
    lab.__trOwn = true;
    css(lab, { font: '700 8px/1.15 system-ui,sans-serif', color: '#ff1744' });
    var conf = typeof b.conf === 'number' && isFinite(b.conf) ? b.conf.toFixed(2) : '?';
    lab.textContent = (b.id != null ? b.id : '?') + ' ' + conf + (b.uni === true ? ' U' : '') +
      (b.st && b.st !== 'ok' ? ' ' + b.st : '');
    var en = document.createElement('span');
    en.__trOwn = true;
    en.textContent = ' ' + (typeof b.en === 'string' ? b.en : '');
    d.appendChild(lab);
    d.appendChild(en);
    return d;
  }

  // ---------- Pulsación larga: original en inglés ----------
  // Nunca se cancela pointerdown/touchstart: el scroll y los toques normales siguen como siempre.

  function cancelPress() {
    var p = S.press;
    S.press = null;
    if (p && p.timer) clearTimeout(p.timer);
  }

  function firePress() {
    var p = S.press;
    S.press = null;
    if (!p) return;
    if (p.timer) clearTimeout(p.timer);
    var el = p.el;
    if (!el || !el.isConnected || !el.__trEn) return;
    // el click que el navegador pueda mandar al soltar no debe llegar a la página
    el.__trSwallow = true;
    toggleBubble(el);
  }

  var onPressDown = safe(function (e) {
    if (e.isPrimary === false) {
      // segundo dedo (zoom): no es una pulsación larga
      cancelPress();
      return;
    }
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    var el = e.currentTarget;
    cancelPress();
    el.__trSwallow = false;
    if (!el.__trEn) return;
    S.press = { el: el, id: e.pointerId, x: e.clientX, y: e.clientY, timer: setTimeout(safe(firePress), PRESS_MS) };
  });

  var onPressMove = safe(function (e) {
    var p = S.press;
    if (!p || e.pointerId !== p.id) return;
    var dx = e.clientX - p.x;
    var dy = e.clientY - p.y;
    if (dx * dx + dy * dy > PRESS_SLOP * PRESS_SLOP) cancelPress();
  });

  var onPressEnd = safe(function (e) {
    var p = S.press;
    if (p && e.pointerId === p.id) cancelPress();
  });

  // En Android el menú contextual llega con la pulsación larga: se suprime y, si llega antes que el timer, dispara.
  var onBubbleMenu = safe(function (e) {
    e.preventDefault();
    var p = S.press;
    if (p && p.el === e.currentTarget) firePress();
  });

  var onBubbleClick = safe(function (e) {
    var el = e.currentTarget;
    e.preventDefault();
    e.stopPropagation();
    if (el.__trSwallow) {
      el.__trSwallow = false;
      return;
    }
    forwardClick(e);
  });

  function isOurs(n) {
    for (; n; n = n.parentNode) if (n.__trOwn) return true;
    return false;
  }

  // El globo tapa la imagen: un toque normal se reenvía a lo que hay debajo
  // (hay lectores que pasan de página o muestran su menú al tocar la imagen).
  function forwardClick(e) {
    if (!e.isTrusted || !document.elementsFromPoint) return;
    var list = document.elementsFromPoint(e.clientX, e.clientY);
    for (var i = 0; i < list.length; i++) {
      var t = list[i];
      if (isOurs(t)) continue;
      t.dispatchEvent(new MouseEvent('click', {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: e.clientX,
        clientY: e.clientY,
        screenX: e.screenX,
        screenY: e.screenY
      }));
      return;
    }
  }

  var onBubbleDrag = safe(function (e) {
    e.preventDefault();
  });

  function bindPress(el) {
    var pas = { passive: true };
    el.addEventListener('pointerdown', onPressDown, pas);
    el.addEventListener('pointermove', onPressMove, pas);
    el.addEventListener('pointerup', onPressEnd, pas);
    el.addEventListener('pointercancel', onPressEnd, pas);
    el.addEventListener('pointerleave', onPressEnd, pas);
    el.addEventListener('contextmenu', onBubbleMenu, false);
    el.addEventListener('click', onBubbleClick, true);
    el.addEventListener('dragstart', onBubbleDrag, false);
  }

  function toggleBubble(el) {
    el.__trShowEn = !el.__trShowEn;
    var t = el.__trText || el;
    t.textContent = el.__trShowEn ? el.__trEn : el.__trEs;
    t.setAttribute('lang', el.__trShowEn ? 'en' : 'es');
    fitAll([el]);
    if (el.parentNode && el.parentNode.__trOwn) normalizeLayers([el.parentNode]);
    // pequeño destello para confirmar el cambio
    css(el, { opacity: '0.45' });
    setTimeout(
      safe(function () {
        css(el, { opacity: '1' });
      }),
      120
    );
  }

  function applyVisibility(layer) {
    layer.style.setProperty('display', S.mode.showOriginal ? 'none' : 'block', 'important');
  }

  // Posición relativa al bloque contenedor de la capa (el padre de la imagen).
  function measure(layer) {
    var img = layer.__trImg;
    var op = layer.offsetParent;
    if (!op) return null;
    var w = img.offsetWidth || 0;
    var h = img.offsetHeight || 0;
    var x;
    var y;
    if (img.offsetParent === op) {
      x = img.offsetLeft;
      y = img.offsetTop;
    } else {
      var r = img.getBoundingClientRect();
      var pr = op.getBoundingClientRect();
      x = r.left - pr.left - op.clientLeft + op.scrollLeft;
      y = r.top - pr.top - op.clientTop + op.scrollTop;
    }
    return { x: Math.round(x * 100) / 100, y: Math.round(y * 100) / 100, w: w, h: h };
  }

  function syncLayers(list, force) {
    if (S.mode.showOriginal) return;
    var geo = [];
    var i;
    // primero todas las lecturas, luego las escrituras (un solo layout)
    for (i = 0; i < list.length; i++) {
      var layer = list[i];
      if (!layer.isConnected) continue;
      var g = measure(layer);
      if (g) geo.push([layer, g]);
    }
    var toFit = [];
    var refit = [];
    for (i = 0; i < geo.length; i++) {
      var L = geo[i][0];
      var G = geo[i][1];
      if (L.__trX !== G.x) {
        L.__trX = G.x;
        L.style.setProperty('left', G.x + 'px', 'important');
      }
      if (L.__trY !== G.y) {
        L.__trY = G.y;
        L.style.setProperty('top', G.y + 'px', 'important');
      }
      if (L.__trW !== G.w) {
        L.__trW = G.w;
        L.style.setProperty('width', G.w + 'px', 'important');
      }
      if (L.__trH !== G.h) {
        L.__trH = G.h;
        L.style.setProperty('height', G.h + 'px', 'important');
      }
      if (G.w > 0 && G.h > 0 && (force || L.__trFitW !== G.w || L.__trFitH !== G.h)) {
        L.__trFitW = G.w;
        L.__trFitH = G.h;
        refit.push(L);
        var kids = L.children;
        for (var k = 0; k < kids.length; k++) {
          var el = kids[k];
          if (!el.__trBubble) continue;
          var lh = el.__trLh > 0 ? el.__trLh * G.h : 14;
          el.__trMax = Math.min(40, Math.max(9, lh));
          // el ajuste anterior ya no vale; los globos en inglés quedan fuera de la normalización
          el.__trFit = 0;
          padText(el);
          toFit.push(el);
        }
      }
    }
    if (toFit.length) fitAll(toFit);
    if (refit.length) normalizeLayers(refit);
  }

  function syncAll(force) {
    if (!S.layers.size) return;
    syncLayers(Array.from(S.layers), force);
  }

  function fits(el) {
    var t = el.__trText || el;
    return t.offsetHeight <= el.clientHeight + 1 && t.scrollWidth <= t.clientWidth + 1;
  }

  function setFont(el, px) {
    (el.__trText || el).style.setProperty('font-size', px + 'px', 'important');
  }

  function wrapMode(el, mode) {
    (el.__trText || el).style.setProperty('overflow-wrap', mode, 'important');
  }

  // Agranda la cubierta (centrada, dentro de la imagen) cuando el español no cabe con letra legible.
  // Los globos lisos no crecen (su caja ya es todo el interior): solo pierden margen interior.
  function grow(el) {
    el.__trGrown = true;
    if (el.__trUni) {
      padText(el);
      return;
    }
    var g = el.__trGeom;
    var nw = Math.min(1, g.w * 1.3);
    var nh = Math.min(1, g.h * 1.45);
    var nx = Math.min(Math.max(0, g.x - (nw - g.w) / 2), 1 - nw);
    var ny = Math.min(Math.max(0, g.y - (nh - g.h) / 2), 1 - nh);
    placeBubble(el, { x: nx, y: ny, w: nw, h: nh });
  }

  // Búsqueda binaria del tamaño de letra, en paralelo para todos los globos (un layout por iteración).
  function fitAll(els) {
    var st = [];
    var i;
    var s;
    for (i = 0; i < els.length; i++) {
      var el = els[i];
      var max = el.__trMax > 0 ? el.__trMax : 12;
      // durante el ajuste no se parten palabras, para que una palabra larga obligue a achicar la letra
      wrapMode(el, 'normal');
      setFont(el, max);
      st.push({ el: el, lo: 6, hi: max, mid: max, done: false, size: max, shown: true });
    }
    for (i = 0; i < st.length; i++) {
      s = st[i];
      if (s.el.clientHeight === 0) {
        s.shown = false;
        s.lo = s.hi;
        s.done = true;
      } else if (fits(s.el)) {
        s.lo = s.hi;
        s.done = true;
      }
    }
    for (var it = 0; it < 8; it++) {
      var any = false;
      for (i = 0; i < st.length; i++) {
        s = st[i];
        if (s.done) continue;
        if (s.hi - s.lo <= 0.5) {
          s.done = true;
          continue;
        }
        s.mid = (s.lo + s.hi) / 2;
        setFont(s.el, s.mid);
        any = true;
      }
      if (!any) break;
      for (i = 0; i < st.length; i++) {
        s = st[i];
        if (s.done) continue;
        if (fits(s.el)) s.lo = s.mid;
        else s.hi = s.mid;
      }
    }
    // escrituras, luego lecturas, luego escrituras: un solo layout más para el cierre
    for (i = 0; i < st.length; i++) {
      s = st[i];
      s.size = Math.floor(s.lo * 2) / 2;
      setFont(s.el, s.size);
    }
    var toGrow = [];
    var toWrap = [];
    for (i = 0; i < st.length; i++) {
      s = st[i];
      if (!s.shown) continue;
      if (s.lo < GROW_BELOW_PX && s.el.__trGeom && !s.el.__trGrown) toGrow.push(s.el);
      else if (!fits(s.el)) toWrap.push(s.el);
    }
    for (i = 0; i < st.length; i++) {
      s = st[i];
      s.el.__trSize = s.size;
      if (!s.el.__trShowEn) s.el.__trFit = s.shown ? s.size : 0;
    }
    for (i = 0; i < toWrap.length; i++) wrapMode(toWrap[i], 'anywhere');
    if (toGrow.length) {
      for (i = 0; i < toGrow.length; i++) grow(toGrow[i]);
      fitAll(toGrow);
    }
  }

  // Letra pareja en la página: los globos de una capa con altura de línea original parecida (±25 %)
  // y el mismo tipo de fondo usan como máximo la mediana de sus tamaños ajustados.
  // Solo achica (nunca pasa de lo que cabe) y solo escribe: no hay lecturas de layout.
  function normalizeLayers(layers) {
    for (var li = 0; li < layers.length; li++) {
      var kids = layers[li].children;
      var items = [];
      var k;
      var el;
      // un globo mostrado en inglés sigue contando con su ajuste en español (si es vigente),
      // así alternarlo no cambia la letra de los demás
      for (k = 0; k < kids.length; k++) {
        el = kids[k];
        if (!el.__trBubble || !(el.__trFit > 0)) continue;
        el.__trTarget = el.__trFit;
        if (el.__trLh > 0) items.push(el);
      }
      items.sort(function (a, b) {
        return (a.__trUni ? 1 : 0) - (b.__trUni ? 1 : 0) || a.__trLh - b.__trLh;
      });
      var i = 0;
      while (i < items.length) {
        var j = i + 1;
        while (
          j < items.length &&
          items[j].__trUni === items[i].__trUni &&
          items[j].__trLh <= items[i].__trLh * SIMILAR_LH
        ) {
          j++;
        }
        if (j - i >= 2) {
          var sizes = [];
          var q;
          for (q = i; q < j; q++) sizes.push(items[q].__trFit);
          sizes.sort(byNum);
          var n = sizes.length;
          var med = n % 2 ? sizes[(n - 1) / 2] : (sizes[n / 2 - 1] + sizes[n / 2]) / 2;
          med = Math.floor(med * 2) / 2;
          for (q = i; q < j; q++) {
            if (items[q].__trFit > med) items[q].__trTarget = med;
          }
        }
        i = j;
      }
      for (k = 0; k < kids.length; k++) {
        el = kids[k];
        if (!el.__trBubble || el.__trShowEn || !(el.__trFit > 0)) continue;
        if (el.__trSize !== el.__trTarget) {
          el.__trSize = el.__trTarget;
          setFont(el, el.__trTarget);
        }
      }
    }
  }

  function applyMode(m) {
    var so = !!m.showOriginal;
    var dbg = !!m.debug;
    var dbgChanged = dbg !== S.mode.debug;
    var soChanged = so !== S.mode.showOriginal;
    S.mode.showOriginal = so;
    S.mode.debug = dbg;
    if (so) cancelPress();
    S.layers.forEach(function (layer) {
      if (dbgChanged) build(layer, S.renders.get(layer.__trKey));
      if (soChanged) applyVisibility(layer);
    });
    if (!so) syncAll(soChanged || dbgChanged);
  }

  function applyRender(m) {
    var key = noHash(typeof m.url === 'string' ? m.url : '');
    if (!key) return;
    var bubbles = Array.isArray(m.bubbles) ? m.bubbles : [];
    if (!bubbles.length) {
      S.renders.delete(key);
      S.layers.forEach(function (l) {
        if (l.__trKey === key) dropLayer(l);
      });
      return;
    }
    S.renders.delete(key);
    S.renders.set(key, m);
    if (S.renders.size > MAX_RENDERS) S.renders.delete(S.renders.keys().next().value);
    var touched = [];
    var imgs = document.images;
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      if (noHash(urlOf(img)) !== key) continue;
      var l = img.__trLayer;
      if (l && S.layers.has(l) && l.isConnected) {
        l.__trKey = key;
        build(l, m);
        applyVisibility(l);
      } else {
        if (l) dropLayer(l);
        l = attach(img, m);
      }
      if (l) touched.push(l);
    }
    if (touched.length) syncLayers(touched, true);
  }

  // ---------- Observadores ----------

  function isOwn(n) {
    return !!(n && n.__trOwn);
  }

  function allOwn(list) {
    for (var i = 0; i < list.length; i++) {
      if (!isOwn(list[i])) return false;
    }
    return true;
  }

  try {
    var mo = new MutationObserver(function (muts) {
      try {
        var hrefOnly = false;
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          if (isOwn(m.target)) continue;
          if (m.type === 'childList' && allOwn(m.addedNodes) && allOwn(m.removedNodes)) continue;
          if (m.type === 'attributes' && m.attributeName === 'href') {
            // un enlace cambió de destino (navegación de una SPA): solo afecta al "siguiente"
            hrefOnly = true;
            continue;
          }
          scheduleScan();
          return;
        }
        if (hrefOnly) checkNextSoon();
      } catch (e) {}
    });
    mo.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: WATCH_ATTRS });
  } catch (e) {}

  var onScroll = throttle(function () {
    syncAll(false);
    checkPos();
  }, 250);

  var onResize = throttle(function () {
    syncAll(false);
    checkPos();
  }, 200);

  try {
    document.addEventListener('scroll', onScroll, { capture: true, passive: true });
    window.addEventListener('resize', onResize, { passive: true });
    document.addEventListener(
      'load',
      function (e) {
        var t = e && e.target;
        if (t && t.nodeName === 'IMG') scheduleScan();
      },
      true
    );
    document.addEventListener('DOMContentLoaded', safe(scan), false);
    window.addEventListener('load', safe(scan), false);
    window.addEventListener('popstate', safe(scheduleScan), false);
    window.addEventListener(
      'pageshow',
      safe(function (e) {
        if (e && e.persisted) {
          // restaurada desde bfcache: Kotlin necesita el estado otra vez
          S.sent.clear();
          S.lastPos = '';
          S.nextSent = '';
          checkHello(true);
          scheduleScan();
        }
      }),
      false
    );
    setInterval(
      safe(function () {
        checkHello(false);
      }),
      2000
    );
  } catch (e) {}

  safe(function () {
    getBridge();
    checkHello(false);
    if (document.readyState !== 'loading') scheduleScan();
  })();
})();
