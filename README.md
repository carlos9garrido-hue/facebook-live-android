# Facebook Live para Android (prototipo)

App Android que transmite la cámara y el micrófono del celular a Facebook Live por RTMPS, como lo haría OBS.

## Cómo obtener el APK

1. Cada vez que se sube código a `main`, GitHub Actions compila la app.
2. Abre la pestaña **Releases** del repositorio y descarga `app-debug.apk` de la compilación más reciente.
3. Instálalo en tu Android (permite instalar apps de fuentes desconocidas cuando el sistema lo pida).

## Cómo transmitir

1. En Facebook, abre **Live Producer** y elige transmitir con software de transmisión.
2. Copia la **URL del servidor** y la **clave de transmisión**.
3. Pégalas en la app y toca **Iniciar transmisión**.
4. Cuando la vista previa de Facebook muestre video, publica la transmisión desde Live Producer.

La clave no se guarda en el celular; la URL del servidor sí.

## Ajustes de video

720p, 30 fps, 2.5 Mbps, audio AAC de 128 kbps. Se cambian en `MainActivity.kt`.

## Tecnología

Kotlin, [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.8.1 (RTMP/RTMPS), mínimo Android 7.0 (API 24).
