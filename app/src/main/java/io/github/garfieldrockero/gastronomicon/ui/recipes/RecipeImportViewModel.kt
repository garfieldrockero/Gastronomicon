package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.InputProblem
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
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
}

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
 * Estado de «Importar receta», por URL o con un archivo o texto.
 *
 * [importedSlug] y [maybeCreated] valen igual para las dos formas: el aviso de
 * «puede que ya se haya creado» es el mismo y tampoco se reintenta solo.
 *
 * **El texto pegado vive aquí y solo aquí**: nunca en `rememberSaveable` ni en
 * el estado guardado de la actividad (reventaría el Bundle y lo dejaría escrito
 * en disco). Se pierde al cerrar la pantalla, a propósito.
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
) {
    val running: Boolean get() = runningSource != null

    /** Hay un archivo elegido: entonces el campo de pegar está apagado. */
    val fileLoaded: Boolean get() = loadedBytes != null

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
            "imported=${importedSlug != null})"
}

/**
 * Importar una receta: por URL o desde un archivo o un texto pegado.
 *
 * Cada operación crea su cliente con [ClientAccess] y corre en un hilo de fondo.
 * El plazo largo lo pone el núcleo (hasta dos minutos); aquí solo se puede dejar
 * de esperar. Un solo envío por pulsación y **nunca** reintento automático: si
 * el servidor no confirma, puede que la receta exista ya.
 *
 * Cancelar no aborta la petición HTTP que ya está en el servidor: por eso, al
 * cancelar, se avisa de que la receta puede acabar de crearse igualmente.
 *
 * El contenido del archivo elegido y el texto pegado no entran en ningún log ni
 * en el `toString` del estado: el texto pegado está en el estado porque es lo
 * que se está escribiendo, y el del archivo se guarda aparte ([loadedData]).
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

    /**
     * Contenido del archivo elegido. Fuera del estado a propósito: no se compara
     * en cada emisión del flujo, no cabe en una preview y no se puede colar en un
     * `toString`. Se descarta al quitar el archivo, al cerrar y al cambiar de sesión.
     */
    private var loadedData: String? = null

    fun open() {
        generation++
        job?.cancel()
        readJob?.cancel()
        job = null
        readJob = null
        loadedData = null
        _state.value = RecipeImportUiState(visible = true)
    }

    fun onUrlChange(value: String) {
        _state.update { current ->
            if (current.running || current.readingFile) {
                current
            } else {
                current.copy(
                    url = value,
                    invalidUrl = false,
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
            if (current.running || current.readingFile || current.fileLoaded) {
                current
            } else {
                current.copy(
                    dataText = value,
                    dataProblem = null,
                    fileProblem = null,
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
        if (!current.visible || current.running || current.readingFile) return
        loadedData = null
        readJob?.cancel()
        _state.value = current.copy(
            readingFile = true,
            loadedBytes = null,
            fileProblem = null,
            dataProblem = null,
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
        if (!current.visible || current.running || current.loadedBytes == null) return
        loadedData = null
        _state.value = current.copy(loadedBytes = null)
    }

    fun onImport() {
        val current = _state.value
        if (!current.visible || current.running || current.readingFile || current.importedSlug != null) return
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
        if (!current.visible || current.running || current.readingFile || current.importedSlug != null) return
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
        job = null
        readJob = null
        loadedData = null
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

    private sealed interface ImportResult {
        data class Success(val slug: String) : ImportResult
        data object InvalidUrl : ImportResult
        data class InputRejected(val reason: InputProblem) : ImportResult
        data object Unauthorized : ImportResult
        data class Rejected(val details: List<ValidationDetail>) : ImportResult
        data class MaybeCreated(val problem: ConnectionProblem) : ImportResult
        data class Problem(val problem: ConnectionProblem) : ImportResult
    }

    companion object {
        fun factory(access: ClientAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeImportViewModel(access) as T
            }
    }
}
