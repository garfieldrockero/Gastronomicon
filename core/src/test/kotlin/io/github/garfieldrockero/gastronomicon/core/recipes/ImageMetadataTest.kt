package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detección de metadatos sobre bytes construidos a mano (nunca ficheros): cubre
 * los tres formatos que acepta la app, los metadatos que no son EXIF y los
 * casos truncados u hostiles, donde la función no puede lanzar ni quedarse
 * dando vueltas y tiene que fallar cerrada.
 */
class ImageMetadataTest {

    // ---------------------------------------------------------------- ayudas

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return bytes(0xFF, marker, (length shr 8) and 0xFF, length and 0xFF) + payload
    }

    /** SOS de una componente con un byte de datos detrás. */
    private val sos = bytes(0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00)
    private val eoi = bytes(0xFF, 0xD9)

    /**
     * JPEG bien formado: SOI, los segmentos de cabecera, SOS, el flujo de
     * datos y EOI. [trailing] son bytes detrás del EOI (un motion photo, por
     * ejemplo).
     */
    private fun jpeg(
        vararg headerSegments: ByteArray,
        entropy: ByteArray = bytes(0),
        trailing: ByteArray = ByteArray(0),
    ): ByteArray {
        val header = headerSegments.fold(ByteArray(0)) { acc, segment -> acc + segment }
        return bytes(0xFF, 0xD8) + header + sos + entropy + eoi + trailing
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val length = data.size
        return bytes(length ushr 24, length ushr 16, length ushr 8, length) +
            ascii(type) + data + ByteArray(4)
    }

    private val pngSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(vararg chunks: ByteArray): ByteArray =
        pngSignature + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

    /** PNG válido de los chunks indicados, sin IEND salvo que se pase. */
    private fun pngHeader(): ByteArray = pngChunk("IHDR", ByteArray(13))

    private val pngIend: ByteArray = pngChunk("IEND", ByteArray(0))

    private fun webpChunk(fourcc: String, data: ByteArray): ByteArray {
        val size = data.size
        val padding = if (size % 2 == 1) bytes(0) else ByteArray(0)
        return ascii(fourcc) + bytes(
            size and 0xFF,
            (size ushr 8) and 0xFF,
            (size ushr 16) and 0xFF,
            (size ushr 24) and 0xFF,
        ) + data + padding
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val data = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        // El tamaño RIFF cuenta «WEBP» y todos los chunks, no solo los chunks.
        val size = 4 + data.size
        return ascii("RIFF") + bytes(
            size and 0xFF,
            (size ushr 8) and 0xFF,
            (size ushr 16) and 0xFF,
            (size ushr 24) and 0xFF,
        ) + ascii("WEBP") + data
    }

    private val exifPayload = bytes('E'.code, 'x'.code, 'i'.code, 'f'.code, 0, 0, 0, 0)
    private val xmpPayload = ascii("http://ns.adobe.com/xap/1.0/\u0000")

    // ----------------------------------------------------------------- JPEG

    @Test
    fun `un jpeg completo sin metadatos es valido`() {
        assertFalse(ImageMetadata.hasMetadata(jpeg()))
        assertFalse(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xE0, ByteArray(14)))))
        // Basura entre segmentos: los decodificadores buscan el siguiente 0xFF.
        assertFalse(
            ImageMetadata.hasMetadata(
                jpeg(
                    bytes(0x00, 0x11, 0x22),
                    jpegSegment(0xE0, ByteArray(14)),
                    bytes(0x7F, 0xAA),
                ),
            ),
        )
        // Relleno 0xFF antes del marcador y datos escapados en el flujo comprimido.
        assertFalse(
            ImageMetadata.hasMetadata(
                jpeg(
                    bytes(0xFF, 0xFF) + jpegSegment(0xE0, ByteArray(14)),
                    entropy = bytes(0x11, 0xFF, 0x00, 0x22, 0xFF, 0xD0, 0x33),
                ),
            ),
        )
        // Un APP1 con una carga más corta que la cabecera EXIF no la contiene.
        assertFalse(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xE1, bytes('E'.code, 'x'.code)))))
    }

    @Test
    fun `un jpeg con exif xmp iptc o comentario lleva metadatos`() {
        assertTrue(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xE1, exifPayload))))
        assertTrue(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xE1, xmpPayload))))
        assertTrue(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xED, ByteArray(4)))))
        assertTrue(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xFE, ascii("hola")))))
        // Con basura delante del APP1 también se llega a él.
        assertTrue(
            ImageMetadata.hasMetadata(
                jpeg(bytes(0x55, 0x66), jpegSegment(0xE1, exifPayload)),
            ),
        )
        // El XMP extendido también es XMP.
        assertTrue(
            ImageMetadata.hasMetadata(
                jpeg(jpegSegment(0xE1, ascii("http://ns.adobe.com/xmp/extension/\u0000"))),
            ),
        )
        // Un APP1 con una cabecera que no es de metadatos conocidos no cuenta.
        assertFalse(ImageMetadata.hasMetadata(jpeg(jpegSegment(0xE1, ascii("OTRA COSA")))))
    }

    @Test
    fun `un jpeg truncado o malformado falla cerrado`() {
        val cases = listOf(
            bytes(0xFF, 0xD8),
            bytes(0xFF, 0xD8, 0xFF),
            // APP1 que dice 32 bytes de carga y solo trae cuatro.
            bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x20) + bytes('E'.code, 'x'.code, 'i'.code, 'f'.code),
            // Longitud cero o uno: no puede avanzar.
            bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x00),
            bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x01),
            // Marcador sin longitud de sobra: 0xFF hasta el final.
            bytes(0xFF, 0xD8) + ByteArray(200) { 0xFF.toByte() },
            // Marcador desconocido con longitud enorme.
            bytes(0xFF, 0xD8, 0xFF, 0xE2, 0xFF, 0xFF),
            // SOS y datos, pero sin EOI: truncado.
            bytes(0xFF, 0xD8) + sos + bytes(0x00, 0x11, 0x22),
            // Cabecera completa y nada más.
            bytes(0xFF, 0xD8) + jpegSegment(0xE0, ByteArray(14)),
            // SOI y EOI sin SOS: no hay imagen.
            bytes(0xFF, 0xD8, 0xFF, 0xD9),
        )
        for (case in cases) {
            assertTrue(ImageMetadata.hasMetadata(case))
        }
    }

    @Test
    fun `datos despues del eoi son metadato`() {
        assertTrue(ImageMetadata.hasMetadata(jpeg(trailing = bytes(1, 2, 3))))
        // Un solo byte de relleno tampoco vale: app solo sube su JPEG recodificado.
        assertTrue(ImageMetadata.hasMetadata(jpeg(trailing = bytes(0))))
    }

    @Test
    fun `el tope de segmentos sin llegar al final falla cerrado`() {
        val segment = jpegSegment(0xE0, ByteArray(0))
        // 1022 APP0 + SOS + EOI: 1024 marcadores y el último es EOI.
        val atLimit = (1..1022).fold(bytes(0xFF, 0xD8)) { acc, _ -> acc + segment } + sos + eoi
        assertFalse(ImageMetadata.hasMetadata(atLimit))
        val tooMany = (1..1100).fold(bytes(0xFF, 0xD8)) { acc, _ -> acc + segment } + sos + eoi
        assertTrue(ImageMetadata.hasMetadata(tooMany))
    }

    // ------------------------------------------------------------------ PNG

    @Test
    fun `un png completo sin metadatos es valido`() {
        assertFalse(ImageMetadata.hasMetadata(png(pngHeader(), pngIend)))
        // «eXIf» dentro de los datos de otro chunk no es el chunk eXIf.
        assertFalse(
            ImageMetadata.hasMetadata(
                png(pngHeader(), pngChunk("IDAT", ascii("basura eXIf basura")), pngIend),
            ),
        )
        // El nombre es sensible a mayúsculas: «exif» no es el chunk de la spec.
        assertFalse(ImageMetadata.hasMetadata(png(pngHeader(), pngChunk("exif", ByteArray(4)), pngIend)))
    }

    @Test
    fun `un png con exif o texto lleva metadatos`() {
        for (type in listOf("eXIf", "iTXt", "tEXt", "zTXt")) {
            assertTrue(
                type,
                ImageMetadata.hasMetadata(png(pngHeader(), pngChunk(type, ByteArray(0)), pngIend)),
            )
        }
        // El XMP de PNG viaja en un iTXt: cualquier texto ya es metadato.
        assertTrue(
            ImageMetadata.hasMetadata(
                png(
                    pngHeader(),
                    pngChunk("iTXt", ascii("XML:com.adobe.xmp\u0000<x:xmpmeta/>")),
                    pngIend,
                ),
            ),
        )
    }

    @Test
    fun `un png truncado con longitudes absurdas o sin iend falla cerrado`() {
        val cases = listOf(
            // Firma completa y nada más: PNG truncado.
            pngSignature,
            pngSignature + bytes(0x00, 0x00, 0x01, 0x00),
            // Longitud de chunk de 0xFFFFFFFF con un tipo cualquiera.
            pngSignature + bytes(0xFF, 0xFF, 0xFF, 0xFF) + ascii("IDAT") + ByteArray(8),
            // Cabecera de chunk corta.
            pngSignature + bytes(0x00, 0x00, 0x00, 0x0D, 'I'.code, 'H'.code),
            // Chunk que declara 13 bytes y solo trae 4.
            pngSignature + bytes(0x00, 0x00, 0x00, 0x0D) + ascii("IHDR") + ByteArray(4),
            // Sin IEND: el fichero termina tras un chunk.
            png(pngHeader()),
            // IEND con datos: malformado.
            png(pngHeader(), pngChunk("IEND", ByteArray(1))),
            // Un PNG tiene que empezar por IHDR.
            pngSignature + pngIend,
            // IHDR con una longitud que no es la de la spec.
            png(pngChunk("IHDR", ByteArray(12)), pngIend),
        )
        for (case in cases) {
            assertTrue(ImageMetadata.hasMetadata(case))
        }
    }

    @Test
    fun `datos despues del iend son metadato`() {
        val valid = png(pngHeader(), pngIend)
        assertTrue(ImageMetadata.hasMetadata(valid + bytes(0)))
        assertTrue(ImageMetadata.hasMetadata(valid + ByteArray(100)))
    }

    @Test
    fun `el tope de chunks png sin llegar a iend falla cerrado`() {
        val chunk = pngChunk("IDAT", ByteArray(0))
        // IHDR primero (obligatorio) y luego miles de chunks sin IEND.
        assertTrue(ImageMetadata.hasMetadata(png(pngHeader(), *Array(4_100) { chunk })))
    }

    // ----------------------------------------------------------------- WebP

    @Test
    fun `un webp completo sin metadatos es valido`() {
        assertFalse(ImageMetadata.hasMetadata(webp(webpChunk("VP8 ", bytes(1, 2, 3, 4, 5)))))
        // «EXIF» dentro de los datos de otro chunk no cuenta.
        assertFalse(ImageMetadata.hasMetadata(webp(webpChunk("VP8 ", ascii("dentro EXIF no vale")))))
        // VP8X sin los bits de EXIF ni XMP.
        assertFalse(
            ImageMetadata.hasMetadata(
                webp(
                    webpChunk("VP8X", bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                    webpChunk("VP8 ", bytes(1, 2)),
                ),
            ),
        )
    }

    @Test
    fun `un webp con exif xmp o bits de vp8x lleva metadatos`() {
        assertTrue(ImageMetadata.hasMetadata(webp(webpChunk("EXIF", ByteArray(0)))))
        assertTrue(ImageMetadata.hasMetadata(webp(webpChunk("XMP ", ByteArray(0)))))
        // Los bits 0x08 (EXIF) y 0x04 (XMP) de VP8X avisan aunque falte el chunk.
        for (flags in listOf(0x08, 0x04, 0x0C)) {
            assertTrue(
                flags.toString(),
                ImageMetadata.hasMetadata(
                    webp(
                        webpChunk("VP8X", bytes(flags, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                        webpChunk("VP8 ", bytes(1, 2)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `un webp truncado o con bytes fuera del riff falla cerrado`() {
        val valid = webp(webpChunk("VP8 ", bytes(1, 2, 3, 4)))
        assertFalse(ImageMetadata.hasMetadata(valid))
        // Vídeo incrustado detrás del contenedor (motion photo).
        assertTrue(ImageMetadata.hasMetadata(valid + bytes(1, 2)))
        // Contenedor cortado: el tamaño declarado ya no cuadra.
        assertTrue(ImageMetadata.hasMetadata(valid.copyOf(valid.size - 1)))
        // Longitud de chunk 0xFFFFFFFF.
        assertTrue(
            ImageMetadata.hasMetadata(
                ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP") + ascii("VP8 ") +
                    bytes(0xFF, 0xFF, 0xFF, 0xFF),
            ),
        )
        // Cabecera RIFF sin chunks: el tamaño no cuadra con el fichero.
        assertTrue(ImageMetadata.hasMetadata(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")))
        // Tamaño correcto pero sin ningún chunk: no es una imagen.
        assertTrue(ImageMetadata.hasMetadata(ascii("RIFF") + bytes(4, 0, 0, 0) + ascii("WEBP")))
    }

    @Test
    fun `el tope de chunks webp sin llegar al final falla cerrado`() {
        val chunk = webpChunk("VP8 ", ByteArray(0))
        assertTrue(ImageMetadata.hasMetadata(webp(*Array(4_100) { chunk })))
    }

    // ---------------------------------------------------------------- varios

    @Test
    fun `otros formatos no son metadatos y el alias obsoleto hace lo mismo`() {
        val cases = listOf(
            ByteArray(0),
            ByteArray(1),
            // Una firma a medias no identifica el formato: uploadImage ya lo
            // rechaza antes por los bytes mágicos.
            bytes(0x89, 0x50, 0x4E),
            ascii("GIF89a...."),
            ascii("<svg xmlns='http://www.w3.org/2000/svg'/>"),
            ascii("RIFF") + bytes(4, 0, 0, 0) + ascii("WAVE") + ByteArray(8),
        )
        for (case in cases) {
            assertFalse(ImageMetadata.hasMetadata(case))
        }
        val valid = png(pngHeader(), pngIend)
        assertFalse(ImageMetadata.hasExif(valid))
        assertTrue(ImageMetadata.hasExif(jpeg(jpegSegment(0xE1, exifPayload))))
    }
}
