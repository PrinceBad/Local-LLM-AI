<div align="center">

# Local LLM/AI

### A premium, high-performance offline Android client for running Large Language Models (LLMs) on-device with multimodal OCR and NPU acceleration.

<br/>

[![Latest release](https://img.shields.io/badge/releases-GitHub-181717?style=for-the-badge&logo=github&labelColor=0d1117)](releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/PrinceBad/Local-LLM-AI/total?style=for-the-badge&labelColor=0d1117)](releases)
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

Local LLM/AI is a high-fidelity, modern Android client designed to provide a completely private, offline, and secure conversational AI experience. By integrating Google's optimized **MediaPipe Tasks GenAI** engine, the app compiles and runs lightweight LLMs (like Qwen 2.5, DeepSeek-R1, Phi-2, and Gemma 2B) natively on mobile hardware. 

The app includes dynamic backend routing depending on the build flavor:
- **Normal Flavor**: Targets GPU acceleration (Vulkan) for responsive streaming generation with graceful CPU fallback.
- **NPU Flavor**: Configured to delegate inference directly to the device's system NPU/AI chip via NNAPI.

The app wraps this powerful local engine in a premium, fluid Jetpack Compose (Material 3) user interface featuring offline OCR document parsing, video/file media integration, and background download handling.

---

## Supported Models

The app includes built-in presets for several highly-capable, lightweight models optimized for mobile execution. Below are their approximate download sizes and memory requirements:

| Model | Developer | Parameters | Approx. Size | Min. RAM Requirement |
| :--- | :--- | :--- | :--- | :--- |
| **Qwen 2.5 1.5B Instruct** | Alibaba | 1.5B | ~1.6 GB | 6 GB+ |
| **DeepSeek-R1 Distill Qwen 1.5B** | DeepSeek | 1.5B | ~1.6 GB | 6 GB+ |
| **Gemma 1.1 2B IT** | Google | 2B | ~1.4 GB | 8 GB+ |
| **Phi-2 2.7B** | Microsoft | 2.7B | ~1.6 GB | 8 GB+ |

---

## Features

| Inference | Multimodal & OCR (100% Offline) |
| --- | --- |
| High-performance offline LLM execution | Attach Images, Videos & Documents (PDF, Code, Text) |
| Dual-flavor release (`normal` Vulkan GPU & `npu` routing) | Offline image OCR text extraction using Google ML Kit |
| Graceful CPU fallback optimization | Offline page-by-page PDF rendering and text recognition |
| Streaming word-by-word responses | Playback attached videos natively and view documents via Intent |

| UI / Experience | Core Features |
| --- | --- |
| Premium Material 3 dynamic styling | Complete offline privacy (no logs or tracking) |
| Custom system instructions prompt | Large model memory size & RAM badges in-app |
| Interactive file attachments preview drawer | Multi-turn chat context memory (6-turn history) |
| Collapsible OCR logs under bubble cards | Quantized weights optimizations |

---

## Screenshots

<div align="center">

<img src="images/screenshot_main.jpg" alt="Local LLM/AI Chat Screen" width="45%" />
&nbsp;&nbsp;&nbsp;&nbsp;
<img src="images/screenshot_manager.jpg" alt="Local LLM Manager Screen" width="45%" />

</div>

---

## Download

Grab the latest compiled APKs from the [GitHub releases page](releases/latest).

We compile two separate releases for each update:
1. **Normal Release (`app-normal-release-unsigned.apk`)**: Optimized for general devices using mobile GPU (Vulkan) or CPU.
2. **NPU Release (`app-npu-release-unsigned.apk`)**: Designed for modern phones featuring specialized AI chips (NPU), utilizing neural network API routing (`LlmInference.Backend.DEFAULT`). Includes the `.npu` application suffix so you can install both releases side-by-side.

---

## Build

To compile the application yourself, ensure you have Java 17 and Android SDK set up. Set your JDK path and run the compilation:

### Build Normal Flavor
```powershell
$env:JAVA_HOME = "C:\Users\Badsiwal\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
./gradlew assembleNormalRelease
```

### Build NPU Flavor
```powershell
$env:JAVA_HOME = "C:\Users\Badsiwal\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
./gradlew assembleNpuRelease
```

---

## Credits

Local LLM/AI is built on top of state-of-the-art on-device intelligence libraries and modern Android components.

Special thanks to:

- [Google MediaPipe Tasks GenAI](https://github.com/google-ai-edge/mediapipe)
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
