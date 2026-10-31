package com.enso.music.data.lyric

import com.enso.music.data.lyric.online.AmllLyricRepository
import org.junit.Assert.*
import org.junit.Test

class AmllResponseParseTest {

    @Test
    fun testParseAmllSearchJsonStructure() {
        val sampleSearchJson = """
        {
          "status": 200,
          "data": {
            "items": [
              {
                "id": 5682592781675181,
                "filename": "1774197984464-96868062-ffTRtCp2.ttml",
                "createdAt": 1774197984464,
                "musicNames": [ "欲泪海" ],
                "artistNames": [ "MOCKER44." ],
                "albumNames": [ "欲泪海" ],
                "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\"><p begin=\"00:01.00\" end=\"00:03.00\"><span>测试</span></p></tt>"
              }
            ],
            "pagination": { "page": 1, "pageSize": 5, "total": 1 }
          }
        }
        """.trimIndent()

        // Test parser
        val rootObj = org.json.JSONObject(sampleSearchJson)
        val dataArray = rootObj.optJSONObject("data")?.optJSONArray("items")
        assertNotNull(dataArray)
        assertEquals(1, dataArray!!.length())
        val item = dataArray.getJSONObject(0)
        val lyrics = item.optString("lyrics")
        assertTrue(lyrics.contains("<tt"))
    }
}
