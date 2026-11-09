package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frontera de saneado: todo lo que escribe la persona pasa por aquí antes de salir hacia el servidor. */
class RecipeDraftValidatorTest {

    private val id1 = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val id2 = "1f8fad5b-d9cb-469f-a165-70867728950e"

    private fun valid(draft: RecipeDraft): RecipeDraft =
        (RecipeDraftValidator.validate(draft) as DraftValidation.Valid).draft

    private fun errors(draft: RecipeDraft): List<DraftError> =
        (RecipeDraftValidator.validate(draft) as DraftValidation.Invalid).errors

    private fun base() = RecipeDraft(name = "Tarta")

    // ---------------------------------------------------------------- nombre

    @Test
    fun `el nombre es obligatorio`() {
        for (name in listOf("", "   ", "\n\t", "‮​")) {
            assertEquals(name, listOf(DraftError(DraftField.Name, DraftErrorKind.Required)), errors(RecipeDraft(name = name)))
        }
    }

    @Test
    fun `el nombre se sanea sin controles ni bidireccionales y con espacios colapsados`() {
        val clean = valid(RecipeDraft(name = "  Tarta‮  de   manzana\u0007\n"))
        assertEquals("Tarta de manzana", clean.name)
    }

    @Test
    fun `el limite del nombre es exacto y no se recorta en silencio`() {
        assertEquals(300, valid(RecipeDraft(name = "a".repeat(300))).name.length)
        assertEquals(
            listOf(DraftError(DraftField.Name, DraftErrorKind.TooLong)),
            errors(RecipeDraft(name = "a".repeat(301))),
        )
        // Un texto enorme no se procesa entero: el saneado corta a max+1.
        assertEquals(
            listOf(DraftError(DraftField.Name, DraftErrorKind.TooLong)),
            errors(RecipeDraft(name = "a".repeat(5_000_000))),
        )
    }

    @Test
    fun `el limite cuenta puntos de codigo y no unidades UTF-16`() {
        val emoji = "🍰"
        assertEquals(300, valid(RecipeDraft(name = emoji.repeat(300))).name.codePointCount(0, 600))
        assertEquals(1, errors(RecipeDraft(name = emoji.repeat(301))).size)
    }

    @Test
    fun `el html se queda como texto plano y no se interpreta`() {
        val clean = valid(base().copy(description = "<script>alert(1)</script> <b>hola</b>"))
        assertEquals("<script>alert(1)</script> <b>hola</b>", clean.description)
    }

    @Test
    fun `la descripcion conserva dos saltos como mucho`() {
        assertEquals("a\n\nb", valid(base().copy(description = "a\n\n\n\n\nb")).description)
    }

    @Test
    fun `validateName usa el mismo criterio`() {
        assertEquals("Hola" to emptyList<DraftError>(), RecipeDraftValidator.validateName(" Hola "))
        val (name, errors) = RecipeDraftValidator.validateName("  ")
        assertNull(name)
        assertEquals(listOf(DraftError(DraftField.Name, DraftErrorKind.Required)), errors)
        assertEquals(DraftErrorKind.TooLong, RecipeDraftValidator.validateName("a".repeat(301)).second.single().kind)
    }

    // ---------------------------------------------------- raciones y tiempos

    @Test
    fun `las raciones tienen que ser un numero finito y positivo`() {
        assertNull(valid(base()).servings)
        assertEquals(4.0, valid(base().copy(servings = 4.0)).servings!!, 0.0)
        assertEquals(10_000.0, valid(base().copy(servings = 10_000.0)).servings!!, 0.0)
        val cases = mapOf(
            Double.NaN to DraftErrorKind.NotFinite,
            Double.POSITIVE_INFINITY to DraftErrorKind.NotFinite,
            Double.NEGATIVE_INFINITY to DraftErrorKind.NotFinite,
            0.0 to DraftErrorKind.OutOfRange,
            -1.0 to DraftErrorKind.OutOfRange,
            10_000.5 to DraftErrorKind.OutOfRange,
        )
        for ((value, kind) in cases) {
            assertEquals(
                value.toString(),
                listOf(DraftError(DraftField.Servings, kind)),
                errors(base().copy(servings = value)),
            )
        }
    }

