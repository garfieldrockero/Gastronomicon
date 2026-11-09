package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityParserTest {
    @Test fun decimalsWithCommaAndDot() {
        assertEquals(1.5, QuantityParser.parse("1,5")!!, 1e-9)
        assertEquals(1.5, QuantityParser.parse("1.5")!!, 1e-9)
        assertEquals(2.0, QuantityParser.parse(" 2 ")!!, 1e-9)
    }

    @Test fun fractions() {
        assertEquals(0.5, QuantityParser.parse("1/2")!!, 1e-9)
        assertEquals(1.5, QuantityParser.parse("1 1/2")!!, 1e-9)
        assertEquals(0.75, QuantityParser.parse("¾")!!, 1e-9)
        assertEquals(1.5, QuantityParser.parse("1½")!!, 1e-9)
        assertEquals(2.5, QuantityParser.parse("2 ½")!!, 1e-9)
    }

    @Test fun rejectsNonsense() {
        assertNull(QuantityParser.parse(""))
        assertNull(QuantityParser.parse("abc"))
        assertNull(QuantityParser.parse("1/0"))
        assertNull(QuantityParser.parse("1/2/3"))
        assertNull(QuantityParser.parse("-1/2"))
        assertNull(QuantityParser.parse("1 2 3"))
        assertNull(QuantityParser.parse("NaN"))
        assertNull(QuantityParser.parse("1e999"))
    }
}
