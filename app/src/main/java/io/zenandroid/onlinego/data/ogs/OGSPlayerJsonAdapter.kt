package io.zenandroid.onlinego.data.ogs

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.zenandroid.onlinego.data.model.ogs.OGSPlayer
import java.io.IOException

class OGSPlayerJsonAdapter : JsonAdapter<OGSPlayer>() {

    @Throws(IOException::class)
    override fun fromJson(reader: JsonReader): OGSPlayer? {
        if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            var id: Long? = null
            var username: String? = null
            var rank: Float? = null
            var professional = false
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextLong()
                    "username" -> username = reader.nextString()
                    "rank" -> rank = reader.nextDouble().toFloat()
                    "professional" -> professional = reader.nextBoolean()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return OGSPlayer(id ?: 0, username ?: "", rank ?: 0f, professional)
        }
        return null
    }

    @Throws(IOException::class)
    override fun toJson(writer: JsonWriter, value: OGSPlayer?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("id").value(value.id)
        writer.name("username").value(value.username)
        writer.name("rank").value(value.rank)
        writer.name("professional").value(value.professional)
        writer.endObject()
    }
}