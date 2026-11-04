# Native feature completion implementation plan

**Goal:** Complete the user's explicitly requested native DSHA chat features from the recoverable build 209 source.

**Architecture:** Keep Android Views and the authenticated local DSH gateway. Small RPC collaborators reuse the existing service-identity-guarded transport. All mutation dialogs bind their lifetime, request generation, session and backend identity; no uncertain write is automatically replayed.

**Tech stack:** Existing Java 17 / Android SDK 37 application; pinned DSH 0.2.1-rc.2 contracts. Native rich text may use reviewed libraries without a whole-chat browser.

## Work units

1. Verify archive identity and recover existing work if accessible. Preserve build 209 originals. Read exact backend packages from lockfile URLs and verify SRI before relying on their contracts.
2. Account/API management: native-created provider list, selection, guarded field edits/deletion, account logout and cancellation. Keep shared credentials and unknown configuration fields intact.
3. Scheduled tasks: catalog, compare-and-swap editing and explicit deletion. Do not invent unsupported direct creation or pause endpoints.
4. Session management: full-text search, rename, archive/restore, and message-bound forks. Active-session archive never silently stops tasks.
5. Chat presentation: exact live tool approval events, bounded tool-result cards, copy/feedback, known-failure retry, API usage, tables/formulas, durable image previews and workspace file/document browsing.
6. Advanced controls: source-backed command, goal and permission-preset interfaces with explicit mutation review.
7. Verification: targeted protocol/lifecycle tests, real Android compilation and applicable aggregate checks. Report pre-existing failures independently. Verify startup recovery and phone layout with available tests; no physical-device claim without a connected test device.
8. Produce reviewable source and evidence. Do not publish credentials, user data or local toolchains. Public upload belongs to the parent publication step after verification.

## Review focus

- Backend replacement, session switches, dismissal and double taps must not execute stale actions.
- Approval audit events are display-only. Only current live correlated requests may return allow-once or reject.
- Interrupted writes remain uncertain until read-back, never retried automatically.
- Archive, API edits and schedules preserve unrelated metadata and respect backend concurrency contracts.
- File previews do not execute HTML/JavaScript or forward local credentials to arbitrary URLs.
- Narrow phones, long text, large fonts and unsupported document formats retain an accessible fallback.

## Known validation boundaries

The original source-only archive omits the SDK, signing keys and large pinned inputs. These must be provisioned separately. Existing historical release/device claims do not describe this change. A compiled unsigned/debug candidate is not a same-signature upgrade release.
