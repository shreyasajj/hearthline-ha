package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class HaConnectionDraftTest {
    @Test fun cameraPermissionIsExplicitFreshAndUnlockGated() {
        val draft=HaConnectionDraft()
        draft.cameraResult(true,1,true);assertFalse(draft.scannerRequested)
        draft.requestCamera(1);draft.cameraResult(true,2,false);assertFalse(draft.scannerRequested)
        draft.requestCamera(1);draft.cameraResult(true,120002,true);assertFalse(draft.scannerRequested)
        draft.requestCamera(1);draft.cameraResult(true,2,true);assertTrue(draft.scannerRequested)
    }
    @Test fun permissionDenialLeavesManualAndImageFallbacksAvailable() {
        val draft=HaConnectionDraft();draft.requestCamera(1);draft.cameraResult(false,2,true)
        assertFalse(draft.scannerRequested);assertTrue(draft.note!!.contains("Import a QR image"))
    }
    @Test fun imageRequestCannotReplayOrSurviveCancellation() {
        val draft=HaConnectionDraft();assertFalse(draft.consumeImageRequest(1))
        draft.requestImage(1);assertTrue(draft.consumeImageRequest(2));assertFalse(draft.consumeImageRequest(3))
        draft.requestImage(1);draft.cancelRequests();assertFalse(draft.consumeImageRequest(2))
    }
    @Test fun clearingDropsCredentialWithoutChangingConfiguredAddress() {
        val draft=HaConnectionDraft();draft.url="https://home.example";draft.updateToken("synthetic token")
        draft.clearToken();assertEquals("",draft.token);assertEquals("https://home.example",draft.url)
    }
}
