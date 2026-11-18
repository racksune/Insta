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
        private const val SETTLE_MS = 150L
        private const val POLL_MS = 50L
        private const val MAX_POLLS = 70
        // Unfollow click ke baad dusra confirm popup aaya ya nahi, ye dekhne ka wait
        private const val CONFIRM_WAIT_MS = 120L
        // Ek unfollow ka lagbhag fixed overhead (tap + popup), sirf speed estimate ke liye
        private const val OVERHEAD_SEC = 0.7f

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
    private var countedThisRow = false
    private var pendingGap = false
    private var lastSig = ""
    private var stepCount = 0
    private var seenList = false
    private var speedMul = 1
    private var offListBacks = 0
    private var offListSeen = 0
    private var nextWiggleAt = 0
    private var tabFixes = 0
    private var scrollInfo = ""
    private val rowQueue = ArrayList<AccessibilityNodeInfo>()
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

    /** Speed multiplier ke hisaab se wait chhota karo (floor se kam nahi). */
    private fun t(ms: Long, floor: Long = 20L): Long = max(ms / speedMul, floor)

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
        val per = (max(avg, SETTLE_MS / 1000f) + OVERHEAD_SEC) / speedMul
        val rate = 60f / per
        val label = when {
            rate < 3f -> "Slow"
            rate < 10f -> "Normal"
            rate < 25f -> "Fast"
            else -> "Turbo"
        }
        var text = "Speed ${speedMul}x: $label, ~${rate.roundToInt()}/min"
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

        // Speed multiplier buttons: 1x 2x 3x 4x 5x 10x
        speedMul = prefs.getInt("mul", 1)
        val mulBtns = listOf(
            R.id.ovX1 to 1, R.id.ovX2 to 2, R.id.ovX3 to 3,
            R.id.ovX4 to 4, R.id.ovX5 to 5, R.id.ovX10 to 10
        ).map { (id, m) -> root.findViewById<Button>(id) to m }
        fun paintMul() {
            for ((b, m) in mulBtns) {
                val on = m == speedMul
                b.setBackgroundResource(if (on) R.drawable.btn_primary else R.drawable.btn_secondary)
                b.setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        for ((b, m) in mulBtns) {
            b.setOnClickListener {
                setEditable(false)
                speedMul = m
                prefs.edit().putInt("mul", m).apply()
                paintMul()
                refresh()
            }
        }
        paintMul()

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
        countedThisRow = false
        pendingGap = false
        lastSig = ""
        seenList = false
        offListBacks = 0
        offListSeen = 0
        nextWiggleAt = Random.nextInt(6, 13)
        tabFixes = 0
        scrollInfo = ""
        rowQueue.clear()
        confirmTries = 0
        startedAt = SystemClock.elapsedRealtime()
        running = true
        val canGesture = (serviceInfo?.capabilities ?: 0) and
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        report(
            "Start: $target unfollow, gap ${fmt(lo)}-${fmt(hi)} sec" +
                if (canGesture) "" else "\n(Gesture permission nahi: accessibility_service_config.xml update karo, phir Accessibility mein Instagrack OFF-ON)"
        )
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
        max(Random.nextLong(minDelayMs, maxDelayMs + 1) / speedMul, t(SETTLE_MS, 30L))

    private fun liveRate(): String {
        val min = (SystemClock.elapsedRealtime() - startedAt) / 60000f
        if (done == 0 || min <= 0f) return ""
        return "  ${(done / min).roundToInt()}/min"
    }

    private class N(val node: AccessibilityNodeInfo, val label: String?, val r: Rect)

    /** Screen ka tree sirf EK baar padho (har getChild ek IPC call hai, isliye wahi slow tha). Offscreen items skip. */
    private fun snapshot(root: AccessibilityNodeInfo): List<N> {
        val out = ArrayList<N>(200)
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(N(n, (n.text ?: n.contentDescription)?.toString()?.trim(), r))
            if (depth > 30) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    /** Tez rasta: system se seedha "Unfollow" text wale nodes maango (poora tree nahi padhna). */
    private fun findUnfollowFast(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText("Unfollow")) {
            val l = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (l.equals("Unfollow", ignoreCase = true)) {
                clickableAncestor(n)?.let { return it }
            }
        }
        return null
    }

    private fun step() {
        if (!running) return
        stepCount++
        // Box ka keyboard/focus hatao, warna Instagram ke popup band ho jate hain
        if (overlayParams?.flags == FLAGS_EDIT) setEditable(false)

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

        if (stepCount % 6 == 0 && isBlocked(root)) {
            stop("Action Blocked aaya. Kuch ghante baad try karo. Done: $done")
            return
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val pollMs = t(POLL_MS, 20L)
        val maxPolls = (3500L / pollMs).toInt() // popup ka wait hamesha ~3.5 sec

        // 1) "Unfollow" (⋮ menu ka laal option ya "Unfollow user?" dialog, dono)
        var confirm = findUnfollowFast(root)
        if (confirm == null && awaitingConfirm && confirmTries == maxPolls / 3) {
            confirm = findNode(root) { label, _ -> label.equals("Unfollow", ignoreCase = true) }
        }
        if (confirm != null && !(done >= target && !countedThisRow)) {
            confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!countedThisRow) {
                done++
                countedThisRow = true
                pendingGap = true
            }
            awaitingConfirm = false
            tapStreak = 0
            emptyRounds = 0
            report("Unfollow $done / $target${liveRate()}")
            later(t(CONFIRM_WAIT_MS, 40L))
            return
        }

        if (done >= target) {
            stop("Ho gaya! $done unfollow.${liveRate()}")
            return
        }

        // Dusra dialog nahi aaya: ab user ka diya gap (jitna bacha hai) ruko
        if (pendingGap) {
            pendingGap = false
            later(max(nextGapMs() - t(CONFIRM_WAIT_MS, 40L), 0L))
            return
        }

        // 1b) Row button tap ho chuka hai, "Unfollow" aane ka wait (poll)
        if (awaitingConfirm) {
            confirmTries++
            if (confirmTries <= maxPolls) {
                later(pollMs)
                return
            }
            awaitingConfirm = false
            tapStreak++
            if (tapStreak > 3) {
                stop("Unfollow option nahi mila. Screen pe: ${labelsOnScreen(snapshot(root))}. Done: $done")
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK) // khula sheet band karo
            later(t(SETTLE_MS + 300, 100L))
            return
        }

        var snapCache: List<N>? = null
        fun getSnap(): List<N> {
            var x = snapCache
            if (x == null) {
                x = snapshot(root)
                snapCache = x
            }
            return x
        }
        // Tez check: upar ke tabs ("... subscriptions") system se seedha poocho
        var onList = root.findAccessibilityNodeInfosByText("subscriptions").isNotEmpty()
        if (!onList) {
            onList = onFollowingList(getSnap(), h)
        }

        // 1c) Galti se profile / settings / koi aur screen khul gayi: Back dabake list par wapas aao
        if (onList) {
            seenList = true
            offListBacks = 0
            offListSeen = 0
        } else if (seenList) {
            rowQueue.clear()
            // Pehle ek baar dobara dekho (popup band hone ka transition ho sakta hai), phir Back
            if (offListSeen < 1) {
                offListSeen++
                later(t(500L, 250L))
                return
            }
            if (offListBacks < 3) {
                offListBacks++
                report("Galat screen khuli, back kar raha hoon... $done / $target")
                performGlobalAction(GLOBAL_ACTION_BACK)
                later(t(800L, 400L))
                return
            }
            seenList = false // back se bhi list nahi mili, neeche auto-navigation try hoga
        }

        // 1d) Insaan jaisa: har 6-12 unfollow par thoda sa upar-neeche scroll
        if (onList && done >= nextWiggleAt) {
            nextWiggleAt = done + Random.nextInt(6, 13)
            rowQueue.clear()
            val up = swipe(w, h, false, 0.10f + Random.nextFloat() * 0.12f, t(600L, 220L) + Random.nextLong(0L, 250L))
            if (up) {
                report("Halka upar scroll... $done / $target${liveRate()}")
                later(t(900L, 350L))
                return
            }
        }

        // 2) Sabse upar wali row: "Following" button (private) ya "Message" ke saath ⋮ (public)
        //    Rows ek baar padhkar queue mein rakhte hain, har unfollow pe poora tree dobara nahi padhna padta.
        var rowBtn: AccessibilityNodeInfo? = if (onList) nextQueued() else null
        if (onList && rowBtn == null) {
            val sn = getSnap()
            rowQueue.clear()
            rowQueue.addAll(findRows(sn, w, h))
            rowBtn = nextQueued()
        }
        if (rowBtn != null) {
            emptyRounds = 0
            navTaps = 0
            tabFixes = 0
            lastSig = ""
            countedThisRow = false
            rowBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitingConfirm = true
            confirmTries = 0
            later(pollMs)
            return
        }

        // 2b) Galat tab (Followers / Subscriptions / Flagged) khula ho ya koi row button na ho: "following" tab dabao
        if (onList && tabFixes < 4) {
            val sn = getSnap()
            val band = sn.filter { it.r.top > h * 0.1 && it.r.top < h * 0.2 }
            val otherSelected = band.any { a ->
                val l = a.label?.lowercase() ?: ""
                a.node.isSelected && (l.endsWith("followers") || l.contains("subscriptions") || l.contains("flagged"))
            }
            val followingTab = band.firstOrNull { a -> a.label?.endsWith(" following", true) == true }
            val hasRowBtn = sn.any { a ->
                val l = a.label
                l != null && (l.equals("Follow", true) || l.equals("Following", true) || l.equals("Message", true)) &&
                    a.r.left > w * 0.4 && a.r.top > h * 0.2
            }
            if (followingTab != null && (otherSelected || (!hasRowBtn && !followingTab.node.isSelected))) {
                val btn = clickableAncestor(followingTab.node)
                if (btn != null) {
                    tabFixes++
                    rowQueue.clear()
                    report("Following tab par wapas aa raha hoon...")
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    later(t(1000L, 500L))
                    return
                }
            }
        }

        // 3) Auto-navigation: Profile tab -> "following" count -> list (sirf jab list ke andar nahi hain)
        if (navTaps < 4 && !onList) {
            val sn = getSnap()
            val navNode = sn.firstOrNull { a ->
                val l = a.label
                l != null && (l.equals("following", true) || l.endsWith(" following", true)) &&
                    a.r.top > h * 0.1 && a.r.top < h * 0.6
            } ?: sn.firstOrNull { a ->
                a.label.equals("Profile", ignoreCase = true) && a.r.top > h * 0.8
            }
            val btn = navNode?.let { clickableAncestor(it.node) }
            if (btn != null) {
                navTaps++
                report("Following list tak ja raha hoon...")
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                later(2500)
                return
            }
        }

        if (!onList) {
            emptyRounds++
            if (emptyRounds >= 8) {
                stop("Following list nahi mili. Instagram mein Following list khol ke Start dabao. Done: $done")
                return
            }
            report("Following list dhundh raha hoon...")
            later(t(1200L, 500L))
            return
        }

        // 4) Koi unfollow-able row nahi: neeche scroll karo.
        //    Agar pichle scroll se screen nahi badli to agla tareeka try hota hai (gesture -> list ka scroll-down -> baaki).
        val sn = getSnap()
        val sig = signature(sn)
        if (sig == lastSig) emptyRounds++ else emptyRounds = 0
        lastSig = sig
        if (emptyRounds >= 6) {
            stop("List khatam ya scroll nahi ho raha ($scrollInfo). Done: $done")
            return
        }
        report("Scroll kar raha hoon... $done / $target${liveRate()}")
        val dist = 0.32f + Random.nextFloat() * 0.14f
        val dur = t(550L, 180L) + Random.nextLong(0L, 300L)
        if (emptyRounds % 2 == 0) {
            val g = swipe(w, h, true, dist, dur)
            scrollInfo = "gesture=${if (g) "ok" else "NO"}"
            if (!g) scrollInfo += " list=${if (scrollList(sn, h)) "ok" else "NO"}"
        } else {
            val a = scrollList(sn, h)
            scrollInfo = "list=${if (a) "ok" else "NO"}"
            if (!a) scrollInfo += " gesture=${if (swipe(w, h, true, dist, dur)) "ok" else "NO"}"
        }
        later(t(if (emptyRounds == 0) 500L else 1000L, 150L) + Random.nextLong(0L, 200L))
    }

    /**
     * Kya abhi Following list screen khuli hai?
     *  - upar ke tabs mein "subscriptions" / "flagged" dikhe (profile page par ye nahi hote), ya
     *  - neeche kam se kam 2 rows ke "Following"/"Follow"/"Message" buttons dikhein.
     */
    private fun onFollowingList(snap: List<N>, h: Int): Boolean {
        var buttons = 0
        for (a in snap) {
            val l = a.label?.lowercase() ?: continue
            if (a.r.top > h * 0.1 && a.r.top < h * 0.2 && (l.contains("subscriptions") || l.contains("flagged"))) {
                return true
            }
            if (a.r.top > h * 0.2 && (l == "following" || l == "follow" || l == "message")) buttons++
        }
        return buttons >= 2
    }

    private fun nextQueued(): AccessibilityNodeInfo? {
        while (rowQueue.isNotEmpty()) {
            val n = rowQueue.removeAt(0)
            if (n.refresh() && n.isVisibleToUser) return n
        }
        return null
    }

    /** Sirf vertical list par SCROLL_DOWN (tabs wale horizontal pager ko kabhi nahi chhuta). */
    private fun scrollList(sn: List<N>, h: Int): Boolean {
        val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
        val list = sn
            .filter { a ->
                a.node.isScrollable && a.r.top > h * 0.1 && a.node.actionList.any { it.id == down.id }
            }
            .maxByOrNull { it.r.width().toLong() * it.r.height() }
        return list?.node?.performAction(down.id) ?: false
    }

    /**
     * Ungli wala swipe, overlay box se bachakar (warna touch box ko lag jaata hai).
     * contentUp=true: list neeche ki taraf badhti hai (naye rows dikhte hain). false: thoda wapas upar.
     */
    private fun swipe(w: Int, h: Int, contentUp: Boolean, frac: Float, durMs: Long): Boolean {
        val ov = overlayRoot
        val lp = overlayParams
        val box = if (ov != null && lp != null) Rect(lp.x, lp.y, lp.x + ov.width, lp.y + ov.height) else Rect()
        val ys = if (contentUp) listOf(0.82f, 0.88f, 0.76f) else listOf(0.40f, 0.34f, 0.46f)
        var sx = w * 0.5f
        var sy = h * ys[0]
        search@ for (yf in ys) {
            for (xf in listOf(0.5f, 0.15f, 0.85f, 0.3f, 0.7f)) {
                if (!box.contains((w * xf).toInt(), (h * yf).toInt())) {
                    sx = w * xf
                    sy = h * yf
                    break@search
                }
            }
        }
        val ey = if (contentUp) sy - h * frac else sy + h * frac
        val path = android.graphics.Path().apply {
            moveTo(sx, sy)
            lineTo(sx, ey)
        }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durMs))
            .build()
        return dispatchGesture(g, null, null)
    }

    /**
     * Screen par jo bhi unfollow-able rows dikh rahi hain unme se sabse upar wali ka button deta hai:
     *  - "Following" button (private accounts), ya
     *  - "Message" button ke right wala ⋮ (public accounts).
     * Jo rows pehle hi unfollow ho chuki ("Follow" button) wo skip hoti hain.
     */
    private fun findRows(snap: List<N>, w: Int, h: Int): List<AccessibilityNodeInfo> {
        val found = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        for (a in snap) {
            val r = a.r
            if (r.width() <= 0 || r.top <= h * 0.2 || r.bottom >= h * 0.95 || r.left <= w * 0.4) continue
            val label = a.label ?: continue
            if (label.equals("Following", ignoreCase = true)) {
                val btn = clickableAncestor(a.node) ?: continue
                found.add(r.top to btn)
            } else if (label.equals("Message", ignoreCase = true)) {
                val menu = snap
                    .filter { m ->
                        m.node.isClickable && m.node.isVisibleToUser &&
                            m.r.left >= r.right - 4 && m.r.centerY() in r.top..r.bottom && m.r.width() < w * 0.25
                    }
                    .minByOrNull { it.r.left }
                if (menu != null) found.add(r.top to menu.node)
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    private fun signature(snap: List<N>): String =
        snap.mapNotNull { it.label }.filter { it.isNotEmpty() && it.length < 25 }.distinct().take(15).joinToString("|")

    private fun labelsOnScreen(snap: List<N>): String = signature(snap)

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
}
