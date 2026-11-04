# DSHA GPT Native UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 DSHA 诊断版从“Web 聊天皮肤 + 原生控制台”改造成手机、平板和横屏一致的 GPT 风格原生应用，同时保留 diag2 会话恢复修复及全部业务安全边界。

**Architecture:** 先建立唯一的颜色/尺寸/动效令牌和可测试的响应式模型，再用 `GptShellLayout` 承载四个主目的地，用 `GptPage`/`GptTopBar`/`GptStatusPanel` 迁移现有 Fragment 与独立 Activity。Web 聊天继续作为独立全屏 `WebPreviewActivity`，共享主题但不叠加原生侧栏；现有服务、终端、权限、插件、备份和恢复逻辑保持不变。

**Tech Stack:** Java 17、Android SDK 37、Material 3/AppCompat、XML View、Robolectric/JUnit 4、Gradle 9.3.1、ARM64。

**Spec:** `docs/superpowers/specs/2026-11-02-gpt-native-ui-redesign.md`

## Global Constraints

- 包名保持 `dsh.android.client.diagnostics`；diag3 使用 versionCode `192`、versionName `0.2.1-diag3-dsh0.2.1-rc.2`。
- 使用诊断版既有 PKCS12 签名，证书 SHA256 必须仍为 `674f41e24ab38375c2519d3132a6ad30fcf6d49562d8c87c3e74a03c3dd9cf4f`。
- minSdk 30、target/compileSdk 37、NDK `26.3.11579264`、仅 `arm64-v8a`；不得新增第二套 UI 框架。
- 断点固定：`<=760dp` PHONE、`761–1023dp` DRAWER、`>=1024dp` SIDEBAR。
- 触摸目标至少 48dp；聊天最大宽度 800dp；设置/表单最大宽度 760dp；固定侧栏 272dp。
- 保留 `StoredWebSession.privateDirectory(...).getCanonicalFile()` 修复、KeyStore alias、Provider URI、备份格式和全部停止/身份/维护门禁。
- Web 继续由独立 `WebPreviewActivity` 承载；独立工具 Activity 保留 Intent/任务栈，只统一界面。
- 真机确认前不得宣称后台恢复已最终修复；日志验收必须包含 `WRITE_VERIFIED`、`SAVED`、`RESTORED`。
- 大型离线输入必须来自已记录官方归档并通过固定 SHA256/受管证明；不得从旧 APK 提取后冒充源码输入。

## Review Focus

- 宽度恰为 760/761/1023/1024dp 时只出现一个导航形态，抽屉关闭后不留下空白图标轨（Task 3 测试）。
- Activity 重建或状态轮询同时发生时，自动进入只能触发一次且不能重复启动服务（Task 4 测试）。
- Web 文件选择/麦克风/画中画和终端 PTY 在换壳后保持原生命周期与返回行为（Task 7、Task 8 测试）。
- 字体缩放 1.3x、英文长文案、TalkBack 和系统动画比例为 0 时关键操作仍可达（Task 10 测试）。
- diag2 数据、会话记录和同签名覆盖安装后不得被清除或迁移到新路径（Task 11 测试与真机清单）。

---

## File Structure

**New UI model and components**

- `app/src/main/java/com/deepseekharness/app/ui/GptResponsive.java` — pure-Java breakpoint and width policy.
- `app/src/main/java/com/deepseekharness/app/ui/GptUiTokens.java` — semantic dimensions and page width kinds.
- `app/src/main/java/com/deepseekharness/app/ui/GptShellLayout.java` — sidebar/drawer/top bar/content shell.
- `app/src/main/java/com/deepseekharness/app/ui/GptTopBar.java` — reusable navigation header.
- `app/src/main/java/com/deepseekharness/app/ui/GptPage.java` — scroll/max-width/section/footer page container.
- `app/src/main/java/com/deepseekharness/app/ui/GptStatusPanel.java` — launch/recovery state presentation.
- `app/src/main/java/com/deepseekharness/app/ui/GptLoadingIndicator.java` — reduced-motion-aware loading view.
- `app/src/main/java/com/deepseekharness/app/ui/GptDialog.java` — unified safe dialog/sheet factory.

**Shared resources**

