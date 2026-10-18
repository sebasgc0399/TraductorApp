<p align="center">
  <img src="docs/logo.svg" width="120" alt="TraductorApp">
</p>

<h1 align="center">TraductorApp</h1>

<p align="center">
  Lector de manhwa y webtoons para Android que traduce los globos de diálogo <b>mientras lees</b>,
  dibujando la traducción encima del original. Usa <b>tu propia API key</b> de OpenAI: sin servidores ni suscripciones.
</p>

---

## Qué hace

- **Lector integrado.** Abres el capítulo dentro de la app (pegando el enlace o con *Compartir* desde Chrome) y el texto traducido aparece **sobre cada globo**. En los globos blancos se ve como impreso, y en los paneles con degradado lleva un parche con desenfoque.
- **Traduce por adelantado.** Mientras lees una página, las siguientes ya se están traduciendo. Al llegar al final del capítulo, también se precarga el siguiente.
- **Coherente.** Cada serie tiene un **glosario** de nombres y términos que la IA amplía sola y que puedes editar. También guarda un **resumen** corto de la serie (quién es quién y cómo habla cada personaje) y las líneas anteriores como contexto.
- **Corregible.** Mantén presionado un globo para ver el **original** o **editar** la traducción. Tu corrección queda guardada.
- **Barata.** El OCR se hace gratis en el teléfono (ML Kit) y a la IA solo se le envía el texto, nunca las imágenes. Con `gpt-6-luna` cuesta **~0.001 USD por capítulo**, así que 10 USD alcanzan para miles de capítulos, y releer cuesta 0.
- **Transparente.** La pantalla **Consumo** muestra los tokens y el costo por capítulo, y exporta a CSV.
- **Además:** biblioteca con favoritas y *Seguir leyendo*, modo inmersivo, tamaño de letra ajustable, español latino o de España.

## Descargar

1. Descarga el APK más reciente desde **[Releases](https://github.com/sebasgc0399/TraductorApp/releases)**. Requiere Android 12 o superior en un teléfono arm64 (cualquier teléfono moderno lo es).
2. Ábrelo en el teléfono y permite instalar apps de origen desconocido. Si tu Samsung muestra **Auto Blocker**, desactívalo en *Ajustes → Seguridad y privacidad*.
3. La primera vez, la app te guía para configurar tu API key.

### Tu API key de OpenAI

1. Crea una cuenta en <https://platform.openai.com> y carga saldo; con 5–10 USD sobra.
2. En **API keys**, crea una y pégala en la app (bienvenida o *Ajustes*).
3. La key se guarda **cifrada** en tu teléfono (Android Keystore) y solo se usa para llamar a OpenAI desde tu dispositivo.

## Privacidad

- No hay servidor propio: la app habla directo con OpenAI y con el sitio que estás leyendo.
- A OpenAI solo se le envía el **texto** de los globos (más el glosario y el resumen de la serie), nunca imágenes ni tu historial.
- Las cachés, el glosario y el registro de consumo viven solo en tu teléfono.

## Compilar tú mismo

Requisitos: Android Studio (trae el JDK 21) y el SDK de Android 37.

```bash
git clone https://github.com/sebasgc0399/TraductorApp.git
cd TraductorApp
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # pruebas unitarias
```

Para instalarla por USB, activa la *Depuración USB* en las opciones de desarrollador y ejecuta:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Instalar con `adb` evita el bloqueo de "ajustes restringidos" de Android 15/16.

**Release firmado (opcional):** crea un `keystore.properties` en la raíz con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`, y ejecuta `./gradlew assembleRelease`. Ese archivo está en `.gitignore`. Si no existe, el release se firma con la clave de depuración.

## Cómo se usa

| Acción | Cómo |
|---|---|
| Abrir un capítulo | Pegar el enlace en Inicio, o *Compartir → TraductorApp* desde el navegador |
| Ver el original de todo | Botón **ES/EN** |
| Ver el original de un globo o corregirlo | Mantener presionado el globo → *Original* / *Editar* |
| Nombres y términos | **Glosario** (desde el lector o la biblioteca); *Retraducir* vuelve a pedir las traducciones de la serie |
| Siguiente capítulo | Botón **Siguiente →** en la barra inferior |
| Gastos | **Consumo** (exportable a CSV) |

## Depuración

- En **Ajustes → Modo depuración** se dibujan las cajas del OCR con su confianza.
- Con el teléfono conectado, abre `chrome://inspect` en la PC para inspeccionar la página del lector.
- `adb logcat -s ReaderSession` muestra cada lote traducido con sus tokens (entrada, en caché, escritos en caché, salida), la latencia y el costo.

## Arquitectura

```
WebView ─▶ ImageInterceptor (OkHttp + cookies/Referer) ─▶ ImageDiskCache
reader.js reporta las <img> del capítulo ─▶ ReaderSession
  └─ PageProcessor: TiledOcr (ML Kit) → BubbleGrouper → TextFilter → BubbleShaper
  └─ BatchPlanner → PromptBuilder (instrucciones | glosario + resumen | contexto) → OpenAiTranslator
  └─ Room: series, page_cache, text_cache, usage_log
reader.js dibuja los globos traducidos sobre cada <img> (posiciones en %).
```

### Ahorro de tokens

Medido en un Galaxy S23 Ultra con `gpt-6-luna`: ~0.000011 USD por globo y ~0.001 USD por capítulo.

- **Lotes planificados** (`BatchPlanner`):
  - Lo que está en pantalla sale de inmediato (hasta 12 globos).
  - Lo que se adelanta se acumula hasta unos 24 globos, o hasta que el OCR termine la ventana, o hasta 2.5 s de espera.
  - Los textos repetidos se envían una sola vez.
  - El contexto previo se achica cuando el lote es grande.
- **Dos breakpoints de caché explícitos:**
  - El primero va tras las instrucciones, que ocupan ~1,380 tokens y no cambian nunca.
  - El segundo va tras el resumen y el glosario. Cuando el glosario crece, solo se reescribe esa parte.
- **Esquema de salida fijo** (`{"t":["1|…"]}`, unos 3 tokens extra por globo). OpenAI guarda el esquema dentro del prefijo en caché, así que un esquema variable rompería la caché.
- **Solo se envía texto:** la imagen nunca viaja; el OCR se hace gratis en el teléfono.
- **Filtros:** se descartan marcas de agua, créditos y onomatopeyas, y las interjecciones cortas se resuelven con un diccionario local.
- **Cachés:** por imagen, por análisis de OCR (con versión) y por texto (por serie y variante), más una memoria de sesión.
- **Consumo completo:** las respuestas cobradas que fallan también quedan registradas, así que el consumo mostrado cuadra con el panel de OpenAI.

## Uso responsable

TraductorApp es una herramienta personal de accesibilidad: **no aloja, copia ni distribuye** ningún cómic. Las imágenes se cargan desde el sitio que abres y las traducciones se guardan solo en tu teléfono.

Respeta los derechos de autor y las condiciones de cada sitio. Cuando una obra tenga **edición oficial en tu idioma, apóyala**.

## Licencia

[MIT](LICENSE). La fuente Comic Neue incluida tiene su propia licencia, la [SIL Open Font License 1.1](app/src/main/assets/fonts/OFL.txt).
