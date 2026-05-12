# Android Privacy-Aware AI Request Router

An Android Library Module (`.aar`) that intercepts user voice/text requests via the
system `VoiceInteractionService`, runs them through a multi-stage on-device privacy
pipeline, and routes each request to one of three execution paths based on detected
PII and a user policy.

> Full design rationale lives in [android_privacy_router_implementation_plan.md](android_privacy_router_implementation_plan.md).
> This README covers what's in the repo today and how to run it.

---

## What it does

```
voice / text input
       ↓
[Stage 0]  capture transcript via SpeechRecognizer
       ↓
[Stage 1]  classify the request type (3 tiers)
              ├─ Tier 0: regex pre-filter
              ├─ Tier 1: MobileBERT fine-tuned classifier (NNAPI / TFLite)
              └─ Tier 2: FunctionGemma fallback when Tier 1 confidence < 0.75
       ↓
[Stage 2]  detect PII (mode chosen by Stage 1 label)
              ├─ Tier 0: Android system TextClassifier
              ├─ Tier 1: DistilBERT-NER (NNAPI / TFLite) + Presidio regex
              └─ Contextual: keyword + role signals
       ↓
[Stage 3]  aggregate sensitivity score + apply policy
       ↓
   ┌──────────────┬────────────────────┬────────────────────┐
   ▼              ▼                    ▼                    ▼
Path A         Path B            Path B (redacted)      Path C
FunctionGemma  Local LLM         Local LLM via         Cloud API
device action  (Gemma 3 4B)      PII redactor          (raw query)
```

| Stage 1 label     | Stage 2 mode   | Default routing                    |
|-------------------|----------------|------------------------------------|
| `DEVICE_ACTION`   | skipped        | Path A (FunctionGemma)             |
| `PERSONAL_QUERY`  | full pipeline  | Path B (local LLM)                 |
| `FACTUAL_QUERY`   | Tier 0 only    | Path C (cloud)                     |
| `CONVERSATIONAL`  | Tier 0 + 1     | Path C, redacted if PII found      |
| `AMBIGUOUS`       | full pipeline  | Path B / redact-then-cloud         |

---

## Module layout

```
privacy-router/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── res/xml/interaction_service.xml
    │   ├── assets/                          ← drop model files here (see below)
    │   └── kotlin/com/example/privacyrouter/
    │       ├── PrivacyRouterVoiceService.kt        VoiceInteractionService entry point
    │       ├── PrivacyRouterSessionService.kt      session factory
    │       ├── PrivacyRouterSession.kt             listens via SpeechRecognizer
    │       ├── pipeline/
    │       │   ├── PrivacyRouterPipeline.kt        orchestrates all 4 stages
    │       │   ├── stage1/   Stage 1 classifier (regex + MobileBERT + FunctionGemma)
    │       │   ├── stage2/   Stage 2 PII detection (TextClassifier + NER + contextual + Presidio)
    │       │   └── stage3/   Stage 3 sensitivity scorer + policy engine
    │       ├── execution/    Paths A (FunctionGemma + ActionExecutor), B (LocalLlmEngine), C (CloudApiClient)
    │       ├── redaction/    PiiRedactor (tokenize → restore)
    │       ├── policy/       PolicyConfigLoader (Moshi JSON)
    │       ├── ml/           TfLiteLoader, WordPieceTokenizer
    │       └── model/        Data classes (RequestLabel, PiiEntity, RoutingDecision, PolicyConfig, …)
    └── test/                 JVM unit tests (scorer, policy, redactor, span merger, regex, JSON loader)
```

---

## Requirements

- **Android Studio** (Hedgehog or newer) with Gradle 8.9
- **Kotlin** 1.9.25, **Android Gradle Plugin** 8.5.2
- **JDK** 17
- **Target device**: Pixel 9/10 recommended (Tensor G4 + 12 GB RAM); minimum is API 31
- For model assets you may also need: Python 3.10+, `transformers`, `optimum`, `onnx-tf`, and a GPU for fine-tuning (see the implementation plan)

---

## Build

```bash
# One-time: generate the gradlew scripts (only gradle-wrapper.properties is checked in)
gradle wrapper --gradle-version 8.9 --distribution-type bin

# Build the AAR
./gradlew :privacy-router:assembleRelease
# Output: privacy-router/build/outputs/aar/privacy-router-release.aar

# Run unit tests (no device required)
./gradlew :privacy-router:test
```

---

## Installing as the device assistant

