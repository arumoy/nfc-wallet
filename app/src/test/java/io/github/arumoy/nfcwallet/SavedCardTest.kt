package io.github.arumoy.nfcwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SavedCardTest {

    @Test
    fun formattedPan_groupsInFours() {
        val card = SavedCard(
            nickname = "Test",
            cardholderName = "A B",
            pan = "4386281234567890",
            expiry = "12/28",
            cvv = "123",
            network = "Visa"
        )
        assertEquals("4386 2812 3456 7890", card.formattedPan)
    }

    @Test
    fun formattedPan_handlesShortPan() {
        val card = SavedCard(
            nickname = "Test",
            cardholderName = null,
            pan = "12",
            expiry = null,
            cvv = null,
            network = null
        )
        assertEquals("12", card.formattedPan)
    }

    @Test
    fun jsonRoundTrip_preservesFields() {
        val original = SavedCard(
            id = "fixed-id",
            nickname = "My Card",
            cardholderName = "Jane Doe",
            pan = "4386281234567890",
            expiry = "12/28",
            cvv = "123",
            network = "Visa"
        )
        val restored = SavedCard.fromJson(original.toJson())
        assertEquals(original, restored)
        assertEquals("123", restored.cvv)
        assertEquals("Jane Doe", restored.cardholderName)
    }

    @Test
    fun jsonRoundTrip_handlesNullOptionals() {
        val original = SavedCard(
            id = "fixed-id",
            nickname = "My Card",
            cardholderName = null,
            pan = "4386281234567890",
            expiry = null,
            cvv = null,
            network = null
        )
        val restored = SavedCard.fromJson(original.toJson())
        assertEquals(original, restored)
        assertNull(restored.expiry)
        assertNull(restored.network)
        assertNull(restored.cvv)
        assertNull(restored.cardholderName)
    }
}

class ValidateCardTest {

    @Test
    fun acceptsBlankNickname_defaultedOnSave() {
        assertNull(validateCard("", "4386281234567890", "12/28", "123"))
    }

    @Test
    fun rejectsTooShortPan() {
        assertNotNull(validateCard("Card", "12345", "12/28", "123"))
    }

    @Test
    fun rejectsTooLongPan() {
        assertNotNull(validateCard("Card", "12345678901234567890", "12/28", "123"))
    }

    @Test
    fun rejectsBadExpiryFormat() {
        assertNotNull(validateCard("Card", "4386281234567890", "1228", "123"))
    }

    @Test
    fun rejectsBadCvvLength() {
        assertNotNull(validateCard("Card", "4386281234567890", "12/28", "12"))
        assertNotNull(validateCard("Card", "4386281234567890", "12/28", "12345"))
    }

    @Test
    fun acceptsValid3DigitCvv() {
        assertNull(validateCard("Card", "4386281234567890", "12/28", "123"))
    }

    @Test
    fun acceptsValid4DigitCvv() {
        assertNull(validateCard("Card", "371380123456789", "12/28", "1234"))
    }

    @Test
    fun acceptsBlankCvv() {
        assertNull(validateCard("Card", "4386281234567890", "12/28", ""))
    }

    @Test
    fun acceptsValidCardWithBlankExpiry() {
        assertNull(validateCard("Card", "4386281234567890", "", ""))
    }
}

class DefaultNicknameTest {

    @Test
    fun buildsNetworkAndLast4() {
        assertEquals("Visa - 7890", defaultNickname("Visa", "4386281234567890"))
    }

    @Test
    fun stripsNonDigitsFromPanForLast4() {
        assertEquals("Visa - 7890", defaultNickname("Visa", "4386 2812 3456 7890"))
    }

    @Test
    fun fallsBackToCardWhenNetworkMissing() {
        assertEquals("Card - 7890", defaultNickname(null, "4386281234567890"))
        assertEquals("Card - 7890", defaultNickname("  ", "4386281234567890"))
    }

    @Test
    fun omitsSuffixWhenTooFewDigits() {
        assertEquals("Visa", defaultNickname("Visa", "12"))
        assertEquals("Card", defaultNickname(null, ""))
    }
}
