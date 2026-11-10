package io.github.garfieldrockero.gastronomicon.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del saneador de HTML. Se centran en la evasión: lo que importa es
 * que ningún script, enlace ni atributo llegue a la salida, venga como venga
 * escrito, y que la entrada hostil o enorme no cuelgue ni reviente.
 */
class HtmlSanitizerTest {

    private fun plain(html: String?): String = HtmlSanitizer.sanitize(html).toPlainText()

    private fun text(s: String, bold: Boolean = false, italic: Boolean = false, underline: Boolean = false) =
        InlineSpan.Text(s, bold, italic, underline)

    private fun paragraph(vararg spans: InlineSpan) = HtmlBlock.Paragraph(spans.toList())

    // --- Estructura básica ---

    @Test
    fun `una entrada nula o vacia da un resultado vacio`() {
        assertEquals(SanitizedHtml.EMPTY, HtmlSanitizer.sanitize(null))
        assertEquals(SanitizedHtml.EMPTY, HtmlSanitizer.sanitize(""))
    }

    @Test
    fun `un parrafo simple`() {
        assertEquals(listOf(paragraph(text("Hola"))), HtmlSanitizer.sanitize("<p>Hola</p>").blocks)
    }

    @Test
    fun `texto sin etiquetas es un parrafo`() {
        assertEquals(listOf(paragraph(text("Hola mundo"))), HtmlSanitizer.sanitize("Hola mundo").blocks)
    }

    @Test
    fun `dos parrafos se separan`() {
        assertEquals("uno\n\ndos", plain("<p>uno</p><p>dos</p>"))
    }

