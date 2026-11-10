package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.client.AiCapabilities
import io.github.garfieldrockero.gastronomicon.core.client.AiImage
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.InputProblem
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.platform.ImageRecodeResult
import io.github.garfieldrockero.gastronomicon.platform.ImageRecoder
import io.github.garfieldrockero.gastronomicon.platform.ImportFileReader
import io.github.garfieldrockero.gastronomicon.platform.ImportFileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * De dónde sale la receta que se manda a Mealie.
 */
enum class ImportSource {
    /** Una dirección: la página la descarga el SERVIDOR. */
    Url,

    /** Un archivo elegido o un texto pegado, que se envían tal cual. */
    Data,

    /** Un enlace y/o un texto y/o unas fotos, que interpreta la IA del SERVIDOR. */
    Ai,
}

/**
 * ¿Ofrece este servidor la importación con IA? Lo dice el núcleo al abrir la
 * pantalla ([MealieClient.aiAvailable] devuelve las [AiCapabilities]: IA general,
 * proveedor de imagen y proveedor de audio).
 *
 * Solo [NotConfigured] apaga la sección: cuando no se sabe, se ofrece igual y el
 * error del servidor se enseña al intentarlo. Esconder una función que el
 * servidor sí tiene sería peor que un error claro.
 */
enum class AiAvailability {
    /** Todavía se está preguntando al servidor. */
    Checking,

    /** El servidor dice que sí: se ofrece. */
    Available,

    /** El servidor dice que no hay IA configurada: la sección queda apagada con el motivo. */
    NotConfigured,

    /** No se ha podido saber (red, 401, respuesta rara): se ofrece igualmente. */
    Unknown,
}

/**
 * Lo que puede ir mal en la sección de IA **antes** de mandar nada, en clave de
 * interfaz. Son motivos, no frases: el texto lo pone la pantalla.
 */
enum class AiProblem {
    /** Ni texto, ni enlace, ni fotos. */
    Empty,

    /** El texto pasa de 1 MiB en UTF-8 (el tope lo pone el núcleo). */
    TextTooLarge,

    /** El texto lleva controles o surrogados sueltos que no se pueden enviar. */
    TextNotText,

    /** Más fotos de las que admite el núcleo ([MealieClient.MAX_AI_IMAGES]). */
    TooManyPhotos,

    /** Una foto o el conjunto de fotos pasan de los topes del núcleo. */
    PhotosTooLarge,

    /** Una foto que el núcleo no reconoce o que aún llevaría metadatos. */
    PhotoUnreadable,

    /** La foto elegida no se puede preparar en el móvil, ni siquiera reduciéndola. */
    PhotoTooLarge,

    /** Este móvil no tiene ninguna app de cámara que haga la foto. */
    NoCamera,

    /** No se ha podido abrir la cámara o la galería. */
    PickFailed,
}

/**
 * Una foto ya preparada para la importación con IA, solo para pintarla: la
 * miniatura (pequeña y recodificada, sin metadatos) y lo que pesa.
 *
 * **Los bytes que se envían no están aquí**: viven en el ViewModel, fuera del
 * estado, para que no puedan acabar en un `Bundle` ni en un `toString`.
 */
class AiPhoto(val preview: Bitmap?, val bytes: Int)

/**
 * Lo que puede ir mal en la sección de archivo o texto ANTES de mandar nada.
 * Son motivos, no frases: el texto lo pone la pantalla.
 */
enum class ImportDataProblem {
    /** El texto está vacío o solo tiene espacios. */
    Empty,

    /** El contenido pasa de 1 MiB en UTF-8 (el tope lo pone el núcleo). */
    TooLarge,

    /** El texto lleva controles o surrogados sueltos que no se pueden enviar. */
    NotText,

    /** El archivo no es UTF-8 válido (un ZIP, un PDF, una codificación rota). */
    NotUtf8,

    /** El archivo no se ha podido abrir o leer. */
    Unreadable,
}

/**
 * Estado de «Importar receta»: por URL, con un archivo o texto, o con la IA del
 * servidor.
 *
 * [importedSlug] y [maybeCreated] valen igual para las tres formas: el aviso de
 * «puede que ya se haya creado» es el mismo y tampoco se reintenta solo.
 *
 * **El texto pegado vive aquí y solo aquí**: nunca en `rememberSaveable` ni en
 * el estado guardado de la actividad (reventaría el Bundle y lo dejaría escrito
 * en disco). Lo mismo vale para el texto y el enlace de la sección de IA: se
 * pierden al cerrar la pantalla, a propósito. Las fotos de la IA solo dejan ver
 * una miniatura ([AiPhoto]); sus bytes viven en el ViewModel.
 */
