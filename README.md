<div align="center">

# Local LLM/AI

### A premium, high-performance offline Android client for running Large Language Models (LLMs) on-device with multimodal OCR.

<br/>

[![Latest release](https://img.shields.io/badge/releases-GitHub-181717?style=for-the-badge&logo=github&labelColor=0d1117)](https://github.com/PrinceBad/Local-LLM-AI/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/PrinceBad/Local-LLM-AI/total?style=for-the-badge&logo=github&labelColor=0d1117)](https://github.com/PrinceBad/Local-LLM-AI/releases)
[![Android](https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=0d1117)](#download)
<br/>

[**Download**](#download) - [**Features**](#features) - [**Screenshots**](#screenshots) - [**Credits**](#credits) - [**Disclaimer**](#disclaimer)

</div>

> [!WARNING]
> Local LLM/AI executes AI models entirely on your physical mobile device. Running large models is highly resource-intensive and requires a modern processor and sufficient RAM (6 GB+). System stability, inference speeds, and output quality depend entirely on your hardware capability.
> Model weights (such as Qwen, DeepSeek, or Gemma) are not packaged inside the APK and must be downloaded or transferred manually due to their size (1.5 GB+).

Additionally, this application executes all calculations offline. No internet connection is required after models are downloaded, and no conversational data ever leaves your device.

---

## What Is Local LLM/AI?

Local LLM/AI is a high-fidelity, modern Android client designed to provide a completely private, offline, and secure conversational AI experience. By integrating Google's optimized **AI Edge LiteRT-LM** engine, the app compiles and runs lightweight LLMs (like Qwen 3, DeepSeek-R1, Qwen 2.5 Coder, and Gemma 4) natively on mobile hardware.

The app targets a 3-tier execution waterfall: Qualcomm Hexagon NPU (Snapdragon 8 Gen 1-4 / Elite via QNN HTP) → Vulkan GPU → targeted big-core CPU threads for responsive streaming generation.

The app wraps this powerful local engine in a premium, fluid Jetpack Compose (Material 3) user interface featuring offline OCR document parsing, video/file media integration, and background download handling.

---

## Supported Models

The app includes built-in presets for several highly-capable, lightweight models optimized for mobile execution. Below are their approximate download sizes and memory requirements:

| Model | Developer / Author | Parameters | Approx. Size | Min. RAM Requirement | Multimodal (Vision) | Role |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Qwen 3 0.6B (Pocket Turbo)** | Alibaba | 0.6B | ~498 MB | 2 GB+ | No | Ultra-Fast Pocket Turbo Floor Model |
| **Qwen 2.5 1.5B Instruct** | Alibaba | 1.5B | ~1.60 GB | 4 GB+ | No | Daily Multilingual Baseline |
| **Qwen 3 1.7B (Dynamic INT4)** | Alibaba | 1.7B | ~977 MB | 4 GB+ | No | Sub-1GB Dynamic Compact Powerhouse |
| **Qwen 2.5 Coder 1.5B Instruct** | Alibaba | 1.5B | ~1.12 GB | 4 GB+ | No | Agile Code Intelligence & Scripting |
| **DeepSeek-R1 Distill Qwen 1.5B** | DeepSeek | 1.5B | ~1.83 GB | 6 GB+ | No | Offline Deep Reasoning & CoT Logic |
| **Google Gemma 4 E2B Instruct** | Google | 2.4B | ~2.59 GB | 6 GB+ | Yes | Budget Multimodal Vision & OCR |
| **Gemma 4 E2B Uncensored MAX** | PeppX / Google | 2.3B | ~2.55 GB | 6 GB+ | Yes | Compact Uncensored Multimodal (8K Ctx) |
| **SuperGemma 4 E4B Abliterated** | Jiunsong / typomonster | 4.0B | ~3.65 GB | 8 GB+ | Yes | Flagship Abliterated Multimodal with MTP |
| **Qwen 3 4B (Hybrid Reasoning)** | Alibaba | 4.0B | ~2.66 GB | 8 GB+ | No | Mid-Range High-Capacity Reasoning |
| **Google Gemma 4 E4B Instruct** | Google | 4.0B | ~3.66 GB | 8 GB+ | Yes | Flagship Multimodal Vision & OCR |

---

## Features

| Inference & Performance | Multimodal & OCR (100% Offline) |
| --- | --- |
| 3-Tier Execution Waterfall (Qualcomm Hexagon NPU → Vulkan GPU → Big-Core CPU) | Attach Images, Videos & Documents (PDF, Code, Text) |
| Universal Thinking Budget Clamping & Dynamic Toggle for Reasoning Models | Offline image OCR text extraction using Google ML Kit |
| Live Hardware Telemetry Pill (Time to First Token in ms, Tokens/Sec Decode Speed) | Offline page-by-page PDF rendering with auto bitmap recycling |
| Streaming word-by-word token generation with responsive Stop control | Video keyframe sampling via MediaMetadataRetriever for Vision LLMs |

| UI & Workflow | Privacy & Architecture |
| --- | --- |
| Premium Material 3 dynamic styling with high-contrast Dark Mode | 100% on-device execution (no telemetry, ads, or data collection) |
| Interactive Collapsible Reasoning Accordion for DeepSeek & Qwen `<think>` traces | Privacy-preserving Web Search Grounding with strict query isolation |
| Multi-Session Chat Manager with auto-titling, rename, and swipe deletion | Background download service with PARTIAL_WAKE_LOCK and HTTP 206 resume |
| Settings sliders for context window, temperature, top-k, top-p, and penalty | Hardware-backed AES-256 Keystore encryption with auto-recovery |
| Type-safe model catalog filtering chips (Turbo, Reasoning, Vision, Compact) | Full post-download SHA-256 verification and atomic file moves |

### Recent Architecture, UI/UX & Quality-of-Life Innovations (v2.5+)

- **DeepSeek & Qwen Reasoning Accordion**: Real-time collapsible reasoning accordion parsing `<think>` and `</think>` tags with live unclosed streaming token support. Reasoning traces are rendered cleanly and automatically stripped from replayed history so intermediate thoughts never exhaust future context windows.
- **Universal Thinking Budget & Control**: Dynamic per-turn thinking toggle in chat and settings, defaulted to off for Qwen 3 models (`qwen3-0.6b`, `1.7b`, `4b`) to prevent 2K/4K window exhaustion, and an automated universal budget clamp `minOf(1024, detectedMaxContextTokens / 3)` for all reasoning bundles.
- **Qualcomm Hexagon NPU & GPU Waterfall**: Multi-tier execution engine prioritizing NPU (Snapdragon 8 Gen 1–4 / Elite via Qualcomm QNN HTP) → Vulkan GPU → targeted big-core CPU threads. Automatically caches NPU subgraph incompatibility in persistent storage (`npu_backend_cache`) to prevent UI freezes.
- **Live Hardware Telemetry Pill**: Real-time performance badge beneath assistant messages displaying exact Time to First Token (TTFT in ms), token decode rate (tokens/sec), prompt token count, and total decode tokens.
- **Background Download Service with WakeLock**: Dedicated foreground download service (`dataSync`) protected by a reference-counted `PARTIAL_WAKE_LOCK` with a 2-hour safety timeout, ensuring Android Doze mode never suspends network sockets during multi-gigabyte downloads. Features seek-based HTTP 206 resumption with `RandomAccessFile`, live transfer speed, ETA, and post-download SHA-256 verification.
- **Privacy-Preserving Web Search Grounding**: Optional search grounding supporting DuckDuckGo, Wikipedia, Jina AI, Brave Search, and Serper. Strict query isolation ensures only the concise search query leaves the device; conversation history and system instructions remain 100% on-device. Features expandable source citation cards with full query transparency.
- **Multimodal Video & Document Vision**: Comprehensive vision pipeline supporting downsampled image OCR, multi-page PDF rendering via `PdfRenderer` with immediate bitmap memory recycling, and video keyframe extraction via `MediaMetadataRetriever` for vision-capable models (Gemma 4 E4B).
- **Multi-Session Chat Manager**: Full multi-chat management featuring isolated session files (`sessions/{id}.json`), automatic session titling derived from initial prompts, rename, session deletion, and conversation switching with clean state restoration.
- **Hardware-Backed Keystore Security**: AES-256 encrypted token and API key storage via AndroidX Security Crypto with automatic degraded fallback and version/patch-based re-probe recovery.
- **Pervasive Native Resource Cleanup**: All native ML Kit `TextRecognizer` instances, `PdfRenderer` descriptors, and `Bitmap` allocations are wrapped in strict `try-finally` lifecycle guards to eliminate memory leaks and avoid native aborts.

---

## Screenshots

<div align="center">

<img src="images/screenshot_main.jpg" alt="Local LLM/AI Chat Screen" width="45%" />
&nbsp;&nbsp;&nbsp;&nbsp;
<img src="images/screenshot_manager.jpg" alt="Local LLM Manager Screen" width="45%" />

</div>

---

## Download

Grab the latest compiled APKs from the [GitHub releases page](https://github.com/PrinceBad/Local-LLM-AI/releases/latest).

The release APK (`app-release.apk`) is optimized for mobile hardware using on-device GPU (Vulkan) or CPU.

---

## Build

To compile the application yourself, ensure you have Java 17 and Android SDK set up. Set your JDK path and run the compilation:

### Build Release APK
```powershell
# Set this to your JDK 17 path if not already set globally
$env:JAVA_HOME = "C:\path\to\your\jdk-17"
./gradlew assembleRelease
```

---

## Credits

Local LLM/AI is built on top of state-of-the-art on-device intelligence libraries and modern Android components.

Special thanks to:

- [Google AI Edge LiteRT (LiteRT-LM)](https://github.com/google-ai-edge/litert-lm)
- [Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
- [Jetpack Compose & Material 3](https://developer.android.com/compose)
- [Coil Image Loading Library](https://github.com/coil-kt/coil)
- [OkHttp](https://github.com/square/okhttp)
- [Kotlin Coroutines Flow](https://github.com/Kotlin/kotlinx.coroutines)

---

## License

Local LLM/AI is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## Disclaimer

Local LLM/AI is an independent, unofficial project. It is not affiliated with, funded, authorized, endorsed by, or associated with Google LLC, MediaPipe, Gemma, or any of their affiliates.

All trademarks, service marks, catalogs, artwork, metadata, and model weights remain the property of their respective owners. Users are responsible for procuring and loading model files in compliance with the respective model's terms of use, license agreements, and regional requirements.
