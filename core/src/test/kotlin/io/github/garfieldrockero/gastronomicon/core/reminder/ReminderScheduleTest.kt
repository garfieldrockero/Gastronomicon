package io.github.garfieldrockero.gastronomicon.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * El cálculo del próximo aviso: hora por defecto, cambios de hora, años y zonas
 * imposibles, y el umbral de disparo tardío. Todo con reloj y zona fijos.
 */
class ReminderScheduleTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun atMadrid(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, madrid).toInstant()

    // ------------------------------------------------------------- hora

    @Test
    fun `el minuto por defecto son las 09 00`() {
        assertEquals(540, ReminderSchedule.DEFAULT_MINUTE_OF_DAY)
        assertEquals("09:00", ReminderSchedule.textOf(ReminderSchedule.DEFAULT_MINUTE_OF_DAY))
        assertEquals(LocalTime.of(9, 0), ReminderSchedule.timeOf(ReminderSchedule.DEFAULT_MINUTE_OF_DAY))
    }

    @Test
    fun `los extremos del dia se aceptan`() {
        assertTrue(ReminderSchedule.isValidMinuteOfDay(0))
        assertTrue(ReminderSchedule.isValidMinuteOfDay(1439))
        assertEquals(LocalTime.of(0, 0), ReminderSchedule.timeOf(0))
        assertEquals(LocalTime.of(23, 59), ReminderSchedule.timeOf(1439))
    }

    @Test
    fun `fuera de rango no se acepta`() {
        assertFalse(ReminderSchedule.isValidMinuteOfDay(-1))
        assertFalse(ReminderSchedule.isValidMinuteOfDay(1440))
        assertFalse(ReminderSchedule.isValidMinuteOfDay(100_000))
        assertNull(ReminderSchedule.nextTrigger(atMadrid(2026, 11, 9, 8), -1, madrid))
        assertNull(ReminderSchedule.nextTrigger(atMadrid(2026, 11, 9, 8), 1440, madrid))
    }

    @Test
    fun `el texto de la hora rellena con cero`() {
        assertEquals("00:00", ReminderSchedule.textOf(0))
        assertEquals("09:05", ReminderSchedule.textOf(9 * 60 + 5))
        assertEquals("23:59", ReminderSchedule.textOf(1439))
    }

    @Test
    fun `un valor invalido se pinta como el de por defecto`() {
        assertEquals("09:00", ReminderSchedule.textOf(-7))
        assertEquals("09:00", ReminderSchedule.textOf(99_999))
    }

    // ---------------------------------------------------- próximo disparo

    @Test
    fun `antes de la hora el disparo es hoy`() {
        val now = atMadrid(2026, 11, 9, 8)
        val trigger = ReminderSchedule.nextTrigger(now, 540, madrid)!!

        assertEquals(LocalDate.of(2026, 11, 9), trigger.date)
        assertEquals(atMadrid(2026, 11, 9, 9), trigger.at)
    }

    @Test
    fun `justo a la hora el disparo es manana`() {
        val now = atMadrid(2026, 11, 9, 9)
        val trigger = ReminderSchedule.nextTrigger(now, 540, madrid)!!

        assertEquals(LocalDate.of(2026, 11, 10), trigger.date)
        assertEquals(atMadrid(2026, 11, 10, 9), trigger.at)
    }

    @Test
    fun `con la hora pasada el disparo es manana`() {
        val now = atMadrid(2026, 11, 9, 10)
        val trigger = ReminderSchedule.nextTrigger(now, 540, madrid)!!

        assertEquals(LocalDate.of(2026, 11, 10), trigger.date)
        assertTrue(trigger.at.isAfter(now))
    }

    @Test
    fun `el disparo nunca es pasado`() {
        val now = atMadrid(2026, 11, 9, 10)
        for (minute in listOf(0, 1, 539, 540, 541, 1439)) {
            val trigger = ReminderSchedule.nextTrigger(now, minute, madrid)!!
            assertTrue("Minuto $minute", trigger.at.isAfter(now))
        }
    }

    @Test
    fun `dos disparos seguidos caen en dias distintos`() {
        val first = ReminderSchedule.nextTrigger(atMadrid(2026, 11, 9, 8), 540, madrid)!!
        val second = ReminderSchedule.nextTrigger(first.at, 540, madrid)!!

        assertEquals(LocalDate.of(2026, 11, 9), first.date)
        assertEquals(LocalDate.of(2026, 11, 10), second.date)
    }

    @Test
    fun `medianoche y ultimo minuto del dia`() {
        val noon = atMadrid(2026, 11, 9, 12)

        val midnight = ReminderSchedule.nextTrigger(noon, 0, madrid)!!
        assertEquals(LocalDate.of(2026, 11, 10), midnight.date)

        val lastMinute = ReminderSchedule.nextTrigger(noon, 1439, madrid)!!
        assertEquals(LocalDate.of(2026, 11, 9), lastMinute.date)

        assertEquals(LocalTime.of(23, 59), lastMinute.at.atZone(madrid).toLocalTime())
    }

    @Test
    fun `el disparo no lleva segundos ni nanosegundos`() {
        val trigger = ReminderSchedule.nextTrigger(atMadrid(2026, 11, 9, 8), 540, madrid)!!
        val local = trigger.at.atZone(madrid)

        assertEquals(0, local.second)
        assertEquals(0, local.nano)
    }

    // ------------------------------------------------------ cambios de hora

    @Test
    fun `primavera la hora que no existe se desplaza y no se pierde`() {
        // 29 de marzo de 2026: a las 02:00 se salta a las 03:00 en Madrid.
        val trigger = ReminderSchedule.nextTrigger(atMadrid(2026, 3, 29, 1), 150, madrid)!!

        assertEquals(LocalDate.of(2026, 3, 29), trigger.date)
        assertEquals(LocalTime.of(3, 30), trigger.at.atZone(madrid).toLocalTime())
    }

    @Test
    fun `otono la hora repetida usa la primera ocurrencia`() {
        // 25 de octubre de 2026: de 03:00 se vuelve a las 02:00 en Madrid.
        val trigger = ReminderSchedule.nextTrigger(atMadrid(2026, 10, 25, 0, 30), 150, madrid)!!

        assertEquals(LocalDate.of(2026, 10, 25), trigger.date)
        assertEquals(LocalTime.of(2, 30), trigger.at.atZone(madrid).toLocalTime())
        assertEquals(ZoneOffset.ofHours(2), trigger.at.atZone(madrid).offset)
    }

    @Test
    fun `Lord Howe su desfase de media hora tambien se resuelve`() {
        val zone = ZoneId.of("Australia/Lord_Howe")
        // 4 de octubre de 2026: de 02:00 se salta a las 02:30 (media hora).
        val now = ZonedDateTime.of(2026, 10, 4, 1, 0, 0, 0, zone).toInstant()
        val trigger = ReminderSchedule.nextTrigger(now, 135, zone)!!

        assertEquals(LocalDate.of(2026, 10, 4), trigger.date)
        assertEquals(LocalTime.of(2, 45), trigger.at.atZone(zone).toLocalTime())
    }

    @Test
    fun `Apia el dia que no existio salta al siguiente`() {
        val zone = ZoneId.of("Pacific/Apia")
        // Samoa se saltó el 30 de diciembre de 2011 entero.
        val now = ZonedDateTime.of(2011, 12, 29, 20, 0, 0, 0, zone).toInstant()
        val trigger = ReminderSchedule.nextTrigger(now, 540, zone)!!

        assertEquals(LocalDate.of(2011, 12, 31), trigger.date)
        assertEquals(LocalTime.of(9, 0), trigger.at.atZone(zone).toLocalTime())
    }

    // ------------------------------------------------- años y zonas raras

    @Test
    fun `un ano fuera de rango no programa nada`() {
        assertNull(ReminderSchedule.nextTrigger(Instant.parse("1899-06-01T00:00:00Z"), 540, ZoneOffset.UTC))
        assertNull(ReminderSchedule.nextTrigger(Instant.parse("3000-01-01T00:00:00Z"), 540, ZoneOffset.UTC))
    }

    @Test
    fun `instantes extremos no lanzan`() {
        assertNull(ReminderSchedule.nextTrigger(Instant.MIN, 540, ZoneOffset.UTC))
        assertNull(ReminderSchedule.nextTrigger(Instant.MAX, 540, ZoneOffset.UTC))
    }

    @Test
    fun `una zona invalida se rechaza sin lanzar`() {
        assertNull(ReminderSchedule.zoneOrNull(null))
        assertNull(ReminderSchedule.zoneOrNull(""))
        assertNull(ReminderSchedule.zoneOrNull("   "))
        assertNull(ReminderSchedule.zoneOrNull("No/Existe"))
        assertNull(ReminderSchedule.zoneOrNull("x".repeat(100)))
        assertEquals(madrid, ReminderSchedule.zoneOrNull("Europe/Madrid"))
    }

    // ------------------------------------------------------- disparo tardío

    @Test
    fun `un disparo a su hora no es tardio`() {
        val scheduled = Instant.parse("2026-11-09T08:00:00Z")
        assertFalse(ReminderSchedule.isTooLate(scheduled, scheduled))
    }

    @Test
    fun `seis horas de retraso todavia se avisan`() {
        val scheduled = Instant.parse("2026-11-09T08:00:00Z")
        assertFalse(ReminderSchedule.isTooLate(scheduled, scheduled.plusSeconds(6 * 60 * 60)))
    }

    @Test
    fun `un segundo mas de seis horas ya no se avisa`() {
        val scheduled = Instant.parse("2026-11-09T08:00:00Z")
        assertTrue(ReminderSchedule.isTooLate(scheduled, scheduled.plusSeconds(6 * 60 * 60 + 1)))
    }

    @Test
    fun `un reloj movido hacia atras no es un disparo tardio`() {
        val scheduled = Instant.parse("2026-11-09T08:00:00Z")
        assertFalse(ReminderSchedule.isTooLate(scheduled, scheduled.minusSeconds(3600)))
    }
}
