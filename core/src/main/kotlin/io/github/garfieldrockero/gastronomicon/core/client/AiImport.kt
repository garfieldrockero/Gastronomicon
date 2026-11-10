package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.SafeUrl
import io.github.garfieldrockero.gastronomicon.core.network.MultipartBody
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageMetadata
import java.util.Locale

/**
 * Una foto para importar una receta con la IA de Mealie
 * (`POST /api/recipes/create/ai`).
 *
 * [bytes] tiene que ser un JPEG, PNG o WebP **ya recodificado y sin metadatos**
 * por `app/` (los EXIF fuera), como en la subida de la foto de una receta. Aun
 * así el núcleo lo vuelve a comprobar entero antes de enviarlo y rechaza lo que
 * no cumpla: aquí no se confía en quien llama.
 *
 * [extension] es `jpeg`, `png` o `webp`; se normaliza (espacios y mayúsculas)
 * igual que en `MealieClient.uploadImage`.
 *
 * [toString] no enseña los bytes ni la extensión: una foto no va a un log.
 */
class AiImage(val bytes: ByteArray, val extension: String) {
    override fun toString(): String = "AiImage(bytes=${bytes.size})"
}

/**
 * Una foto que ya ha pasado las comprobaciones, con el tipo que dicen sus
 * primeros bytes (no la extensión que haya escrito quien llama).
 */
internal class AiImageData(val type: ImageType, val bytes: ByteArray)

/**
 * Lo que se va a mandar a la IA, ya comprobado y normalizado: el texto tal cual
 * (sin recortar ni sanear: lo interpreta el servidor), la URL ya validada y las
 * fotos con su tipo real. No lleva nada que pueda imprimirse en un log.
 */
internal class AiPlan(
    val content: String?,
    val url: String?,
    val images: List<AiImageData>,
)

/** Resultado de comprobar la entrada: o el plan, o el motivo del rechazo local. */
internal sealed interface AiImportCheck {
    class Valid(val plan: AiPlan) : AiImportCheck

    class Invalid(val problem: InputProblem) : AiImportCheck
}

/**
 * Frontera de `POST /api/recipes/create/ai`, antes de enviar nada.
 *
 * Se comprueba aquí y no en la interfaz por la misma razón que el resto del
 * núcleo: un rechazo local es inmediato y claro, y no gasta ni red ni una
 * llamada a la IA del servidor (que la paga la persona). Las reglas son las de
 * los hermanos de este endpoint, no unas nuevas:
 *
 * - **texto**: las mismas de [MealieClient.importFromData] ([ImportData]): no
 *   vacío, como mucho [MealieClient.MAX_IMPORT_DATA_BYTES] en bytes UTF-8, sin
 *   controles (NUL incluido; tabulador, salto de línea y retorno sí) y sin
 *   surrogados sueltos. Un texto en blanco se trata como «no hay texto»: si hay
 *   fotos o enlace, la importación sigue sin él.
 * - **enlace**: las de [MealieClient.importFromUrl]: solo `https`, por
 *   [SafeUrl] (sin usuario ni contraseña, ASCII, sin espacios). Quien descarga
 *   es el servidor; la app no abre esa URL jamás.
 * - **fotos**: como [MealieClient.uploadImage]: extensión `jpeg`, `png` o
 *   `webp`, bytes que empiezan como ese formato, no vacía, como mucho
 *   [MealieClient.MAX_IMAGE_BYTES] cada una y sin metadatos
 *   ([ImageMetadata.hasMetadata], que también rechaza truncados y datos detrás
 *   del final). Además, como mucho [MealieClient.MAX_AI_IMAGES] fotos y
 *   [MealieClient.MAX_AI_TOTAL_IMAGE_BYTES] en total.
 */
internal object AiImport {

    /**
     * Tope del cuerpo multipart de esta petición, más alto que
     * `HttpRequest.MAX_BODY_BYTES`: el texto (1 MiB), las fotos (15 MiB) y el
     * envoltorio caben de sobra en 17 MiB, y así el constructor de
     * `HttpRequest` no puede lanzar por un cuerpo que ya se ha validado.
     *
     * Todo el cuerpo se monta en memoria (`MultipartBody`), como en la subida
     * de imagen: son decenas de MiB como mucho y el móvil ya tiene las fotos
     * cargadas.
     */
    const val MAX_BODY_BYTES = 17 * 1024 * 1024

