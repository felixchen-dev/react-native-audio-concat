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

  private fun resamplePCM16(
    input: ByteArray,
    inputSampleRate: Int,
    outputSampleRate: Int,
    channelCount: Int
  ): ByteArray {
    if (inputSampleRate == outputSampleRate) {
      return input
    }

    val inputSampleCount = input.size / (2 * channelCount) // 16-bit = 2 bytes per sample
    val outputSampleCount = (inputSampleCount.toLong() * outputSampleRate / inputSampleRate).toInt()
    val output = ByteArray(outputSampleCount * 2 * channelCount)

    val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()

    for (i in 0 until outputSampleCount) {
      val srcPos = i * ratio
      val srcIndex = srcPos.toInt()
      val fraction = srcPos - srcIndex

      for (ch in 0 until channelCount) {
        // Get current and next sample
        val idx1 = (srcIndex * channelCount + ch) * 2
        val idx2 = ((srcIndex + 1) * channelCount + ch) * 2

        val sample1 = if (idx1 + 1 < input.size) {
          (input[idx1].toInt() and 0xFF) or (input[idx1 + 1].toInt() shl 8)
        } else {
          0
        }

        val sample2 = if (idx2 + 1 < input.size) {
          (input[idx2].toInt() and 0xFF) or (input[idx2 + 1].toInt() shl 8)
        } else {
          sample1
        }

        // Convert to signed 16-bit
        val s1 = if (sample1 > 32767) sample1 - 65536 else sample1
        val s2 = if (sample2 > 32767) sample2 - 65536 else sample2

        // Linear interpolation
        val interpolated = (s1 + (s2 - s1) * fraction).toInt()

        // Clamp to 16-bit range
        val clamped = interpolated.coerceIn(-32768, 32767)

        // Convert back to unsigned and write
        val outIdx = (i * channelCount + ch) * 2
        output[outIdx] = (clamped and 0xFF).toByte()
        output[outIdx + 1] = (clamped shr 8).toByte()
      }
    }

    return output
  }

  private fun convertChannelCount(
    input: ByteArray,
    inputChannels: Int,
    outputChannels: Int
  ): ByteArray {
    if (inputChannels == outputChannels) {
      return input
    }

    val sampleCount = input.size / (2 * inputChannels)
    val output = ByteArray(sampleCount * 2 * outputChannels)

    when {
      inputChannels == 1 && outputChannels == 2 -> {
        // Mono to Stereo: duplicate the channel
        for (i in 0 until sampleCount) {
          val srcIdx = i * 2
          val dstIdx = i * 4
          output[dstIdx] = input[srcIdx]
          output[dstIdx + 1] = input[srcIdx + 1]
          output[dstIdx + 2] = input[srcIdx]
          output[dstIdx + 3] = input[srcIdx + 1]
        }
      }
      inputChannels == 2 && outputChannels == 1 -> {
        // Stereo to Mono: average the channels
        for (i in 0 until sampleCount) {
          val srcIdx = i * 4
          val dstIdx = i * 2

          val left = (input[srcIdx].toInt() and 0xFF) or (input[srcIdx + 1].toInt() shl 8)
          val right = (input[srcIdx + 2].toInt() and 0xFF) or (input[srcIdx + 3].toInt() shl 8)

          val leftSigned = if (left > 32767) left - 65536 else left
          val rightSigned = if (right > 32767) right - 65536 else right

          val avg = ((leftSigned + rightSigned) / 2).coerceIn(-32768, 32767)

          output[dstIdx] = (avg and 0xFF).toByte()
          output[dstIdx + 1] = (avg shr 8).toByte()
        }
      }
      else -> {
        // Fallback: just take the first channel
        for (i in 0 until sampleCount) {
          val srcIdx = i * 2 * inputChannels
          val dstIdx = i * 2 * outputChannels
          for (ch in 0 until minOf(inputChannels, outputChannels)) {
            output[dstIdx + ch * 2] = input[srcIdx + ch * 2]
            output[dstIdx + ch * 2 + 1] = input[srcIdx + ch * 2 + 1]
          }
        }
      }
    }

    return output
  }

  private fun streamDecodeAudioFile(
    filePath: String,
    encoder: StreamingEncoder,
    isLastFile: Boolean,
    targetSampleRate: Int,
    targetChannelCount: Int
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

      val sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val sourceChannelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

      val needsResampling = sourceSampleRate != targetSampleRate
      val needsChannelConversion = sourceChannelCount != targetChannelCount

      if (needsResampling || needsChannelConversion) {
        Log.d("AudioConcat", "File: $filePath - ${sourceSampleRate}Hz ${sourceChannelCount}ch -> ${targetSampleRate}Hz ${targetChannelCount}ch")
      }

      extractor.selectTrack(audioTrackIndex)

      val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
      decoder = MediaCodec.createDecoderByType(mime)
      decoder.configure(audioFormat, null, null, 0)
      decoder.start()

      val bufferInfo = MediaCodec.BufferInfo()
      var isEOS = false

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
            var pcmData = ByteArray(bufferInfo.size)
            outputBuffer.get(pcmData)

            // Convert channel count if needed
            if (needsChannelConversion) {
              pcmData = convertChannelCount(pcmData, sourceChannelCount, targetChannelCount)
            }

            // Resample if needed
            if (needsResampling) {
              pcmData = resamplePCM16(pcmData, sourceSampleRate, targetSampleRate, targetChannelCount)
            }

            // Stream to encoder
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
              streamDecodeAudioFile(
                filePath,
                encoder,
                isLastFile,
                audioConfig.sampleRate,
                audioConfig.channelCount
              )
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