- Modify `res/values/colors.xml`, `values-night/colors.xml`, `dimens.xml`, `themes.xml`, `styles.xml`.
- Create `res/values/gpt_ui.xml`, `values-night/gpt_ui.xml` for semantic tokens.
- Create/replace `res/drawable/gpt_*` backgrounds and required vector navigation icons.
- Replace `res/layout/activity_main.xml`, `activity_diagnostic_home.xml`, `fragment_settings.xml`; adjust existing fragment/activity layouts to shared styles.

**Navigation and page migration**

- Modify `MainActivity.java`, `ChatHomeActivity.java`, `ChatManagementActivity.java`, `ChatSetupCoordinator.java`, `UiMotion.java`, `WebPreviewActivity.java`.
- Refactor compatibility helpers `CardPage.java`, `UiNavigation.java`, `DshaDialogBuilder.java`, `CardSheet.java` to delegate to GPT components.
- Migrate `LaunchFragment`, `SettingsFragment`, `ConfigFragment`, `WorkspaceFragment`, `DeviceGrantsFragment`, `InstallFragment`, `PluginFragment`, `TerminalFragment`, `PtyTerminalFragment`.
- Migrate independent Activities using `CardPage`/custom roots: backup/recovery/ADB/update/skills/files/tasks/onboarding/diagnostics.
- Align `assets/web-integration/chat-ui.css`, `chat-ui.js`, `chat-draft.js` with native tokens.

**Tests**

- Add focused Robolectric/JUnit tests under `app/src/test/java/com/deepseekharness/app/ui/` for every component and screen group.
- Extend existing session recovery, navigation, draft, terminal and package identity tests rather than replacing them.

---

### Task 1: Restore Reproducible Build Inputs and Lock diag3 Identity

**Files:**
- Modify: `app/build.gradle`
- Modify: `diagnostic-build/README.md`
- Create: `app/src/test/java/com/deepseekharness/app/ui/Diag3IdentityTest.java`

**Interfaces:**
- Consumes: official source archive SHA256 and diagnostic signing archive documented by diag2.
- Produces: buildable diag3 source tree with `BuildConfig.APPLICATION_ID`, `VERSION_CODE`, and `VERSION_NAME` fixed for all later tasks.

- [ ] **Step 1: Write the failing identity test**

Create `Diag3IdentityTest` asserting application ID `dsh.android.client.diagnostics`, code `192`, name `0.2.1-diag3-dsh0.2.1-rc.2`, minSdk 30, and provider authorities prefixed by the diagnostic package.

- [ ] **Step 2: Run the test and record the expected red result**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*Diag3IdentityTest' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: FAIL because diag2 still reports code 191/name diag2.

- [ ] **Step 3: Restore only fixed large inputs through `tools/ci-assets.py` and verify proofs**

Use the official `DSHA-0.2.1-rc.2-20261022.2100-source.zip` with SHA256 `ef134fe8713bfa4d6cf3bdea883be1591246d4b78ace16bd924f6d739188425a`; run `prepare-backup-assets.py --write`, `prepare-runtime-descriptor.py --write`, and `prepare-test-runtime.py`. Do not overwrite modified source files.

- [ ] **Step 4: Set diag3 version fields without changing package/signing identity**

Modify `app/build.gradle` to code 192/name diag3; retain the existing environment-backed `dsha-diagnostics` PKCS12 debug signing config.

- [ ] **Step 5: Run identity and existing diagnostic package tests**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*Diag3IdentityTest' --tests '*DiagnosticPackageTest' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle diagnostic-build/README.md app/src/main/assets/runtime-descriptor.json app/src/main/assets/managed-package-proofs.json app/src/test/java/com/deepseekharness/app/ui/Diag3IdentityTest.java
git commit -m "build: prepare diagnostic version 3"
```

### Task 2: Semantic GPT Theme and Primitive Tokens

**Files:**
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptUiTokens.java`
- Create: `app/src/main/res/values/gpt_ui.xml`
- Create: `app/src/main/res/values-night/gpt_ui.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values/styles.xml`
- Create: `app/src/main/res/drawable/gpt_surface_*.xml`
- Test: `app/src/test/java/com/deepseekharness/app/ui/GptUiTokensTest.java`

**Interfaces:**
- Produces: `GptUiTokens.PageKind { CHAT, FORM, TERMINAL }`, `maxWidthDp(PageKind)`, semantic resources `gpt_bg`, `gpt_sidebar`, `gpt_raised`, `gpt_text`, `gpt_muted`, `gpt_line`, and shared widget styles.

