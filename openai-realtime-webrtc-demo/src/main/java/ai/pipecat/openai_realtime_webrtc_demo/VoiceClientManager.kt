package ai.pipecat.openai_realtime_webrtc_demo

import ai.pipecat.client.PipecatClient
import ai.pipecat.client.PipecatClientOptions
import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.openai_realtime_webrtc.OpenAIRealtimeSessionConfig
import ai.pipecat.client.openai_realtime_webrtc.OpenAIRealtimeWebRTCTransport
import ai.pipecat.client.openai_realtime_webrtc.OpenAIServiceOptions
import ai.pipecat.client.openai_realtime_webrtc.PipecatClientOpenAIRealtimeWebRTC
import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.Result
import ai.pipecat.client.transport.MsgServerToClient
import ai.pipecat.client.types.BotOutputData
import ai.pipecat.client.types.BotReadyData
import ai.pipecat.client.types.LLMContextMessage
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.PipecatMetrics
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.types.Transcript
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.openai_realtime_webrtc_demo.utils.Timestamp
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Immutable
data class Error(val message: String)

@Stable
class VoiceClientManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceClientManager"
    }

    private val client = mutableStateOf<PipecatClientOpenAIRealtimeWebRTC?>(null)

    val state = mutableStateOf<TransportState?>(null)

    val errors = mutableStateListOf<Error>()

    val botReady = mutableStateOf(false)
    val botIsTalking = mutableStateOf(false)
    val userIsTalking = mutableStateOf(false)
    val botAudioLevel = mutableFloatStateOf(0f)
    val userAudioLevel = mutableFloatStateOf(0f)

    val mic = mutableStateOf(false)
    val camera = mutableStateOf(false)
    val tracks = mutableStateOf<Tracks?>(null)

    private fun <E> Future<E, RTVIError>.displayErrors() = withErrorCallback {
        Log.e(TAG, "Future resolved with error: ${it.description}", it.exception)
        errors.add(Error(it.description))
    }

    fun start() {

        if (client.value != null) {
            return
        }

        val apiKey = Preferences.apiKey.value ?: return

        val config = OpenAIRealtimeSessionConfig(
            turnDetection = Value.Object(
                "type" to Value.Str("semantic_vad")
            ),
            inputAudioNoiseReduction = Value.Object(
                "type" to Value.Str("near_field")
            ),
            inputAudioTranscription = Value.Object(
                "model" to Value.Str("whisper-1")
            ),
            voice = "marin"
        )

        val options = OpenAIServiceOptions(
            apiKey = apiKey,
            sessionConfig = config,
            initialMessages = listOf(LLMContextMessage(
                role = LLMContextMessage.Role.System,
                content = "You are a helpful voice assistant. Start the conversation and greet the user."
            ))
        )

        state.value = TransportState.Disconnected

        val callbacks = object : PipecatEventCallbacks() {
            override fun onTransportStateChanged(state: TransportState) {
                this@VoiceClientManager.state.value = state
            }

            override fun onBackendError(message: String) {
                "Error from backend: $message".let {
                    Log.e(TAG, it)
                    errors.add(Error(it))
                }
            }

            override fun onBotReady(data: BotReadyData) {
                Log.i(TAG, "Bot ready. Version ${data.version}")
                botReady.value = true
            }

            override fun onMetrics(data: PipecatMetrics) {
                Log.i(TAG, "Pipecat metrics: $data")
            }

            override fun onBotOutput(data: BotOutputData) {
                Log.i(TAG, "Bot output: ${data.text}")
            }

            override fun onUserTranscript(data: Transcript) {
                Log.i(TAG, "User transcript: ${data.text}")
            }

            override fun onBotStartedSpeaking() {
                Log.i(TAG, "Bot started speaking")
                botIsTalking.value = true
            }

            override fun onBotStoppedSpeaking() {
                Log.i(TAG, "Bot stopped speaking")
                botIsTalking.value = false
            }

            override fun onUserStartedSpeaking() {
                Log.i(TAG, "User started speaking")
                userIsTalking.value = true
            }

            override fun onUserStoppedSpeaking() {
                Log.i(TAG, "User stopped speaking")
                userIsTalking.value = false
            }

            override fun onTracksUpdated(tracks: Tracks) {
                this@VoiceClientManager.tracks.value = tracks
            }

            override fun onInputsUpdated(camera: Boolean, mic: Boolean) {
                this@VoiceClientManager.camera.value = camera
                this@VoiceClientManager.mic.value = mic
            }

            override fun onDisconnected() {
                botIsTalking.value = false
                userIsTalking.value = false
                state.value = null
                botReady.value = false
                tracks.value = null

                client.value?.release()
                client.value = null
            }

            override fun onUserAudioLevel(level: Float) {
                userAudioLevel.floatValue = level
            }

            override fun onRemoteAudioLevel(level: Float, participant: Participant) {
                botAudioLevel.floatValue = level
            }
        }

        val client = PipecatClient(
            OpenAIRealtimeWebRTCTransport(context),
            PipecatClientOptions(callbacks = callbacks)
        )

        client.connect(options).displayErrors().withErrorCallback {
            callbacks.onDisconnected()
        }

        this.client.value = client
    }

    fun enableMic(enabled: Boolean) {
        client.value?.enableMic(enabled)?.displayErrors()
    }

    fun toggleMic() = enableMic(!mic.value)

    fun stop() {
        client.value?.disconnect()?.displayErrors()
    }
}