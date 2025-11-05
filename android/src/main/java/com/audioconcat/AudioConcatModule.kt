package com.audioconcat

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import android.util.Log

@ReactModule(name = AudioConcatModule.NAME)
class AudioConcatModule(reactContext: ReactApplicationContext) :
  NativeAudioConcatSpec(reactContext) {

  private sealed class AudioDataOrSilence {
    data class AudioFile(val filePath: String) : AudioDataOrSilence()
    data class Silence(val durationMs: Double) : AudioDataOrSilence()
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

  private fun buildFFmpegCommand(
    parsedData: List<AudioDataOrSilence>,
    outputPath: String
  ): String {
    val filesStr = StringBuilder()
    val filterComplexStr = StringBuilder()
    var inputCount = 0
    
    // Build input files string and filter complex string
    for (item in parsedData) {
      when (item) {
        is AudioDataOrSilence.AudioFile -> {
          filesStr.append("-i \"${item.filePath}\" ")
          filterComplexStr.append("[$inputCount:a]")
          inputCount++
        }
        is AudioDataOrSilence.Silence -> {
          val duration = item.durationMs / 1000.0
          filesStr.append("-f lavfi -t $duration -i anullsrc=r=44100:cl=stereo ")
          filterComplexStr.append("[$inputCount:a]")
          inputCount++
        }
      }
    }
    
    // Complete the filter complex string
    filterComplexStr.append("concat=n=$inputCount:v=0:a=1[out]")
    
    // Build the complete command
    return "-y ${filesStr.toString()}-filter_complex \"${filterComplexStr.toString()}\" -map \"[out]\" \"$outputPath\" -loglevel level+error"
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
      Log.d("AudioConcat", "FFmpeg merge of ${parsedData.size} items")
      Log.d("AudioConcat", "Output: $outputPath")

      // Delete existing output file
      val outputFile = File(outputPath)
      if (outputFile.exists()) {
        outputFile.delete()
      }

      // Build FFmpeg command using filter_complex
      val command = buildFFmpegCommand(parsedData, outputPath)
      Log.d("AudioConcat", "FFmpeg command: $command")

      // Execute FFmpeg command
      FFmpegKit.executeAsync(command) { session ->
        val returnCode = session.returnCode
        if (ReturnCode.isSuccess(returnCode)) {
          Log.d("AudioConcat", "Successfully merged audio to $outputPath")
          promise.resolve(outputPath)
        } else {
          val output = session.output
          val error = session.failStackTrace
          Log.e("AudioConcat", "FFmpeg failed: $output")
          Log.e("AudioConcat", "Error: $error")
          promise.reject("FFMPEG_ERROR", "FFmpeg execution failed: $output", Exception(error))
        }
      }

    } catch (e: Exception) {
      Log.e("AudioConcat", "Error parsing data: ${e.message}", e)
      promise.reject("PARSE_ERROR", e.message, e)
    }
  }

  override fun convertToM4a(inputPath: String, outputPath: String, promise: Promise) {
    try {
      // Check if input file exists
      val inputFile = File(inputPath)
      if (!inputFile.exists()) {
        promise.reject("INPUT_NOT_FOUND", "Input file does not exist: $inputPath")
        return
      }

      Log.d("AudioConcat", "Converting to M4A: $inputPath")
      Log.d("AudioConcat", "Output: $outputPath")

      // Delete existing output file
      val outputFile = File(outputPath)
      if (outputFile.exists()) {
        outputFile.delete()
      }

      // Build FFmpeg command for M4A conversion with AAC codec
      val command = "-y -i \"$inputPath\" -c:a aac -b:a 128k -f mp4 \"$outputPath\" -loglevel level+error"
      Log.d("AudioConcat", "FFmpeg command: $command")

      // Execute FFmpeg command
      FFmpegKit.executeAsync(command) { session ->
        val returnCode = session.returnCode
        if (ReturnCode.isSuccess(returnCode)) {
          Log.d("AudioConcat", "Successfully converted to M4A: $outputPath")
          promise.resolve(outputPath)
        } else {
          val output = session.output
          val error = session.failStackTrace
          Log.e("AudioConcat", "FFmpeg failed: $output")
          Log.e("AudioConcat", "Error: $error")
          promise.reject("FFMPEG_ERROR", "FFmpeg conversion failed: $output", Exception(error))
        }
      }

    } catch (e: Exception) {
      Log.e("AudioConcat", "Error converting to M4A: ${e.message}", e)
      promise.reject("CONVERSION_ERROR", e.message, e)
    }
  }

  companion object {
    const val NAME = "AudioConcat"
  }
}
