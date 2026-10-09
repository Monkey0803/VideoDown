# Background download device evidence

Device: 4f0ef8ac, SHARK_PRS_A0, Android API 30, arm64-v8a, physical 1080×2400 / density 440 (unchanged).

- Final complete class: `instrumentation.txt`, OK (3 tests), 87.359 s.
- Latest checkpoint change: `instrumentation-checkpoint.txt`, OK (1 test), 44.584 s. Assert persisted failure bytes equal partial length (147456); screenshot shows 1% instead of the older throttled 0%.
- Real application foreground service verified with scoped `dumpsys activity services` `isForeground=true`; its actual notification pause PendingIntent was invoked. Home transfer bytes 8192→188416; pause retained 196608 bytes and stopped writes; byte-range resume completed with exact 8 MiB byte equality; service notification exited.
- Unexpected actual stopService produced FAILED/BACKGROUND_SERVICE and stable partial. Task screen shows Chinese title, precise interruption reason, and retry. Actual retry button recreated service and completed exact bytes.
- ContextWrapper startForegroundService SecurityException was wrapped; isolated engine failed BACKGROUND_SERVICE before any HTTP and preserved partial bytes.
- Screen-off segment was omitted because device has secure keyguard. No lockscreen/system preferences changed; these passing results do not establish screen-off behavior.
- `instrumentation-initial.txt` preserves harness failures: asynchronous UI retry read old FAILED too soon; MIUI notification update dropped FGS flag (0xa), so flag-filtered lookup was invalid. Final harness waits for new progress and checks real service foreground state independently.
- All generated UUID fixture records/media and cache evidence were cleaned. Final history `[]`, cache empty, no running app service. Original empty Home restored and visually confirmed in `after.png`/`after.xml`; light theme and display metrics unchanged. No app fatal crash observed. No production source modified by verifier; no commit.

Production review: DownloadServiceRequests, DownloadService, DownloadEngine, ServiceLocator, DownloadFailure and HistoryScreen read with call sites. No high-confidence CRITICAL/HIGH/MEDIUM/LOW findings; checkpoint revision also reviewed.