    @Test
    fun `los tiempos se sanean y tienen limite`() {
        val clean = valid(base().copy(prepTime = " 20 ‮ min ", cookTime = "1 h", totalTime = "x", performTime = "y"))
        assertEquals("20 min", clean.prepTime)
        assertEquals(
            listOf(DraftError(DraftField.CookTime, DraftErrorKind.TooLong)),
            errors(base().copy(cookTime = "a".repeat(101))),
        )
        assertEquals(100, valid(base().copy(prepTime = "a".repeat(100))).prepTime.length)
    }

    // ----------------------------------------------------------- ingredientes

    @Test
    fun `cantidades no finitas negativas o enormes se rechazan por fila`() {
        val rows = listOf(
            DraftIngredient(note = "ok"),
            DraftIngredient(quantity = Double.NaN, note = "a"),
            DraftIngredient(quantity = -1.0, note = "b"),
            DraftIngredient(quantity = 1_000_000.5, note = "c"),
            DraftIngredient(quantity = Double.POSITIVE_INFINITY, note = "d"),
        )
        assertEquals(
            listOf(
                DraftError(DraftField.IngredientQuantity, DraftErrorKind.NotFinite, 1),
                DraftError(DraftField.IngredientQuantity, DraftErrorKind.OutOfRange, 2),
                DraftError(DraftField.IngredientQuantity, DraftErrorKind.OutOfRange, 3),
                DraftError(DraftField.IngredientQuantity, DraftErrorKind.NotFinite, 4),
            ),
            errors(base().copy(ingredients = rows)),
        )
    }

    @Test
    fun `una linea nueva en blanco se descarta y una con solo cantidad es un error`() {
        val clean = valid(
            base().copy(ingredients = listOf(DraftIngredient(note = "sal"), DraftIngredient(), DraftIngredient(note = "  "))),
        )
        assertEquals(listOf("sal"), clean.ingredients.map { it.note })
        assertEquals(
            listOf(DraftError(DraftField.IngredientNote, DraftErrorKind.Required, 0)),
            errors(base().copy(ingredients = listOf(DraftIngredient(quantity = 2.0)))),
        )
        // Cantidad cero y nada más: es una fila vacía.
        assertTrue(valid(base().copy(ingredients = listOf(DraftIngredient(quantity = 0.0)))).ingredients.isEmpty())
    }

    @Test
    fun `una linea que ya existia se conserva aunque se vea vacia`() {
        val clean = valid(base().copy(ingredients = listOf(DraftIngredient(sourceId = id1.uppercase()))))
        assertEquals(id1, clean.ingredients.single().sourceId)
    }

    @Test
    fun `unidad y alimento se eligen del catalogo y se validan sus ids`() {
        val unitId = "11111111-1111-4111-8111-111111111111"
        val foodId = "22222222-2222-4222-8222-222222222222"
        // Una línea nueva ya puede llevar unidad y alimento, con id o solo nombre.
        val clean = valid(
            base().copy(
                ingredients = listOf(
                    DraftIngredient(quantity = 2.0, unit = "g", unitId = unitId, food = "harina", foodId = foodId),
                    DraftIngredient(note = "pizca", food = "sal"),
                ),
            ),
        )
        assertEquals(unitId, clean.ingredients[0].unitId)
        assertEquals(foodId, clean.ingredients[0].foodId)
        assertEquals("harina", clean.ingredients[0].food)
        assertNull(clean.ingredients[1].foodId)
        assertEquals("sal", clean.ingredients[1].food)
    }

