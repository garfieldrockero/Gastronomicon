package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeImageTest {

    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun `la ruta usa el id y el fichero del tamaño`() {
        assertEquals("/api/media/recipes/$id/images/min-original.webp", RecipeImage.path(id))
        assertEquals("/api/media/recipes/$id/images/original.webp", RecipeImage.path(id, ImageSize.Original))
        assertEquals("/api/media/recipes/$id/images/tiny-original.webp", RecipeImage.path(id, ImageSize.Tiny))
    }

    @Test
    fun `el id se pasa a minusculas`() {
        assertEquals("/api/media/recipes/$id/images/min-original.webp", RecipeImage.path(id.uppercase()))
    }

    @Test
    fun `un id que no es un UUID no da ruta`() {
        for (bad in listOf(null, "", "tortilla", "../x", "$id/../x", "$id\\n", " $id", "0f8fad5b-d9cb-469f-a165", "g${id.drop(1)}")) {
            assertNull(bad, RecipeImage.path(bad))
            assertNull(bad, RecipeImage.request(bad))
        }
    }

    @Test
    fun `la peticion es un GET a esa ruta`() {
        val request = RecipeImage.request(id)!!
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/api/media/recipes/$id/images/min-original.webp", request.path)
        assertNull(request.query)
    }

    @Test
    fun `la peticion pide solo imagenes permitidas`() {
        val accept = RecipeImage.request(id)!!.headers["Accept"]!!
        assertEquals("image/webp,image/jpeg,image/png", accept)
    }
}
