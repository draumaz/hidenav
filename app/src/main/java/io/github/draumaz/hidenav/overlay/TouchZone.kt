package io.github.draumaz.hidenav.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.draumaz.hidenav.IHideNavService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An invisible strip over where the pill used to be, so Circle to Search still works.
 *
 * It sits below the Taskbar window, which isn't touchable there in gesture mode, so the
 * strip gets the touch first while Launcher's input monitor sees it in parallel:
 *  - Swipes: Launcher recognises them and pilfers the pointer; the strip just gets CANCEL.
 *  - Holds: after [HOLD_MS] without moving, the shell service replays a long press that
 *    Launcher turns into Circle to Search.
 *  - Taps: re-injected underneath with the strip made untouchable for a moment, so apps
 *    with something clickable at the very bottom still get it.
 *
 * During a hold the strip draws its own copy of the pill and fades it in, so it's already
 * fully visible when the replay briefly un-hides Launcher's real pill. That hides the
 * real pill's hard one-frame flash. The copy then fades out again.
 *
 * A visible overlay window also keeps this process at perceptible priority, so no
 * foreground-service notification is needed to keep the strip alive.
 */
class TouchZone(
    private val context: Context,
    private val service: () -> IHideNavService?,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val params by lazy { layoutParams() }

    fun show() {
        if (view != null) return
        val zone = ZoneView(context)
        try {
            windowManager.addView(zone, params)
            view = zone
        } catch (e: Exception) {
            Log.w(TAG, "addView failed", e)
        }
    }

    fun hide() {
        val zone = view ?: return
        view = null
        main.removeCallbacksAndMessages(null)
        runCatching { windowManager.removeView(zone) }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (ZONE_WIDTH_DP * density).roundToInt(),
            navBarHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "HideNav touch zone"
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun navBarHeight(): Int {
        val res = context.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).roundToInt()
    }

    private fun setTouchable(touchable: Boolean) {
        val zone = view ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(zone, params) }
    }

    private inner class ZoneView(context: Context) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        /** Set while our own replayed long press is in flight, so we ignore its events. */
        private var replaying = false

        private val density = resources.displayMetrics.density
        private val pillWidth = PILL_WIDTH_DP * density
        private val pillHeight = PILL_HEIGHT_DP * density
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL_COLOR }

        init {
            alpha = 0f
        }

        private val fadeIn = Runnable { animate().alpha(1f).setDuration(FADE_IN_MS).start() }
        private val fadeOut = Runnable {
            main.removeCallbacks(fadeIn)
            animate().alpha(0f).setDuration(FADE_OUT_MS).start()
        }

        private val hold = Runnable {
            tracking = false
            replaying = true
            service()?.runCatching { longPressHandle(downX, downY) }
            // Keep our pill up until Launcher's real one has flashed and gone again.
            main.postDelayed(fadeOut, PILL_DWELL_MS)
            main.postDelayed({ replaying = false }, REPLAY_GUARD_MS)
        }

        override fun onDraw(canvas: Canvas) {
            // Same size and place as Launcher's pill: centred in the navigation bar area.
            val left = (width - pillWidth) / 2
            val top = (height - pillHeight) / 2
            val radius = pillHeight / 2
            canvas.drawRoundRect(left, top, left + pillWidth, top + pillHeight, radius, radius, pillPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (replaying) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    tracking = true
                    main.removeCallbacks(fadeOut)
                    main.postDelayed(fadeIn, FADE_IN_DELAY_MS)
                    main.postDelayed(hold, HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> if (tracking &&
                    (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)
                ) {
                    // A swipe: Launcher will pilfer it. Stop treating it as a hold.
                    tracking = false
                    main.removeCallbacks(hold)
                    fadeOut.run()
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    if (tracking) {
                        fadeOut.run()
                        passThroughTap(event.rawX, event.rawY)
                    }
                    tracking = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    if (tracking) fadeOut.run()
                    tracking = false
                }
            }
            return true
        }

        private fun passThroughTap(x: Float, y: Float) {
            val shell = service() ?: return
            setTouchable(false)
            // The delay lets the untouchable flag reach InputDispatcher before the tap does.
            shell.runCatching { tap(x, y, TAP_DELAY_MS) }
            main.postDelayed({ setTouchable(true) }, TAP_DELAY_MS + 200)
        }
    }

    private companion object {
        const val TAG = "HideNavZone"
        /** Wide enough to cover the pill (about 108 dp) with some margin either side. */
        const val ZONE_WIDTH_DP = 180
        /**
         * Our part of the hold; Launcher's 450 ms long-press timer runs after this. Swipes
         * get pilfered well before it, and a tap held longer than this opens Circle to Search.
         */
        const val HOLD_MS = 150L
        const val TAP_DELAY_MS = 60L
        const val REPLAY_GUARD_MS = 1200L

        /**
         * Pixel Launcher's phone pill is 108 x 4 dp. Its colour (taskbar_stashed_handle_light_color)
         * is #EBFFFFFF, but ours is opaque: two translucent pills stacked during the real flash
         * would show up as a brighter blip.
         */
        const val PILL_WIDTH_DP = 108
        const val PILL_HEIGHT_DP = 4
        const val PILL_COLOR = 0xFFFFFFFF.toInt()
        /**
         * The fade-in starts a little into the hold so quick taps mostly show nothing, and
         * finishes just before [HOLD_MS], when the real pill is about to flash.
         */
        const val FADE_IN_DELAY_MS = 40L
        const val FADE_IN_MS = 100L
        /** How long after the replay starts our pill stays up. The real flash is about 30 ms. */
        const val PILL_DWELL_MS = 150L
        const val FADE_OUT_MS = 200L
    }
}
