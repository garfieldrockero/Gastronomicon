package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageSize
import io.github.garfieldrockero.gastronomicon.platform.ImageLoadResult
import io.github.garfieldrockero.gastronomicon.platform.ImageLoader

/**
 * Foto de una receta con carga propia ([ImageLoader]). Mientras llega, o si no hay
 * foto o no se puede cargar (sin imagen, sesión caducada, fallo de red), se ve el
 * plato del marcador: nunca un mensaje técnico. Si la sesión ha caducado, el aviso
 * lo da la pantalla, no cada foto.
 *
 * La foto es decorativa: el nombre de la receta ya está al lado como texto, así que
 * TalkBack no la lee ([contentDescription] null). Quien la use sin texto al lado
 * debe pasar una descripción.
 *
 * Sin [loader] (previews) solo se ve el marcador. La carga se cancela sola al salir
 * de la pantalla o al desplazar la fila fuera de la vista.
 */
@Composable
fun RecipePhoto(
    recipeId: String?,
    loader: ImageLoader?,
    maxSidePx: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val cached = if (recipeId != null && loader != null) loader.cached(recipeId, ImageSize.Min, maxSidePx) else null
    var bitmap by remember(recipeId, loader, maxSidePx) { mutableStateOf(cached) }

    LaunchedEffect(recipeId, loader, maxSidePx) {
        if (recipeId == null || loader == null || bitmap != null) return@LaunchedEffect
        val result = loader.load(recipeId, ImageSize.Min, maxSidePx)
        if (result is ImageLoadResult.Loaded) bitmap = result.bitmap
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_recipe_placeholder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PlaceholderIconSize),
            )
        }
    }
}

private val PlaceholderIconSize = 40.dp
