package com.example.admuteforspotify

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Multi-type RecyclerView adapter that renders the muted-ad history.
 *
 * Item types:
 *  - [TYPE_HEADER] : a String section label (TODAY, YESTERDAY, …)
 *  - [TYPE_EVENT]  : a [MuteEvent] row with time + duration
 *
 * Items are pre-grouped by [buildDisplayList] before being passed here.
 * Each event item animates in with a slide-from-right + fade, staggered per position.
 */
class MuteHistoryAdapter(events: List<MuteEvent>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items: List<Any> = buildDisplayList(events)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EVENT  = 1
        private const val STAGGER_MS  = 40L
        private const val ANIM_DURATION_MS = 280L
        private const val SLIDE_OFFSET_PX  = 80f

        /**
         * Groups [MuteEvent] items into dated sections and returns a flat list of
         * alternating [String] headers and [MuteEvent] rows.
         */
        fun buildDisplayList(events: List<MuteEvent>): List<Any> {
            if (events.isEmpty()) return emptyList()

            val now      = Calendar.getInstance()
            val today    = now.clone() as Calendar
            val yesterday = now.clone() as Calendar
            yesterday.add(Calendar.DAY_OF_YEAR, -1)
            val weekAgo  = now.clone() as Calendar
            weekAgo.add(Calendar.DAY_OF_YEAR, -7)

            fun isSameDay(cal: Calendar, ts: Long): Boolean {
                val c = Calendar.getInstance().apply { timeInMillis = ts }
                return cal.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
                        cal.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
            }

            val buckets = linkedMapOf(
                "TODAY"       to mutableListOf<MuteEvent>(),
                "YESTERDAY"   to mutableListOf(),
                "LAST 7 DAYS" to mutableListOf(),
                "OLDER"       to mutableListOf()
            )

            for (event in events) {
                when {
                    isSameDay(today, event.timestampMs)     -> buckets["TODAY"]!!.add(event)
                    isSameDay(yesterday, event.timestampMs) -> buckets["YESTERDAY"]!!.add(event)
                    event.timestampMs >= weekAgo.timeInMillis -> buckets["LAST 7 DAYS"]!!.add(event)
                    else                                    -> buckets["OLDER"]!!.add(event)
                }
            }

            return buildList {
                buckets.forEach { (label, list) ->
                    if (list.isNotEmpty()) {
                        add(label)
                        addAll(list)
                    }
                }
            }
        }
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view as TextView
    }

    class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView     = view.findViewById(R.id.tvTime)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
    }

    // ── Adapter overrides ─────────────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int =
        if (items[position] is String) TYPE_HEADER else TYPE_EVENT

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_mute_event, parent, false)
            EventViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                holder.tvHeader.text = items[position] as String
                // Headers don't animate
                holder.itemView.alpha = 1f
                holder.itemView.translationX = 0f
            }
            is EventViewHolder -> {
                val event = items[position] as MuteEvent
                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                holder.tvTime.text = timeFmt.format(Date(event.timestampMs))
                holder.tvDuration.text = formatDuration(event.durationMs)
                animateItemSlideIn(holder.itemView, position)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000L               -> "< 1s"
            ms < TimeUnit.MINUTES.toMillis(1) -> "~${ms / 1000}s"
            else -> {
                val mins = TimeUnit.MILLISECONDS.toMinutes(ms)
                val secs = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
                "~${mins}m ${secs}s"
            }
        }
    }

    private fun animateItemSlideIn(view: View, position: Int) {
        view.alpha = 0f
        view.translationX = SLIDE_OFFSET_PX
        // Skip headers (position 0 is usually a header); stagger by position
        val delay = position * STAGGER_MS
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(delay)
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
