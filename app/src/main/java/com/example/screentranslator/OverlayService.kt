package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SOURCE = "source"
        const val ACTION_STOP = "com.example.screentranslator.STOP"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = ConcurrentHashMap<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var overlay: TranslationOverlayView? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var latestBitmap: Bitmap? = null

    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var modelJob: Job? = null
    private var busy = false

    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = getDataExtra(intent)
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "ja"
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // Android 14: ต้องเรียก startForeground แบบ mediaProjection ก่อนขอ MediaProjection
        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val bounds = wm.currentWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()
        screenDpi = resources.displayMetrics.densityDpi

        setupTranslator(source)
        setupCapture(resultCode, data)
        showOverlayAndBubble()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        bubble?.let { runCatching { wm.removeView(it) } }
        overlay?.let { runCatching { wm.removeView(it) } }
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        recognizer?.close()
        translator?.close()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun getDataExtra(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    // ---------- การตั้งค่า OCR และการแปล ----------

    private fun setupTranslator(source: String) {
        val (srcLang, rec) = when (source) {
            "ko" -> TranslateLanguage.KOREAN to
                    TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "en" -> TranslateLanguage.ENGLISH to
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            else -> TranslateLanguage.JAPANESE to
                    TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        }
        recognizer?.close()
        translator?.close()
        recognizer = rec
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(srcLang)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
        )
        // ดาวน์โหลดโมเดลแปลครั้งแรก (ครั้งเดียว หลังจากนั้นใช้ออฟไลน์ได้)
        modelJob = scope.launch {
            try {
                translator?.downloadModelIfNeeded()?.await()
            } catch (e: Exception) {
                Toast.makeText(this@OverlayService, "โหลดโมเดลแปลไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- การจับภาพหน้าจอ ----------

    private fun setupCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data)
        projection = p

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                latestBitmap = imageToBitmap(img)
            } finally {
                img.close()
            }
        }, mainHandler)

        // Android 14: สร้าง VirtualDisplay ได้เพียงครั้งเดียวต่อ MediaProjection token
        virtualDisplay = p.createVirtualDisplay(
            "ScreenTranslator",
            screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    // ---------- UI ลอย ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showOverlayAndBubble() {
        // เพิ่ม overlay ก่อน เพื่อให้ปุ่มลอยอยู่ชั้นบนสุด
        val ov = TranslationOverlayView(this)
        overlay = ov
        val ovParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(ov, ovParams)

        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val view = TextView(this).apply {
            text = "แปล"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 63, 81, 181))
            }
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * dp).toInt()
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX
                    val dy = ev.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        bubble = view
    }

    // ---------- ขั้นตอนหลัก: จับภาพ → OCR → แปล → วาดทับ ----------

    private fun onBubbleTap() {
        val ov = overlay ?: return
        // ถ้ามีคำแปลค้างอยู่ กดอีกครั้งเพื่อซ่อน
        if (ov.hasContent) {
            ov.clear()
            return
        }
        if (busy) return
        busy = true

        scope.launch {
            try {
                bubble?.visibility = View.INVISIBLE
                delay(350) // รอให้หน้าจอวาดใหม่โดยไม่มีปุ่มลอยอยู่ในภาพ

                val bmp = latestBitmap ?: error("ยังไม่มีภาพจากหน้าจอ")
                val rec = recognizer ?: error("ยังไม่ได้ตั้งค่า OCR")
                modelJob?.join()

                val text = rec.process(InputImage.fromBitmap(bmp, 0)).await()
                val blocks = text.textBlocks.mapNotNull { b ->
                    b.boundingBox?.let { rect -> rect to b.text }
                }

                // แปลทุกกล่องพร้อมกัน เพื่อให้เร็วขึ้น
                val results = blocks.map { (rect, t) ->
                    async(Dispatchers.Default) { rect to translate(t) }
                }.awaitAll()

                ov.show(results)
            } catch (e: Exception) {
                Toast.makeText(this@OverlayService, "ผิดพลาด: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                bubble?.visibility = View.VISIBLE
                busy = false
            }
        }
    }

    private suspend fun translate(src: String): String {
        cache[src]?.let { return it }
        val t = translator ?: return src
        val out = t.translate(src).await()
        cache[src] = out
        return out
    }

    // ---------- การแจ้งเตือนของ Foreground Service ----------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Screen Translator", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator กำลังทำงาน")
            .setContentText("กดปุ่มแปลลอยเพื่อแปลหน้าจอ")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "หยุด", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}

/** View ที่วาดกล่องข้อความแปลทับตำแหน่งเดิม */
class TranslationOverlayView(context: android.content.Context) : View(context) {

    private val items = mutableListOf<Pair<Rect, String>>()
    private val bgPaint = android.graphics.Paint().apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    private val textPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    val hasContent: Boolean get() = items.isNotEmpty()

    fun show(list: List<Pair<Rect, String>>) {
        items.clear()
        items.addAll(list)
        invalidate()
    }

    fun clear() {
        items.clear()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        for ((rect, text) in items) {
            val boxH = rect.height().toFloat().coerceAtLeast(1f)
            textPaint.textSize = (boxH * 0.45f).coerceIn(14f, 48f)
            val width = rect.width().coerceAtLeast(40)
            val layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, width)
                .setIncludePad(false)
                .build()
            val bottom = maxOf(rect.bottom.toFloat(), rect.top + layout.height.toFloat())
            canvas.drawRoundRect(
                rect.left.toFloat(), rect.top.toFloat(),
                rect.right.toFloat(), bottom, 8f, 8f, bgPaint
            )
            canvas.save()
            canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
