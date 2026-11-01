# Native review fixes implementation plan

> Execute using subagent-driven-development for the isolated device-verification task; root owns production fixes. User approved the reviewed scope; continue without another design approval gate.

Goal: recover safely from invalid navigation and failed unissued checkpoints, retain meaningful timeout feedback, cover Fab orchestration and restore repeatable device-recovery verification.
Architecture: keep Kotlin Views, existing synchronous journal durability and source-restricted WebView messaging. Do not add dependencies or rewrite the whole task engine. Measure synchronous commits before changing the persistence concurrency model.
Decision: keep synchronous durability for this preview. VM2 benchmark measured separate serialization and commit phases: 21KB ordinary journal is small; a 425KB retained-history journal has a one-frame jank risk. Explicitly record this accepted cost and physical-device uncertainty; do not claim all main-thread I/O is removed.

Global constraints: minimum API26, compile/target35; original signing/storage formats; no new real orders; existing reservations cannot be cleared just to retry; preserve untracked IDE files and the old workspace; local Git checkpoints only, no public push.

## Task 1 - URL failures and Fab coordinator regression
Files: claim/ClaimCoordinator.kt, claim/ClaimCatalogue.kt, web/BrowserPagePolicy.kt, web/OfficialBrowserController.kt, MainActivity.kt, model/CatalogItem.kt, related JVM and diagnostic tests.
- [ ] Write failing tests for malformed navigation cancelling Fab confirmation, invalid/unofficial cached product URLs failing before browser publication, and Fab tier mismatch/account drift/license invalidation/ownership.
- [ ] Run focused JVM tests in D:/Android/Builds/runa-native-review (no apostrophe).
- [ ] Add pure official product URL validation at cache/model boundary; malformed navigation revokes the current Fab confirmation without adding Android dependencies.
- [ ] Browser show failures report task-specific errors through a callback, never crash the Activity or reply using an obsolete nonce.
- [ ] Verify focused tests and checkpoint.

## Task 2 - Durable reservation failure recovery
Files: ClaimJournal.kt, claim/ClaimCoordinator.kt, claim and journal JVM tests.
- [ ] Fail a test that retries the first unissued stage after save failure and storage recovery.
- [ ] Introduce a targeted reserve-and-save transaction: rollback only this unissued stage if save reports failure. Existing successful stages/reservations remain protected.
- [ ] Publish paused/error state after a failed save; do not grant page permission. Test submit-stage failure retains prior product reservation; crash-reconstructed records remain conservative.
- [ ] Keep next/fail reentrancy unchanged unless a concrete test demonstrates incorrect behavior.

## Task 3 - Read reminder and local UI draft
Files: web/ReadDeadline.kt (pure policy if useful), OfficialBrowserController.kt, AccountPanel.kt, native XML and focused tests.
- [ ] Reproduce waiting messages cancelling the generic 40s reminder.
- [ ] Keep the reminder clock independent of progress text; append a reminder to specific waiting status, do not terminate login/captcha/order and do not erase explicit network failures.
- [ ] Preserve unsubmitted country input across busy/account updates; add live-region to journal error. Keep history view accessible during work with its actions disabled.
- [ ] Test policies and native diagnostic states.

## Task 4 - Repeatable device verification
Files: debug-only fixture host(s), NativeVerification.kt and tests/device/native-smoke.py plus docs.
- [ ] In an isolated debug host backed by separate diagnostic prefs, persist an in-flight journal/reservation, externally kill the process, restart and resume read-only; assert same run/reservation and no order permission. Never write real account journal/prefs.
- [ ] Save a standard-library-only adb driver requiring explicit serial and output dir, checking every command/result. Include real release read-only UI checks and debug recovery modes separately.
- [ ] Add measured main-thread commit benchmark in separate diagnostic prefs; report payload size and p50/p95/max, do not conflate with physical-device latency.
- [ ] Repeat popup/nonce/frame/timeout/error-redaction checks as affected.

## Task 5 - Verification and delivery
- [ ] Code-simplifier review of changed code, independent full diff review; fix substantive findings and rerun affected checks.
- [ ] Run JVM/88 JS/Android debug tests and signed release build+Lint; verify actual release startup, native UI and image states; no real new orders.
- [ ] Record remaining synchronous IO decision from measurements; update version to preview.2, docs, APK bytes/hash and test boundaries.
- [ ] Commit, local tag, deliver versioned desktop APK and update desktop source in place; retain original preview artifact and old source. No GitHub push.
