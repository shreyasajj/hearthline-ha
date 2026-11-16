package dev.pixelhome

import android.graphics.Bitmap
import android.media.FaceDetector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.LibVosk

/** Inference-only tests: never create AudioRecord, open a camera or change app preferences. */
@RunWith(AndroidJUnit4::class)
class OfflineWakeDeviceTest {
    @Test fun bundledModelRecognizesSyntheticWakePhraseWithoutMicrophone() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        LibVosk.vosk_set_log_level(-1)
        Model(WakeModel.directory(instrumentation.targetContext).absolutePath).use { model ->
            Recognizer(model,16000f,"[\"wake up\",\"[unk]\"]").use { recognizer ->
                var detected=false
                instrumentation.context.assets.open("wake-up.pcm").use { input ->
                    val buffer=ByteArray(3200)
                    while(true) {val n=input.read(buffer);if(n<0)break;if(recognizer.acceptWaveForm(buffer,n)) detected=detected||AmbientSignals.wakePhrase(JSONObject(recognizer.result).optString("text"))}
                }
                detected=detected||AmbientSignals.wakePhrase(JSONObject(recognizer.finalResult).optString("text"))
                assertTrue("Bundled offline decoder did not recognize the synthetic fixture",detected)
            }
        }
    }
    @Test fun systemFaceDetectorLoadsAndBlankFrameIsNotPresence() {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.RGB_565)
        try {assertEquals(0,FaceDetector(320,240,1).findFaces(bitmap,arrayOfNulls(1)))} finally {bitmap.recycle()}
    }
}
