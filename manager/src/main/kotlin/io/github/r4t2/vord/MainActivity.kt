package io.github.r4t2.vord

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.r4t2.vord.patcher.Features
import java.io.File

/**
 * One screen, laid out like ReVanced Manager's flow but drawn by hand: what to patch, which
 * patches, the signing key, then live progress. The action button stays pinned at the bottom.
 */
class MainActivity : Activity() {
    private companion object {
        const val PICK_SOURCE = 1
        const val PICK_KEY = 2

        const val BG = 0xFF16171A.toInt()
        const val CARD = 0xFF1F2024.toInt()
        const val RAISED = 0xFF2B2D31.toInt()
        const val LINE = 0xFF34363C.toInt()
        const val TEXT = 0xFFF2F3F5.toInt()
        const val MUTED = 0xFF9AA0A8.toInt()
        const val ACCENT = 0xFF5865F2.toInt()
        const val GREEN = 0xFF3BA55D.toInt()
        const val AMBER = 0xFFFAA61A.toInt()
        const val RED = 0xFFED4245.toInt()
    }

    private class StepRow(val badge: TextView, val label: TextView, val note: TextView)

    private var pickedUri: Uri? = null
    private var pickedName: String? = null
    private val switches = LinkedHashMap<String, Switch>()
    private val steps = ArrayList<StepRow>()
    private var busy = false

    private lateinit var chipDiscord: TextView
    private lateinit var chipPatched: TextView
    private lateinit var chipKey: TextView
    private lateinit var sourceTitle: TextView
    private lateinit var sourceNote: TextView
    private lateinit var keyText: TextView
    private lateinit var progressCard: LinearLayout
    private lateinit var log: TextView
    private lateinit var logToggle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var action: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // --- header -------------------------------------------------------------------------
        page.addView(label("Vord", 30f, TEXT, bold = true).apply { setPadding(dp(4), dp(8), 0, 0) })
        page.addView(label("Patch Discord on this phone", 14f, MUTED).apply { setPadding(dp(4), 0, 0, dp(12)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipDiscord = chip(); chipPatched = chip(); chipKey = chip()
        chips.addView(chipDiscord); chips.addView(chipPatched); chips.addView(chipKey)
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        // --- 1 Discord ----------------------------------------------------------------------
        page.addView(sectionTitle("1", "Discord to patch"))
        val sourceCard = card()
        sourceTitle = label("", 17f, TEXT, bold = true)
        sourceNote = label("", 13f, MUTED).apply { setPadding(0, dp(2), 0, 0) }
        sourceCard.addView(sourceTitle)
        sourceCard.addView(sourceNote)
        val sourceButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        sourceButtons.addView(pill("Use installed") { pickedUri = null; pickedName = null; refreshSource() })
        sourceButtons.addView(spacer(dp(10)))
        sourceButtons.addView(pill("Pick APK or bundle") { startActivityForResult(openDocument(), PICK_SOURCE) })
        sourceCard.addView(sourceButtons)
        page.addView(sourceCard)

        // --- 2 Patches ----------------------------------------------------------------------
        page.addView(sectionTitle("2", "Patches"))
        val patchCard = card(padV = 4)
        Features.all.forEachIndexed { i, f ->
            if (i > 0) patchCard.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(12) }
            }
            texts.addView(label(f.name, 15f, TEXT, bold = true))
            texts.addView(label(f.description, 12.5f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
            val sw = Switch(this).apply {
                isChecked = f.defaultOn
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB5BAC1.toInt()))
                trackTintList = ColorStateList(states, intArrayOf(ACCENT, 0xFF4E5058.toInt()))
            }
            switches[f.id] = sw
            row.addView(texts)
            row.addView(sw)
            row.setOnClickListener { if (!busy) sw.toggle() }
            patchCard.addView(row)
        }
        page.addView(patchCard)

        // --- progress (shown once a run starts) -----------------------------------------------
        progressCard = card().apply { visibility = View.GONE }
        PatchRunner.STEPS.forEachIndexed { i, name ->
            val badge = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 12f; setTextColor(TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            val text = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(12) }
            }
            val title = label(name, 15f, TEXT)
            val note = label("", 12f, MUTED)
            text.addView(title); text.addView(note)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(badge); row.addView(text)
            progressCard.addView(row)
            if (i < PatchRunner.STEPS.size - 1) {
                progressCard.addView(View(this).apply {
                    setBackgroundColor(LINE)
                    layoutParams = LinearLayout.LayoutParams(dp(2), dp(14)).also { it.marginStart = dp(11) }
                })
            }
            steps.add(StepRow(badge, title, note))
            setStep(i, PatchRunner.State.PENDING, "")
        }
        logToggle = label("Show details", 13f, ACCENT, bold = true).apply {
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener {
                val show = log.visibility != View.VISIBLE
                log.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide details" else "Show details"
            }
        }
        log = label("", 11f, MUTED).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            background = rounded(BG, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        progressCard.addView(logToggle)
        progressCard.addView(log)
        page.addView(progressCard)

        // --- 3 Signing key (advanced) ----------------------------------------------------------
        page.addView(sectionTitle("3", "Signing key"))
        val keyCard = card()
        keyText = label("", 14f, TEXT)
        keyCard.addView(keyText)
        keyCard.addView(label(
            "An update keeps Discord's data only if the same key signs every build. " +
                "Import the key your patched Discord was installed with.", 12.5f, MUTED,
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        keyCard.addView(pill("Import key (.p12)") { startActivityForResult(openDocument(), PICK_KEY) })
        page.addView(keyCard)
        page.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) })

