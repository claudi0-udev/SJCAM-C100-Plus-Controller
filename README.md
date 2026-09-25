# SJCAM C100+ Android Controller & Debugger

Aplicación Android nativa en **Kotlin + Jetpack Compose** para el control inalámbrico de la cámara de acción **SJCAM C100+** (chipset Novatek NTK96675).

---

## Características Principales

1. **Previsualización en Directo Nativa (LibVLC)**:
   - Reproductor nativo de alto rendimiento **LibVLC** (`org.videolan.android:libvlc-all:3.6.4`), 100% compatible con el servidor **LIVE555 Streaming Media** y chipsets Novatek.
   - Buffer de ultrabaja latencia (150ms) y decodificación acelerada por hardware (`mediacodec`).
   - Sincronización ininterrumpida de previsualización durante grabación en la MicroSD.
   - Watchdog de red con reconexión automática ante microcortes o caídas de señal.
   - Heartbeat periódico HTTP (ping cada 4s) para evitar que la cámara apague el stream por inactividad.

2. **Control Completo de Modos y Disparo**:
   - Iniciar / Detener grabación de video en tarjeta MicroSD con indicador en vivo.
   - Captura de fotos en alta resolución con restauración suave del stream.
   - Monitor de estado de batería con telemetría en tiempo real.

3. **Panel de Ajustes y Configuración de Cámara**:
   - Lectura en tiempo real del volcado de parámetros Novatek (`cmd=3014`).
   - Resolución de Video (2K 30fps, 1080P 60/30fps, 720P 120/60fps).
   - Grabación en Bucle (Loop: Off, 1m, 3m, 5m).
   - WDR (Rango Dinámico Amplio).
   - Compensación de Exposición (EV desde -2.0 hasta +2.0).
   - Micrófono de audio integrado (activado/silenciado).
   - Marca de fecha y hora.
   - Resolución de Foto (15MP, 12MP, 10MP, 8MP, 5MP, 3MP).
   - Balance de Blancos (Auto, Luz de Día, Nublado, Tungsteno, Fluorescente).
   - Pitidos Beep de botones y Apagado Automático configurable.
   - Almacenamiento MicroSD: Gráfico de capacidad usada/libre y formateo seguro con diálogo de confirmación.

4. **Actualizaciones Automáticas In-App**:
   - Verificación de nuevas versiones directamente contra **GitHub Releases**.
   - Descarga del APK con barra de progreso y porcentaje.
   - Instalación segura nativa mediante `FileProvider` sin salir de la aplicación.
   - Enrutamiento inteligente por datos móviles/Internet cuando la cámara está enlazada por Wi-Fi.

5. **Consola Sandbox y Sistema de Logs**:
   - Pestaña de comandos para consultar y enviar códigos CGI HTTP arbitrarios.
   - Consola de logs en vivo con filtros de severidad y exportación al portapapeles.

---

## Cómo Probar la Aplicación

1. **Abrir en Android Studio**:
   - Abre la carpeta `/home/claudio/.gemini/antigravity/scratch/sjcam-controller` en Android Studio.
   - Deja que Gradle sincronice las dependencias.
   - Conecta tu teléfono Android por USB o ejecuta en tu dispositivo físico.
2. **Conexión a la SJCAM C100+**:
   - Enciende la cámara y activa su Wi-Fi (presionando el botón Wi-Fi de la cámara hasta que el LED parpadee).
   - Desde los ajustes de Wi-Fi de tu móvil, conéctate a la red de la cámara (ej: `C100+_...`, contraseña habitual: `12345678`).
   - Abre la aplicación **SJCAM C100+**.
3. **Uso y Pruebas**:
   - Ve a la pestaña **Cámara**: Presiona el botón de refrescar para verificar batería y conexión.
   - Presiona **Iniciar Previsualización** para conectar el stream de video.
   - Prueba los botones de **Grabar Video** o **Tomar Foto**.
4. **Depuración y Compartir Logs**:
   - Ve a la pestaña **Logs / Debug**.
   - Si algo no responde como esperabas o el stream no carga, presiona el botón **Copiar** o **Compartir** y pega el texto resultante en nuestro chat para analizar los códigos devueltos por el firmware.
