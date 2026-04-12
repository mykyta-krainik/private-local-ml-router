# Android Privacy-Aware AI Request Router — Implementation Plan

## Project overview

Build an Android Library Module (`.aar`) that intercepts user voice/text requests via
`VoiceInteractionService`, runs them through a multi-stage on-device privacy pipeline,
and routes them to either a local LLM, FunctionGemma (device actions), or a cloud API
based on detected PII and user policy.

**Target device:** Google Pixel 9 / 10 (Android 14+, 12 GB RAM, Tensor G4 NPU)  
**Language:** Kotlin  
**Min SDK:** Android 12 (API 31)  
**Build output:** Android Library Module publishable as `.aar`

---

## Architecture overview

```
User voice/text input
        ↓
[VoiceInteractionService] ← intercepts assistant session
        ↓
[Stage 0] Raw input normalization (audio → text via SpeechRecognizer)
        ↓
[Stage 1] Request type classifier (two-tier)
        ↓
[Stage 2] PII detection (mode depends on Stage 1 label)
        ↓
[Stage 3] Sensitivity aggregation + policy engine
        ↓
      ┌──────────────────────────────┐
      │                              │
[Path A]                      [Path B]              [Path C]
FunctionGemma             Local LLM (Gemma 3 4B)   Cloud API
(device actions)          (sensitive queries)       (safe queries,
                                                     optional redaction)
```

---

## Stage 0 — Input capture and normalization

### What it does
Registers the app as the default Android assistant, captures the audio stream or text
input from the `VoiceInteractionSession`, and produces a clean text transcript for the
pipeline.

### Key Android APIs
- `VoiceInteractionService` — register in `AndroidManifest.xml` with
  `android.permission.BIND_VOICE_INTERACTION`
- `VoiceInteractionSession` — override `onShow()` to receive the session
- `android.speech.SpeechRecognizer` — for speech-to-text transcription
- `AlwaysOnHotwordDetector` — optional, requires `CAPTURE_AUDIO_HOTWORD` permission
  (grant via ADB on dev device: `adb shell pm grant <package> android.permission.CAPTURE_AUDIO_HOTWORD`)

### Setup steps
1. Register service in `AndroidManifest.xml`:
```xml
<service
    android:name=".PrivacyRouterVoiceService"
    android:permission="android.permission.BIND_VOICE_INTERACTION">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
    <meta-data
        android:name="android.voice_interaction"
        android:resource="@xml/interaction_service" />
</service>
```

2. Create `res/xml/interaction_service.xml` pointing to your session and settings.

3. Implement `VoiceInteractionSession`:
```kotlin
class PrivacyRouterSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startListening()
    }

    private fun startListening() {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val transcript = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                // hand off to pipeline
                lifecycleScope.launch { pipeline.process(transcript) }
            }
            // implement other required callbacks
        })
    }
}
```

4. Warm up all downstream models in `VoiceInteractionService.onCreate()` to avoid
   cold-start latency on first request.

### Output
`RawInput(transcript: String, timestampMs: Long)`

---

## Stage 1 — Request type classifier

### What it does
Classifies the incoming query into one of five categories. The category determines
which PII detection mode Stage 2 uses and which execution path Stage 3 can choose.

### Output labels
| Label | Meaning | Stage 2 mode |
|---|---|---|
| `DEVICE_ACTION` | Timer, alarm, call, flashlight, calendar | **Skip Stage 2** → FunctionGemma |
| `PERSONAL_QUERY` | User's health, finance, relationships | Full pipeline + contextual |
| `FACTUAL_QUERY` | General knowledge, no personal context | Tier 0 only |
| `CONVERSATIONAL` | Chit-chat, ambiguous phrasing | Tier 0 + Tier 1 parallel |
| `AMBIGUOUS` | Low-confidence classification | Full pipeline, conservative threshold |

### Two-tier implementation

**Tier 0 — Regex pre-filter (< 1 ms)**

Run first. On high-confidence pattern match, skip Tier 1 entirely.