        scroll = ScrollView(this).apply {
            addView(page)
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // --- pinned action bar -----------------------------------------------------------------
        action = TextView(this).apply {
            text = "Patch and install"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(ACCENT, 28)
            setOnClickListener { start() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(action)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            addView(scroll)
            addView(bar)
            // Android 15+ draws edge to edge: keep content clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top; bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                v.setPadding(0, top, 0, bottom)
                insets
            }
        }
        setContentView(root)
        refreshStatus()
        refreshSource()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------------------------------
    // state

    private fun refreshStatus() {
        val d = Sources.installed(this, Sources.DISCORD)
        val p = Sources.installed(this, Sources.PATCHED)
        setChip(
            chipDiscord,
            d?.let { "Discord ${it.version.substringBefore(' ')}" } ?: "Discord missing",
            when {
                d == null -> RED
                d.version.startsWith(Sources.TESTED_VERSION) -> GREEN
                else -> AMBER
            },
        )
        setChip(chipPatched, p?.let { "Patched ${it.version.substringBefore(' ')}" } ?: "Not patched yet", if (p != null) GREEN else MUTED)
        val keyOk = Keys.exists(this)
        setChip(chipKey, if (keyOk) "Key ready" else "Key: new", if (keyOk) GREEN else AMBER)
        keyText.text = if (keyOk) "Key in use: ${Keys.fingerprintOf(this)}" else "A new key is created on the first patch"
    }

    private fun refreshSource() {
        val d = Sources.installed(this, Sources.DISCORD)
        val name = pickedName
        when {
            name != null -> {
                sourceTitle.text = name
                sourceNote.text = "Picked file. Bundles are trimmed to this phone's architecture, screen and language."
            }
            d != null -> {
                sourceTitle.text = "Installed Discord ${d.version.substringBefore(' ')}"
                sourceNote.text = if (d.version.startsWith(Sources.TESTED_VERSION)) "Tested version, ${d.paths.size} APKs on this phone"
                else "Only ${Sources.TESTED_VERSION} is tested, this version may break"
            }
            else -> {
                sourceTitle.text = "No Discord found"
                sourceNote.text = "Install Discord, or pick an APK or bundle file"
            }
        }
    }

    private fun setStep(i: Int, s: PatchRunner.State, note: String) {
        val row = steps[i]
        val (glyph, fill) = when (s) {
            PatchRunner.State.PENDING -> "" to null
            PatchRunner.State.RUNNING -> "•" to ACCENT
            PatchRunner.State.DONE -> "✓" to GREEN
            PatchRunner.State.FAILED -> "✗" to RED
        }
        row.badge.text = glyph
        row.badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (fill != null) setColor(fill) else { setColor(0); setStroke(dp(2), LINE) }
        }
        row.label.setTextColor(if (s == PatchRunner.State.PENDING) MUTED else TEXT)
        row.note.text = note
        row.note.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        row.note.setTextColor(if (s == PatchRunner.State.FAILED) RED else MUTED)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        action.text = if (b) "Working…" else "Patch and install"
        action.background = rounded(if (b) 0xFF4E5058.toInt() else ACCENT, 28)
        action.isEnabled = !b
    }