- [ ] **Step 1: Write failing token tests**

Assert CHAT=800dp, FORM=760dp, TERMINAL=-1/full; light values match `#FFFFFF/#F7F7F8/#F1F1F1/#171717/#676767/#E7E7E7`; dark values match the spec through Robolectric night qualifiers; all button styles expose minHeight 48dp.

- [ ] **Step 2: Run token tests to verify red**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*GptUiTokensTest' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: FAIL because tokens/styles do not exist.

- [ ] **Step 3: Implement semantic resources and `GptUiTokens`**

Use resource aliases so legacy `surface/card/raised/text/line/primary` resolve to the new semantic palette during migration; do not leave blue as the default primary action.

- [ ] **Step 4: Run token tests**

Expected: PASS in both day and night qualifiers.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui/GptUiTokens.java app/src/main/res app/src/test/java/com/deepseekharness/app/ui/GptUiTokensTest.java
git commit -m "feat: add GPT semantic UI tokens"
```

### Task 3: Responsive Shell and Navigation

**Files:**
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptResponsive.java`
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptShellLayout.java`
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptTopBar.java`
- Replace: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/MainActivity.java`
- Modify: `app/src/main/res/menu/bottom_nav_menu.xml`
- Test: `app/src/test/java/com/deepseekharness/app/ui/GptResponsiveTest.java`
- Test: `app/src/test/java/com/deepseekharness/app/ui/GptShellLayoutTest.java`

**Interfaces:**
- Produces: `GptResponsive.Mode classify(int widthDp)`, `GptShellLayout.setMode(Mode)`, `setSelectedDestination(int menuId)`, `openNavigation()`, `closeNavigation()`, and existing `R.id.fragment_container`.
- Preserves: existing destination IDs for launch/plugins/settings/terminal so `MainActivity` business routing remains valid.

- [ ] **Step 1: Write boundary tests**

Assert 760=PHONE, 761=DRAWER, 1023=DRAWER, 1024=SIDEBAR; PHONE/DRAWER expose one menu button and no persistent rail; SIDEBAR exposes one 272dp sidebar and no bottom navigation.

- [ ] **Step 2: Run shell tests to verify red**

Expected: FAIL with missing classes/layout behavior.

- [ ] **Step 3: Implement `GptResponsive` and `GptShellLayout`**

The shell owns navigation presentation only; retain `fragment_container`, recovery banner, task/theme actions, and FragmentManager routing. Drawer overlay must close on destination selection/back and restore selected destination without recreating the Fragment.

- [ ] **Step 4: Replace `MainActivity` bottom-nav binding with shell callbacks**

Keep launch/plugin/settings/terminal selection semantics, startup recovery destination checks, gated pages, and task intents unchanged.

- [ ] **Step 5: Add rotation/state restoration test**

Recreate `MainActivity` in DRAWER mode and assert the selected destination restores once, drawer is closed, and the current Fragment is not duplicated.

- [ ] **Step 6: Run shell and existing MainActivity/navigation tests**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*GptResponsiveTest' --tests '*GptShellLayoutTest' --tests '*MainActivity*Test' --tests '*StartupRecovery*Test' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui/GptResponsive.java app/src/main/java/com/deepseekharness/app/ui/GptShellLayout.java app/src/main/java/com/deepseekharness/app/ui/GptTopBar.java app/src/main/java/com/deepseekharness/app/ui/MainActivity.java app/src/main/res/layout/activity_main.xml app/src/main/res/menu app/src/test/java/com/deepseekharness/app/ui
git commit -m "feat: add responsive GPT application shell"
```

### Task 4: Conversation Home, Loading, and Automatic Entry

