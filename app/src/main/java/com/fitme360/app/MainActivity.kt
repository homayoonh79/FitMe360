package com.fitme360.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
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
    private var debugMode = false
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
            updateDebugButtonAppearance()
        }
        binding.overlayView.debugMode = debugMode
        updateDebugButtonAppearance()

        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            bindCamera()
        }

        binding.capturePhotoButton.setOnClickListener { onCaptureClicked() }

        binding.upperBodyButton.setOnClickListener { selectGarmentType(GarmentType.UPPER) }
        binding.lowerBodyButton.setOnClickListener { selectGarmentType(GarmentType.LOWER) }
        binding.overallBodyButton.setOnClickListener { selectGarmentType(GarmentType.OVERALL) }
        selectGarmentType(GarmentType.UPPER)

        setupAdjustSliders()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_CAMERA -> if (granted) startCamera()
            REQUEST_STORAGE -> if (granted) {
                capturePhoto()
            } else {
                Toast.makeText(this, "Storage permission is needed to save the photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectGarmentType(type: GarmentType) {
        binding.overlayView.garmentType = type
        val selected = ContextCompat.getColor(this, R.color.accent_green)
        val unselected = ContextCompat.getColor(this, R.color.pill_unselected)
        binding.upperBodyButton.backgroundTintList =
            ColorStateList.valueOf(if (type == GarmentType.UPPER) selected else unselected)
        binding.lowerBodyButton.backgroundTintList =
            ColorStateList.valueOf(if (type == GarmentType.LOWER) selected else unselected)
        binding.overallBodyButton.backgroundTintList =
            ColorStateList.valueOf(if (type == GarmentType.OVERALL) selected else unselected)
    }

    private fun updateDebugButtonAppearance() {
        val color = if (debugMode) {
            ContextCompat.getColor(this, R.color.accent_amber)
        } else {
            ContextCompat.getColor(this, R.color.accent_blue)
        }
        binding.debugToggleButton.backgroundTintList = ColorStateList.valueOf(color)
        binding.debugToggleButton.text = if (debugMode) "Debug: ON" else "Debug: OFF"
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

    // --- Photo capture -------------------------------------------------------

    /**
     * Pre-Android-10 devices need the legacy WRITE_EXTERNAL_STORAGE permission
     * to insert into MediaStore; API 29+ can insert without it (scoped storage).
     */
    private fun onCaptureClicked() {
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

        if (needsLegacyPermission) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
        } else {
            capturePhoto()
        }
    }

    /** Rasterizes the overlay view (camera frame + warped garment) exactly as shown on screen. */
    private fun capturePhoto() {
        val view = binding.overlayView
        if (view.width == 0 || view.height == 0) {
            Toast.makeText(this, "Nothing to capture yet", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        saveBitmapToGallery(bitmap)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "FitMe360_${System.currentTimeMillis()}.jpg"
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FitMe360")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                binding.statusText.text = "Failed to save photo"
                return
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
            }
            binding.statusText.text = "Photo saved to gallery"
            Toast.makeText(this, "Saved to Pictures/FitMe360", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            binding.statusText.text = "Save failed: ${e.message}"
        }
    }

    // --- Camera ---------------------------------------------------------------

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

    companion object {
        private const val REQUEST_CAMERA = 10
        private const val REQUEST_STORAGE = 20
    }
}

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
