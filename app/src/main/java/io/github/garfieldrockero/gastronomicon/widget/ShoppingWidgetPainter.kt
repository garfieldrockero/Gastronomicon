package io.github.garfieldrockero.gastronomicon.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetLayout
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetStatus
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetView
import java.util.Date

/**
 * Convierte la vista ya decidida por el núcleo ([ShoppingWidgetView]) en
 * `RemoteViews`. Aquí no se calcula nada de la lista: las filas que caben, los
 * contadores, el «y N más» y la línea de estado vienen hechos; esto solo copia
 * textos, oculta lo que sobra y pone los `PendingIntent`.
 *
 * Reglas:
 * - Todo lo que se pinta viaja al proceso del launcher: solo textos ya saneados
 *   por el núcleo, contadores y la hora de la copia. Ni URL, ni usuario, ni
 *   token, ni `rawJson`.
 * - Los colores y tamaños van explícitos en el layout (el launcher no tiene
 *   nuestro tema); el modo oscuro lo resuelve `values-night`.
 * - Cada fila es un objetivo táctil completo de 48 dp con su descripción de
 *   TalkBack; la casilla vacía del principio es decorativa.
 * - El número de filas sale del alto que da el launcher en la orientación actual
 *   y del tamaño de letra del sistema ([ShoppingWidgetLayout.rowsFor]): con
 *   texto grande caben menos.
 */
internal object ShoppingWidgetPainter {

    private val rowContainerIds = intArrayOf(
        R.id.widget_row_1,
        R.id.widget_row_2,
        R.id.widget_row_3,
        R.id.widget_row_4,
        R.id.widget_row_5,
        R.id.widget_row_6,
        R.id.widget_row_7,
        R.id.widget_row_8,
    )

    private val rowTextIds = intArrayOf(
        R.id.widget_row_text_1,
        R.id.widget_row_text_2,
        R.id.widget_row_text_3,
        R.id.widget_row_text_4,
        R.id.widget_row_text_5,
        R.id.widget_row_text_6,
        R.id.widget_row_text_7,
        R.id.widget_row_text_8,
    )

