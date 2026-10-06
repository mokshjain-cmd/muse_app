package com.phonebridge.integrations

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.phonebridge.muse.CommandProvider
import com.phonebridge.muse.Specs
import com.phonebridge.muse.Specs.P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a photo with the phone's camera and reads it on the device with ML Kit
 * (text recognition, barcodes and QR codes). Nothing leaves the phone except
 * the recognised text.
 */
class CameraCommands(context: Context, private val log: (String) -> Unit) : CommandProvider {

    private val app = context.applicationContext

    override val specs: JSONObject
        get() = JSONObject()
            .put("camera.read_text", Specs.spec(
                "Take a photo with the user's phone camera and read the text in it (OCR, on the phone). Ask the user to point " +
                    "the phone first. Returns the recognised text.",
                optional = listOf(P("camera", "back (default) or front."), P("delay_s", "Seconds to wait before the photo so the user can aim. Default 3.", "integer")),
                timeoutMs = 30_000,
            ))
            .put("camera.scan_code", Specs.spec(
                "Take a photo with the phone camera and read any barcodes or QR codes in it. Returns their values and types.",
                optional = listOf(P("camera", "back (default) or front."), P("delay_s", "Seconds to wait before the photo. Default 3.", "integer")),
                timeoutMs = 30_000,
            ))

    override suspend fun run(command: String, params: JSONObject): JSONObject? {
        if (!command.startsWith("camera.")) return null
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return Specs.err("Camera permission isn't granted. Open Phone Bridge and allow the camera.")
        }
        return try {
            delay(params.optInt("delay_s", 3).coerceIn(0, 10) * 1000L)
            val front = params.optString("camera") == "front"
            when (command) {
                "camera.read_text" -> withImage(front) { image ->
                    val text = suspendTask { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image) }
                    log("Camera read ${text.text.length} characters")
                    Specs.ok(JSONObject().put("text", text.text.take(6000)))
                }
                "camera.scan_code" -> withImage(front) { image ->
                    val codes = suspendTask { BarcodeScanning.getClient().process(image) }
                    log("Camera found ${codes.size} code(s)")
                    Specs.ok(JSONObject().put("codes", JSONArray(codes.map {
                        JSONObject().put("value", it.rawValue ?: "").put("format", it.format).put("type", it.valueType)
                    })))
                }
                else -> Specs.err("unknown command: $command")
            }
        } catch (e: Exception) {
            log("$command failed: ${e.message}")
            Specs.err("Camera failed: ${e.message}")
        }
    }

    /** A lifecycle that is resumed only while a photo is being taken. */
    private class Shot : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun <T> withImage(front: Boolean, use: suspend (InputImage) -> T): T {
        val provider = suspendTask(ProcessCameraProvider.getInstance(app))
        val shot = Shot()
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        withContext(Dispatchers.Main) {
            shot.start()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(shot, selector, capture)
        }
        try {
            delay(800) // let exposure settle
            val proxy: ImageProxy = suspendCancellableCoroutine { cont ->
                capture.takePicture(ContextCompat.getMainExecutor(app), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                    override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
                })
            }
            proxy.use {
                val media = it.image ?: throw IllegalStateException("no image")
                return use(InputImage.fromMediaImage(media, it.imageInfo.rotationDegrees))
            }
        } finally {
            withContext(Dispatchers.Main) {
                provider.unbind(capture)
                shot.stop()
            }
        }
    }

    private suspend fun <T> suspendTask(future: com.google.common.util.concurrent.ListenableFuture<T>): T =
        suspendCancellableCoroutine { cont ->
            future.addListener({
                try { cont.resume(future.get()) } catch (e: Exception) { cont.resumeWithException(e.cause ?: e) }
            }, ContextCompat.getMainExecutor(app))
        }

    private suspend fun <T> suspendTask(block: () -> com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            block().addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resumeWithException(it) }
        }
}
