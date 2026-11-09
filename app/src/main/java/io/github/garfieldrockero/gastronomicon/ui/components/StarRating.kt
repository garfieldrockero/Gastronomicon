package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.recipes.RatingStars

/**
 * Estrellas de una valoración. [stars] ya viene redondeada de 0 a 5 desde el núcleo
 * (`ratingStars`); aquí solo se dibujan. TalkBack no lee las estrellas sueltas: dice la
 * valoración con palabras.
 */
@Composable
fun StarRating(
    stars: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    val filled = stars.coerceIn(0, RatingStars.MAX_STARS)
    val description = stringResource(R.string.detail_rating, filled.toString())
    Text(
        text = "★".repeat(filled) + "☆".repeat(RatingStars.MAX_STARS - filled),
        style = style,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    )
}