```kotlin
object RegexPreFilter {
    private val deviceActionPatterns = listOf(
        Regex("set (a )?(?:timer|alarm)", IGNORE_CASE),
        Regex("turn (on|off) (?:the )?(?:flashlight|wifi|bluetooth)", IGNORE_CASE),
        Regex("add (?:a )?(?:calendar )?event", IGNORE_CASE),
        Regex("call (?:my )?\\w+", IGNORE_CASE),
        Regex("play .+ (?:on|via) \\w+", IGNORE_CASE),
    )

    fun match(query: String): RequestLabel? {
        if (deviceActionPatterns.any { it.containsMatchIn(query) })
            return RequestLabel.DEVICE_ACTION
        return null  // no match → proceed to Tier 1
    }
}
```

**Tier 1 — MobileBERT fine-tuned classifier (~10–20 ms on NNAPI)**

Model: `MobileBERT` fine-tuned on remapped CLINC150 dataset + synthetic privacy-specific
examples (~500–2000 examples per class).

Training pipeline:
1. Download CLINC150 dataset
2. Remap 150 intent classes → 5 labels (see mapping table below)
3. Generate ~300 synthetic examples per class covering privacy-specific patterns
   (use cloud LLM for generation, manually review)
4. Fine-tune MobileBERT with HuggingFace Transformers, 3–5 epochs, AdamW
5. Export to TFLite with int8 dynamic range quantization:
```python
converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_path)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()
```
6. Place `.tflite` file in `src/main/assets/`

CLINC150 → label mapping:
- `alarm`, `reminder`, `calendar`, `phone`, `timer`, `lights` → `DEVICE_ACTION`
- `balance`, `transactions`, `bill_due`, `medical_bills` → `PERSONAL_QUERY`
- `weather`, `definition`, `translate`, `what_is_fact` → `FACTUAL_QUERY`
- `jokes`, `meaning_of_life`, `tell_me_a_story` → `CONVERSATIONAL`

Android inference:
```kotlin
class MobileBertClassifier(context: Context) {
    private val interpreter: Interpreter

    init {
        val options = Interpreter.Options().apply {
            addDelegate(NnApiDelegate())
        }
        interpreter = Interpreter(loadModelFile(context, "mobilbert_classifier.tflite"), options)
    }

    fun classify(query: String): Pair<RequestLabel, Float> {
        // tokenize, run inference, return label + softmax confidence
    }
}
```

**Tier 2 — FunctionGemma fallback for AMBIGUOUS (< 0.75 confidence)**

Only invoked when MobileBERT confidence is below threshold. Frame classification
as a function call:
```json
{
  "function": "classify_request",
  "parameters": {
    "category": "DEVICE_ACTION|PERSONAL_QUERY|FACTUAL_QUERY|CONVERSATIONAL|AMBIGUOUS",
    "confidence": 0.0,
    "reasoning": ""
  }
}
```

**Confidence threshold:** 0.75 (tune empirically on held-out test set, report in thesis)

**Contact sensitivity guard:** For any `DEVICE_ACTION` involving a contact reference
("call my therapist"), apply a deny-list of sensitive role descriptors before dispatching:
```kotlin
val sensitiveRoles = setOf("therapist", "doctor", "lawyer", "psychiatrist",
    "counselor", "sponsor", "broker", "accountant")

fun guardContactAction(query: String, label: RequestLabel): RequestLabel {
    if (label == RequestLabel.DEVICE_ACTION &&
        sensitiveRoles.any { query.contains(it, ignoreCase = true) })
        return RequestLabel.PERSONAL_QUERY
    return label
}
```

### Output
`ClassificationResult(label: RequestLabel, confidence: Float, tierId: Int)`

---

## Stage 2 — PII detection

### What it does
Detects personally identifiable information in the query. Detection mode is determined
by the Stage 1 label — not every request runs the full pipeline.

### Detection mode routing
| Stage 1 label | Detection mode |
|---|---|
| `DEVICE_ACTION` | **Skipped** — no data leaves device anyway |
| `FACTUAL_QUERY` | **Tier 0 only** — escalate to Tier 1 if Tier 0 finds anything |
| `CONVERSATIONAL` | **Tier 0 + Tier 1 in parallel** |
| `PERSONAL_QUERY` | **Full pipeline** (Tier 0 + Tier 1 + contextual checks, parallel) |
| `AMBIGUOUS` | **Full pipeline** with conservative thresholds |

### Tier 0 — Android TextClassifier (2–5 ms)

Detects: addresses, phone numbers, emails, URLs, date-time expressions.

