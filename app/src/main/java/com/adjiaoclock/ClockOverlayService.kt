package com.adjiaoclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ClockOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "adjiao_clock_channel"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_INTERVAL_MS = 16L
        private const val DOUBLE_TAP_TIMEOUT = 400L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var clockTextView: TextView? = null
    private var handler: Handler? = null
    private var lastTapTime: Long = 0
    private var tapCount: Int = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler?.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: must specify foregroundServiceType
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler = Handler(Looper.getMainLooper())
        showOverlay()
        handler?.post(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler?.removeCallbacks(updateRunnable)
        removeOverlay()
        handler = null
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density

        // Use a FrameLayout as container for reliable touch handling
        val container = FrameLayout(this)

        clockTextView = TextView(this).apply {
            text = "00:00:00.000"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(
                (12 * density).toInt(),
                (6 * density).toInt(),
                (12 * density).toInt(),
                (6 * density).toInt()
            )
            setBackgroundColor(0xB3000000.toInt()) // 70% opacity black
        }
        container.addView(clockTextView)

        // 50% screen width
        val displayMetrics = resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.5).toInt()

        val params = WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (80 * density).toInt()
        }

        // Double-tap to close: use ACTION_DOWN + tap counting
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    tapCount++
                    if (tapCount == 1) {
                        lastTapTime = now
                        // Post a delayed check: if no second tap within timeout, reset
                        handler?.postDelayed({
                            if (tapCount == 1) {
                                tapCount = 0
                            }
                        }, DOUBLE_TAP_TIMEOUT)
                    } else if (tapCount == 2) {
                        if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                            // Double tap confirmed — close
                            tapCount = 0
                            stopSelf()
                            return@setOnTouchListener true
                        }
                        tapCount = 1
                        lastTapTime = now
                    }
                    true
                }
            }
            false
        }

        overlayView = container
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            // Permission not granted
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        clockTextView = null
        windowManager = null
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        val date = Date(now)
        val timeStr = timeFormat.format(date)
        val ms = now % 1000
        val msStr = ms.toString().padStart(3, '0')
        clockTextView?.text = "$timeStr.$msStr"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "阿刁时钟",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮时钟运行"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("阿刁时钟运行中")
                .setContentText("双击悬浮窗关闭")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("阿刁时钟运行中")
                .setContentText("双击悬浮窗关闭")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        }
    }
}