The library exposes itself as a `VoiceInteractionService`. To activate it on a Pixel
test device:

1. Build and install a host app that depends on this `.aar`.
2. **Settings → Apps → Default apps → Digital assistant app** → select the host app.
3. Optional, for hotword: grant the elevated permission via ADB:

   ```bash
   adb shell pm grant <your.package.id> android.permission.CAPTURE_AUDIO_HOTWORD
   ```

4. Long-press the home button (or use the assist gesture) to invoke the session.

---

## Model assets (drop-in)

Every model-backed engine first checks for its asset; if missing, it falls back to a
heuristic so the pipeline stays runnable end-to-end. To activate real inference, drop
the files into `privacy-router/src/main/assets/` (or, for the LLMs, into
`/data/local/tmp/` on device):

| Engine                                | Asset path                                 | How to produce                                                        |
|---------------------------------------|--------------------------------------------|-----------------------------------------------------------------------|
| Stage 1 — `MobileBertClassifier`      | `assets/mobilbert_classifier.tflite` + `assets/mobilbert_vocab.txt` | Fine-tune MobileBERT on remapped CLINC150 → 5 labels, export to TFLite int8 |
| Stage 2 — `NerModelDetector`          | `assets/ner_model.tflite` + `assets/ner_vocab.txt`                  | Convert `dslim/bert-base-NER` to ONNX → TFLite int8                   |
| Path A — `FunctionGemmaEngine`        | `/data/local/tmp/function_gemma_270m.task`                          | LoRA fine-tune Gemma 3 270M on Mobile Actions → export to LiteRT      |
| Path B — `LocalLlmEngine`             | `/data/local/tmp/gemma3_4b_q4.task`                                 | Download Gemma 3 4B Q4 `.task` file from Google                       |

The `LABELS` array in [`MobileBertClassifier`](privacy-router/src/main/kotlin/com/example/privacyrouter/pipeline/stage1/MobileBertClassifier.kt)
and the `TAGS` array in [`NerModelDetector`](privacy-router/src/main/kotlin/com/example/privacyrouter/pipeline/stage2/NerModelDetector.kt)
must match the label/tag order used at training time — adjust them after fine-tuning.

To push the LLM `.task` files to a connected device:

```bash
adb push gemma3_4b_q4.task /data/local/tmp/
adb push function_gemma_270m.task /data/local/tmp/
```

---

## Configuring the policy

The default policy lives in [`PolicyConfig.default()`](privacy-router/src/main/kotlin/com/example/privacyrouter/model/PolicyConfig.kt).
To override it from JSON (for example via `assets/policy.json`):

```kotlin
val policy = PolicyConfigLoader().load(context.assets.open("policy.json"))
val pipeline = PrivacyRouterPipeline.build(context, cloud = cloud, policy = policy)
```

Schema:

```json
{
  "version": 1,
  "defaultAction": "redact_then_cloud",
  "scoreThresholds": { "local": 0.70, "redact_then_cloud": 0.35, "cloud": 0.0 },
  "entityRules": [
    { "type": "HEALTH",     "action": "route_local",       "override": true  },
    { "type": "FINANCIAL",  "action": "route_local",       "override": true  },
    { "type": "PERSON",     "action": "redact_then_cloud", "override": false }
  ],
  "signalRules": [
    { "signal": "HEALTH_CONTEXT",    "action": "route_local", "override": true }
  ],
  "allowList": ["weather", "news", "translation"],
  "denyList":  ["my doctor", "my bank", "my therapist"]
}
```

Evaluation is deterministic, first match wins: allow-list → deny-list → entity
overrides → signal overrides → score thresholds.

---

## Cloud API (Path C)

`PrivacyRouterPipeline.build(context, cloud = ...)` accepts an optional `CloudApiClient`.
Without it, any routing decision of `CLOUD` or `REDACT_THEN_CLOUD` returns
`ExecutionResult.Error("cloud client not configured")`. Construct one with the
endpoint and bearer token of your provider (Gemini, OpenAI, etc.) and pass it in.

---

## Status

- Architecture and data flow: complete.
- Real TFLite/MediaPipe integrations: wired, but each engine falls back to a heuristic
  until the corresponding model asset is dropped in.
- Unit tests: scorer, policy engine, redactor, span merger, regex pre-filter,
  contact-sensitivity guard, policy JSON loader.
- Not yet covered: instrumented tests against real assets on a Pixel device, and
  end-to-end energy / latency benchmarks (see Chapter 4 metrics in the plan).