**Files:**
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptStatusPanel.java`
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptLoadingIndicator.java`
- Replace: `app/src/main/res/layout/activity_diagnostic_home.xml`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/ChatHomeActivity.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/ChatSetupCoordinator.java`
- Extend: `app/src/test/java/com/deepseekharness/app/ui/DiagnosticPackageTest.java`
- Create: `app/src/test/java/com/deepseekharness/app/ui/GptStatusPanelTest.java`
- Extend: `app/src/test/java/com/deepseekharness/app/util/ChatEntryPolicyTest.java`

**Interfaces:**
- Produces: `GptStatusPanel.render(ChatSetupCoordinator.State)`, one-shot auto-entry gate `ChatEntryPolicy.shouldEnter(State, Event)` using the existing persisted/manual-stop semantics.
- Consumes: existing status fields and session recovery result; does not start/stop services itself.

- [ ] **Step 1: Write status mapping and one-shot entry tests**

Cover STOPPED, PREPARING, READY, ERROR, RECOVERY_UNKNOWN; assert only READY/authenticated and RESTORED can auto-enter, repeated renders/recreation emit one navigation event, and failure/permission/maintenance states retain retry/details/log actions.

- [ ] **Step 2: Run tests to verify red**

Expected: FAIL because `GptStatusPanel` and the explicit one-shot gate are absent.

- [ ] **Step 3: Implement status panel and reduced-motion loading indicator**

Use real stage text and accessibility live region; animation reads the same system animation scale policy as `UiMotion` and becomes static at scale 0.

- [ ] **Step 4: Replace diagnostic home with conversation-first layout**

Include GPT top bar, centered welcome/status panel, 26dp draft composer, primary action, and contextual details/log controls. Preserve 64 KiB draft limit, debounce, attachment/submission gates and export-log behavior.

- [ ] **Step 5: Wire one-shot automatic entry**

When a newly started or restored service is authenticated, launch independent `WebPreviewActivity` once. Do not auto-enter after explicit stop, unknown recovery, permission prompt or maintenance block.

- [ ] **Step 6: Run home, draft, entry, session-recovery tests**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*GptStatusPanelTest' --tests '*DiagnosticPackageTest' --tests '*ChatEntryPolicyTest' --tests '*WebSessionRecoveryTest' --tests '*StoredWebSessionPrivateDirectoryTest' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui/GptStatusPanel.java app/src/main/java/com/deepseekharness/app/ui/GptLoadingIndicator.java app/src/main/java/com/deepseekharness/app/ui/ChatHomeActivity.java app/src/main/java/com/deepseekharness/app/ui/ChatSetupCoordinator.java app/src/main/res/layout/activity_diagnostic_home.xml app/src/test/java
git commit -m "feat: redesign conversation entry and status flow"
```

### Task 5: Shared GPT Page, Rows, Buttons, and Dialogs

**Files:**
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptPage.java`
- Create: `app/src/main/java/com/deepseekharness/app/ui/GptDialog.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/CardPage.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/UiNavigation.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/DshaDialogBuilder.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/CardSheet.java`
- Test: `app/src/test/java/com/deepseekharness/app/ui/GptPageTest.java`
- Test: `app/src/test/java/com/deepseekharness/app/ui/GptDialogTest.java`

**Interfaces:**
- Produces: `GptPage(Context, title, subtitle, PageKind)`, `section(title)`, `row(...)`, `button(..., ButtonKind)`, `topBar(backAction)`; `GptDialog.create(...)` selecting bottom sheet for PHONE and centered dialog otherwise.
- Compatibility: existing `CardPage` methods delegate to `GptPage` until every caller is migrated.

- [ ] **Step 1: Write primitive tests**

Assert max width, 48dp touch targets, row focus order, semantic button colors, long text wrapping, PHONE sheet vs tablet dialog, and dangerous actions retaining explicit title/body/confirm.

- [ ] **Step 2: Run tests to verify red**

- [ ] **Step 3: Implement components and compatibility adapters**

Do not alter existing callback order or dialog positive/negative semantics.

- [ ] **Step 4: Run component tests and representative existing dialog tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui/GptPage.java app/src/main/java/com/deepseekharness/app/ui/GptDialog.java app/src/main/java/com/deepseekharness/app/ui/CardPage.java app/src/main/java/com/deepseekharness/app/ui/UiNavigation.java app/src/main/java/com/deepseekharness/app/ui/DshaDialogBuilder.java app/src/main/java/com/deepseekharness/app/ui/CardSheet.java app/src/test/java/com/deepseekharness/app/ui
git commit -m "feat: add shared GPT page and dialog components"
```

### Task 6: Settings and Management Information Architecture