    /** Repinta todos los widgets de la app con [snapshot] y [status]. */
    fun paintAll(context: Context, snapshot: ShoppingWidgetSnapshot?, status: ShoppingWidgetStatus) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, ShoppingWidgetProvider::class.java))
        paint(context, manager, ids, snapshot, status)
    }

    /** Repinta solo [ids] (el sistema puede pedir un subconjunto). */
    fun paint(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        snapshot: ShoppingWidgetSnapshot?,
        status: ShoppingWidgetStatus,
    ) {
        if (ids.isEmpty()) return
        for (id in ids) {
            val rows = rowsFor(context, manager, id)
            val view = ShoppingWidgetView.from(snapshot, maxRows = rows, status = status)
            manager.updateAppWidget(id, build(context, view))
        }
    }

    /**
     * Filas que caben en [id].
     *
     * El launcher da el alto en dp **según la orientación**: en vertical el del
     * widget es `OPTION_APPWIDGET_MAX_HEIGHT` y `OPTION_APPWIDGET_MIN_HEIGHT` es
     * el de horizontal (en horizontal, al revés). Se pide el de la orientación
     * actual y, si ese no viene, se prueba el otro antes de darse por vencido
     * ([ShoppingWidgetLayout.DEFAULT_ROWS]).
     */
    private fun rowsFor(context: Context, manager: AppWidgetManager, id: Int): Int {
        val configuration = context.resources.configuration
        val portrait = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val current = if (portrait) {
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        } else {
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        }
        val other = if (portrait) {
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        } else {
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        }
        val height = try {
            val options = manager.getAppWidgetOptions(id)
            val fromCurrent = options.getInt(current, 0)
            if (fromCurrent > 0) fromCurrent else options.getInt(other, 0)
        } catch (ignored: RuntimeException) {
            0
        }
        return ShoppingWidgetLayout.rowsFor(height, configuration.fontScale)
    }

    private fun build(context: Context, view: ShoppingWidgetView): RemoteViews {
        val remote = RemoteViews(context.packageName, R.layout.widget_shopping)

        val hasSnapshot = view.updatedAtMillis != null
        val hasRows = view.hasRows

        // Cabecera: nombre, contadores y botón de actualizar.
        val counts = if (hasSnapshot && !view.isEmpty) {
            val pending = context.resources.getQuantityString(
                R.plurals.shopping_pending,
                view.pendingCount,
                view.pendingCount,
            )
            val checked = context.resources.getQuantityString(
                R.plurals.shopping_checked,
                view.checkedCount,
                view.checkedCount,
            )
            context.getString(R.string.widget_counts_format, pending, checked)
        } else {
            null
        }
        remote.setTextViewText(R.id.widget_title, view.listName)
        if (counts != null) {
            remote.setTextViewText(R.id.widget_counts, counts)
            remote.setViewVisibility(R.id.widget_counts, View.VISIBLE)
            remote.setContentDescription(
                R.id.widget_header,
                context.getString(R.string.widget_header_description, view.listName, counts),
            )
        } else {
            remote.setViewVisibility(R.id.widget_counts, View.GONE)
            remote.setContentDescription(
                R.id.widget_header,
                context.getString(R.string.widget_title_description, view.listName),
            )
        }
        remote.setOnClickPendingIntent(R.id.widget_header, ShoppingWidgetIntents.open(context))
        remote.setOnClickPendingIntent(R.id.widget_refresh, ShoppingWidgetIntents.refresh(context))

        // Filas pendientes que caben. Las demás se ocultan.
        for (index in rowContainerIds.indices) {
            val container = rowContainerIds[index]
            val row = view.rows.getOrNull(index)
            if (row == null) {
                remote.setViewVisibility(container, View.GONE)
                continue
            }
            remote.setViewVisibility(container, View.VISIBLE)
            remote.setTextViewText(rowTextIds[index], row.text)
            remote.setContentDescription(
                container,
                context.getString(R.string.widget_mark_item, row.text),
            )
            remote.setOnClickPendingIntent(container, ShoppingWidgetIntents.toggle(context, row.itemId))
        }

        // «y N más».
        val more = view.moreText
        if (hasRows && more != null) {
            remote.setTextViewText(R.id.widget_more, more)
            remote.setViewVisibility(R.id.widget_more, View.VISIBLE)
        } else {
            remote.setViewVisibility(R.id.widget_more, View.GONE)
        }

        // Estado vacío: se lleva el mensaje (que ya es fijo) y abre la app. El
        // título ya dice qué hacer, así que no se repite con una segunda línea.
        if (hasRows) {
            remote.setViewVisibility(R.id.widget_empty, View.GONE)
        } else {
            val text = when {
                view.hasStatus -> view.statusText
                view.isEmpty -> context.getString(R.string.widget_empty_list)
                // La lista tiene cosas, pero todas marcadas: el aviso es el de la app.
                view.pendingCount == 0 -> context.getString(R.string.shopping_all_done)
                else -> context.getString(R.string.widget_empty_open)
            }
            remote.setTextViewText(R.id.widget_empty_title, text)
            remote.setContentDescription(R.id.widget_empty, text)
            remote.setOnClickPendingIntent(R.id.widget_empty, ShoppingWidgetIntents.open(context))
            remote.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        }

        // Línea de estado: solo con filas. Vacía, el mensaje ya está arriba.
        if (hasRows && view.hasStatus) {
            remote.setTextViewText(R.id.widget_status, view.statusText)
            remote.setViewVisibility(R.id.widget_status, View.VISIBLE)
        } else {
            remote.setViewVisibility(R.id.widget_status, View.GONE)
        }

        // Hora de la copia, en formato absoluto (no hace falta repintar cada minuto).
        val updatedAt = view.updatedAtMillis
        if (updatedAt != null) {
            val time = DateFormat.getTimeFormat(context).format(Date(updatedAt))
            remote.setTextViewText(R.id.widget_updated, context.getString(R.string.widget_updated_at, time))
            remote.setViewVisibility(R.id.widget_updated, View.VISIBLE)
        } else {
            remote.setViewVisibility(R.id.widget_updated, View.GONE)
        }

        return remote
    }
}
