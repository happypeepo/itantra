package org.itantra.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as M

/**
 * Live captions, so a deaf or hard-of-hearing user can read every message: what they said (while
 * and after speech recognition runs), what arrives (shown before it is spoken, with its playback
 * progress) and alerts. [panel] is the transcript inside the Conversation card; [bar] is pinned
 * to the bottom of the screen and always shows the latest message, whatever is scrolled into view.
 * Every public call is posted to the main thread, so it is safe from the audio/inference threads.
 */
class Conversation(private val context: Context, private val dp: (Int) -> Int) {
    enum class Kind { SENT, HEARD, ALERT }

    private val main = Handler(Looper.getMainLooper())
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = text(M.style.TextAppearance_Material3_BodyMedium).apply {
        text = "No messages yet. Everything you say and hear appears here as text."
    }
    val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(empty); addView(list) }
    var onBarClick: () -> Unit = {}

    private val barMeta = text(M.style.TextAppearance_Material3_LabelLarge)
    // Long Indic sentences need ~4 lines at this size; a cut-off caption would hide words from a deaf reader.
    private val barText = text(M.style.TextAppearance_Material3_TitleMedium).apply { maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
    val bar = MaterialCardView(context).apply {
        radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = 0; visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(14))
            addView(barMeta); addView(barText)
        })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) }
        setOnClickListener { onBarClick() }
    }
    private var latest: Entry? = null

    inner class Entry internal constructor(val kind: Kind, private val who: String, private var text: String, private var status: String) {
        private val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        internal lateinit var card: MaterialCardView
        private lateinit var meta: TextView
        private lateinit var body: TextView

        fun update(text: String? = null, status: String? = null) {
            main.post { text?.let { this.text = it }; status?.let { this.status = it }; render() }
        }
        fun remove() {
            main.post {
                list.removeView(card)
                if (latest === this) { latest = null; bar.visibility = View.GONE }
                empty.visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
            }
        }
        internal fun build() {
            val (bg, fg) = colors(kind)
            meta = text(M.style.TextAppearance_Material3_LabelMedium).apply { setTextColor(fg); alpha = 0.8f }
            body = text(M.style.TextAppearance_Material3_BodyLarge).apply { setTextColor(fg); setTextIsSelectable(true) }
            card = MaterialCardView(context).apply {
                radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(bg)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
                    addView(meta); addView(body)
                })
            }
            // Chat layout: your messages on the end side, received ones on the start side, alerts full width.
            list.addView(card, LinearLayout.LayoutParams(if (kind == Kind.ALERT) -1 else -2, -2).apply {
                topMargin = dp(8)
                gravity = if (kind == Kind.SENT) Gravity.END else Gravity.START
                if (kind == Kind.SENT) marginStart = dp(40) else if (kind == Kind.HEARD) marginEnd = dp(40)
            })
            while (list.childCount > MAX) list.removeViewAt(0)
            empty.visibility = View.GONE
            latest = this
            render()
        }
        private fun render() {
            meta.text = "$who · $time · $status"
            body.text = text
            card.contentDescription = "$who: $text. $status"
            if (latest === this) {
                val (bg, fg) = colors(kind)
                bar.setCardBackgroundColor(bg); barMeta.setTextColor(fg); barText.setTextColor(fg)
                barMeta.text = "$who · $status"; barText.text = text
                bar.accessibilityLiveRegion = if (kind == Kind.ALERT) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else View.ACCESSIBILITY_LIVE_REGION_POLITE
                bar.visibility = View.VISIBLE
            }
        }
    }

    /** Adds a message and makes it the one shown in the caption bar. */
    fun add(kind: Kind, who: String, text: String, status: String): Entry =
        Entry(kind, who, text, status).also { e -> main.post { e.build() } }

    private fun colors(kind: Kind): Pair<Int, Int> {
        fun c(attr: Int) = MaterialColors.getColor(bar, attr)
        return when (kind) {
            Kind.SENT -> c(M.attr.colorPrimaryContainer) to c(M.attr.colorOnPrimaryContainer)
            Kind.HEARD -> c(M.attr.colorSurfaceContainerHighest) to c(M.attr.colorOnSurface)
            Kind.ALERT -> c(M.attr.colorErrorContainer) to c(M.attr.colorOnErrorContainer)
        }
    }

    private fun text(appearance: Int) = MaterialTextView(context).apply { setTextAppearance(appearance) }

    private companion object { const val MAX = 50 }
}
