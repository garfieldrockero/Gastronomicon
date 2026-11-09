package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Resultado de guardar una receta en la caché o un borrador.
 *
 * No lanza: la interfaz nunca tiene que envolver estas llamadas en `try`.
 */
sealed interface OfflineWrite {

    data object Saved : OfflineWrite

    /**
     * No cabe en el tope por entrada: no se ha guardado y se ha quitado la
     * copia anterior (servir una versión vieja como si fuera nueva sería peor).
     */
    data object TooLarge : OfflineWrite

    /** El identificador (slug) no es válido: no se ha tocado el almacén. */
    data object Rejected : OfflineWrite

    data class Failed(val problem: StorageProblem) : OfflineWrite
}

/** Resultado de leer una entrada concreta. */
sealed interface OfflineRead<out T> {

    /**
     * Se ha encontrado y ha pasado el saneado. [T] es `CachedRecipe` o
     * `CachedDraft`, que redactan su `toString`.
     */
    data class Hit<out T>(val value: T) : OfflineRead<T> {
        // Un resultado puede acabar en un log: que no arrastre contenido.
        override fun toString(): String = "Hit"
    }

    /** No hay entrada, ha caducado o se ha descartado por no ser de fiar. */
    data object Miss : OfflineRead<Nothing>

    /** El almacén no responde. No significa que no haya datos: se puede reintentar. */
    data class Failed(val problem: StorageProblem) : OfflineRead<Nothing>
}

/** Resultado de enumerar entradas guardadas. */
sealed interface OfflineList<out T> {

    data class Ok<out T>(val items: List<T>) : OfflineList<T>

    data class Failed(val problem: StorageProblem) : OfflineList<Nothing>
}
