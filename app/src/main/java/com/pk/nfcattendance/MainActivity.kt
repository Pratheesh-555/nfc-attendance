package com.pk.nfcattendance

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var urlInput: EditText
    private lateinit var sheetSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var manualInput: EditText

    private var marked = 0
    // Pull exactly 9 digits, never a slice of a longer number.
    private val reg9: Pattern = Pattern.compile("(?<!\\d)\\d{9}(?!\\d)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        urlInput = findViewById(R.id.urlInput)
        sheetSpinner = findViewById(R.id.sheetSpinner)
        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        manualInput = findViewById(R.id.manualInput)

        urlInput.setText(prefs.getString("url", ""))

        findViewById<Button>(R.id.loadBtn).setOnClickListener { loadSheets() }
        findViewById<Button>(R.id.manualBtn).setOnClickListener {
            val v = manualInput.text.toString().trim()
            if (v.length == 9 && v.all { it.isDigit() }) {
                manualInput.setText("")
                mark(v)
            } else {
                status("Enter exactly 9 digits", Kind.BAD)
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) status("This phone has no NFC chip.", Kind.BAD)

        // If a sheet list was saved before, restore it so scanning can resume.
        val saved = prefs.getString("tabs", null)
        if (saved != null) setSpinner(saved.split("\n").filter { it.isNotBlank() })
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            status("NFC is OFF. Turn it on in quick settings, then come back.", Kind.WARN)
        } else if (prefs.getString("url", "").isNullOrBlank()) {
            status("Enter the URL and load your sheets to begin.", Kind.NEUTRAL)
        }
        // Reader mode reads NDEF without needing any card keys.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(this, { tag -> onTag(tag) }, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // ---------- NFC ----------

    private fun onTag(tag: Tag) {
        val reg = readReg(tag)
        if (reg == null) {
            runOnUiThread {
                status("Card read, but no 9-digit reg number is stored on it.\nType it in below.", Kind.WARN)
                buzz(longArrayOf(0, 80, 60, 80))
            }
            return
        }
        mark(reg)
    }

    private fun readReg(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val msg = ndef.cachedNdefMessage ?: ndef.ndefMessage ?: return null
            for (rec in msg.records) {
                val text = recordText(rec) ?: continue
                val m = reg9.matcher(text)
                if (m.find()) return m.group()
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    private fun recordText(rec: NdefRecord): String? {
        return try {
            if (rec.tnf == NdefRecord.TNF_WELL_KNOWN &&
                rec.type.contentEquals(NdefRecord.RTD_TEXT)
            ) {
                val p = rec.payload
                val langLen = p[0].toInt() and 0x3f
                val enc = if (p[0].toInt() and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
                String(p, langLen + 1, p.size - langLen - 1, enc)
            } else {
                // Fallback: decode the raw payload as text and let the regex try.
                String(rec.payload, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Google Sheet calls ----------

    private fun loadSheets() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            status("Paste the Web App URL first (it starts with https://script.google.com).", Kind.BAD)
            return
        }
        prefs.edit().putString("url", url).apply()
        status("Loading sheets…", Kind.NEUTRAL)
        Thread {
            try {
                val res = httpGet("$url?action=tabs")
                val obj = JSONObject(res)
                if (obj.optString("status") == "ok") {
                    val arr = obj.getJSONArray("tabs")
                    val tabs = ArrayList<String>()
                    for (i in 0 until arr.length()) tabs.add(arr.getString(i))
                    prefs.edit().putString("tabs", tabs.joinToString("\n")).apply()
                    runOnUiThread {
                        setSpinner(tabs)
                        status("Sheets loaded. Pick one, then tap a card.", Kind.OK)
                    }
                } else {
                    runOnUiThread { status("Script error: ${obj.optString("message")}", Kind.BAD) }
                }
            } catch (e: Exception) {
                runOnUiThread { status("Couldn't reach the sheet. Check the URL and your internet.\n(${e.message})", Kind.BAD) }
            }
        }.start()
    }

    private fun mark(reg: String) {
        val url = prefs.getString("url", "") ?: ""
        val sheet = sheetSpinner.selectedItem?.toString()
        if (url.isBlank()) {
            runOnUiThread { status("Load your sheet first.", Kind.BAD) }
            return
        }
        if (sheet.isNullOrBlank()) {
            runOnUiThread { status("Pick a sheet first.", Kind.BAD) }
            return
        }
        runOnUiThread { status("Marking $reg …", Kind.NEUTRAL) }
        Thread {
            try {
                val full = "$url?action=mark&sheet=${enc(sheet)}&reg=${enc(reg)}"
                val obj = JSONObject(httpGet(full))
                when (obj.optString("status")) {
                    "ok" -> runOnUiThread {
                        marked++
                        countText.text = "Marked this session: $marked"
                        status("✓ $reg marked Present (row ${obj.optInt("row")})", Kind.OK)
                        buzz(longArrayOf(0, 150))
                    }
                    "already" -> runOnUiThread {
                        status("$reg was already Present (row ${obj.optInt("row")})", Kind.WARN)
                        buzz(longArrayOf(0, 60, 50, 60))
                    }
                    "notfound" -> runOnUiThread {
                        status("$reg is not in this sheet.", Kind.BAD)
                        buzz(longArrayOf(0, 250))
                    }
                    else -> runOnUiThread { status("Error: ${obj.optString("message")}", Kind.BAD) }
                }
            } catch (e: Exception) {
                runOnUiThread { status("Network error marking $reg.\n(${e.message})", Kind.BAD) }
            }
        }.start()
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- UI helpers ----------

    private enum class Kind { OK, WARN, BAD, NEUTRAL }

    private fun status(msg: String, kind: Kind) {
        statusText.text = msg
        statusText.setTextColor(
            when (kind) {
                Kind.OK -> 0xFF1B8A2E.toInt()
                Kind.WARN -> 0xFFB45309.toInt()
                Kind.BAD -> 0xFFC62828.toInt()
                Kind.NEUTRAL -> 0xFF607080.toInt()
            }
        )
    }

    private fun setSpinner(tabs: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tabs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sheetSpinner.adapter = adapter
        val last = prefs.getString("sheet", null)
        if (last != null) {
            val idx = tabs.indexOf(last)
            if (idx >= 0) sheetSpinner.setSelection(idx)
        }
        sheetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString("sheet", tabs[pos]).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            v.vibrate(pattern, -1)
        }
    }
}