```kotlin
suspend fun runTier0(query: String): List<PiiEntity> =
    withContext(Dispatchers.Default) {
        val classifier = TextClassifier.create(context, TextClassifierOptions.defaultOptions())
        val request = TextLinks.Request.Builder(query).build()
        val result = classifier.generateLinks(request)
        result.links.map { link ->
            PiiEntity(
                span = link.start..link.end,
                text = query.substring(link.start, link.end),
                type = mapTextLinksType(link),
                confidence = link.getConfidenceScore(/* best type */),
                source = DetectionTier.TIER_0
            )
        }
    }
```

### Tier 1 — DistilBERT-NER TFLite model (20–40 ms on NNAPI)

Model: `dslim/bert-base-NER` distilled variant, exported to TFLite with int8
quantization (~40–60 MB).

Detects: PER (person names), LOC (locations), ORG (organizations), MISC.

Export pipeline:
1. Load `dslim/bert-base-NER` from HuggingFace
2. Convert to ONNX: `transformers.onnx` or `optimum`
3. Convert ONNX → TFLite via `onnx-tf` + TFLite converter
4. Apply int8 quantization
5. Place in `src/main/assets/`

Additionally: port Presidio's regex recognizers to Kotlin for structured PII patterns
(credit card numbers, SSNs, passport numbers, medical record numbers). These are pure
regex — no model needed.

```kotlin
object PresidioRegexRecognizers {
    private val creditCard = Regex("""\b(?:\d[ -]?){13,16}\b""")
    private val phoneNumber = Regex("""\+?[\d\s\-().]{7,15}""")
    // add remaining Presidio patterns
}
```

### Contextual checks (PERSONAL_QUERY and AMBIGUOUS only, ~1 ms)

Pure Kotlin — no model. Raises sensitivity score without redacting spans.

```kotlin
object ContextualPiiDetector {
    private val sensitiveRoles = Regex(
        "my (doctor|therapist|lawyer|accountant|sponsor|psychiatrist|counselor|broker|pastor)",
        IGNORE_CASE
    )
    private val implicitLocation = Regex(
        "at (home|work|the office|my usual place|my place)",
        IGNORE_CASE
    )
    // healthKeywords: load from bundled OpenFDA drug name list + symptom terms
    // financialKeywords: transaction verbs + account type terms

    fun detect(query: String): Set<Signal> {
        val signals = mutableSetOf<Signal>()
        if (sensitiveRoles.containsMatchIn(query)) signals += Signal.SENSITIVE_ROLE
        if (implicitLocation.containsMatchIn(query)) signals += Signal.IMPLICIT_LOCATION
        if (healthKeywords.any { query.contains(it, ignoreCase = true) })
            signals += Signal.HEALTH_CONTEXT
        if (financialKeywords.any { query.contains(it, ignoreCase = true) })
            signals += Signal.FINANCIAL_CONTEXT
        return signals
    }
}
```

### Parallel execution

```kotlin
suspend fun detectPii(query: String, mode: DetectionMode): PiiDetectionResult {
    val start = System.currentTimeMillis()
    return when (mode) {
        DetectionMode.TIER_0_ONLY -> {
            val tier0 = runTier0(query)
            val escalated = if (tier0.isNotEmpty()) runTier1(query) else emptyList()
            PiiDetectionResult(mergeAndDeduplicate(tier0, escalated), emptySet(),
                System.currentTimeMillis() - start, setOf(DetectionTier.TIER_0))
        }
        DetectionMode.PARALLEL -> {
            val tier0 = async(Dispatchers.Default) { runTier0(query) }
            val tier1 = async(Dispatchers.Default) { runTier1(query) }
            PiiDetectionResult(mergeAndDeduplicate(tier0.await(), tier1.await()),
                emptySet(), System.currentTimeMillis() - start,
                setOf(DetectionTier.TIER_0, DetectionTier.TIER_1))
        }
        DetectionMode.FULL -> {
            val tier0 = async(Dispatchers.Default) { runTier0(query) }
            val tier1 = async(Dispatchers.Default) { runTier1(query) }
            val contextual = async(Dispatchers.Default) {
                ContextualPiiDetector.detect(query)
            }
            PiiDetectionResult(mergeAndDeduplicate(tier0.await(), tier1.await()),
                contextual.await(), System.currentTimeMillis() - start,
                setOf(DetectionTier.TIER_0, DetectionTier.TIER_1, DetectionTier.CONTEXTUAL))
        }
    }
}
```

