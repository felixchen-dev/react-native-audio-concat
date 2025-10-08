import { TurboModuleRegistry, type TurboModule } from 'react-native';

export type AudioDataOrSilence = { filePath: string } | { durationMs: number };

export interface Spec extends TurboModule {
  concatAudioFiles(
    data: AudioDataOrSilence[],
    outputPath: string
  ): Promise<string>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('AudioConcat');
