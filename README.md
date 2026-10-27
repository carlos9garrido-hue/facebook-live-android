# DEVOCast · Facebook Live para Android

App Android que transmite la cámara y el micrófono del celular a Facebook Live, como un OBS de bolsillo.

## Cómo obtener el APK

Cada vez que se sube código a `main`, GitHub Actions compila la app y publica el APK en la pestaña **Releases**. Descarga `app-debug.apk` de la compilación más reciente e instálalo (Android pedirá permitir fuentes desconocidas).

> La app se firma siempre con la misma llave (`app/fblive-signing.jks`). La **primera vez** que instales esta versión, desinstala la anterior; de ahí en adelante cada APK nuevo se instala encima sin perder los ajustes.

## Cómo transmitir

1. En la app abre **Ajustes → Directo** y toca **Abrir Live Producer en Facebook**. Elige «Software de transmisión».
2. En Live Producer elige **Usar una clave de transmisión persistente** (una sola clave que no cambia) y cópiala. Si quieres, copia también la URL del servidor.
3. Vuelve a la app y toca **Pegar lo copiado**. Si copiaste la URL con la clave juntas, la app las separa sola. La clave se guarda cifrada en el teléfono.
4. Toca **LIVE**. La insignia **EN VIVO**, el marco rojo parpadeante y los MB enviados confirman que sale video.
5. El botón **Chat** abre una ventana para leer los comentarios sin salir de la app.

## Funciones

- Lentes: detecta los lentes del teléfono (incluidos algunos ocultos), zoom rápido e informe de cámaras.
- Cámara: zoom, exposición, enfoque manual, balance de blancos, bloqueos, estabilización, linterna y cuadrícula.
- Multicámara experimental (imagen en imagen).
- Capas: textos e imágenes sobre el video.
- Escenas de video pregrabadas, con intro automática al iniciar.
- Medidor del micrófono con avisos de silencio o bloqueo, micrófono externo y Bluetooth.
- 720p/1080p, 30/60 fps, bitrate adaptativo, reconexión automática.

## Avanzado: inicio de sesión con Facebook (experimental)

La app puede crear la transmisión por la API de Facebook, sin copiar la clave. Requiere una app de Facebook para desarrolladores con el permiso `publish_video` (o `pages_manage_posts` para Páginas), la cuenta con 60 días o más y 100 seguidores o más. Hoy Meta no siempre ofrece ese permiso a apps nuevas, por eso **está apagado por defecto**: se activa al final de la pestaña Directo.

Si quieres probarlo:

1. Crea una app en <https://developers.facebook.com> con el caso de uso de Facebook Login.
2. En **Configuración de la app → Básica** agrega la plataforma Android: paquete `com.carlos.fblive`, clase `com.carlos.fblive.MainActivity`, hash de clave `y3X0FLJylnxMmFqrfD74z3JD4to=`.
3. Pon el ID de la app y el token de cliente en `app/src/main/res/values/facebook.xml`.

## Tecnología

Kotlin, [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.8.1, Facebook Login SDK 18.x y Graph API v26.0. Android 7.0 (API 24) o superior.
