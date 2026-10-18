# TraductorApp

Lector de manhwa para Android (Galaxy S23) que traduce los globos de diálogo del inglés al español mientras lees.

- Abres el capítulo dentro de la app (navegador integrado), por ejemplo en Vortex Scans.
- El OCR corre en el teléfono (ML Kit, gratis) y a la IA solo se le envía el texto.
- La traducción usa OpenAI `gpt-6-luna`: unos 0.002 USD por capítulo.
- Hay un glosario por serie (nombres y términos) y contexto de las líneas anteriores, para que la traducción sea coherente.
- Las páginas siguientes se traducen por adelantado, y todo queda en caché: releer un capítulo cuesta 0 tokens.
- La pantalla **Consumo** muestra los tokens y el costo por capítulo, y exporta a CSV.

## Compilar

Requisitos: Android Studio (trae el JDK 21) y el SDK de Android 36.

```bash
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # pruebas unitarias
```

## Instalar en el S23

1. En el teléfono, entra a **Ajustes → Acerca del teléfono → Información de software** y toca 7 veces "Número de compilación" para activar las opciones de desarrollador.
2. En **Ajustes → Opciones de desarrollador**, activa **Depuración USB**. También puedes usar **Depuración inalámbrica** con `adb pair`.
3. Conecta el cable USB y acepta el aviso de "Permitir depuración USB".
4. Instala la app:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Instalar con `adb` evita el bloqueo de "ajustes restringidos" que Android 15/16 aplica a las APK abiertas desde el navegador. Si Samsung muestra "Auto Blocker", desactívalo en **Ajustes → Seguridad y privacidad → Auto Blocker**.

## Primer uso

1. Abre la app, entra a **Ajustes**, pega tu API key de OpenAI y pulsa *Guardar*. La key se guarda cifrada con Android Keystore.
2. En la pantalla principal, pega la URL de un capítulo y pulsa *Abrir*. Otra opción: desde Chrome, usa **Compartir → TraductorApp**.
3. Los globos aparecen en español a medida que se traducen:
   - El botón **ES/EN** alterna entre la traducción y el original.
   - Mantener presionado un globo muestra su texto original; otra pulsación larga vuelve al español. Un toque normal pasa a la página.
   - Las barras se ocultan al bajar (modo inmersivo) y vuelven al subir.
   - Al acercarte al final del capítulo, se descargan, se procesan con OCR y se traducen las primeras páginas del siguiente. La barra muestra "sig. capítulo listo ✓".
4. **Glosario** permite corregir nombres y términos de la serie. La IA agrega términos nuevos sola, y al terminar cada capítulo actualiza un resumen corto de la serie (personajes, tono). *Retraducir* vuelve a pedir las traducciones con el glosario actualizado.

## Depuración

- En **Ajustes → Modo depuración** se dibujan las cajas del OCR con su confianza.
- Con el teléfono conectado, abre `chrome://inspect` en la PC para inspeccionar la página del lector.
- `adb logcat -s ReaderSession` muestra cada lote traducido con sus tokens (entrada, en caché, escritos en caché, salida), la latencia y el costo.

## Arquitectura

```
WebView ─▶ ImageInterceptor (OkHttp + cookies/Referer) ─▶ ImageDiskCache
reader.js reporta las <img> del capítulo ─▶ ReaderSession
  └─ PageProcessor: TiledOcr (ML Kit) → BubbleGrouper → TextFilter → ColorSampler
  └─ PromptBuilder (prefijo estable + glosario + contexto) → OpenAiTranslator (Structured Outputs)
  └─ Room: page_cache, text_cache, usage_log
reader.js dibuja los globos en español sobre cada <img> (posiciones en %).
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
- **Cachés:** por imagen, por análisis de OCR (con versión) y por texto (por serie y variante), más una memoria de sesión. Releer un capítulo cuesta 0.
- **Consumo completo:** las respuestas cobradas que fallan también quedan registradas, así que el consumo mostrado cuadra con el panel de OpenAI.
