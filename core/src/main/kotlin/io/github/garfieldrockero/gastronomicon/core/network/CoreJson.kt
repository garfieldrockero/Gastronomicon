package io.github.garfieldrockero.gastronomicon.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Única configuración de JSON de la app.
 *
 * - «ignoreUnknownKeys»: cada versión de Mealie puede añadir campos y un campo
 *   nuevo del servidor no puede romper una app ya instalada.
 * - «coerceInputValues»: Mealie devuelve muchos null explícitos. Sin esto, un
 *   null en un campo no anulable con valor por defecto (`val name: String = ""`)
 *   hace fallar la deserialización entera; con esto cae en el valor por
 *   defecto. Por lo mismo, un valor de enum que esta versión de la app no
 *   conoce (Mealie puede añadir uno) cae en el valor por defecto del campo en
 *   vez de romper la respuesta.
 * - «explicitNulls = false»: al enviar, los null no viajan (Mealie no tiene
 *   por qué recibir campos que la app no ha rellenado); al recibir, un campo
 *   anulable que no viene se queda en null aunque el modelo no le dé valor
 *   por defecto. Esto no convierte un null en el valor por defecto: eso es
 *   cosa de «coerceInputValues».
 */
object CoreJson {
    // «explicitNulls» sigue marcado como API experimental en
    // kotlinx.serialization; se acepta a sabiendas porque su comportamiento
    // es justo el que se quiere y CoreJsonTest lo vigila si algún día cambia.
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
}
