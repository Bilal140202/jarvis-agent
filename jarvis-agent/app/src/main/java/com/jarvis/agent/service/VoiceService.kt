package com.jarvis.agent.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Voice Service — Speech-to-Text (STT) and Text-to-Speech (TTS).
 *
 * Uses native Android SpeechRecognizer for STT and TextToSpeech for TTS.
 * No external API calls needed — everything runs on-device.
 */
class VoiceService(private val context: android.content.Context) {

    companion object {
        private const val TAG = "VoiceService"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * Initialize TTS engine.
     */
    fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.i(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    /**
     * Speak text aloud.
     */
    fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, skipping speak")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_tts_${System.currentTimeMillis()}")
    }

    /**
     * Stop speaking.
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    /**
     * Listen for speech input and return the recognized text.
     * Must be called from the main thread.
     */
    suspend fun listen(): String = suspendCancellableCoroutine { continuation ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            continuation.resumeWithException(Exception("Speech recognition not available"))
            return@suspendCancellableCoroutine
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech began")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Speech error: $error"
                }
                Log.e(TAG, msg)
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.i(TAG, "Recognized: $text")
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        continuation.invokeOnCancellation {
            recognizer.destroy()
        }

        speechRecognizer = recognizer
        recognizer.startListening(intent)
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Release all resources.
     */
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        tts = null
        speechRecognizer = null
    }
}
