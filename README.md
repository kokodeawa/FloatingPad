# Mando Flotante

Mando táctil flotante para Android. Se dibuja sobre cualquier app y crea **dispositivos de entrada virtuales del sistema**
(un mando con ejes analógicos, un teclado y un ratón), de modo que juegos, emuladores y apps de streaming o escritorio
remoto los reconocen como hardware real.

> **Estado: beta (1.0.0).** Funciona en el dispositivo de desarrollo; no se ha verificado en una gama amplia de
> fabricantes ni de versiones de Android. Consulta [Límites conocidos](#límites-conocidos).

## Índice
1. [Qué hace](#qué-hace)
2. [Cómo funciona](#cómo-funciona)
3. [Requisitos](#requisitos)
4. [Compilar e instalar](#compilar-e-instalar)
5. [Primeros pasos](#primeros-pasos)
6. [Perfiles y controles](#perfiles-y-controles)
7. [Modos de pantalla](#modos-de-pantalla)
8. [Teclado, ratón y trackpad](#teclado-ratón-y-trackpad)
9. [Especificaciones técnicas](#especificaciones-técnicas)
10. [Arquitectura del código](#arquitectura-del-código)
11. [Límites conocidos](#límites-conocidos)
12. [Privacidad y seguridad](#privacidad-y-seguridad)
13. [Solución de problemas](#solución-de-problemas)
14. [Hoja de ruta](#hoja-de-ruta)
15. [Contribuir](#contribuir)
16. [Licencia y créditos](#licencia-y-créditos)

---

## Qué hace
- **Mando virtual**: botones, cruceta, dos sticks analógicos y gatillos, con vibración al pulsar.
- **Seis perfiles integrados** (tres horizontales y tres verticales) y perfiles propios guardables por orientación.
- **Editor en pantalla**: arrastrar, redimensionar, rejilla, añadir y eliminar controles, reasignar teclas.
- **Teclado y ratón virtuales**: teclas, joysticks de teclas (WASD, flechas, IJKL o personalizados), trackpad con
  aceleración y un **teclado en pantalla propio**.
- **No bloquea la pantalla**: en juego, el mando son ventanas pequeñas; el resto de la pantalla sigue siendo de la app
  de debajo. Un botón 🎮 pequeño y arrastrable da acceso a ocultar, ajustes, editar y salir.
- **Tres modos de pantalla**: normal, tocar la pantalla a través del mando y solo botones.
- **Conexión sin ordenador**: usa Shizuku o su propio cliente de depuración inalámbrica, con un emparejamiento guiado
  por notificación (escribes el código de 6 dígitos sin salir de los ajustes del sistema).
- Transparencia, tamaño global y tamaño por control; el mando se adapta al girar el móvil.

## Cómo funciona
```
 Toques ──► ventanas flotantes (GamepadView) ──► FloatingControllerService
                                                        │
                       ┌────────────────────────────────┴───────────────────────────┐
                       ▼                                                            ▼
          VirtualGamepadManager                                              KbmDevices
       (mando: botones, hat, ejes)                                  (teclado y ratón, proceso aparte)
                       │                                                            │
                       └──────────► "/system/bin/uinput -"  (comandos JSON por stdin) ◄┘
                                          ejecutado con privilegios de shell
                                    ┌──────────────┴───────────────┐
                                    ▼                              ▼
                                Shizuku                  ADB propio (libadb-android)
                                                       conectado al propio teléfono (127.0.0.1)
```
1. Una app normal no puede crear dispositivos de entrada. La herramienta `uinput` del sistema sí, pero solo se puede
   lanzar con privilegios de shell.
2. La app obtiene esos privilegios de **Shizuku**, o bien conectándose por **depuración inalámbrica** al ADB del propio
   teléfono (el puerto se descubre por mDNS; no hay que teclear IP ni puerto).
3. Abre `uinput -`, registra los dispositivos con un comando JSON y envía los eventos línea a línea.
4. Android ve un mando (y, cuando hace falta, un teclado y un ratón) conectados.

## Requisitos
**En el teléfono**
- Android 12 o superior (el binario `uinput` del sistema; la app lo comprueba y avisa si falta).
- Para el ADB propio: Android 11 o superior, **Opciones de desarrollador** y **Depuración inalámbrica** activadas y
  Wi-Fi conectada (Android exige Wi-Fi para la depuración inalámbrica). Alternativa: Shizuku en ejecución.
- Permiso «Mostrar sobre otras apps» y notificaciones permitidas.

**Para compilar**
- JDK 8 (probado). Gradle 6.7.1 y Android Gradle Plugin 4.2.2 (el wrapper los descarga).
- Android SDK con la plataforma 28 y, por defecto, build-tools 30.0.2.
- Acceso a Google Maven, Maven Central, JCenter y JitPack.

## Compilar e instalar
```bash
git clone <url-del-repositorio>
cd FloatingPad
./gradlew assembleDebug          # en Windows: gradlew.bat assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

Ajustes útiles en `gradle.properties`:
| Propiedad | Para qué sirve |
|---|---|
| `pad.compileSdk` | Plataforma del SDK usada para compilar (por defecto 28). |
| `pad.buildTools` | Fija unas build-tools concretas (opcional). |

Define `ANDROID_HOME` o crea `local.properties` con `sdk.dir=/ruta/al/sdk`.
Si compilas directamente en un dispositivo Android, puede hacer falta apuntar AGP a un `aapt2` nativo
(`android.aapt2FromMavenOverride`) en tu `~/.gradle/gradle.properties`; no se incluye en el repositorio por ser
específico de cada entorno.

## Primeros pasos
La pantalla principal tiene un **botón único que te guía** y cuyo texto indica el siguiente paso; arriba se muestra el
estado (✔/✖) de superposición, emparejamiento y mando virtual.
1. **«1. Permitir mostrar sobre otras apps»**: concede el permiso y vuelve a la app. Permite también las notificaciones.
2. **«2. Emparejar (solo la primera vez)»** *(ADB propio)*: se abren las Opciones de desarrollador. Activa
   «Depuración inalámbrica» y toca **«Vincular dispositivo con código de vinculación»**. Con ese cuadro abierto, baja las
   notificaciones, toca «Escribir código» y teclea los 6 dígitos. Si no se detecta el puerto, escribe el código y el
   puerto separados por un espacio (por ejemplo `123456 41234`). Con **Shizuku** en ejecución y con permiso concedido
   se salta el emparejamiento (menú «Más opciones» → «Usar Shizuku»).
3. **«2. Conectar mando»**: crea el mando virtual (aparece ✔ Mando virtual).
4. **«3. Iniciar mando flotante»**: arranca el overlay. Abre tu juego o app de streaming y toca 🎮 para ocultar, ajustar,
   editar o salir.

En «Más opciones»: probar el mando (envía el botón A), emparejar de nuevo, reiniciar la conexión, olvidar el
emparejamiento, diagnóstico, registros detallados, opciones de desarrollador, ajustes de notificaciones y Shizuku.
**Salida de emergencia**: la notificación del servicio tiene «Mostrar / ocultar» y «Cerrar», y la app tiene «Detener».

## Perfiles y controles
Cada orientación recuerda su perfil activo. Al girar el móvil se carga el de la nueva orientación.

| Perfil | Disposición |
|---|---|
| **Asimétrico** | Stick izquierdo arriba y cruceta abajo; botones de acción arriba y stick derecho abajo. |
| **Simétrico** | Cruceta arriba-izquierda, botones de acción arriba-derecha, sticks abajo al centro, L3/R3 y HOME. |
| **Teclado** | Joystick WASD, teclas de acción, clics de ratón, trackpad y botón de teclado en pantalla. |
| **…vertical** | Los tres anteriores con todo en la mitad inferior de la pantalla (manos abajo, como en una consola portátil). |

**Editor** (menú 🎮 → Editar): arrastra controles; tamaño por control; rejilla; **＋ Añadir** (tecla, botón de mando,
joystick WASD/flechas/IJKL/personalizado, trackpad, botón de teclado); **Asignar**; **Eliminar**; **Restablecer**;
**Guardar**. Los controles no pueden solaparse ni salirse de la pantalla; el tamaño global se limita al máximo sin
colisiones. Los perfiles se guardan como JSON en las preferencias privadas de la app.

## Modos de pantalla
Se cambian desde el menú 🎮 o desde Ajustes (por defecto, *Normal*):
- **Normal**: los botones responden y el resto de la pantalla se puede tocar.
- **Tocar pantalla** 👆: los toques atraviesan el mando; los botones se ven más tenues y no responden.
- **Solo botones** 🔒: solo responden los botones; una ventana transparente bloquea el fondo para evitar clics
  accidentales. «Ocultar» libera la pantalla en cualquier modo.

## Teclado, ratón y trackpad
- El **teclado y el ratón virtuales** solo existen mientras el perfil activo los necesita. Se crean al entrar en el
  perfil y se destruyen unos segundos después de salir.
- **Teclado en pantalla**: el botón «Teclado» abre uno propio (arrastrable y redimensionable). Envía las teclas por el
  teclado virtual, así se puede escribir aunque Android oculte su teclado al detectar uno conectado. Los modificadores
  se quedan pulsados hasta la siguiente tecla.
- **Trackpad**: arrastrar mueve el ratón con aceleración (lento = preciso, rápido = cruza la pantalla); un toque es clic
  izquierdo; toque y arrastrar, arrastrar; dos dedos, rueda; un toque con dos dedos, clic derecho. «Sens −/+» en el editor.
- Los clics del ratón virtual que caen sobre las ventanas del propio mando se ignoran.
- En Ajustes hay un acceso a los ajustes del teclado del sistema (opción de mostrar el teclado en pantalla).

## Especificaciones técnicas
| | |
|---|---|
| Lenguaje | Java 8 (compatibilidad con proyectos antiguos y con editores móviles) |
| `minSdk` / `targetSdk` / `compileSdk` | 24 / 28 / 28 |
| Build | Gradle 6.7.1 · Android Gradle Plugin 4.2.2 · AndroidX |
| Dependencias | Shizuku API y provider 13.1.5 · libadb-android 1.0.1 · sun-security-android 1.1 · Conscrypt 2.5.2 |
| Interfaz | Vistas nativas dibujadas en `Canvas` (sin XML de layouts ni WebView) |
| Ventanas | `TYPE_APPLICATION_OVERLAY`, no enfocables, con `FLAG_SPLIT_TOUCH` para multitáctil entre ventanas |
| Multitáctil | Un puntero por control; varios controles a la vez, incluso entre ventanas distintas |
| Persistencia | `SharedPreferences` privadas (perfiles JSON, posición del tirador, modo de pantalla, claves ADB) |

**Permisos y para qué se usan**
| Permiso | Uso |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Dibujar el mando sobre otras apps |
| `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` | Mantener el mando activo y mostrar la notificación con «Mostrar/ocultar» y «Cerrar»; campo del código de emparejamiento |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sockets locales hacia el ADB del propio teléfono (127.0.0.1) |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Descubrimiento mDNS de los servicios de depuración inalámbrica |
| `VIBRATE` | Respuesta háptica al pulsar |

**Mapa de entradas del mando virtual**
| Control | Evento `uinput` |
|---|---|
| A, B, X, Y | `BTN_A` (304), `BTN_B` (305), `BTN_X` (307), `BTN_Y` (308) |
| L1, R1 | `BTN_TL` (310), `BTN_TR` (311) |
| L2, R2 | `ABS_Z`, `ABS_RZ` (digitales: 0 o 255) |
| SELECT, START, MODE | `BTN_SELECT` (314), `BTN_START` (315), `BTN_MODE` (316) |
| L3, R3 | `BTN_THUMBL` (317), `BTN_THUMBR` (318) |
| Cruceta | `ABS_HAT0X`, `ABS_HAT0Y` |
| Stick izquierdo / derecho | `ABS_X`,`ABS_Y` / `ABS_RX`,`ABS_RY` (±32767, analógicos) |
| Teclado | Dispositivo «Overlay Keyboard», códigos de tecla Linux |
| Ratón | Dispositivo «Overlay Mouse»: `REL_X`, `REL_Y`, `REL_WHEEL`, `BTN_LEFT/RIGHT/MIDDLE` |

El mando se registra con el nombre «Overlay Gamepad» y unos identificadores VID/PID numéricos (0x045E / 0x028E) con los
que Android aplica su mapa de ejes estándar a los gatillos y al stick derecho. Están marcados con un comentario en
`VirtualGamepadManager`; cambiarlos puede romper esos ejes en algunas apps.

## Arquitectura del código
```
app/src/main/java/com/example/floatingpad/
├── MainActivity.java               Pantalla principal: permisos, conexión, arranque y diagnóstico
├── FloatingControllerService.java  Servicio del overlay: ventanas, tirador, ajustes, editor, perfiles, modos de pantalla
├── GamepadView.java                Vista de una parte del mando: dibujo, multitáctil, trackpad y modo edición
├── PadModel.java                   Modelo (sin vistas): controles, distribuciones, colisiones, agrupación en ventanas, JSON
├── KeyDefs.java                    Tablas de teclas (teclado/ratón y botones de mando) para el selector
├── ScreenKeyboard.java             Teclado en pantalla propio
├── VirtualGamepadManager.java      Mando virtual: sesión `uinput`, botones, hat y ejes
├── KbmDevices.java                 Teclado y ratón virtuales: ciclo de vida, cola ordenada, teclas pulsadas
├── ShellSession.java               Abstracción de la sesión de shell (Shizuku o ADB)
├── AdbConnector.java               Cliente de depuración inalámbrica propio (emparejar, conectar, abrir flujos)
├── PairingService.java             Emparejamiento guiado por notificación con descubrimiento mDNS
└── ProfileStore.java               Almacén de perfiles y perfil activo por orientación
tools/layout-check/LayoutCheck.java Comprobador de distribuciones (ver abajo)
```
Decisiones de diseño relevantes:
- **Ventanas pequeñas, no una pantalla completa.** `PadModel.clusters()` agrupa los controles en cajas disjuntas
  (máximo 8, para no penalizar el rendimiento); cada caja es una ventana. Lo que queda fuera no recibe toques del mando.
- **Marco útil medido en ejecución.** Una ventana sonda mide la zona donde Android coloca las superposiciones, de modo
  que nada queda bajo la barra de estado o de navegación.
- **Sin reconstrucciones innecesarias.** Android avisa de un cambio de configuración cuando aparece un teclado; el
  servicio solo reconstruye las ventanas si cambia la orientación o el tamaño.
- **Teclado/ratón aislados del mando.** Van en un segundo proceso `uinput`; si su sesión falla no se reinicia la
  conexión del mando. Una sola hebra ordena los eventos y se recuerdan las teclas pulsadas.

**Comprobador de distribuciones** (no necesita Android; usa un JAR de `org.json`):
```bash
javac -cp json.jar -d /tmp/lc app/src/main/java/com/example/floatingpad/{PadModel,KeyDefs}.java tools/layout-check/LayoutCheck.java
java -cp /tmp/lc:json.jar com.example.floatingpad.LayoutCheck
```
Verifica, para varios tamaños de pantalla, que ningún perfil integrado colisiona, que las ventanas son disjuntas y no
superan el límite, y que los perfiles se guardan y cargan sin pérdidas. No hay todavía pruebas de interfaz.

## Límites conocidos
- **Privilegios**: sin Shizuku ni depuración inalámbrica activa no hay mando virtual. Si se desactiva la depuración
  inalámbrica o se pierde la Wi-Fi, la conexión se corta y hay que reconectar.
- **Versión de Android**: necesita el binario `uinput` del sistema (Android 12+). Algunos fabricantes pueden restringirlo.
- **Gatillos digitales**: L2 y R2 son todo o nada; no hay gatillos analógicos.
- **Sin vibración del mando, giroscopio ni panel táctil** reportados al sistema.
- **Puntero de ratón visible**: con el ratón virtual activo, Android muestra un puntero y puede ocultar su teclado en
  pantalla. Al salir de un perfil de teclado los dispositivos se destruyen tras unos segundos.
- **Cambios de dispositivos de entrada**: al crearse o destruirse un teclado/ratón, algunas apps o juegos reciben un
  cambio de configuración y pueden reaccionar (reiniciar la actividad, cambiar de esquema de control).
- **Gesto «atrás»**: los toques pegados a los bordes pueden activar el gesto de navegación del sistema.
- **Máximo 8 ventanas**: en perfiles muy densos las cajas se unen y pueden cubrir huecos entre botones (usa el modo
  «Tocar pantalla» si necesitas esa zona).
- **Seguridad de ADB**: la biblioteca de ADB no ha pasado una auditoría de seguridad (aviso de su autor). Lee
  [Privacidad y seguridad](#privacidad-y-seguridad).
- **Distribución**: `targetSdk 28` impide publicar en Google Play, que exige un valor mucho más reciente. Pensado para
  distribuirse por GitHub o tiendas alternativas.
- **Interfaz solo en español** (textos fijos en el código) y sin icono propio.
- **Compatibilidad**: verificado sobre todo en un único modelo de teléfono. Otros fabricantes pueden comportarse distinto.

## Privacidad y seguridad
- La app **no recoge ni envía datos** y no se conecta a servidores externos. `INTERNET` se usa solo para sockets
  locales hacia el ADB del propio teléfono (127.0.0.1) y para el descubrimiento mDNS en la red local.
- Las claves y el certificado del emparejamiento ADB se guardan en las preferencias privadas de la app, en Base64 y
  **sin cifrar**; `allowBackup` está desactivado. Quien tenga acceso root al teléfono podría leerlas.
- Mientras el teléfono esté emparejado, la app puede ejecutar comandos con privilegios de shell. Usa «Olvidar
  emparejamiento» (y revoca el dispositivo en Depuración inalámbrica) si dejas de usar la app.
- Los registros de diagnóstico se muestran solo en la propia app.

## Solución de problemas
| Síntoma | Qué probar |
|---|---|
| «Necesita emparejar» | Activa Depuración inalámbrica, pulsa «Vincular dispositivo con código» y escribe el código en la notificación. |
| No aparece la notificación del código | Permite las notificaciones de la app en los ajustes de Android. |
| No existe `/system/bin/uinput` | Tu Android no lo incluye (necesita 12+). Prueba con Shizuku o con otra versión de Android. |
| El mando virtual no se ve en la app | Reinicia la conexión desde «Más opciones» y revisa el diagnóstico. |
| El overlay no aparece | Concede «Mostrar sobre otras apps» y comprueba que el servicio está en la notificación. |
| No puedo tocar la pantalla | Menú 🎮 → modo de pantalla, o «Ocultar» desde la notificación. |
| El trackpad va lento o rápido | «Sens −/+» en el editor y la velocidad del puntero en los ajustes de Android. |
| No sale el teclado del sistema | Usa el teclado en pantalla propio o activa «mostrar teclado en pantalla» en los ajustes del teclado. |
| Fallo al compilar con D8 | Respeta las versiones de `libadb-android`/`spake2-android` del `build.gradle`. |

## Hoja de ruta
- [ ] Textos en `strings.xml` y traducción (inglés primero).
- [ ] Pruebas unitarias JUnit para `PadModel` y pruebas de interfaz.
- [ ] Gatillos analógicos (control deslizante) y sensibilidad/zona muerta de los sticks por perfil.
- [ ] Importar y exportar perfiles; transparencia y tamaño guardados por perfil.
- [ ] Más controles: macros, botones de alternancia, deslizadores, ruedas de selección.
- [ ] Vibración del mando virtual (efectos de fuerza) y soporte de varios mandos.
- [ ] Zonas de exclusión del gesto «atrás» y mejor tratamiento de recortes de pantalla.
- [ ] Subir `targetSdk` (revisando tipos de servicio en primer plano y permisos) y migrar a un AGP actual.
- [ ] Integración continua (GitHub Actions) y publicación de APK firmados.
- [ ] Icono propio y tutorial de primer arranque.
- [ ] Cifrar las claves ADB con el Keystore de Android.

## Contribuir
1. Abre un *issue* describiendo el problema o la idea (incluye modelo de teléfono, versión de Android y el diagnóstico
   de la app).
2. Mantén Java 8 y los textos de interfaz en español (hasta que se externalicen).
3. Si tocas `PadModel` o las distribuciones, ejecuta el comprobador de distribuciones.
4. Procura no añadir dependencias sin necesidad; cada una aumenta el tamaño y los avisos de licencia.

## Licencia y créditos
**Este repositorio todavía no incluye un archivo `LICENSE`.** Sin él, el código queda con todos los derechos
reservados por defecto: elige una licencia antes de publicarlo (por ejemplo MIT, Apache-2.0 o GPL-3.0) y comprueba que sea
compatible con las dependencias.

Dependencias de terceros (cada una con su propia licencia; revísalas antes de redistribuir binarios):
| Proyecto | Uso | Licencia |
|---|---|---|
| [Shizuku](https://github.com/RikkaApps/Shizuku) (API y provider) | Ejecutar `uinput` con privilegios de shell | Apache-2.0 |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | Cliente de depuración inalámbrica | GPL-3.0-or-later **o** Apache-2.0 (doble licencia; su autor indica una dependencia LGPL) |
| [Conscrypt](https://github.com/google/conscrypt) | TLS para el emparejamiento ADB | Apache-2.0 |
| sun-security-android | Certificados X.509 para ADB | Comprobar en su repositorio |

**Aviso legal.** Todas las marcas y nombres comerciales pertenecen a sus propietarios. Este proyecto es independiente y
no está afiliado, patrocinado ni respaldado por ningún fabricante de hardware ni desarrollador de software.