    // ---------------------------------------------------------------------------------------
    // actions

    private fun openDocument() =
        Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_SOURCE -> {
                pickedUri = uri
                pickedName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                } ?: uri.lastPathSegment
                refreshSource()
            }
            PICK_KEY -> Thread {
                val err = Keys.import(this, uri)
                runOnUiThread {
                    progressCard.visibility = View.VISIBLE
                    append(err ?: "Signing key imported")
                    refreshStatus()
                }
            }.start()
        }
    }

    private fun start() {
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            progressCard.visibility = View.VISIBLE
            append("Allow \"Install unknown apps\" for Vord, then tap again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val enabled = switches.filter { it.value.isChecked }.keys.toSet()
        setBusy(true)
        progressCard.visibility = View.VISIBLE
        log.text = ""
        steps.indices.forEach { setStep(it, PatchRunner.State.PENDING, "") }
        scroll.post { scroll.smoothScrollTo(0, progressCard.top - dp(16)) }
        Thread {
            try {
                val staging = File(cacheDir, "vord/in").also { it.deleteRecursively() }
                val uri = pickedUri
                val inputs = if (uri != null) Sources.stagePicked(this, uri, staging) { line -> runOnUiThread { append(line) } }
                else Sources.installed(this, Sources.DISCORD)?.let { Sources.stageInstalled(it, staging) }
                    ?: error("No Discord installed and no file picked")
                val outputs = PatchRunner(this).run(inputs, enabled, listener())
                runOnUiThread { setStep(4, PatchRunner.State.RUNNING, "confirm on the install screen") }
                Installer.listener = { ok, msg ->
                    runOnUiThread {
                        setStep(4, if (ok) PatchRunner.State.DONE else PatchRunner.State.FAILED, msg)
                        append(msg)
                        finish(ok)
                    }
                }
                Installer.install(this, outputs)
            } catch (t: Throwable) {
                runOnUiThread { finish(false) }
            }
        }.start()
    }

    private fun finish(ok: Boolean) {
        setBusy(false)
        refreshStatus()
        if (ok) File(cacheDir, "vord").deleteRecursively()
    }

    private fun listener() = object : PatchRunner.Listener {
        override fun step(index: Int, state: PatchRunner.State, note: String) =
            runOnUiThread { setStep(index, state, note) }

        override fun log(line: String) = runOnUiThread { append(line) }
    }

    private fun append(line: String) {
        log.append(line + "\n")
    }

    // ---------------------------------------------------------------------------------------
    // drawing helpers

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionTitle(number: String, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(22), 0, dp(8))
        addView(label(number, 12f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(RAISED, 11)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginEnd = dp(10) }
        })
        addView(label(title, 16f, TEXT, bold = true))
    }

    private fun card(padV: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16)
        setPadding(dp(16), dp(padV), dp(16), dp(padV))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun spacer(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }

    private fun chip() = TextView(this).apply {
        textSize = 12f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(8) }
    }

    private fun setChip(c: TextView, text: String, accent: Int) {
        c.text = text
        c.setTextColor(accent)
        c.background = rounded(RAISED, 14)
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(0, 20, stroke = LINE)
        setOnClickListener { onClick() }
    }
}
