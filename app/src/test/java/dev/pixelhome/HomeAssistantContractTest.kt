package dev.pixelhome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeAssistantContractTest {
    @Test fun dashboardBoundsAndPlayerFiltering() {
        val config = parseConfig(JSONObject("""{"idle_seconds":1,"players":["light.bad","media_player.living_room"],"favorites":["light.one"],"music":[{"title":"Morning","uri":"library://playlist/1","type":"playlist"}]}"""))
        assertEquals(15, config.idleSeconds)
        assertEquals(listOf("media_player.living_room"), config.players)
        assertEquals("playlist", config.shortcuts.single().type)
        assertEquals(600, parseConfig(JSONObject("""{"idle_seconds":9000}""")).idleSeconds)
    }
    @Test fun credentialsOnlyAccompanySameOriginArtwork() {
        val client = HomeAssistant("https://home.example:8123", "test-token")
        assertEquals("Bearer test-token", client.artworkRequest("/api/media_player_proxy/media_player.living_room")?.header("Authorization"))
        assertNull(client.artworkRequest("https://other.example/cover.jpg"))
        assertNull(client.artworkRequest("https://home.example:443/cover.jpg"))
        assertNull(client.artworkRequest("http://home.example:8123/cover.jpg"))
        assertNull(client.artworkRequest("//other.example/cover.jpg"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCleartextCredentials() {
        HomeAssistant("http://home.example", "test-token")
    }
    @Test fun unavailableAndUnsupportedAreExplicit() {
        val entity = Entity("media_player.test", "unavailable", JSONObject("""{"supported_features":4}"""))
        assertFalse(entity.available)
        assertTrue(entity.supports(4))
        assertFalse(entity.supports(32))
    }
}
