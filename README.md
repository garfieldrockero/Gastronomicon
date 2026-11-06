# Gastronomicón

Cliente Android **no oficial** para [Mealie](https://mealie.io): usa tu servidor
autoalojado desde el móvil para consultar y crear recetas.

> No es un fork de Mealie ni está afiliado a su proyecto. Es un cliente independiente de
> su API REST.

## Estado

En desarrollo. Fase 1: esqueleto del proyecto, compilación en GitHub Actions y validación
del núcleo. Todavía no hay una versión publicada.

La primera versión incluirá:

- configuración de la URL del servidor y del token de API;
- lista de recetas con búsqueda y detalle, con raciones escaladas;
- creación, edición y borrado de recetas;
- ingredientes, pasos, tiempos, etiquetas y categorías;
- foto desde la galería o la cámara del sistema;
- importación de recetas por URL.

La lista de la compra, el planificador y el modo sin conexión llegarán después.

## Seguridad

- Solo **HTTPS**; no se admite tráfico HTTP en claro.
- El token se guarda cifrado con el **Keystore de Android** y no se registra en logs ni en
  copias de seguridad.
- El HTML de las instrucciones se sanea y se renderiza de forma restringida: nunca en un
  WebView con JavaScript.
- Sin telemetría ni analítica: la app solo habla con el servidor que configures.
- Solo dos permisos: `INTERNET` y `ACCESS_NETWORK_STATE`.

## Compilación

El proyecto se compila en **GitHub Actions** (`.github/workflows/apk.yml`): primero las
pruebas del núcleo, después la APK release y una comprobación del manifiesto final. La APK
queda como artefacto `gastronomicon-apk` en la ejecución del workflow.

En el equipo de desarrollo no hace falta instalar el SDK de Android ni Gradle.

## Tecnología

- Kotlin + Jetpack Compose (Material 3).
- Núcleo en Kotlin puro, con pruebas JUnit.
- `kotlinx-serialization-json` para el JSON de la API.
- `HttpURLConnection` para la red, sin librerías HTTP adicionales.

## Licencia

[Apache License 2.0](LICENSE).
