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

/** Which part of the body the currently loaded garment image should fit to. */
enum class GarmentType { UPPER, LOWER }

/**
 * Draws the live camera frame and warps the chosen garment bitmap onto the
 * detected body using a 4-point perspective mapping.
 *
 * UPPER garments (shirts, t-shirts) are fitted between the shoulder line and
 * the hip line. LOWER garments (pants, skirts) are fitted between the hip
 * line and the ankle line. Both cases reuse the same generic "top pair /
 * bottom pair" quad computation below.
 */
class ClothOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** When true, draws every pose landmark, the warp quad, and diagnostic numbers. */
    var debugMode: Boolean = false

    /** Which body region the loaded garment image should be fitted to. */
    var garmentType: GarmentType = GarmentType.UPPER

    private var frameBitmap: Bitmap? = null
    private var garmentBitmap: Bitmap? = null
    private var poseResult: PoseLandmarkerResult? = null
    private var imageWidth = 1
    private var imageHeight = 1

    // --- Fit tuning knobs ---------------------------------------------------
    // The quad's top corners come from the top landmark pair (shoulders for
    // UPPER, hips for LOWER), and the bottom corners from the bottom pair
    // (hips for UPPER, ankles for LOWER). Bottom width is expressed as a
    // fraction of top width, since the raw bottom landmarks are anatomically
    // narrower than the visible garment should be (hips vs. shoulders,
    // ankles vs. hips).
    var bottomWidthRatio = 0.92f       // desired bottom width, as a fraction of top width
    var bottomOvershootRatio = 0f      // optional small extension below the bottom line, 0 = exact

    // How far above the top landmarks the garment should start, as a fraction
    // of the top width. Positive = garment raised up (e.g. collar covers the
    // neck). Negative = garment pushed down. 0 = starts exactly on the
    // landmark line.
    var topOffsetRatio = 0.15f

    // Independent size multipliers for the fitted garment, applied around
    // the quad's own center. 1.0 = exact fit to the computed quad.
    var garmentScaleX = 1.0f
    var garmentScaleY = 1.0f
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

    fun setGarment(bitmap: Bitmap) {
        garmentBitmap = bitmap
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

        // UPPER needs landmarks up to the hips (index 24). LOWER needs
        // landmarks up to the ankles (index 28).
        val landmarksNeeded = if (garmentType == GarmentType.LOWER) 29 else 25
        if (landmarks == null || landmarks.size < landmarksNeeded) {
            if (debugMode) {
                drawDebugLine(canvas, 0, "NO POSE DETECTED (landmarks=${landmarks?.size ?: 0}, need=$landmarksNeeded)")
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

        // BlazePose / MediaPipe Pose indices:
        // 11=L shoulder, 12=R shoulder, 23=L hip, 24=R hip, 27=L ankle, 28=R ankle
        val (topLm1, topLm2, bottomLm1, bottomLm2) = when (garmentType) {
            GarmentType.UPPER -> listOf(landmarks[11], landmarks[12], landmarks[23], landmarks[24])
            GarmentType.LOWER -> listOf(landmarks[23], landmarks[24], landmarks[27], landmarks[28])
        }

        val topA = toScreen(topLm1)
        val topB = toScreen(topLm2)
        val bottomA = toScreen(bottomLm1)
        val bottomB = toScreen(bottomLm2)

        if (debugMode) {
            for (p in listOf(topA, topB, bottomA, bottomB)) {
                canvas.drawCircle(p[0], p[1], 10f, keyDotPaint)
            }
        }

        // Don't trust the model's anatomical "left/right" labels for screen
        // position - depending on camera mirroring, they can end up on
        // either side of the screen. Always pick whichever point actually
        // has the smaller X as the screen-left corner, or the quad can
        // self-cross into a bowtie shape.
        val (rawTopLeft, rawTopRight) = if (topA[0] <= topB[0]) topA to topB else topB to topA
        val (screenLeftBottomY, screenRightBottomY) =
            if (bottomA[0] <= bottomB[0]) bottomA[1] to bottomB[1] else bottomB[1] to bottomA[1]

        val topWidth = kotlin.math.hypot((rawTopRight[0] - rawTopLeft[0]).toDouble(), (rawTopRight[1] - rawTopLeft[1]).toDouble()).toFloat()

        // Shift the top edge up/down relative to the raw landmark line
        // (e.g. raise a collar above the shoulders, or push a waistband
        // down/up relative to the hip line).
        val topOffset = topWidth * topOffsetRatio
        val topLeft = floatArrayOf(rawTopLeft[0], rawTopLeft[1] - topOffset)
        val topRight = floatArrayOf(rawTopRight[0], rawTopRight[1] - topOffset)

        // Bottom corners: Y comes from each bottom landmark; X is centered on
        // the bottom pair's midpoint with a width equal to
        // bottomWidthRatio * topWidth, since the raw bottom landmarks
        // (hips or ankles) are anatomically narrower than the top ones.
        val desiredBottomWidth = topWidth * bottomWidthRatio
        val bottomCenterX = (bottomA[0] + bottomB[0]) / 2f
        val bottomOvershoot = topWidth * bottomOvershootRatio
        val bottomLeft = floatArrayOf(bottomCenterX - desiredBottomWidth / 2f, screenLeftBottomY + bottomOvershoot)
        val bottomRight = floatArrayOf(bottomCenterX + desiredBottomWidth / 2f, screenRightBottomY + bottomOvershoot)

        // Scale the whole quad up/down around its own center so the garment
        // grows/shrinks while staying centered on the body. X and Y are
        // scaled independently so width and height/length can be tuned apart.
        if (garmentScaleX != 1.0f || garmentScaleY != 1.0f) {
            val centerX = (topLeft[0] + topRight[0] + bottomLeft[0] + bottomRight[0]) / 4f
            val centerY = (topLeft[1] + topRight[1] + bottomLeft[1] + bottomRight[1]) / 4f
            fun scaleAroundCenter(p: FloatArray) {
                p[0] = centerX + (p[0] - centerX) * garmentScaleX
                p[1] = centerY + (p[1] - centerY) * garmentScaleY
            }
            scaleAroundCenter(topLeft)
            scaleAroundCenter(topRight)
            scaleAroundCenter(bottomLeft)
            scaleAroundCenter(bottomRight)
        }

        if (debugMode) {
            val path = android.graphics.Path().apply {
                moveTo(topLeft[0], topLeft[1])
                lineTo(topRight[0], topRight[1])
                lineTo(bottomRight[0], bottomRight[1])
                lineTo(bottomLeft[0], bottomLeft[1])
                close()
            }
            canvas.drawPath(path, quadPaint)
            drawDebugLine(canvas, 6, "mode=$garmentType topPx=${topWidth.toInt()} bottomPx=${desiredBottomWidth.toInt()} scale=${"%.2f".format(scale)}")
            val garment = garmentBitmap
            drawDebugLine(canvas, 7, "garmentBitmap=${if (garment == null) "NULL (pick one!)" else "${garment.width}x${garment.height}"}")
        }

        val garment = garmentBitmap ?: return

        srcPoints[0] = 0f; srcPoints[1] = 0f
        srcPoints[2] = garment.width.toFloat(); srcPoints[3] = 0f
        srcPoints[4] = 0f; srcPoints[5] = garment.height.toFloat()
        srcPoints[6] = garment.width.toFloat(); srcPoints[7] = garment.height.toFloat()

        dstPoints[0] = topLeft[0]; dstPoints[1] = topLeft[1]
        dstPoints[2] = topRight[0]; dstPoints[3] = topRight[1]
        dstPoints[4] = bottomLeft[0]; dstPoints[5] = bottomLeft[1]
        dstPoints[6] = bottomRight[0]; dstPoints[7] = bottomRight[1]

        warpMatrix.reset()
        warpMatrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        canvas.drawBitmap(garment, warpMatrix, paint)
    }

    private fun drawDebugLine(canvas: Canvas, line: Int, text: String) {
        val y = 40f + line * 44f
        val textWidth = textPaint.measureText(text)
        canvas.drawRect(10f, y - 32f, 20f + textWidth, y + 10f, textBgPaint)
        canvas.drawText(text, 15f, y, textPaint)
    }

    private fun drawDebugText(canvas: Canvas, landmarks: List<NormalizedLandmark>?) {
        drawDebugLine(canvas, 0, "frame=${imageWidth}x${imageHeight}  view=${width}x${height}")
        drawDebugLine(canvas, 1, "landmarks=${landmarks?.size ?: 0}  mode=$garmentType")
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
