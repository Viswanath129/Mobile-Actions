# FunctionGemma Voice Assistant — Standalone Bilingual Android App

A standalone, privacy-first Android voice assistant powered by on-device **FunctionGemma-270M** (`functiongemma_mobile_actions.litertlm`, ~435.49 MB) via Google's official **LiteRT-LM Android API**.

Designed with a modern minimal white-and-blue theme, tap-to-talk voice interface supporting **English (India)** and **Telugu (India)** with automatic fallback, strict action validation, and sensitive-action confirmation dialogs.

---

## 1. Environment & Architecture Specifications

- **Target Device:** Realme RMX5070 (Realme P1 / Narzo 70 5G), Android 16 (API 36), `arm64-v8a`.
- **JDK:** Java 21 / JBR 21 (`B:\Android Studio\jbr`).
- **Android Gradle Plugin (AGP):** `8.9.2`.
- **Kotlin & Compose Compiler:** `2.4.0` (matching LiteRT-LM binary metadata).
- **LiteRT-LM Android Runtime:** `com.google.ai.edge.litertlm:litertlm-android:0.18.0`.
- **Speech Input:** On-device preferred `SpeechRecognizer` (`createOnDeviceSpeechRecognizer`), falling back dynamically to installed recognition service.
- **Speech Output:** Android `TextToSpeech` with Telugu (`te-IN`) and English (`en-IN`) voice routing and live playback interruption controls.
- **Model Storage:** App-managed internal storage (`context.filesDir`) via SAF document picker, with auto-detection for existing `/sdcard/.../mobile_actions.litertlm`.

---

## 2. 108 Mobile Action Tools Catalog Integration

The complete 108 mobile action schema is packaged in [`app/src/main/assets/tool_catalog.json`](file:///C:/Users/kasiv/AndroidStudioProjects/FunctionGemmaVoiceAssistant/app/src/main/assets/tool_catalog.json) and registered with LiteRT-LM as OpenAPI tools.

### Implemented Handlers
- **Flashlight Hardware Control:** `turn_on_flashlight`, `turn_off_flashlight`, `toggle_flashlight` (via Android `CameraManager.setTorchMode`).
- **Device & System Settings:** Wi-Fi, Bluetooth, Airplane mode, Mobile Data, NFC, Battery Saver, Storage, Security, Date & Time, Developer Options, Accessibility (`Settings` intents).
- **Navigation & Maps:** `show_location_on_map`, `show_map`, `navigate_to_place` (`geo:` and `google.navigation:` intents).
- **Web Search & URL Navigation:** `search_web`, `open_url` (strictly restricted to `https://` only; blocks `file:`, `javascript:`, and intent injection).
- **App Management:** `open_app` (dynamic package and label resolver), `go_home_screen`.
- **Clock, Alarms & Timers:** `create_alarm`, `create_timer`, `open_clock_stopwatch` (`AlarmClock` intents).
- **Communication & Contacts:**
  - `create_contact` (opens form with prefilled first/last name, phone, email; user must manually review and save).
  - `dial_phone_number` (opens system dialer with verified phone format; never initiates calls automatically).
  - `compose_sms` (opens SMS composer with verified recipient and message).
  - `send_email` / `compose_email` (opens email composer with RFC-validated email; never sends silently).
- **Media & Camera:** `take_photo`, `open_video_camera`, `open_photo_gallery` (`MediaStore` intents).
- **Productivity & Notes:** `create_note`, `copy_to_clipboard` (`ClipboardManager`).
- **Device Telemetry:** `get_battery_status` (live battery percentage broadcast receiver), `get_device_info`, `check_current_time_date`.

### Sensitive Action Gating & Confirmation UI
All actions modifying contacts, creating calendar events, dialing numbers, or composing messages require explicit user confirmation via an interactive Jetpack Compose `AlertDialog` displaying the exact tool call parameters before intent dispatch.

---

## 3. Bilingual Voice Interface (English & Telugu)

- **Language Selector:** Supports `English + Telugu`, `English (India)` (`en-IN`), and `Telugu (India)` (`te-IN`).
- **Recognition Fallback Order:**
  1. `SpeechRecognizer.createOnDeviceSpeechRecognizer` (Android 12+ API 31+).
  2. Installed Android Speech Recognition Service (Google Speech Services / offline voice pack if installed).
  3. Informative error banner directing to voice pack download if speech service is absent.
- **Whisper Tiny Architecture:** Contract defined in [`WhisperTinyFallback.kt`](file:///C:/Users/kasiv/AndroidStudioProjects/FunctionGemmaVoiceAssistant/app/src/main/java/com/example/functiongemmavoice/speech/WhisperTinyFallback.kt). Whisper requires NDK CMake compilation of `libwhisper.so` and bundling `ggml-tiny.bin` (~75 MB).
- **Bilingual TTS:** Automatically detects Telugu script unicode ranges (`\u0C00` - `\u0C7F`) and dynamically selects Telugu voice synthesis with English fallback.

---

## 4. Verification & Testing

### Unit Tests
Pure JVM JUnit test suite in [`ActionRegistryTest.kt`](file:///C:/Users/kasiv/AndroidStudioProjects/FunctionGemmaVoiceAssistant/app/src/test/java/com/example/functiongemmavoice/ActionRegistryTest.kt):
- Validated HTTPS-only enforcement for `open_url`.
- Blocked malicious URIs (`file:///`, `javascript:`, `http://`).
- Verified sensitive action confirmation flags on contacts, calendar, dialing, and email.
- Verified parameter regex validation for phone numbers and email addresses.
- Verified rejection of unknown/unregistered tools.
- **Test Result:** 5/5 passed (100%).

### On-Device Deployment
- Built debug APK: `app/build/outputs/apk/debug/app-debug.apk` (65.8 MB).
- Installed and launched on Realme RMX5070 (Android 16):
  ```bash
  adb -s e7c443b9 install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s e7c443b9 shell am start -n com.example.functiongemmavoice/.MainActivity
  ```
- Device process verified: PID `11727` active.

---

## 5. Deliverable Archive

- Source Archive: `C:\Users\kasiv\Downloads\FunctionGemmaVoiceAssistant_Standalone_Bilingual_Complete.zip`
- Launcher Icon: Generated across all standard Android mipmap densities (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) from `FunctionGemma Voice Assistant Logo.png`.