### Span deduplication
When both tiers detect the same span, keep the higher-confidence annotation.
For partial overlaps, take the union span and assign the higher-confidence type.

### Data classes

```kotlin
data class PiiDetectionResult(
    val entities: List<PiiEntity>,
    val contextualSignals: Set<Signal>,
    val detectionLatencyMs: Long,
    val tiersUsed: Set<DetectionTier>
)

data class PiiEntity(
    val span: IntRange,
    val text: String,
    val type: PiiType,
    val confidence: Float,
    val source: DetectionTier
)

enum class PiiType { PERSON, LOCATION, ORGANIZATION, ADDRESS, PHONE,
                     EMAIL, DATE_TIME, HEALTH, FINANCIAL, MISC }

enum class Signal { HEALTH_CONTEXT, FINANCIAL_CONTEXT, SENSITIVE_ROLE, IMPLICIT_LOCATION }
```

### Output
`PiiDetectionResult` passed to Stage 3.

---

## Stage 3 — Sensitivity aggregation + policy engine

### What it does
Combines Stage 1 and Stage 2 outputs into a single sensitivity score, then applies
user policy rules to produce a final routing action.

### Part A — Sensitivity scoring

**Entity type base weights** (grounded in GDPR Article 9 special categories):
```kotlin
val BASE_WEIGHTS = mapOf(
    PiiType.HEALTH         to 1.00f,
    PiiType.FINANCIAL      to 0.95f,
    PiiType.PHONE          to 0.90f,
    PiiType.ADDRESS        to 0.90f,
    PiiType.PERSON         to 0.75f,
    PiiType.LOCATION       to 0.65f,
    PiiType.ORGANIZATION   to 0.45f,
    PiiType.DATE_TIME      to 0.30f,
    PiiType.MISC           to 0.25f,
)
```

**Aggregation (max-dominant with diminishing returns):**
```kotlin
fun aggregateEntityScore(entities: List<PiiEntity>): Float {
    if (entities.isEmpty()) return 0.0f
    val maxWeight = entities.maxOf { BASE_WEIGHTS[it.type]!! * it.confidence }
    val additionalScore = entities
        .sortedByDescending { BASE_WEIGHTS[it.type]!! * it.confidence }
        .drop(1)
        .fold(0.0f) { acc, entity ->
            acc + (BASE_WEIGHTS[entity.type]!! * entity.confidence * 0.15f)
        }
    return (maxWeight + additionalScore).coerceAtMost(1.0f)
}
```

**Contextual signal boosts (capped at 0.50):**
```kotlin
val SIGNAL_BOOSTS = mapOf(
    Signal.HEALTH_CONTEXT      to 0.35f,
    Signal.FINANCIAL_CONTEXT   to 0.30f,
    Signal.SENSITIVE_ROLE      to 0.25f,
    Signal.IMPLICIT_LOCATION   to 0.15f,
)

fun contextualBoost(signals: Set<Signal>): Float =
    signals.sumOf { SIGNAL_BOOSTS[it]!!.toDouble() }.toFloat().coerceAtMost(0.50f)
```

**Stage 1 label multiplier:**
```kotlin
val LABEL_MULTIPLIERS = mapOf(
    RequestLabel.PERSONAL_QUERY  to 1.20f,
    RequestLabel.AMBIGUOUS       to 1.10f,
    RequestLabel.CONVERSATIONAL  to 1.00f,
    RequestLabel.FACTUAL_QUERY   to 0.80f,
    RequestLabel.DEVICE_ACTION   to 0.00f,
)
```

**Final score:**
```kotlin
fun computeSensitivityScore(
    entities: List<PiiEntity>,
    signals: Set<Signal>,
    label: RequestLabel
): Float {
    val entityScore = aggregateEntityScore(entities)
    val signalBoost = contextualBoost(signals)
    val multiplier = LABEL_MULTIPLIERS[label]!!
    return ((entityScore + signalBoost) * multiplier).coerceIn(0.0f, 1.0f)
}
```

**Score zones (default, before policy overrides):**
- `0.0 – 0.35` → cloud
- `0.35 – 0.70` → redact_then_cloud
- `0.70 – 1.0` → local LLM (Path B)

