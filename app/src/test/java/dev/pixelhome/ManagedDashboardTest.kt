package dev.pixelhome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagedDashboardTest {
    private fun config()=JSONObject("""{"schema_version":1,"title":"Kitchen","favorites":["light.test"],"scenes":[],"players":[],"home_entities":[],"weather_entity":null,"music":[],"idle_seconds":45}""")
    @Test fun emptyPlayersAndRealConfig() {
        val a=config(); validateManagedConfig(a)
        assertEquals(listOf("light.test"),parseConfig(a).favorites)
        assertTrue(parseConfig(a).players.isEmpty())
    }
    @Test fun rejectsWrongDomainsAndUnknownFields() {
        for(a in listOf(config().put("extra",true),config().put("favorites",org.json.JSONArray("[\"script.bad\"]")),config().put("idle_seconds",true))) {
            assertThrows(IllegalArgumentException::class.java) { validateManagedConfig(a) }
        }
    }
    @Test fun sharedTelemetryFixtureHasExplicitUnknownDockOnAc() {
        val fixture=JSONObject(javaClass.classLoader!!.getResource("tablet-telemetry.json")!!.readText())
        assertEquals("ac",fixture.getString("power_source")); assertEquals("unknown",fixture.getString("dock_state"))
        assertTrue(fixture.getBoolean("charging")); assertEquals(1,fixture.getInt("schema_version"))
    }
    @Test fun pairingOriginRejectsCredentialForwardingTargets() {
        for(origin in listOf("http://ha.local","https://user:pass@ha.local","https://ha.local/path","https://ha.local/?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { HomeAssistant(origin,"dummy","tablet") }
        }
    }
}
