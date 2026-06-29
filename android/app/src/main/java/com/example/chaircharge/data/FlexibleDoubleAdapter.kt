package com.example.chaircharge.data

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class FlexibleDoubleAdapter : TypeAdapter<Double?>() {
    override fun write(writer: JsonWriter, value: Double?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(value)
        }
    }

    override fun read(reader: JsonReader): Double? {
        return when (reader.peek()) {
            JsonToken.NUMBER,
            JsonToken.STRING -> reader.nextString()
                .trim()
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() }
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }
}
