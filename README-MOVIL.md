# ẞetaMusic Android: importación usando solo el teléfono

Este paquete está pensado para que no tengas que subir docenas de archivos por separado. Contiene el código Android dentro de `BetaMusic-Android-source.zip` y el workflow que le indica a GitHub cómo extraerlo y compilarlo.

## Pasos

1. Crea un repositorio nuevo en GitHub llamado `betamusic-android`. Es mejor crearlo vacío, sin README automático.
2. En GitHub abre **Add file → Create new file**.
3. En el campo del nombre escribe exactamente `.github/workflows/android-apk.yml`.
4. Abre el archivo visible `android-apk.yml.txt` que viene en este paquete y copia todo su contenido al editor de GitHub. Guarda con **Commit changes**.
5. En el repositorio usa **Add file → Upload files** y sube `BetaMusic-Android-source.zip` desde este paquete. Guarda con **Commit changes**.
6. Abre **Actions**. El workflow comenzará con ese commit. Si no, entra en **Build ẞetaMusic Android APK from source ZIP** y pulsa **Run workflow**.
7. Cuando termine correctamente, abre la ejecución y baja hasta **Artifacts**. Descarga `betamusic-android-debug-apk`.
8. Extrae el ZIP descargado y abre `app-debug.apk` para instalarlo. Android puede pedir permiso para instalar desde el gestor de archivos o navegador.

## Nota

El repositorio usa el workflow de este paquete y descomprime el código fuente durante la ejecución. No descomprimas `BetaMusic-Android-source.zip` en GitHub: súbelo tal cual. La compilación requiere que GitHub Actions pueda descargar el Android SDK, Gradle y las dependencias.
