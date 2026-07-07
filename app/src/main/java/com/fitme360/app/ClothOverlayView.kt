package com.fitme360.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Draws the live camera frame and warps the chosen shirt bitmap onto the
 * detected body using a 4-point (shoulders + hips) perspective mapping,
 * instead of the old "scale + paste at one point" approach. This keeps the
 * shirt aligned even when the person turns or leans.
 */
class ClothOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** When true, draws every pose landmark, the warp quad, and diagnostic numbers. */
    var debugMode: Boolean = false

    private var frameBitmap: Bitmap? = null
    private var shirtBitmap: Bitmap? = null
    private var poseResult: PoseLandmarkerResult? = null
    private var imageWidth = 1
    private var imageHeight = 1

    // --- Fit tuning knobs ---------------------------------------------------
    // These are the main values to tweak if the shirt still looks too
    // wide/narrow or sits too high/low. Adjust in small steps (0.02-0.05).
    var shoulderPaddingRatio = 0.18f   // extra width added past each shoulder point
    var topPaddingRatio = 0.08f        // how far the collar is pulled up above the shoulder line
    var bottomOvershootRatio = 0.04f   // small overshoot below the hip line
    // Hip LANDMARKS sit on the pelvis joint, which is anatomically much
    // narrower than the visible torso/shoulder width - using them directly
    // produces a narrow "pennant" shape. Instead we target the bottom width
    // as a fraction of the shoulder width (most shirts don't taper much).
    var bottomWidthRatio = 0.92f       // desired bottom width, as a fraction of shoulder width
    // -------------------------------------------------------------------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val warpMatrix = Matrix()
    private val srcPoints = FloatArray(8)
    private val dstPoints = FloatArray(8)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val keyDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }
    private val textBgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setShirt(bitmap: Bitmap) {
        shirtBitmap = bitmap
        invalidate()
    }

    /** Call for every processed camera frame, with its matching pose result. */
    fun updateFrame(frame: Bitmap, result: PoseLandmarkerResult?, srcW: Int, srcH: Int) {
        frameBitmap = frame
        poseResult = result
        imageWidth = srcW
        imageHeight = srcH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val frame = frameBitmap ?: return
        val scale = maxOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val dx = (width - frame.width * scale) / 2f
        val dy = (height - frame.height * scale) / 2f

        val baseMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(frame, baseMatrix, paint)

        val landmarks = poseResult?.landmarks()?.firstOrNull()

        if (debugMode) {
            drawDebugText(canvas, landmarks)
        }

        if (landmarks == null || landmarks.size < 25) {
            if (debugMode) {
                drawDebugLine(canvas, 0, "NO POSE DETECTED (landmarks=${landmarks?.size ?: 0})")
            }
            return
        }

        fun toScreen(lm: NormalizedLandmark): FloatArray {
            val px = lm.x() * imageWidth * scale + dx
            val py = lm.y() * imageHeight * scale + dy
            return floatArrayOf(px, py)
        }

        if (debugMode) {
            // Draw every detected landmark as a small red dot so you can see
            // whether the model is tracking the body correctly at all.
            for (lm in landmarks) {
                val p = toScreen(lm)
                canvas.drawCircle(p[0], p[1], 6f, dotPaint)
            }
        }

        // BlazePose / MediaPipe Pose indices: 11=L shoulder, 12=R shoulder, 23=L hip, 24=R hip
        val lShoulder = landmarks[11]
        val rShoulder = landmarks[12]
        val lHip = landmarks[23]
        val rHip = landmarks[24]

        val ls = toScreen(lShoulder)
        val rs = toScreen(rShoulder)
        val lh = toScreen(lHip)
        val rh = toScreen(rHip)

        if (debugMode) {
            for (p in listOf(ls, rs, lh, rh)) {
                canvas.drawCircle(p[0], p[1], 10f, keyDotPaint)
            }
        }

        val shoulderWidth = kotlin.math.hypot((rs[0] - ls[0]).toDouble(), (rs[1] - ls[1]).toDouble()).toFloat()
        val hipWidth = kotlin.math.hypot((rh[0] - lh[0]).toDouble(), (rh[1] - lh[1]).toDouble()).toFloat()

        val pad = shoulderWidth * shoulderPaddingRatio
        val topPad = shoulderWidth * topPaddingRatio
        val bottomOvershoot = shoulderWidth * bottomOvershootRatio

        // Target bottom width as a fraction of shoulder width, since the raw
        // hip-joint landmarks are much narrower than the visible torso.
        val desiredBottomWidth = shoulderWidth * bottomWidthRatio
        val hipPad = ((desiredBottomWidth - hipWidth) / 2f).coerceAtLeast(0f)

        val topLeft = floatArrayOf(ls[0] - pad, ls[1] - topPad)
        val topRight = floatArrayOf(rs[0] + pad, rs[1] - topPad)
        val bottomLeft = floatArrayOf(lh[0] - hipPad, lh[1] + bottomOvershoot)
        val bottomRight = floatArrayOf(rh[0] + hipPad, rh[1] + bottomOvershoot)

        if (debugMode) {
            val path = android.graphics.Path().apply {
                moveTo(topLeft[0], topLeft[1])
                lineTo(topRight[0], topRight[1])
                lineTo(bottomRight[0], bottomRight[1])
                lineTo(bottomLeft[0], bottomLeft[1])
                close()
            }
            canvas.drawPath(path, quadPaint)
            drawDebugLine(canvas, 6, "shoulderPx=${shoulderWidth.toInt()} hipPx=${hipWidth.toInt()} hipPad=${hipPad.toInt()} scale=${"%.2f".format(scale)}")
            val shirt = shirtBitmap
            drawDebugLine(canvas, 7, "shirtBitmap=${if (shirt == null) "NULL (pick one!)" else "${shirt.width}x${shirt.height}"}")
        }

        val shirt = shirtBitmap ?: return

        srcPoints[0] = 0f; srcPoints[1] = 0f
        srcPoints[2] = shirt.width.toFloat(); srcPoints[3] = 0f
        srcPoints[4] = 0f; srcPoints[5] = shirt.height.toFloat()
        srcPoints[6] = shirt.width.toFloat(); srcPoints[7] = shirt.height.toFloat()

        dstPoints[0] = topLeft[0]; dstPoints[1] = topLeft[1]
        dstPoints[2] = topRight[0]; dstPoints[3] = topRight[1]
        dstPoints[4] = bottomLeft[0]; dstPoints[5] = bottomLeft[1]
        dstPoints[6] = bottomRight[0]; dstPoints[7] = bottomRight[1]

        warpMatrix.reset()
        warpMatrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        canvas.drawBitmap(shirt, warpMatrix, paint)
    }

    private fun drawDebugLine(canvas: Canvas, line: Int, text: String) {
        val y = 40f + line * 44f
        val textWidth = textPaint.measureText(text)
        canvas.drawRect(10f, y - 32f, 20f + textWidth, y + 10f, textBgPaint)
        canvas.drawText(text, 15f, y, textPaint)
    }

    private fun drawDebugText(canvas: Canvas, landmarks: List<NormalizedLandmark>?) {
        drawDebugLine(canvas, 0, "frame=${imageWidth}x${imageHeight}  view=${width}x${height}")
        drawDebugLine(canvas, 1, "landmarks=${landmarks?.size ?: 0}")
        if (landmarks != null && landmarks.size >= 25) {
            val ls = landmarks[11]; val rs = landmarks[12]
            val lh = landmarks[23]; val rh = landmarks[24]
            drawDebugLine(canvas, 2, "L-shoulder norm=(${"%.3f".format(ls.x())}, ${"%.3f".format(ls.y())})")
            drawDebugLine(canvas, 3, "R-shoulder norm=(${"%.3f".format(rs.x())}, ${"%.3f".format(rs.y())})")
            drawDebugLine(canvas, 4, "L-hip norm=(${"%.3f".format(lh.x())}, ${"%.3f".format(lh.y())})")
            drawDebugLine(canvas, 5, "R-hip norm=(${"%.3f".format(rh.x())}, ${"%.3f".format(rh.y())})")
        }
    }
}