data class RecipeImportUiState(
    val visible: Boolean = false,
    val url: String = "",
    /** Texto pegado o escrito en el campo. */
    val dataText: String = "",
    /** Bytes del archivo elegido. Su contenido no está aquí (ver `RecipeImportViewModel`). */
    val loadedBytes: Int? = null,
    val readingFile: Boolean = false,
    val fileProblem: ImportDataProblem? = null,
    val dataProblem: ImportDataProblem? = null,
    /** Qué se está importando ahora mismo; `null` si no hay nada en marcha. */
    val runningSource: ImportSource? = null,
    val invalidUrl: Boolean = false,
    val problem: ConnectionProblem? = null,
    /** De qué sección viene [problem], para enseñarlo donde se estaba escribiendo. */
    val problemSource: ImportSource? = null,
    /** De qué sección viene el 422 de [details]. */
    val rejectedSource: ImportSource? = null,
    val details: List<ValidationDetail> = emptyList(),
    val maybeCreated: Boolean = false,
    val cancelled: Boolean = false,
    val importedSlug: String? = null,
    // ------------------------------------------------------- IA del servidor
    /** Lo que ha dicho el servidor sobre su IA al abrir la pantalla. */
    val aiAvailability: AiAvailability = AiAvailability.Checking,

    /**
     * Qué puede hacer la IA del servidor, ya decidido en el núcleo
     * ([AiCapabilities]): IA general, proveedor de **imagen** para las fotos y
     * proveedor de **audio** para los enlaces de vídeo.
     *
     * `null` es «no se sabe» (red, 401, respuesta rara) o «la sección está
     * apagada»: en los dos casos no se esconde ni se apaga nada, porque la app no
     * puede adivinar lo que el servidor no ha dicho.
     */
    val aiCapabilities: AiCapabilities? = null,
    /** Enlace pegado para que lo lea la IA del servidor. */
    val aiLink: String = "",
    /** Texto pegado (transcripción, descripción) para la IA del servidor. */
    val aiText: String = "",
    /** Miniaturas ya preparadas; los bytes viven en el ViewModel. */
    val aiPhotos: List<AiPhoto> = emptyList(),
    /** Se está recodificando una foto elegida (sin subirla todavía a ningún sitio). */
    val aiPreparingPhoto: Boolean = false,
    /** Motivo local de la sección de IA. */
    val aiProblem: AiProblem? = null,
    /** El enlace de la sección de IA no vale: se marca el campo, no toda la sección. */
    val aiLinkInvalid: Boolean = false,
) {
    val running: Boolean get() = runningSource != null

    /** Hay un archivo elegido: entonces el campo de pegar está apagado. */
    val fileLoaded: Boolean get() = loadedBytes != null

    /** Hay una importación, una lectura o una foto en marcha: no se toca el formulario. */
    val busy: Boolean get() = running || readingFile || aiPreparingPhoto

    /** Fotos como máximo en una importación con IA: lo decide el núcleo. */
    val maxAiPhotos: Int get() = MealieClient.MAX_AI_IMAGES

    /** Ya no caben más fotos. */
    val aiPhotosFull: Boolean get() = aiPhotos.size >= maxAiPhotos

    /** Hay algo que la sección de IA mandaría: enlace, texto o fotos. */
    val hasAiContent: Boolean
        get() = aiLink.isNotBlank() || aiText.isNotBlank() || aiPhotos.isNotEmpty()

    /**
     * El servidor ha dicho que no tiene IA configurada. Es el dato, no la
     * decisión de la pantalla: si el «no hay IA» llega tarde, con el texto o las
     * fotos ya dentro, se avisa pero se deja intentarlo (el servidor contestará
     * con su error). Para saber si hay que pintar el formulario, [aiFormVisible].
     */
    val aiUnavailable: Boolean get() = aiAvailability == AiAvailability.NotConfigured

    /**
     * Hay que enseñar el formulario de la sección de IA: siempre que el servidor
     * no haya dicho que no, y también cuando lo ha dicho pero ya hay contenido
     * que la persona no podría mandar ni recuperar de otra forma.
     */
    val aiFormVisible: Boolean get() = !aiUnavailable || hasAiContent

    /**
     * Se pueden ofrecer fotos: el servidor tiene proveedor de imagen **o no se
     * sabe**. Solo se apaga la zona de fotos con un «no» claro del servidor
     * ([AiCapabilities.imageAvailable] en `false`): sin IA general no hay
     * formulario, así que no hace falta mirar nada más.
     */
    val aiPhotosAvailable: Boolean get() = aiCapabilities?.imageAvailable != false

    /**
     * El servidor ha dicho que no tiene proveedor de **audio**: los enlaces de
     * vídeo pueden no funcionar. No se bloquea el campo del enlace: la app no
     * puede distinguir un vídeo de una página web, y el servidor contestará.
     */
    val aiAudioUnavailable: Boolean get() = aiCapabilities?.audioAvailable == false

    /** Lo que pesan entre todas las fotos preparadas. Solo se compara: no se manda. */
    val aiPhotosBytes: Long get() = aiPhotos.sumOf { it.bytes.toLong() }

    /**
     * Las fotos preparadas pasan del tope del conjunto
     * ([MealieClient.MAX_AI_TOTAL_IMAGE_BYTES]). Es solo para avisar **antes** de
     * enviar: quien rechaza es el núcleo, que lo comprueba otra vez.
     */
    val aiPhotosOverTotalBytes: Boolean
        get() = aiPhotosBytes > MealieClient.MAX_AI_TOTAL_IMAGE_BYTES

    /** Los topes del núcleo en MiB, para los textos: no se escriben en los recursos. */
    val maxAiPhotoMiB: Int get() = (MealieClient.MAX_IMAGE_BYTES / MIB_BYTES).toInt()
    val maxAiTotalMiB: Int get() = (MealieClient.MAX_AI_TOTAL_IMAGE_BYTES / MIB_BYTES).toInt()
    val maxAiTextMiB: Int get() = (MealieClient.MAX_IMPORT_DATA_BYTES / MIB_BYTES).toInt()

    /**
     * Sin el texto pegado ni la dirección: este `toString` puede acabar en un log
     * o en un informe de fallos, y lo pegado es la receta entera. Solo se dice
     * cuánto ocupa, nunca lo que dice.
     */
    override fun toString(): String =
        "RecipeImportUiState(visible=$visible, urlChars=${url.length}, " +
            "dataChars=${dataText.length}, loadedBytes=$loadedBytes, " +
            "readingFile=$readingFile, fileProblem=$fileProblem, dataProblem=$dataProblem, " +
            "runningSource=$runningSource, invalidUrl=$invalidUrl, problem=$problem, " +
            "problemSource=$problemSource, rejectedSource=$rejectedSource, " +
            "details=${details.size}, maybeCreated=$maybeCreated, cancelled=$cancelled, " +
            "imported=${importedSlug != null}, aiAvailability=$aiAvailability, " +
            "aiLinkChars=${aiLink.length}, aiTextChars=${aiText.length}, " +
            "aiPhotos=${aiPhotos.size}, aiPhotoBytes=$aiPhotosBytes, " +
            "aiPreparingPhoto=$aiPreparingPhoto, aiProblem=$aiProblem, " +
            "aiLinkInvalid=$aiLinkInvalid)"
}

