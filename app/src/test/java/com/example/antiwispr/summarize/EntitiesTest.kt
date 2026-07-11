package com.example.antiwispr.summarize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure extraction core (regexes, normalization, merge/dedupe/cap). */
class EntitiesTest {

    private fun amounts(text: String) = amountCandidates(text).map { it.text }
    private fun entities(text: String, cap: Int = EntityExtractor.READER_CAP) =
        assembleEntities(text, emptyList(), cap)

    // ---- amount forms -----------------------------------------------------------

    @Test fun `rupee symbol`() = assertEquals(listOf("₹1,500"), amounts("bring ₹1,500 cash"))
    @Test fun `Rs with dot`() = assertEquals(listOf("₹500"), amounts("Rs. 500 dena hai"))
    @Test fun `Rs without dot`() = assertEquals(listOf("₹500"), amounts("Rs 500 dena hai"))
    @Test fun `INR prefix`() = assertEquals(listOf("₹2,000"), amounts("transfer INR 2000 today"))
    @Test fun `rupees suffix`() = assertEquals(listOf("₹1,500"), amounts("1500 rupees for the cab"))
    @Test fun `rs suffix`() = assertEquals(listOf("₹500"), amounts("500 rs for parking"))
    @Test fun `lakh grouping preserved`() =
        assertEquals(listOf("₹1,50,000"), amounts("advance 1,50,000 rupees"))
    @Test fun `paise kept`() = assertEquals(listOf("₹99.50"), amounts("it costs ₹99.50 only"))

    // ---- amount false positives -------------------------------------------------

    @Test fun `phone number is not an amount`() = assertTrue(amounts("call 9876543210").isEmpty())
    @Test fun `date is not an amount`() = assertTrue(amounts("meet on 5/6/2026").isEmpty())
    @Test fun `cars is not rs`() = assertTrue(amounts("cars 500 are parked").isEmpty())
    @Test fun `rsvp is not rs`() = assertTrue(amounts("500 rsvp confirmed").isEmpty())

    // ---- phone fallback ---------------------------------------------------------

    @Test fun `bare indian mobile found`() {
        val out = entities("call me back on 9876543210")
        assertEquals(listOf(EntityKind.PHONE), out.map { it.kind })
        assertEquals("9876543210", out[0].data)
    }

    @Test fun `plus91 with space found and normalized data`() {
        val out = entities("number hai +91 9876543210")
        assertEquals(listOf(EntityKind.PHONE), out.map { it.kind })
        assertEquals("+919876543210", out[0].data)
    }

    @Test fun `amount digits never doubles as phone`() {
        // 8-digit cap on amounts + phone needs 10 digits starting 6-9
        val out = entities("send Rs 98765432 to the account")
        assertEquals(listOf(EntityKind.AMOUNT), out.map { it.kind })
    }

    @Test fun `landline-short runs ignored`() = assertTrue(entities("room 4521 on floor 3").isEmpty())

    // ---- dedupe + ordering + cap --------------------------------------------------

    @Test fun `same amount spoken twice dedupes`() {
        val out = entities("Rs 1500 dena hai\ngive 1500 rupees tomorrow")
        assertEquals(1, out.count { it.kind == EntityKind.AMOUNT })
    }

    @Test fun `same phone with and without country code dedupes`() {
        val out = entities("call 9876543210\nya phir +919876543210 pe")
        assertEquals(1, out.count { it.kind == EntityKind.PHONE })
    }

    @Test fun `kind order puts phone before amount`() {
        val out = entities("pay ₹500 then call 9876543210")
        assertEquals(listOf(EntityKind.PHONE, EntityKind.AMOUNT), out.map { it.kind })
    }

    @Test fun `cap applies after kind sort`() {
        val text = "₹100 and ₹200 and ₹300 and call 9876543210"
        val out = entities(text, cap = 2)
        assertEquals(listOf(EntityKind.PHONE, EntityKind.AMOUNT), out.map { it.kind })
    }

    // ---- source line ---------------------------------------------------------------

    @Test fun `source line is the containing action line`() {
        val out = entities("SUMMARY LINE HERE\nbanker ko call karna 9876543210 pe")
        assertEquals("banker ko call karna 9876543210 pe", out[0].sourceLine)
    }

    @Test fun `normalize handles plain thousands`() = assertEquals("₹1,500", normalizeAmount("1500"))
}
