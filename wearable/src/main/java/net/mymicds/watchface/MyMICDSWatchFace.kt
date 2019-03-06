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
import android.support.v4.math.MathUtils
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.roundToInt

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
         * Rectangle scale factor for the outer school ring.
         */
        private const val SCHOOL_RING_SCALE = 0.975f

        /**
         * Rectangle scale factor for the inner class ring.
         */
        private const val CLASS_RING_SCALE = 0.935f

        /**
         * Rectangle scale factor for the tap indicator circle.
         * Calculates radius of circle as half the scaled width.
         */
        private const val TAP_INDICATOR_SCALE = 0.5f

        /**
         * API route to make schedule request to.
         */
        private const val SCHEDULE_API_ROUTE = "https://api.mymicds.net/schedule/get"

        /**
         * Time interval to make schedule API requests at, in milliseconds.
         */
        private const val SCHEDULE_API_INTERVAL = 1 /* hour */ * 60 * 60 * 1000L

        /**
         * API route to make lunch request to.
         */
        private const val LUNCH_API_ROUTE = "https://api.mymicds.net/lunch/get"

        /**
         * Time interval to make lunch API requests at, in milliseconds.
         */
        private const val LUNCH_API_INTERVAL = 6 /* hours */ * 60 * 60 * 1000L

        /**
         * Data map key that holds the user's JWT.
         */
        private const val JWT_KEY = "net.mymicds.watchface.jwt"

        /**
         * Capability that defines where the JWT is written.
         */
        private const val JWT_CAPABILITY = "retrieve_jwt"
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

        private lateinit var mNow: LocalTime

        private lateinit var mDataClient: DataClient

        private lateinit var mRequestQueue: RequestQueue
        private val mTimedRequestHandler = Handler()

        private var mRequestJWT: String? = null

        private var mSchoolToday = true
        private var mScheduleClasses = emptyList<ScheduleClass>()
        private var mSchoolEnd = LocalTime.of(15, 15)

        private var mShowLunch = false
        private var mLunchDishes = listOf("Lunch Not Available")

        private var mRegisteredTimeZoneReceiver = false

        private var mXOffset = 0f
        private var mYOffset = 0f

        private var mCenterX = 0f
        private var mCenterY = 0f

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mTimePaint: Paint
        private lateinit var mSmallTextPaint: Paint
        private lateinit var mRingPaint: Paint
        private lateinit var mTapIndicatorPaint: Paint

        private var mShowTapIndicator = false
        private var mTapIndicatorRadius = 0f

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
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MyMICDSWatchFace)
                    .setAcceptsTapEvents(true)
                    .setHideStatusBar(true)
                    .build()
            )

            mDataClient = Wearable.getDataClient(this@MyMICDSWatchFace)
            mDataClient.addListener(this)

            // Get JWT that's already been stored
            Wearable.getCapabilityClient(this@MyMICDSWatchFace)
                .getCapability(JWT_CAPABILITY, CapabilityClient.FILTER_ALL)
                .addOnSuccessListener { info ->
                    // If there's no node that can retrieve the JWT, then we might as well just return
                    val phoneNode = info.nodes.firstOrNull() ?: return@addOnSuccessListener

                    val uri = Uri.Builder()
                        .scheme("wear")
                        .path("/jwt")
                        .authority(phoneNode.id)
                        .build()

                    Log.d(TAG, "Constructed URI: $uri")

                    mDataClient.getDataItem(uri).addOnSuccessListener(::dataItemCallback)
                }

            mRequestQueue = Volley.newRequestQueue(this@MyMICDSWatchFace)

            val scheduleRequestRunnable = object: Runnable {
                override fun run() {
                    if (mRequestJWT != null) {
                        makeScheduleRequest()
                        mTimedRequestHandler.postDelayed(this, SCHEDULE_API_INTERVAL)
                    }
                }
            }
            mTimedRequestHandler.post(scheduleRequestRunnable)

            val lunchRequestRunnable = object: Runnable {
                override fun run() {
                    makeLunchRequest()
                    mTimedRequestHandler.postDelayed(this, LUNCH_API_INTERVAL)
                }
            }
            mTimedRequestHandler.post(lunchRequestRunnable)

            val resources = this@MyMICDSWatchFace.resources
            mYOffset = resources.getDimension(R.dimen.digital_y_offset)

            // Initializes background.
            mBackgroundPaint = Paint().apply {
                color = ContextCompat.getColor(applicationContext, R.color.background)
            }

            // Initializes time paint.
            mTimePaint = Paint().apply {
                typeface = NORMAL_TYPEFACE
                isAntiAlias = true
                color = ContextCompat.getColor(applicationContext, R.color.digital_text)
                textAlign = Paint.Align.CENTER
                textSize = resources.getDimension(R.dimen.digital_text_size)
            }

            // Initializes percent text paint.
            mSmallTextPaint = Paint(mTimePaint).apply {
                textSize = resources.getDimension(R.dimen.digital_text_size_small)
            }

            // Initializes outer ring paint.
            mRingPaint = Paint().apply {
                isAntiAlias = true
                color = ContextCompat.getColor(applicationContext, R.color.school_ring)
                strokeWidth = RING_STROKE_WIDTH
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            // Initializes tap indicator paint.
            mTapIndicatorPaint = Paint().apply {
                color = ContextCompat.getColor(applicationContext, R.color.tap_indicator)
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

            if (inAmbientMode) {
                mRingPaint.color = Color.LTGRAY
            } else {
                mRingPaint.color = ContextCompat.getColor(applicationContext, R.color.school_ring)
            }

            if (mLowBitAmbient) {
                mTimePaint.isAntiAlias = !inAmbientMode
                mRingPaint.isAntiAlias = !inAmbientMode
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TAP -> {
                    mShowTapIndicator = false
                    if (withinTapRegion(x, y)) {
                        mShowLunch = true
                    }
                }
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    if (withinTapRegion(x, y)) {
                        mShowTapIndicator = true
                    }
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> mShowTapIndicator = false
                else -> super.onTapCommand(tapType, x, y, eventTime)
            }
        }

        private fun withinTapRegion(x: Int, y: Int): Boolean {
            val distanceSquared = (x - mCenterX).pow(2) + (y - mCenterY).pow(2)
            return distanceSquared <= mTapIndicatorRadius.pow(2)
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            /**
             * Combines two strings together with a colon and truncates the first string if the overall text is too large.
             */
            fun combineAndTruncate(text1: String, text2: String): String {
                val text = "$text1: $text2"
                val boundingBox = Rect().also { mSmallTextPaint.getTextBounds(text, 0, text.length, it) }

                Log.d(TAG, "Width ratio for \"$text1\": ${boundingBox.width().toDouble() / bounds.width()}")

                return if (boundingBox.width() >= 0.7 * bounds.width()) {
                    "${text1.substring(0..8)}…: $text2"
                } else {
                    text
                }
            }

            mNow = LocalTime.now()

            val timeFormat = DateTimeFormatter.ofPattern("h:mm")
            val timeText = timeFormat.format(mNow)
            val centerTextBounds = Rect().also { mTimePaint.getTextBounds(timeText, 0, 1, it) }
            val centerTextYOffset = centerTextBounds.exactCenterY()

            mCenterX = bounds.exactCenterX()
            mCenterY = bounds.exactCenterY()

            // Draw the background.
            if (mAmbient) {
                canvas.drawColor(Color.BLACK)
            } else {
                canvas.drawRect(
                    0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), mBackgroundPaint
                )
            }
            // Draw tap indicator.
            if (mShowTapIndicator) {
                mTapIndicatorRadius = bounds.scale(TAP_INDICATOR_SCALE).width() / 2
                canvas.drawCircle(mCenterX, mCenterY, mTapIndicatorRadius, mTapIndicatorPaint)
            }

            if (!mShowLunch) {
                // Draw time.
                canvas.drawText(timeText, mCenterX, mCenterY - centerTextYOffset, mTimePaint)
            } else {
                // TODO: Draw lunch text.
            }

            // Draw school ring.

            val schoolStart = LocalTime.of(if (LocalDate.now().dayOfWeek == DayOfWeek.WEDNESDAY) 9 else 8, 0)
            var percent = getPercent(mScheduleClasses.firstOrNull()?.start ?: schoolStart, mSchoolEnd)

            if (mSchoolToday) {
                canvas.drawArc(bounds.scale(SCHOOL_RING_SCALE), -90f, 360 * percent, false, mRingPaint)
            }

            // Draw percent text.

            val percentTextYOffset = Rect().also { mSmallTextPaint.getTextBounds("1", 0, 1, it) }.exactCenterY() * 2

            var percentClassName = "School"

            // Do stuff that requires schedule classes to be populated
            if (!mScheduleClasses.isEmpty()) {
                val currentClass = mScheduleClasses.find { mNow in it.start..it.end }
                if (currentClass != null) {
                    percent = getPercent(currentClass.start, currentClass.end)
                    val classPaint = Paint(mRingPaint).apply { color = if (mAmbient) Color.LTGRAY else currentClass.color }

                    // Draw class ring, set class name.

                    canvas.drawArc(bounds.scale(CLASS_RING_SCALE), -90f, 360 * percent, false, classPaint)
                    percentClassName = currentClass.name
                }

                // Draw next class text.

                if (!mShowLunch) {
                    val nextClass = mScheduleClasses.firstOrNull { mNow < it.start && it.name != "Break" }
                    if (nextClass != null && !mAmbient) {
                        canvas.drawText(
                            combineAndTruncate(nextClass.name, timeFormat.format(nextClass.start)),
                            mCenterX,
                            mCenterY + centerTextBounds.height() - percentTextYOffset,
                            mSmallTextPaint
                        )
                    }
                }
            }

            // Draw class percent text.
            if (!mAmbient && !mShowLunch) {
                canvas.drawText(
                    if (mSchoolToday) combineAndTruncate(
                        percentClassName,
                        "${(percent * 100).roundToInt()}%"
                    ) else "No School",
                    mCenterX,
                    mCenterY - centerTextBounds.height(),
                    mSmallTextPaint
                )
            }
        }

        /**
         * Scales a rectangle evenly on all sides.
         */
        private fun Rect.scale(scaleFactor: Float): RectF {
            val deltaX = width() * (1 - scaleFactor)
            val deltaY = height() * (1 - scaleFactor)

            return RectF(
                left + deltaX,
                top + deltaY,
                right - deltaX,
                bottom - deltaY
            )
        }

        /**
         * Gets percentage of time completed between two LocalTimes relative to the current time.
         */
        private fun getPercent(start: LocalTime, end: LocalTime): Float {
            val numerator = mNow.toSecondOfDay() - start.toSecondOfDay()
            val denominator = end.toSecondOfDay() - start.toSecondOfDay()

            return MathUtils.clamp(numerator.toFloat() / denominator, 0f, 1f)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()

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

            mTimePaint.textSize = textSize
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

        private fun makeScheduleRequest() {
            Log.d(TAG, "Making schedule request")

            val jsonRequest = object: JsonObjectRequest(
                Request.Method.POST,
                SCHEDULE_API_ROUTE,
                null,
                Response.Listener { response ->
                    Log.i(TAG, "Response: $response")

                    val schedule = response.getJSONObject("schedule")

                    mSchoolToday = schedule.get("day") != JSONObject.NULL

                    Log.d(TAG, "Is there school today: $mSchoolToday")

                    val classesArray = schedule.getJSONArray("classes")
                    val scheduleClasses = mutableListOf<ScheduleClass>()

                    for (i in 0 until classesArray.length() - 1) {
                        val class1 = ScheduleClass.fromJSON(classesArray.getJSONObject(i))
                        val class2 = ScheduleClass.fromJSON(classesArray.getJSONObject(i + 1))

                        scheduleClasses.add(class1)
                        // If the classes aren't back to back, insert a break
                        if (class1.end != class2.start) {
                            scheduleClasses.add(ScheduleClass(
                                "Break",
                                class1.end,
                                class2.start,
                                Color.GRAY
                            ))
                        }
                        scheduleClasses.add(class2)
                    }

                    mScheduleClasses = scheduleClasses.toList()
                    Log.i(TAG, "Classes: $mScheduleClasses")

                    mSchoolEnd = if (mScheduleClasses.isEmpty()) {
                        LocalTime.of(15, 15)
                    } else {
                        minOf(mScheduleClasses.last().end, LocalTime.of(15, 15))
                    }
                },
                Response.ErrorListener { error ->
                    Log.e(TAG, "Error: ${error.message}")
                }
            ) {
                override fun getHeaders() = mutableMapOf("Authorization" to "Bearer $mRequestJWT")
            }

            mRequestQueue.add(jsonRequest)
        }

        private fun makeLunchRequest() {
            Log.d(TAG, "Making lunch request")

            val jsonRequest = JsonObjectRequest(
                Request.Method.POST,
                LUNCH_API_ROUTE,
                null,
                Response.Listener { response ->
                    val lunch = response.getJSONObject("lunch")

                    // TODO: Maybe make user request to get the correct school?
                    val todaysLunch = lunch
                        .getJSONObject(LocalDate.now().toString())
                        .getJSONObject("upperschool")
                        .getJSONObject("categories")
                        .getJSONArray("Main Dish")
                    val dishes = mutableListOf<String>()

                    for (i in 0 until todaysLunch.length()) {
                        dishes.add(todaysLunch.getString(i))
                    }

                    mLunchDishes = dishes.toList()
                },
                Response.ErrorListener { error ->
                    Log.e(TAG, "Error: ${error.message}")
                }
            )

            mRequestQueue.add(jsonRequest)
        }

        override fun onDataChanged(dataEvents: DataEventBuffer) {
            Log.d(TAG, "Data change listener called")
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) return

                dataItemCallback(event.dataItem)
            }
        }

        private fun dataItemCallback(dataItem: DataItem) {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            mRequestJWT = dataMap.getString(JWT_KEY)
            Log.i(TAG, "JWT: $mRequestJWT")
            makeScheduleRequest()
        }
    }
}
