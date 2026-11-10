package io.github.garfieldrockero.gastronomicon.core.client

/**
 * Qué puede hacer la IA del servidor de la persona, según
 * `aiProviderSettings` de `GET /api/groups/self` (`AIProviderSettingsOut` de
 * Mealie v3.29.0).
 *
 * Son **tres cosas distintas** y la interfaz las usa por separado:
 *
 * - [available]: hay IA general (`aiEnabled`). Sin esto no se ofrece nada.
 * - [imageAvailable]: hay proveedor de **imagen** (`imageProviderEnabled`). Lo
 *   necesitan las fotos: con un servidor de solo texto, mandar fotos gasta una
 *   petición a la IA para recibir un error.
 * - [audioAvailable]: hay proveedor de **audio** (`audioProviderEnabled`). Lo
 *   necesitan los enlaces de vídeo, que el servidor transcribe antes de
 *   redactar la receta.
 *
 * Los tres son «se puede / no se puede», no «está configurado»: si
 * [available] es false, [imageAvailable] y [audioAvailable] también, porque sin
 * IA general no hay nada que hacer aunque quede un proveedor configurado de
 * antes.
 *
 * Un proveedor que el servidor **no** anuncia cuenta como no disponible (fallo
 * cerrado: se apaga esa entrada de la pantalla). No es lo mismo que
 * «desconocido»: cuando no se sabe si hay IA siquiera, `MealieClient.aiAvailable`
 * devuelve un fallo —no un [AiCapabilities] con todo a false—, para que la
 * interfaz ofrezca la opción en vez de esconder una función que el servidor sí
 * tiene.
 *
 * No lleva nada del servidor más allá de tres booleanos: ni claves de
 * proveedor, ni identificadores, ni URLs, así que se puede imprimir sin riesgo.
 */
data class AiCapabilities(
    val available: Boolean,
    val imageAvailable: Boolean,
    val audioAvailable: Boolean,
)
