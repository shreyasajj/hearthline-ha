package dev.pixelhome

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DisplayProtocolTest {
    @Test fun crossPlatformPayloadFixtures() {
        val raw=javaClass.getResourceAsStream("/display-fixtures.json")!!.bufferedReader().use { it.readText() }
        val fixtures=JSONArray(raw)
        for(i in 0 until fixtures.length()) {
            val fixture=fixtures.getJSONObject(i)
            val accepted=runCatching { DisplayProtocol.parse(fixture.getJSONObject("request").toString()) }.isSuccess
            assertEquals(fixture.getString("name"),fixture.getBoolean("valid"),accepted)
        }
    }
    @Test fun sharedNativeFixtures() {
        val fixtures=JSONArray(javaClass.getResourceAsStream("/template-fixtures.json")!!.bufferedReader().use { it.readText() })
        for(i in 0 until fixtures.length()) {
            val f=fixtures.getJSONObject(i)
            assertEquals(f.getString("name"),f.getBoolean("valid"),runCatching { DisplayProtocol.parse(f.getJSONObject("request").toString()) }.isSuccess)
        }
    }
    @Test fun unknownWeatherValuesStayUnknown() {
        val fixtures=JSONArray(javaClass.getResourceAsStream("/template-fixtures.json")!!.bufferedReader().use { it.readText() })
        val weather=DisplayProtocol.parse(fixtures.getJSONObject(5).getJSONObject("request").toString()).template!!.result as WeatherData
        assertNull(weather.probability)
        assertNull(weather.hours.first().probability)
    }
    @Test fun previewIsValidAndLabeled() {
        assertEquals("preview",DisplayProtocol.preview().id)
        assertEquals("reading",DisplayProtocol.preview().layout)
    }
}
