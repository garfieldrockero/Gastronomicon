package io.github.garfieldrockero.gastronomicon.core.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La frontera local de importar con la IA (`POST /api/recipes/create/ai`): lo
 * que no cumple no sale de la app.
 *
 * Cada foto es una llamada al proveedor de IA que paga la persona y el texto
 * puede ser un fichero entero por error, así que todo se comprueba **antes** de
 * enviar: tamaño del texto en bytes UTF-8 y controles, solo `https` en el
 * enlace, y en las fotos el tipo real, el tope por foto, el tope del conjunto y
 * los metadatos. Un rechazo aquí no gasta ni red ni dinero.
 */
class MealieClientAiLimitsTest {

    private lateinit var mealie: FakeMealie

    private val png = FakeMealie.minimalPng()

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun rejected(reason: InputProblem): ApiResult<String> =
        ApiResult.InputRejected(reason)

    // ------------------------------------------------------- nada que enviar

    @Test
    fun `sin texto ni enlace ni fotos no se llama a la IA`() {
        val cases: List<Triple<String?, String?, List<AiImage>>> = listOf(
            Triple(null, null, emptyList()),
            Triple("", null, emptyList()),
            Triple("   ", "", emptyList()),
            // Espacios «de verdad»: el mismo criterio que importar texto.
            Triple("\u00A0", null, emptyList()),
            Triple("\u2003\u3000", "  ", emptyList()),
            Triple(null, "   ", emptyList()),
        )
        for ((content, url, images) in cases) {
            assertEquals(
                "content=${content?.length} url=${url?.length}",
                rejected(InputProblem.EmptyData),
                mealie.client().createRecipeWithAi(content, url, images),
            )
        }
        assertTrue(mealie.seen.isEmpty())
    }

    // ------------------------------------------------------------- el texto

