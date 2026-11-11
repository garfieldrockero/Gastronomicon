package io.github.garfieldrockero.gastronomicon.ui.components

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAddition
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAdditionProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAdditions
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingIds
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingList
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerController
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSource
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSummary
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingAccess
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException

/*
 * Fase 9, extra 1: añadir los ingredientes de una receta (o de la semana del
 * planificador) a una lista de la compra.
 *
 * Vive en `ui/components` desde la fase 16b: lo usan el detalle de una receta y
 * el planificador, así que no es de ninguna de las dos pantallas.
 *
 * Aquí no hay ninguna regla de negocio: qué recetas entran, cómo se suman las
 * escalas, el tope de 30, si un id sirve y si se puede reintentar lo decide
 * `core/shopping` (`RecipeAdditions`, `ShoppingListPickerController`,
 * `ShoppingClient.addRecipes`). La interfaz solo elige el texto de cada
 * desenlace y pinta.
 */

/**
 * Cómo ha terminado un «añadir a la compra». Tipado a propósito: la interfaz
 * solo elige el texto y si se puede volver a intentar; nunca lleva mensajes de
 * excepción, ids ni textos del servidor.
 */
sealed interface AddToShoppingOutcome {

    /**
     * Los ingredientes ya están en [listName] (nombre ya saneado por el núcleo).
     * [recipes] son las recetas distintas que se han añadido.
     */
    data class Added(val listName: String, val recipes: Int) : AddToShoppingOutcome {
        // Sin el nombre de la lista: es texto del servidor.
        override fun toString(): String = "AddToShoppingOutcome.Added(recipes=$recipes)"
    }

    /**
     * El `POST` pudo llegar al servidor y no se sabe si entró. **Nunca se
     * reintenta solo**: hay que mirar la lista antes de repetir.
     */
    data class MaybeAdded(val connection: ConnectionProblem) : AddToShoppingOutcome

    /** Un dato de la tanda no vale: no ha salido nada de la app. */
    data class Invalid(val reason: RecipeAdditionProblem) : AddToShoppingOutcome

    /** Nada añadido: sin red, servidor caído, TLS o respuesta ilegible. */
    data class Failed(val connection: ConnectionProblem) : AddToShoppingOutcome

    /** Nada añadido: el token ha caducado o no tiene permiso. Se arregla en Ajustes. */
    data object Unauthorized : AddToShoppingOutcome

    /** Nada añadido: el servidor ha rechazado la petición. */
    data object Rejected : AddToShoppingOutcome

    /** Nada añadido: la lista ya no está (404). Hay que volver a mirar las listas. */
    data object ListMissing : AddToShoppingOutcome
}

/**
 * Todo lo que pinta el diálogo de «añadir a la compra» y el aviso de después.
 *
 * [available] dice si la pantalla tiene con qué hacerlo (sesión y transporte de
 * la compra); sin eso, la acción ni se ofrece. [recipes] y [skipped] solo los
 * usa la semana del planificador (el recuento lo da el núcleo); [scaleText],
 * [usesServings] y [picker] solo los usa el detalle de una receta (el
 * planificador también usa [picker], pero sin campo de número).
 *
 * [usesServings] dice qué significa el número que escribe la persona: **raciones**
 * (true, la receta dice para cuántas es) o **veces la receta** (false, no lo
 * dice). Con raciones, el campo arranca con las que estén elegidas en el detalle
 * y la conversión a escala la hace el núcleo
 * (`RecipeAdditions.scaleForServings`); la pantalla no divide.
 */
