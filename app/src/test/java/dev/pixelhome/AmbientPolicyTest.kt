package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class AmbientPolicyTest {
    private val settings = AmbientSettings(idleSeconds=15)
    private fun sleep(p: AmbientPolicy, now: Long, enabled: AmbientSettings=settings, eligible: Boolean=true, busy: Boolean=false, editing: Boolean=false, music: Boolean=false, present: Boolean=false) = p.asleep(now,enabled,eligible,busy,editing,music,present)
    @Test fun timesOutThenTouchStartsANewIdleWindow() {
        val p=AmbientPolicy(0);assertFalse(sleep(p,14999));assertTrue(sleep(p,15000));p.wake(15001);assertFalse(sleep(p,15002));assertTrue(sleep(p,30001))
    }
    @Test fun musicAndPresenceKeepAwakeAndResetTimeout() {
        val p=AmbientPolicy(0);assertFalse(sleep(p,20000,music=true));assertFalse(sleep(p,34999));assertFalse(sleep(p,40000,present=true));assertFalse(sleep(p,54999))
    }
    @Test fun musicWakeCanBeDisabled() { assertTrue(sleep(AmbientPolicy(0),20000,settings.copy(wakeOnMusic=false),music=true)) }
    @Test fun neverReplacesIneligibleOffDockLockScreen() {val p=AmbientPolicy(0);p.preview();assertFalse(sleep(p,20000,eligible=false))}
    @Test fun busyAnswersAndEditingStayVisible() {val p=AmbientPolicy(0);assertFalse(sleep(p,20000,busy=true));assertFalse(sleep(p,40000,editing=true))}
    @Test fun explicitPreviewAllowsSettingsButTouchEndsPreview() {val p=AmbientPolicy(0);p.preview();assertTrue(sleep(p,1,editing=true));p.wake(2);assertFalse(sleep(p,3,editing=true))}
    @Test fun disablingAndClockRegressionCancelSleep() {val p=AmbientPolicy(100);p.preview();assertFalse(sleep(p,99));assertFalse(sleep(p,20000,settings.copy(enabled=false)))}
    @Test fun presenceRequiresFreshRealSuccessfulData() {
        assertEquals(true,AmbientSignals.presence("on",false,true,10,20))
        assertEquals(false,AmbientSignals.presence("off",false,true,10,20))
        assertNull(AmbientSignals.presence("on",true,true,10,20));assertNull(AmbientSignals.presence("on",false,false,10,20))
        assertNull(AmbientSignals.presence("on",false,true,10,20011));assertNull(AmbientSignals.presence("on",false,true,30,20))
        assertNull(AmbientSignals.presence("unknown",false,true,10,20));assertNull(AmbientSignals.presence(null,false,true,null,20))
    }
    @Test fun darkRoomUsesDwellAndHysteresis() {
        val r=RoomLight();r.observe(2f,0);r.tick(2999);assertNull(r.dark);r.tick(3000);assertEquals(true,r.dark)
        r.observe(12f,4000);r.tick(8000);assertEquals(true,r.dark)
        r.observe(25f,9000);r.observe(3f,10000);r.tick(15000);assertEquals(true,r.dark)
        r.observe(25f,16000);r.tick(19000);assertEquals(false,r.dark)
    }
    @Test fun invalidLightNeverImpliesDarkness() {val r=RoomLight();r.observe(Float.NaN,0);r.observe(-1f,0);r.observe(Float.POSITIVE_INFINITY,0);r.tick(10000);assertNull(r.dark)}
    @Test fun onlyExactWakePhraseIsAccepted() {assertTrue(AmbientSignals.wakePhrase(" Wake   Up "));for(s in listOf("wake","backup","don't wake up","[unk]","wake up now")) assertFalse(AmbientSignals.wakePhrase(s))}
    @Test fun invalidConfigurationCannotEnterSettings() {
        for(s in listOf(settings.copy(dimPercent=0),settings.copy(activePercent=1),settings.copy(presenceEntity="light.room"),settings.copy(presenceEntity="binary_sensor.a?token=secret"),settings.copy(idleSeconds=1)))
            assertThrows(IllegalArgumentException::class.java) { s.validated() }
    }
}
