package com.voltfetch.app

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.voltfetch.engine.DAY_SHORT
import com.voltfetch.engine.Fmt
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.Queue
import com.voltfetch.engine.Schedule
import com.voltfetch.engine.Status
import com.voltfetch.engine.hm
import com.voltfetch.engine.minutesOf

/** Edit a queue: name, how many at once, timetable, and the order of its waiting files. */
class QueueActivity : Screen() {
    private var original: String? = null          // null = creating a new queue
    private val isMain get() = original == MAIN_QUEUE
    private var name = ""
    private var max = 2
    private var sched = Schedule()
    private lateinit var orderBox: LinearLayout
    private var orderSig = ""
    private val handler = Handler(Looper.getMainLooper())

    override val screenTitle: String get() = intent.getStringExtra("name") ?: "New queue"
    override val wantsBottomBar get() = !isMainQueueIntent()
    private fun isMainQueueIntent() = intent.getStringExtra("name") == MAIN_QUEUE

    override fun content() {
        original = intent.getStringExtra("name")
        val q = original?.let { engine.settings.queue(it) }
        if (original != null && !isMain && q == null) { finish(); return }
        name = original ?: suggestName()
        max = if (isMain) engine.settings.simultaneous else q?.maxConcurrent ?: 2
        sched = q?.schedule ?: Schedule()

        if (isMain) {
            body.addView(k.text("Main starts downloads as soon as you add them. Use another queue for files you want to fetch later or on a timetable.", 13.5f, p.mute).apply { setLineSpacing(0f, 1.25f); setPadding(dp(4), 0, dp(4), 0) })
            section("Main queue", stepperRow("Downloads at once", "Others wait their turn.", max, 1, 8) { max = it; engine.updateSettings(engine.settings.copy(simultaneous = it)) })
        } else {
            body.addView(k.sectionTitle("Name"))
            val nameIn = k.input("Queue name", name).apply {
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { name = s?.toString()?.trim() ?: "" }
                })
            }
            body.addView(nameIn)
            section("Running", stepperRow("Downloads at once", "How many files in this queue download together.", max, 1, 6) { max = it })
            buildSchedule()
        }

        body.addView(k.sectionTitle("Order"))
        body.addView(k.text("Waiting files start from the top. Use the arrows to change the order.", 12.5f, p.mute).apply { setPadding(dp(4), 0, 0, dp(10)) })
        orderBox = k.vbox(gap = dp(8))
        body.addView(orderBox)
        if (original != null) body.addView(k.button("Add files to this queue", "ghost", R.drawable.ic_plus) { addFiles() }, lp(-1, dp(50)).apply { topMargin = dp(10) })
        renderOrder()

        bottomBar?.let { bb ->
            bb.removeAllViews()
            if (original != null && !isMain) bb.addView(k.button("Delete", "danger", R.drawable.ic_trash) { confirmDelete() }, lp(0, dp(52), 1f))
            bb.addView(k.button(if (original == null) "Create queue" else "Save", "primary", R.drawable.ic_check) { save() }, lp(0, dp(52), 1.6f))
        }
    }

    private fun suggestName(): String {
        val taken = engine.settings.queueNames().toSet()
        for (n in listOf("Night", "Later", "Wi-Fi at home", "Weekend")) if (n !in taken) return n
        var i = 2; while ("Queue $i" in taken) i++; return "Queue $i"
    }

    private lateinit var schedBox: LinearLayout

    private fun buildSchedule() {
        body.addView(k.sectionTitle("Timetable"))
        schedBox = k.vbox()
        body.addView(k.card(schedBox, dp(2)))
        renderSchedule()
    }

    private fun renderSchedule() {
        schedBox.removeAllViews()
        schedBox.addView(toggleRow("Start automatically", "Starts the queue at a set time. Your phone can be asleep.", sched.enabled) { sched = sched.copy(enabled = it); renderSchedule() })
        if (!sched.enabled) return
        schedBox.addView(k.divider())
        schedBox.addView(timeRow("Start at", sched.start) { sched = sched.copy(start = it); renderSchedule() })
        val days = k.hbox(dp(5)).apply { setPadding(dp(14), dp(4), dp(14), dp(14)) }
        val picks = sched.days.toMutableList()
        for (d in 0..6) days.addView(k.chip(DAY_SHORT[d].take(2), picks[d]) { picks[d] = !picks[d]; sched = sched.copy(days = picks.toList()); renderSchedule() }.apply { setPadding(0, 0, 0, 0) }, lp(0, dp(38), 1f))
        schedBox.addView(days)
        schedBox.addView(k.divider())
        schedBox.addView(toggleRow("Stop automatically", "Pauses whatever is still downloading at a set time.", sched.stopEnabled) { sched = sched.copy(stopEnabled = it); renderSchedule() })
        if (sched.stopEnabled) schedBox.addView(timeRow("Stop at", sched.stop) { sched = sched.copy(stop = it); renderSchedule() })
        schedBox.addView(k.text(sched.describe(), 12.5f, p.tide).apply { setPadding(dp(14), dp(4), dp(14), dp(14)) })
    }

    private fun timeRow(label: String, value: String, onPick: (String) -> Unit): View {
        val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
        row.addView(k.text(label, 15f, p.fg, Type.medium), lp(0, -2, 1f))
        val btn = k.button(value, "tonal", R.drawable.ic_clock) {
            val m = minutesOf(value).coerceAtLeast(0)
            TimePickerDialog(this, { _, h, mi -> onPick(hm(h * 60 + mi)) }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
        }.apply { textSize = 17f; typeface = Type.black }
        row.addView(btn, LinearLayout.LayoutParams(-2, dp(46)))
        return row
    }

    private val tick = object : Runnable { override fun run() { renderOrder(); handler.postDelayed(this, 1000) } }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun renderOrder() {
        if (!::orderBox.isInitialized) return
        val qn = original
        val files = if (qn == null) emptyList() else engine.views().filter { it.queue == qn && it.status != Status.COMPLETE }
            .sortedWith(Comparator { a, b -> if (a.active != b.active) (if (a.active) -1 else 1) else if (a.queuePos != b.queuePos) a.queuePos.compareTo(b.queuePos) else a.id.compareTo(b.id) })
        val sig = files.joinToString { "${it.id}${it.status}${it.queuePos}${it.percent.toInt()}" } + p.dark
        if (sig == orderSig) return
        orderSig = sig
        orderBox.removeAllViews()
        if (files.isEmpty()) {
            orderBox.addView(k.card(k.text(if (qn == null) "Create the queue, then add files to it from their details or here." else "Nothing waiting in this queue.", 13.5f, p.mute).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }))
            return
        }
        files.forEachIndexed { i, v ->
            val row = k.hbox(dp(10)).apply { setPadding(dp(12), dp(10), dp(4), dp(10)) }
            val num = k.text(if (v.active) "▶" else "${i + 1}", 14f, if (v.active) p.tide else p.mute, Type.black).apply { gravity = Gravity.CENTER; minWidth = dp(22) }
            row.addView(num)
            row.addView(k.tile(v.category, 36))
            val tx = k.vbox(gap = dp(2))
            tx.addView(k.text(v.name, 14f, p.fg, Type.medium, lines = 1))
            tx.addView(k.text(listOf(v.status.label(), if (v.size > 0) "${v.percent.toInt()}% of ${Fmt.size(v.size)}" else "").filter { it.isNotEmpty() }.joinToString(" · "), 12f, p.mute, lines = 1))
            row.addView(tx, lp(0, -2, 1f))
            if (!v.active) {
                row.addView(k.iconButton(R.drawable.ic_up, "Move up") { engine.move(v.id, "up"); orderSig = ""; renderOrder() }.apply { alpha = if (i == 0) .3f else 1f }, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(k.iconButton(R.drawable.ic_more, "More") { orderMenu(v.id) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            orderBox.addView(k.card(row, 0))
        }
    }

    private fun orderMenu(id: Long) {
        val items = arrayOf("Move to the top", "Move down", "Move to the bottom", "Move to another queue")
        dialog().setItems(items) { _, w ->
            when (w) {
                0 -> engine.move(id, "top"); 1 -> engine.move(id, "down"); 2 -> engine.move(id, "bottom")
                3 -> {
                    val names = engine.settings.queueNames().filter { it != original }
                    dialog().setTitle("Move to").setItems(names.toTypedArray()) { _, i -> engine.moveToQueue(listOf(id), names[i]) }.show()
                }
            }
            orderSig = ""; renderOrder()
        }.show()
    }

    private fun addFiles() {
        val qn = original ?: return
        val cands = engine.views().filter { it.queue != qn && it.status != Status.COMPLETE && !it.active }
        if (cands.isEmpty()) { toast("No other unfinished files to add"); return }
        val checked = BooleanArray(cands.size)
        dialog().setTitle("Add to $qn").setMultiChoiceItems(cands.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Add") { _, _ -> engine.moveToQueue(cands.filterIndexed { i, _ -> checked[i] }.map { it.id }, qn); orderSig = ""; renderOrder() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun save() {
        val n = name.trim()
        if (n.isEmpty()) { toast("Give the queue a name"); return }
        if (n.equals(MAIN_QUEUE, true) || (n != original && n in engine.settings.queueNames())) { toast("There's already a queue called $n"); return }
        if (sched.enabled && sched.days.none { it }) { toast("Pick at least one day, or turn off automatic start"); return }
        engine.saveQueue(original, Queue(n, max, sched))
        toast(if (original == null) "Queue created" else "Saved")
        if (original == null) {
            // reopen as the saved queue so files can be added straight away
            startActivity(Intent(this, QueueActivity::class.java).putExtra("name", n))
        }
        finish()
    }

    private fun confirmDelete() {
        val qn = original ?: return
        val count = engine.views().count { it.queue == qn }
        dialog().setTitle("Delete the queue “$qn”?")
            .setMessage(if (count > 0) "Its $count file(s) move to Main. Nothing is deleted from your phone." else "Nothing else changes.")
            .setPositiveButton("Delete queue") { _, _ -> engine.deleteQueue(qn); finish() }
            .setNegativeButton("Cancel", null).show()
    }
}
