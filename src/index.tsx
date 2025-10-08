import AudioConcat from './NativeAudioConcat';

export type { AudioDataOrSilence } from './NativeAudioConcat';

export function concatAudioFiles(
  data: Array<{ filePath: string } | { durationMs: number }>,
  outputPath: string
): Promise<string> {
  return AudioConcat.concatAudioFiles(data, outputPath);
}