data class AddToShoppingUi(
    val available: Boolean = false,
    val open: Boolean = false,
    val picker: ShoppingListPickerState = ShoppingListPickerState(),
    val scaleText: String = "",
    val usesServings: Boolean = false,
    val recipes: Int = 0,
    val skipped: Int = 0,
    val truncated: Boolean = false,
    val sending: Boolean = false,
    /** Fallo que deja el diálogo abierto, con su motivo. */
    val problem: AddToShoppingOutcome? = null,
    /** Aviso de después de cerrar el diálogo: solo `Added` o `MaybeAdded`. */
    val notice: AddToShoppingOutcome? = null,
) {
    /** Se puede enviar: hay una lista elegida y no hay nada en vuelo. */
    val canSend: Boolean get() = !sending && !picker.isBusy && picker.openListId != null

    // Sin nombres de lista ni textos: esto puede acabar en un log.
    override fun toString(): String =
        "AddToShoppingUi(available=$available, open=$open, lists=${picker.lists.size}, " +
            "recipes=$recipes, usesServings=$usesServings, sending=$sending, " +
            "problem=${problem?.javaClass?.simpleName}, notice=${notice?.javaClass?.simpleName})"
}

// ------------------------------------------------------------------ el núcleo

/**
 * Pasarela a `core/shopping` para las dos pantallas que añaden ingredientes a la
 * compra. No decide nada: crea el selector de listas del núcleo y le pasa el
 * `POST` ya validado. Los métodos son bloqueantes; se llaman desde un hilo de
 * fondo.
 */
internal object AddToShopping {

    /**
     * Selector de listas listo para `load()`. No toca la red todavía, así que se
     * puede crear en el hilo principal. Qué lista queda elegida lo decide el
     * núcleo: como esta pantalla no tiene ninguna recordada, la primera por
     * nombre, que es la misma que abre la pestaña Compra la primera vez.
     *
     * Pendiente de decisión: si la persona cambió de lista en la pestaña Compra,
     * esa elección vive en `ShoppingViewModel` y esta pantalla no puede leerla
     * sin tocar `ui/shopping` o la navegación; cuando se cablee, se pasa aquí
     * como `initialOpenListId` y el núcleo la respeta.
     */
    fun newPicker(access: ShoppingAccess): ShoppingListPickerController =
        ShoppingListPickerController(PickerSource(access))

    /**
     * Añade [additions] a la lista [listId]. Antes de enviar nada se valida la
     * tanda con el núcleo (`RecipeAdditions.check`): si no vale, devuelve el
     * motivo y no se abre conexión. Un desenlace posterior al envío que no sea
     * un rechazo definitivo es [AddToShoppingOutcome.MaybeAdded] y **no se
     * reintenta nunca solo**.
     *
     * La creación del cliente va en su propio `try`, como en [PickerSource]: un
     * fallo de configuración o de sesión (HTTP local apagado, almacén sin
     * respuesta, sin sesión) es de **conexión**, no un dato de la receta, y se
     * enseña con su texto y no con «esa receta no se puede añadir». Solo la
     * última barrera del cliente (un id que no puede ir en la ruta) es
     * [AddToShoppingOutcome.Invalid].
     */
    fun add(
        access: ShoppingAccess,
        listId: String,
        additions: List<RecipeAddition>,
        listName: String,
    ): AddToShoppingOutcome {
        // Sin una lista que se pueda poner en la ruta no hay nada que enviar.
        if (ShoppingIds.valid(listId) == null) return AddToShoppingOutcome.ListMissing
        val invalid = RecipeAdditions.check(additions)
        if (invalid != null) return AddToShoppingOutcome.Invalid(invalid)

        val client = try {
            access.newClient()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NetworkError) {
            return failureOf(error)
        } catch (error: RuntimeException) {
            // Sin sesión legible, almacén sin respuesta o HTTP local apagado:
            // no ha salido nada. Mismo motivo que al borrar, no uno de receta.
            return AddToShoppingOutcome.Failed(ConnectionProblem.Unknown)
        }

        // Última barrera del cliente: el id de la receta en la ruta. No ha salido nada.
        val result = try {
            client.addRecipes(listId, additions)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: IllegalArgumentException) {
            return AddToShoppingOutcome.Invalid(RecipeAdditionProblem.InvalidRecipeId)
        }

        return when (result) {
            is ApiResult.Success -> AddToShoppingOutcome.Added(
                listName = listName,
                // Recetas distintas, ya fusionadas por el núcleo.
                recipes = RecipeAdditions.merge(additions).size,
            )
            is ApiResult.MaybeCreated -> AddToShoppingOutcome.MaybeAdded(result.problem)
            ApiResult.Unauthorized, ApiResult.Forbidden -> AddToShoppingOutcome.Unauthorized
            ApiResult.NotFound -> AddToShoppingOutcome.ListMissing
            ApiResult.Conflict, is ApiResult.Invalid, is ApiResult.Rejected,
            is ApiResult.DraftInvalid, is ApiResult.InputRejected,
            is ApiResult.SavedButNotReloaded -> AddToShoppingOutcome.Rejected
            is ApiResult.Failure -> AddToShoppingOutcome.Failed(result.problem)
        }
    }

