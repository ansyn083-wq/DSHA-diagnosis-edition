# Diagnostic build 210

This candidate extends the recoverable diagnostic build 209 source. It is not the official production-signing channel. Publication to GitHub requires a later user decision after APK review.

## Native features

- Twenty-four bilingual welcome phrases, local-time-aware selection, no immediate repeat, and saved greeting identity across rotation.

- Session rename, duplicate/fork, archive/restore/delete and authoritative workspace state.
- Live, session-bound tool approval/rejection, bounded execution details, copy/fork/feedback actions and explicitly confirmed retry of an eligible failed user turn. Uncertain submissions are not automatically retried.
- Common Markdown, selectable code, horizontally scrollable tables and a restricted common-math renderer. Unsupported math stays readable source. No active HTML or remote image loading.
- Workspace navigation and bounded text/Markdown/image previews; PDF uses Android PdfRenderer. Unsupported documents show an explicit fallback.
- Scheduled-task editing and account/API configuration with revision checks, explicit review, and uncertainty reconciliation.
- Slash commands, goals and permission controls use the bundled backend's verified contracts. Presets without a backend description cannot be applied.
- Loaded-history token usage is not presented as billing/account-wide usage. Model selection retains the model-name-only display.

## Important verification limits

Software tests are not physical-device acceptance. Phone rotation, keyboard/insets, old-service startup recovery, actual PDF page drawing and upgrade/data retention still need testing on the intended device. The final verification receipt records actual compile/test/package outcomes separately.

The existing native-session exact-byte rebuild check differs across Windows/Linux solely in ELF compiler-identification metadata (.comment); executable bytes match after removing that section from temporary comparison copies. Original packaged binary and strict comparison gate were not changed, and the original-byte check is not claimed to pass.

Some inherited host-suite checks fail independently of this feature work, including UI/source-architecture assertions and the cross-platform runtime-fixture negative control. These are recorded rather than hidden. A passing focused feature test does not mean the entire inherited suite is green.

## Source and licensing

Deliver source with the APK, including app/src/main/assets/licenses and tools/vendor/native-markdown/jlatexmath-android-v0.2.0.zip. The latter contains the exact unmodified JLaTeXMath Android source corresponding to the formula dependency, with its upstream license and linking exception. See tools/vendor/native-markdown/README.md. Do not include signing keys, passwords, caches or unpinned binaries in public source.

For fixed runtime inputs, follow README.md, including the optimized recovery-rootfs source mapping. No original input pins were changed.