**Files:**
- Replace: `app/src/main/res/layout/fragment_settings.xml`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/ChatManagementActivity.java`
- Modify layouts/classes: `ConfigFragment`, `WorkspaceFragment`, `DeviceGrantsFragment`, `InstallFragment`, `OverlayFragment`, `AboutFragment`
- Create: `app/src/test/java/com/deepseekharness/app/ui/GptSettingsNavigationTest.java`

**Interfaces:**
- Consumes: `GptPage` and existing Fragment factories.
- Produces: grouped destinations RUNNING, DATA, DEVICE, APPEARANCE, SUPPORT, DIAGNOSTICS with unchanged underlying Fragment/Activity targets.

- [ ] **Step 1: Write navigation coverage test**

Assert every former settings destination is reachable exactly once, back returns to the grouped settings page, current values/switches remain present, and diagnostics/export is reachable without a failure state.

- [ ] **Step 2: Run test to verify red**

- [ ] **Step 3: Implement grouped settings page and management shell**

Remove the oversized back/more button layout; use `GptTopBar`, group rows and responsive max width. Keep runtime configuration, data, permissions, appearance, update/about and diagnostics behavior unchanged.

- [ ] **Step 4: Migrate the six settings subpages to shared styles**

Replace local card/background/button hardcoding with `GptPage` styles; keep existing IDs used by controllers/tests.

- [ ] **Step 5: Run settings, permission, backup and language tests**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*GptSettingsNavigationTest' --tests '*Config*Test' --tests '*DeviceGrants*Test' --tests '*Backup*Test' --tests '*Language*Test' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/fragment_settings.xml app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java app/src/main/java/com/deepseekharness/app/ui/ChatManagementActivity.java app/src/main/java/com/deepseekharness/app/ui/{ConfigFragment,WorkspaceFragment,DeviceGrantsFragment,InstallFragment,OverlayFragment,AboutFragment}.java app/src/test/java/com/deepseekharness/app/ui/GptSettingsNavigationTest.java
git commit -m "feat: redesign settings and management pages"
```

### Task 7: Plugins and Terminal Main Destinations

**Files:**
- Modify: `app/src/main/res/layout/fragment_plugins.xml`
- Modify: `app/src/main/res/layout/item_plugin.xml`
- Modify: `app/src/main/res/layout/plugin_list_header.xml`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java`
- Modify: `app/src/main/res/layout/fragment_terminal.xml`
- Modify: `app/src/main/res/layout/fragment_pty_terminal.xml`
- Modify: `app/src/main/res/layout/view_terminal_tabs.xml`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java`
- Test: `app/src/test/java/com/deepseekharness/app/ui/GptPluginPageTest.java`
- Extend: terminal lifecycle tests under `app/src/test/java/com/deepseekharness/app/core/`

**Interfaces:**
- Consumes: main shell content and GPT page styles.
- Preserves: plugin callbacks/IDs and PTY/static shell ownership.

- [ ] **Step 1: Write plugin visual-structure test**

Assert search/sort actions, flat rows, enabled/disabled labels, empty state, 48dp actions, and dangerous delete/disable confirmation remain present.

- [ ] **Step 2: Write terminal lifecycle regression test**

Navigate terminal → settings → terminal and recreate the Activity; assert one PTY owner, same active session/tab, no duplicate process, and back does not kill a running session.

- [ ] **Step 3: Run tests to verify current layout/lifecycle expectations fail**

- [ ] **Step 4: Migrate plugin layouts without changing repository calls**

- [ ] **Step 5: Apply GPT shell/top tabs to terminal while leaving terminal content high-contrast dark**