    /** 401 y 403 se distinguen; el resto es un fallo de conexión de siempre. */
    private fun failureOf(error: NetworkError): AddToShoppingOutcome = when {
        error is NetworkError.HttpError && error.statusCode == 401 -> AddToShoppingOutcome.Unauthorized
        error is NetworkError.HttpError && error.statusCode == 403 -> AddToShoppingOutcome.Unauthorized
        else -> AddToShoppingOutcome.Failed(error.toConnectionProblem())
    }

    /**
     * Fuente de solo lectura de la página de listas: la creación del cliente va
     * envuelta para que un fallo de sesión o de configuración llegue al selector
     * como resultado tipado y no como excepción. La llamada queda fuera del
     * `try` para no perder ningún matiz del núcleo.
     */
    private class PickerSource(private val access: ShoppingAccess) : ShoppingListSource {

        override fun listLists(page: Int, perPage: Int): ApiResult<Page<ShoppingListSummary>> {
            val client = try {
                access.newClient()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: NetworkError) {
                return when {
                    error is NetworkError.HttpError && error.statusCode == 401 -> ApiResult.Unauthorized
                    error is NetworkError.HttpError && error.statusCode == 403 -> ApiResult.Forbidden
                    else -> ApiResult.Failure(error.toConnectionProblem())
                }
            } catch (error: RuntimeException) {
                return ApiResult.Failure(ConnectionProblem.Unknown)
            }
            return client.listLists(page, perPage)
        }

        // El diálogo solo lee la página de listas: los elementos no se tocan aquí.
        override fun getList(listId: String): ApiResult<ShoppingList> = notUsed()

        override fun addItem(listId: String, text: String): ApiResult<Unit> = notUsed()

        override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> = notUsed()

        override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> = notUsed()

        override fun deleteItem(itemId: String): ApiResult<Unit> = notUsed()

        override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> = notUsed()

        /**
         * El diálogo no usa estos caminos. Se devuelve un fallo tipado en vez de
         * lanzar: esto corre en un hilo de fondo y una excepción se llevaría por
         * delante la corrutina; un fallo se queda en un mensaje y no toca nada.
         */
        private fun <T> notUsed(): ApiResult<T> = ApiResult.Failure(ConnectionProblem.Unknown)
    }
}

/**
 * [ShoppingAccess] atado a una época de sesión: si `reset()` la ha superado
 * mientras la operación estaba en vuelo, no se lee la sesión nueva ni se abre
 * conexión. Lanza un fallo normal (no ha salido nada), que la pantalla descarta
 * por época; sin esto, un `POST` que nace en una sesión podría mandar a la
 * siguiente una lista que ya no es suya.
 */
internal fun ShoppingAccess.guardedBy(isCurrent: () -> Boolean): ShoppingAccess =
    ShoppingAccess {
        if (!isCurrent()) throw IllegalStateException("La sesión ha cambiado.")
        newClient()
    }

// ------------------------------------------------------------------ el diálogo

/**
 * Diálogo de «añadir a la compra»: elige la lista (y las raciones, en el
 * detalle de una receta) y manda. Mientras el `POST` está en vuelo no se puede
 * tocar nada ni cerrar; con un fallo se queda abierto con el motivo, y con
 * éxito se cierra para enseñar el aviso.
 *
 * [lead] es la frase con el recuento que compone la pantalla (el número lo da
 * el núcleo); [note] es una línea más para lo que se queda fuera. El campo del
 * número cambia de nombre según [AddToShoppingUi.usesServings]: «Raciones» si la
 * receta dice para cuántas es, «Veces la receta» si no.
 */