### Part B — Policy engine

**Policy config format** (stored locally as JSON, loaded at service startup):
```json
{
  "version": 1,
  "defaultAction": "redact_then_cloud",
  "scoreThresholds": {
    "local": 0.70,
    "redact_then_cloud": 0.35,
    "cloud": 0.0
  },
  "entityRules": [
    { "type": "HEALTH",     "action": "route_local",       "override": true },
    { "type": "FINANCIAL",  "action": "route_local",       "override": true },
    { "type": "PERSON",     "action": "redact_then_cloud", "override": false },
    { "type": "LOCATION",   "action": "cloud",             "override": false }
  ],
  "signalRules": [
    { "signal": "HEALTH_CONTEXT",    "action": "route_local", "override": true },
    { "signal": "FINANCIAL_CONTEXT", "action": "route_local", "override": true }
  ],
  "allowList": ["weather", "news", "translation"],
  "denyList": ["my doctor", "my bank", "my therapist"]
}
```

**Evaluation order (deterministic, first match wins):**

```kotlin
fun evaluate(
    query: String,
    entities: List<PiiEntity>,
    signals: Set<Signal>,
    score: Float,
    policy: PolicyConfig
): RoutingAction {
    // 1. allow-list → immediate cloud
    if (policy.allowList.any { query.contains(it, ignoreCase = true) })
        return RoutingAction.CLOUD

    // 2. deny-list → immediate local
    if (policy.denyList.any { query.contains(it, ignoreCase = true) })
        return RoutingAction.LOCAL

    // 3. entity override rules
    entities.forEach { entity ->
        policy.entityRules
            .find { it.type == entity.type && it.override }
            ?.let { return RoutingAction.from(it.action) }
    }

    // 4. signal override rules
    signals.forEach { signal ->
        policy.signalRules
            .find { it.signal == signal && it.override }
            ?.let { return RoutingAction.from(it.action) }
    }

    // 5. score thresholds
    return when {
        score >= policy.scoreThresholds.local           -> RoutingAction.LOCAL
        score >= policy.scoreThresholds.redactThenCloud -> RoutingAction.REDACT_THEN_CLOUD
        else                                            -> RoutingAction.CLOUD
    }
}
```

### Output
`RoutingDecision(action: RoutingAction, sensitivityScore: Float, firedRule: String?)`

where `RoutingAction` is one of: `LOCAL`, `REDACT_THEN_CLOUD`, `CLOUD`, `FUNCTION_GEMMA`

---

## Path A — Device action execution via FunctionGemma

### When it fires
Stage 1 label is `DEVICE_ACTION` (and contact sensitivity guard did not override it).

### Model
**FunctionGemma 270M** — Google's Gemma 3 270M fine-tuned for function calling.
Released December 2025.

- Size: 288 MB (int8 only)
- RAM: ~550 MB at runtime
- Speed: ~50 tokens/s on Pixel 8+ via LiteRT XNNPACK with 4 threads
- Deployment: LiteRT `.task` format, or GGUF via llama.cpp JNI
- Base accuracy: 58% → fine-tuned accuracy: 85% (on Mobile Actions benchmark)

**Fine-tuning required:**
The base model must be fine-tuned on Android system actions for production-grade
reliability. For the thesis prototype, fine-tune on ~200–500 examples of Android
actions using LoRA via Unsloth or HuggingFace Transformers, then export to LiteRT.

Google provides a Mobile Actions dataset and Colab fine-tuning notebook — use these
as the starting point.

### Action execution

FunctionGemma emits structured JSON:
```json
{ "function": "create_calendar_event",
  "args": { "title": "Dentist", "datetime": "2026-04-07T10:00:00" } }
```

Implement an `ActionExecutor` that maps function names to Android APIs:
```kotlin
class ActionExecutor(context: Context) {
    fun execute(call: FunctionCall): ActionResult {
        return when (call.function) {
            "create_calendar_event" -> createCalendarEvent(call.args)
            "set_alarm"             -> setAlarm(call.args)
            "make_phone_call"       -> makePhoneCall(call.args)
            "toggle_flashlight"     -> toggleFlashlight(call.args)
            "send_sms"              -> sendSms(call.args)
            else                   -> ActionResult.Unknown(call.function)
        }
    }

    private fun createCalendarEvent(args: Map<String, Any>): ActionResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, args["title"])
            // map remaining args
        }
        context.startActivity(intent)
        return ActionResult.Success
    }
    // implement remaining actions using AlarmManager, TelecomManager, etc.
}
```

