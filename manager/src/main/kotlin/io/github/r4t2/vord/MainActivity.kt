package io.github.r4t2.vord

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.r4t2.vord.patcher.Features
import java.io.File

/**
 * One screen, laid out like ReVanced Manager's flow: pick the app, choose patches, watch the
 * steps, install. Plain Android views, no extra libraries.
 */
class MainActivity : Activity() {
    private companion object {
        const val PICK_SOURCE = 1
        const val PICK_KEY = 2
        const val BG = 0xFF1E1F22.toInt()
        const val CARD = 0xFF2B2D31.toInt()
        const val TEXT = 0xFFF2F3F5.toInt()
        const val MUTED = 0xFFB5BAC1.toInt()
        const val ACCENT = 0xFF5865F2.toInt()
    }

    private var pickedUri: Uri? = null
    private var pickedName: String? = null
    private val checks = LinkedHashMap<String, CheckBox>()
    private val stepViews = ArrayList<TextView>()
    private lateinit var status: TextView
    private lateinit var source: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var go: Button
    private lateinit var progress: ProgressBar
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            setBackgroundColor(BG)
        }
        root.addView(text("Vord", 28f, TEXT, bold = true))
        root.addView(text("Patch the Discord app on this phone", 14f, MUTED))

        status = card(root)
        refreshStatus()

        root.addView(heading("Discord to patch"))
        source = card(root)
        val pickRow = row()
        pickRow.addView(button("Pick APK / bundle") { pickSource() })
        pickRow.addView(button("Use installed") { pickedUri = null; pickedName = null; refreshSource() })
        root.addView(pickRow)
        refreshSource()

        root.addView(heading("Patches"))
        for (f in Features.all) {
            val box = CheckBox(this).apply {
                isChecked = f.defaultOn
                setTextColor(TEXT)
                text = f.name
                textSize = 15f
            }
            checks[f.id] = box
            root.addView(box)
            root.addView(text(f.description, 12f, MUTED).apply { setPadding(dp(32), 0, 0, dp(8)) })
        }

        root.addView(heading("Signing key"))
        root.addView(text("Updates keep Discord's data only if the same key signs every build.", 12f, MUTED))
        root.addView(row().apply { addView(button("Import key (.p12)") { pickKey() }) })

        go = button("Patch and install") { start() }.apply { setBackgroundColor(ACCENT) }
        root.addView(go)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)
        PatchRunner.STEPS.forEach { name ->
            val v = text("○  $name", 14f, MUTED)
            stepViews.add(v)
            root.addView(v)
        }
        log = text("", 11f, MUTED).apply { typeface = Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(-1, dp(220)).also { it.topMargin = dp(12) }
            setBackgroundColor(CARD)
        }
        root.addView(logScroll)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(BG); isFillViewport = true })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val d = Sources.installed(this, Sources.DISCORD)
        val p = Sources.installed(this, Sources.PATCHED)
        status.text = buildString {
            append("Discord: ").append(d?.let { "${it.version} (${it.paths.size} APKs)" } ?: "not installed").append('\n')
            append("Patched Discord: ").append(p?.version ?: "not installed").append('\n')
            append("Signing key: ").append(if (Keys.exists(this@MainActivity)) "present" else "created on first patch")
        }
    }

    private fun refreshSource() {
        val d = Sources.installed(this, Sources.DISCORD)
        val name = pickedName
        source.text = when {
            name != null -> "File: $name"
            d != null -> "Installed Discord ${d.version}" +
                if (d.version.startsWith(Sources.TESTED_VERSION)) "" else "\nOnly ${Sources.TESTED_VERSION} is tested, other versions may break."
            else -> "No Discord installed. Pick an APK or bundle."
        }
    }

    private fun pickSource() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_SOURCE)
    }

    private fun pickKey() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_KEY)
    }

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
                runOnUiThread { append(err ?: "Signing key imported"); refreshStatus() }
            }.start()
        }
    }

    private fun start() {
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            append("Allow \"Install unknown apps\" for Vord, then tap again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val enabled = checks.filter { it.value.isChecked }.keys.toSet()
        busy = true
        go.isEnabled = false
        progress.visibility = View.VISIBLE
        log.text = ""
        stepViews.forEachIndexed { i, v -> v.text = "○  ${PatchRunner.STEPS[i]}"; v.setTextColor(MUTED) }
        Thread {
            try {
                val staging = File(cacheDir, "vord/in").also { it.deleteRecursively() }
                val uri = pickedUri
                val inputs = if (uri != null) Sources.stagePicked(this, uri, staging) { post { append(it) } }
                else Sources.installed(this, Sources.DISCORD)?.let { Sources.stageInstalled(it, staging) }
                    ?: error("No Discord installed and no file picked")
                val outputs = PatchRunner(this).run(inputs, enabled, listener())
                runOnUiThread { stepState(4, PatchRunner.State.RUNNING, "confirm on the install screen") }
                Installer.listener = { ok, msg ->
                    runOnUiThread {
                        stepState(4, if (ok) PatchRunner.State.DONE else PatchRunner.State.FAILED, msg)
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
        busy = false
        go.isEnabled = true
        progress.visibility = View.GONE
        refreshStatus()
        if (ok) File(cacheDir, "vord").deleteRecursively()
    }

    private fun listener() = object : PatchRunner.Listener {
        override fun step(index: Int, state: PatchRunner.State, note: String) =
            runOnUiThread { stepState(index, state, note) }

        override fun log(line: String) = runOnUiThread { append(line) }
    }

    private fun stepState(i: Int, s: PatchRunner.State, note: String) {
        val (mark, color) = when (s) {
            PatchRunner.State.PENDING -> "○" to MUTED
            PatchRunner.State.RUNNING -> "…" to TEXT
            PatchRunner.State.DONE -> "✓" to 0xFF3BA55D.toInt()
            PatchRunner.State.FAILED -> "✗" to 0xFFED4245.toInt()
        }
        stepViews[i].text = "$mark  ${PatchRunner.STEPS[i]}" + if (note.isNotEmpty()) "  ($note)" else ""
        stepViews[i].setTextColor(color)
    }

    private fun append(line: String) {
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun post(r: () -> Unit) = runOnUiThread(r)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun heading(s: String) = text(s, 16f, TEXT, bold = true).apply { setPadding(0, dp(20), 0, dp(6)) }

    private fun card(parent: LinearLayout) = text("", 14f, TEXT).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(CARD)
        layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) }
        parent.addView(this)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(TEXT); isAllCaps = false
        setBackgroundColor(0xFF4E5058.toInt())
        layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(8); it.topMargin = dp(8) }
        setOnClickListener { onClick() }
    }
}