/**
 * ¿Se puede mandar la importación con IA con este estado? Hace falta algo que
 * mandar y que no haya otro envío, lectura o foto en marcha: un segundo toque
 * a ciegas podría dejar dos recetas. Lo que falte o no valga lo rechaza el
 * núcleo antes de abrir conexión ([MealieClient.createRecipeWithAi]).
 */
internal fun canImportAi(state: RecipeImportUiState): Boolean =
    state.visible && !state.busy && state.importedSlug == null && state.hasAiContent

/** Un MiB en bytes, para pasar los topes del núcleo a los textos. */
private const val MIB_BYTES = 1024L * 1024L

/**
 * Importar una receta: por URL, desde un archivo o un texto pegado, o con la IA
 * del servidor (un enlace y/o un texto y/o fotos).
 *
 * Cada operación crea su cliente con [ClientAccess] y corre en un hilo de fondo.
 * El plazo largo lo pone el núcleo (hasta dos minutos; con IA, hasta tres);
 * aquí solo se puede dejar de esperar. Un solo envío por pulsación y **nunca**
 * reintento automático: si el servidor no confirma, puede que la receta exista
 * ya.
 *
 * Cancelar no aborta la petición HTTP que ya está en el servidor: por eso, al
 * cancelar, se avisa de que la receta puede acabar de crearse igualmente.
 *
 * El contenido del archivo elegido, el texto pegado y las fotos de la IA no
 * entran en ningún log ni en el `toString` del estado: el texto pegado está en
 * el estado porque es lo que se está escribiendo, el del archivo se guarda
 * aparte ([loadedData]) y los bytes de las fotos también ([aiPhotos]).
 */