    /** El plan o el primer motivo por el que no se puede enviar. */
    fun check(content: String?, url: String?, images: List<AiImage>): AiImportCheck {
        // 1. Texto: mismas reglas que importar HTML o JSON, en bytes UTF-8.
        var cleanContent: String? = null
        if (content != null) {
            val problem = ImportData.problemOf(content, MealieClient.MAX_IMPORT_DATA_BYTES)
            if (problem == null) {
                cleanContent = content
            } else if (problem != InputProblem.EmptyData) {
                // En blanco es «no he escrito nada», no un error: con fotos o
                // enlace la importación tiene sentido igual.
                return AiImportCheck.Invalid(problem)
            }
        }

        // 2. Enlace: solo https. Un hueco en blanco también es «no hay enlace».
        var cleanUrl: String? = null
        if (url != null && url.isNotBlank()) {
            val safe = SafeUrl.httpOrNull(url)
            if (safe == null || !safe.startsWith("https://", ignoreCase = true)) {
                return AiImportCheck.Invalid(InputProblem.InvalidUrl)
            }
            cleanUrl = safe
        }

        // 3. Sin nada que mandar no se llama a la IA del servidor.
        if (cleanContent == null && cleanUrl == null && images.isEmpty()) {
            return AiImportCheck.Invalid(InputProblem.EmptyData)
        }

        // 4. Fotos: primero lo barato (cuántas y cuánto ocupan en total) y
        // después lo que obliga a recorrer bytes (tipo real y metadatos).
        if (images.size > MealieClient.MAX_AI_IMAGES) {
            return AiImportCheck.Invalid(InputProblem.TooManyImages)
        }
        var totalBytes = 0L
        for (image in images) {
            totalBytes += image.bytes.size.toLong()
        }
        if (totalBytes > MealieClient.MAX_AI_TOTAL_IMAGE_BYTES) {
            return AiImportCheck.Invalid(InputProblem.ImageTooLarge)
        }
        val checked = ArrayList<AiImageData>(images.size)
        for (image in images) {
            val type = typeOf(image.extension)
                ?: return AiImportCheck.Invalid(InputProblem.UnsupportedImageType)
            val bytes = image.bytes
            if (bytes.isEmpty()) return AiImportCheck.Invalid(InputProblem.InvalidImage)
            // El tope por foto se mira otra vez aquí: con menos de cinco fotos
            // el total no lo detecta y el servidor no tiene por qué tragárselo.
            if (bytes.size > MealieClient.MAX_IMAGE_BYTES) {
                return AiImportCheck.Invalid(InputProblem.ImageTooLarge)
            }
            if (ImageTypes.fromMagic(bytes) != type) {
                return AiImportCheck.Invalid(InputProblem.InvalidImage)
            }
            if (ImageMetadata.hasMetadata(bytes)) {
                return AiImportCheck.Invalid(InputProblem.ImageHasMetadata)
            }
            checked += AiImageData(type, bytes)
        }
        return AiImportCheck.Valid(AiPlan(cleanContent, cleanUrl, checked))
    }

    /**
     * El cuerpo `multipart/form-data` con los campos del esquema
     * `Body_create_recipe_with_ai_api_recipes_create_ai_post`: `content` y
     * `url` solo si hay, `createNewOrganizers` siempre (el esquema le pone
     * `false` por defecto, pero se manda explícito para no depender de eso) y
     * una parte de fichero `images` por foto.
     *
     * `translateLanguage` **no se manda**: la app no traduce recetas. Y cada
     * fichero lleva un nombre propio (`image-1.jpeg`…) porque el nombre es lo
     * único que distingue las partes en un registro; el servidor no lo usa para
     * nada más. Los nombres y los tipos salen de una lista fija, nunca del
     * texto que escribe la persona.
     */
    fun multipart(plan: AiPlan, createNewOrganizers: Boolean): MultipartBody.Built {
        val parts = ArrayList<MultipartBody.Part>(2 + plan.images.size)
        plan.content?.let { parts += MultipartBody.Part.Field("content", it) }
        plan.url?.let { parts += MultipartBody.Part.Field("url", it) }
        parts += MultipartBody.Part.Field(
            "createNewOrganizers",
            if (createNewOrganizers) "true" else "false",
        )
        for ((index, image) in plan.images.withIndex()) {
            val extension = extensionOf(image.type)
            parts += MultipartBody.Part.File(
                name = "images",
                filename = "image-${index + 1}.$extension",
                mimeType = image.type.mimeType,
                bytes = image.bytes,
            )
        }
        return MultipartBody.build(parts)
    }

    /** El tipo que corresponde a la extensión escrita, o null si no es de los tres admitidos. */
    private fun typeOf(extension: String): ImageType? {
        val clean = extension.trim().lowercase(Locale.ROOT)
        return ImageType.values().firstOrNull { extensionOf(it) == clean }
    }

    /** `jpeg`, `png` o `webp`: el subtipo del MIME, que es la extensión admitida. */
    private fun extensionOf(type: ImageType): String = type.mimeType.substringAfter('/')
}