**Privacy guarantee for Path A:** No data leaves the device. FunctionGemma runs
entirely locally, and action execution uses Android system APIs directly.

---

## Path B — Local LLM for sensitive queries

### When it fires
Stage 3 routing decision is `LOCAL` (sensitivity score ≥ 0.70, or policy override).

### Recommended model: Gemma 3 4B

| Model | Size (Q4) | Tokens/s Pixel 9 | Integration | Recommendation |
|---|---|---|---|---|
| Gemma 3 2B | ~1.2 GB | 20–35 | MediaPipe LLM (official) | Fallback / fast mode |
| **Gemma 3 4B** | **~2.4 GB** | **12–20** | **MediaPipe LLM (official)** | **Primary** |
| Phi-3 Mini (3.8B) | ~2.2 GB | 15–25 | llama.cpp + JNI | Evaluation baseline |
| Mistral 7B | ~4.0 GB | 8–12 | llama.cpp + JNI | Quality ceiling baseline |

**Primary choice: Gemma 3 4B** — best quality/integration tradeoff. Officially supported
by MediaPipe LLM Inference API, no custom conversion pipeline needed. Google ships
`.task` files directly.

### Integration via MediaPipe LLM Inference API

```kotlin
class LocalLlmEngine(context: Context) {
    private val llmInference: LlmInference

    init {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath("/data/local/tmp/gemma3_4b_q4.task")
            .setMaxTokens(1024)
            .setTopK(40)
            .setTemperature(0.8f)
            .setRandomSeed(101)
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
    }

    suspend fun generate(prompt: String): Flow<String> = flow {
        llmInference.generateResponseAsync(prompt) { partialResult, done ->
            // emit tokens as they arrive
        }
    }
}
```

### Important metrics to report in Chapter 4
- Time-to-first-token (TTFT) — report separately from full generation time
- Peak memory (RSS) during inference via `Debug.MemoryInfo`
- Task success rate on 50–100 curated sensitive query examples (human evaluation)
- Quality delta vs. cloud API (GPT-4o or Gemini Pro) on same query set

---

## Path C — Cloud API with optional PII redaction

### When it fires
Stage 3 routing decision is `CLOUD` or `REDACT_THEN_CLOUD`.

### Redaction strategy (for REDACT_THEN_CLOUD)

**Tokenization approach:**
Replace detected PII spans with typed placeholders before sending, restore in response.

```kotlin
class PiiRedactor {
    fun redact(query: String, entities: List<PiiEntity>): RedactedQuery {
        val mapping = mutableMapOf<String, String>()
        var redacted = query
        entities.sortedByDescending { it.span.first }.forEach { entity ->
            val token = "[${entity.type}_${mapping.size + 1}]"
            mapping[token] = entity.text
            redacted = redacted.replaceRange(entity.span, token)
        }
        return RedactedQuery(redacted, mapping)
    }

    fun restore(response: String, mapping: Map<String, String>): String {
        var restored = response
        mapping.forEach { (token, original) ->
            restored = restored.replace(token, original)
        }
        return restored
    }
}
```

**Limitation to acknowledge in thesis:** Response quality degrades when PII is
tokenized because the cloud model loses context. Measure and report this degradation
as part of the quality-privacy tradeoff analysis in Chapter 4.

---

## Module structure

