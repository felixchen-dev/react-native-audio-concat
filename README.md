# react-native-audio-concat

Concatenate audio files and silence periods into a single audio file for React Native.

## Features

- ✅ Concat multiple audio files with silence periods
- ✅ Convert audio files to M4A format with AAC encoding
- ✅ Support for iOS and Android
- ✅ Output in M4A format

## Installation

```sh
npm install react-native-audio-concat
```

### iOS

```sh
cd ios && pod install
```

### Android

No additional steps required.

## Usage

### Concatenate Audio Files

```typescript
import { concatAudioFiles } from 'react-native-audio-concat';

// Concatenate audio files with silence periods
const data = [
  { filePath: '/path/to/audio1.m4a' },
  { durationMs: 500 }, // 500ms silence
  { filePath: '/path/to/audio2.m4a' },
  { durationMs: 1000 }, // 1 second silence
  { filePath: '/path/to/audio3.m4a' },
];

const outputPath = '/path/to/merged.m4a';

try {
  const result = await concatAudioFiles(data, outputPath);
  console.log('Concatenated audio file:', result);
} catch (error) {
  console.error('Failed to concatenate audio:', error);
}
```

### Convert Audio to M4A

```typescript
import { convertToM4a } from 'react-native-audio-concat';

const inputPath = '/path/to/audio.mp3';
const outputPath = '/path/to/output.m4a';

try {
  const result = await convertToM4a(inputPath, outputPath);
  console.log('Converted to M4A:', result);
} catch (error) {
  console.error('Conversion failed:', error);
}
```

## API

### `concatAudioFiles(data, outputPath)`

Concatenates audio files and silence periods into a single output file.

**Parameters:**

- `data`: `AudioDataOrSilence[]` - Array of audio files and silence periods to merge. Each item can be either:
  - `{ filePath: string }` - Path to an audio file
  - `{ durationMs: number }` - Duration of silence in milliseconds
- `outputPath`: `string` - Absolute path where the merged audio file will be saved (M4A format)

**Returns:**

- `Promise<string>` - Resolves with the output file path

### `convertToM4a(inputPath, outputPath)`

Converts an audio file to M4A format with AAC encoding.

**Parameters:**

- `inputPath`: `string` - Absolute path to the input audio file (supports MP3, WAV, FLAC, OGG, M4A, and other common formats)
- `outputPath`: `string` - Absolute path where the M4A file will be saved

**Returns:**

- `Promise<string>` - Resolves with the output file path

**Notes:**

- On Android: Uses FFmpeg with AAC codec at 128kbps bitrate
- On iOS: Uses AVFoundation's native M4A export preset

## Example

Check out the [example app](example/) for a complete working example.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
