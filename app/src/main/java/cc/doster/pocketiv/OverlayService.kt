package cc.doster.pocketiv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK as CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD as THUD
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK as TICK
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Captures the screen at half resolution, runs on-device OCR only when the info card changed,
 * and shows IV% on a floating button. Tap = pause/resume capture, hold = copy rename, drag = move.
 */
class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var reader: ImageReader
    private lateinit var dex: List<Species>
    private lateinit var moves: Map<String, String>
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var paused = false
    private var busy = false
    private var lastHash = 0L
    private var renameText = ""
    private var lastScan: Scan? = null
    private val memory = ScanMemory()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        dex = Species.load(this)
        moves = Moves.load(this)
        wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val w = bounds.width() / 2
        val h = bounds.height() / 2
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)?.apply {
                registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopSelf() // user revoked capture or locked the phone
                }, handler)
                display = createVirtualDisplay("pocketiv", w, h, resources.displayMetrics.densityDpi / 2,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
            }
        if (projection == null) { // consent token already used or expired
            stopSelf()
            return START_NOT_STICKY
        }
        addBubble()
        handler.post(tick)
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!busy) scan()
            handler.postDelayed(this, Prefs.interval(this@OverlayService))
        }
    }

    private fun scan() {
        val img = reader.acquireLatestImage() ?: return
        val plane = img.planes[0]
        val buf = plane.buffer
        // Hash a sparse grid over the left side of the bottom half (name/HP/weight/appraisal bars). The animated
        // Pokemon (top half) and team leader (bottom right) are left out, so a static card skips OCR.
        var hash = 1L
        for (y in img.height / 2 until img.height step maxOf(1, img.height / 128))
            for (x in 0 until img.width * 45 / 100 step maxOf(1, img.width / 64))
                hash = hash * 31 + (buf.get(y * plane.rowStride + x * plane.pixelStride).toInt() shr 4)
        if (hash == lastHash) {
            img.close()
            return
        }
        lastHash = hash
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val w = img.width // bitmap may be wider: row padding
        val h = img.height
        img.close()
        busy = true
        ocr.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.map {
                    val box = it.boundingBox ?: android.graphics.Rect()
                    Line(it.text, box.exactCenterY() / h.toDouble(), box.left / w.toDouble(), box.height() / h.toDouble())
                }
                val px = Pixels(w, h) { x, y -> bmp.getPixel(x, y) }
                val scan = ScreenParser.parse(lines, dex, px, Prefs.maxArcLevel(this), lastScan, moves)
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("PocketIV", "lines=${lines.map { it.text }} arc=${ScreenParser.arcDegrees(px)} " +
                    "bars=${ScreenParser.appraisalBars(px, lines)} -> ${scan?.species?.name} ${scan?.hits?.size}")
                ScreenParser.trainerLevel(lines)?.let(::saveTrainerLevel)
                if (scan?.arcOff == true) askForProfile()
                // One misread frame shouldn't wipe a good result while the same Pokemon (same CP) is on screen
                if (scan != null) {
                    val result = memory.add(scan)
                    History.get(this).put(Record.from(result), memory.linkedFrom)
                    show(result)
                }
                else if (ScreenParser.cp(lines) != lastScan?.cp) show(null)
            }
            .addOnCompleteListener {
                busy = false
                bmp.recycle()
            }
    }

    private var celebrated: Scan? = null

    private fun show(scan: Scan?) {
        lastScan = scan
        if (scan == null) {
            style("IV", null, IDLE)
            renameText = ""
            return
        }
        val (lo, hi) = IvMath.ivRange(scan.hits)
        val levels = scan.hits.map { it.level }.distinct().sorted()
        val lv = { l: Double -> if (l % 1 == 0.0) "${l.toInt()}" else "${l.toInt()}½" }
        val level = if (levels.size == 1) "L${lv(levels[0])}" else "L${lv(levels.first())}–${lv(levels.last())}"
        val perfect = lo == 100
        style(if (lo == hi) "$lo%" else "$lo–$hi%", level, if (perfect) PERFECT else tier(lo), dark = perfect)
        if (perfect && celebrated?.let { it.species == scan.species && it.cp == scan.cp } != true) {
            celebrated = scan // once per Pokemon: pulse + haptic fanfare
            celebrate()
            bubble.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150)
                .withEndAction { bubble.animate().scaleX(1f).scaleY(1f).setDuration(250).start() }.start()
        }
        renameText = IvMath.rename(Prefs.scheme(this), scan.species.baseName, scan.hits, scan.moveTypes)
    }

    /** Rising ticks into a heavy thud ("ta-ta-ta-BOOM"), unlike any notification buzz. Pixel haptics play
     * the primitives natively; the waveform is the fallback. */
    private fun celebrate() {
        val vib = getSystemService(VibratorManager::class.java).defaultVibrator
        val effect = if (vib.areAllPrimitivesSupported(TICK, CLICK, THUD)) {
            VibrationEffect.startComposition()
                .addPrimitive(TICK, 0.4f)
                .addPrimitive(TICK, 0.7f, 70)
                .addPrimitive(CLICK, 1f, 70)
                .addPrimitive(THUD, 1f, 120)
                .compose()
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 25, 70, 35, 120, 150), intArrayOf(0, 100, 0, 170, 0, 255, 0, 255), -1)
        }
        vib.vibrate(effect)
    }

    /** Appraisal-star colors, by the lowest possible IV%: 3★ green, 2★ blue, 1★ orange, 0★ grey. */
    private fun tier(lo: Int) = when {
        lo >= 82 -> 0xF02E7D32.toInt()
        lo >= 67 -> 0xF01565C0.toInt()
        lo >= 51 -> 0xF0E65100.toInt()
        else -> 0xF0424242.toInt()
    }

    /** Big IV% line, small level line underneath. Perfect flips to gold with dark text. */
    private fun style(main: String, sub: String?, color: Int, dark: Boolean = false) {
        val text = android.text.SpannableStringBuilder(main)
        if (sub != null) {
            val start = text.length
            text.append("\n").append(sub)
            text.setSpan(android.text.style.RelativeSizeSpan(0.6f), start, text.length, 0)
        }
        bubble.text = text
        bubble.setTextColor(if (dark) 0xFF3E2723.toInt() else Color.WHITE)
        (bubble.background as GradientDrawable).apply {
            setColor(color)
            setStroke(dp(2), if (dark) 0xFFFFF8E1.toInt() else 0x66FFFFFF)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun saveTrainerLevel(level: Int) {
        if (level == Prefs.trainerLevel(this)) return
        Prefs.set(this, "trainer", level.toLong())
        Toast.makeText(this, "Trainer level $level saved", Toast.LENGTH_SHORT).show()
    }

    private var lastAsk = 0L

    /** Arc dot fits no level: trainer level is wrong (still the default 1, or you leveled up). Nudge once a minute. */
    private fun askForProfile() {
        val now = System.currentTimeMillis()
        if (now - lastAsk < 60_000) return
        lastAsk = now
        Toast.makeText(this, "Levels look off — open your Trainer profile so PocketIV can read your level",
            Toast.LENGTH_LONG).show()
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            handler.removeCallbacks(tick)
            display?.surface = null // stops the system rendering frames for us
            style("❚❚", null, IDLE)
            bubble.alpha = 0.5f
        } else {
            display?.surface = reader.surface
            lastHash = 0
            lastScan?.let(::show) ?: style("IV", null, IDLE)
            bubble.alpha = 1f
            handler.post(tick)
        }
    }

    private fun copyRename() {
        if (renameText.isEmpty()) {
            Toast.makeText(this, "No Pokémon read yet", Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("rename", renameText))
        Toast.makeText(this, "Copied $renameText", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 400
        }
        bubble = TextView(this).apply {
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(48)
            setLineSpacing(0f, 0.9f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat() }
        }
        style("IV", null, IDLE)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent) = true.also { togglePause() }
            override fun onLongPress(e: MotionEvent) = copyRename()
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float) = true.also {
                lp.x -= dx.toInt()
                lp.y -= dy.toInt()
                wm.updateViewLayout(bubble, lp)
            }
        })
        bubble.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        wm.addView(bubble, lp)
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("scan", "Screen reading", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "scan")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PocketIV is reading the screen")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        display?.release()
        projection?.stop()
        if (::bubble.isInitialized) wm.removeView(bubble)
        ocr.close()
        running = false
    }

    override fun onCreate() {
        running = true
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val IDLE = 0xE6212121.toInt()
        private const val PERFECT = 0xFFFFC107.toInt() // gold
        var running = false
    }
}
