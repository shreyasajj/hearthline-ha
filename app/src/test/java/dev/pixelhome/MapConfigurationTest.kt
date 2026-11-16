package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class MapConfigurationTest {
    @Test fun styleMustBelongToExplicitAllowlist() {
        assertTrue(runCatching { MapConfiguration.parse("https://maps.example/style.json","https://maps.example,https://tiles.example","Map provider · data attribution") }.isSuccess)
        assertTrue(runCatching { MapConfiguration.parse("https://other.example/style.json","https://maps.example","Provider") }.isFailure)
    }
    @Test fun deniesInsecureOrCredentialedOrigins() {
        for(origin in listOf("http://maps.example","https://token@maps.example","https://maps.example/path","https://maps.example/?secret=1")) {
            assertTrue(origin,runCatching { MapConfiguration.parse("https://maps.example/style.json",origin,"Provider") }.isFailure)
        }
    }
    @Test fun attributionRequired() {
        assertTrue(runCatching { MapConfiguration.parse("https://maps.example/style.json","https://maps.example","") }.isFailure)
    }
}
