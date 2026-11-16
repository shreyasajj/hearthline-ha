package dev.pixelhome

import com.google.zxing.*
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class HaTokenQrTest {
    private fun encoded(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private val syntheticToken get() = encoded("""{"alg":"HS256","typ":"JWT"}""") + "." +
        encoded("""{"iss":"synthetic-test-only","iat":0,"exp":9999999999}""") + "." + encoded("synthetic signature, not a real HA credential")
    @Test fun importsTheRawTokenFormatUsedByHomeAssistantQr() {
        assertEquals(syntheticToken, HaQrToken.parse(syntheticToken).forConnection())
        assertEquals(syntheticToken, HaQrToken.parse(" \n$syntheticToken\n").forConnection())
    }
    @Test fun sensitiveValueToStringIsRedacted() {
        assertEquals("HaQrToken(redacted)", HaQrToken.parse(syntheticToken).toString())
    }
    @Test fun rejectsUrlsOtpAndInventedJsonEnvelopesWithoutEchoingPayload() {
        for(raw in listOf("https://home.example/token?secret=$syntheticToken", "otpauth://totp/Home?secret=SYNTHETIC", "{\"token\":\"$syntheticToken\"}", "Bearer $syntheticToken", "not a token")) {
            val error = runCatching { HaQrToken.parse(raw) }.exceptionOrNull()!!
            assertEquals(HaQrToken.INVALID, error.message)
            assertNull(error.cause)
        }
    }
    @Test fun rejectsOversizedAndDeeplyNestedPayloads() {
        for(raw in listOf("x".repeat(8193), encoded("""{"alg":"HS256"}""") + "." + encoded("[".repeat(100)+"0"+"]".repeat(100)) + "." + encoded("synthetic signature"))) {
            assertTrue(runCatching { HaQrToken.parse(raw) }.isFailure)
        }
    }
    @Test fun rejectsUnsignedOrMalformedJwt() {
        val tail = "." + encoded("""{"iss":"test"}""") + "." + encoded("synthetic signature")
        assertTrue(runCatching { HaQrToken.parse(encoded("""{"alg":"none"}""") + tail) }.isFailure)
        assertTrue(runCatching { HaQrToken.parse("abcdefgh.abcdefgh.abcdefghijklmnop") }.isFailure)
    }
    @Test fun aScanIsSingleUseAndExpires() {
        val session=QrImportSession(100)
        session.accept(syntheticToken,101,true,true)
        assertTrue(runCatching { session.accept(syntheticToken,102,true,true) }.isFailure)
        assertTrue(runCatching { QrImportSession(100).accept(syntheticToken,120101,true,true) }.isFailure)
    }
    @Test fun lockBackgroundCancellationAndClockRegressionRejectResults() {
        assertTrue(runCatching { QrImportSession(100).accept(syntheticToken,101,false,true) }.isFailure)
        assertTrue(runCatching { QrImportSession(100).accept(syntheticToken,101,true,false) }.isFailure)
        assertTrue(runCatching { QrImportSession(100).accept(syntheticToken,99,true,true) }.isFailure)
        val cancelled=QrImportSession(100);cancelled.cancel()
        assertTrue(runCatching { cancelled.accept(syntheticToken,101,true,true) }.isFailure)
    }
    @Test fun decodesQrWithHomeAssistantStyleCenterLogoSpace() {
        val matrix=MultiFormatWriter().encode(syntheticToken,BarcodeFormat.QR_CODE,512,512,mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q))
        val pixels=IntArray(512*512) { index ->
            val x=index%512; val y=index/512
            if(x in 171..340 && y in 171..340) -1 else if(matrix[x,y]) -0x1000000 else -1
        }
        val decoded=TokenQrDecoder.decode(pixels,512,512)
        assertEquals(syntheticToken,QrImportSession(0).accept(decoded,1,true,true).forConnection())
    }
    @Test fun emptyImageDoesNotProduceAToken() {
        assertTrue(runCatching { TokenQrDecoder.decode(IntArray(10000) { -1 },100,100) }.isFailure)
    }
}