@Composable
internal fun AddToShoppingDialog(
    state: AddToShoppingUi,
    title: String,
    lead: String,
    showScale: Boolean,
    showLists: Boolean,
    onSelectList: (String) -> Unit,
    onScaleChange: (String) -> Unit,
    onRetryLists: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    note: String? = null,
) {
    val sending = state.sending
    val problem = state.problem
    val picker = state.picker
    val scaleInvalid = problem is AddToShoppingOutcome.Invalid &&
        problem.reason == RecipeAdditionProblem.InvalidScale
    // Sin listas leídas ni lectura en marcha (una receta sin identificador, por
    // ejemplo) no se enseña «Buscando tus listas…»: no hay nada que buscar.
    val listsVisible = showLists && (picker.loadedOnce || picker.isLoading || picker.problem != null)

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                // Con el texto grande el contenido no cabe: se desplaza.
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Text(
                    text = lead,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showScale) {
                    ScaleInput(
                        value = state.scaleText,
                        onValueChange = onScaleChange,
                        enabled = !sending,
                        invalid = scaleInvalid,
                        usesServings = state.usesServings,
                    )
                }
                if (listsVisible) {
                    ListChoices(
                        picker = picker,
                        sending = sending,
                        onSelectList = onSelectList,
                        onRetryLists = onRetryLists,
                        onOpenSettings = onOpenSettings,
                    )
                }
                // El fallo de las raciones ya se ve en su campo; los demás, aquí.
                if (problem != null && !(showScale && scaleInvalid)) {
                    Text(
                        text = addToShoppingProblemText(problem),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (problem == AddToShoppingOutcome.Unauthorized) {
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                        ) {
                            Text(stringResource(R.string.problem_open_settings))
                        }
                    }
                    // La lista ya no está: lo útil es volver a leer las listas sin
                    // cerrar el diálogo, en vez de decirle que lo intente a ciegas.
                    if (problem == AddToShoppingOutcome.ListMissing) {
                        TextButton(
                            onClick = onRetryLists,
                            enabled = !sending,
                            modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                        ) {
                            Text(stringResource(R.string.extras_compra_lists_retry))
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !sending,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.extras_compra_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = showLists && state.canSend,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (sending) BusyIndicator()
                Text(
                    stringResource(
                        if (sending) R.string.extras_compra_sending else R.string.extras_compra_send,
                    ),
                )
            }
        },
    )
}

/**
 * El campo del número. Se llama «Raciones» cuando la receta dice para cuántas
 * es (y arranca con las que estén elegidas en el detalle) o «Veces la receta»
 * cuando no lo dice. El texto de ayuda dice siempre a qué se refiere el número,
 * y el error que ese número no vale.
 */
@Composable
private fun ScaleInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    invalid: Boolean,
    usesServings: Boolean,
) {
    val errorText = stringResource(
        if (usesServings) R.string.extras_compra_invalid_scale else R.string.extras_compra_invalid_times,
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // El error se queda pegado al campo, que es donde se corrige.
        modifier = Modifier
            .fillMaxWidth()
            .fieldError(invalid, errorText),
        label = {
            Text(
                stringResource(
                    if (usesServings) R.string.extras_compra_scale_label else R.string.extras_compra_times_label,
                ),
            )
        },
        placeholder = { Text(stringResource(R.string.extras_compra_scale_placeholder)) },
        // Lo que escribe la persona lo valida el núcleo; aquí solo se refleja.
        supportingText = {
            Text(
                if (invalid) {
                    errorText
                } else {
                    stringResource(
                        if (usesServings) R.string.extras_compra_scale_hint else R.string.extras_compra_times_hint,
                    )
                },
            )
        },
        isError = invalid,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
    )
}

