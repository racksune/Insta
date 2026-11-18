package com.rack.instagrack

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class UnfollowService : AccessibilityService() {

    companion object {
        var instance: UnfollowService? = null
        const val IG = "com.instagram.android"

        // Do unfollow ke beech technical minimum (popup band hone ka time), isse kam nahi ho sakta
        private const val SETTLE_MS = 500L
        private const val POLL_MS = 150L
        private const val MAX_POLLS = 25
        // Ek unfollow ka lagbhag fixed overhead (tap + popup), sirf speed estimate ke liye
        private const val OVERHEAD_SEC = 1.2f

        private const val FLAGS_IDLE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        private const val FLAGS_EDIT = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var target = 0
    private var done = 0
    private var emptyRounds = 0
    private var wrongAppRounds = 0
    private var tapStreak = 0
    private var navTaps = 0
    private var awaitingConfirm = false
    private var confirmTries = 0
    private var startedAt = 0L
    private var minDelayMs = 3_000L
    private var maxDelayMs = 6_000L

    private var wm: WindowManager? = null
    private var overlayRoot: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var tvStatus: TextView? = null
    private var pbar: ProgressBar? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------- Overlay (side mein chhota box) ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun parseSec(s: String): Float? =
        s.trim().replace(',', '.').toFloatOrNull()?.coerceAtLeast(0f)

    private fun fmt(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else v.toString()

    private fun etaText(sec: Float): String =
        if (sec < 90f) "${sec.roundToInt()} sec" else "${(sec / 60f).roundToInt()} min"

    private fun watch(et: EditText, f: () -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { f() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun updateSpeed(tv: TextView, etCount: EditText, etMin: EditText, etMax: EditText) {
        val n = etCount.text.toString().toIntOrNull() ?: 0
        val lo = parseSec(etMin.text.toString()) ?: 0f
        val hi = max(parseSec(etMax.text.toString()) ?: lo, lo)
        val avg = (lo + hi) / 2f
        val per = max(avg, SETTLE_MS / 1000f) + OVERHEAD_SEC
        val rate = 60f / per
        val label = when {
            rate < 3f -> "Slow"
            rate < 10f -> "Normal"
            rate < 25f -> "Fast"
            else -> "Turbo"
        }
        var text = "Speed: $label, ~${rate.roundToInt()}/min"
        if (n > 0) text += "  |  ~${etaText(n * per)}"
        tv.text = text
    }

    fun showOverlay() {
        if (overlayRoot != null) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val root = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null)

        val panel = root.findViewById<View>(R.id.panel)
        val bubble = root.findViewById<View>(R.id.bubble)
        val header = root.findViewById<View>(R.id.header)
        val etCount = root.findViewById<EditText>(R.id.ovCount)
        val etMin = root.findViewById<EditText>(R.id.ovMin)
        val etMax = root.findViewById<EditText>(R.id.ovMax)
        val tvSpeed = root.findViewById<TextView>(R.id.ovSpeed)
        tvStatus = root.findViewById(R.id.ovStatus)
        pbar = root.findViewById(R.id.ovProgress)

        val prefs = getSharedPreferences("instagrack", Context.MODE_PRIVATE)
        etCount.setText(prefs.getInt("count", 10).toString())
        etMin.setText(fmt(prefs.getFloat("gapMin", 3f)))
        etMax.setText(fmt(prefs.getFloat("gapMax", 6f)))

        val refresh = { updateSpeed(tvSpeed, etCount, etMin, etMax) }
        watch(etCount, refresh)
        watch(etMin, refresh)
        watch(etMax, refresh)
        refresh()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FLAGS_IDLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = resources.displayMetrics.widthPixels - dp(210) - dp(8)
        lp.y = dp(120)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        overlayParams = lp
        overlayRoot = root

        // Type karne ke liye box ko focus do, baaki time Instagram ko focus rehne do
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val editTouch = View.OnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                setEditable(true)
                v.post {
                    v.requestFocus()
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            false
        }
        for (et in listOf(etCount, etMin, etMax)) {
            et.setOnTouchListener(editTouch)
            et.setOnEditorActionListener { _, _, _ ->
                setEditable(false)
                true
            }
        }

        attachDrag(header, null)
        attachDrag(bubble) {
            bubble.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }
        root.findViewById<View>(R.id.btnMin).setOnClickListener {
            setEditable(false)
            panel.visibility = View.GONE
            bubble.visibility = View.VISIBLE
        }

        root.findViewById<Button>(R.id.ovStart).setOnClickListener {
            setEditable(false)
            val n = etCount.text.toString().toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            if (n == null || n <= 0) {
                tvStatus?.text = "Sahi number daalo"
                return@setOnClickListener
            }
            val lo = parseSec(etMin.text.toString()) ?: 3f
            var hi = parseSec(etMax.text.toString()) ?: lo
            if (hi < lo) {
                hi = lo
                etMax.setText(fmt(hi))
            }
            prefs.edit().putInt("count", n).putFloat("gapMin", lo).putFloat("gapMax", hi).apply()
            start(n, lo, hi)
            packageManager.getLaunchIntentForPackage(IG)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?.let { startActivity(it) }
        }
        root.findViewById<Button>(R.id.ovStop).setOnClickListener {
            setEditable(false)
            stop("Rok diya")
        }
        root.findViewById<Button>(R.id.ovOff).setOnClickListener {
            hideOverlay()
        }

        manager.addView(root, lp)
    }

    fun hideOverlay() {
        running = false
        handler.removeCallbacksAndMessages(null)
        overlayRoot?.let { runCatching { wm?.removeView(it) } }
        overlayRoot = null
        overlayParams = null
        tvStatus = null
        pbar = null
    }

    private fun setEditable(on: Boolean) {
        val lp = overlayParams ?: return
        val v = overlayRoot ?: return
        lp.flags = if (on) FLAGS_EDIT else FLAGS_IDLE
        wm?.updateViewLayout(v, lp)
        if (!on) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        }
    }

    private fun attachDrag(handle: View, onTap: (() -> Unit)?) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            val lp = overlayParams ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    overlayRoot?.let { wm?.updateViewLayout(it, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    // ---------------- Unfollow engine ----------------

    fun start(count: Int, minSec: Float, maxSec: Float) {
        val lo = minSec.coerceAtLeast(0f)
        val hi = maxSec.coerceAtLeast(lo)
        minDelayMs = (lo * 1000f).toLong()
        maxDelayMs = (hi * 1000f).toLong()
        target = count.coerceAtLeast(1)
        done = 0
        emptyRounds = 0
        wrongAppRounds = 0
        tapStreak = 0
        navTaps = 0
        awaitingConfirm = false
        confirmTries = 0
        startedAt = SystemClock.elapsedRealtime()
        running = true
        report("Start: $target unfollow, gap ${fmt(lo)}-${fmt(hi)} sec")
        handler.removeCallbacksAndMessages(null)
        later(2500)
    }

    fun stop(msg: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        report(msg)
    }

    private fun report(msg: String) {
        tvStatus?.text = msg
        pbar?.let {
            it.max = if (target > 0) target else 100
            it.progress = done
        }
    }

    private fun later(ms: Long) {
        handler.postDelayed({ step() }, ms)
    }

    private fun nextGapMs(): Long =
        max(Random.nextLong(minDelayMs, maxDelayMs + 1), SETTLE_MS)

    private fun liveRate(): String {
        val min = (SystemClock.elapsedRealtime() - startedAt) / 60000f
        if (done == 0 || min <= 0f) return ""
        return "  ${(done / min).roundToInt()}/min"
    }

    private fun step() {
        if (!running) return

        if (done >= target) {
            stop("Ho gaya! $done unfollow.${liveRate()}")
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG) {
            wrongAppRounds++
            if (wrongAppRounds > 10) {
                stop("Instagram screen pe nahi hai. Ruk gaya. Done: $done")
                return
            }
            later(3000)
            return
        }
        wrongAppRounds = 0

        if (isBlocked(root)) {
            stop("Action Blocked aaya. Kuch ghante baad try karo. Done: $done")
            return
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        // 1) Confirm popup: "Unfollow"
        val confirm = findNode(root) { label, _ -> label.equals("Unfollow", ignoreCase = true) }
        if (confirm != null) {
            confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            done++
            awaitingConfirm = false
            tapStreak = 0
            emptyRounds = 0
            report("Unfollow $done / $target${liveRate()}")
            if (done >= target) {
                later(SETTLE_MS)
                return
            }
            later(nextGapMs())
            return
        }

        // 1b) "Following" tap ho chuka hai, popup aane ka wait (poll)
        if (awaitingConfirm) {
            confirmTries++
            if (confirmTries <= MAX_POLLS) {
                later(POLL_MS)
                return
            }
            awaitingConfirm = false
            tapStreak++
            if (tapStreak > 3) {
                stop("Unfollow popup nahi aaya (Instagram UI badla hoga). Done: $done")
                return
            }
            later(SETTLE_MS)
            return
        }

        // 2) Row ka "Following" button (header/tabs ko skip karta hai)
        val rowBtn = findNode(root) { label, r ->
            label.equals("Following", ignoreCase = true) && r.top > h * 0.2 && r.left > w * 0.5
        }
        if (rowBtn != null) {
            emptyRounds = 0
            navTaps = 0
            rowBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitingConfirm = true
            confirmTries = 0
            later(POLL_MS)
            return
        }

        // 3) Auto-navigation: Profile tab -> "following" count -> list
        if (navTaps < 4) {
            val navNode = findNode(root) { label, r ->
                (label.equals("following", ignoreCase = true) || label.endsWith(" following", ignoreCase = true)) &&
                    r.top > h * 0.1 && r.top < h * 0.6
            } ?: findNode(root) { label, r ->
                label.equals("Profile", ignoreCase = true) && r.top > h * 0.8
            }
            if (navNode != null) {
                navTaps++
                report("Following list tak ja raha hoon...")
                navNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                later(2500)
                return
            }
        }

        // 4) Kuch nahi dikha to scroll
        emptyRounds++
        if (emptyRounds >= 4) {
            stop("Aur buttons nahi mile (list khatam?). Done: $done")
            return
        }
        findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        later(2000)
    }

    private fun isBlocked(root: AccessibilityNodeInfo): Boolean {
        val phrases = listOf("Try again later", "Action Blocked", "We limit how often")
        return phrases.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    }

    /** Text ya contentDescription se node dhundhta hai aur uska clickable ancestor deta hai. */
    private fun findNode(node: AccessibilityNodeInfo?, pred: (String, Rect) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!label.isNullOrEmpty()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (pred(label, r)) {
                clickableAncestor(node)?.let { return it }
            }
        }
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), pred)?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && !n.isClickable && hops < 4) {
            n = n.parent
            hops++
        }
        return if (n != null && n.isClickable) n else null
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
