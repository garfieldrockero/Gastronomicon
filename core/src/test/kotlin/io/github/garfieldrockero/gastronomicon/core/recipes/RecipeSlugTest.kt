package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeSlugTest {

    @Test
    fun `slugs de Mealie pasan`() {
        for (slug in listOf("tortilla", "tortilla-de-patatas", "Tortilla_1", "7", "a-b-c-2", "a".repeat(255))) {
            assertTrue(slug, RecipeSlug.isValid(slug))
            assertEquals(slug, RecipeSlug.checked(slug))
        }
    }

    @Test
    fun `todo lo que cambiaria la ruta o la consulta se rechaza`() {
        val bad = listOf(
            null, "", " ", "a b", ".", "..", "../x", "a/b", "/a", "a/", "a\\b", "a%2f", "a%2F", "a%2e", "a%", "%41",
            "a?b", "a#b", "a;b", "a:b", "a@b", "a=b", "a&b", "a+b", "a,b", "a'b", "a\"b", "a<b", "a>b", "a|b",
            "a\nb", "a\rb", "a\tb", "a\u0000b", "tortilla\n", "\ntortilla", "añ", "ñ", "日本", "a‮b", "a​b",
            "a".repeat(256),
        )
        for (slug in bad) {
            assertFalse("«${slug?.replace("\n", "\\n")}»", RecipeSlug.isValid(slug))
            assertThrows(IllegalArgumentException::class.java) { RecipeSlug.checked(slug) }
        }
    }

    @Test
    fun `el mensaje no repite el valor`() {
        val error = assertThrows(IllegalArgumentException::class.java) { RecipeSlug.checked("../secreto-123") }
        assertFalse(error.message!!.contains("secreto"))
    }
}