/** Las listas entre las que elegir, con su carga y sus fallos. */
@Composable
private fun ListChoices(
    picker: ShoppingListPickerState,
    sending: Boolean,
    onSelectList: (String) -> Unit,
    onRetryLists: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(
            text = stringResource(R.string.extras_compra_lists_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val problem = picker.problem
        when {
            picker.isLoading || (!picker.loadedOnce && problem == null) -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(Spacing.small))
                Text(
                    text = stringResource(R.string.extras_compra_lists_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            problem != null -> {
                Text(
                    text = pickerProblemText(problem),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    TextButton(
                        onClick = onRetryLists,
                        enabled = !sending,
                        modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.extras_compra_lists_retry))
                    }
                    if (problem == ShoppingListPickerProblem.Unauthorized) {
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                        ) {
                            Text(stringResource(R.string.problem_open_settings))
                        }
                    }
                }
            }

            picker.lists.isEmpty() -> Text(
                text = stringResource(R.string.extras_compra_lists_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> for (list in picker.lists) {
                ListChoiceRow(
                    list = list,
                    selected = list.id == picker.openListId,
                    enabled = !sending,
                    onSelect = { onSelectList(list.id) },
                )
            }
        }
    }
}

/**
 * Una lista del selector. La fila entera es el botón (48 dp) y la marca es un
 * botón de radio, que TalkBack anuncia como «elegida»: el estado no depende
 * solo del color.
 */
@Composable
private fun ListChoiceRow(
    list: ShoppingListSummary,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.MinTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // La fila entera es el botón; el radio solo muestra el estado.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.small))
        Text(
            text = list.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------------- el aviso

/**
 * Texto del aviso de éxito: los ingredientes ya están en la lista. Vive aquí, en
 * un solo sitio, porque lo leen la tarjeta y el aviso corto (`Snackbar`) del
 * detalle de una receta, y tienen que decir exactamente lo mismo.
 *
 * El recuento de recetas lo da el núcleo ([AddToShoppingOutcome.Added.recipes]) y
 * el nombre de la lista ya viene saneado.
 */
@Composable
internal fun addToShoppingAddedText(added: AddToShoppingOutcome.Added): String =
    pluralStringResource(
        R.plurals.extras_compra_added,
        added.recipes,
        added.recipes,
        added.listName,
    )

/**
 * Aviso de después de añadir, encima del contenido y sin taparlo: región viva
 * para que TalkBack lo lea al aparecer, el resultado en una línea y la acción
 * que lleva a la lista. Con «puede que ya se hayan añadido» no se ofrece
 * repetir: solo mirar la compra.
 *
 * Fase 16h: el **éxito** (`Added`) del detalle de una receta ya no pasa por aquí:
 * lo confirma un aviso corto (`Snackbar`, `ui/components/GastronomiconSnackbar.kt`)
 * con «Ver la compra», que se va solo. Esta tarjeta sigue siendo la de la **duda**
 * ([AddToShoppingOutcome.MaybeAdded]), que no puede desaparecer sin que la persona
 * la lea, y la que usa el planificador con sus dos desenlaces.
 */
@Composable
internal fun AddToShoppingNoticeCard(
    notice: AddToShoppingOutcome,
    onLook: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val added = notice as? AddToShoppingOutcome.Added
    val maybe = notice is AddToShoppingOutcome.MaybeAdded
    if (added == null && !maybe) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (maybe) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (maybe) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = if (added != null) {
                    addToShoppingAddedText(added)
                } else {
                    stringResource(R.string.extras_compra_maybe_added)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (onLook != null) {
                Button(
                    onClick = onLook,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    // Con la duda, el botón lleva a mirar; con el éxito, a verla.
                    Text(
                        stringResource(
                            if (maybe) R.string.extras_compra_notice_look else R.string.extras_compra_notice_open,
                        ),
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.extras_compra_notice_dismiss))
            }
        }
    }
}

// -------------------------------------------------------------------- textos

/**
 * Raciones para el campo de texto: decimal con coma y sin fracciones («1,5»,
 * «8»), que es lo que se puede escribir con el teclado numérico. No se usa
 * `RecipeDetailView.servingsText`, que está pensado para leerse («1 1/2») y no
 * se puede volver a escribir tal cual.
 */
internal fun servingsInputText(servings: Double): String =
    BigDecimal.valueOf(servings).stripTrailingZeros().toPlainString().replace('.', ',')

/** Texto de un dato de la tanda que no vale (lo rechaza el núcleo). */
@StringRes
internal fun additionProblemTextRes(problem: RecipeAdditionProblem): Int = when (problem) {
    RecipeAdditionProblem.Empty -> R.string.extras_compra_nothing_to_add
    RecipeAdditionProblem.TooManyRecipes -> R.string.extras_compra_too_many
    RecipeAdditionProblem.InvalidRecipeId -> R.string.extras_compra_invalid_recipe
    RecipeAdditionProblem.InvalidScale -> R.string.extras_compra_invalid_scale
}

/**
 * Texto del fallo que deja el diálogo abierto. Sin «else»: un desenlace nuevo
 * no compila hasta tener su texto (y `Added`/`MaybeAdded` no llegan aquí).
 */
@Composable
internal fun addToShoppingProblemText(problem: AddToShoppingOutcome): String = when (problem) {
    is AddToShoppingOutcome.Invalid -> stringResource(additionProblemTextRes(problem.reason))
    is AddToShoppingOutcome.Failed -> stringResource(errorTextRes(problem.connection))
    AddToShoppingOutcome.Unauthorized -> stringResource(R.string.problem_text_unauthorized)
    AddToShoppingOutcome.Rejected -> stringResource(R.string.extras_compra_rejected)
    AddToShoppingOutcome.ListMissing -> stringResource(R.string.extras_compra_list_missing)
    is AddToShoppingOutcome.Added -> stringResource(R.string.extras_compra_nothing_to_add)
    is AddToShoppingOutcome.MaybeAdded -> stringResource(R.string.extras_compra_maybe_added)
}

/** Texto de no poder leer las listas. Un motivo nuevo no compila hasta tener el suyo. */
@Composable
private fun pickerProblemText(problem: ShoppingListPickerProblem): String = when (problem) {
    ShoppingListPickerProblem.Unauthorized -> stringResource(R.string.problem_text_unauthorized)
    is ShoppingListPickerProblem.Rejected -> stringResource(R.string.extras_compra_lists_rejected)
    is ShoppingListPickerProblem.InvalidData -> stringResource(R.string.extras_compra_lists_rejected)
    is ShoppingListPickerProblem.InputRejected -> stringResource(R.string.extras_compra_lists_rejected)
    is ShoppingListPickerProblem.MaybeCreated -> stringResource(R.string.extras_compra_lists_rejected)
    is ShoppingListPickerProblem.Failed -> stringResource(errorTextRes(problem.problem))
}

// ------------------------------------------------------------------ previews

/** Datos inventados para las previews: ninguna URL, ningún token y nombres de mentira. */
internal object AddToShoppingPreviewData {

    private const val FIRST_LIST = "00000000-0000-4000-8000-000000000001"
    private const val SECOND_LIST = "00000000-0000-4000-8000-000000000002"
    private const val THIRD_LIST = "00000000-0000-4000-8000-000000000003"

    private val lists = listOf(
        ShoppingListSummary(FIRST_LIST, "Compra de la semana"),
        ShoppingListSummary(SECOND_LIST, "Mercado del sábado"),
        ShoppingListSummary(THIRD_LIST, "Cena de cumpleaños"),
    )

    fun picker(
        items: List<ShoppingListSummary> = lists,
        openListId: String? = if (items.isEmpty()) null else items.first().id,
        phase: ShoppingListPickerPhase = ShoppingListPickerPhase.Idle,
        problem: ShoppingListPickerProblem? = null,
        loadedOnce: Boolean = true,
    ) = ShoppingListPickerState(
        lists = items,
        openListId = openListId,
        phase = phase,
        problem = problem,
        loadedOnce = loadedOnce,
    )

    fun ui(
        open: Boolean = false,
        lists: ShoppingListPickerState = picker(),
        scaleText: String = "",
        usesServings: Boolean = true,
        recipes: Int = 0,
        skipped: Int = 0,
        truncated: Boolean = false,
        sending: Boolean = false,
        problem: AddToShoppingOutcome? = null,
        notice: AddToShoppingOutcome? = null,
    ) = AddToShoppingUi(
        available = true,
        open = open,
        picker = lists,
        scaleText = scaleText,
        usesServings = usesServings,
        recipes = recipes,
        skipped = skipped,
        truncated = truncated,
        sending = sending,
        problem = problem,
        notice = notice,
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun AddToShoppingDialogPreview() {
    GastronomiconTheme {
        AddToShoppingDialog(
            state = AddToShoppingPreviewData.ui(open = true, scaleText = "8"),
            title = stringResource(R.string.extras_compra_dialog_title),
            lead = stringResource(R.string.extras_compra_dialog_lead, "Arroz con verduras"),
            showScale = true,
            showLists = true,
            onSelectList = {},
            onScaleChange = {},
            onRetryLists = {},
            onConfirm = {},
            onDismiss = {},
            onOpenSettings = {},
        )
    }
}

/** Receta que no dice para cuántas raciones es: el campo cuenta «veces la receta». */
@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun AddToShoppingDialogTimesPreview() {
    GastronomiconTheme {
        AddToShoppingDialog(
            state = AddToShoppingPreviewData.ui(
                open = true,
                scaleText = "1",
                usesServings = false,
            ),
            title = stringResource(R.string.extras_compra_dialog_title),
            lead = stringResource(R.string.extras_compra_dialog_lead_times, "Arroz con verduras"),
            showScale = true,
            showLists = true,
            onSelectList = {},
            onScaleChange = {},
            onRetryLists = {},
            onConfirm = {},
            onDismiss = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun AddToShoppingDialogLargeTextPreview() {
    GastronomiconTheme {
        AddToShoppingDialog(
            state = AddToShoppingPreviewData.ui(
                open = true,
                problem = AddToShoppingOutcome.Invalid(RecipeAdditionProblem.InvalidScale),
                scaleText = "dos",
            ),
            title = stringResource(R.string.extras_compra_dialog_title),
            lead = stringResource(R.string.extras_compra_dialog_lead, "Arroz con verduras"),
            showScale = true,
            showLists = true,
            onSelectList = {},
            onScaleChange = {},
            onRetryLists = {},
            onConfirm = {},
            onDismiss = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddToShoppingDialogDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        AddToShoppingDialog(
            state = AddToShoppingPreviewData.ui(
                open = true,
                sending = true,
                recipes = 4,
            ),
            title = stringResource(R.string.extras_compra_week_title),
            lead = pluralStringResource(R.plurals.extras_compra_week_lead, 4, 4),
            showScale = false,
            showLists = true,
            onSelectList = {},
            onScaleChange = {},
            onRetryLists = {},
            onConfirm = {},
            onDismiss = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun AddToShoppingNoticePreview() {
    GastronomiconTheme {
        Surface {
            AddToShoppingNoticeCard(
                notice = AddToShoppingOutcome.Added("Compra de la semana", 1),
                onLook = {},
                onDismiss = {},
                modifier = Modifier.padding(Spacing.medium),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun AddToShoppingMaybeAddedLargeTextPreview() {
    GastronomiconTheme {
        Surface {
            AddToShoppingNoticeCard(
                notice = AddToShoppingOutcome.MaybeAdded(ConnectionProblem.ServerNotResponding),
                onLook = {},
                onDismiss = {},
                modifier = Modifier.padding(Spacing.medium),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddToShoppingNoticeDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        Surface {
            AddToShoppingNoticeCard(
                notice = AddToShoppingOutcome.Added("Compra de la semana", 6),
                onLook = null,
                onDismiss = {},
                modifier = Modifier.padding(Spacing.medium),
            )
        }
    }
}
