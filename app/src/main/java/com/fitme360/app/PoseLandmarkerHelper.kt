package com.fitme360.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Thin wrapper around MediaPipe's PoseLandmarker running in LIVE_STREAM mode.
 * Model file must be placed at: app/src/main/assets/pose_landmarker_full.task
 * (download from: https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)
 */
class PoseLandmarkerHelper(
    context: Context,
    private val listener: (PoseLandmarkerResult, Long) -> Unit,
    private val errorListener: (String) -> Unit = { Log.e("PoseLandmarkerHelper", it) }
) {
    private var poseLandmarker: PoseLandmarker? = null

    init {
        poseLandmarker = try {
            buildLandmarker(context, Delegate.GPU)
        } catch (e: Exception) {
            // GPU delegate isn't supported on every device (common on emulators
            // and some budget phones) - fall back to CPU instead of crashing.
            buildLandmarker(context, Delegate.CPU)
        }
    }

    private fun buildLandmarker(context: Context, delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .setDelegate(delegate)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setResultListener { result, _ ->
                listener(result, System.currentTimeMillis())
            }
            .setErrorListener { e -> errorListener(e.message ?: "Unknown PoseLandmarker error") }
            .build()

        return PoseLandmarker.createFromOptions(context, options)
    }

    /** Feed one camera frame. Must be called with monotonically increasing timestamps. */
    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        poseLandmarker?.detectAsync(mpImage, timestampMs)
    }

    fun close() {
        poseLandmarker?.close()
    }
}