- [ ] **Step 6: Run plugin and full terminal test groups**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/fragment_plugins.xml app/src/main/res/layout/item_plugin.xml app/src/main/res/layout/plugin_list_header.xml app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java app/src/main/res/layout/fragment_terminal.xml app/src/main/res/layout/fragment_pty_terminal.xml app/src/main/res/layout/view_terminal_tabs.xml app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java app/src/test/java
git commit -m "feat: redesign plugin and terminal destinations"
```

### Task 8: Full-Screen Web Conversation and Native-to-Web Transition

**Files:**
- Modify: `app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java`
- Modify: `app/src/main/res/layout/activity_web_preview.xml`
- Modify: `app/src/main/java/com/deepseekharness/app/ui/UiMotion.java`
- Modify: `app/src/main/assets/web-integration/chat-ui.css`
- Modify: `app/src/main/assets/web-integration/chat-ui.js`
- Modify: `app/src/main/assets/web-integration/chat-draft.js`
- Extend: `tools/test-chat-ui.mjs`, `tools/test-chat-draft.mjs`
- Extend: Web preview/upload/microphone/PiP tests.

**Interfaces:**
- Consumes: native semantic colors and independent Activity launch from Task 4.
- Preserves: WebView instance ownership, upload chooser, downloads, microphone permissions, PiP and browser-origin restore.

- [ ] **Step 1: Add Web layout/selector tests**

Assert native-equivalent colors/radii, 800px content width, 1024px host drawer breakpoint, no forced sidebar display, and 64 KiB draft/attachment/submission guards.

- [ ] **Step 2: Add Web lifecycle regression tests**

Assert file chooser, microphone request, PiP entry/exit, draft handoff and back behavior still route to the same callbacks after theme/transition changes.

- [ ] **Step 3: Run Node and Java tests to verify red where new expectations differ**

- [ ] **Step 4: Align Web CSS/JS tokens and WebPreview system bars/transitions**

Do not add the native shell/sidebar around WebView; use edge-to-edge full screen and a short fade shared with `UiMotion`.

- [ ] **Step 5: Run all Web integration tests**

Run: `node tools/test-chat-ui.mjs && node tools/test-chat-draft.mjs && ./gradlew :app:testStandardDebugUnitTest --tests '*Web*Test' --tests '*Browser*Test' --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java app/src/main/res/layout/activity_web_preview.xml app/src/main/java/com/deepseekharness/app/ui/UiMotion.java app/src/main/assets/web-integration tools/test-chat-*.mjs app/src/test/java
git commit -m "feat: align full-screen conversation with GPT shell"
```

### Task 9: Independent Tool Activities and Long-Running Flows

**Files:**
- Modify: Activities using `CardPage`/custom roots, including `AutomaticBackupActivity`, `BackgroundTasksActivity`, `AgentTasksActivity`, `RecoveryActivity`, `StartupRecoveryActivity`, `NativeDataActivity`, `RetainedDataActivity`, `AdbPairActivity`, `AccessibilitySetupActivity`, `UpdateActivity`, `SkillsActivity`, `WorkspaceFilesActivity`, `StorageHelpActivity`, `DiagnosticActivity`, `ExtractActivity`, `PluginInstallActivity`.
- Modify: associated layouts `activity_adb_pair.xml`, `activity_diagnostics.xml`, `activity_extract.xml`, `activity_plugin_install.xml`, `activity_update.xml`.
- Create: `app/src/test/java/com/deepseekharness/app/ui/GptToolPageCoverageTest.java`

**Interfaces:**
- Consumes: compatibility `CardPage`→`GptPage`, `GptTopBar`, `GptDialog`.
- Preserves: each Activity class, Intent extras/results, long-running operation ownership and existing view IDs used by controllers.

- [ ] **Step 1: Write reflection/layout coverage test**

Instantiate every listed Activity or its page builder and assert it contains a GPT top bar, one bounded content host, no legacy blue primary/background resource, and all original critical action IDs.

- [ ] **Step 2: Run coverage test to verify red**

- [ ] **Step 3: Migrate CardPage callers first through the compatibility adapter**

- [ ] **Step 4: Migrate custom-root/XML Activities while preserving Intent/result contracts**

- [ ] **Step 5: Add long-operation recreation tests**

Cover extraction, update and backup recreation; assert UI rebinds without starting a second operation and cancel/result callbacks remain once-only.

- [ ] **Step 6: Run tool-page, update, backup, recovery and extraction tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui app/src/main/res/layout app/src/test/java/com/deepseekharness/app/ui/GptToolPageCoverageTest.java
git commit -m "feat: migrate native tool pages to GPT components"
```

### Task 10: Onboarding, Dialogs, Accessibility, Localization, and Layout Matrix

**Files:**
- Modify: `WelcomeActivity.java`, `OnboardingUi.java`, `activity_welcome.xml`, `welcome_page1.xml`, `welcome_page2.xml`, `welcome_page3.xml`.
- Modify: `dialog_about.xml`, `DshaSelectView.java`, and remaining dialogs/sheets.
- Create: `app/src/test/java/com/deepseekharness/app/ui/GptLayoutMatrixTest.java`
- Create: `app/src/test/java/com/deepseekharness/app/ui/GptAccessibilityTest.java`

**Interfaces:**
- Consumes: all shared components.
- Produces: final coverage across 360×640, 412×915, 800×1280, 1024×800; day/night; zh/en; fontScale 1.3; animation scale 0.

