package dev.pixelhome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.FaceDetector
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Optional local face detection. Frames stay in memory; no face identity or image storage. */
@Suppress("DEPRECATION")
class FrontPresence(private val context: Context, private val rotation: ()->Int, private val status: (String)->Unit, private val seen: ()->Unit) {
    private val main = Handler(context.mainLooper)
    private var requested = false
    private var session: Session? = null
    private class Session {
        val active = AtomicBoolean(true)
        val thread = HandlerThread("PixelHomePresence").apply { start() }
        val handler = Handler(thread.looper)
        var camera: Camera? = null
        var texture: SurfaceTexture? = null
    }
    fun watching(value: Boolean) {
        if(value == requested) return
        requested = value; stopSession()
        if(!value) { status("Front camera off"); return }
        if(context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { status("Camera permission needed"); return }
        val current = Session(); session = current
        fun report(message: String) { main.post { if(session === current && current.active.get()) status(message) } }
        fun fail(message: String) { main.post { if(session === current) { status(message);stopSession() } } }
        current.handler.post {
            try {
                val id = (0 until Camera.getNumberOfCameras()).firstOrNull {
                    val info = Camera.CameraInfo(); Camera.getCameraInfo(it,info); info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                } ?: error("No front camera")
                val info = Camera.CameraInfo(); Camera.getCameraInfo(id,info)
                val camera = Camera.open(id); current.camera = camera
                val parameters = camera.parameters
                val size = parameters.supportedPreviewSizes.filter { it.width <= 640 && it.height <= 480 && it.width % 2 == 0 }.maxByOrNull { it.width*it.height } ?: error("Preview size unavailable")
                parameters.setPreviewSize(size.width,size.height)
                parameters.previewFormat = android.graphics.ImageFormat.NV21
                camera.parameters = parameters
                val texture = SurfaceTexture(false); current.texture = texture
                camera.setPreviewTexture(texture)
                var lastFrame = 0L
                camera.setPreviewCallbackWithBuffer { data, source ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if(current.active.get() && now-lastFrame >= 1000) {
                            lastFrame = now
                            val pixels = IntArray(size.width*size.height) { i ->
                                val y = data[i].toInt() and 255
                                android.graphics.Color.rgb(y,y,y)
                            }
                            val original = Bitmap.createBitmap(size.width,size.height,Bitmap.Config.RGB_565)
                            original.setPixels(pixels,0,size.width,0,0,size.width,size.height)
                            val angle = (info.orientation + rotation()) % 360
                            val upright = if(angle == 0) original else Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply { postRotate(angle.toFloat()) },false)
                            try {
                                val faces = arrayOfNulls<FaceDetector.Face>(2)
                                FaceDetector(upright.width,upright.height,2).findFaces(upright,faces)
                                val face = faces.any { it != null && it.confidence() >= .6f && it.eyesDistance() >= 5f }
                                if(face) main.post { if(session === current && current.active.get()) seen() }
                                report(if(face) "Front camera · face detected locally" else "Front camera · watching locally")
                            } finally { if(upright !== original) upright.recycle(); original.recycle() }
                        }
                    } catch(_: Exception) { fail("Front-camera face detection unavailable") }
                    catch(_: LinkageError) { fail("System face detector unavailable") }
                    finally { if(current.active.get()) runCatching { source.addCallbackBuffer(data) } }
                }
                repeat(2) { camera.addCallbackBuffer(ByteArray(size.width*size.height*3/2)) }
                if(!current.active.get()) return@post
                camera.startPreview(); report("Front camera · watching locally")
            } catch(_: Exception) { fail("Front camera unavailable · check privacy/permission or another camera app") }
            catch(_: LinkageError) { fail("System face detector unavailable") }
        }
    }
    private fun stopSession() {
        val old = session ?: return
        session = null; old.active.set(false)
        old.handler.post {
            runCatching { old.camera?.setPreviewCallbackWithBuffer(null); old.camera?.stopPreview() }
            old.camera?.release(); old.texture?.release(); old.thread.quitSafely()
        }
    }
    fun close() { requested = false; stopSession() }
}
