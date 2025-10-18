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
import java.util.concurrent.Executors
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

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

  private data class PCMChunk(
    val data: ByteArray,
    val sequenceNumber: Int,
    val isEndOfStream: Boolean = false
  ) {
    companion object {
      fun endOfStream(sequenceNumber: Int) = PCMChunk(ByteArray(0), sequenceNumber, true)
    }
  }

  // Cache for decoded PCM data
  private data class CachedPCMData(
    val chunks: List<ByteArray>,
    val totalBytes: Long
  )

  private data class SilenceCacheKey(
    val durationMs: Double,
    val sampleRate: Int,
    val channelCount: Int
  )

  // Buffer pool for silence generation to reduce memory allocations
  private object SilenceBufferPool {
    private val pool = ConcurrentHashMap<Int, ByteArray>()
    private val standardSizes = listOf(4096, 8192, 16384, 32768, 65536, 131072)

    init {
      // Pre-allocate common silence buffer sizes
      standardSizes.forEach { size ->
        pool[size] = ByteArray(size)
      }
      Log.d("AudioConcat", "SilenceBufferPool initialized with ${standardSizes.size} standard sizes")
    }

    fun getBuffer(requestedSize: Int): ByteArray {
      // Find the smallest standard size that fits the request
      val standardSize = standardSizes.firstOrNull { it >= requestedSize }

      return if (standardSize != null) {
        // Return pooled buffer (already zeroed)
        pool.getOrPut(standardSize) { ByteArray(standardSize) }
      } else {
        // Size too large for pool, create new buffer
        ByteArray(requestedSize)
      }
    }

    fun clear() {
      pool.clear()
      Log.d("AudioConcat", "SilenceBufferPool cleared")
    }
  }

  private class PCMCache(
    private val shouldCacheFile: Set<String>,
    private val shouldCacheSilence: Set<SilenceCacheKey>
  ) {
    private val audioFileCache = ConcurrentHashMap<String, CachedPCMData>()
    private val silenceCache = ConcurrentHashMap<SilenceCacheKey, ByteArray>()
    private var currentCacheSizeBytes = 0L

    // Dynamic cache size based on available memory
    private val maxCacheSizeBytes: Long
      get() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory

        // Use 20% of available memory for cache, but constrain between 50MB and 200MB
        val dynamicCacheMB = (availableMemory / (1024 * 1024) * 0.2).toLong()
        val cacheMB = dynamicCacheMB.coerceIn(50, 200)

        return cacheMB * 1024 * 1024
      }

    fun getAudioFile(filePath: String): CachedPCMData? {
      return audioFileCache[filePath]
    }

    fun putAudioFile(filePath: String, data: CachedPCMData) {
      // Only cache if this file appears multiple times
      if (!shouldCacheFile.contains(filePath)) {
        return
      }

      // Check cache size limit (dynamic)
      if (currentCacheSizeBytes + data.totalBytes > maxCacheSizeBytes) {
        val maxCacheMB = maxCacheSizeBytes / (1024 * 1024)
        Log.d("AudioConcat", "Cache full ($maxCacheMB MB), not caching: $filePath")
        return
      }

      audioFileCache[filePath] = data
      currentCacheSizeBytes += data.totalBytes
      Log.d("AudioConcat", "Cached audio file: $filePath (${data.totalBytes / 1024}KB, total: ${currentCacheSizeBytes / 1024}KB)")
    }

    fun getSilence(key: SilenceCacheKey): ByteArray? {
      return silenceCache[key]
    }

    fun putSilence(key: SilenceCacheKey, data: ByteArray) {
      // Only cache if this silence pattern appears multiple times
      if (!shouldCacheSilence.contains(key)) {
        return
      }

      silenceCache[key] = data
      Log.d("AudioConcat", "Cached silence: ${key.durationMs}ms")
    }

    fun clear() {
      audioFileCache.clear()
      silenceCache.clear()
      currentCacheSizeBytes = 0
      Log.d("AudioConcat", "Cache cleared")
    }

    fun getStats(): String {
      return "Audio files: ${audioFileCache.size}, Silence patterns: ${silenceCache.size}, Size: ${currentCacheSizeBytes / 1024}KB"
    }
  }

  // Helper class to manage MediaCodec decoder reuse
  private class ReusableDecoder {
    private var decoder: MediaCodec? = null
    private var currentMimeType: String? = null
    private var currentFormat: MediaFormat? = null

    fun getOrCreateDecoder(mimeType: String, format: MediaFormat): MediaCodec {
      // Check if we can reuse the existing decoder
      if (decoder != null && currentMimeType == mimeType && formatsCompatible(currentFormat, format)) {
        // Flush the decoder to reset its state
        try {
          decoder!!.flush()
          Log.d("AudioConcat", "  Reused decoder for $mimeType")
          return decoder!!
        } catch (e: Exception) {
          Log.w("AudioConcat", "Failed to flush decoder, recreating: ${e.message}")
          release()
        }
      }

      // Need to create a new decoder
      release() // Release old one if exists

      val newDecoder = MediaCodec.createDecoderByType(mimeType)
      newDecoder.configure(format, null, null, 0)
      newDecoder.start()

      decoder = newDecoder
      currentMimeType = mimeType
      currentFormat = format

      Log.d("AudioConcat", "  Created new decoder for $mimeType")
      return newDecoder
    }

    private fun formatsCompatible(format1: MediaFormat?, format2: MediaFormat): Boolean {
      if (format1 == null) return false

      // Check key format properties
      return try {
        format1.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format2.getInteger(MediaFormat.KEY_SAMPLE_RATE) &&
        format1.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format2.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      } catch (e: Exception) {
        false
      }
    }

    fun release() {
      decoder?.let {
        try {
          it.stop()
          it.release()
        } catch (e: Exception) {
          Log.w("AudioConcat", "Error releasing decoder: ${e.message}")
        }
      }
      decoder = null
      currentMimeType = null
      currentFormat = null
    }
  }

  // Thread-safe decoder pool for parallel processing
  private class DecoderPool {
    private val decoders = ConcurrentHashMap<Long, ReusableDecoder>()

    fun getDecoderForCurrentThread(): ReusableDecoder {
      val threadId = Thread.currentThread().id
      return decoders.getOrPut(threadId) {
        Log.d("AudioConcat", "  Created decoder for thread $threadId")
        ReusableDecoder()
      }
    }

    fun releaseAll() {
      decoders.values.forEach { it.release() }
      decoders.clear()
      Log.d("AudioConcat", "Released all pooled decoders")
    }
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
    private val maxChunkSize: Int

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

      // Optimized buffer size based on audio parameters
      // Target: ~1024 samples per frame for optimal AAC encoding
      val samplesPerFrame = 1024
      val bytesPerSample = channelCount * 2 // 16-bit PCM
      val optimalBufferSize = samplesPerFrame * bytesPerSample
      // Use at least the optimal size, but allow for some overhead
      val bufferSize = (optimalBufferSize * 1.5).toInt().coerceAtLeast(16384)
      outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)

      // Store for use in encodePCMChunk
      this.maxChunkSize = bufferSize

      Log.d("AudioConcat", "Encoder buffer size: $bufferSize bytes (${samplesPerFrame} samples, ${sampleRate}Hz, ${channelCount}ch)")

      encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
      encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      encoder.start()

      muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodePCMChunk(pcmData: ByteArray, isLast: Boolean = false): Boolean {
      // Split large PCM data into smaller chunks that fit in encoder buffer (use configured size)
      var offset = 0

      while (offset < pcmData.size) {
        val chunkSize = minOf(maxChunkSize, pcmData.size - offset)
        val isLastChunk = (offset + chunkSize >= pcmData.size) && isLast

        // Feed PCM data chunk to encoder (reduced timeout for better throughput)
        val inputBufferIndex = encoder.dequeueInputBuffer(1000)
        if (inputBufferIndex >= 0) {
          val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
          val bufferCapacity = inputBuffer.capacity()

          // Ensure chunk fits in buffer
          val actualChunkSize = minOf(chunkSize, bufferCapacity)

          inputBuffer.clear()
          inputBuffer.put(pcmData, offset, actualChunkSize)

          val presentationTimeUs = totalPresentationTimeUs
          totalPresentationTimeUs += (actualChunkSize.toLong() * 1_000_000) / (sampleRate * channelCount * 2)

          val flags = if (isLastChunk) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
          encoder.queueInputBuffer(inputBufferIndex, 0, actualChunkSize, presentationTimeUs, flags)

          offset += actualChunkSize
        } else {
          // Buffer not available, drain first
          drainEncoder(false)
        }

        // Drain encoder output periodically
        if (offset < pcmData.size || !isLastChunk) {
          drainEncoder(false)
        }
      }

      // Final drain if last chunk
      if (isLast) {
        drainEncoder(true)
      }

      return true
    }

    private fun drainEncoder(endOfStream: Boolean) {
      while (true) {
        // Use shorter timeout for better responsiveness
        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 1000 else 0)

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
      // Signal end of stream (reduced timeout)
      val inputBufferIndex = encoder.dequeueInputBuffer(1000)
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

    val startTime = System.currentTimeMillis()
    val inputSampleCount = input.size / (2 * channelCount) // 16-bit = 2 bytes per sample
    val outputSampleCount = (inputSampleCount.toLong() * outputSampleRate / inputSampleRate).toInt()
    val output = ByteArray(outputSampleCount * 2 * channelCount)

    // Helper function to read a sample with bounds checking
    fun readSample(sampleIndex: Int, channel: Int): Int {
      val clampedIndex = sampleIndex.coerceIn(0, inputSampleCount - 1)
      val idx = (clampedIndex * channelCount + channel) * 2
      val unsigned = (input[idx].toInt() and 0xFF) or (input[idx + 1].toInt() shl 8)
      return if (unsigned > 32767) unsigned - 65536 else unsigned
    }

    // Use floating-point for better accuracy than fixed-point
    val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()

    for (i in 0 until outputSampleCount) {
      val srcPos = i * ratio
      val srcIndex = srcPos.toInt()
      val fraction = srcPos - srcIndex // Fractional part (0.0 to 1.0)

      for (ch in 0 until channelCount) {
        // Linear interpolation with floating-point precision
        val s1 = readSample(srcIndex, ch).toDouble()
        val s2 = readSample(srcIndex + 1, ch).toDouble()

        // Linear interpolation: s1 + (s2 - s1) * fraction
        val interpolated = s1 + (s2 - s1) * fraction

        // Clamp to 16-bit range
        val clamped = interpolated.toInt().coerceIn(-32768, 32767)

        // Write to output (little-endian)
        val outIdx = (i * channelCount + ch) * 2
        output[outIdx] = (clamped and 0xFF).toByte()
        output[outIdx + 1] = (clamped shr 8).toByte()
      }
    }

    val elapsedTime = System.currentTimeMillis() - startTime
    Log.d("AudioConcat", "  Resampled ${inputSampleRate}Hz→${outputSampleRate}Hz, ${input.size / 1024}KB→${output.size / 1024}KB in ${elapsedTime}ms")

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

          // Use bit shift instead of division for better performance (x / 2 = x >> 1)
          val avg = ((leftSigned + rightSigned) shr 1).coerceIn(-32768, 32767)

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

  private fun parallelDecodeToQueue(
    filePath: String,
    queue: BlockingQueue<PCMChunk>,
    sequenceStart: AtomicInteger,
    targetSampleRate: Int,
    targetChannelCount: Int,
    latch: CountDownLatch,
    cache: PCMCache,
    decoderPool: DecoderPool? = null
  ) {
    try {
      // Check cache first
      val cachedData = cache.getAudioFile(filePath)
      if (cachedData != null) {
        Log.d("AudioConcat", "Using cached PCM for: $filePath")
        // Put cached chunks to queue
        for (chunk in cachedData.chunks) {
          val seqNum = sequenceStart.getAndIncrement()
          queue.put(PCMChunk(chunk, seqNum))
        }
        latch.countDown()
        return
      }

      val extractor = MediaExtractor()
      var decoder: MediaCodec? = null
      val decodedChunks = mutableListOf<ByteArray>()
      var totalBytes = 0L
      val shouldReleaseDecoder = (decoderPool == null) // Only release if not using pool

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
          Log.d("AudioConcat", "Parallel decode: $filePath - ${sourceSampleRate}Hz ${sourceChannelCount}ch -> ${targetSampleRate}Hz ${targetChannelCount}ch")
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!

        // Use decoder pool if available, otherwise create new decoder
        decoder = if (decoderPool != null) {
          val reusableDecoder = decoderPool.getDecoderForCurrentThread()
          reusableDecoder.getOrCreateDecoder(mime, audioFormat)
        } else {
          val newDecoder = MediaCodec.createDecoderByType(mime)
          newDecoder.configure(audioFormat, null, null, 0)
          newDecoder.start()
          newDecoder
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
          // Feed input to decoder (reduced timeout for faster processing)
          val inputBufferIndex = decoder.dequeueInputBuffer(1000)
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

          // Get PCM output from decoder and put to queue (reduced timeout)
          val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000)
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

              // Optimization: avoid unnecessary clone() - store original for caching
              decodedChunks.add(pcmData)
              totalBytes += pcmData.size

              // Put a clone to queue (queue might modify it)
              val seqNum = sequenceStart.getAndIncrement()
              queue.put(PCMChunk(pcmData.clone(), seqNum))
            }

            decoder.releaseOutputBuffer(outputBufferIndex, false)

            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              isEOS = true
            }
          }
        }

        // Cache the decoded data
        if (decodedChunks.isNotEmpty()) {
          cache.putAudioFile(filePath, CachedPCMData(decodedChunks, totalBytes))
        }

      } finally {
        // Only stop/release decoder if not using pool
        if (shouldReleaseDecoder) {
          decoder?.stop()
          decoder?.release()
        }
        extractor.release()
      }
    } catch (e: Exception) {
      Log.e("AudioConcat", "Error in parallel decode: ${e.message}", e)
      throw e
    } finally {
      latch.countDown()
    }
  }

  private fun streamDecodeAudioFile(
    filePath: String,
    encoder: StreamingEncoder,
    isLastFile: Boolean,
    targetSampleRate: Int,
    targetChannelCount: Int,
    reusableDecoder: ReusableDecoder? = null
  ) {
    val startTime = System.currentTimeMillis()
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    val shouldReleaseDecoder = (reusableDecoder == null) // Only release if not reusing

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

      // Use reusable decoder if provided, otherwise create a new one
      decoder = if (reusableDecoder != null) {
        reusableDecoder.getOrCreateDecoder(mime, audioFormat)
      } else {
        val newDecoder = MediaCodec.createDecoderByType(mime)
        newDecoder.configure(audioFormat, null, null, 0)
        newDecoder.start()
        newDecoder
      }

      val bufferInfo = MediaCodec.BufferInfo()
      var isEOS = false

      while (!isEOS) {
        // Feed input to decoder (reduced timeout for faster processing)
        val inputBufferIndex = decoder.dequeueInputBuffer(1000)
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

        // Get PCM output from decoder and feed to encoder (reduced timeout)
        val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000)
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
      // Only stop/release decoder if we created it locally (not reusing)
      if (shouldReleaseDecoder) {
        decoder?.stop()
        decoder?.release()
      }
      extractor.release()
      val elapsedTime = System.currentTimeMillis() - startTime
      Log.d("AudioConcat", "  Decoded file in ${elapsedTime}ms")
    }
  }

  private fun streamEncodeSilence(
    durationMs: Double,
    encoder: StreamingEncoder,
    sampleRate: Int,
    channelCount: Int,
    cache: PCMCache
  ) {
    val cacheKey = SilenceCacheKey(durationMs, sampleRate, channelCount)

    // Check cache first
    val cachedSilence = cache.getSilence(cacheKey)
    if (cachedSilence != null) {
      Log.d("AudioConcat", "Using cached silence: ${durationMs}ms")
      encoder.encodePCMChunk(cachedSilence, false)
      return
    }

    // Generate silence
    val totalSamples = ((durationMs / 1000.0) * sampleRate).toInt()
    val bytesPerSample = channelCount * 2 // 16-bit stereo
    val totalBytes = totalSamples * bytesPerSample

    // For short silence (< 5 seconds), cache as single chunk
    if (durationMs < 5000) {
      // Use buffer pool to avoid allocation
      val pooledBuffer = SilenceBufferPool.getBuffer(totalBytes)
      val silenceData = if (pooledBuffer.size == totalBytes) {
        pooledBuffer
      } else {
        // Copy only the needed portion
        pooledBuffer.copyOf(totalBytes)
      }
      cache.putSilence(cacheKey, silenceData)
      encoder.encodePCMChunk(silenceData, false)
    } else {
      // For longer silence, process in chunks without caching using pooled buffers
      val chunkSamples = 16384
      var samplesRemaining = totalSamples

      while (samplesRemaining > 0) {
        val currentChunkSamples = minOf(chunkSamples, samplesRemaining)
        val chunkBytes = currentChunkSamples * bytesPerSample

        // Use pooled buffer for chunk
        val pooledBuffer = SilenceBufferPool.getBuffer(chunkBytes)
        val silenceChunk = if (pooledBuffer.size == chunkBytes) {
          pooledBuffer
        } else {
          pooledBuffer.copyOf(chunkBytes)
        }

        encoder.encodePCMChunk(silenceChunk, false)
        samplesRemaining -= currentChunkSamples
      }
    }
  }

  private fun getOptimalThreadCount(audioFileCount: Int): Int {
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val optimalThreads = when {
      cpuCores <= 2 -> 2
      cpuCores <= 4 -> 3
      cpuCores <= 8 -> 4
      else -> 6
    }
    // Don't create more threads than files to process
    return optimalThreads.coerceAtMost(audioFileCount)
  }

  private fun getOptimalQueueSize(audioFileCount: Int): Int {
    // Dynamic queue size based on number of files to prevent memory waste or blocking
    return when {
      audioFileCount <= 5 -> 20
      audioFileCount <= 20 -> 50
      audioFileCount <= 50 -> 100
      else -> 150
    }
  }

  private fun parallelProcessAudioFiles(
    audioFiles: List<Pair<Int, String>>, // (index, filePath)
    encoder: StreamingEncoder,
    targetSampleRate: Int,
    targetChannelCount: Int,
    cache: PCMCache,
    numThreads: Int = 3
  ) {
    if (audioFiles.isEmpty()) return

    // Group consecutive duplicate files
    val optimizedFiles = mutableListOf<Pair<Int, String>>()
    val consecutiveDuplicates = mutableMapOf<Int, Int>() // originalIndex -> count

    var i = 0
    while (i < audioFiles.size) {
      val (index, filePath) = audioFiles[i]
      var count = 1

      // Check for consecutive duplicates
      while (i + count < audioFiles.size && audioFiles[i + count].second == filePath) {
        count++
      }

      if (count > 1) {
        Log.d("AudioConcat", "Detected $count consecutive occurrences of: $filePath")
        optimizedFiles.add(Pair(index, filePath))
        consecutiveDuplicates[optimizedFiles.size - 1] = count
      } else {
        optimizedFiles.add(Pair(index, filePath))
      }

      i += count
    }

    val queueSize = getOptimalQueueSize(optimizedFiles.size)
    val pcmQueue = LinkedBlockingQueue<PCMChunk>(queueSize)
    Log.d("AudioConcat", "Using queue size: $queueSize for ${optimizedFiles.size} files")
    val executor = Executors.newFixedThreadPool(numThreads)
    val latch = CountDownLatch(optimizedFiles.size)
    val sequenceCounter = AtomicInteger(0)

    // Create decoder pool for reuse across threads
    val decoderPool = DecoderPool()
    Log.d("AudioConcat", "Created decoder pool for parallel processing ($numThreads threads)")

    try {
      // Submit decode tasks for unique files only
      optimizedFiles.forEachIndexed { optIndex, (index, filePath) ->
        executor.submit {
          try {
            val fileSequenceStart = AtomicInteger(sequenceCounter.get())
            sequenceCounter.addAndGet(1000000)

            Log.d("AudioConcat", "Starting parallel decode [$index]: $filePath")
            parallelDecodeToQueue(filePath, pcmQueue, fileSequenceStart, targetSampleRate, targetChannelCount, latch, cache, decoderPool)

            // Mark end with duplicate count
            val repeatCount = consecutiveDuplicates[optIndex] ?: 1
            val endSeqNum = fileSequenceStart.get()
            pcmQueue.put(PCMChunk(ByteArray(0), endSeqNum, true)) // endOfStream marker with repeat count

          } catch (e: Exception) {
            Log.e("AudioConcat", "Error decoding file $filePath: ${e.message}", e)
            latch.countDown()
          }
        }
      }

      // Consumer thread: encode in order
      var filesCompleted = 0
      var cachedChunks = mutableListOf<ByteArray>()
      var isCollectingChunks = false

      while (filesCompleted < optimizedFiles.size) {
        val chunk = pcmQueue.take()

        if (chunk.isEndOfStream) {
          val optIndex = filesCompleted
          val repeatCount = consecutiveDuplicates[optIndex] ?: 1

          if (repeatCount > 1 && cachedChunks.isNotEmpty()) {
            // Repeat the cached chunks
            Log.d("AudioConcat", "Repeating cached chunks ${repeatCount - 1} more times")
            repeat(repeatCount - 1) {
              cachedChunks.forEach { data ->
                encoder.encodePCMChunk(data, false)
              }
            }
            cachedChunks.clear()
          }

          filesCompleted++
          isCollectingChunks = false
          Log.d("AudioConcat", "Completed file $filesCompleted/${optimizedFiles.size}")
          continue
        }

        // Encode chunk
        encoder.encodePCMChunk(chunk.data, false)

        // Cache chunks for consecutive duplicates
        val optIndex = filesCompleted
        if (consecutiveDuplicates.containsKey(optIndex)) {
          cachedChunks.add(chunk.data.clone())
        }
      }

      // Wait for all decode tasks to complete
      latch.await()
      Log.d("AudioConcat", "All parallel decode tasks completed")

    } finally {
      decoderPool.releaseAll()
      executor.shutdown()
    }
  }

  private data class InterleavedPattern(
    val filePath: String,
    val silenceKey: SilenceCacheKey?,
    val indices: List<Int>, // Indices where this pattern occurs
    val repeatCount: Int
  )

  private data class DuplicateAnalysis(
    val duplicateFiles: Set<String>,
    val duplicateSilence: Set<SilenceCacheKey>,
    val fileOccurrences: Map<String, List<Int>>, // filePath -> list of indices
    val silenceOccurrences: Map<SilenceCacheKey, List<Int>>,
    val interleavedPatterns: List<InterleavedPattern>
  )

  private fun analyzeDuplicates(
    parsedData: List<AudioDataOrSilence>,
    audioConfig: AudioConfig
  ): DuplicateAnalysis {
    val fileCounts = mutableMapOf<String, MutableList<Int>>()
    val silenceCounts = mutableMapOf<SilenceCacheKey, MutableList<Int>>()

    parsedData.forEachIndexed { index, item ->
      when (item) {
        is AudioDataOrSilence.AudioFile -> {
          fileCounts.getOrPut(item.filePath) { mutableListOf() }.add(index)
        }
        is AudioDataOrSilence.Silence -> {
          val key = SilenceCacheKey(item.durationMs, audioConfig.sampleRate, audioConfig.channelCount)
          silenceCounts.getOrPut(key) { mutableListOf() }.add(index)
        }
      }
    }

    val duplicateFiles = fileCounts.filter { it.value.size > 1 }.keys.toSet()
    val duplicateSilence = silenceCounts.filter { it.value.size > 1 }.keys.toSet()

    // Detect interleaved patterns: file -> silence -> file -> silence -> file
    val interleavedPatterns = mutableListOf<InterleavedPattern>()

    var i = 0
    while (i < parsedData.size - 2) {
      if (parsedData[i] is AudioDataOrSilence.AudioFile &&
          parsedData[i + 1] is AudioDataOrSilence.Silence &&
          parsedData[i + 2] is AudioDataOrSilence.AudioFile) {

        val file1 = (parsedData[i] as AudioDataOrSilence.AudioFile).filePath
        val silence = parsedData[i + 1] as AudioDataOrSilence.Silence
        val file2 = (parsedData[i + 2] as AudioDataOrSilence.AudioFile).filePath
        val silenceKey = SilenceCacheKey(silence.durationMs, audioConfig.sampleRate, audioConfig.channelCount)

        // Check if it's the same file with silence separator
        if (file1 == file2) {
          var count = 1
          var currentIndex = i
          val indices = mutableListOf(i)

          // Count how many times this pattern repeats
          while (currentIndex + 2 < parsedData.size &&
                 parsedData[currentIndex + 2] is AudioDataOrSilence.AudioFile &&
                 (parsedData[currentIndex + 2] as AudioDataOrSilence.AudioFile).filePath == file1) {

            // Check if there's a silence in between
            if (currentIndex + 3 < parsedData.size &&
                parsedData[currentIndex + 3] is AudioDataOrSilence.Silence) {
              val nextSilence = parsedData[currentIndex + 3] as AudioDataOrSilence.Silence
              val nextSilenceKey = SilenceCacheKey(nextSilence.durationMs, audioConfig.sampleRate, audioConfig.channelCount)

              if (nextSilenceKey == silenceKey) {
                count++
                currentIndex += 2
                indices.add(currentIndex)
              } else {
                break
              }
            } else {
              // Last file in the pattern (no silence after)
              count++
              indices.add(currentIndex + 2)
              break
            }
          }

          if (count >= 2) {
            interleavedPatterns.add(InterleavedPattern(file1, silenceKey, indices, count))
            Log.d("AudioConcat", "Detected interleaved pattern: '$file1' + ${silenceKey.durationMs}ms silence, repeats $count times")
            i = currentIndex + 2 // Skip processed items
            continue
          }
        }
      }
      i++
    }

    Log.d("AudioConcat", "Duplicate analysis: ${duplicateFiles.size} files, ${duplicateSilence.size} silence patterns, ${interleavedPatterns.size} interleaved patterns")
    duplicateFiles.forEach { file ->
      Log.d("AudioConcat", "  File '$file' appears ${fileCounts[file]?.size} times")
    }
    duplicateSilence.forEach { key ->
      Log.d("AudioConcat", "  Silence ${key.durationMs}ms appears ${silenceCounts[key]?.size} times")
    }

    return DuplicateAnalysis(duplicateFiles, duplicateSilence, fileCounts, silenceCounts, interleavedPatterns)
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
    val totalStartTime = System.currentTimeMillis()
    Log.d("AudioConcat", "========== Audio Concat Started ==========")

    try {
      if (data.size() == 0) {
        promise.reject("EMPTY_DATA", "Data array is empty")
        return
      }

      // Parse data
      val parseStartTime = System.currentTimeMillis()
      val parsedData = parseAudioData(data)
      val parseTime = System.currentTimeMillis() - parseStartTime
      Log.d("AudioConcat", "✓ Parsed ${parsedData.size} items in ${parseTime}ms")
      Log.d("AudioConcat", "Output: $outputPath")

      // Get audio config from first audio file
      val configStartTime = System.currentTimeMillis()
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

      val configTime = System.currentTimeMillis() - configStartTime

      // Force output sample rate to 24kHz for optimal performance
      val outputSampleRate = 24000
      Log.d("AudioConcat", "✓ Extracted audio config in ${configTime}ms: ${audioConfig.channelCount}ch, ${audioConfig.bitRate}bps")
      Log.d("AudioConcat", "Output sample rate: ${outputSampleRate}Hz (24kHz optimized)")

      // Create modified config with fixed sample rate
      val outputAudioConfig = AudioConfig(outputSampleRate, audioConfig.channelCount, audioConfig.bitRate)

      // Analyze duplicates to determine cache strategy
      val analysisStartTime = System.currentTimeMillis()
      val duplicateAnalysis = analyzeDuplicates(parsedData, outputAudioConfig)
      val analysisTime = System.currentTimeMillis() - analysisStartTime
      Log.d("AudioConcat", "✓ Analyzed duplicates in ${analysisTime}ms")

      // Create cache instance with intelligent caching strategy
      val cache = PCMCache(duplicateAnalysis.duplicateFiles, duplicateAnalysis.duplicateSilence)

      // Delete existing output file
      val outputFile = File(outputPath)
      if (outputFile.exists()) {
        outputFile.delete()
      }

      // Create streaming encoder with fixed 24kHz sample rate
      val encoder = StreamingEncoder(
        outputSampleRate,
        audioConfig.channelCount,
        audioConfig.bitRate,
        outputPath
      )

      try {
        // Separate audio files and other items (silence)
        val audioFileItems = mutableListOf<Pair<Int, String>>()
        val nonAudioItems = mutableListOf<Pair<Int, AudioDataOrSilence>>()

        for ((index, item) in parsedData.withIndex()) {
          when (item) {
            is AudioDataOrSilence.AudioFile -> {
              audioFileItems.add(Pair(index, item.filePath))
            }
            is AudioDataOrSilence.Silence -> {
              nonAudioItems.add(Pair(index, item))
            }
          }
        }

        // Decide whether to use parallel or sequential processing
        val useParallel = audioFileItems.size >= 10 // Use parallel for 10+ files
        val processingStartTime = System.currentTimeMillis()

        if (useParallel) {
          Log.d("AudioConcat", "→ Using parallel processing for ${audioFileItems.size} audio files")

          // Process interleaved patterns optimally
          val processedIndices = mutableSetOf<Int>()

          // First, handle all interleaved patterns
          duplicateAnalysis.interleavedPatterns.forEach { pattern ->
            Log.d("AudioConcat", "Processing interleaved pattern: ${pattern.filePath}, ${pattern.repeatCount} repetitions")

            // Decode the file once
            val filePath = pattern.filePath
            val cachedData = cache.getAudioFile(filePath)

            val pcmChunks = if (cachedData != null) {
              Log.d("AudioConcat", "Using cached PCM for interleaved pattern: $filePath")
              cachedData.chunks
            } else {
              // Decode once and store
              val chunks = mutableListOf<ByteArray>()
              val tempQueue = LinkedBlockingQueue<PCMChunk>(100)
              val latch = CountDownLatch(1)
              val seqStart = AtomicInteger(0)

              parallelDecodeToQueue(filePath, tempQueue, seqStart, outputSampleRate, audioConfig.channelCount, latch, cache)

              // Collect chunks
              var collecting = true
              while (collecting) {
                val chunk = tempQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk != null) {
                  if (!chunk.isEndOfStream) {
                    chunks.add(chunk.data)
                  } else {
                    collecting = false
                  }
                } else if (latch.count == 0L) {
                  collecting = false
                }
              }

              latch.await()
              chunks
            }

            // Get silence PCM
            val silencePCM = pattern.silenceKey?.let { cache.getSilence(it) }
              ?: pattern.silenceKey?.let {
                val totalSamples = ((it.durationMs / 1000.0) * it.sampleRate).toInt()
                val bytesPerSample = it.channelCount * 2
                ByteArray(totalSamples * bytesPerSample)
              }

            // Encode the pattern: file -> silence -> file -> silence -> ...
            repeat(pattern.repeatCount) { iteration ->
              // Encode file
              pcmChunks.forEach { chunk ->
                encoder.encodePCMChunk(chunk, false)
              }

              // Encode silence (except after the last file)
              if (iteration < pattern.repeatCount - 1 && silencePCM != null) {
                encoder.encodePCMChunk(silencePCM, false)
              }
            }

            // Mark these indices as processed
            pattern.indices.forEach { idx ->
              processedIndices.add(idx)
              if (idx + 1 < parsedData.size && parsedData[idx + 1] is AudioDataOrSilence.Silence) {
                processedIndices.add(idx + 1)
              }
            }
          }

          // Then process remaining items normally
          var audioFileIdx = 0
          for ((index, item) in parsedData.withIndex()) {
            if (processedIndices.contains(index)) {
              if (item is AudioDataOrSilence.AudioFile) audioFileIdx++
              continue
            }

            when (item) {
              is AudioDataOrSilence.AudioFile -> {
                // Collect consecutive audio files for parallel processing
                val consecutiveFiles = mutableListOf<Pair<Int, String>>()
                var currentIdx = audioFileIdx

                while (currentIdx < audioFileItems.size) {
                  val (itemIdx, filePath) = audioFileItems[currentIdx]
                  if (processedIndices.contains(itemIdx)) {
                    currentIdx++
                    continue
                  }
                  if (itemIdx != index + (currentIdx - audioFileIdx)) break
                  consecutiveFiles.add(Pair(itemIdx, filePath))
                  currentIdx++
                }

                if (consecutiveFiles.isNotEmpty()) {
                  val optimalThreads = getOptimalThreadCount(consecutiveFiles.size)
                  Log.d("AudioConcat", "Using $optimalThreads threads for ${consecutiveFiles.size} files (CPU cores: ${Runtime.getRuntime().availableProcessors()})")
                  parallelProcessAudioFiles(
                    consecutiveFiles,
                    encoder,
                    outputSampleRate,
                    audioConfig.channelCount,
                    cache,
                    numThreads = optimalThreads
                  )
                  audioFileIdx = currentIdx
                }
              }

              is AudioDataOrSilence.Silence -> {
                val durationMs = item.durationMs
                Log.d("AudioConcat", "Item $index: Streaming silence ${durationMs}ms")
                streamEncodeSilence(
                  durationMs,
                  encoder,
                  outputSampleRate,
                  audioConfig.channelCount,
                  cache
                )
              }
            }
          }
        } else {
          Log.d("AudioConcat", "→ Using sequential processing for ${audioFileItems.size} audio files")

          // Create a reusable decoder for sequential processing
          val reusableDecoder = ReusableDecoder()
          Log.d("AudioConcat", "Created reusable decoder for sequential processing")

          try {
            // Process each item sequentially (with decoder reuse)
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
                    outputSampleRate,
                    audioConfig.channelCount,
                    reusableDecoder
                  )
                }

                is AudioDataOrSilence.Silence -> {
                  val durationMs = item.durationMs
                  Log.d("AudioConcat", "Item $index: Streaming silence ${durationMs}ms")

                  streamEncodeSilence(
                    durationMs,
                    encoder,
                    outputSampleRate,
                    audioConfig.channelCount,
                    cache
                  )
                }
              }
            }
          } finally {
            // Release the reusable decoder when done
            reusableDecoder.release()
            Log.d("AudioConcat", "Released reusable decoder")
          }
        }

        val processingTime = System.currentTimeMillis() - processingStartTime
        Log.d("AudioConcat", "✓ Processing completed in ${processingTime}ms")

        // Finish encoding
        val encodingFinishStartTime = System.currentTimeMillis()
        encoder.finish()
        val encodingFinishTime = System.currentTimeMillis() - encodingFinishStartTime
        Log.d("AudioConcat", "✓ Encoding finalized in ${encodingFinishTime}ms")

        // Log cache statistics
        Log.d("AudioConcat", "Cache statistics: ${cache.getStats()}")

        val totalTime = System.currentTimeMillis() - totalStartTime
        Log.d("AudioConcat", "========== Total Time: ${totalTime}ms (${totalTime / 1000.0}s) ==========")
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
