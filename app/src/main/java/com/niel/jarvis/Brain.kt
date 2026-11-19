package com.niel.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class Brain(private val ctx: Context, private val say: (String) -> Unit) {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val history = JSONArray()
    private var model: String? = null

    @Volatile
    var busy = false

    private val persona = "You are Jarvis, a polite British butler style assistant. Reply in one to three short sentences. Use your tools to open apps, call, text, send WhatsApp messages, check the weather and set timers."

    fun ask(q: String, done: (String) -> Unit) {
        if (busy) return
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) {
            done("Please open the Jarvis app and enter your Groq key first.")
            return
        }
        busy = true
        history.put(JSONObject().put("role", "user").put("content", q))
        thread {
            val reply = try {
                runChat(key)
            } catch (e: Exception) {
                if (history.length() > 0) history.remove(history.length() - 1)
                "Sorry, that failed: ${e.message}"
            }
            busy = false
            ui.post { done(reply) }
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
                ui.postDelayed({ say("Timer done.") }, s * 1000)
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
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val match = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(q) }
            ?: return "I could not find an app called $name."
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I cannot open that app."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return "Opening ${match.loadLabel(pm)}."
    }

    private fun findNumber(name: String): String? {
        if (Regex("^[+0-9 ()-]{5,}$").matches(name.trim())) return name.replace(" ", "")
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = ctx.contentResolver.query(
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
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return "I need phone permission. Please allow it in settings and ask again."
        val num = findNumber(name) ?: return "I could not find a number for $name. Check contacts permission."
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num)))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Calling $name."
    }

    private fun sendText(name: String, message: String): String {
        if (message.isBlank()) return "What should the message say?"
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "I need SMS permission. Please allow it in settings and ask again."
        val num = findNumber(name) ?: return "I could not find a number for $name. Check contacts permission."
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(num, null, sms.divideMessage(message), null, null)
        return "Text sent to $name."
    }

    private fun waNumber(raw: String): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
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
            ctx.startActivity(i)
        } catch (e: Exception) {
            i.setPackage(null)
            ctx.startActivity(i)
        }
        return "WhatsApp is ready for $name. Tap send."
    }

    private fun currentLocation(): Location? {
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
            val list = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
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

    fun notificationSummary(): String {
        val svc = NotifService.instance ?: return ""
        val list = svc.activeNotifications.filter { !it.isOngoing && it.packageName != ctx.packageName }
        if (list.isEmpty()) return "You have no new notifications."
        val pm = ctx.packageManager
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
}
