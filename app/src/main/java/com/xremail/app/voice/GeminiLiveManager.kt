package com.xremail.app.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xremail.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Manages a bidirectional Gemini Live API session over a raw OkHttp WebSocket.
 *
 * Handles:
 * - WebSocket lifecycle (connect / disconnect)
 * - Microphone capture (16kHz 16-bit PCM) streamed as base64
 * - Audio playback of Gemini responses (24kHz 16-bit PCM)
 * - Function call parsing and tool response sending
 */
class GeminiLiveManager {

    enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED, LISTENING, ERROR }

    data class FunctionCall(
        val id: String,
        val name: String,
        val command: EmailCommandTool.Command,
    )

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _functionCalls = MutableSharedFlow<FunctionCall>(extraBufferCapacity = 16)
    val functionCalls: SharedFlow<FunctionCall> = _functionCalls.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        private const val TAG = "GeminiLive"
        private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        private const val CAPTURE_SAMPLE_RATE = 16000
        private const val PLAYBACK_SAMPLE_RATE = 24000
        private const val CHUNK_DURATION_MS = 250
        private val WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=${BuildConfig.GEMINI_API_KEY}"
    }

    // -- Public API ---------------------------------------------------------------

    fun connect() {
        if (_state.value != SessionState.DISCONNECTED) return
        _state.value = SessionState.CONNECTING

        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, Listener())
    }

    @SuppressLint("MissingPermission")
    fun startAudioCapture() {
        if (_state.value != SessionState.CONNECTED && _state.value != SessionState.LISTENING) return
        _state.value = SessionState.LISTENING

        val bufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(CAPTURE_SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        audioRecord = recorder
        recorder.startRecording()

        val chunkBytes = CAPTURE_SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000
        val buffer = ByteArray(chunkBytes)

        captureJob = scope.launch {
            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val msg = JsonObject().apply {
                        add("realtimeInput", JsonObject().apply {
                            add("audio", JsonObject().apply {
                                addProperty("data", b64)
                                addProperty("mimeType", "audio/pcm;rate=$CAPTURE_SAMPLE_RATE")
                            })
                        })
                    }
                    webSocket?.send(gson.toJson(msg))
                }
            }
        }
    }

    fun stopAudioCapture() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        if (_state.value == SessionState.LISTENING) {
            _state.value = SessionState.CONNECTED
        }
    }

    fun sendToolResponse(callId: String, name: String, result: String?) {
        val response = JsonObject().apply {
            add("toolResponse", JsonObject().apply {
                add("functionResponses", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("id", callId)
                        addProperty("name", name)
                        add("response", JsonObject().apply {
                            addProperty("result", result ?: "Done")
                        })
                    })
                })
            })
        }
        webSocket?.send(gson.toJson(response))
    }

    fun disconnect() {
        stopAudioCapture()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = SessionState.DISCONNECTED
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    // -- WebSocket listener -------------------------------------------------------

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")
            sendSetupMessage(webSocket)
            _state.value = SessionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JsonParser.parseString(text).asJsonObject
                handleServerMessage(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            _state.value = SessionState.ERROR
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, reason)
            _state.value = SessionState.DISCONNECTED
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            _state.value = SessionState.DISCONNECTED
        }
    }

    // -- Setup message ------------------------------------------------------------

    private fun sendSetupMessage(ws: WebSocket) {
        val setup = JsonObject().apply {
            add("setup", JsonObject().apply {
                addProperty("model", MODEL)
                add("generationConfig", JsonObject().apply {
                    add("responseModalities", com.google.gson.JsonArray().apply {
                        add("AUDIO")
                    })
                    add("speechConfig", JsonObject().apply {
                        add("voiceConfig", JsonObject().apply {
                            add("prebuiltVoiceConfig", JsonObject().apply {
                                addProperty("voiceName", "Aoede")
                            })
                        })
                    })
                })
                add("systemInstruction", JsonObject().apply {
                    add("parts", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", SYSTEM_INSTRUCTION)
                        })
                    })
                })
                add("tools", EmailCommandTool.toolDeclarationsJson())
            })
        }
        ws.send(gson.toJson(setup))
        Log.i(TAG, "Setup message sent")
    }

    // -- Incoming message handling -------------------------------------------------

    private fun handleServerMessage(json: JsonObject) {
        if (json.has("setupComplete")) {
            Log.i(TAG, "Setup complete, session ready")
            return
        }

        if (json.has("toolCall")) {
            handleToolCall(json.getAsJsonObject("toolCall"))
            return
        }

        if (json.has("serverContent")) {
            val content = json.getAsJsonObject("serverContent")

            if (content.has("modelTurn")) {
                val parts = content.getAsJsonObject("modelTurn")
                    .getAsJsonArray("parts") ?: return
                for (i in 0 until parts.size()) {
                    val part = parts[i].asJsonObject
                    if (part.has("inlineData")) {
                        val data = part.getAsJsonObject("inlineData")
                        val b64 = data.get("data").asString
                        playAudioChunk(Base64.decode(b64, Base64.DEFAULT))
                    }
                }
            }
        }
    }

    private fun handleToolCall(toolCall: JsonObject) {
        val calls = toolCall.getAsJsonArray("functionCalls") ?: return
        for (i in 0 until calls.size()) {
            val fc = calls[i].asJsonObject
            val id = fc.get("id")?.asString ?: "unknown"
            val name = fc.get("name")?.asString ?: continue
            val args = fc.getAsJsonObject("args")

            val command = EmailCommandTool.parse(name, args)
            if (command != null) {
                _functionCalls.tryEmit(FunctionCall(id = id, name = name, command = command))
            } else {
                Log.w(TAG, "Unknown function call: $name")
                sendToolResponse(id, name, "Unknown command")
            }
        }
    }

    // -- Audio playback -----------------------------------------------------------

    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let { return it }

        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        audioTrack = track
        return track
    }

    private fun playAudioChunk(pcmBytes: ByteArray) {
        val track = ensureAudioTrack()
        track.write(pcmBytes, 0, pcmBytes.size)
    }
}

private const val SYSTEM_INSTRUCTION = """You are the voice assistant for XrMail, an XR email client running on a headset.
The user controls their email entirely by voice. Keep spoken responses concise — under 15 words when possible.
Use the provided functions to control the interface. When the user asks to read an email, use read_email or read_summary.
When they say archive, snooze, reply, star, forward, etc., call the matching function.
When reading urgency or unread counts, use what_is_urgent or how_many_unread.
For navigation, use expand/collapse functions matching the user's intent.
Do NOT make up email content — only reference data returned by function results."""
