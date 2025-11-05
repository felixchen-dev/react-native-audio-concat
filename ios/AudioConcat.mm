#import "AudioConcat.h"
#import <AVFoundation/AVFoundation.h>

@implementation AudioConcat

- (void)concatAudioFiles:(NSArray *)data
              outputPath:(NSString *)outputPath
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject
{
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            AVMutableComposition *composition = [AVMutableComposition composition];
            AVMutableCompositionTrack *audioTrack = [composition addMutableTrackWithMediaType:AVMediaTypeAudio
                                                                              preferredTrackID:kCMPersistentTrackID_Invalid];

            CMTime currentTime = kCMTimeZero;

            for (NSDictionary *item in data) {
                if (item[@"filePath"]) {
                    // Add audio file
                    NSString *filePath = item[@"filePath"];
                    NSURL *fileURL = [NSURL fileURLWithPath:filePath];

                    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:fileURL options:nil];
                    NSArray *tracks = [asset tracksWithMediaType:AVMediaTypeAudio];

                    if (tracks.count == 0) {
                        reject(@"ERR_NO_AUDIO_TRACK", [NSString stringWithFormat:@"No audio track found in file: %@", filePath], nil);
                        return;
                    }

                    AVAssetTrack *track = tracks[0];
                    CMTimeRange timeRange = CMTimeRangeMake(kCMTimeZero, asset.duration);

                    NSError *error = nil;
                    [audioTrack insertTimeRange:timeRange
                                        ofTrack:track
                                         atTime:currentTime
                                          error:&error];

                    if (error) {
                        reject(@"ERR_INSERT_TRACK", error.localizedDescription, error);
                        return;
                    }

                    currentTime = CMTimeAdd(currentTime, asset.duration);

                } else if (item[@"durationMs"]) {
                    // Add silence period
                    NSNumber *durationMs = item[@"durationMs"];
                    double durationSeconds = [durationMs doubleValue] / 1000.0;
                    CMTime silenceDuration = CMTimeMakeWithSeconds(durationSeconds, 600);

                    currentTime = CMTimeAdd(currentTime, silenceDuration);
                }
            }

            // Export the composition
            NSURL *outputURL = [NSURL fileURLWithPath:outputPath];

            // Remove existing file if it exists
            [[NSFileManager defaultManager] removeItemAtURL:outputURL error:nil];

            AVAssetExportSession *exportSession = [[AVAssetExportSession alloc] initWithAsset:composition
                                                                                    presetName:AVAssetExportPresetAppleM4A];
            exportSession.outputURL = outputURL;
            exportSession.outputFileType = AVFileTypeAppleM4A;

            [exportSession exportAsynchronouslyWithCompletionHandler:^{
                switch (exportSession.status) {
                    case AVAssetExportSessionStatusCompleted:
                        resolve(outputPath);
                        break;
                    case AVAssetExportSessionStatusFailed:
                        reject(@"ERR_EXPORT_FAILED", exportSession.error.localizedDescription, exportSession.error);
                        break;
                    case AVAssetExportSessionStatusCancelled:
                        reject(@"ERR_EXPORT_CANCELLED", @"Export was cancelled", nil);
                        break;
                    default:
                        reject(@"ERR_EXPORT_UNKNOWN", @"Unknown export error", nil);
                        break;
                }
            }];

        } @catch (NSException *exception) {
            reject(@"ERR_EXCEPTION", exception.reason, nil);
        }
    });
}

- (void)convertToM4a:(NSString *)inputPath
          outputPath:(NSString *)outputPath
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            NSURL *inputURL = [NSURL fileURLWithPath:inputPath];

            // Check if input file exists
            if (![[NSFileManager defaultManager] fileExistsAtPath:inputPath]) {
                reject(@"ERR_INPUT_NOT_FOUND", [NSString stringWithFormat:@"Input file does not exist: %@", inputPath], nil);
                return;
            }

            // Load the input audio file
            AVURLAsset *asset = [AVURLAsset URLAssetWithURL:inputURL options:nil];
            NSArray *tracks = [asset tracksWithMediaType:AVMediaTypeAudio];

            if (tracks.count == 0) {
                reject(@"ERR_NO_AUDIO_TRACK", [NSString stringWithFormat:@"No audio track found in file: %@", inputPath], nil);
                return;
            }

            // Setup output URL
            NSURL *outputURL = [NSURL fileURLWithPath:outputPath];

            // Remove existing file if it exists
            [[NSFileManager defaultManager] removeItemAtURL:outputURL error:nil];

            // Create export session
            AVAssetExportSession *exportSession = [[AVAssetExportSession alloc] initWithAsset:asset
                                                                                    presetName:AVAssetExportPresetAppleM4A];
            exportSession.outputURL = outputURL;
            exportSession.outputFileType = AVFileTypeAppleM4A;

            // Start export
            [exportSession exportAsynchronouslyWithCompletionHandler:^{
                switch (exportSession.status) {
                    case AVAssetExportSessionStatusCompleted:
                        resolve(outputPath);
                        break;
                    case AVAssetExportSessionStatusFailed:
                        reject(@"ERR_CONVERSION_FAILED", exportSession.error.localizedDescription, exportSession.error);
                        break;
                    case AVAssetExportSessionStatusCancelled:
                        reject(@"ERR_CONVERSION_CANCELLED", @"Conversion was cancelled", nil);
                        break;
                    default:
                        reject(@"ERR_CONVERSION_UNKNOWN", @"Unknown conversion error", nil);
                        break;
                }
            }];

        } @catch (NSException *exception) {
            reject(@"ERR_EXCEPTION", exception.reason, nil);
        }
    });
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeAudioConcatSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"AudioConcat";
}

@end
