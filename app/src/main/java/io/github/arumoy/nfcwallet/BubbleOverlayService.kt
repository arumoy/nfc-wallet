package io.github.arumoy.nfcwallet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * A foreground service that shows a draggable floating bubble over other apps.
 * Tapping the bubble expands a floating card listing the selected saved card's
 * fields. Tapping a field copies its value to the clipboard (for manual paste).
 *
 * Sensitive values are not passed through the Intent; only the card id is, and
 * the (decrypted) card is loaded from [CardRepository] inside the service.
 *
 * This deliberately uses clipboard copy rather than injecting text into other
 * apps: Android has no safe API for cross-app text injection, and doing so via
 * an accessibility service is the hallmark of overlay malware.
 */
class BubbleOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var cardView: View? = null
    private var dismissView: View? = null
    private var card: SavedCard? = null

    // Layout params kept so the floating card can be anchored to the bubble.
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    /** Height of the bottom drag-to-dismiss region. */
    private val dismissZoneHeight: Int get() = dp(140)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cardId = intent?.getStringExtra(EXTRA_CARD_ID)
        card = cardId?.let { id -> CardRepository(this).getCards().firstOrNull { it.id == id } }

        if (card == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (bubbleView == null) {
            showBubble()
        }
        return START_STICKY
    }

    // --- Bubble --------------------------------------------------------------

    private fun showBubble() {
        val size = dp(56)
        val bubble = TextView(this).apply {
            text = "💳"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3F51B5.toInt())
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        attachDragAndTap(bubble, params) { toggleCard() }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
    }

    /** Handles dragging (move the view), tap (invoke [onTap]), and drag-to-dismiss. */
    private fun attachDragAndTap(
        view: View,
        params: WindowManager.LayoutParams,
        onTap: () -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val touchSlop = dp(8)

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                    // Keep the pop-out card attached to the bubble as it moves.
                    if (cardView != null) positionCardToBubble()
                    if (moved) {
                        showDismissZone()
                        setDismissZoneActive(isInDismissZone(event.rawY))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        onTap()
                    } else if (isInDismissZone(event.rawY)) {
                        // Dropped in the dismiss region: tear everything down.
                        stopSelf()
                        return@setOnTouchListener true
                    }
                    hideDismissZone()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideDismissZone()
                    true
                }
                else -> false
            }
        }
    }

    /** True when the given raw Y (touch point) falls within the bottom dismiss region. */
    private fun isInDismissZone(rawY: Float): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        return rawY >= (screenHeight - dismissZoneHeight)
    }

    private fun showDismissZone() {
        if (dismissView != null) return
        val zone = TextView(this).apply {
            text = "Drop here to remove"
            gravity = Gravity.CENTER
            setTextColor(0xFFB00020.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // Light red, translucent.
            setBackgroundColor(0x33FF5252)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dismissZoneHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        runCatching { windowManager.addView(zone, params) }
        dismissView = zone
    }

    /** Darkens the dismiss region when the bubble is hovering over it. */
    private fun setDismissZoneActive(active: Boolean) {
        (dismissView as? TextView)?.setBackgroundColor(
            if (active) 0x66FF1744 else 0x33FF5252
        )
    }

    private fun hideDismissZone() {
        dismissView?.let { runCatching { windowManager.removeView(it) } }
        dismissView = null
    }

    // --- Floating card -------------------------------------------------------

    private fun toggleCard() {
        if (cardView != null) {
            removeCard()
        } else {
            showCard()
        }
    }

    private fun showCard() {
        val c = card ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFCCCCCC.toInt())
            }
        }

        // Header row with title and close button.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = c.nickname.ifBlank { "Card" }
            setTextColor(0xFF222222.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), 0, dp(8), 0)
            // Collapse the card only — the bubble keeps floating so the user can
            // reopen it to copy another field.
            setOnClickListener { removeCard() }
        })
        container.addView(header)

        container.addView(hint("Tap a field to copy it"))

        // One tappable row per non-empty field.
        addFieldRow(container, "Name", c.cardholderName)
        addFieldRow(container, "Number", c.pan.filter { it.isDigit() })
        addFieldRow(container, "Expiry", c.expiry)
        addFieldRow(container, "CVV", c.cvv)

        val params = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(container, params)
        cardView = container
        cardParams = params
        // Anchor it to the bubble's current position.
        positionCardToBubble()
    }

    /**
     * Positions the floating card just below the bubble, anchored to its
     * current location. Clamps within the screen so the card stays on-screen
     * even when the bubble is near an edge.
     */
    private fun positionCardToBubble() {
        val cp = cardParams ?: return
        val bp = bubbleParams ?: return
        val bubbleSize = dp(56)
        val cardWidth = dp(280)
        val gap = dp(8)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // Prefer placing the card below the bubble; if there isn't room, place above.
        val belowY = bp.y + bubbleSize + gap
        val estimatedCardHeight = dp(220)
        cp.y = if (belowY + estimatedCardHeight <= screenHeight) {
            belowY
        } else {
            (bp.y - estimatedCardHeight - gap).coerceAtLeast(0)
        }

        // Align left edge with the bubble, clamped to the screen width.
        cp.x = bp.x.coerceIn(0, (screenWidth - cardWidth).coerceAtLeast(0))

        cardView?.let { runCatching { windowManager.updateViewLayout(it, cp) } }
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF999999.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun addFieldRow(container: LinearLayout, label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val row = TextView(this).apply {
            text = "$label: $value"
            setTextColor(0xFF222222.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { copyToClipboard(label, value) }
        }
        container.addView(row)
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Mark card data as sensitive so the OS keeps it out of clipboard previews (API 33+).
        val clip = ClipData.newPlainText(label, value).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun removeCard() {
        cardView?.let { runCatching { windowManager.removeView(it) } }
        cardView = null
        cardParams = null
    }

    // --- Lifecycle / helpers -------------------------------------------------

    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Card bubble",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Card bubble active")
            .setContentText("Tap the bubble to view and copy card fields.")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        removeCard()
        hideDismissZone()
        bubbleView = null
        bubbleParams = null
    }

    companion object {
        const val EXTRA_CARD_ID = "card_id"
        private const val CHANNEL_ID = "card_bubble"
        private const val NOTIFICATION_ID = 42
    }
}
