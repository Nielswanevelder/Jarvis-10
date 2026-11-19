package com.niel.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var logBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var model: String? = null
    private var busy = false
    private var recognizer: SpeechRecognizer? = null
    private var alwaysOn = false
    private var speaking = false
    private var awaitingCommand = false
    private var listeningActive = false
    private lateinit var alwaysBtn: Button
    private lateinit var bgBtn: Button
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    private val persona = "You are Jarvis, a polite British butler style assistant. Reply in one to three short sentences. Use your tools to open apps, call, text, send WhatsApp messages and set timers."

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF060D16.toInt())
        root.setPadding(px(12), px(12), px(12), px(12))
        root.fitsSystemWindows = true

        val title = TextView(this)
        title.text = "J A R V I S"
        title.setTextColor(0xFF27C4FF.toInt())
        title.textSize = 16f
        title.gravity = Gravity.CENTER
        title.typeface = Typeface.DEFAULT_BOLD
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this)
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        scroll.addView(logBox, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val mic = Button(this)
        mic.text = "Tap to talk"
        mic.setOnClickListener { listen() }
        root.addView(mic, LinearLayout.LayoutParams(-1, px(64)))

        alwaysOn = prefs.getBoolean("always", false)
        alwaysBtn = Button(this)
        alwaysBtn.text = if (alwaysOn) "Always listening: ON (say Jarvis)" else "Always listening: OFF"
        alwaysBtn.setOnClickListener { setAlways(!alwaysOn) }
        root.addView(alwaysBtn, LinearLayout.LayoutParams(-1, -2))

        bgBtn = Button(this)
        bgBtn.text = if (prefs.getBoolean("bg", false)) "Background mode: ON" else "Background mode: OFF"
        bgBtn.setOnClickListener { setBackground(!prefs.getBoolean("bg", false)) }
        root.addView(bgBtn, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        input = EditText(this)
        input.hint = "Or type here"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.GRAY)
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val keyBtn = Button(this)
        keyBtn.text = "Key"
        keyBtn.setOnClickListener { promptKey(null) }
        row.addView(keyBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        requestPermissions(
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ), 2
        )
        askNotificationAccess()
        addMsg("Good day. How may I help?", false)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.UK
        val voices = tts.voices?.filter { it.locale == Locale.UK } ?: emptyList()
        val pick = voices.firstOrNull { it.name.contains("gbb") || it.name.contains("gbd") }
            ?: voices.firstOrNull()
        if (pick != null) tts.voice = pick
        tts.setPitch(0.8f)
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                ui.post { doneSpeaking() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                ui.post { doneSpeaking() }
            }
        })
        ui.postDelayed({ readNotifications() }, 800)
    }

    override fun onDestroy() {
        stopAlways()
        tts.shutdown()
        super.onDestroy()
    }

    private fun speak(t: String) {
        ui.post {
            pauseListening()
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    private fun addMsg(text: String, me: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(0xFFDFF3FF.toInt())
        tv.setPadding(px(12), px(8), px(12), px(8))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.85).toInt()
        val bg = GradientDrawable()
        bg.cornerRadius = px(14).toFloat()
        bg.setColor(if (me) 0xFF12324A.toInt() else 0xFF0F1D2C.toInt())
        tv.background = bg
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.gravity = if (me) Gravity.END else Gravity.START
        lp.setMargins(0, px(5), 0, px(5))
        logBox.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) {
            input.setText("")
            ask(t)
        }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening")
        try {
            startActivityForResult(i, 1)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input is not available. Type instead.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) ask(said)
        }
    }

    private fun promptKey(after: (() -> Unit)?) {
        val et = EditText(this)
        et.hint = "gsk_..."
        et.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Groq API key")
            .setMessage("Stored only on this phone.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val k = et.text.toString().trim()
                if (k.isNotEmpty()) {
                    prefs.edit().putString("key", k).apply()
                    model = null
                    after?.invoke()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ask(q: String) {
        if (busy) return
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) {
            promptKey { ask(q) }
            return
        }
        busy = true
        addMsg(q, true)
        history.put(JSONObject().put("role", "user").put("content", q))
        val bubble = addMsg("...", false)
        thread {
            try {
                val reply = runChat(key)
                ui.post { bubble.text = reply; speak(reply); busy = false }
            } catch (e: Exception) {
                if (history.length() > 0) history.remove(history.length() - 1)
                ui.post {
                    bubble.text = "Sorry, that failed: ${e.message}"
                    busy = false
                    startAlways()
                }
            }
        }
    }

    private fun http(url: String, key: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (body == null) "GET" else "POST"
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 20000
        c.readTimeout = 60000
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("$code ${text.take(150)}")
        return text
    }

    private fun pickModel(key: String): String {
        val data = JSONObject(http("https://api.groq.com/openai/v1/models", key, null)).getJSONArray("data")
        val ids = ArrayList<String>()
        for (i in 0 until data.length()) ids.add(data.getJSONObject(i).getString("id"))
        for (p in listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant")) {
            if (ids.contains(p)) return p
        }
        val bad = Regex("whisper|guard|tts|orpheus|playai|compound|embed|safeguard|distil", RegexOption.IGNORE_CASE)
        return ids.firstOrNull { !bad.containsMatchIn(it) } ?: throw Exception("no chat models available")
    }

    private fun tool(name: String, desc: String, vararg params: String): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (p in params) {
            props.put(p, JSONObject().put("type", if (p == "seconds") "number" else "string"))
            req.put(p)
        }
        val fn = JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
        return JSONObject().put("type", "function").put("function", fn)
    }

    private fun toolDefs(): JSONArray {
        val a = JSONArray()
        a.put(tool("get_time", "Get the current local date and time."))
        a.put(tool("set_timer", "Start a countdown timer. The app announces when it ends.", "seconds"))
        a.put(tool("open_app", "Open an installed app on this phone by its name, for example Spotify.", "app"))
        a.put(tool("call_contact", "Call a person from the phone contacts by name, or a phone number.", "name"))
        a.put(tool("send_text", "Send an SMS text message to a contact by name, or a phone number.", "name", "message"))
        a.put(tool("get_weather", "Get the current temperature and weather where the user is."))
        a.put(tool("get_location", "Get the town or city where the user currently is."))
        a.put(tool("read_notifications", "Read out the notifications currently on the phone."))
        a.put(tool("whatsapp_message", "Open a WhatsApp chat with a contact and prepare a message. The user taps send.", "name", "message"))
        return a
    }

    private fun runChat(key: String): String {
        if (model == null) model = pickModel(key)
        val msgs = JSONArray(history.toString())
        for (round in 0 until 5) {
            val all = JSONArray()
            all.put(JSONObject().put("role", "system").put("content", persona))
            for (k in 0 until msgs.length()) all.put(msgs.get(k))
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 500)
                .put("tools", toolDefs())
                .put("messages", all)
            val resp = http("https://api.groq.com/openai/v1/chat/completions", key, body.toString())
            val m = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val content = if (m.isNull("content")) "" else m.getString("content").trim()
            val calls = m.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val out = if (content.isEmpty()) "Apologies, I have nothing to say." else content
                history.put(JSONObject().put("role", "assistant").put("content", out))
                return out
            }
            msgs.put(JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls))
            for (c in 0 until calls.length()) {
                val call = calls.getJSONObject(c)
                val fn = call.getJSONObject("function")
                val args = try { JSONObject(fn.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
                val result = try { runTool(fn.getString("name"), args) } catch (e: Exception) { "Error: ${e.message}" }
                msgs.put(
                    JSONObject().put("role", "tool")
                        .put("tool_call_id", call.getString("id"))
                        .put("content", result)
                )
            }
        }
        return "Sorry, I got stuck on that one."
    }

    private fun runTool(name: String, args: JSONObject): String {
        return when (name) {
            "get_time" -> java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
            "set_timer" -> {
                val s = args.optDouble("seconds", 0.0).toLong().coerceIn(1L, 86400L)
                ui.postDelayed({
                    val m = "Timer done."
                    addMsg(m, false)
                    speak(m)
                }, s * 1000)
                "Timer set for $s seconds."
            }
            "open_app" -> openApp(args.optString("app", ""))
            "call_contact" -> callContact(args.optString("name", ""))
            "send_text" -> sendText(args.optString("name", ""), args.optString("message", ""))
            "get_weather" -> weather()
            "get_location" -> locationText()
            "read_notifications" -> notificationSummary().ifEmpty { "Notification access is off. Turn it on in settings." }
            "whatsapp_message" -> whatsappMessage(args.optString("name", ""), args.optString("message", ""))
            else -> "Unknown tool."
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun openApp(name: String): String {
        val q = norm(name)
        if (q.isEmpty()) return "Which app?"
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val match = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(q) }
            ?: return "I could not find an app called $name."
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I cannot open that app."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "Opening ${match.loadLabel(pm)}."
    }

    private fun findNumber(name: String): String? {
        if (Regex("^[+0-9 ()-]{5,}$").matches(name.trim())) return name.replace(" ", "")
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )
        c?.use { if (it.moveToFirst()) return it.getString(1) }
        return null
    }

    private fun callContact(name: String): String {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return "I need phone permission. Please allow it in settings and ask again."
        val num = findNumber(name) ?: return "I could not find a number for $name. Check contacts permission."
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num)))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return "Calling $name."
    }

    private fun sendText(name: String, message: String): String {
        if (message.isBlank()) return "What should the message say?"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "I need SMS permission. Please allow it in settings and ask again."
        val num = findNumber(name) ?: return "I could not find a number for $name. Check contacts permission."
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(num, null, sms.divideMessage(message), null, null)
        return "Text sent to $name."
    }

    private fun waNumber(raw: String): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        var iso = tm.networkCountryIso.uppercase()
        if (iso.isEmpty()) iso = Locale.getDefault().country
        val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(raw, iso)
        return (e164 ?: raw).filter { it.isDigit() }
    }

    private fun whatsappMessage(name: String, message: String): String {
        if (message.isBlank()) return "What should the message say?"
        val raw = findNumber(name) ?: return "I could not find a number for $name. Check contacts permission."
        val url = "https://wa.me/" + waNumber(raw) + "?text=" + Uri.encode(message)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.setPackage("com.whatsapp")
        try {
            startActivity(i)
        } catch (e: Exception) {
            i.setPackage(null)
            startActivity(i)
        }
        return "WhatsApp is ready for $name. Tap send."
    }

    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun askNotificationAccess() {
        if (listenerEnabled() || prefs.getBoolean("asked_notif", false)) return
        prefs.edit().putBoolean("asked_notif", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Notification access")
            .setMessage("Jarvis needs notification access to read your notifications aloud when it opens.")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun notificationSummary(): String {
        val svc = NotifService.instance ?: return ""
        val list = svc.activeNotifications.filter { !it.isOngoing && it.packageName != packageName }
        if (list.isEmpty()) return "You have no new notifications."
        val pm = packageManager
        val sb = StringBuilder("You have ${list.size} notifications. ")
        for (n in list.take(8)) {
            val ex = n.notification.extras
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(n.packageName, 0)).toString()
            } catch (e: Exception) {
                n.packageName
            }
            val title = ex.getCharSequence("android.title")?.toString() ?: ""
            val text = (ex.getCharSequence("android.text")?.toString() ?: "").take(100)
            sb.append("$app. $title. $text. ")
        }
        return sb.toString()
    }

    private fun readNotifications() {
        val t = notificationSummary()
        if (t.isEmpty()) return
        addMsg(t, false)
        speak(t)
    }

    private val seen = HashMap<String, String>()

    override fun onResume() {
        super.onResume()
        NotifService.listener = { sbn -> ui.post { announce(sbn) } }
        if (alwaysOn) ui.postDelayed({ startAlways() }, 500)
    }

    override fun onPause() {
        NotifService.listener = null
        if (alwaysOn) stopAlways()
        super.onPause()
    }

    private fun announce(n: StatusBarNotification) {
        if (n.isOngoing || n.packageName == packageName) return
        if ((n.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.notification.extras
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString()
        } catch (e: Exception) {
            n.packageName
        }
        val title = ex.getCharSequence("android.title")?.toString() ?: ""
        val text = (ex.getCharSequence("android.text")?.toString() ?: "").take(100)
        val line = "$app. $title. $text."
        if (seen[n.key] == line) return
        seen[n.key] = line
        addMsg(line, false)
        pauseListening()
        tts.speak(line, TextToSpeech.QUEUE_ADD, null, "n" + System.nanoTime())
    }

    private fun currentLocation(): Location? {
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        return best
    }

    private fun placeName(loc: Location): String {
        return try {
            if (!Geocoder.isPresent()) return "your area"
            @Suppress("DEPRECATION")
            val list = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: "your area"
        } catch (e: Exception) {
            "your area"
        }
    }

    private fun locationText(): String {
        val loc = currentLocation()
            ?: return "I cannot get your location. Allow location permission, and open Maps once so the phone has a fix."
        return "You are in " + placeName(loc) + "."
    }

    private fun weather(): String {
        val loc = currentLocation()
            ?: return "I cannot get your location. Allow location permission, and open Maps once so the phone has a fix."
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.latitude +
            "&longitude=" + loc.longitude + "&current=temperature_2m,weather_code&timezone=auto"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        val cur = JSONObject(txt).getJSONObject("current")
        val t = Math.round(cur.getDouble("temperature_2m"))
        val code = cur.optInt("weather_code", 0)
        val sky = when {
            code == 0 -> "clear"
            code in 1..3 -> "partly cloudy"
            code == 45 || code == 48 -> "foggy"
            code in 51..67 -> "rainy"
            code in 71..77 -> "snowy"
            code in 80..82 -> "showery"
            code >= 95 -> "stormy"
            else -> "mixed"
        }
        return "It is $t degrees Celsius and $sky in " + placeName(loc) + "."
    }

    private fun pauseListening() {
        speaking = true
        recognizer?.cancel()
        listeningActive = false
    }

    private fun doneSpeaking() {
        if (tts.isSpeaking) return
        speaking = false
        if (alwaysOn) ui.postDelayed({ startAlways() }, 500)
    }

    private fun setAlways(on: Boolean) {
        alwaysOn = on
        prefs.edit().putBoolean("always", on).apply()
        alwaysBtn.text = if (on) "Always listening: ON (say Jarvis)" else "Always listening: OFF"
        if (on) {
            if (prefs.getBoolean("bg", false)) setBackground(false)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
                return
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startAlways()
        } else {
            stopAlways()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setBackground(true)
            }
        }
        if (requestCode == 3) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && alwaysOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startAlways()
            } else {
                alwaysOn = false
                alwaysBtn.text = "Always listening: OFF"
            }
        }
    }

    private fun stopAlways() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        listeningActive = false
        awaitingCommand = false
    }

    private fun restartSoon(ms: Long) {
        listeningActive = false
        ui.postDelayed({ startAlways() }, ms)
    }

    private fun startAlways() {
        if (!alwaysOn || speaking || busy || listeningActive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        if (recognizer == null) {
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            recognizer?.cancel()
                            restartSoon(1000)
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            alwaysOn = false
                            alwaysBtn.text = "Always listening: OFF"
                        }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(300)
                        else -> restartSoon(1500)
                    }
                }
                override fun onResults(results: Bundle?) {
                    listeningActive = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    handleHeard(text)
                    restartSoon(400)
                }
            })
            recognizer = r
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listeningActive = true
        recognizer?.startListening(i)
    }

    private fun reply(t: String) {
        addMsg(t, false)
        speak(t)
    }

    private fun handleHeard(text: String) {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return
        if (awaitingCommand) {
            awaitingCommand = false
            ask(text)
            return
        }
        val idx = t.indexOf("jarvis")
        if (idx < 0) return
        if (t.contains("daddy") && t.contains("home")) {
            val n = notificationSummary()
            reply("Welcome home, sir. All systems are online. " + n)
            return
        }
        val cmd = t.substring(idx + 6).trim()
        if (cmd.isEmpty()) {
            awaitingCommand = true
            reply("Yes, sir?")
        } else {
            ask(cmd)
        }
    }

    private fun setBackground(on: Boolean) {
        if (!on) {
            stopService(Intent(this, ListenService::class.java))
            prefs.edit().putBoolean("bg", false).apply()
            bgBtn.text = "Background mode: OFF"
            return
        }
        if (alwaysOn) setAlways(false)
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            need.add("android.permission.POST_NOTIFICATIONS")
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 4)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Display over other apps")
                .setMessage("Turn this on for Jarvis so it can open apps from the background. Then come back and tap Background mode again.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        startForegroundService(Intent(this, ListenService::class.java))
        prefs.edit().putBoolean("bg", true).apply()
        bgBtn.text = "Background mode: ON"
    }
}
