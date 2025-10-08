# 專案架構分析

## 📁 專案結構

```
react-native-audio-concat/
├── src/                          # TypeScript 源碼
│   ├── index.tsx                 # 主入口
│   ├── NativeAudioConcat.ts      # Turbo Module 定義
│   └── __tests__/                # 測試檔案
│       └── index.test.tsx
├── ios/                          # iOS 原生實作
│   ├── AudioConcat.h             # Objective-C 標頭檔
│   └── AudioConcat.mm            # Objective-C++ 實作
├── android/                      # Android 原生實作
│   └── src/main/java/com/audioconcat/
│       ├── AudioConcatModule.kt  # Kotlin 主模組實作
│       └── AudioConcatPackage.kt # 模組註冊
├── example/                      # 示範應用
├── lib/                          # 編譯輸出目錄
├── node_modules/                 # 依賴套件
└── 配置檔案
    ├── package.json              # NPM 套件配置
    ├── tsconfig.json             # TypeScript 配置
    ├── eslint.config.mjs         # ESLint 配置
    ├── babel.config.js           # Babel 配置
    ├── turbo.json                # Turbo 建構配置
    └── AudioConcat.podspec       # iOS CocoaPods 配置
```

## 🏗️ 技術架構

### 1. 核心技術棧

**模組類型**: React Native Turbo Module
- 使用 `TurboModuleRegistry` 提供高效能的原生橋接
- 支援 React Native 新架構 (New Architecture)
- 類型安全的 JavaScript/TypeScript 與原生程式碼溝通

**平台支援**:
- ✅ iOS (Objective-C++)
- ✅ Android (Kotlin)

### 2. 建構工具鏈

- **react-native-builder-bob** (v0.40.13)
  - 編譯 TypeScript 源碼
  - 生成 CommonJS/ES Module 格式
  - 產生型別定義檔 (.d.ts)

- **Turbo** (v2.5.6)
  - Monorepo 建構加速
  - 快取機制優化建構速度

- **Yarn 3.6.1**
  - Workspace 管理
  - 包含 example 子專案

### 3. 開發工具

**程式碼品質**:
- ESLint 9.35.0 + React Native ESLint Config
- Prettier 3.6.2 (自動格式化)
- TypeScript 5.9.2 (型別檢查)

**測試框架**:
- Jest 29.7.0
- React Native 測試預設配置

**Git 工作流**:
- Commitlint (Conventional Commits)
- Lefthook 1.12.3 (Git hooks 管理)

**發布工具**:
- release-it 19.0.4
- @release-it/conventional-changelog (自動生成變更日誌)

### 4. Codegen 配置

```json
{
  "codegenConfig": {
    "name": "AudioConcatSpec",
    "type": "modules",
    "jsSrcsDir": "src",
    "android": {
      "javaPackageName": "com.audioconcat"
    }
  }
}
```

- 自動生成 Turbo Module 規格檔案
- 為 iOS/Android 生成對應的原生介面程式碼

## 📝 目前實作狀態

### ⚠️ 尚未實作核心功能

目前程式碼仍是 `create-react-native-library` 腳手架的範例狀態：

**JavaScript/TypeScript 層** (`src/`):
- `src/index.tsx:3-5` - 只有範例 `multiply(a, b)` 函數
- `src/NativeAudioConcat.ts:4` - Spec 定義僅包含 `multiply` 方法

**iOS 層** (`ios/AudioConcat.mm`):
- `ios/AudioConcat.mm:4-7` - 只實作了 `multiply` 方法
- 需要實作 AVFoundation 音訊處理邏輯

**Android 層** (`android/.../AudioConcatModule.kt`):
- `android/.../AudioConcatModule.kt:16-18` - 只實作了 `multiply` 方法
- 需要實作 MediaCodec/AudioTrack 音訊處理邏輯

### ✅ 需要實作的功能

根據 README.md 描述，需要實作：

```typescript
concatAudioFiles(
  data: AudioDataOrSilence[],
  outputPath: string
): Promise<string>
```

**功能需求**:
1. 接受音訊檔案陣列和靜音段落
2. 合併成單一 M4A 音訊檔案
3. 支援在檔案間插入指定時長的靜音
4. 非同步處理，回傳輸出檔案路徑

**型別定義**:
```typescript
type AudioDataOrSilence =
  | { filePath: string }        // 音訊檔案路徑
  | { durationMs: number }       // 靜音時長 (毫秒)
```

## 📦 發布配置

**NPM 設定**:
- Package Name: `react-native-audio-concat`
- Version: 0.1.0
- Registry: https://registry.npmjs.org/
- License: MIT

**GitHub**:
- Repository: https://github.com/felixchen-dev/react-native-audio-concat
- 自動發布 GitHub Release

**版本管理**:
- 遵循 Conventional Commits 規範
- 使用 Angular 風格的變更日誌
- Git 標籤格式: `v${version}`

## 🎯 下一步實作方向

1. **定義 TypeScript API** - 更新 `NativeAudioConcat.ts` Spec
2. **實作 iOS 音訊處理** - 使用 AVFoundation 合併音訊
3. **實作 Android 音訊處理** - 使用 MediaMuxer/MediaCodec
4. **編寫單元測試** - 測試音訊合併邏輯
5. **更新範例應用** - 展示實際使用案例
6. **效能優化** - 處理大型音訊檔案的記憶體管理

## 📚 相關技術文件

- [React Native Turbo Modules](https://reactnative.dev/docs/the-new-architecture/pillars-turbomodules)
- [iOS AVFoundation](https://developer.apple.com/av-foundation/)
- [Android MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)
- [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