class RecipeImportViewModel(
    private val access: ClientAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipeImportUiState())
    val state: StateFlow<RecipeImportUiState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null
    private var readJob: Job? = null
    private var aiCheckJob: Job? = null
    private var aiPhotoJob: Job? = null

    /**
     * Contenido del archivo elegido. Fuera del estado a propósito: no se compara
     * en cada emisión del flujo, no cabe en una preview y no se puede colar en un
     * `toString`. Se descarta al quitar el archivo, al cerrar y al cambiar de sesión.
     */
    private var loadedData: String? = null

    /**
     * Bytes ya recodificados (sin EXIF) de las fotos de la sección de IA, en el
     * mismo orden que [RecipeImportUiState.aiPhotos]. Fuera del estado por la
     * misma razón que [loadedData]: pueden pesar varios MiB y no tienen por qué
     * viajar en un `Bundle`. Se descarta al quitar la foto, al cerrar y al
     * cambiar de sesión.
     */
    private val aiPhotos = ArrayList<ByteArray>()

    fun open() {
        generation++
        job?.cancel()
        readJob?.cancel()
        aiCheckJob?.cancel()
        aiPhotoJob?.cancel()
        job = null
        readJob = null
        aiCheckJob = null
        aiPhotoJob = null
        loadedData = null
        aiPhotos.clear()
        _state.value = RecipeImportUiState(visible = true)
        // ¿Tiene el servidor IA configurada? Se pregunta al abrir para poder
        // apagar la sección con el motivo, en vez de dejar que la persona pegue
        // media receta y se encuentre el error al final.
        checkAiAvailability()
    }

    fun onUrlChange(value: String) {
        _state.update { current ->
            if (current.busy) {
                current
            } else {
                current.copy(
                    url = value,
                    invalidUrl = false,
                    // El aviso local de la sección de IA no pinta nada aquí: nació de
                    // lo que se hizo allí.
                    aiProblem = null,
                    problem = null,
                    problemSource = null,
                    rejectedSource = null,
                    details = emptyList(),
                    maybeCreated = false,
                    cancelled = false,
                )
            }
        }
    }

    fun onDataTextChange(value: String) {
        _state.update { current ->
            if (current.busy || current.fileLoaded) {
                current
            } else {
                current.copy(
                    dataText = value,
                    dataProblem = null,
                    fileProblem = null,
                    aiProblem = null,
                    problem = null,
                    problemSource = null,
                    rejectedSource = null,
                    details = emptyList(),
                    maybeCreated = false,
                    cancelled = false,
                )
            }
        }
    }

    /**
     * Ha vuelto el selector de archivos con un Uri. Se lee en el ViewModel para
     * que cerrar la pantalla o cambiar de sesión cancele la lectura ([generation]).
     */
    fun onFilePicked(uri: Uri, reader: ImportFileReader) {
        val current = _state.value
        if (!current.visible || current.busy) return
        loadedData = null
        readJob?.cancel()
        _state.value = current.copy(
            readingFile = true,
            loadedBytes = null,
            fileProblem = null,
            dataProblem = null,
            aiProblem = null,
            problem = null,
            problemSource = null,
            rejectedSource = null,
            details = emptyList(),
            maybeCreated = false,
            cancelled = false,
        )
        val gen = generation
        readJob = viewModelScope.launch {
            val result = reader.read(uri)
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            _state.value = when (result) {
                is ImportFileResult.Text -> {
                    loadedData = result.content
                    latest.copy(readingFile = false, loadedBytes = result.byteCount, dataText = "")
                }
                ImportFileResult.TooLarge ->
                    latest.copy(readingFile = false, fileProblem = ImportDataProblem.TooLarge)
                ImportFileResult.NotText ->
                    latest.copy(readingFile = false, fileProblem = ImportDataProblem.NotUtf8)
                ImportFileResult.Unreadable ->
                    latest.copy(readingFile = false, fileProblem = ImportDataProblem.Unreadable)
            }
        }
    }

    /** «Quitar»: se olvida el archivo y vuelve a mandar lo que se pegue en el campo. */
    fun onRemoveFile() {
        val current = _state.value
        if (!current.visible || current.busy || current.loadedBytes == null) return
        loadedData = null
        _state.value = current.copy(loadedBytes = null)
    }

    fun onImport() {
        val current = _state.value
        if (!current.visible || current.busy || current.importedSlug != null) return
        _state.value = current.copy(
            runningSource = ImportSource.Url,
            invalidUrl = false,
            dataProblem = null,
            problem = null,
            problemSource = null,
            rejectedSource = null,
            details = emptyList(),
            maybeCreated = false,
            cancelled = false,
        )
        val url = current.url
        val gen = generation
        job = viewModelScope.launch {
            val result = withContext(ioDispatcher) { importRecipe(url) }
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            _state.value = settled(latest, ImportSource.Url, result)
        }
    }

    /**
     * Manda el archivo elegido o, si no hay ninguno, el texto pegado. Un solo
     * envío por pulsación: repetir a ciegas podría dejar dos recetas.
     */
    fun onImportData() {
        val current = _state.value
        if (!current.visible || current.busy || current.importedSlug != null) return
        val data = loadedData ?: current.dataText
        _state.value = current.copy(
            runningSource = ImportSource.Data,
            invalidUrl = false,
            dataProblem = null,
            fileProblem = null,
            problem = null,
            problemSource = null,
            rejectedSource = null,
            details = emptyList(),
            maybeCreated = false,
            cancelled = false,
        )
        val gen = generation
        job = viewModelScope.launch {
            val result = withContext(ioDispatcher) { importRecipeData(data) }
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            _state.value = settled(latest, ImportSource.Data, result)
        }
    }

    // ------------------------------------------------------------ IA del servidor

    fun onAiLinkChange(value: String) {
        _state.update { current ->
            if (current.busy || !current.aiFormVisible) {
                current
            } else {
                current.copy(
                    aiLink = value,
                    aiLinkInvalid = false,
                    aiProblem = null,
                    problem = null,
                    problemSource = null,
                    rejectedSource = null,
                    details = emptyList(),
                    maybeCreated = false,
                    cancelled = false,
                )
            }
        }
    }

    fun onAiTextChange(value: String) {
        _state.update { current ->
            if (current.busy || !current.aiFormVisible) {
                current
            } else {
                current.copy(
                    aiText = value,
                    aiProblem = null,
                    problem = null,
                    problemSource = null,
                    rejectedSource = null,
                    details = emptyList(),
                    maybeCreated = false,
                    cancelled = false,
                )
            }
        }
    }

    /**
     * Una foto elegida con la galería o con la cámara del sistema. Se recodifica
     * aquí (fuera del hilo principal, sin EXIF y reducida, con [ImageRecoder]) y
     * solo se guarda una miniatura en el estado; los bytes se quedan en
     * [aiPhotos]. Cancelar la pantalla o cambiar de sesión tira lo preparado
     * ([generation]).
     *
     * [discardSource] es `true` cuando la foto es un temporal de la cámara: el
     * recodificador lo borra al acabar, y aquí también cuando la foto no se
     * llega a usar, para no dejar ficheros en la caché.
     */
    fun onAiPhotoPicked(uri: Uri, recoder: ImageRecoder, discardSource: Boolean) {
        val current = _state.value
        if (!current.visible || current.busy || !current.aiFormVisible) {
            if (discardSource) recoder.discard(uri)
            return
        }
        if (current.aiPhotosFull) {
            if (discardSource) recoder.discard(uri)
            _state.value = current.copy(aiProblem = AiProblem.TooManyPhotos)
            return
        }
        aiPhotoJob?.cancel()
        _state.value = current.copy(
            aiPreparingPhoto = true,
            aiProblem = null,
            problem = null,
            problemSource = null,
            rejectedSource = null,
            details = emptyList(),
            maybeCreated = false,
            cancelled = false,
        )
        val gen = generation
        aiPhotoJob = viewModelScope.launch {
            // Una foto enorme puede agotar la memoria del móvil: se dice y se sigue,
            // en vez de dejar la sección con la barra de «preparando» para siempre.
            val recoded = try {
                recoder.recode(uri, discardSource = discardSource)
            } catch (ignored: OutOfMemoryError) {
                if (gen != generation) return@launch
                setAiProblem(AiProblem.PhotoTooLarge)
                return@launch
            }
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            when (recoded) {
                is ImageRecodeResult.Ok -> {
                    if (latest.aiPhotos.size >= latest.maxAiPhotos) {
                        _state.value = latest.copy(
                            aiPreparingPhoto = false,
                            aiProblem = AiProblem.TooManyPhotos,
                        )
                        return@launch
                    }
                    val bytes = recoded.bytes
                    val preview = withContext(ioDispatcher) { thumbnailOf(bytes) }
                    if (gen != generation) return@launch
                    val settledState = _state.value
                    if (!settledState.visible) return@launch
                    if (settledState.aiPhotos.size >= settledState.maxAiPhotos) {
                        _state.value = settledState.copy(
                            aiPreparingPhoto = false,
                            aiProblem = AiProblem.TooManyPhotos,
                        )
                        return@launch
                    }
                    aiPhotos.add(bytes)
                    _state.value = settledState.copy(
                        aiPreparingPhoto = false,
                        aiPhotos = settledState.aiPhotos + AiPhoto(preview, bytes.size),
                    )
                }
                ImageRecodeResult.TooLarge -> setAiProblem(AiProblem.PhotoTooLarge)
                ImageRecodeResult.NotAnImage -> setAiProblem(AiProblem.PhotoUnreadable)
                ImageRecodeResult.Failed -> setAiProblem(AiProblem.PhotoUnreadable)
            }
        }
    }

    /**
     * El selector o la cámara no han dejado foto: el móvil no tiene app de cámara
     * o no se ha podido abrir nada. Se dice en la sección, sin sacar a nadie de
     * la pantalla.
     */
    fun onAiPhotoProblem(problem: AiProblem) {
        val current = _state.value
        if (!current.visible || current.busy) return
        _state.value = current.copy(aiProblem = problem)
    }

    /** «Quitar foto N»: se olvidan la miniatura y los bytes de esa foto. */
    fun onRemoveAiPhoto(index: Int) {
        val current = _state.value
        if (!current.visible || current.busy) return
        if (index !in current.aiPhotos.indices) return
        if (index >= aiPhotos.size) return
        aiPhotos.removeAt(index)
        _state.value = current.copy(
            aiPhotos = current.aiPhotos.filterIndexed { position, _ -> position != index },
            aiProblem = null,
        )
    }

    /**
     * «Importar con IA»: un solo envío por pulsación, con el enlace, el texto y
     * las fotos que haya. Lo que falta o no vale lo rechaza el núcleo **antes**
     * de abrir conexión ([MealieClient.createRecipeWithAi]), así que aquí no se
     * comprueba nada de eso. Nunca se reintenta solo.
     *
     * Sin contenido, o con un envío ya en marcha, no se hace nada ([canImportAi]):
     * el primer toque deja el estado ocupado antes de lanzar la corrutina, así que
     * un segundo toque no llega a mandar otra receta.
     */
    fun onImportAi() {
        val current = _state.value
        if (!canImportAi(current)) return
        val link = current.aiLink
        val text = current.aiText
        // Copia de las referencias a los bytes ya preparados: el estado no los lleva
        // y no se copian los bytes (hasta 5 fotos de 5 MiB no caben dos veces).
        val images = aiPhotos.map { AiImage(it, ImageRecoder.EXTENSION) }
        _state.value = current.copy(
            runningSource = ImportSource.Ai,
            aiProblem = null,
            aiLinkInvalid = false,
            invalidUrl = false,
            dataProblem = null,
            fileProblem = null,
            problem = null,
            problemSource = null,
            rejectedSource = null,
            details = emptyList(),
            maybeCreated = false,
            cancelled = false,
        )
        val gen = generation
        job = viewModelScope.launch {
            val result = withContext(ioDispatcher) { importRecipeWithAi(text, link, images) }
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            _state.value = settled(latest, ImportSource.Ai, result)
        }
    }

    /** ¿Tiene el servidor IA configurada y para qué? Si no se sabe, se ofrece igualmente. */
    private fun checkAiAvailability() {
        val gen = generation
        aiCheckJob?.cancel()
        aiCheckJob = viewModelScope.launch {
            val check = withContext(ioDispatcher) { askAiAvailability() }
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            _state.value = latest.copy(
                aiAvailability = check.availability,
                aiCapabilities = check.capabilities,
            )
        }
    }

    private fun askAiAvailability(): AiCheck {
        val client = try {
            access.newClient()
        } catch (error: CancellationException) {
            throw error
        } catch (ignored: NetworkError) {
            // Sin sesión, sin red o con el almacén sin responder: no se sabe.
            return AiCheck(AiAvailability.Unknown)
        } catch (ignored: Exception) {
            return AiCheck(AiAvailability.Unknown)
        }
        return when (val result = client.aiAvailable()) {
            // Solo un «no» claro apaga la sección; las capacidades ya vienen
            // decididas del núcleo (ver el KDoc de AiCapabilities).
            is ApiResult.Success ->
                if (result.value.available) {
                    AiCheck(AiAvailability.Available, result.value)
                } else {
                    // IA apagada: no hay formulario, así que no hacen falta las
                    // capacidades y se quedan en «no se sabe».
                    AiCheck(AiAvailability.NotConfigured)
                }
            else -> AiCheck(AiAvailability.Unknown)
        }
    }

    /**
     * Lo que ha contestado el servidor sobre su IA: el sí/no que decide si se
     * ofrece la sección y, cuando lo ha dicho, sus capacidades. Va aparte porque
     * «no hay IA» y «no se sabe» no traen capacidades que enseñar.
     */
    private class AiCheck(
        val availability: AiAvailability,
        val capabilities: AiCapabilities? = null,
    )

    /** Vuelca un motivo local de la sección de IA, si la pantalla sigue abierta. */
    private fun setAiProblem(problem: AiProblem) {
        val latest = _state.value
        if (!latest.visible) return
        _state.value = latest.copy(aiPreparingPhoto = false, aiProblem = problem)
    }

    /** Miniatura pequeña de una foto ya recodificada (sin EXIF). Solo para pintarla. */
    private fun thumbnailOf(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) {
            null
        } else {
            var sample = 1
            while (longest / (sample * 2) >= AI_THUMB_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    } catch (ignored: OutOfMemoryError) {
        null
    } catch (ignored: RuntimeException) {
        null
    }

    /** Deja de esperar; la petición del servidor puede seguir su curso. */
    fun onCancel() {
        val current = _state.value
        if (!current.running) return
        generation++
        job?.cancel()
        job = null
        _state.value = current.copy(
            runningSource = null,
            cancelled = true,
            problem = null,
            problemSource = null,
            rejectedSource = null,
            details = emptyList(),
            maybeCreated = false,
        )
    }

    fun close() {
        generation++
        job?.cancel()
        readJob?.cancel()
        aiCheckJob?.cancel()
        aiPhotoJob?.cancel()
        job = null
        readJob = null
        aiCheckJob = null
        aiPhotoJob = null
        loadedData = null
        aiPhotos.clear()
        _state.value = RecipeImportUiState()
    }

    /** Al cambiar de sesión: nada de lo cargado vale. */
    fun reset() = close()

    /** Vuelca el desenlace de una importación al estado, venga de donde venga. */
    private fun settled(
        latest: RecipeImportUiState,
        source: ImportSource,
        result: ImportResult,
    ): RecipeImportUiState = when (result) {
        is ImportResult.Success -> latest.copy(runningSource = null, importedSlug = result.slug)
        // Solo puede venir de la URL: `importFromData` devuelve sus motivos aparte.
        ImportResult.InvalidUrl -> latest.copy(runningSource = null, invalidUrl = true)
        is ImportResult.InputRejected -> latest.copy(
            runningSource = null,
            dataProblem = dataProblemOf(result.reason),
        )
        is ImportResult.AiInputRejected -> latest.copy(
            runningSource = null,
            // El enlace inválido ya se marca en su campo: no se repite la misma frase
            // en un aviso debajo (aiProblemOf devuelve null para ese motivo).
            aiProblem = aiProblemOf(result.reason),
            aiLinkInvalid = result.reason == InputProblem.InvalidUrl,
        )
        // Se ha agotado la memoria al montar el envío: no hay receta creada y se
        // dice en la sección, sin dejar la barra de progreso colgada.
        ImportResult.OutOfMemory -> latest.copy(
            runningSource = null,
            aiProblem = AiProblem.PhotosTooLarge,
        )
        ImportResult.Unauthorized -> latest.copy(
            runningSource = null,
            problem = ConnectionProblem.Unauthorized,
            problemSource = source,
        )
        is ImportResult.Rejected -> latest.copy(
            runningSource = null,
            rejectedSource = source,
            details = result.details,
        )
        is ImportResult.MaybeCreated -> latest.copy(
            runningSource = null,
            maybeCreated = true,
            problem = result.problem,
            problemSource = source,
        )
        is ImportResult.Problem -> latest.copy(
            runningSource = null,
            problem = result.problem,
            problemSource = source,
        )
    }

    private fun importRecipe(url: String): ImportResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return ImportResult.Problem(e.toConnectionProblem())
        } catch (e: Exception) {
            return ImportResult.Problem(ConnectionProblem.Unknown)
        }
        return when (val result = client.importFromUrl(url)) {
            is ApiResult.Success -> ImportResult.Success(result.value)
            // importFromUrl solo rechaza por la dirección.
            is ApiResult.InputRejected -> ImportResult.InvalidUrl
            ApiResult.Unauthorized -> ImportResult.Unauthorized
            is ApiResult.Invalid -> ImportResult.Rejected(result.details)
            ApiResult.Forbidden, ApiResult.Conflict, ApiResult.NotFound,
            is ApiResult.Rejected, is ApiResult.DraftInvalid, is ApiResult.SavedButNotReloaded ->
                ImportResult.Rejected(emptyList())
            // El núcleo ya sabe cuándo un fallo prueba que NO se creó y cuándo no:
            // solo avisa de «quizá creada» cuando la petición pudo llegar a salir.
            is ApiResult.MaybeCreated -> ImportResult.MaybeCreated(result.problem)
            is ApiResult.Failure -> ImportResult.Problem(result.problem)
        }
    }

    private fun importRecipeData(data: String): ImportResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return ImportResult.Problem(e.toConnectionProblem())
        } catch (e: Exception) {
            return ImportResult.Problem(ConnectionProblem.Unknown)
        }
        return when (val result = client.importFromData(data)) {
            is ApiResult.Success -> ImportResult.Success(result.value)
            // Vacío, demasiado grande o no es texto: el núcleo lo rechaza sin enviar nada.
            is ApiResult.InputRejected -> ImportResult.InputRejected(result.reason)
            ApiResult.Unauthorized -> ImportResult.Unauthorized
            is ApiResult.Invalid -> ImportResult.Rejected(result.details)
            ApiResult.Forbidden, ApiResult.Conflict, ApiResult.NotFound,
            is ApiResult.Rejected, is ApiResult.DraftInvalid, is ApiResult.SavedButNotReloaded ->
                ImportResult.Rejected(emptyList())
            is ApiResult.MaybeCreated -> ImportResult.MaybeCreated(result.problem)
            is ApiResult.Failure -> ImportResult.Problem(result.problem)
        }
    }

    /** Los motivos que puede devolver `importFromData`, en clave de interfaz. */
    private fun dataProblemOf(reason: InputProblem): ImportDataProblem = when (reason) {
        InputProblem.EmptyData -> ImportDataProblem.Empty
        InputProblem.DataTooLarge -> ImportDataProblem.TooLarge
        InputProblem.DataNotText -> ImportDataProblem.NotText
        // No los devuelve `importFromData` (son de la URL, de la imagen y de la
        // importación con IA), pero el `when` sobre el enum tiene que estar completo:
        // si llegara alguno, el aviso de «no es texto» sigue siendo el más útil.
        InputProblem.InvalidUrl, InputProblem.InvalidImage, InputProblem.UnsupportedImageType,
        InputProblem.ImageTooLarge, InputProblem.ImageHasMetadata, InputProblem.TooManyImages ->
            ImportDataProblem.NotText
    }

    /**
     * La importación con IA. El texto y el enlace se mandan tal cual (vacío o en
     * blanco cuenta como ausente, lo decide el núcleo) y las fotos ya van
     * recodificadas: el núcleo las vuelve a comprobar.
     */
    private fun importRecipeWithAi(
        content: String,
        url: String,
        images: List<AiImage>,
    ): ImportResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return ImportResult.Problem(e.toConnectionProblem())
        } catch (e: Exception) {
            return ImportResult.Problem(ConnectionProblem.Unknown)
        }
        // El cuerpo multipart se monta en memoria (las fotos ya están en `aiPhotos`
        // y el núcleo reserva el cuerpo entero): en un móvil justo puede no caber.
        // Sin este `catch` el proceso moriría en vez de decir «quita fotos».
        val result = try {
            client.createRecipeWithAi(content = content, url = url, images = images)
        } catch (ignored: OutOfMemoryError) {
            return ImportResult.OutOfMemory
        }
        return when (result) {
            is ApiResult.Success -> ImportResult.Success(result.value)
            // Falta algo, el texto no es texto, el enlace no vale o hay demasiadas
            // fotos: el núcleo lo rechaza sin abrir conexión.
            is ApiResult.InputRejected -> ImportResult.AiInputRejected(result.reason)
            ApiResult.Unauthorized -> ImportResult.Unauthorized
            is ApiResult.Invalid -> ImportResult.Rejected(result.details)
            ApiResult.Forbidden, ApiResult.Conflict, ApiResult.NotFound,
            is ApiResult.Rejected, is ApiResult.DraftInvalid, is ApiResult.SavedButNotReloaded ->
                ImportResult.Rejected(emptyList())
            // Con IA el «quizá» es más probable que nunca: la petición puede tardar
            // minutos y el servidor seguir trabajando cuando la app deja de esperar.
            is ApiResult.MaybeCreated -> ImportResult.MaybeCreated(result.problem)
            is ApiResult.Failure -> ImportResult.Problem(result.problem)
        }
    }

    /**
     * Los motivos que puede devolver `createRecipeWithAi`, en clave de interfaz.
     * `null` cuando no hay nada que avisar debajo: el enlace inválido ya se marca
     * en su propio campo y repetir la misma frase sobraba.
     */
    private fun aiProblemOf(reason: InputProblem): AiProblem? = when (reason) {
        InputProblem.EmptyData -> AiProblem.Empty
        InputProblem.DataTooLarge -> AiProblem.TextTooLarge
        InputProblem.DataNotText -> AiProblem.TextNotText
        InputProblem.InvalidUrl -> null
        InputProblem.TooManyImages -> AiProblem.TooManyPhotos
        // El núcleo usa el mismo motivo para una foto gorda y para el conjunto.
        InputProblem.ImageTooLarge -> AiProblem.PhotosTooLarge
        // Los produce `uploadImage`; con las fotos ya recodificadas aquí, si
        // llegaran sería porque la foto no es lo que parece: se quita y se repite.
        InputProblem.InvalidImage, InputProblem.UnsupportedImageType, InputProblem.ImageHasMetadata ->
            AiProblem.PhotoUnreadable
    }

    private sealed interface ImportResult {
        data class Success(val slug: String) : ImportResult
        data object InvalidUrl : ImportResult
        data class InputRejected(val reason: InputProblem) : ImportResult
        data class AiInputRejected(val reason: InputProblem) : ImportResult
        data object Unauthorized : ImportResult
        data class Rejected(val details: List<ValidationDetail>) : ImportResult
        data class MaybeCreated(val problem: ConnectionProblem) : ImportResult
        data class Problem(val problem: ConnectionProblem) : ImportResult

        /** No había memoria para montar el envío: no se ha llegado a abrir conexión. */
        data object OutOfMemory : ImportResult
    }

    companion object {
        /**
         * Lado mayor de las miniaturas de la sección de IA: se ven a 96 dp, así que
         * 192 px sobra incluso con una pantalla densa. Son pequeñas a propósito:
         * solo sirven para reconocer la foto y se tiran al cerrar la pantalla.
         */
        private const val AI_THUMB_SIDE = 192

        fun factory(access: ClientAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeImportViewModel(access) as T
            }
    }
}
