package io.github.garfieldrockero.gastronomicon.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Coacción de las preferencias: un valor manipulado no puede dejar la alarma a
 * una hora imposible ni repetir el aviso del mismo día.
 */
class ReminderSettingsTest {

    private val today = LocalDate.of(2026, 11, 9)

    @Test
    fun `el valor de fabrica esta apagado y a las nueve`() {
        val settings = ReminderSettings.DEFAULT

        assertFalse(settings.enabled)
        assertEquals(540, settings.minuteOfDay)
        assertNull(settings.lastNotifiedDate)
        assertEquals("09:00", settings.timeText)
        assertTrue(settings.hasValidMinuteOfDay)
    }

    @Test
    fun `un minuto fuera de rango cae al de por defecto`() {
        assertEquals(540, ReminderSettings.coerceMinuteOfDay(null))
        assertEquals(540, ReminderSettings.coerceMinuteOfDay(-1))
        assertEquals(540, ReminderSettings.coerceMinuteOfDay(1440))
        assertEquals(540, ReminderSettings.coerceMinuteOfDay(100_000))
    }

    @Test
    fun `los extremos validos se conservan`() {
        assertEquals(0, ReminderSettings.coerceMinuteOfDay(0))
        assertEquals(1439, ReminderSettings.coerceMinuteOfDay(1439))
    }

    @Test
    fun `la hora invalida se pinta como la de por defecto`() {
        val settings = ReminderSettings(enabled = true, minuteOfDay = 5_000)

        assertEquals("09:00", settings.timeText)
        assertFalse(settings.hasValidMinuteOfDay)
    }

    @Test
    fun `la marca del ultimo aviso se lee solo en formato canonico`() {
        assertEquals(today, ReminderSettings.parseDate("2026-11-09"))
        assertNull(ReminderSettings.parseDate(null))
        assertNull(ReminderSettings.parseDate(""))
        assertNull(ReminderSettings.parseDate("9/11/2026"))
        assertNull(ReminderSettings.parseDate("2026-1-1"))
        assertNull(ReminderSettings.parseDate("2026-13-01"))
        assertNull(ReminderSettings.parseDate("1899-12-31"))
        assertNull(ReminderSettings.parseDate("3000-01-01"))
        assertNull(ReminderSettings.parseDate("2026-11-09T00:00:00"))
    }

    @Test
    fun `la marca guardada se escribe y se recupera`() {
        assertEquals("2026-11-09", ReminderSettings.dateText(today))
        assertNull(ReminderSettings.dateText(null))
    }

    @Test
    fun `lo leido de preferencias se coacciona`() {
        val settings = ReminderSettings.fromStored(
            enabled = null,
            minuteOfDay = 9_999,
            lastNotifiedDate = "basura",
        )

        assertFalse(settings.enabled)
        assertEquals(540, settings.minuteOfDay)
        assertNull(settings.lastNotifiedDate)
    }

    @Test
    fun `con el recordatorio apagado no se avisa`() {
        val settings = ReminderSettings.fromStored(false, 540, null)

        assertFalse(settings.shouldNotify(today))
    }

    @Test
    fun `sin haber avisado hoy toca avisar`() {
        val settings = ReminderSettings.fromStored(true, 540, "2026-11-08")

        assertTrue(settings.shouldNotify(today))
    }

    @Test
    fun `el mismo dia no se avisa dos veces`() {
        val settings = ReminderSettings.fromStored(true, 540, "2026-11-09")

        assertFalse(settings.shouldNotify(today))
        assertTrue(settings.shouldNotify(today.plusDays(1)))
    }

    @Test
    fun `marcar el aviso lo deja apuntado`() {
        val settings = ReminderSettings(enabled = true)

        val notified = settings.notifiedOn(today)

        assertEquals(today, notified.lastNotifiedDate)
        assertTrue(settings.shouldNotify(today))
        assertFalse(notified.shouldNotify(today))
        assertTrue(notified.shouldNotify(today.plusDays(1)))
    }

    @Test
    fun `el texto del ajuste no lleva contenido del plan`() {
        val settings = ReminderSettings(enabled = true, minuteOfDay = 545, lastNotifiedDate = today)

        assertEquals("09:05", settings.timeText)
        assertTrue(settings.toString().contains("enabled=true"))
        assertTrue(settings.toString().contains("minuteOfDay=545"))
    }
}
