package net.mymicds.watchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v4.content.ContextCompat
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowInsets
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.wearable.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.*

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
class MyMICDSWatchFace : CanvasWatchFaceService() {

    companion object {
        private const val TAG = "MyMICDSWatchFace"

        private val NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

        /**
         * Updates rate in milliseconds for interactive mode. We update once a second since seconds
         * are displayed in interactive mode.
         */
        private const val INTERACTIVE_UPDATE_RATE_MS = 1000

        /**
         * Handler message id for updating the time periodically in interactive mode.
         */
        private const val MSG_UPDATE_TIME = 0

        /**
         * Stroke width for outer rings.
         */
        private const val RING_STROKE_WIDTH = 8f

        /**
         * API route to make request to.
         */
        private const val API_ROUTE = "https://api.mymicds.net/schedule/get"

        /**
         * Time interval to make API requests at, in milliseconds.
         */
        private const val API_INTERVAL = 1 /* hour */ * 60 * 60 * 1000L

        /**
         * Data map key that holds the user's JWT.
         */
        private const val JWT_KEY = "net.mymicds.watchface.jwt"
    }

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: MyMICDSWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<MyMICDSWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine(), DataClient.OnDataChangedListener {

        private lateinit var mCalendar: Calendar

        private lateinit var mDataClient: DataClient

        private lateinit var mRequestQueue: RequestQueue
        private val mTimedRequestHandler = Handler()

        private var mRequestJWT: String? = null

        private var mRegisteredTimeZoneReceiver = false

        private var mXOffset: Float = 0F
        private var mYOffset: Float = 0F

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mTextPaint: Paint
        private lateinit var mRingPaint: Paint

        /**
         * The arc angles for each of the outer progress rings, in degrees.
         */
        private var mInnerRingArcAngle: Float = 0f
        private var mOuterRingArcAngle: Float = 0f

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false
        private var mAmbient: Boolean = false

        private val mUpdateTimeHandler: Handler = EngineHandler(this)

        private val mTimeZoneReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MyMICDSWatchFace)
                    .build()
            )


            mDataClient = Wearable.getDataClient(this@MyMICDSWatchFace).apply {
                addListener(this@Engine)
            }

            mCalendar = Calendar.getInstance()

            mRequestQueue = Volley.newRequestQueue(this@MyMICDSWatchFace)

            val addRequestRunnable = object: Runnable {
                override fun run() {
                    if (mRequestJWT != null) {
                        makeMyMICDSRequest()
                        mTimedRequestHandler.postDelayed(this, API_INTERVAL)
                    }
                }
            }
            mTimedRequestHandler.post(addRequestRunnable)

            val resources = this@MyMICDSWatchFace.resources
            mYOffset = resources.getDimension(R.dimen.digital_y_offset)

            // Initializes background.
            mBackgroundPaint = Paint().apply {
                color = ContextCompat.getColor(applicationContext, R.color.background)
            }

            // Initializes Watch Face.
            mTextPaint = Paint().apply {
                typeface = NORMAL_TYPEFACE
                isAntiAlias = true
                color = ContextCompat.getColor(applicationContext, R.color.digital_text)
                textAlign = Paint.Align.CENTER
            }

            // Initializes outer ring paint.
            mRingPaint = Paint().apply {
                isAntiAlias = true
                color = ContextCompat.getColor(applicationContext, R.color.outer_rings)
                strokeWidth = RING_STROKE_WIDTH
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        }

        override fun onDestroy() {
            mDataClient.removeListener(this)
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false
            )
            mBurnInProtection = properties.getBoolean(
                WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false
            )
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            if (mLowBitAmbient) {
                mTextPaint.isAntiAlias = !inAmbientMode
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            // Draw the background.
            if (mAmbient) {
                canvas.drawColor(Color.BLACK)
            } else {
                canvas.drawRect(
                    0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), mBackgroundPaint
                )
            }

            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()

            // Draw time.

            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            val text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE))
            val centerTextYOffset = Rect().also { mTextPaint.getTextBounds("1", 0, 1, it) }.exactCenterY()

            canvas.drawText(text, centerX, centerY - centerTextYOffset, mTextPaint)

            // Draw rings.

            canvas.drawArc(scaleRect(bounds, 0.90f), -90f, mInnerRingArcAngle, false, mRingPaint)
            canvas.drawArc(scaleRect(bounds, 0.95f), -90f, mOuterRingArcAngle, false, mRingPaint)
        }

        private fun scaleRect(rect: Rect, scaleFactor: Float): RectF {
            val deltaX = rect.width() * (1 - scaleFactor)
            val deltaY = rect.height() * (1 - scaleFactor)

            return RectF(
                rect.left + deltaX,
                rect.top + deltaY,
                rect.right - deltaX,
                rect.bottom - deltaY
            )
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()

                // Update time zone in case it changed while we weren't visible.
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MyMICDSWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@MyMICDSWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        override fun onApplyWindowInsets(insets: WindowInsets) {
            super.onApplyWindowInsets(insets)

            // Load resources that have alternate values for round watches.
            val resources = this@MyMICDSWatchFace.resources
            val isRound = insets.isRound
            mXOffset = resources.getDimension(
                if (isRound)
                    R.dimen.digital_x_offset_round
                else
                    R.dimen.digital_x_offset
            )

            val textSize = resources.getDimension(
                if (isRound)
                    R.dimen.digital_text_size_round
                else
                    R.dimen.digital_text_size
            )

            mTextPaint.textSize = textSize
        }

        /**
         * Starts the [.mUpdateTimeHandler] timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !isInAmbientMode
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }

        private fun makeMyMICDSRequest() {
            Log.d(TAG, "Making request")

            val jsonRequest = object: JsonObjectRequest(
                Request.Method.POST,
                API_ROUTE,
                JSONObject() // TODO: Remove after testing
                    .put("year", 2018)
                    .put("month", 12)
                    .put("day", 14),
                Response.Listener { response ->
                    Log.i(TAG, "Response: $response")
                    // TODO: Handle schedule response, figure out percentage calculation
                },
                Response.ErrorListener { error ->
                    Log.e(TAG, "Error: ${error.message}")
                    // TODO: Figure out if it needs to do anything special on error
                }
            ) {
                override fun getHeaders() = mutableMapOf("Authorization" to "Bearer $mRequestJWT")
            }

            mRequestQueue.add(jsonRequest)
        }

        override fun onDataChanged(dataEvents: DataEventBuffer) {
            Log.d(TAG, "Data change listener called")
            dataEvents.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return

                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                mRequestJWT = dataMap.getString(JWT_KEY)
                Log.i(TAG, "JWT: $mRequestJWT")
                makeMyMICDSRequest()
            }
        }
    }
}
