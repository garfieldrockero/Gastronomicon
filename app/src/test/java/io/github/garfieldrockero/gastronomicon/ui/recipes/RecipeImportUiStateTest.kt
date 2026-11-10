package io.github.garfieldrockero.gastronomicon.ui.recipes

import io.github.garfieldrockero.gastronomicon.core.client.AiCapabilities
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariantes de «Importar receta» que se pueden comprobar sin Android ni
 * corrutinas: el estado no enseña lo que se ha pegado y de él se deduce si se
 * puede mandar algo.
 *
 * Aquí no se decide qué vale (eso es del núcleo, con sus propias pruebas): solo
 * se comprueba el cableado que impide dos envíos con un mismo contenido y que el
 * `toString` no se lleve la receta a un log.
 */
class RecipeImportUiStateTest {

    @Test
    fun toStringSaysNothingAboutWhatWasPasted() {
        val state = RecipeImportUiState(
            visible = true,
            url = "https://recetas.example.com/arroz-con-verduras",
            dataText = """{"@type":"Recipe","name":"Arroz con verduras"}""",
            aiLink = "https://video.example.com/paella",
            aiText = "Sofríe el pollo y añade el arroz.",
        )

        val text = state.toString()

        assertFalse(text.contains("recetas.example.com"))
        assertFalse(text.contains("Arroz con verduras"))
        assertFalse(text.contains("video.example.com"))
        assertFalse(text.contains("Sofríe el pollo"))
        // Lo que sí dice: cuánto ocupa, para poder diagnosticar sin leerlo.
        assertTrue(text.contains("dataChars="))
        assertTrue(text.contains("aiTextChars="))
        assertTrue(text.contains("aiLinkChars="))
    }

    @Test
    fun aSecondPressCannotSendWhileTheFirstOneIsRunning() {
        val ready = RecipeImportUiState(visible = true, aiText = "Sofríe el pollo.")
        assertTrue(canImportAi(ready))

        // Así queda el estado en el mismo instante del primer toque, antes de lanzar
        // la corrutina: un segundo toque ya lo ve ocupado y no manda nada.
        assertFalse(canImportAi(ready.copy(runningSource = ImportSource.Ai)))
        assertFalse(canImportAi(ready.copy(aiPreparingPhoto = true)))
        assertFalse(canImportAi(ready.copy(readingFile = true)))
        assertFalse(canImportAi(ready.copy(importedSlug = "arroz-con-verduras")))
        assertFalse(canImportAi(ready.copy(visible = false)))
    }

    @Test
    fun nothingIsSentWithoutContent() {
        val empty = RecipeImportUiState(visible = true, aiText = "   ", aiLink = "")

        assertFalse(empty.hasAiContent)
        assertFalse(canImportAi(empty))
    }

    @Test
    fun photosAreCountedAgainstTheCoreTotal() {
        val perPhoto = 5 * 1024 * 1024
        val fitting = RecipeImportUiState(aiPhotos = List(3) { AiPhoto(preview = null, bytes = perPhoto) })

        assertFalse(fitting.aiPhotosOverTotalBytes)
        assertEquals(3L * perPhoto, fitting.aiPhotosBytes)

        // Cuatro fotos de 5 MiB ya pasan de los 15 MiB del conjunto: se avisa antes
        // de enviar, aunque quien rechace sea el núcleo.
        val over = RecipeImportUiState(aiPhotos = List(4) { AiPhoto(preview = null, bytes = perPhoto) })
        assertTrue(over.aiPhotosOverTotalBytes)
        assertTrue(over.aiPhotosBytes > MealieClient.MAX_AI_TOTAL_IMAGE_BYTES)
    }

    @Test
    fun theNumbersInTheTextsComeFromTheCore() {
        val state = RecipeImportUiState()

        assertEquals(MealieClient.MAX_AI_IMAGES, state.maxAiPhotos)
        assertEquals(5, state.maxAiPhotoMiB)
        assertEquals(15, state.maxAiTotalMiB)
        assertEquals(1, state.maxAiTextMiB)
    }

    @Test
    fun noAiWithSomethingWrittenKeepsTheFormAndLetsYouTry() {
        val late = RecipeImportUiState(
            visible = true,
            aiAvailability = AiAvailability.NotConfigured,
            aiText = "Sofríe el pollo.",
        )

        // El «no hay IA» llegó tarde: se avisa (la pantalla pinta el aviso) pero lo
        // escrito no se queda encerrado.
        assertTrue(late.aiUnavailable)
        assertTrue(late.aiFormVisible)
        assertTrue(canImportAi(late))

        // Sin nada escrito no hay formulario que enseñar: solo título y aviso.
        val empty = late.copy(aiText = "")
        assertFalse(empty.aiFormVisible)
        assertFalse(canImportAi(empty))
    }

    @Test
    fun onlyAClearNoFromTheServerTurnsPhotosOff() {
        val unknown = RecipeImportUiState(visible = true, aiAvailability = AiAvailability.Available)

        // Sin capacidades (no se sabe, o la sección está apagada) se ofrece lo de
        // siempre: la interfaz no adivina lo que el servidor no ha dicho.
        assertTrue(unknown.aiPhotosAvailable)
        assertFalse(unknown.aiAudioUnavailable)

        // Con IA de texto y sin proveedor de imagen: fotos fuera, vídeos dentro.
        val noImage = unknown.copy(
            aiCapabilities = AiCapabilities(
                available = true,
                imageAvailable = false,
                audioAvailable = true,
            ),
        )
        assertFalse(noImage.aiPhotosAvailable)
        assertFalse(noImage.aiAudioUnavailable)

        // Sin proveedor de audio: las fotos siguen y los vídeos se avisan.
        val noAudio = unknown.copy(
            aiCapabilities = AiCapabilities(
                available = true,
                imageAvailable = true,
                audioAvailable = false,
            ),
        )
        assertTrue(noAudio.aiPhotosAvailable)
        assertTrue(noAudio.aiAudioUnavailable)
    }
}
