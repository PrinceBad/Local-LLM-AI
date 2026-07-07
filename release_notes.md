The official release of a premium, offline-first Android client for running Large Language Models (LLMs) privately on native mobile hardware.

### 🔑 Key Features

* **100% Private & Offline:** No cloud APIs, telemetry, or data logging. Everything stays on your device.
* **Vulkan GPU Acceleration:** High-speed streaming generation with seamless, automated CPU fallback.
* **Smart Storage:** Models save to `getExternalFilesDir(null)/models` to save internal space while complying with SELinux sandboxing.
* **Fail-Safe Downloads:** Strict `Content-Length` verification prevents corrupted or truncated model loads.
* **Model Manager:** Includes a one-tap **"Delete"** feature to easily purge local storage and re-download.
* **Diagnostic UI:** Beautiful error banners in the onboarding view showing exact stack traces for easy troubleshooting.
* **Multimodal OCR:** Offline text extraction from images, videos, and PDFs via Google ML Kit.

### 📦 Included Assets

* **`app-release.apk`**: Unified production package that automatically optimizes for GPU/CPU runtime (built without Qualcomm dependencies, falling back directly to Vulkan GPU).
