package com.niel.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class ListenService : Service(), TextToSpeech.OnInitListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private lateinit var brain: Brain
    private var recognizer: SpeechRecognizer? = null
    private var speaking = false
    private var listeningActive = false
    private var awaiting = false
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = Brain(this) { speakText(it) }
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        running = true
        ui.postDelayed({ startListening() }, 1000)
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jarvis", "Jarvis listening", NotificationManager.IMPORTANCE_LOW)
        )
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say Jarvis, then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
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
    }

    private fun speakText(t: String) {
        speaking = true
        recognizer?.cancel()
        listeningActive = false
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun doneSpeaking() {
        if (tts.isSpeaking) return
        speaking = false
        if (running) ui.postDelayed({ startListening() }, 500)
    }

    private fun restartSoon(ms: Long) {
        listeningActive = false
        ui.postDelayed({ startListening() }, ms)
    }

    private fun startListening() {
        if (!running || speaking || brain.busy || listeningActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
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
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
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

    private fun runCommand(cmd: String) {
        brain.ask(cmd) { reply -> speakText(reply) }
    }

    private fun handleHeard(text: String) {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return
        if (awaiting) {
            awaiting = false
            runCommand(text)
            return
        }
        val idx = t.indexOf("jarvis")
        if (idx < 0) return
        if (t.contains("daddy") && t.contains("home")) {
            speakText("Welcome home, sir. All systems are online. " + brain.notificationSummary())
            return
        }
        val cmd = t.substring(idx + 6).trim()
        if (cmd.isEmpty()) {
            awaiting = true
            speakText("Yes, sir?")
        } else {
            runCommand(cmd)
        }
    }

    override fun onDestroy() {
        running = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts.shutdown()
        super.onDestroy()
    }
}