    @Test
    fun `los controles y surrogados sueltos del texto no salen de la app`() {
        val bad = listOf(
            "\u0000", "a\u0000b", "\u0007", "\u001B", "<p>ok</p>\u001F", "abc\u007F", "\u0085", "\u009F",
            "\uD800", "abc\uDC00", "\uD83D", "x\uD83Dy",
        )
        for (content in bad) {
            assertEquals(
                "longitud ${content.length}",
                rejected(InputProblem.DataNotText),
                mealie.client().createRecipeWithAi(content, null, emptyList()),
            )
        }
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `el texto tiene el mismo tope en bytes UTF-8 que importar html o json`() {
        mealie.fixed("POST", PATH, 201, "\"tarta\"")
        val max = MealieClient.MAX_IMPORT_DATA_BYTES
        // «ñ» ocupa 2 bytes: justo en el tope con la mitad de caracteres.
        val atLimit = "\u00F1".repeat(max / 2)
        assertEquals(max, atLimit.toByteArray(Charsets.UTF_8).size)
        assertEquals(ApiResult.Success("tarta"), mealie.client().createRecipeWithAi(atLimit, null, emptyList()))
        assertEquals(1, mealie.requestsTo("POST", PATH).size)

        // Un byte ASCII más ya pasa, aunque sigan siendo menos de 1 MiB de
        // caracteres: lo que cuenta es el tamaño en UTF-8, no la longitud.
        mealie.seen.clear()
        assertEquals(
            rejected(InputProblem.DataTooLarge),
            mealie.client().createRecipeWithAi(atLimit + "a", null, emptyList()),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    // -------------------------------------------------------------- el enlace

    @Test
    fun `solo se admite un enlace https y el resto no sale de la app`() {
        val bad = listOf(
            "http://ejemplo.org/r", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,x",
            "ftp://ejemplo.org/r", "//ejemplo.org/r", "ejemplo.org/r", "https://usuario:clave@ejemplo.org/r",
            "https://ejemplo.org/a b", "https://ejemplo.org/\r\nX: y", "https://", "https:///x",
            "https://ejemplo.org/" + "a".repeat(2_100), "https://ejemplo.org/ñ",
        )
        for (url in bad) {
            assertEquals(url.take(30), rejected(InputProblem.InvalidUrl), mealie.client().createRecipeWithAi(null, url, emptyList()))
        }
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `un enlace https con mayusculas y espacios en los extremos vale`() {
        mealie.fixed("POST", PATH, 201, "\"x\"")
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().createRecipeWithAi(null, "  HTTPS://Ejemplo.org/r  ", emptyList()),
        )
        val body = String(mealie.seen.single().body, Charsets.UTF_8)
        assertTrue(body.contains("name=\"url\"\r\n\r\nHTTPS://Ejemplo.org/r\r\n"))
    }

    // -------------------------------------------------------------- las fotos

    @Test
    fun `como mucho cinco fotos`() {
        val six = List(MealieClient.MAX_AI_IMAGES + 1) { AiImage(png, "png") }
        assertEquals(
            rejected(InputProblem.TooManyImages),
            mealie.client().createRecipeWithAi(null, null, six),
        )
        assertTrue(mealie.seen.isEmpty())

        // Justo cinco, pequeñas, sí salen.
        mealie.fixed("POST", PATH, 201, "\"x\"")
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().createRecipeWithAi(null, null, six.dropLast(1)),
        )
        assertEquals(1, mealie.requestsTo("POST", PATH).size)
    }

    @Test
    fun `solo jpeg png y webp, y los bytes tienen que ser de ese formato`() {
        val unsupported = listOf("gif", "svg", "jpg", "bmp", "", "png\r\nX: y", "../png", "png;x", "image/png", ".png")
        for (extension in unsupported) {
            assertEquals(
                extension,
                rejected(InputProblem.UnsupportedImageType),
                mealie.client().createRecipeWithAi(null, null, listOf(AiImage(png, extension))),
            )
        }
        val notImages = listOf(
            "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
            "<html><script>alert(1)</script></html>".toByteArray(),
            "GIF89a....".toByteArray(),
            "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WAVE".toByteArray() + byteArrayOf(1, 2),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E),
            byteArrayOf(0),
            ByteArray(0),
        )
        for (bytes in notImages) {
            assertEquals(
                "tamaño ${bytes.size}",
                rejected(InputProblem.InvalidImage),
                mealie.client().createRecipeWithAi(null, null, listOf(AiImage(bytes, "png"))),
            )
        }
        // Una imagen real con la extensión de otro formato tampoco se envía.
        assertEquals(
            rejected(InputProblem.InvalidImage),
            mealie.client().createRecipeWithAi(null, null, listOf(AiImage(png, "webp"))),
        )
        assertEquals(
            rejected(InputProblem.InvalidImage),
            mealie.client().createRecipeWithAi(null, null, listOf(AiImage(FakeMealie.minimalJpeg(), "png"))),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `una foto con metadatos no sale de la app`() {
        assertEquals(
            rejected(InputProblem.ImageHasMetadata),
            mealie.client().createRecipeWithAi(null, null, listOf(AiImage(FakeMealie.jpegWithExif(), "jpeg"))),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `el tope por foto es el mismo que al subir la foto de una receta`() {
        // Justo en el tope sí se manda (5 MiB de imagen más el envoltorio).
        mealie.fixed("POST", PATH, 201, "\"x\"")
        val atLimit = FakeMealie.pngOfSize(MealieClient.MAX_IMAGE_BYTES.toInt())
        assertEquals(MealieClient.MAX_IMAGE_BYTES, atLimit.size.toLong())
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().createRecipeWithAi(null, null, listOf(AiImage(atLimit, "png"))),
        )
        assertEquals(1, mealie.requestsTo("POST", PATH).size)

        // Un byte más ya no: el tamaño se mira antes que los bytes mágicos, así
        // que no hace falta que el fichero sea una imagen de verdad.
        mealie.seen.clear()
        val over = AiImage(ByteArray(MealieClient.MAX_IMAGE_BYTES.toInt() + 1), "png")
        assertEquals(
            rejected(InputProblem.ImageTooLarge),
            mealie.client().createRecipeWithAi(null, null, listOf(over)),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `el conjunto de fotos tiene su propio tope`() {
        val photo = MealieClient.MAX_IMAGE_BYTES.toInt()
        // Justo en el tope del conjunto (15 MiB) sí se manda: es la prueba de
        // que el cuerpo multipart puede pasar de los 6 MiB de las demás
        // peticiones sin que HttpRequest lo rechace.
        mealie.fixed("POST", PATH, 201, "\"x\"")
        val atLimit = List(3) { AiImage(FakeMealie.pngOfSize(photo), "png") }
        assertEquals(MealieClient.MAX_AI_TOTAL_IMAGE_BYTES, atLimit.sumOf { it.bytes.size.toLong() })
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().createRecipeWithAi(null, null, atLimit),
        )
        assertTrue(mealie.requestsTo("POST", PATH).single().body.size > MealieClient.MAX_AI_TOTAL_IMAGE_BYTES)

        // Un byte más en el conjunto no: el total se mira antes que nada más.
        mealie.seen.clear()
        val over = listOf(
            AiImage(ByteArray(photo), "png"),
            AiImage(ByteArray(photo), "png"),
            AiImage(ByteArray(photo), "png"),
            AiImage(ByteArray(1), "png"),
        )
        assertEquals(
            rejected(InputProblem.ImageTooLarge),
            mealie.client().createRecipeWithAi(null, null, over),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `un problema en una foto no deja escapar las demas`() {
        // La primera es buena y la segunda lleva EXIF: no se envía ninguna.
        assertEquals(
            rejected(InputProblem.ImageHasMetadata),
            mealie.client().createRecipeWithAi(
                "Tarta",
                "https://ejemplo.org/r",
                listOf(AiImage(png, "png"), AiImage(FakeMealie.jpegWithExif(), "jpeg")),
            ),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `la foto no se imprime ni se puede confundir con su extension`() {
        val marker = "MARCA-DE-LA-FOTO"
        val image = AiImage(marker.toByteArray(), "png")
        val text = image.toString()
        assertFalse(text, text.contains(marker))
        assertTrue(text, text.contains("bytes=${marker.length}"))
    }

    private companion object {
        const val PATH = "/api/recipes/create/ai"
    }
}
