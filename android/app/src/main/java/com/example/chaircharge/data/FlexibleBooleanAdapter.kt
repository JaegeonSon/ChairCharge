package com.example.chaircharge.data

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class FlexibleBooleanAdapter : TypeAdapter<Boolean?>() {
    override fun write(writer: JsonWriter, value: Boolean?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(value)
        }
    }

    override fun read(reader: JsonReader): Boolean? {
        return when (reader.peek()) {
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.STRING -> {
                when (reader.nextString().trim().lowercase()) {
                    "true", "y", "yes", "예", "1", "실내", "이동 가능" -> true
                    else -> false
                }
            }
            JsonToken.NUMBER -> reader.nextInt() != 0
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