```
privacy-router/
├── src/main/
│   ├── kotlin/com/example/privacyrouter/
│   │   ├── PrivacyRouterVoiceService.kt      ← VoiceInteractionService
│   │   ├── PrivacyRouterSession.kt           ← VoiceInteractionSession
│   │   ├── pipeline/
│   │   │   ├── PrivacyRouterPipeline.kt      ← orchestrates all stages
│   │   │   ├── stage1/
│   │   │   │   ├── RegexPreFilter.kt
│   │   │   │   ├── MobileBertClassifier.kt
│   │   │   │   └── ContactSensitivityGuard.kt
│   │   │   ├── stage2/
│   │   │   │   ├── PiiDetectionOrchestrator.kt
│   │   │   │   ├── TextClassifierDetector.kt  ← Tier 0
│   │   │   │   ├── NerModelDetector.kt        ← Tier 1
│   │   │   │   ├── ContextualPiiDetector.kt
│   │   │   │   └── PresidioRegexRecognizers.kt
│   │   │   └── stage3/
│   │   │       ├── SensitivityScorer.kt
│   │   │       └── PolicyEngine.kt
│   │   ├── execution/
│   │   │   ├── FunctionGemmaEngine.kt        ← Path A
│   │   │   ├── ActionExecutor.kt
│   │   │   ├── LocalLlmEngine.kt             ← Path B (Gemma 3 4B)
│   │   │   └── CloudApiClient.kt             ← Path C
│   │   ├── redaction/
│   │   │   └── PiiRedactor.kt
│   │   └── model/
│   │       ├── RequestLabel.kt
│   │       ├── PiiEntity.kt
│   │       ├── PiiDetectionResult.kt
│   │       ├── RoutingDecision.kt
│   │       └── PolicyConfig.kt
│   ├── assets/
│   │   ├── mobilbert_classifier.tflite
│   │   └── ner_model.tflite
│   └── res/xml/
│       └── interaction_service.xml
└── build.gradle.kts
```

---

## Models summary

| Stage / Path | Model | Size | Latency | Deployment |
|---|---|---|---|---|
| Stage 1 Tier 0 | Regex patterns | 0 MB | < 1 ms | Kotlin code |
| Stage 1 Tier 1 | MobileBERT (TFLite, int8) | ~25 MB | 10–20 ms | NNAPI delegate |
| Stage 1 Tier 2 | FunctionGemma 270M (fallback) | 288 MB | 150–300 ms | LiteRT |
| Stage 2 Tier 0 | Android TextClassifier | 0 MB | 2–5 ms | System API |
| Stage 2 Tier 1 | DistilBERT-NER (TFLite, int8) | ~50 MB | 20–40 ms | NNAPI delegate |
| Stage 2 contextual | Regex + keyword lists | 0 MB | < 1 ms | Kotlin code |
| Path A | FunctionGemma 270M (fine-tuned) | 288 MB | 50–200 ms | LiteRT XNNPACK |
| Path B | Gemma 3 4B (Q4, primary) | ~2.4 GB | 12–20 t/s | MediaPipe LLM |
| Path B | Gemma 3 2B (Q4, fallback) | ~1.2 GB | 20–35 t/s | MediaPipe LLM |
| Path B | Phi-3 Mini (eval baseline) | ~2.2 GB | 15–25 t/s | llama.cpp JNI |
| Path B | Mistral 7B (quality ceiling) | ~4.0 GB | 8–12 t/s | llama.cpp JNI |
| Path C | Cloud API (Gemini / GPT-4o) | — | network | REST |

---

## Key dependencies (build.gradle.kts)

```kotlin
dependencies {
    // MediaPipe LLM Inference (Path B — Gemma 3 4B)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // TFLite + NNAPI delegate (Stage 1 classifier, Stage 2 NER)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")

    // Kotlin coroutines (parallel detection)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // HTTP client (Path C — cloud API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
}
```

---

## Evaluation metrics (Chapter 4)

For each stage, report:

**Stage 1:**
- Per-class precision / recall / F1 on held-out test set
- Confusion matrix (which labels get confused)
- Tier hit rate (what % handled by regex vs. MobileBERT vs. FunctionGemma fallback)
- Inference latency per tier on Pixel 9

**Stage 2:**
- PII Leak Rate (PLR) — fraction of queries with PII that reach cloud unredacted
- False Negative Rate per entity type
- Tier contribution breakdown (what % of entities found by Tier 0 only, Tier 1 only, both)
- Latency per detection mode on Pixel 9
- Cold-start vs warm latency (report separately)

**Stage 3:**
- Rule fire rate per rule type (allow-list, deny-list, entity override, score threshold)
- Distribution of sensitivity scores across a test query set

**Path B (local LLM):**
- Time-to-first-token (TTFT)
- Full generation latency for typical response length
- Peak memory (RSS) during inference
- Task success rate vs. cloud baseline (human evaluation, 50–100 queries)

**Overall system:**
- End-to-end latency (voice input → response) for each path
- Energy per request (Android Battery Historian)
- Compare against 4 baselines: always-cloud, always-local, rule-only router, random 50/50
