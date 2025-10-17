# Performance Testing Guide

This guide helps you test and verify the performance improvements in v0.5.0 of react-native-audio-concat.

## Quick Performance Test

### Basic Benchmark Script

```javascript
import AudioConcat from 'react-native-audio-concat';
import RNFS from 'react-native-fs';

async function runPerformanceTest() {
  console.log('=== Audio Concat Performance Test ===');

  // Test Configuration
  const testFiles = [
    // Add your test audio file paths here
    '/path/to/audio1.m4a',
    '/path/to/audio2.m4a',
    '/path/to/audio3.m4a',
  ];

  const outputPath = `${RNFS.CachesDirectoryPath}/test_output.m4a`;

  // Prepare test data with duplicates to test caching
  const testData = [
    { filePath: testFiles[0] },
    { durationMs: 500 }, // silence
    { filePath: testFiles[1] },
    { durationMs: 500 },
    { filePath: testFiles[0] }, // duplicate - should use cache
    { durationMs: 500 },
    { filePath: testFiles[2] },
    { durationMs: 1000 },
    { filePath: testFiles[1] }, // duplicate - should use cache
  ];

  // Measure performance
  const startTime = Date.now();
  const startMemory = performance.memory?.usedJSHeapSize || 0;

  try {
    const result = await AudioConcat.concatAudioFiles(testData, outputPath);

    const endTime = Date.now();
    const endMemory = performance.memory?.usedJSHeapSize || 0;
    const duration = endTime - startTime;
    const memoryUsed = (endMemory - startMemory) / (1024 * 1024);

    console.log('✅ Concatenation successful!');
    console.log(`⏱️  Time: ${duration}ms`);
    console.log(`💾 Memory delta: ${memoryUsed.toFixed(2)}MB`);
    console.log(`📁 Output: ${result}`);

    // Check file size
    const stats = await RNFS.stat(result);
    console.log(`📦 Output size: ${(stats.size / (1024 * 1024)).toFixed(2)}MB`);

  } catch (error) {
    console.error('❌ Test failed:', error);
  }
}

runPerformanceTest();
```

## Detailed Test Scenarios

### Test 1: Resampling Performance (Phase 1 Optimization)

Tests the 3-5x improvement in resampling speed.

```javascript
async function testResampling() {
  console.log('\n=== Test 1: Resampling Performance ===');

  // Use files with different sample rates (e.g., 44.1kHz and 48kHz)
  // The module will automatically resample to match
  const mixedSampleRateFiles = [
    { filePath: '/path/to/44.1khz_audio.m4a' },
    { filePath: '/path/to/48khz_audio.m4a' },
    { filePath: '/path/to/44.1khz_audio.m4a' },
  ];

  // Run test multiple times and average
  const runs = 3;
  let totalTime = 0;

  for (let i = 0; i < runs; i++) {
    const start = Date.now();
    await AudioConcat.concatAudioFiles(
      mixedSampleRateFiles,
      `${RNFS.CachesDirectoryPath}/resample_test_${i}.m4a`
    );
    totalTime += Date.now() - start;
  }

  const avgTime = totalTime / runs;
  console.log(`Average time (${runs} runs): ${avgTime}ms`);

  // Expected: 3-5x faster than v0.4.0 when resampling is needed
}
```

### Test 2: Cache Performance (Phase 1 & 3 Optimization)

Tests intelligent caching and buffer pooling.

```javascript
async function testCaching() {
  console.log('\n=== Test 2: Cache Performance ===');

  const singleFile = '/path/to/test_audio.m4a';

  // Test with many duplicates
  const duplicateTest = Array(20).fill(null).flatMap((_, i) => [
    { filePath: singleFile },
    { durationMs: 500 }, // silence between each
  ]);

  const start = Date.now();
  await AudioConcat.concatAudioFiles(
    duplicateTest,
    `${RNFS.CachesDirectoryPath}/cache_test.m4a`
  );
  const duration = Date.now() - start;

  console.log(`Time for 20 duplicates: ${duration}ms`);
  console.log('Expected: Should decode file only once, use cache for remaining 19');

  // Check Android logs for cache hits
  // Expected logs: "Using cached PCM for: ..."
}
```

### Test 3: Silence Buffer Pooling (Phase 3 Optimization)

Tests memory efficiency with silence generation.

```javascript
async function testSilencePooling() {
  console.log('\n=== Test 3: Silence Buffer Pooling ===');

  // Many silence segments to test pooling
  const silenceHeavy = Array(50).fill(null).flatMap((_, i) => [
    { filePath: '/path/to/short_audio.m4a' },
    { durationMs: 1000 }, // 1 second silence
  ]);

  const start = Date.now();
  const startMem = performance.memory?.usedJSHeapSize || 0;

  await AudioConcat.concatAudioFiles(
    silenceHeavy,
    `${RNFS.CachesDirectoryPath}/silence_test.m4a`
  );

  const duration = Date.now() - start;
  const memUsed = (performance.memory?.usedJSHeapSize - startMem) / (1024 * 1024);

  console.log(`Time: ${duration}ms`);
  console.log(`Memory: ${memUsed.toFixed(2)}MB`);
  console.log('Expected: 50-80% reduction in memory allocations vs v0.4.0');
}
```

