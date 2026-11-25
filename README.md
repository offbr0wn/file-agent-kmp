# File Agent (KMP, Android)

A Hermes-style agent that organises files on your phone.
You ask in plain English. It explores, proposes a plan, you approve, it moves files. Every run can be undone.

## How it works

```
You: "Tidy my Downloads"
   │
   ▼
FileAgent loop (shared/commonMain)
   ├─ list_dir / file_info      → run for real (read only)
   ├─ propose_mkdir / propose_move → added to a Plan (nothing moves)
   └─ finish
   │
   ▼
Plan review screen  →  tick / untick  →  Apply
   │
   ▼
PlanExecutor → AndroidFileSystem → UndoBatch saved (Undo button)
```

Background: `AutoSortWorker` runs daily, rules only (no LLM), sorting `Download/` into `Download/Sorted/<Type>`.

## Layout

| Path | What |
|---|---|
| `shared/commonMain/agent` | Agent loop, tools, plan, executor, undo |
| `shared/commonMain/llm` | `LlmClient` interface + Claude client (Ktor) |
| `shared/commonMain/rules` | No-LLM `RuleSorter` |
| `shared/androidMain/fs` | `AndroidFileSystem` (java.io, guards paths) |
| `androidApp` | Compose UI, ViewModel, WorkManager |

## Run

1. Open the folder in Android Studio and let Gradle sync.
2. Run `./gradlew :shared:testDebugUnitTest` (fake LLM + fake file system).
3. Run the app, grant **All files access**, paste a Claude API key.

## Safety rails

- No delete tool. Ever.
- Paths can't escape storage root. `Android/` and hidden folders are blocked.
- Moves fail if the destination exists.
- Nothing changes until you tap Apply.

## Next steps

- Swap the model: implement `LlmClient` (OpenRouter, Gemini Nano via ML Kit GenAI).
- Add `read_text_snippet` tool so it can sort PDFs/notes by content.
- Store the API key with Keystore, or proxy via your own backend.
- Play Store: All-files access needs a file-manager declaration. For side-loading, nothing extra.

## Get the APK without Android Studio

1. Create an empty private repo on GitHub.
2. Push this folder to it.
3. Open the repo's **Actions** tab. "Build APK" runs automatically (about 5 min).
4. Open the finished run, download **FileAgent-debug-apk**, unzip, install the `.apk`.
