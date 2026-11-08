package io.github.garfieldrockero.gastronomicon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas de la configuración de JSON contra lo que de verdad manda Mealie:
 * null explícitos, valores de enum que la app aún no conoce y campos nuevos.
 * Si alguna de estas opciones se pierde, una respuesta normal del servidor
 * dejaría de deserializarse.
 */
class CoreJsonTest {

    @Test
    fun `null, enum desconocido y campo extra caen en los valores por defecto`() {
        val text = """
            {
              "name": null,
              "kind": "algo-que-esta-version-no-conoce",
              "campoNuevoDeMealie": {"anidado": [1, 2, 3]}
            }
        """.trimIndent()
        val sample = CoreJson.json.decodeFromString(CoreJsonSample.serializer(), text)
        assertEquals("", sample.name)
        assertEquals(CoreJsonSampleKind.Unknown, sample.kind)
        assertNull(sample.note)
    }

    @Test
    fun `los valores validos se leen tal cual`() {
        val text = """{"name":"Tortilla","kind":"recipe","note":"con cebolla"}"""
        val sample = CoreJson.json.decodeFromString(CoreJsonSample.serializer(), text)
        assertEquals("Tortilla", sample.name)
        assertEquals(CoreJsonSampleKind.Recipe, sample.kind)
        assertEquals("con cebolla", sample.note)
    }

    @Test
    fun `al enviar no viajan los null`() {
        val text = CoreJson.json.encodeToString(
            CoreJsonSample.serializer(),
            CoreJsonSample(name = "Tortilla", note = null),
        )
        assertFalse("Se ha enviado un null: $text", text.contains("null"))
        assertFalse(text.contains("note"))
    }
}

@Serializable
private enum class CoreJsonSampleKind {
    @SerialName("recipe")
    Recipe,

    @SerialName("unknown")
    Unknown,
}

/** Modelo mínimo con las tres formas de campo que importan aquí. */
@Serializable
private data class CoreJsonSample(
    val name: String = "",
    val kind: CoreJsonSampleKind = CoreJsonSampleKind.Unknown,
    val note: String?,
)