- [ ] **Step 1: Write matrix tests for representative home/settings/plugin/terminal/error screens**

Assert no critical view is clipped/off-screen, long English wraps, sidebar/drawer mode is correct, fixed actions remain reachable, and content max widths match tokens.

- [ ] **Step 2: Write accessibility tests**

Assert icon buttons have descriptions, focus order follows visual order, status live region is polite, color is not the sole status signal, and loading indicator is static when animations are disabled.

- [ ] **Step 3: Run tests to verify red**

- [ ] **Step 4: Redesign onboarding and remaining dialogs; fix matrix failures**

- [ ] **Step 5: Run matrix/accessibility and language tests**

Expected: PASS for all specified configurations.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/deepseekharness/app/ui app/src/main/res/layout app/src/main/res/values* app/src/test/java/com/deepseekharness/app/ui
git commit -m "feat: finish onboarding and accessible responsive UI"
```

### Task 11: Regression, Packaging, and Delivery

**Files:**
- Modify: `diagnostic-build/VERIFICATION.md`
- Create: `delivery/dsha-diagnostics-3-report.md`
- Produce: `delivery/dsha-diagnostics-3-arm64.apk`
- Produce: `delivery/dsha-diagnostics-3-source.zip`

**Interfaces:**
- Consumes: completed diag3 branch, fixed inputs and diagnostic signing key.
- Produces: signed installable APK, source snapshot without private key/large inputs, test/build report.

- [ ] **Step 1: Run targeted UI, recovery, draft, terminal and identity suites**

Run all new tests plus `SessionDiagnosticTest`, `WebSessionRecoveryTest`, `WebSessionProofTest`, `StoredWebSessionPrivateDirectoryTest`, startup/stop protection, draft, plugin, terminal and navigation groups.

Expected: 0 failures/errors/skips for the targeted suite.

- [ ] **Step 2: Run the full standard debug unit suite**

Run: `./gradlew :app:testStandardDebugUnitTest --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: record exact pass/fail/skip counts; do not hide inherited upstream failures.

- [ ] **Step 3: Build once from clean generated outputs**

Run: `./gradlew :app:clean :app:assembleStandardDebug --no-daemon --console=plain --max-workers=1 --no-watch-fs`

Expected: BUILD SUCCESSFUL and one `app-standard-debug.apk`.

- [ ] **Step 4: Verify package, resources and signature**

Run ZIP CRC; reject `*.tmp`/random compressor artifacts; run `aapt dump badging`, `zipalign -c -P 16 -v 4`, and `apksigner verify --verbose --print-certs`. Assert package/name/code 192/ARM64/minSdk30 and exact certificate digest.

- [ ] **Step 5: Verify source snapshot**

Create `SOURCE-SHA256.json`; exclude PKCS12/keystore/credentials, caches, user data and fixed large inputs; reopen ZIP and verify every manifest hash.

- [ ] **Step 6: Write honest verification report**

Separate static/unit/build evidence from unverified target-device behavior. Include the one-time diag2 stale-process caveat and exact log lines required for recovery confirmation.

- [ ] **Step 7: Perform target-device checklist**

On vivo/Android 16: stop original DSHA, cover-install diag3 without clearing data, test first run/reopen, export logs before any manual stop, verify `WRITE_VERIFIED`, `SAVED`, `RESTORED`, and capture 412/800/1024-width screenshots where available.

- [ ] **Step 8: Commit delivery metadata**

```bash
git add diagnostic-build/VERIFICATION.md delivery/dsha-diagnostics-3-report.md
git commit -m "docs: record diagnostic 3 verification"
```

## Self-Review Result

- Spec coverage: every design section maps to Tasks 2–11; build provenance and versioning are covered by Task 1.
- Step scan: each task has a red test, minimal implementation boundary, green verification and commit.
- Type consistency: `GptResponsive.Mode`, `GptUiTokens.PageKind`, `GptShellLayout`, `GptPage`, `GptTopBar`, `GptStatusPanel`, `GptDialog` names are introduced once and reused unchanged.
- Review Focus: exact breakpoints, one-shot auto-entry, Web/PTY lifecycle, accessibility/reduced motion, and cover-install data preservation each have an owning test/checklist.
- Proportion: the plan fixes interfaces and proof points without transcribing component implementations.
