package io.github.garfieldrockero.gastronomicon.core.client

/**
 * Reglas del texto que se manda a Mealie en `POST /api/recipes/create/html-or-json`
 * (el JSON de `schema.org/Recipe` o el HTML de una receta).
 *
 * **Aquí no se sanea ni se interpreta el contenido**, a propósito: lo elige la
 * persona y quien lo entiende es el servidor (`ScrapeRecipeData.data`). Solo se
 * comprueba que sea texto que se pueda enviar y que no pase del tope, porque un
 * rechazo local es más rápido y más claro que un 422, y porque un texto enorme
 * no debe llegar ni a la red ni a la memoria del móvil.
 *
 * El tope se mide en **bytes UTF-8**, no en caracteres: una receta con tildes o
 * emoji ocupa más de lo que sugiere su longitud en `Char`, y dos textos «del
 * mismo tamaño» pueden ocupar el doble el uno que el otro.
 */
internal object ImportData {

    /**
     * El primer motivo por el que [data] no se puede enviar, o `null` si vale.
     *
     * [maxBytes] es el tope en bytes UTF-8 que pone quien llama
     * (`MealieClient.MAX_IMPORT_DATA_BYTES`, 1 MiB): una receta entera —el HTML
     * de una web con su JSON-LD incluido— cabe de sobra; lo que pasa de ahí es
     * un fichero equivocado (una web entera, un ZIP, un volcado) y no una
     * receta.
     *
     * Se recorre una sola vez y sin crear copias: el texto puede ser de 1 MiB y
     * no hay razón para tenerlo dos veces en memoria. Se comprueba en este
     * orden, que es el que da el motivo más útil:
     *
     * 1. vacío o solo espacios ([InputProblem.EmptyData]);
     * 2. carácter de control o surrogado suelto ([InputProblem.DataNotText]);
     * 3. pasado de tope ([InputProblem.DataTooLarge]), en cuanto se sabe.
     */
    fun problemOf(data: String, maxBytes: Int): InputProblem? {
        // `all` sobre una cadena vacía da true: nada que enviar.
        if (data.all { it.isWhitespace() || Character.isSpaceChar(it) }) {
            return InputProblem.EmptyData
        }
        var bytes = 0
        var index = 0
        while (index < data.length) {
            val current = data[index]
            if (Character.isHighSurrogate(current)) {
                // Un par bien formado son 4 bytes UTF-8; suelto, no se puede
                // codificar (ni enviar: el JSON saldría con un «?» o roto).
                val next = if (index + 1 < data.length) data[index + 1] else null
                if (next == null || !Character.isLowSurrogate(next)) return InputProblem.DataNotText
                bytes += 4
                index += 2
            } else if (Character.isLowSurrogate(current)) {
                // Un subrogado bajo sin su pareja delante.
                return InputProblem.DataNotText
            } else {
                if (isForbiddenControl(current)) return InputProblem.DataNotText
                bytes += when {
                    current.code < 0x80 -> 1
                    current.code < 0x800 -> 2
                    else -> 3
                }
                index += 1
            }
            if (bytes > maxBytes) return InputProblem.DataTooLarge
        }
        return null
    }

    /**
     * Controles que no viajan: todo el rango de control C0 salvo tabulador,
     * salto de línea y retorno de carro (los tres son formato legítimo en HTML
     * y en JSON), más DEL y los controles C1.
     *
     * El NUL cae en el primer rango: no hay motivo para mandarlo y algunos
     * parsers cortan el texto ahí.
     */
    private fun isForbiddenControl(c: Char): Boolean {
        val code = c.code
        if (code < 0x20) return c != '\t' && c != '\n' && c != '\r'
        return code == 0x7F || code in 0x80..0x9F
    }
}