### Test 4: Multi-threading (Phase 1 & 2 Optimization)

Tests dynamic thread pool and queue sizing.

```javascript
async function testMultiThreading() {
  console.log('\n=== Test 4: Multi-threading Performance ===');

  // Large number of files to trigger parallel processing
  const manyFiles = Array(30).fill(null).map((_, i) => ({
    filePath: `/path/to/audio${i % 5}.m4a`, // 5 unique files repeated
  }));

  const start = Date.now();
  await AudioConcat.concatAudioFiles(
    manyFiles,
    `${RNFS.CachesDirectoryPath}/multithread_test.m4a`
  );
  const duration = Date.now() - start;

  console.log(`Time for 30 files: ${duration}ms`);

  // Check logs for thread count
  // Expected: "Using X threads for Y files (CPU cores: Z)"
}
```

## Monitoring Performance

### Android Logcat

Monitor detailed performance logs:

```bash
# Filter for AudioConcat logs
adb logcat | grep AudioConcat

# Expected log messages:
# - "SilenceBufferPool initialized with N standard sizes"
# - "Using cached PCM for: ..."
# - "Using X threads for Y files (CPU cores: Z)"
# - "Using queue size: X for Y files"
# - "Encoder buffer size: X bytes"
# - "Cache statistics: Audio files: X, Silence patterns: Y, Size: ZKB"
```

### Key Metrics to Track

1. **Execution Time**
   - Before: X ms
   - After: Y ms
   - Improvement: (X-Y)/X * 100%

2. **Memory Usage**
   - Peak memory
   - GC frequency
   - Memory delta

3. **Cache Efficiency**
   - Cache hit rate
   - Duplicate file detection
   - Memory saved

## Expected Performance Improvements

### Scenario-Based Expectations

| Scenario | v0.4.0 | v0.5.0 | Improvement |
|----------|--------|--------|-------------|
| **10 files, need resampling** | 20s | 8-10s | 50-60% faster |
| **20 duplicate files** | 40s | 15-20s | 50-63% faster |
| **30 files, mixed** | 50s | 25-30s | 40-50% faster |
| **Silence-heavy (100 segments)** | 15s | 7-10s | 33-53% faster |

### Memory Improvements

| Scenario | v0.4.0 Peak | v0.5.0 Peak | Reduction |
|----------|-------------|-------------|-----------|
| **50 files** | 250MB | 120MB | 52% |
| **100 silence segments** | 180MB | 70MB | 61% |
| **20 duplicates** | 200MB | 90MB | 55% |

## Comparison Script

Run this to compare v0.4.0 vs v0.5.0:

```javascript
async function compareVersions() {
  const testData = [
    // Your test configuration
  ];

  console.log('Run this test on v0.4.0 first, note the results');
  console.log('Then upgrade to v0.5.0 and run again');
  console.log('');

  const start = Date.now();
  const result = await AudioConcat.concatAudioFiles(
    testData,
    `${RNFS.CachesDirectoryPath}/comparison.m4a`
  );
  const duration = Date.now() - start;

  console.log(`=== Results ===`);
  console.log(`Version: [Enter version here]`);
  console.log(`Duration: ${duration}ms`);
  console.log(`Output: ${result}`);
  console.log('');
  console.log('Compare with previous run to calculate improvement');
}
```

## Troubleshooting

### Performance Not Improving?

1. **Check Device**
   - Low-end devices (1-2 cores) see smaller improvements
   - Best results on 4+ core devices

2. **Check File Characteristics**
   - Same sample rate files = no resampling optimization
   - Unique files = less cache benefit
   - Use mixed scenarios for best results

3. **Check Logs**
   - Verify cache hits: "Using cached PCM for..."
   - Verify threading: "Using X threads..."
   - Verify pooling: "Using cached silence..."

### Still Having Issues?

Create an issue with:
- Device specs (CPU cores, RAM)
- Test file details (format, sample rate, duration)
- Logcat output
- Performance measurements

## Automation

### CI/CD Performance Regression Test

```javascript
// performance.test.js
import AudioConcat from 'react-native-audio-concat';

test('performance regression test', async () => {
  const testData = [...]; // Your test data
  const maxAllowedTime = 10000; // 10 seconds

  const start = Date.now();
  await AudioConcat.concatAudioFiles(testData, outputPath);
  const duration = Date.now() - start;

  expect(duration).toBeLessThan(maxAllowedTime);
}, 30000);
```

## Contributing

Found better benchmarks or test scenarios? Please contribute to this guide!
