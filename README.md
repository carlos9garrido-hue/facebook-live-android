# Facebook Live para Android

App Android que transmite la cámara y el micrófono del celular a Facebook Live por RTMPS, como un OBS de bolsillo.

## Cómo obtener el APK

Cada vez que se sube código a `main`, GitHub Actions compila la app y publica el APK en la pestaña **Releases**. Descarga `app-debug.apk` de la compilación más reciente e instálalo (Android pedirá permitir fuentes desconocidas).

## Cómo transmitir

1. En Facebook abre **Live Producer** y elige transmitir con software de transmisión.
2. Copia la **URL del servidor** y la **clave de transmisión** (conviene una clave persistente).
3. En la app abre *Ajustes → Transmisión*, pégalas y, si quieres, activa **Recordar la clave en este teléfono** (se guarda cifrada con el almacén seguro de Android).
4. Toca **LIVE**. La insignia EN VIVO, el marco rojo parpadeante y los MB enviados confirman que sale video.

## Funciones

- Lentes: detecta los lentes del teléfono (incluidos algunos ocultos), zoom rápido e informe de cámaras.
- Cámara: zoom, exposición, enfoque manual, balance de blancos, bloqueos, estabilización, linterna y cuadrícula.
- Multicámara experimental (imagen en imagen).
- Capas: textos e imágenes sobre el video.
- Escenas de video pregrabadas, con intro automática al iniciar.
- Medidor del micrófono con avisos de silencio o bloqueo.
- 720p/1080p, 30/60 fps, bitrate adaptativo, reconexión automática.

## Tecnología

Kotlin y [RootEncoder](https://github.com/pedroSG94/RootEncoder) 2.8.1. Android 7.0 (API 24) o superior.
