# Changelog

## [1.1.0](https://github.com/tstapler/agrapha/compare/v1.0.0...v1.1.0) (2026-05-19)


### Features

* **diarization:** extend DiarizationBackend with model-management API (FA-1A) ([65e6a00](https://github.com/tstapler/agrapha/commit/65e6a0025ff2b18cdb0192cf4e2172edf40928f1))
* **dictation:** wire LIVE_CAPTIONS activation via Settings toggle ([411d536](https://github.com/tstapler/agrapha/commit/411d5362c6d08523eaca7285ad86389b3004f955))
* **fluida:** add Swift JNI bridge package and Gradle build task (Stories 6 & 7) ([11223d8](https://github.com/tstapler/agrapha/commit/11223d810d379ba1c5c75800a34fd17485899360))
* **fluida:** implement FluidAudio backend architecture (Stories 2–5, 9–11) ([fdfb84d](https://github.com/tstapler/agrapha/commit/fdfb84dc978f87369dc9265c82dacd4794ac37a2))
* **fluida:** wire FluidAudio v0.14.5 API and unblock Story 8 ([0c7f461](https://github.com/tstapler/agrapha/commit/0c7f461e372c959e0c73f2aa35b1b7e247f538c0))
* **linux:** Linux audio support, plugin SPI, and dictation engine ([#1](https://github.com/tstapler/agrapha/issues/1)) ([fef6348](https://github.com/tstapler/agrapha/commit/fef63487877674b449af07a18ab63609774ca8dd))
* **scripts:** add WER benchmark data downloader ([b20dabf](https://github.com/tstapler/agrapha/commit/b20dabf8016c974c985a093cdfd996107a50cfa9))
* **transcription:** add WER measurement harness (Task 2.2) ([15559ec](https://github.com/tstapler/agrapha/commit/15559ec0d6bf2d85f14673f4349b8a05fd9f1689))
* **whisper:** CUDA GPU backend + Nix dev shell ([674460c](https://github.com/tstapler/agrapha/commit/674460c482d54dc46b76bc97bfca03f9f267c6d4))


### Bug Fixes

* **ci:** fix rootProject.file paths and CUDA toolkit install ([64788db](https://github.com/tstapler/agrapha/commit/64788db4509278d1dd336e76a72b17b6bdfef4d1))
* **ci:** fix WhisperCoreML jni.h cache poisoning and CUDA package name ([e18521a](https://github.com/tstapler/agrapha/commit/e18521aac0a7be30329784f2ac5709ddf0d32d4c))
* **ci:** pass JAVA_HOME to WhisperCUDA make and suppress JNI typedef redefinition ([e017a6e](https://github.com/tstapler/agrapha/commit/e017a6e917e3e8b5435e780b9321d727b4f3db52))
* **ci:** resolve JNI typedef conflicts in FluidAudio bridge for Xcode 16 ([eed306d](https://github.com/tstapler/agrapha/commit/eed306d4249f775262150163372d05427a623408))
* **ci:** update factory test for built FluidAudio dylib; fix CUDA JAVA_HOME env ([c48318c](https://github.com/tstapler/agrapha/commit/c48318c7ebcedc7e54e93ae123b8c2d04114a864))
* **ci:** upgrade macOS runner to macos-15 for Swift 6 support ([6080408](https://github.com/tstapler/agrapha/commit/6080408fe05dd72aa365f30e5b4f825005aea8bd))
* **settings:** persist all AppSettings fields through SettingsRepository ([b297139](https://github.com/tstapler/agrapha/commit/b2971396f4ac38f10bd1995b6be60a7d57281f98))
