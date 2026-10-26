# ẞetaMusic Android 2.0 — base reconstruida

Proyecto Android nativo en Kotlin, Jetpack Compose y AndroidX Media3.

## Requisitos de compilación
- JDK 17
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Android SDK 35

## Compilar con GitHub Actions
1. Sube el contenido de esta carpeta a la raíz del repositorio (incluida `.github/workflows/android-apk.yml`).
2. Abre la pestaña **Actions**.
3. Ejecuta **Build BetaMusic Android APK** mediante **Run workflow**, o haz un commit para que se ejecute automáticamente.
4. Abre la ejecución finalizada y descarga el artefacto `BetaMusic-Android-debug`.
5. El APK está dentro del ZIP del artefacto.

## Funciones incluidas en esta base
- Lectura de música local mediante MediaStore y permiso de audio según la versión de Android.
- Búsqueda en títulos, artistas, álbumes y rutas.
- Filtro de favoritos persistente.
- Reproducción Media3 con servicio y sesión multimedia, controles básicos, cola de biblioteca, progreso y notificación multimedia del sistema.
- Interfaz oscura con acento verde lima y mini reproductor.
- Workflow para compilar y publicar el APK como artefacto de GitHub Actions.

## Estado y limitaciones
Esta es una reconstrucción inicial mejorada, no una aplicación final ya validada. No se ha ejecutado una compilación real en este entorno. Las pestañas Artistas, Álbumes y Carpetas están preparadas visualmente, pero en esta versión siguen mostrando la lista musical filtrable, no vistas agrupadas independientes. No incluye todavía carátulas, ecualizador DSP, edición de etiquetas, escaneo de letras ni importación de listas. La compilación de GitHub Actions será la primera validación y puede revelar incompatibilidades que habrá que corregir.
