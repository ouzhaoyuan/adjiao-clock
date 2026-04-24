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
        private const val CLOCK_TEMPLATE = "00:00:00.000"
        private const val TOUCH_SLOP = 10 // px threshold: drag vs tap
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var clockTextView: TextView? = null
    private var handler: Handler? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Drag state
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f

    // Double-tap state
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

        val container = FrameLayout(this)

        clockTextView = TextView(this).apply {
            text = CLOCK_TEMPLATE
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(
                (10 * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
                (4 * density).toInt()
            )
            setBackgroundColor(0xB3000000.toInt())
            measure(
                View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.UNSPECIFIED
            )
            minWidth = measuredWidth
        }
        container.addView(clockTextView)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Center horizontally on screen
            val screenWidth = resources.displayMetrics.widthPixels
            val viewWidth = clockTextView!!.measuredWidth + (20 * density).toInt()
            x = (screenWidth - viewWidth) / 2
            y = (60 * density).toInt()
        }

        // Touch: drag + double-tap close
        container.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        downRawX = event.rawX
                        downRawY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // It's a tap — check double-tap
                        val now = System.currentTimeMillis()
                        tapCount++
                        if (tapCount == 1) {
                            lastTapTime = now
                            handler?.postDelayed({
                                if (tapCount == 1) {
                                    tapCount = 0
                                }
                            }, DOUBLE_TAP_TIMEOUT)
                        } else if (tapCount >= 2) {
                            if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                                tapCount = 0
                                stopSelf()
                                return@setOnTouchListener true
                            }
                            tapCount = 1
                            lastTapTime = now
                        }
                    }
                    isDragging = false
                    true
                }
            }
            false
        }

        overlayView = container
        try {
            windowManager?.addView(overlayView, layoutParams)
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
        layoutParams = null
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
                .setContentText("拖动移动，双击关闭")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("阿刁时钟运行中")
                .setContentText("拖动移动，双击关闭")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        }
    }
}
