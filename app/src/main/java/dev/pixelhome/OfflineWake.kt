package dev.pixelhome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.LibVosk
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object WakeModel {
    const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    @Synchronized fun directory(context: Context): File {
        val base = File(context.noBackupFilesDir,"wake-en-us-015")
        if(File(base,"ready").readTextOrNull() == SHA256 && File(base,"vosk-model-small-en-us-0.15/am/final.mdl").isFile) return File(base,"vosk-model-small-en-us-0.15")
        base.deleteRecursively(); check(base.mkdirs())
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("voice/wake-en-us.zip").use { input ->
            val buffer = ByteArray(32768)
            while(true) { val n = input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == SHA256)
        var total = 0L; var entries = 0
        ZipInputStream(context.assets.open("voice/wake-en-us.zip")).use { zip ->
            while(true) {
                val entry = zip.nextEntry ?: break
                check(++entries <= 64)
                val file = File(base,entry.name)
                check(file.canonicalPath.startsWith(base.canonicalPath+File.separator))
                if(entry.isDirectory) file.mkdirs() else {
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val bytes = ByteArray(32768)
                        while(true) { val n = zip.read(bytes); if(n<0) break; total += n; check(total <= 100*1024*1024); out.write(bytes,0,n) }
                    }
                }
                zip.closeEntry()
            }
        }
        File(base,"ready").writeText(SHA256)
        return File(base,"vosk-model-small-en-us-0.15")
    }
    private fun File.readTextOrNull() = if(isFile) readText() else null
}
/** Records only during the explicitly enabled, visible ambient clock. No audio is retained. */
class OfflineWake(private val context: Context, private val status: (String)->Unit, private val wake: ()->Unit) {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val gate = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var requested = false
    private var session: Session? = null
    private class Session { val active = AtomicBoolean(true); @Volatile var recorder: AudioRecord? = null }
    fun listening(value: Boolean) {
        if(requested == value) return
        requested = value; stopSession()
        if(!value) { status("Microphone off"); return }
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status("Microphone permission needed"); return }
        val current = Session(); session = current
        fun report(text: String) { handler.post { if(session === current && current.active.get()) status(text) } }
        scope.launch { gate.withLock {
            var model: Model? = null; var recognizer: Recognizer? = null; var recorder: AudioRecord? = null
            try {
                report("Preparing bundled offline wake model…")
                LibVosk.vosk_set_log_level(-1)
                model = Model(WakeModel.directory(context).absolutePath)
                if(!current.active.get()) return@launch
                recognizer = Recognizer(model,16000f,"[\"wake up\",\"[unk]\"]")
                val size = maxOf(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
                @Suppress("MissingPermission")
                val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size)
                recorder = input; current.recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED)
                synchronized(current) {
                    if(!current.active.get()) return@launch
                    input.startRecording(); check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                }
                report("Listening offline for “wake up”")
                val bytes = ByteArray(3200)
                while(current.active.get() && isActive) {
                    val n = input.read(bytes,0,bytes.size)
                    check(n > 0)
                    if(recognizer.acceptWaveForm(bytes,n)) {
                        val phrase = JSONObject(recognizer.result).optString("text")
                        if(AmbientSignals.wakePhrase(phrase)) {
                            handler.post { if(session === current && current.active.get()) wake() }
                            break
                        }
                    }
                }
            } catch(_: CancellationException) { }
            catch(_: Exception) { report("Offline wake unavailable · check microphone/privacy permission") }
            catch(_: UnsatisfiedLinkError) { report("Offline speech engine unavailable on this device") }
            finally { runCatching { recorder?.stop() }; runCatching { recorder?.release() }; runCatching { recognizer?.close() }; runCatching { model?.close() }; current.recorder = null }
        } }
    }
    private fun stopSession() { session?.let { current -> synchronized(current) { current.active.set(false); runCatching { current.recorder?.stop() } } }; session = null }
    fun close() { requested = false; stopSession(); scope.cancel() }
}
