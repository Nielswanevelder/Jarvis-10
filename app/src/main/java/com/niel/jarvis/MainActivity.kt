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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
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
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

        private val persona = "You are Jarvis, a witty and polite butler style voice assistant on an Android phone. Reply in one to three short spoken sentences with no markdown. Use your tools when relevant for opening apps, calling, texting, timers and the time."