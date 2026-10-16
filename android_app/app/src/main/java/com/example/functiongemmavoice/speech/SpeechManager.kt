package com.example.functiongemmavoice.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpeechManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    fun initTts(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                tts?.language = Locale.ENGLISH
                onReady?.invoke()
            }
        }
    }

    fun speak(text: String, languageChoice: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady || tts == null) return
        val targetLocale = when (languageChoice) {
            "Telugu (India)" -> Locale("te", "IN")
            "English + Telugu" -> {
                if (text.any { it.code in 0x0C00..0x0C7F }) Locale("te", "IN") else Locale("en", "IN")
            }
            else -> Locale("en", "IN")
        }
        val result = tts?.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.ENGLISH
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun startListening(
        activity: Activity,
        languageChoice: String,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        val preferredLocale = when (languageChoice) {
            "English (India)" -> "en-IN"
            "Telugu (India)" -> "te-IN"
            else -> "en-IN"
        }

        fun launchRecognizer(onDevice: Boolean) {
            val mode = if (onDevice) "On-device" else "Network/Fallback"

            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                onError("Android speech recognition service is not available on this device.")
                return
            }

            onStatus("Listening via $mode ($languageChoice)…")

            val recognizer = try {
                if (onDevice && Build.VERSION.SDK_INT >= 31) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(activity)
                }
            } catch (_: Throwable) {
                if (onDevice) {
                    launchRecognizer(false)
                } else {
                    onError("Failed to initialize speech recognizer.")
                }
                return
            }

            var completed = false
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (completed) return
                    completed = true
                    if (onDevice) {
                        launchRecognizer(false)
                    } else {
                        onError("Speech recognition error ($error). Check microphone permission or language pack.")
                    }
                }

                override fun onResults(results: Bundle?) {
                    recognizer.destroy()
                    if (completed) return
                    completed = true
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()
                    if (text.isNullOrBlank()) {
                        if (onDevice) launchRecognizer(false) else onError("No speech detected.")
                    } else {
                        onStatus("Recognized with $mode")
                        onText(text)
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, preferredLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            try {
                recognizer.startListening(intent)
            } catch (_: Throwable) {
                recognizer.destroy()
                if (onDevice) launchRecognizer(false) else onError("Failed to start speech listener.")
            }
        }

        launchRecognizer(Build.VERSION.SDK_INT >= 31)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
