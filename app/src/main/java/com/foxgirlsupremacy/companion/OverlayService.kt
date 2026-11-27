package com.foxgirlsupremacy.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class OverlayService : LifecycleService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var renderer: CharacterRenderer
    private var faceTrackingController: FaceTrackingController? = null
    private lateinit var settings: Settings
    private lateinit var animMapping: AnimMapping
    private lateinit var controller: CompanionController
    private var overlayView: View? = null
    private var snackView: View? = null
    private var bubbleView: View? = null
    private var radialMenuView: RadialMenuView? = null
    private var loadingView: View? = null
    private var petModeActive = false
    private lateinit var params: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var userInteracting = false
    private var spriteWidthPx = 0
    private var spriteHeightPx = 0

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTick).coerceIn(0, 100)
            lastTick = now
            if (!userInteracting) controller.tick(dt)
            handler.postDelayed(this, if (controller.isWalking) TICK_WALK_MS else TICK_IDLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
        animMapping = settings.animMapping()
        renderer = createRenderer()
        recomputeSpriteSize()

        controller = CompanionController(
            onMove = { x, y ->
                params.x = x
                params.y = y
                overlayView?.let { windowManager.updateViewLayout(it, params) }
            },
            onState = { state -> renderer.setState(state) },
            onFacing = { facing -> renderer.setFacing(facing) },
            onReachLure = { eatSnack() },
        )

        startForegroundNotification()
        addOverlay()
        applySettings()
        updateFaceTracking()
        settings.registerListener(this)

        lastTick = SystemClock.uptimeMillis()
        handler.post(tick)
        handler.postDelayed(randomTalk, Random.nextLong(TALK_MIN_MS, TALK_MAX_MS))
        handler.postDelayed(idleAnimTick, Random.nextLong(IDLE_ANIM_MIN_MS, IDLE_ANIM_MAX_MS))
    }

    private val idleAnimTick = object : Runnable {
        override fun run() {
            if (!userInteracting && !petModeActive && !controller.isWalking) {
                val anims = animMapping.animsForAction("IDLE")
                if (anims.isNotEmpty()) playAnim(weightedPick(anims))
            }
            handler.postDelayed(this, Random.nextLong(IDLE_ANIM_MIN_MS, IDLE_ANIM_MAX_MS))
        }
    }

    private fun playForAction(action: String, fallback: () -> Unit) {
        val anims = animMapping.animsForAction(action)
        if (anims.isEmpty()) {
            fallback()
            return
        }
        playAnim(weightedPick(anims))
    }

    private fun playAnim(animKey: String) {
        val parts = animKey.split("¦")
        when (parts.getOrNull(0)) {
            "m" -> renderer.playMotion(parts.getOrElse(1) { "" }, parts.getOrNull(2)?.toIntOrNull() ?: 0)
            "e" -> renderer.playExpression(parts.drop(1).joinToString("¦"))
        }
    }

    private fun weightedPick(anims: List<Pair<String, Int>>): String {
        val total = anims.sumOf { it.second }.coerceAtLeast(1)
        var r = Random.nextInt(total)
        for ((key, weight) in anims) {
            r -= weight
            if (r < 0) return key
        }
        return anims.last().first
    }

    private val randomTalk = object : Runnable {
        override fun run() {
            if (!userInteracting && bubbleView == null && overlayView != null) {
                showBubble()
            }
            handler.postDelayed(this, Random.nextLong(TALK_MIN_MS, TALK_MAX_MS))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService must receive this call so its Lifecycle reaches STARTED.
        // CameraX bindToLifecycle(this, ...) will otherwise never activate the camera.
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SNACK -> dropSnack()
            ACTION_PLAY_MOTION -> renderer.playMotion(
                intent.getStringExtra(EXTRA_GROUP) ?: "",
                intent.getIntExtra(EXTRA_INDEX, 0),
            )
            ACTION_PLAY_EXPR -> intent.getStringExtra(EXTRA_NAME)?.let { renderer.playExpression(it) }
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val snackIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_SNACK),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_companion)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_snack, getString(R.string.notif_action_snack), snackIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (settings.faceTrackingEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIF_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && settings.faceTrackingEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun addOverlay(forcedX: Int? = null, forcedY: Int? = null) {
        val view = renderer.createView(this)
        renderer.setState(CharacterState.IDLE)

        val screen = screenSize()
        val startX: Int
        val startY: Int
        when {
            forcedX != null && forcedY != null -> {
                startX = forcedX
                startY = forcedY
            }
            settings.posX in 0..(screen.x - spriteWidthPx) && settings.posY >= 0 -> {
                startX = settings.posX
                startY = settings.posY
            }
            else -> {

                startX = (screen.x - spriteWidthPx) / 2
                startY = screen.y - spriteHeightPx - bottomMarginPx()
            }
        }

        params = WindowManager.LayoutParams(
            spriteWidthPx,
            spriteHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        controller.screenWidth = screen.x
        controller.spriteSize = spriteWidthPx
        controller.placeAt(startX, startY)

        view.setOnTouchListener(DragTouchListener())
        windowManager.addView(view, params)
        overlayView = view
        showLoading()
    }

    private fun createRenderer(): CharacterRenderer {
        val entry = Live2DModels.resolve(this, settings.modelId)
            ?: return PlaceholderRenderer()
        return Live2DRenderer(this, entry.dir, entry.jsonFile) {
            handler.post { hideLoading() }
        }
    }

    private fun showLoading() {
        hideLoading()
        val size = (48 * resources.displayMetrics.density).toInt()
        val spinner = ProgressBar(this)
        val loadingParams = overlayParams(size, size, touchable = false).apply {
            x = params.x + params.width / 2 - size / 2
            y = params.y + params.height / 2 - size / 2
        }
        windowManager.addView(spinner, loadingParams)
        loadingView = spinner
        handler.postDelayed(hideLoadingRunnable, LOADING_TIMEOUT_MS)
    }

    private val hideLoadingRunnable = Runnable { hideLoading() }

    private fun hideLoading() {
        handler.removeCallbacks(hideLoadingRunnable)
        loadingView?.let { runCatching { windowManager.removeView(it) } }
        loadingView = null
    }

    private fun rebuildOverlay() {
        val keepX = params.x
        val keepY = params.y
        removeSnack()
        removeBubble()
        hideLoading()
        faceTrackingController?.stop()
        faceTrackingController = null
        renderer.dispose()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        animMapping = settings.animMapping()
        renderer = createRenderer()
        recomputeSpriteSize()
        addOverlay(keepX, keepY)
        applySettings()
        updateFaceTracking()
    }

    private fun updateFaceTracking() {
        faceTrackingController?.stop()
        faceTrackingController = null
        val liveRenderer = renderer as? Live2DRenderer
        if (!settings.faceTrackingEnabled || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || liveRenderer == null) {
            liveRenderer?.setFaceTrackingData(null)
            return
        }
        faceTrackingController = FaceTrackingController(this, this) { data ->
            liveRenderer.setFaceTrackingData(data)
        }.also { it.start() }
    }

    private fun applySettings() {
        renderer.setScale(settings.scale)
        renderer.setAnimationsEnabled(settings.animationsEnabled)
        renderer.setSnowFox(settings.snowFox)
        controller.wanderEnabled = settings.wanderEnabled
        overlayView?.let { windowManager.updateViewLayout(it, params) }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            Settings.KEY_ENABLED -> if (!settings.companionEnabled) stopSelf()
            Settings.KEY_SCALE -> {
                renderer.setScale(settings.scale)
                recomputeSpriteSize()
                controller.spriteSize = spriteWidthPx
                params.width = spriteWidthPx
                params.height = spriteHeightPx
                overlayView?.let { windowManager.updateViewLayout(it, params) }
            }
            Settings.KEY_ANIMATIONS -> renderer.setAnimationsEnabled(settings.animationsEnabled)
            Settings.KEY_SNOW -> renderer.setSnowFox(settings.snowFox)
            Settings.KEY_WANDER -> controller.wanderEnabled = settings.wanderEnabled
            Settings.KEY_FACE_TRACKING -> { startForegroundNotification(); updateFaceTracking() }
            Settings.KEY_MODEL -> rebuildOverlay()
        }

        if (key?.startsWith("anim_map_") == true) {
            animMapping = settings.animMapping()
        }
    }

    private fun dropSnack() {
        if (overlayView == null) return
        removeSnack()

        val snackSize = (48 * resources.displayMetrics.density).toInt()
        val screen = screenSize()
        val snackX = Random.nextInt(0, (screen.x - snackSize).coerceAtLeast(1))

        val snackY = params.y + spriteHeightPx - snackSize

        val snack = ImageView(this).apply {
            setImageResource(R.drawable.ic_snack)
        }
        val snackParams = overlayParams(snackSize, snackSize, touchable = false).apply {
            x = snackX
            y = snackY
        }
        windowManager.addView(snack, snackParams)
        snackView = snack

        controller.lureTo(snackX + snackSize / 2 - spriteWidthPx / 2)
    }

    private fun eatSnack() {
        removeSnack()
        playForAction("FEED") {
            renderer.setState(CharacterState.EAT)
            handler.postDelayed({ renderer.setState(CharacterState.IDLE) }, EAT_MS)
        }
    }

    private fun removeSnack() {
        snackView?.let { runCatching { windowManager.removeView(it) } }
        snackView = null
    }

    private fun showBubble() {
        removeBubble()
        val lines = resources.getStringArray(R.array.chat_lines)
        val text = lines[Random.nextInt(lines.size)]

        playForAction("TALK") { renderer.setState(CharacterState.TALK) }

        val bubble = TextView(this).apply {
            setText(text)
            setBackgroundResource(R.drawable.bubble_bg)
            setTextColor(0xFF211A17.toInt())
            textSize = 14f
        }
        val margin = (8 * resources.displayMetrics.density).toInt()
        val bubbleHeight = (44 * resources.displayMetrics.density).toInt()
        val bubbleParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = false,
        ).apply {
            x = params.x
            y = (params.y - bubbleHeight - margin).coerceAtLeast(0)
        }
        windowManager.addView(bubble, bubbleParams)
        bubbleView = bubble

        handler.postDelayed({
            removeBubble()
            renderer.setState(CharacterState.IDLE)
        }, BUBBLE_MS)
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun showRadialMenu() {
        dismissRadialMenu()

        val loc = IntArray(2)
        overlayView?.getLocationOnScreen(loc)
        val cx = (loc[0] + params.width / 2).toFloat()
        val cy = (loc[1] + params.height / 2).toFloat()
        val firstItem = if (petModeActive) {
            RadialMenuView.Item(RadialAction.NORMAL, getString(R.string.action_normal), R.drawable.ic_normal)
        } else {
            RadialMenuView.Item(RadialAction.PET, getString(R.string.action_pet), R.drawable.ic_pet)
        }
        val items = listOf(
            firstItem,
            RadialMenuView.Item(RadialAction.FEED, getString(R.string.action_feed), R.drawable.ic_snack),
            RadialMenuView.Item(RadialAction.TALK, getString(R.string.action_talk), R.drawable.ic_talk),
            RadialMenuView.Item(RadialAction.BIGGER, getString(R.string.action_bigger), R.drawable.ic_bigger),
            RadialMenuView.Item(RadialAction.SMALLER, getString(R.string.action_smaller), R.drawable.ic_smaller),
            RadialMenuView.Item(RadialAction.OFF, getString(R.string.action_off), R.drawable.ic_off),
        )
        val view = RadialMenuView(this, cx, cy, items)

        val menuParams = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            x = 0
            y = 0
        }
        windowManager.addView(view, menuParams)
        radialMenuView = view
    }

    private fun dismissRadialMenu() {
        radialMenuView?.let { runCatching { windowManager.removeView(it) } }
        radialMenuView = null
    }

    private fun performRadialAction(action: RadialAction) {
        when (action) {
            RadialAction.PET -> enterPetMode()
            RadialAction.NORMAL -> exitPetMode()
            RadialAction.FEED -> dropSnack()
            RadialAction.TALK -> showBubble()
            RadialAction.BIGGER -> stepScale(SCALE_STEP)
            RadialAction.SMALLER -> stepScale(-SCALE_STEP)
            RadialAction.OFF -> settings.companionEnabled = false
        }
    }

    private fun stepScale(delta: Float) {
        if (petModeActive) exitPetMode()
        settings.scale = (settings.scale + delta).coerceIn(0.5f, 5.0f)
    }

    private fun triggerCaress() {
        playForAction("PET") { renderer.setState(CharacterState.PET) }
    }

    private fun enterPetMode() {
        if (petModeActive) return
        petModeActive = true
        userInteracting = true

        val screen = screenSize()
        val petW = (screen.x * PET_SCREEN_FRACTION).toInt()
        val petH = (petW * PORTRAIT_RATIO).toInt()
        val cx = params.x + params.width / 2
        val cy = params.y + params.height / 2
        params.width = petW
        params.height = petH
        params.x = cx - petW / 2
        params.y = (cy - petH / 2).coerceAtLeast(0)
        overlayView?.let { windowManager.updateViewLayout(it, params) }

        playForAction("PET") { renderer.setState(CharacterState.PET) }
    }

    private fun exitPetMode() {
        if (!petModeActive) return
        petModeActive = false

        val cx = params.x + params.width / 2
        val cy = params.y + params.height / 2
        params.width = spriteWidthPx
        params.height = spriteHeightPx
        params.x = cx - spriteWidthPx / 2
        params.y = (cy - spriteHeightPx / 2).coerceAtLeast(0)
        overlayView?.let { windowManager.updateViewLayout(it, params) }

        renderer.setState(CharacterState.IDLE)
        controller.placeAt(params.x, params.y)
        userInteracting = false
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var dragging = false
        private var longPressed = false
        private var menuActive = false
        private var lastCaressX = 0f
        private var lastCaressY = 0f
        private var caressAccum = 0f
        private val strokePx = 44 * resources.displayMetrics.density
        private val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                menuActive = true
                showRadialMenu()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    userInteracting = true
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    dragging = false
                    longPressed = false
                    lastCaressX = event.rawX
                    lastCaressY = event.rawY
                    caressAccum = 0f
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (menuActive) {

                        radialMenuView?.highlightAt(event.rawX, event.rawY)
                        return true
                    }
                    if (petModeActive) {

                        caressAccum += hypot(event.rawX - lastCaressX, event.rawY - lastCaressY)
                        lastCaressX = event.rawX
                        lastCaressY = event.rawY
                        if (abs(event.rawX - touchStartX) > TOUCH_SLOP ||
                            abs(event.rawY - touchStartY) > TOUCH_SLOP
                        ) {
                            handler.removeCallbacks(longPress)
                        }
                        if (caressAccum >= strokePx) {
                            caressAccum = 0f
                            triggerCaress()
                        }
                        return true
                    }
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (menuActive) {
                        val action = radialMenuView?.selectionAt(event.rawX, event.rawY)
                        dismissRadialMenu()
                        menuActive = false
                        userInteracting = false
                        action?.let { performRadialAction(it) }
                        return true
                    }
                    if (petModeActive) {

                        if (!longPressed) triggerCaress()
                        return true
                    }
                    if (dragging) {
                        settings.savePosition(params.x, params.y)
                        controller.placeAt(params.x, params.y)
                    } else if (!longPressed && settings.reactionsEnabled) {

                        playForAction("PET") {
                            renderer.setState(CharacterState.PET)
                            v.postDelayed({ renderer.setState(CharacterState.IDLE) }, PET_MS)
                        }
                    }

                    if (!petModeActive) userInteracting = false
                    return true
                }
            }
            return false
        }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun recomputeSpriteSize() {
        spriteWidthPx = (96 * resources.displayMetrics.density * settings.scale).toInt()
        spriteHeightPx = (spriteWidthPx * PORTRAIT_RATIO).toInt()
    }

    private fun bottomMarginPx(): Int = (24 * resources.displayMetrics.density).toInt()

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val dm = resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        settings.unregisterListener(this)
        faceTrackingController?.stop()
        faceTrackingController = null
        settings.savePosition(params.x, params.y)
        removeSnack()
        removeBubble()
        dismissRadialMenu()
        hideLoading()
        renderer.dispose()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }


    companion object {
        private const val CHANNEL_ID = "companion_overlay"
        private const val NOTIF_ID = 1
        private const val TOUCH_SLOP = 12f
        private const val TICK_WALK_MS = 33L
        private const val TICK_IDLE_MS = 200L
        private const val LONG_PRESS_MS = 500L
        private const val PET_MS = 400L
        private const val EAT_MS = 1600L
        private const val BUBBLE_MS = 2500L
        private const val PET_SCREEN_FRACTION = 0.7f
        private const val LOADING_TIMEOUT_MS = 8000L
        private const val SCALE_STEP = 0.5f
        private const val TALK_MIN_MS = 25_000L
        private const val TALK_MAX_MS = 70_000L
        private const val IDLE_ANIM_MIN_MS = 10_000L
        private const val IDLE_ANIM_MAX_MS = 22_000L

        private const val PORTRAIT_RATIO = 1.6f
        private const val ACTION_SNACK = "com.foxgirlsupremacy.companion.action.SNACK"
        private const val ACTION_PLAY_MOTION = "com.foxgirlsupremacy.companion.action.PLAY_MOTION"
        private const val ACTION_PLAY_EXPR = "com.foxgirlsupremacy.companion.action.PLAY_EXPR"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_NAME = "name"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun playMotion(context: Context, group: String, index: Int) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_PLAY_MOTION)
                .putExtra(EXTRA_GROUP, group)
                .putExtra(EXTRA_INDEX, index)
            context.startForegroundService(intent)
        }

        fun playExpression(context: Context, name: String) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_PLAY_EXPR)
                .putExtra(EXTRA_NAME, name)
            context.startForegroundService(intent)
        }
    }
}
