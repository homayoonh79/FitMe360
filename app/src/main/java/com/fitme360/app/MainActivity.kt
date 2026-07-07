package com.fitme360.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fitme360.app.databinding.ActivityMainBinding
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var latestFrame: Bitmap? = null
    private var latestResult: PoseLandmarkerResult? = null
    private var frameW = 0
    private var frameH = 0
    private var debugMode = true
    private var useFrontCamera = true

    // --- Slider range constants ---------------------------------------------
    // Collar/waistband offset: allows pushing the garment UP (positive) or
    // DOWN (negative) relative to the raw landmark line, not just up.
    private val topOffsetMin = -0.30f
    private val topOffsetMax = 0.50f
    private val topOffsetSpan = topOffsetMax - topOffsetMin // 0.80 -> seek max = 80

    // Garment size: widened so garments can be enlarged a lot more than before.
    private val scaleMin = 0.5f
    private val scaleMax = 3.0f
    private val scaleSpan = scaleMax - scaleMin // 2.5 -> seek max = 250
    // -------------------------------------------------------------------------

    // Keeps a short history of recent frames keyed by the timestamp they were
    // sent to the pose model with, so we can pair each result with the EXACT
    // frame it was computed from instead of whatever the newest frame is by
    // the time the (async) result arrives. Fixes the garment "lagging behind"
    // the body during movement.
    private val frameCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, Bitmap>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean {
                return size > 8
            }
        }
    )

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadGarmentFromUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = this,
                listener = { result, _ -> onPoseResult(result) },
                errorListener = { msg -> runOnUiThread { binding.statusText.text = msg } }
            )
        } catch (e: Exception) {
            // Most common cause: pose_landmarker_full.task missing/corrupt in assets/,
            // or the GPU delegate isn't supported on this device.
            binding.statusText.text = "Pose model failed to load: ${e.message}"
        }

        binding.pickGarmentButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.debugToggleButton.setOnClickListener {
            debugMode = !debugMode
            binding.overlayView.debugMode = debugMode
            binding.debugToggleButton.text = if (debugMode) "Debug: ON" else "Debug: OFF"
        }
        binding.overlayView.debugMode = debugMode

        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            bindCamera()
        }

        binding.upperBodyButton.setOnClickListener { selectGarmentType(GarmentType.UPPER) }
        binding.lowerBodyButton.setOnClickListener { selectGarmentType(GarmentType.LOWER) }
        selectGarmentType(GarmentType.UPPER)

        setupAdjustSliders()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun selectGarmentType(type: GarmentType) {
        binding.overlayView.garmentType = type
        val selected = "#4CAF50".toColorInt()
        val unselected = "#555555".toColorInt()
        binding.upperBodyButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (type == GarmentType.UPPER) selected else unselected)
        binding.lowerBodyButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (type == GarmentType.LOWER) selected else unselected)
    }

    /**
     * Wires the three on-screen sliders to the live tuning knobs on
     * ClothOverlayView, so the fit can be adjusted while watching the camera
     * preview instead of hard-coding numbers and rebuilding the app.
     */
    private fun setupAdjustSliders() {
        // topOffsetRatio: topOffsetMin..topOffsetMax (steps of 0.01)
        val topOffsetSeekMax = (topOffsetSpan * 100).toInt()
        binding.topOffsetSeekBar.max = topOffsetSeekMax
        binding.topOffsetSeekBar.progress = ((binding.overlayView.topOffsetRatio - topOffsetMin) * 100).toInt()
        binding.topOffsetLabel.text = "Collar/Waist Offset: %.2f".format(binding.overlayView.topOffsetRatio)
        binding.topOffsetSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = topOffsetMin + progress / 100f
                binding.overlayView.topOffsetRatio = ratio
                binding.topOffsetLabel.text = "Collar/Waist Offset: %.2f".format(ratio)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // garmentScaleX: scaleMin..scaleMax (steps of 0.01)
        val scaleSeekMax = (scaleSpan * 100).toInt()
        binding.garmentScaleXSeekBar.max = scaleSeekMax
        binding.garmentScaleXSeekBar.progress = ((binding.overlayView.garmentScaleX - scaleMin) * 100).toInt()
        binding.garmentScaleXLabel.text = "Garment Width: %.2fx".format(binding.overlayView.garmentScaleX)
        binding.garmentScaleXSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = scaleMin + progress / 100f
                binding.overlayView.garmentScaleX = scale
                binding.garmentScaleXLabel.text = "Garment Width: %.2fx".format(scale)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // garmentScaleY: scaleMin..scaleMax (steps of 0.01)
        binding.garmentScaleYSeekBar.max = scaleSeekMax
        binding.garmentScaleYSeekBar.progress = ((binding.overlayView.garmentScaleY - scaleMin) * 100).toInt()
        binding.garmentScaleYLabel.text = "Garment Height: %.2fx".format(binding.overlayView.garmentScaleY)
        binding.garmentScaleYSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = scaleMin + progress / 100f
                binding.overlayView.garmentScaleY = scale
                binding.garmentScaleYLabel.text = "Garment Height: %.2fx".format(scale)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadGarmentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                    ?: throw IllegalStateException("decodeStream returned null for $uri")
                binding.overlayView.setGarment(bmp)
                binding.statusText.text = "Garment loaded: ${bmp.width}x${bmp.height}"
            }
        } catch (e: Exception) {
            binding.statusText.text = "Failed to load picked image: ${e.message}"
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /** (Re)binds the camera use cases with whichever lens is currently selected. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            binding.switchCameraButton.text = if (useFrontCamera) "Camera: Front" else "Camera: Back"
        } catch (e: Exception) {
            binding.statusText.text = "Camera bind failed: ${e.message}"
        }
    }

    /**
     * NOTE: converting YUV -> JPEG -> Bitmap on every frame is simple but not the
     * fastest path. For production, replace with a direct YUV->RGB renderer
     * (e.g. a small RenderScript/OpenGL converter) to cut per-frame latency.
     */
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapOrNull()
            if (bitmap == null) return

            frameW = bitmap.width
            frameH = bitmap.height
            latestFrame = bitmap

            val ts = System.currentTimeMillis()
            frameCache[ts] = bitmap
            poseLandmarkerHelper?.detectAsync(bitmap, ts)

            runOnUiThread {
                binding.overlayView.updateFrame(bitmap, latestResult, frameW, frameH)
            }
        } catch (e: Exception) {
            runOnUiThread { binding.statusText.text = "Frame processing error: ${e.message}" }
        } finally {
            imageProxy.close()
        }
    }

    private fun onPoseResult(result: PoseLandmarkerResult) {
        latestResult = result
        val matchedFrame = frameCache.remove(result.timestampMs()) ?: latestFrame ?: return
        runOnUiThread {
            binding.overlayView.updateFrame(matchedFrame, result, matchedFrame.width, matchedFrame.height)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseLandmarkerHelper?.close()
    }
}

/** Small local helper so we don't need to pull in androidx.core-ktx's graphics extensions. */
private fun String.toColorInt(): Int = android.graphics.Color.parseColor(this)

/** Converts a YUV_420_888 ImageProxy from CameraX into an ARGB Bitmap. */
private fun ImageProxy.toBitmapOrNull(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()
    var bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

    if (imageInfo.rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
    return bmp
}