    @Test
    fun `un id de unidad o alimento que no es un UUID se rechaza`() {
        assertEquals(
            listOf(
                DraftError(DraftField.IngredientUnit, DraftErrorKind.Invalid, 0),
                DraftError(DraftField.IngredientFood, DraftErrorKind.Invalid, 0),
            ),
            errors(
                base().copy(
                    ingredients = listOf(
                        DraftIngredient(note = "a", unit = "g", unitId = "../x", food = "harina", foodId = "no"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `un id de unidad o alimento sin nombre es un error`() {
        // `IngredientUnit-Input` e `IngredientFood-Input` exigen id y nombre juntos.
        assertEquals(
            listOf(
                DraftError(DraftField.IngredientUnit, DraftErrorKind.Required, 0),
                DraftError(DraftField.IngredientFood, DraftErrorKind.Required, 0),
            ),
            errors(
                base().copy(
                    ingredients = listOf(
                        DraftIngredient(
                            unitId = "11111111-1111-4111-8111-111111111111",
                            foodId = "22222222-2222-4222-8222-222222222222",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `un id de unidad o alimento sin nombre en una linea existente no bloquea`() {
        // El servidor puede devolver un nombre que el saneado deja vacío. En una
        // línea que ya existía, el id se descarta y se trata como «sin cambio»:
        // no puede impedir guardar el resto de la receta.
        val clean = valid(
            base().copy(
                ingredients = listOf(
                    DraftIngredient(
                        sourceId = id1,
                        unitId = "11111111-1111-4111-8111-111111111111",
                        foodId = "22222222-2222-4222-8222-222222222222",
                    ),
                ),
            ),
        )
        val ingredient = clean.ingredients.single()
        assertEquals(id1, ingredient.sourceId)
        assertNull(ingredient.unitId)
        assertNull(ingredient.foodId)
    }

    @Test
    fun `el nombre de una unidad o alimento nuevo tiene su propio limite`() {
        assertEquals(
            listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.TooLong, 0)),
            errors(base().copy(ingredients = listOf(DraftIngredient(note = "a", unit = "u".repeat(101))))),
        )
        assertEquals(
            listOf(DraftError(DraftField.IngredientFood, DraftErrorKind.TooLong, 0)),
            errors(base().copy(ingredients = listOf(DraftIngredient(note = "a", food = "f".repeat(101))))),
        )
    }

    @Test
    fun `el nombre de una unidad o alimento nuevo se sanea`() {
        val (unit, unitErrors) = RecipeDraftValidator.validateUnitName("  gra\u0007mo  ")
        assertEquals("gramo", unit)
        assertTrue(unitErrors.isEmpty())
        assertEquals(
            listOf(DraftError(DraftField.IngredientFood, DraftErrorKind.Required)),
            RecipeDraftValidator.validateFoodName("   ").second,
        )
        assertNull(RecipeDraftValidator.validateFoodName("   ").first)
        assertEquals(
            listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.TooLong)),
            RecipeDraftValidator.validateUnitName("a".repeat(101)).second,
        )
    }

    @Test
    fun `ids de linea invalidos o repetidos se rechazan`() {
        assertEquals(
            listOf(DraftError(DraftField.Ingredients, DraftErrorKind.Invalid, 0)),
            errors(base().copy(ingredients = listOf(DraftIngredient(note = "a", sourceId = "../x")))),
        )
        assertEquals(
            listOf(DraftError(DraftField.Steps, DraftErrorKind.Duplicate, 1)),
            errors(base().copy(steps = listOf(DraftStep(text = "a", sourceId = id1), DraftStep(text = "b", sourceId = id1.uppercase())))),
        )
        assertEquals(
            listOf(DraftError(DraftField.Notes, DraftErrorKind.Invalid, 0)),
            errors(base().copy(notes = listOf(DraftNote(text = "a", sourceId = "no")))),
        )
    }

    @Test
    fun `los textos de una linea tienen limite`() {
        assertEquals(
            listOf(
                DraftError(DraftField.IngredientNote, DraftErrorKind.TooLong, 0),
                DraftError(DraftField.IngredientTitle, DraftErrorKind.TooLong, 0),
            ),
            errors(base().copy(ingredients = listOf(DraftIngredient(note = "a".repeat(501), title = "b".repeat(501))))),
        )
    }

    @Test
    fun `demasiados elementos se rechazan sin recorrerlos`() {
        val many = List(501) { DraftIngredient(note = "x") }
        assertEquals(listOf(DraftError(DraftField.Ingredients, DraftErrorKind.TooMany)), errors(base().copy(ingredients = many)))
        assertEquals(listOf(DraftError(DraftField.Steps, DraftErrorKind.TooMany)), errors(base().copy(steps = List(501) { DraftStep(text = "x") })))
        assertEquals(listOf(DraftError(DraftField.Notes, DraftErrorKind.TooMany)), errors(base().copy(notes = List(101) { DraftNote(text = "x") })))
        assertEquals(500, valid(base().copy(ingredients = List(500) { DraftIngredient(note = "x") })).ingredients.size)
    }

    // ------------------------------------------------------ pasos y notas

    @Test
    fun `pasos y notas nuevos en blanco se descartan y el resto se sanea`() {
        val clean = valid(
            base().copy(
                steps = listOf(DraftStep(), DraftStep(title = " Mezclar ", text = "Uno\u0000\n\n\n\nDos")),
                notes = listOf(DraftNote(title = " ", text = ""), DraftNote(text = "Truco")),
            ),
        )
        assertEquals(1, clean.steps.size)
        assertEquals("Mezclar", clean.steps[0].title)
        assertEquals("Uno\n\nDos", clean.steps[0].text)
        assertEquals(listOf("Truco"), clean.notes.map { it.text })
    }

    @Test
    fun `el texto de un paso tiene limite con su fila`() {
        assertEquals(
            listOf(DraftError(DraftField.StepText, DraftErrorKind.TooLong, 1)),
            errors(base().copy(steps = listOf(DraftStep(text = "ok"), DraftStep(text = "a".repeat(10_001))))),
        )
        assertEquals(
            listOf(DraftError(DraftField.NoteText, DraftErrorKind.TooLong, 0)),
            errors(base().copy(notes = listOf(DraftNote(text = "a".repeat(10_001))))),
        )
    }

    // ------------------------------------------------- etiquetas y categorias

    @Test
    fun `etiquetas con id y slug validos pasan y normalizan el id`() {
        val clean = valid(base().copy(tags = listOf(DraftOrganizer(id = id1.uppercase(), name = " Rápido ", slug = "rapido"))))
        assertEquals(DraftOrganizer(id1, "Rápido", "rapido"), clean.tags.single())
    }

    @Test
    fun `una etiqueta nueva solo por nombre se admite`() {
        assertEquals(DraftOrganizer(null, "Cena", null), valid(base().copy(categories = listOf(DraftOrganizer(name = "Cena")))).categories.single())
    }

    @Test
    fun `id slug o nombre invalidos y repetidos se rechazan`() {
        assertEquals(
            listOf(DraftError(DraftField.Tags, DraftErrorKind.Invalid, 0)),
            errors(base().copy(tags = listOf(DraftOrganizer(id = "no-es-uuid", name = "a")))),
        )
        assertEquals(
            listOf(DraftError(DraftField.Categories, DraftErrorKind.Invalid, 0)),
            errors(base().copy(categories = listOf(DraftOrganizer(name = "a", slug = "../x")))),
        )
        assertEquals(
            listOf(DraftError(DraftField.Tags, DraftErrorKind.Duplicate, 1)),
            errors(base().copy(tags = listOf(DraftOrganizer(name = "Postre"), DraftOrganizer(name = "POSTRE")))),
        )
        assertEquals(
            listOf(DraftError(DraftField.Tags, DraftErrorKind.Required, 0)),
            errors(base().copy(tags = listOf(DraftOrganizer(id = id2, name = " ")))),
        )
        assertEquals(
            listOf(DraftError(DraftField.Tags, DraftErrorKind.TooLong, 0)),
            errors(base().copy(tags = listOf(DraftOrganizer(name = "a".repeat(101))))),
        )
        assertEquals(listOf(DraftError(DraftField.Categories, DraftErrorKind.TooMany)), errors(base().copy(categories = List(101) { DraftOrganizer(name = "c$it") })))
    }

    @Test
    fun `una etiqueta nueva sin nombre se descarta`() {
        assertTrue(valid(base().copy(tags = listOf(DraftOrganizer(name = "  ")))).tags.isEmpty())
    }

    @Test
    fun `los errores se acumulan y no llevan texto de la persona`() {
        val result = errors(
            RecipeDraft(
                name = "",
                servings = Double.NaN,
                ingredients = listOf(DraftIngredient(quantity = -1.0, note = "SECRETO-DE-LA-PERSONA")),
            ),
        )
        assertEquals(3, result.size)
        assertFalse(result.toString().contains("SECRETO"))
    }
}
