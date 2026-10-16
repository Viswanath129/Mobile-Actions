package com.example.functiongemmavoice.speech

import java.io.File

/**
 * Optional offline Whisper.cpp adapter contract.
 * The native whisper.cpp library and multilingual model are not bundled in this starter.
 */
interface WhisperTinyFallback {
    fun isAvailable(): Boolean
    suspend fun transcribe(wavFile: File, modelFile: File, language: String = "auto"): String
}

class UnbundledWhisperTinyFallback : WhisperTinyFallback {
    override fun isAvailable(): Boolean = false
    override suspend fun transcribe(wavFile: File, modelFile: File, language: String): String {
        throw IllegalStateException(
            "Whisper tiny is not bundled. Add whisper.cpp Android JNI libraries and the " +
            "multilingual ggml-tiny model, then replace UnbundledWhisperTinyFallback."
        )
    }
}