    @Test
    fun `negrita cursiva y subrayado`() {
        val blocks = HtmlSanitizer.sanitize("Hola <b>a</b><strong>b</strong><i>c</i><em>d</em><u>e</u>").blocks
        assertEquals(
            listOf(
                paragraph(
                    text("Hola "),
                    text("ab", bold = true),
                    text("cd", italic = true),
                    text("e", underline = true),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `estilos anidados se combinan`() {
        val blocks = HtmlSanitizer.sanitize("<b><i>x</i></b>").blocks
        assertEquals(listOf(paragraph(text("x", bold = true, italic = true))), blocks)
    }

    @Test
    fun `las etiquetas en mayusculas valen`() {
        val blocks = HtmlSanitizer.sanitize("<P>Hola <B>x</B></P>").blocks
        assertEquals(listOf(paragraph(text("Hola "), text("x", bold = true))), blocks)
    }

    @Test
    fun `saltos de linea en sus formas`() {
        val expected = listOf(paragraph(text("a"), InlineSpan.LineBreak, text("b")))
        assertEquals(expected, HtmlSanitizer.sanitize("a<br>b").blocks)
        assertEquals(expected, HtmlSanitizer.sanitize("a<br/>b").blocks)
        assertEquals(expected, HtmlSanitizer.sanitize("a<BR />b").blocks)
        assertEquals(expected, HtmlSanitizer.sanitize("a</br>b").blocks)
    }

    @Test
    fun `como mucho dos saltos seguidos y ninguno en los extremos`() {
        assertEquals(
            listOf(paragraph(text("a"), InlineSpan.LineBreak, InlineSpan.LineBreak, text("b"))),
            HtmlSanitizer.sanitize("a<br><br><br><br>b").blocks,
        )
        assertEquals(
            listOf(paragraph(text("x"))),
            HtmlSanitizer.sanitize("<br><br>x<br><br>").blocks,
        )
    }

    @Test
    fun `div y span`() {
        assertEquals("a\n\nb\n\nc", plain("<div>a<div>b</div>c</div>"))
        assertEquals("hi", plain("<span style=\"color:red\">hi</span>"))
    }

    @Test
    fun `texto suelto antes y despues de un parrafo`() {
        assertEquals("x\n\ny\n\nz", plain("x<p>y</p>z"))
    }

    @Test
    fun `los espacios se colapsan y se recortan`() {
        assertEquals("a b", plain("<p>  a \n\n  b  </p>"))
    }

    @Test
    fun `los parrafos vacios se descartan`() {
        assertTrue(HtmlSanitizer.sanitize("<p></p><p>&nbsp;</p><div> </div>").blocks.isEmpty())
    }

    // --- Listas ---

    @Test
    fun `lista no ordenada`() {
        val blocks = HtmlSanitizer.sanitize("<ul><li>a</li><li>b</li></ul>").blocks
        assertEquals(
            listOf(HtmlBlock.UnorderedList(listOf(ListItem(listOf(text("a"))), ListItem(listOf(text("b")))))),
            blocks,
        )
    }

    @Test
    fun `lista ordenada con li sin cerrar`() {
        val blocks = HtmlSanitizer.sanitize("<ol><li>a<li>b</ol>").blocks
        assertEquals(
            listOf(HtmlBlock.OrderedList(listOf(ListItem(listOf(text("a"))), ListItem(listOf(text("b")))))),
            blocks,
        )
    }

    @Test
    fun `listas anidadas`() {
        val blocks = HtmlSanitizer.sanitize("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>").blocks
        val inner = HtmlBlock.UnorderedList(listOf(ListItem(listOf(text("b")))))
        assertEquals(
            listOf(
                HtmlBlock.UnorderedList(
                    listOf(ListItem(listOf(text("a")), listOf(inner)), ListItem(listOf(text("c")))),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `texto de lista en texto plano`() {
        assertEquals("1. uno\n2. dos", plain("<ol><li>uno</li><li>dos</li></ol>"))
        assertEquals("• a\n  • b", plain("<ul><li>a<ul><li>b</li></ul></li></ul>"))
    }

    @Test
    fun `parrafo antes y despues de una lista`() {
        assertEquals("antes\n\n• a\n\ndespues", plain("<p>antes</p><ul><li>a</li></ul><p>despues</p>"))
    }

    @Test
    fun `una lista vacia desaparece`() {
        assertTrue(HtmlSanitizer.sanitize("<ul></ul><ol> <li></li> </ol>").blocks.isEmpty())
    }

    @Test
    fun `un li suelto fuera de una lista no revienta`() {
        assertEquals("a\n\nb", plain("<li>a</li><li>b</li>"))
    }

    @Test
    fun `parrafos sin cerrar no se anidan`() {
        val result = HtmlSanitizer.sanitize("<p>x".repeat(1000))
        assertFalse(result.truncated)
        assertEquals(1000, result.blocks.size)
    }

    // --- Contenido descartado ---

    @Test
    fun `script style e iframe se descartan con su contenido`() {
        assertEquals("a\n\nb", plain("<p>a</p><script>alert(1)</script><p>b</p>"))
        assertEquals("ab", plain("a<style>p{color:red}</style>b"))
        assertEquals("ab", plain("a<iframe src=\"https://x\">texto</iframe>b"))
    }

    @Test
    fun `object embed svg math form e input`() {
        assertEquals("fin", plain("<object><param name=x>texto</object>fin"))
        assertEquals("ab", plain("a<embed src=x>b"))
        assertEquals("after", plain("<svg><script>alert(1)</script><text>hi</text></svg>after"))
        assertEquals("after", plain("<math><mi xlink:href=\"javascript:alert(1)\">x</mi></math>after"))
        assertEquals("ab", plain("a<form action=x><input name=y>dentro</form>b"))
        assertEquals("ab", plain("a<input type=text value=\"</p>\">b"))
    }

    @Test
    fun `svg anidado dentro de svg descarta hasta el cierre exterior`() {
        assertEquals("fin", plain("<svg><svg>a</svg>b</svg>fin"))
    }

    @Test
    fun `script en distintas formas de escribirlo`() {
        assertEquals("ok", plain("ok<ScRiPt>alert(1)</sCrIpT>"))
        assertEquals("ok", plain("ok<script >alert(1)</script >"))
        assertEquals("ok", plain("ok<script\n>alert(1)</script\n>"))
        assertEquals("ok", plain("ok<script>alert(1)"))
        assertEquals("ok", plain("ok<script src=//evil/x.js></script>"))
    }

    @Test
    fun `un cierre falso de script no termina el bloque`() {
        assertEquals("z", plain("<script>x</scriptx>y</script>z"))
    }

    @Test
    fun `textarea title noscript y xmp son texto crudo`() {
        assertEquals("y", plain("<textarea><p>x</p></textarea>y"))
        assertEquals("y", plain("<title>t</title>y"))
        assertEquals("y", plain("<noscript><img src=x></noscript>y"))
        assertEquals("y", plain("<xmp><b>x</b></xmp>y"))
    }

    @Test
    fun `comentarios y declaraciones`() {
        assertEquals("ab", plain("a<!-- <script>x</script> -->b"))
        assertEquals("a", plain("a<!-- sin cerrar <b>b</b>"))
        assertEquals("ab", plain("a<!doctype html>b"))
        assertEquals("ab", plain("a<?xml version=\"1.0\"?>b"))
    }

    // --- Enlaces, atributos y URLs ---

    @Test
    fun `los enlaces quedan como texto sin direccion`() {
        val result = plain("<a href=\"javascript:alert(1)\">enlace</a> y <a href=http://x.com>otro</a>")
        assertEquals("enlace y otro", result)
    }

    @Test
    fun `imagenes y otras etiquetas desconocidas no dejan rastro`() {
        assertEquals("", plain("<IMG SRC=x onerror=alert(1)>"))
        assertEquals("", plain("<img src=\"javascript:alert(1)\">"))
        assertEquals("", plain("<a href=\"  jav&#x09;ascript:alert(1)\"></a>"))
        assertEquals("x", plain("<font color=red>x</font>"))
    }

    @Test
    fun `atributos con y sin comillas se ignoran`() {
        assertEquals("hola", plain("<p onclick=\"alert(1)\" style=\"x\">hola</p>"))
        assertEquals("hola", plain("<p onclick=alert(1)>hola</p>"))
        assertEquals("hola", plain("<p/onclick=alert(1)>hola</p>"))
        assertEquals("hola", plain("<p onclick = 'a' >hola</p>"))
    }

    @Test
    fun `un mayor que dentro de comillas no cierra la etiqueta`() {
        assertEquals("hola", plain("<p title=\"a>b\">hola</p>"))
        assertEquals("hola", plain("<p title='a>b'>hola</p>"))
    }

    @Test
    fun `la salida nunca conserva javascript ni data ni href`() {
        val inputs = listOf(
            "<a href=\"javascript:alert(1)\">x</a>",
            "<a href=\"data:text/html,<script>alert(1)</script>\">x</a>",
            "<a href=\"vbscript:x\">x</a>",
            "<p><a HREF='JaVaScRiPt:alert(1)'>x</a></p>",
        )
        for (input in inputs) {
            val out = plain(input).lowercase()
            assertFalse(input, out.contains("javascript"))
            assertFalse(input, out.contains("data:"))
            assertFalse(input, out.contains("href"))
            assertFalse(input, out.contains("alert"))
        }
    }

    // --- Etiquetas mal formadas ---

    @Test
    fun `etiquetas sin cerrar`() {
        assertEquals(
            listOf(paragraph(text("hola "), text("mundo", bold = true))),
            HtmlSanitizer.sanitize("<p>hola <b>mundo").blocks,
        )
        assertEquals("holax", plain("<p>hola</b></i>x"))
    }

    @Test
    fun `una etiqueta sin terminar se descarta`() {
        assertEquals("hola", plain("<p>hola <b"))
        assertEquals("hola", plain("<p>hola <b class=\"x"))
        assertEquals("hola", plain("hola <script"))
    }

    @Test
    fun `un menor que suelto es texto`() {
        assertEquals("a < b", plain("a < b"))
        assertEquals("1<2", plain("1<2"))
        assertEquals("hola<", plain("<p>hola<"))
    }

    @Test
    fun `etiquetas partidas no forman un script`() {
        val out = plain("<scr<script>ipt>alert(1)</script>")
        assertFalse(out.contains("<"))
        assertEquals("<", plain("<<script>alert(1)</script>"))
    }

    @Test
    fun `cierres cruzados`() {
        assertEquals("abc", plain("<b>a<i>b</b>c</i>"))
    }

    @Test
    fun `un cierre sin apertura se ignora`() {
        assertEquals("a", plain("</p></div></li></ul>a"))
    }

    // --- Entidades ---

    @Test
    fun `entidades basicas`() {
        assertEquals("Tom & Jerry", plain("Tom &amp; Jerry"))
        assertEquals("\"x\" 'y'", plain("&quot;x&quot; &apos;y&apos;"))
        assertEquals("éñ", plain("&eacute;&ntilde;"))
        assertEquals("a b", plain("a&nbsp;&nbsp;b"))
    }

    @Test
    fun `una entidad que decodifica a etiqueta es texto y no etiqueta`() {
        assertEquals("<b>x</b>", plain("&lt;b&gt;x&lt;/b&gt;"))
        assertEquals("<script>alert(1)</script>", plain("&#60;script&#62;alert(1)&#60;/script&#62;"))
        val blocks = HtmlSanitizer.sanitize("&lt;b&gt;x").blocks
        assertEquals(listOf(paragraph(text("<b>x"))), blocks)
    }

    @Test
    fun `entidades dobles se decodifican una sola vez`() {
        assertEquals("&lt;", plain("&amp;lt;"))
        assertEquals("&amp;", plain("&amp;amp;"))
        assertEquals("&#60;", plain("&amp;#60;"))
    }

    @Test
    fun `entidades numericas`() {
        assertEquals("AB", plain("&#x41;&#66;"))
        assertEquals("AB", plain("&#X41;B"))
        assertEquals("\uD83D\uDE00", plain("&#128512;"))
    }

    @Test
    fun `entidades numericas peligrosas se descartan`() {
        assertEquals("x", plain("&#0;x"))
        assertEquals("x", plain("&#x110000;x"))
        assertEquals("x", plain("&#xD800;x"))
        assertEquals("x", plain("&#8238;x"))
        assertEquals("x", plain("&#1;&#8;&#127;&#x9F;x"))
        // Demasiados dígitos: no se intenta interpretar, queda como texto.
        assertEquals("&#99999999999;x", plain("&#99999999999;x"))
    }

    @Test
    fun `lo que no es una entidad se deja como esta`() {
        assertEquals("&unknown;", plain("&unknown;"))
        assertEquals("&amp", plain("&amp"))
        assertEquals("&#;", plain("&#;"))
        assertEquals("&#x;", plain("&#x;"))
        assertEquals("a & b", plain("a & b"))
        assertEquals("&", plain("&"))
    }

    // --- Caracteres peligrosos ---

    @Test
    fun `los nulos se quitan y no esconden etiquetas`() {
        assertEquals("ab", plain("a\u0000b"))
        assertEquals("ok", plain("ok<scr\u0000ipt>alert(1)</scr\u0000ipt>"))
    }

    @Test
    fun `control bidi e invisibles fuera`() {
        assertEquals("ab", plain("a\u0001b"))
        assertEquals("ab", plain("a\u202Eb"))
        assertEquals("ab", plain("a\u2066b\u2069"))
        assertEquals("ab", plain("a\u200Bb"))
        assertEquals("ab", plain("\uFEFFab"))
        assertEquals("ab", plain("a\u00ADb"))
    }

    // --- Límites ---

    @Test
    fun `texto enorme se trunca`() {
        val result = HtmlSanitizer.sanitize("a".repeat(HtmlSanitizer.MAX_INPUT_LENGTH + 5_000))
        assertTrue(result.truncated)
        assertEquals(HtmlSanitizer.MAX_INPUT_LENGTH, result.toPlainText().length)
    }

    @Test
    fun `el recorte no deja un sustituto suelto`() {
        val input = "a".repeat(HtmlSanitizer.MAX_INPUT_LENGTH - 1) + "\uD83D\uDE00"
        val result = HtmlSanitizer.sanitize(input)
        assertTrue(result.truncated)
        assertFalse(result.toPlainText().any { Character.isSurrogate(it) })
    }

    @Test
    fun `una entrada justo en el limite no se marca como truncada`() {
        assertFalse(HtmlSanitizer.sanitize("a".repeat(HtmlSanitizer.MAX_INPUT_LENGTH)).truncated)
    }

    @Test
    fun `anidado en el limite se conserva`() {
        val depth = HtmlSanitizer.MAX_DEPTH
        val result = HtmlSanitizer.sanitize("<div>".repeat(depth) + "texto" + "</div>".repeat(depth))
        assertFalse(result.truncated)
        assertEquals("texto", result.toPlainText())
    }

    @Test
    fun `anidado por encima del limite cae a texto plano truncado`() {
        val depth = HtmlSanitizer.MAX_DEPTH + 1
        val result = HtmlSanitizer.sanitize("<div>".repeat(depth) + "texto" + "</div>".repeat(depth))
        assertTrue(result.truncated)
        assertEquals(listOf(paragraph(text("texto"))), result.blocks)
    }

    @Test(timeout = 10_000)
    fun `anidado profundo de miles de niveles`() {
        val n = 15_000 // "<div>" * n + texto debe caber en MAX_INPUT_LENGTH
        for (tag in listOf("div", "b", "ul", "span")) {
            val result = HtmlSanitizer.sanitize("<$tag>".repeat(n) + "texto")
            assertTrue(tag, result.truncated)
            assertEquals(tag, "texto", result.toPlainText())
        }
    }

    @Test
    fun `el texto plano de reserva no contiene scripts ni etiquetas`() {
        val input = "<div>".repeat(HtmlSanitizer.MAX_DEPTH + 5) +
            "<script>alert(1)</script><a href=\"javascript:x\">enlace</a>"
        val result = HtmlSanitizer.sanitize(input)
        assertTrue(result.truncated)
        assertEquals("enlace", result.toPlainText())
    }

    @Test(timeout = 10_000)
    fun `entradas patologicas terminan`() {
        val n = HtmlSanitizer.MAX_INPUT_LENGTH
        val inputs = listOf(
            "<".repeat(n),
            "&".repeat(n),
            "&#".repeat(n / 2),
            "&a".repeat(n / 2),
            "<a ".repeat(n / 3),
            "<!--".repeat(n / 4),
            "</".repeat(n / 2),
            "<p a=\"".repeat(n / 7),
            "<script>".repeat(n / 8),
            "<svg>".repeat(n / 5),
            "<li>".repeat(n / 4),
            "<p><b>".repeat(n / 6),
            "<ul>".repeat(n / 4),
            "&#x".repeat(n / 3) + "0".repeat(1000),
        )
        for (input in inputs) {
            val out = HtmlSanitizer.sanitize(input)
            assertTrue(out.toPlainText().length <= HtmlSanitizer.MAX_INPUT_LENGTH * 2)
        }
    }

    /** Falla si [block] tarda un segundo o más: detecta concatenaciones cuadráticas. */
    private fun assertFast(label: String, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("$label tardó $millis ms (límite 1000)", millis < 1_000)
    }

    @Test
    fun `cien mil signos menor seguidos se sanean en menos de un segundo`() {
        val input = "<".repeat(HtmlSanitizer.MAX_INPUT_LENGTH)
        assertFast("100000 '<'") {
            val out = HtmlSanitizer.sanitize(input)
            // Todos los tramos contiguos con el mismo estilo se unen en uno.
            assertEquals(1, (out.blocks.single() as HtmlBlock.Paragraph).spans.size)
            assertEquals(input, out.toPlainText())
        }
    }

    @Test
    fun `cien mil entidades seguidas se sanean en menos de un segundo`() {
        val input = "&amp;".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 5)
        assertFast("&amp; repetido") {
            assertEquals("&".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 5), HtmlSanitizer.sanitize(input).toPlainText())
        }
    }

    @Test
    fun `texto mezclado con menores y estilos se sanea en menos de un segundo`() {
        val input = "a < b &amp; <b>c</b> < <i>d</i> <br>".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 36)
        assertFast("texto mezclado") { HtmlSanitizer.sanitize(input) }
        val onlyLess = "< ".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 2)
        assertFast("menores con espacios") { HtmlSanitizer.sanitize(onlyLess) }
    }

    @Test
    fun `comentarios repetidos se saltan en menos de un segundo`() {
        val input = "<!--x-->".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 8)
        assertFast("comentarios repetidos") { assertEquals("", HtmlSanitizer.sanitize(input).toPlainText()) }
        val bang = "<!--x--!>".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 9)
        assertFast("comentarios con --!> repetidos") { assertEquals("", HtmlSanitizer.sanitize(bang).toPlainText()) }
        val sinCierre = "<!--".repeat(HtmlSanitizer.MAX_INPUT_LENGTH / 4)
        assertFast("comentarios sin cierre") { HtmlSanitizer.sanitize(sinCierre) }
    }

    @Test
    fun `el tope de marcas combinantes no se esquiva con etiquetas vacias entre medias`() {
        val out = plain("a" + "\u0301<b></b>".repeat(20))
        assertEquals(4, out.count { it == '\u0301' })
        val conSpan = plain("a" + "\u0301<span></span>".repeat(20))
        assertEquals(4, conSpan.count { it == '\u0301' })
    }

    @Test
    fun `los tramos contiguos con el mismo estilo se unen y los distintos no`() {
        val out = HtmlSanitizer.sanitize("a < b <b>c</b>< d")
        assertEquals(
            listOf(paragraph(text("a < b "), text("c", bold = true), text("< d"))),
            out.blocks,
        )
    }

    @Test
    fun `comentarios degenerados se cierran como en html5`() {
        assertEquals("ab", plain("a<!-->b"))
        assertEquals("ab", plain("a<!--->b"))
        assertEquals("ab", plain("a<!---->b"))
        assertEquals("ab", plain("a<!-- x --!>b"))
        assertEquals("ab", plain("a<!-- x --->b"))
        assertEquals("a", plain("a<!-- x --!"))
        assertEquals("a", plain("a<!--"))
        // Lo que sigue a un comentario vacío sigue pasando por el saneado.
        assertEquals("ab", plain("a<!--><script>x</script>b"))
    }

    @Test
    fun `la salida de un html hostil no contiene etiquetas ejecutables`() {
        val hostile = "<p onmouseover=alert(1)>a</p><IFRAME SRC=javascript:alert(1)></IFRAME>" +
            "<svg onload=alert(1)><b>x</b></svg><a href=javascript:alert(1)>b</a>" +
            "<img src=x onerror=alert(1)><body onload=alert(1)>c"
        val out = plain(hostile)
        assertEquals("a\n\nbc", out)
    }

    // --- Imágenes y vídeo descartados ---

    @Test
    fun `sin imagenes ni video no se marca nada`() {
        assertFalse(HtmlSanitizer.sanitize("<p>Hola <b>mundo</b></p><ul><li>uno</li></ul>").omittedMedia)
        // Un enlace tampoco es una imagen: queda como texto y no enciende el aviso.
        assertFalse(HtmlSanitizer.sanitize("<a href=\"https://ejemplo.org\">enlace</a>").omittedMedia)
        assertFalse(HtmlSanitizer.sanitize(null).omittedMedia)
        assertFalse(SanitizedHtml.EMPTY.omittedMedia)
    }

    @Test
    fun `una imagen descartada se anota`() {
        val result = HtmlSanitizer.sanitize("<p>Paso</p><img src=\"https://ejemplo.org/foto.jpg\" alt=\"x\">")
        assertTrue(result.omittedMedia)
        // Lo que se descarta no cambia: la imagen no deja texto ni atributos.
        assertEquals("Paso", result.toPlainText())
    }

    @Test
    fun `un iframe de video se anota`() {
        val result = HtmlSanitizer.sanitize(
            "<p>Mira</p><iframe src=\"https://www.youtube.com/embed/abc\"></iframe>",
        )
        assertTrue(result.omittedMedia)
        assertEquals("Mira", result.toPlainText())
    }

    @Test
    fun `las demas etiquetas de imagen y video tambien se anotan`() {
        val inputs = listOf(
            "<video src=\"v.mp4\"></video>",
            "<audio src=\"a.mp3\"></audio>",
            "<picture><source srcset=\"x.jpg\"></picture>",
            "<embed src=\"x.swf\">",
            "<object data=\"x.swf\"></object>",
            "<svg><circle r=\"1\"/></svg>",
            "<IMG SRC=\"x.png\">",
        )
        for (input in inputs) {
            assertTrue(input, HtmlSanitizer.sanitize(input).omittedMedia)
        }
    }

    @Test
    fun `una imagen dentro de un contenedor que se tira entero tambien se anota`() {
        // El <form> se descarta con todo su contenido; la imagen sigue siendo
        // algo que la persona no va a ver.
        assertTrue(HtmlSanitizer.sanitize("<form><img src=\"x.png\"></form>").omittedMedia)
    }

    @Test
    fun `un img escrito dentro de un script no es una imagen`() {
        // El contenido de script es texto crudo: no hay etiqueta que anotar.
        assertFalse(
            HtmlSanitizer.sanitize("<script>var s = \"<img src=x>\";</script>").omittedMedia,
        )
        // Ni un texto escapado que se vea como etiqueta.
        assertFalse(HtmlSanitizer.sanitize("&lt;img src=x&gt;").omittedMedia)
    }

    @Test
    fun `lo que se queda fuera del recorte no se anota`() {
        // La imagen está más allá del tope de entrada: no se ha leído.
        val input = "a".repeat(HtmlSanitizer.MAX_INPUT_LENGTH) + "<img src=\"x.png\">"
        val result = HtmlSanitizer.sanitize(input)
        assertTrue(result.truncated)
        assertFalse(result.omittedMedia)
    }

    @Test
    fun `el aviso sobrevive a la caida a texto plano por anidado excesivo`() {
        // El primer recorrido se corta al desbordar el anidado, así que la
        // imagen solo la ve el segundo: la marca se suma de los dos.
        val input = "<div>".repeat(HtmlSanitizer.MAX_DEPTH + 2) + "<img src=\"x.png\">texto"
        val result = HtmlSanitizer.sanitize(input)
        assertTrue(result.truncated)
        assertTrue(result.omittedMedia)
        assertEquals("texto", result.toPlainText())
    }
}
