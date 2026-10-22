package dev.swipeto.sender.aim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads SwipeTo QR markers with the rear camera (CameraX image analysis + ML Kit, bundled model,
 * on-device). The camera runs only for the duration of one [firstMarker] call; frames are never
 * stored or sent anywhere. Without the camera permission it returns null at once.
 */
class CameraMarkerScanner(private val context: Context) : MarkerScanner {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override suspend fun firstMarker(timeoutMs: Long, accept: (MarkerPayload) -> Boolean): MarkerPayload? {
        if (!hasPermission) return null
        val found = CompletableDeferred<MarkerPayload>()
        val busy = AtomicBoolean(false)
        val owner = ScanLifecycle()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { frame ->
                    val image = frame.image
                    if (image == null || found.isCompleted || !busy.compareAndSet(false, true)) {
                        frame.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            codes.firstNotNullOfOrNull { MarkerPayload.parse(it.rawValue)?.takeIf(accept) }?.let(found::complete)
                        }
                        .addOnCompleteListener {
                            busy.set(false)
                            frame.close()
                        }
                }
            }

        val provider = ProcessCameraProvider.awaitInstance(context)
        return try {
            withContext(Dispatchers.Main) {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                owner.start()
            }
            withTimeoutOrNull(timeoutMs) { found.await() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("SwipeTo", "camera marker scan failed: $e")
            null
        } finally {
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                provider.unbind(analysis)
                owner.stop()
            }
        }
    }

    /** A lifecycle that is resumed only while one scan runs, so the camera is never left on. */
    private class ScanLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
