package com.audioconcat

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import android.util.Log

@ReactModule(name = AudioConcatModule.NAME)
class AudioConcatModule(reactContext: ReactApplicationContext) :
  NativeAudioConcatSpec(reactContext) {

  private data class AudioConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val bitRate: Int
  )

  private sealed class AudioDataOrSilence {
    data class AudioFile(val filePath: String) : AudioDataOrSilence()
    data class Silence(val durationMs: Double) : AudioDataOrSilence()
  }

  private fun extractAudioConfig(filePath: String): AudioConfig {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(filePath)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
          val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          val bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            format.getInteger(MediaFormat.KEY_BIT_RATE)
          } else {
            128000 // Default 128kbps
          }
          return AudioConfig(sampleRate, channelCount, bitRate)
        }
      }
      throw Exception("No audio track found in $filePath")
    } finally {
      extractor.release()
    }
  }

  private class StreamingEncoder(
    sampleRate: Int,
    channelCount: Int,
    bitRate: Int,
    outputPath: String
  ) {
    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var totalPresentationTimeUs = 0L
    private val sampleRate: Int
    private val channelCount: Int

    init {
      this.sampleRate = sampleRate
      this.channelCount = channelCount

      val outputFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        sampleRate,
        channelCount
      )
      outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
      outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

      encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
      encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      encoder.start()

      muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodePCMChunk(pcmData: ByteArray, isLast: Boolean = false): Boolean {
      // Feed PCM data to encoder
      val inputBufferIndex = encoder.dequeueInputBuffer(10000)
      if (inputBufferIndex >= 0) {
        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
        inputBuffer.clear()
        inputBuffer.put(pcmData)

        val presentationTimeUs = totalPresentationTimeUs
        totalPresentationTimeUs += (pcmData.size.toLong() * 1_000_000) / (sampleRate * channelCount * 2)

        val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        encoder.queueInputBuffer(inputBufferIndex, 0, pcmData.size, presentationTimeUs, flags)
      }

      // Drain encoder output
      drainEncoder(isLast)

      return true
    }

    private fun drainEncoder(endOfStream: Boolean) {
      while (true) {
        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10000 else 0)

        when (outputBufferIndex) {
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            if (muxerStarted) {
              throw RuntimeException("Format changed twice")
            }
            val newFormat = encoder.outputFormat
            audioTrackIndex = muxer.addTrack(newFormat)
            muxer.start()
            muxerStarted = true
            Log.d("AudioConcat", "Encoder started, format: $newFormat")
          }
          MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (!endOfStream) {
              break
            }
            // Continue draining when end of stream
          }
          else -> {
            if (outputBufferIndex >= 0) {
              val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!

              if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0
              }

              if (bufferInfo.size > 0 && muxerStarted) {
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
              }

              encoder.releaseOutputBuffer(outputBufferIndex, false)

              if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break
              }
            }
          }
        }
      }
    }

    fun finish() {
      // Signal end of stream
      val inputBufferIndex = encoder.dequeueInputBuffer(10000)
      if (inputBufferIndex >= 0) {
        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      }

      // Drain remaining data
      drainEncoder(true)

      encoder.stop()
      encoder.release()

      if (muxerStarted) {
        muxer.stop()
      }
      muxer.release()
    }
  }

  private fun streamDecodeAudioFile(
    filePath: String,
    encoder: StreamingEncoder,
    isLastFile: Boolean
  ) {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null

    try {
      extractor.setDataSource(filePath)

      var audioTrackIndex = -1
      var audioFormat: MediaFormat? = null

      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
          audioTrackIndex = i
          audioFormat = format
          break
        }
      }

      if (audioTrackIndex == -1 || audioFormat == null) {
        throw Exception("No audio track found in $filePath")
      }

      extractor.selectTrack(audioTrackIndex)

      val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
      decoder = MediaCodec.createDecoderByType(mime)
      decoder.configure(audioFormat, null, null, 0)
      decoder.start()

      val bufferInfo = MediaCodec.BufferInfo()
      var isEOS = false
      val pcmChunkSize = 8192 // Process in 8KB chunks

      while (!isEOS) {
        // Feed input to decoder
        val inputBufferIndex = decoder.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
          val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
          val sampleSize = extractor.readSampleData(inputBuffer, 0)

          if (sampleSize < 0) {
            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          } else {
            val presentationTimeUs = extractor.sampleTime
            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
            extractor.advance()
          }
        }

        // Get PCM output from decoder and feed to encoder
        val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
        if (outputBufferIndex >= 0) {
          val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)!!

          if (bufferInfo.size > 0) {
            val pcmData = ByteArray(bufferInfo.size)
            outputBuffer.get(pcmData)

            // Stream to encoder immediately
            encoder.encodePCMChunk(pcmData, false)
          }

          decoder.releaseOutputBuffer(outputBufferIndex, false)

          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            isEOS = true
          }
        }
      }

    } finally {
      decoder?.stop()
      decoder?.release()
      extractor.release()
    }
  }

  private fun streamEncodeSilence(
    durationMs: Double,
    encoder: StreamingEncoder,
    sampleRate: Int,
    channelCount: Int
  ) {
    val totalSamples = ((durationMs / 1000.0) * sampleRate).toInt()
    val chunkSamples = 4096 // Process in chunks
    val bytesPerSample = channelCount * 2 // 16-bit stereo

    var samplesRemaining = totalSamples

    while (samplesRemaining > 0) {
      val currentChunkSamples = minOf(chunkSamples, samplesRemaining)
      val chunkBytes = currentChunkSamples * bytesPerSample
      val silenceChunk = ByteArray(chunkBytes) // All zeros = silence

      encoder.encodePCMChunk(silenceChunk, false)
      samplesRemaining -= currentChunkSamples
    }
  }

  private fun parseAudioData(data: ReadableArray): List<AudioDataOrSilence> {
    val result = mutableListOf<AudioDataOrSilence>()
    for (i in 0 until data.size()) {
      val item = data.getMap(i)
      if (item != null) {
        if (item.hasKey("filePath")) {
          val filePath = item.getString("filePath")
          if (filePath != null) {
            result.add(AudioDataOrSilence.AudioFile(filePath))
          }
        } else if (item.hasKey("durationMs")) {
          result.add(AudioDataOrSilence.Silence(item.getDouble("durationMs")))
        }
      }
    }
    return result
  }

  override fun getName(): String {
    return NAME
  }

  override fun concatAudioFiles(data: ReadableArray, outputPath: String, promise: Promise) {
    try {
      if (data.size() == 0) {
        promise.reject("EMPTY_DATA", "Data array is empty")
        return
      }

      val parsedData = parseAudioData(data)
      Log.d("AudioConcat", "Streaming merge of ${parsedData.size} items")
      Log.d("AudioConcat", "Output: $outputPath")

      // Get audio config from first audio file
      var audioConfig: AudioConfig? = null
      for (item in parsedData) {
        if (item is AudioDataOrSilence.AudioFile) {
          audioConfig = extractAudioConfig(item.filePath)
          break
        }
      }

      if (audioConfig == null) {
        promise.reject("NO_AUDIO_FILES", "No audio files found in data array")
        return
      }

      Log.d("AudioConcat", "Audio config: ${audioConfig.sampleRate}Hz, ${audioConfig.channelCount}ch, ${audioConfig.bitRate}bps")

      // Delete existing output file
      val outputFile = File(outputPath)
      if (outputFile.exists()) {
        outputFile.delete()
      }

      // Create streaming encoder
      val encoder = StreamingEncoder(
        audioConfig.sampleRate,
        audioConfig.channelCount,
        audioConfig.bitRate,
        outputPath
      )

      try {
        // Process each item
        for ((index, item) in parsedData.withIndex()) {
          when (item) {
            is AudioDataOrSilence.AudioFile -> {
              val filePath = item.filePath
              Log.d("AudioConcat", "Item $index: Streaming decode $filePath")

              val isLastFile = (index == parsedData.size - 1)
              streamDecodeAudioFile(filePath, encoder, isLastFile)
            }

            is AudioDataOrSilence.Silence -> {
              val durationMs = item.durationMs
              Log.d("AudioConcat", "Item $index: Streaming silence ${durationMs}ms")

              streamEncodeSilence(
                durationMs,
                encoder,
                audioConfig.sampleRate,
                audioConfig.channelCount
              )
            }
          }
        }

        // Finish encoding
        encoder.finish()
        Log.d("AudioConcat", "Successfully merged audio to $outputPath")
        promise.resolve(outputPath)

      } catch (e: Exception) {
        Log.e("AudioConcat", "Error during streaming merge: ${e.message}", e)
        promise.reject("MERGE_ERROR", e.message, e)
      }

    } catch (e: Exception) {
      Log.e("AudioConcat", "Error parsing data: ${e.message}", e)
      promise.reject("PARSE_ERROR", e.message, e)
    }
  }

  companion object {
    const val NAME = "AudioConcat"
  }
}
