# DEVOCast · Facebook Live para Android

App Android que transmite la cámara y el micrófono del celular a Facebook Live, como un OBS de bolsillo.
Hay dos modos:

- **Con tu cuenta de Facebook (recomendado):** inicias sesión una vez, tocas **LIVE** y la app crea la transmisión sola. No hay que copiar URL ni clave.
- **Clave manual:** pegas la URL y la clave de Live Producer (modo de la primera versión).

## Cómo obtener el APK

Cada vez que se sube código a `main`, GitHub Actions compila la app y publica el APK en la pestaña **Releases**. Descarga `app-debug.apk` de la compilación más reciente e instálalo (Android pedirá permitir fuentes desconocidas).

> La app ahora se firma siempre con la misma llave (`app/fblive-signing.jks`). La **primera vez** que instales esta versión, desinstala la anterior; de ahí en adelante cada APK nuevo se instala encima sin perder los ajustes.

## Conectar la app con Facebook (una sola vez)

Facebook exige que toda app que inicie sesión esté registrada. Es gratis y solo la usas tú.

1. Entra a <https://developers.facebook.com>, crea una app y agrégale **Facebook Login**.
2. En **Configuración → Básica** copia el **ID de la app**. En **Configuración → Avanzada** copia el **Token de cliente**.
3. En esa misma pantalla agrega la plataforma **Android** con estos datos:
   - Nombre del paquete: `com.carlos.fblive`
   - Nombre de la clase: `com.carlos.fblive.MainActivity`
   - Hash de clave: `y3X0FLJylnxMmFqrfD74z3JD4to=`
   - Activa **Single Sign On**.
4. Asegúrate de que la app tenga disponibles los permisos `public_profile`, `publish_video`, `pages_show_list`, `pages_read_engagement` y `pages_manage_posts`. Mientras la app esté en modo **Desarrollo**, tú (como administrador) puedes usarlos sin pasar la revisión de Meta. Los nombres de las pantallas de Meta cambian seguido; si no encuentras algo, busca el permiso por su nombre.
5. En GitHub edita `app/src/main/res/values/facebook.xml` y reemplaza:
   - `TU_APP_ID` por el ID de la app (aparece en dos lugares: `facebook_app_id` y, después de `fb`, en `fb_login_protocol_scheme`).
   - `TU_CLIENT_TOKEN` por el token de cliente.
6. Espera a que termine la compilación, instala el APK nuevo y en **Ajustes → Directo** toca **Iniciar sesión con Facebook**.

### Requisitos de Facebook para transmitir por API

- La cuenta debe tener al menos 60 días.
- El perfil (en modo profesional) o la Página debe tener 100 seguidores o más.
- Para abrir la app a otras personas (no solo a ti) Meta exige pasar la revisión de la app.

## Cómo transmitir

1. **Ajustes → Directo → Iniciar sesión con Facebook** y acepta los permisos.
2. Elige dónde transmitir (tu perfil o una Página), pon un título y elige quién lo ve. «Solo yo» sirve para pruebas.
3. Toca **LIVE**. Primero sale «CONECTANDO» mientras Facebook crea la transmisión; cuando todo está bien la insignia dice **EN VIVO** y, unos segundos después, **FACEBOOK ✓ EN VIVO** con el número de espectadores. Eso lo reporta Facebook, no la app.
4. El botón **Chat** abre tu transmisión para leer los comentarios.
5. Toca **STOP**: la app termina la transmisión en Facebook.

## Funciones

- Lentes: detecta los lentes del teléfono (incluidos algunos ocultos), zoom rápido e informe de cámaras.
- Cámara: zoom, exposición, enfoque manual, balance de blancos, bloqueos, estabilización, linterna y cuadrícula.
- Multicámara experimental (imagen en imagen).
- Capas: textos e imágenes sobre el video.
- Escenas de video pregrabadas, con intro automática al iniciar.
- Medidor del micrófono con avisos de silencio o bloqueo.
- 720p/1080p, 30/60 fps, bitrate adaptativo, reconexión automática.

## Tecnología

Kotlin, [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.8.1, Facebook Login SDK 18.x y la Graph API v26.0 (Live Video API). Android 7.0 (API 24) o superior.
