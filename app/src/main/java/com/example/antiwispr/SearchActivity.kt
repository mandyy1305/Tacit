package com.example.antiwispr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Simple search over cached transcripts (case-insensitive substring, newest first). Programmatic UI
 * matching MainActivity's idiom. Tapping a result copies the full transcript to the clipboard.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "Search voice-note transcripts (${Transcripts.get(this@SearchActivity).count()} stored)"
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        val box = EditText(this).apply {
            hint = "type a word or phrase…"
            setSingleLine(true)
        }
        root.addView(box)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(results)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        box.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        setContentView(root)
        render("")
    }

    private fun render(query: String) {
        results.removeAllViews()
        if (query.trim().length < 2) {
            results.addView(hintRow("Type at least 2 characters."))
            return
        }
        val hits = Transcripts.get(this).search(query)
        if (hits.isEmpty()) { results.addView(hintRow("No matches.")); return }
        results.addView(hintRow("${hits.size} match(es):"))
        for (h in hits.take(100)) {
            val when_ = DateUtils.getRelativeTimeSpanString(h.updatedAt).toString()
            val snippet = snippet(h.text, query)
            results.addView(TextView(this).apply {
                text = "• ${h.name}  ($when_)\n$snippet"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("transcript", h.text))
                    Toast.makeText(this@SearchActivity, "Transcript copied", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun snippet(text: String, query: String): String {
        val i = text.indexOf(query, ignoreCase = true)
        if (i < 0) return text.take(140)
        val start = (i - 40).coerceAtLeast(0)
        val end = (i + query.length + 80).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
    }

    private fun hintRow(t: String) = TextView(this).apply {
        text = t; setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
