import { useState } from 'react';
import {
  Text,
  View,
  StyleSheet,
  Button,
  Alert,
  ScrollView,
} from 'react-native';
import PQueue from 'p-queue';
import { concatAudioFiles } from 'react-native-audio-concat';
import RNFS from 'react-native-fs';

export default function App() {
  const [mergeStatus, setMergeStatus] = useState<string>('Ready to merge');
  const [outputPath, setOutputPath] = useState<string>('');
  console.log('Document Directory Path:', RNFS.DocumentDirectoryPath);

  const handleMergeAudio = async () => {
    try {
      setMergeStatus('Merging audio files...');

      // Both iOS and Android now support various audio formats (MP3, M4A, etc.)
      // Android will decode and re-encode to M4A (AAC)

      const audio1 = `${RNFS.DocumentDirectoryPath}/speech-9ceec8fe480cd3df472a54156d628420.mp3`;
      const audio2 = `${RNFS.DocumentDirectoryPath}/speech-2526be023850e17acbde274337f6c8c1.mp3`;
      const audio3 = `${RNFS.DocumentDirectoryPath}/speech-0948f9b8d34ebe854ae4c3e0d04c22a3.mp3`;
      const audio4 = `${RNFS.DocumentDirectoryPath}/speech-8ba2ae99503b01b28345ed04dcf5e0c9.mp3`;

      // Output is always M4A (AAC) on both platforms
      // const outputFile = `${RNFS.DocumentDirectoryPath}/merged_output.m4a`;
      // const outputFile2 = `${RNFS.DocumentDirectoryPath}/merged_output2.m4a`;

      const spec = [
        { filePath: audio1 },
        { durationMs: 1500 },
        { filePath: audio1 },
        { durationMs: 1500 },
        { filePath: audio1 },
        { durationMs: 1500 },
        { filePath: audio2 },
        { durationMs: 1500 }, // 1.5 seconds silence
        { filePath: audio3 },
        { durationMs: 1500 },
        { filePath: audio4 },
      ];

      const testArr = new Array(30).fill(spec);

      const queue = new PQueue({ concurrency: 5 });

      await queue.addAll(
        testArr.map((item, index) => async () => {
          const outFile = `${RNFS.DocumentDirectoryPath}/merged_output_${index}.m4a`;
          await concatAudioFiles(item, outFile);
          console.log(`Merged file ${index} to:`, outFile);
        })
      );

      // // Merge audio files with silence between them
      // // Using the new data structure: audio file, silence, audio file, silence, audio file
      // const [result, result2] = await Promise.all([
      //   concatAudioFiles(
      //     [
      //       { filePath: audio1 },
      //       { durationMs: 1500 },
      //       { filePath: audio1 },
      //       { durationMs: 1500 },
      //       { filePath: audio1 },
      //       { durationMs: 1500 },
      //       { filePath: audio2 },
      //       { durationMs: 1500 }, // 1.5 seconds silence
      //       { filePath: audio3 },
      //       { durationMs: 1500 },
      //       { filePath: audio4 },
      //     ],
      //     outputFile
      //   ),
      //   concatAudioFiles(
      //     [
      //       { filePath: audio1 },
      //       { durationMs: 1500 },
      //       { filePath: audio1 },
      //       { durationMs: 1500 },
      //       { filePath: audio1 },
      //       { durationMs: 1500 },
      //       { filePath: audio2 },
      //       { durationMs: 1500 }, // 1.5 seconds silence
      //       { filePath: audio3 },
      //       { durationMs: 1500 },
      //       { filePath: audio4 },
      //     ],
      //     outputFile2
      //   ),
      // ]);

      console.log('Merge result paths:');
      setOutputPath('result');
      setMergeStatus('✅ Audio files merged successfully!');
      Alert.alert('Success', `Audio merged `);
    } catch (error) {
      console.error('Error merging audio:', error);
      setMergeStatus(
        `❌ Error: ${error instanceof Error ? error.message : String(error)}`
      );
      Alert.alert(
        'Error',
        `Failed to merge audio files:\n${error instanceof Error ? error.message : String(error)}`
      );
    }
  };

  const listDocumentFiles = async () => {
    try {
      const files = await RNFS.readDir(RNFS.DocumentDirectoryPath);
      const fileList = files
        .map((file) => `${file.name} (${file.size} bytes)`)
        .join('\n');
      Alert.alert('Files in Documents', fileList || 'No files found');
    } catch (error) {
      Alert.alert('Error', `Failed to list files: ${error}`);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <View style={styles.section}>
        <Text style={styles.title}>React Native Library</Text>
        <Text style={styles.subtitle}>Example App</Text>
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Audio Merge Function</Text>
        <Text style={styles.status}>{mergeStatus}</Text>
        {outputPath ? (
          <Text style={styles.path}>Output: {outputPath}</Text>
        ) : null}

        <View style={styles.buttonContainer}>
          <Button title="Merge Audio Files" onPress={handleMergeAudio} />
        </View>

        <View style={styles.buttonContainer}>
          <Button
            title="List Document Files"
            onPress={listDocumentFiles}
            color="#666"
          />
        </View>

        <Text style={styles.info}>
          📝 Note: Place test audio files in the Documents directory.
          {'\n'}iOS: audio1.m4a, audio2.m4a, audio3.m4a
          {'\n'}Android: audio1.mp3, audio2.mp3, audio3.mp3
          {'\n'}Output: merged_output.m4a (both platforms)
        </Text>
        <Text style={styles.info}>📂 Path: {RNFS.DocumentDirectoryPath}</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flexGrow: 1,
    padding: 20,
    backgroundColor: '#f5f5f5',
  },
  section: {
    backgroundColor: 'white',
    borderRadius: 10,
    padding: 20,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    textAlign: 'center',
    color: '#333',
  },
  subtitle: {
    fontSize: 16,
    textAlign: 'center',
    color: '#666',
    marginTop: 5,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 10,
    color: '#444',
  },
  result: {
    fontSize: 20,
    color: '#007AFF',
    fontWeight: '500',
  },
  status: {
    fontSize: 16,
    marginBottom: 10,
    color: '#333',
  },
  path: {
    fontSize: 12,
    color: '#666',
    marginBottom: 15,
    fontFamily: 'monospace',
  },
  buttonContainer: {
    marginVertical: 5,
  },
  info: {
    fontSize: 12,
    color: '#888',
    marginTop: 10,
    lineHeight: 18,
  },
});
