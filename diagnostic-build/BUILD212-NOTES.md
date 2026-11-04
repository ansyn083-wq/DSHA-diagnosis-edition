# Diagnostic build 212

This candidate preserves build 211's48 bilingual greetings and is built from complete source, not by replacing DEX files in a previous APK. The application and installation version must both report212. Release and test outcomes are recorded separately for the final candidate.

## Question interaction corrections

- Only the explicit negative action rejects a live question. Back dismisses its UI while preserving the pending answer; touching outside does not cancel it.
- Questions and approvals are processed in bounded arrival order. Duplicate/stale event deliveries cannot produce duplicate decisions. Capacity handling never grants a tool automatically.
- Event-channel readiness is validated independently of transcript readiness. Failed/ended channels cannot silently count as ready to receive questions.
- Approval-dialog cleanup is tied to the exact dialog instance, so an old callback cannot clear a newer dialog's reference.
- Timed question claims use the locked backend handshake and monotonic countdown, with focus/edit holds. Transport opening failure, expiry and user cancellation are distinct. Session changes and uncertain replies do not trigger automatic answer retries.
- Diagnostic operation exports include bounded, privacy-safe question lifecycle records. Only fixed state codes and process-salted correlation hashes are retained, not question/answer text, API keys, cookies or raw identifiers. Records can be dropped under pressure; missing records are not proof an event never happened.

These fixes address reproduced source/component defects. The screenshots included both ASK_ABORTED and a separate explicit-close message; they do not establish the original phone's precise cancellation trigger. No claim is made that all possible backend/turn cancellations are eliminated.

## Message actions

Copy, fork and feedback use icon actions beneath the corresponding message, with user/assistant alignment,48dp touch targets, labels and tooltips. Source-session bindings prevent detached actions from operating on another conversation. Bottom following occurs after layout and accounts for composer growth, while preserving readers who scroll upward.

## Verification and distribution limits

Software/Robolectric tests do not establish physical-phone touch delivery, accessibility service behavior, keyboard/inset handling or data-preserving upgrade. Check those on the intended device. Keep the same diagnostic signing certificate and incremented versionCode; never ship an unsigned intermediate. The original strict native-session cross-host byte-comparison issue remains documented in BUILD210-NOTES.md; no bundled binary or input pin is changed by this work.

Deliver the full source and corresponding formula-library source/licenses with the APK. No GitHub publication until the user explicitly approves after trying the APK.
