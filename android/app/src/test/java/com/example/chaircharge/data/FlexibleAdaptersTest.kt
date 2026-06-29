package com.example.chaircharge.data

import com.google.gson.stream.JsonReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleAdaptersTest {
    @Test
    fun invalidCoordinateStringBecomesNull() {
        val value = FlexibleDoubleAdapter().read(
            JsonReader(StringReader("\"not-a-number\""))
        )

        assertNull(value)
    }

    @Test
    fun numericCoordinateStringIsParsed() {
        val value = FlexibleDoubleAdapter().read(
            JsonReader(StringReader("\"35.9676\""))
        )

        assertEquals(35.9676, value ?: 0.0, 0.000001)
    }

    @Test
    fun indoorAndMovableKoreanValuesBecomeTrue() {
        val adapter = FlexibleBooleanAdapter()

        val indoor = adapter.read(JsonReader(StringReader("\"실내\"")))
        val movable = adapter.read(JsonReader(StringReader("\"이동 가능\"")))

        assertTrue(indoor == true)
        assertTrue(movable == true)
    }
}
