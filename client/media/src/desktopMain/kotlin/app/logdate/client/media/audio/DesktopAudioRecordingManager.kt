package app.logdate.client.media.audio

import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Desktop implementation of AudioRecordingManager using JavaSound API
 */
class DesktopAudioRecordingManager(
    private val audioStorage: AudioStorage,
) : AudioRecordingManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Recording state
    private var recordingActive = false
    private var recordingThread: Thread? = null
    private var recordingStartTime: Long = 0
    private var recordingTarget: AudioRecordingTarget? = null

    // Audio capture
    private var line: TargetDataLine? = null
    private var audioData: ByteArrayOutputStream? = null

    // Audio format for recording
    private val audioFormat =
        AudioFormat(
            44100f, // sample rate
            16, // sample size in bits
            2, // channels
            true, // signed
            false, // big endian
        )

    // Flows
    private val audioLevelFlow = MutableStateFlow(0f)
    private val transcriptionFlow = MutableStateFlow<String?>(null)
    private var transcriptionService: TranscriptionService? = null

    override val isRecording: Boolean
        get() = recordingActive

    override fun setTranscriptionService(service: TranscriptionService) {
        this.transcriptionService = service

        // Listen for transcription updates
        scope.launch {
            service.getTranscriptionFlow().collectLatest { result ->
                when (result) {
                    is TranscriptionResult.Success -> {
                        transcriptionFlow.value = result.text
                    }

                    is TranscriptionResult.Error -> {
                        Napier.e("Transcription error: ${result.reason}")
                    }

                    is TranscriptionResult.InProgress -> {
                        // Just wait for the text
                    }

                    TranscriptionResult.Cancelled -> {
                        Unit
                    }
                }
            }
        }
    }

    override suspend fun startRecording(targetNoteId: Uuid?): Boolean = startRecording(targetNoteId) { true }

    override suspend fun startRecording(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean {
        if (recordingActive) return false

        try {
            val target = audioStorage.createRecordingTarget(extension = "wav")
            if (!onTargetReady(target.path)) return false
            recordingTarget = target
            // Set up audio format and data line
            val info = DataLine.Info(TargetDataLine::class.java, audioFormat)

            if (!AudioSystem.isLineSupported(info)) {
                Napier.e("Desktop: Audio line not supported")
                return false
            }

            line = AudioSystem.getLine(info) as TargetDataLine
            line?.open(audioFormat)
            line?.start()

            audioData = ByteArrayOutputStream()
            recordingActive = true
            recordingStartTime = System.currentTimeMillis()

            // Start recording thread
            recordingThread =
                Thread {
                    val buffer = ByteArray(4096)
                    var bytesRead: Int

                    try {
                        while (recordingActive && line != null) {
                            bytesRead = line!!.read(buffer, 0, buffer.size)
                            if (bytesRead > 0) {
                                audioData?.write(buffer, 0, bytesRead)

                                // Calculate audio levels for visualization
                                val level = calculateAudioLevel(buffer, bytesRead)
                                audioLevelFlow.value = level
                            }
                        }
                    } catch (e: Exception) {
                        Napier.e("Desktop: Error recording audio", e)
                    }
                }.apply {
                    name = "AudioRecordingThread"
                    start()
                }

            // Start transcription service
            transcriptionService?.let { service ->
                if (service.supportsLiveTranscription) {
                    service.startLiveTranscription()
                }
            }

            Napier.d("Desktop: Started recording")
            return true
        } catch (e: LineUnavailableException) {
            Napier.e("Desktop: Could not start recording", e)
            return false
        } catch (e: Exception) {
            Napier.e("Desktop: Error starting recording", e)
            release()
            return false
        }
    }

    override suspend fun stopRecording(): String? {
        if (!recordingActive) return null

        try {
            recordingActive = false

            // Stop recording thread
            recordingThread?.join(1000) // Give thread time to finish
            recordingThread = null

            // Stop and close the line
            line?.stop()
            line?.close()

            // Stop transcription service
            transcriptionService?.let { service ->
                if (service.supportsLiveTranscription) {
                    service.stopLiveTranscription()
                }
            }

            val outputFile =
                recordingTarget?.path?.let { File(it) }
                    ?: File.createTempFile("desktop_audio_", ".wav")

            // Save audio data to file
            saveAudioToFile(outputFile)

            // Get URI for the file
            val fileUri = outputFile.absolutePath

            // Use transcription service for file
            transcriptionService?.let { service ->
                if (service.supportsFileTranscription) {
                    try {
                        val result = service.transcribeAudioFile(fileUri)
                        if (result is TranscriptionResult.Success) {
                            transcriptionFlow.value = result.text
                        }
                    } catch (e: Exception) {
                        Napier.e("Error transcribing audio file", e)
                    }
                }
            }

            // Reset state
            line = null
            audioData = null
            recordingTarget = null

            Napier.d("Desktop: Stopped recording: $fileUri")
            return fileUri
        } catch (e: Exception) {
            Napier.e("Desktop: Error stopping recording", e)
            release()
            return null
        }
    }

    override fun getAudioLevelFlow(): Flow<Float> = audioLevelFlow

    override fun getRecordingDurationFlow(): Flow<Duration> =
        flow {
            while (recordingActive) {
                val duration = System.currentTimeMillis() - recordingStartTime
                emit(duration.milliseconds)
                delay(100)
            }
        }

    override fun getTranscriptionFlow(): Flow<String?> = transcriptionFlow

    override fun release() {
        try {
            recordingActive = false

            // Stop recording thread
            recordingThread?.interrupt()
            recordingThread = null

            // Stop and close the line
            line?.stop()
            line?.close()
            line = null

            // Clear audio data
            audioData = null
            recordingTarget = null

            // Release transcription service
            transcriptionService?.release()
            transcriptionService = null

            Napier.d("Desktop: Released audio recording resources")
        } catch (e: Exception) {
            Napier.e("Desktop: Error releasing resources", e)
        }
    }

    /**
     * Saves the recorded audio data to a file in WAV format
     */
    private fun saveAudioToFile(outputFile: File) {
        if (audioData == null) {
            Napier.e("Desktop: No audio data to save")
            return
        }

        try {
            // Create an audio input stream from the recorded data
            val byteData = audioData!!.toByteArray()
            val byteArrayInputStream = java.io.ByteArrayInputStream(byteData)
            val audioInputStream =
                AudioInputStream(
                    byteArrayInputStream,
                    audioFormat,
                    byteData.size.toLong() / audioFormat.frameSize,
                )

            // Write to file
            AudioSystem.write(
                audioInputStream,
                AudioFileFormat.Type.WAVE,
                outputFile,
            )

            audioInputStream.close()
            Napier.d("Desktop: Saved audio file to ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Napier.e("Desktop: Error saving audio file", e)
        }
    }

    /**
     * Calculates audio level from the raw audio buffer
     */
    private fun calculateAudioLevel(
        buffer: ByteArray,
        bytesRead: Int,
    ): Float {
        if (bytesRead <= 0) return 0f

        var sum = 0.0
        val bytesPerSample = audioFormat.sampleSizeInBits / 8
        val frameSize = audioFormat.frameSize

        var sampleCount = 0

        for (i in 0 until bytesRead step frameSize) {
            if (i + bytesPerSample <= bytesRead) {
                // Process each sample based on bit depth
                val amplitude =
                    when (audioFormat.sampleSizeInBits) {
                        8 -> {
                            // 8-bit audio is unsigned in Java
                            val unsignedByte = buffer[i].toInt() and 0xFF
                            unsignedByte / 128.0 - 1.0 // Convert to -1.0 to 1.0 range
                        }

                        16 -> {
                            // 16-bit audio (handle endianness)
                            val low = buffer[i].toInt() and 0xFF
                            val high = buffer[i + 1].toInt() and 0xFF
                            val sample = if (audioFormat.isBigEndian) (high shl 8) or low else (low shl 8) or high
                            sample / 32768.0 // Convert to -1.0 to 1.0 range
                        }

                        else -> {
                            0.0
                        }
                    }

                sum += Math.abs(amplitude)
                sampleCount++
            }
        }

        // Calculate average amplitude (0.0 to 1.0)
        val avgAmplitude = if (sampleCount > 0) (sum / sampleCount).toFloat() else 0f

        // Apply some smoothing and amplification to make the visualization more responsive
        return (avgAmplitude * 3.0f).coerceIn(0f, 1f)
    }
}
