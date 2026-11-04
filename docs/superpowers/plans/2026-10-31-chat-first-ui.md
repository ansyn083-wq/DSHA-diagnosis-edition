# Chat-first DSHA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an applicable source patch implementing the approved chat-first Android UI and unobtrusive environment setup.

**Architecture:** Add an independent native launcher (`ChatHomeActivity`) which observes the existing application task/controller and requests setup through a small coordinator. Keep the existing authenticated WebPreview and upstream conversation model; apply an app-owned chat presentation script and stylesheet rather than modifying managed runtime bytes. Existing MainActivity becomes the advanced settings destination.

**Tech Stack:** Java 17, AndroidX, Android system WebView, CSS, plain JavaScript, Node browser fixtures.

**Spec:** `docs/superpowers/specs/2026-10-31-dsha-ui-design.md`

## Global Constraints

- Upstream base: `1bf282eb044f4082f58a60b64676419feb68a60d`.
- Standard Android 11+, arm64; preserve shared Low source compatibility, no claim of Gecko UI parity.
- Use actual maintenance state and ready-page identity; no fake ready markers or message replay.
- Preserve visible foreground-service startup tickets, pending maintenance barriers, and unknown-session blocking.
- Keep all original management features accessible, and retain app identity.
- Code delivery only; no push, public release, or automatic signing.

## Review Focus

- Interrupted setup and repeated launcher entry must not repeat environment writes.
- Stop/unknown runtime state must not cause automatic restart or navigation.
- Draft transfer must never overwrite a resident web draft or send a message.
- Theme/layout changes must not obscure permissions, tools, attachments or menus.
- Settings navigation must survive an unready environment without forcing reinstall.

### Task 1: Setup policy and chat launcher

**Files:** Create `util/ChatEntryPolicy.java`, `ui/ChatSetupCoordinator.java`, `ui/ChatHomeActivity.java`, `ui/ChatManagementActivity.java`; modify manifest; add host policy tests.

**Interfaces:** `ChatEntryPolicy.action(...)` returns a finite action for the read-only setup snapshot. Coordinator observes BackupTask and HarnessController off the UI thread, and the activity submits protected startup only while resumed. Management embeds existing SettingsFragment and provides plugins, workspace, terminal and environment routes.

- [ ] Add executable policy tests for busy/pending/unknown/stopped/failed/ready combinations; run and observe missing implementation failure.
- [ ] Implement policy, coordinator and native chat shell. Use persisted attempted_identity before maintenance dispatch and ViewModel request ownership for recreation.
- [ ] Run host policy tests; validate Android resource and Java compilation where tooling permits; commit.

### Task 2: Chat presentation and safe native actions

**Files:** Create `web-integration/chat-ui.js` and `chat-ui.css`, modify `ui/WebPageScripts.java` and `ui/WebPreviewActivity.java`; add browser fixture test.

**Interfaces:** `window.__dshaChatUi.install(binding)` uses existing authenticated presentation identity. Native `DshaChat` web-message listener accepts bounded fixed actions from the current loopback main frame only. Draft is offered explicitly, transferred once into an empty actual composer via its native textarea input event, read back, and always retained natively until user clears it; never auto-sent.

- [ ] Write failing browser checks for style idempotence, settings entry, draft collision/no auto-submit, unsafe bindings and dark/phone/tablet layout.
- [ ] Apply neutral tokens, 800px content limit, rounded composer/user bubbles, 840px responsive sidebar and settings button without replacing host conversation components. Gate optional adaptation on known host markers.
- [ ] Run browser checks and relevant existing integration tests; commit.

### Task 3: Package and review

**Files:** Chinese apply/rollback README, evidence report, source patch ZIP with pinned base and relevant tests.

**Interfaces:** Patch applies with `git apply --check` to the exact upstream base; package contains only modified/new sources and docs plus unified diff.

- [ ] Run test commands, attempt Standard Gradle check and report actual blockers, verify patch applies to clean base.
- [ ] Fresh reviewer checks complete diff against spec; repair important findings and rerun checks.
- [ ] Package patch, checks and instructions, save deliverable and provide download link.
