# Improvement Notes — running log

Things noticed while working in the code, not yet fixed (unless marked).
Companion to VOICE_REVIEW.md (the original prioritized review). Add to this
file as new items surface.

## Round 8 (2026-07-20/21) — on-device LLM evaluation

Goal: find out whether STT → LLM → TTS can run entirely on the phone at
conversational latency, and specifically whether the offline (SenseVoice)
recognizer is "too slow" for realtime voice. Device: BraX3, MediaTek MT6835
(Dimensity 6100/6300 class, 2x Cortex-A76 ~2.2 GHz + 6x A55), Mali G57,
8 GB RAM, **no swap and no zram**.

### Answer to the original question: STT is not the bottleneck

Measured end to end:

| Leg | Latency |
|-----|---------|
| Offline STT (SenseVoice), 3 s utterance | **~230 ms** (13x realtime) |
| Gemma 4 E2B (CPU) | **2635 ms + 134 ms/word** |
| TTS first audio | ~390 ms (short) — ~1100 ms (2.5 s reply) |

A 15-word spoken reply is ~230 + 4635 + 700 ≈ **5.6 s**, of which the LLM is
~83% and STT ~4%. The streaming-vs-offline STT question is noise at this
scale — use the accurate transcript. Offline latency scales near-linearly:
3.7 s → 279 ms, 7.4 s → 569 ms, 14.8 s → 1291 ms, 29.6 s → 3208 ms.

### Model comparison (all via LiteRT-LM 0.14.0, CPU)

| Model | Size | Load | Output | Speed |
|-------|------|------|--------|-------|
| **gemma-4-E2B-it** | 2.6 GB | 6.4 s warm / 25.6 s cold, ~415 MB | coherent | **2635 ms + 133.5 ms/word** (~7.5 words/s, ~10 tok/s) |
| granite-4.0-350m | 468 MB | 0.76 s, ~161 MB | **garbage** | — |
| qwen3-0.6b (int4) | 498 MB | 5.4 s, ~515 MB | coherent but **always reasons** | 13-37 s/turn |

**Gemma 4 E2B wins, which inverts what parameter counts predict.** Both small
models failed for reasons unrelated to size:

- **granite-4.0-350m emits degenerate text** — `"=s=s=s=s…"`, `".j.j.j.j…"`,
  on 75 s runs, from *freshly loaded sessions* (so not context pollution). It
  *is* instruction-tuned (tagged `conversational`, a finetune of `-base`), so
  that isn't it either. Most likely a bad conversion of its unusual
  `granitemoehybrid` architecture; the bundle has 97 downloads against
  Qwen3-0.6B's 98K, so essentially nobody has exercised it.
- **qwen3-0.6b reasons by default and cannot be stopped from here.** Neither
  the upstream trick of pre-filling an empty `<think>\n\n</think>\n\n` block
  nor `/no_think` (prefix or suffix) suppressed it — every reply still began
  `<think>`. LiteRT-LM never applies the chat template itself, so there is no
  path to `enable_thinking=false`. Reasoning costs 13-37 s per turn, making a
  4x smaller model *slower* than Gemma.

Reference points from the vendor's own table: S26 Ultra CPU 46.9 tok/s /
1.8 s TTFT; Raspberry Pi 5 CPU 7.6 tok/s / 7.8 s TTFT. The MT6835 sits near
the Pi on decode but much better on prefill.

### Chat templates are per-family and share no syntax

LiteRT-LM does **not** apply the template even though the bundle embeds one.
An untemplated prompt returns `{"text":""}` after burning seconds of compute —
that is what "the model loads but produces nothing" looks like.

```
Gemma 4   <|turn>user\n … <turn|>\n<|turn>model\n      (NOT Gemma 2/3's <start_of_turn>)
Granite   <|start_of_role|>user<|end_of_role|> … <|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>
Qwen3     <|im_start|>user\n … <|im_end|>\n<|im_start|>assistant\n  (+ empty <think> block)
```

`GemmaEngine.applyChatTemplate` selects on the model filename. That is a
silent-failure risk if a filename ever lacks the family name.

### GPU: usable in principle, blocked by a missing symlink

**The GPU is not broken and the earlier "GPU is a dead end" conclusion was
wrong** — every GPU failure before this point was the *vision encoder*, not
the GPU. With vision/audio null (text-only), models load on GPU fine:

```
qwen3-0.6b  gpu -> loaded in 19.3 s (kernel compilation), ~1.2 GB
gemma-4-E2B gpu -> loaded in 74.5 s
```

Generation then fails with:

```
UNKNOWN: Can not find OpenCL library on this device
```

LiteRT-LM's GPU backend requires **OpenCL**. This device ships OpenGL ES and
Vulkan but no `libOpenCL.so` anywhere under /vendor, /system or /odm.

**But the OpenCL implementation is present** — ARM builds it into the Mali
GLES blob:

```
/vendor/lib64/egl/libGLES_mali.so   (42 MB)
  exports clGetPlatformIDs, clCreateContext, clEnqueueNDRangeKernel,
          clBuildProgram, clCloneKernel, clCommand*KHR, …
```

MediaTek simply never created the `libOpenCL.so` name that
`dlopen("libOpenCL.so")` resolves. So this is an exposure problem, not a
driver-authoring problem. Options, cheapest first:

1. **Ship a copy of the blob in the APK as `libOpenCL.so`** (jniLibs/arm64-v8a),
   so it lands in the app's `nativeLibraryDir`, which *is* on the app's dlopen
   search path. No root. Risks: +42 MB APK, and the blob's DT_NEEDED vendor
   dependencies may not resolve inside the app's linker namespace.
2. **`System.load()` the absolute vendor path early**, before LiteRT probes.
   Only helps if LiteRT resolves via `dlsym(RTLD_DEFAULT, …)` rather than its
   own `dlopen` handle — worth one experiment.
3. **Root / Magisk**: symlink `/vendor/lib64/libOpenCL.so`. Cleanest if root
   is acceptable; /vendor is read-only otherwise.
4. Do **not** write an OpenCL driver. The implementation exists; and a
   from-scratch stack is Mesa/rusticl territory.

Temper expectations: the vendor's 6x GPU speedup is measured on flagship
Adreno/Apple silicon. A mid-range Mali G57 may deliver much less, and kernel
compilation costs 19-74 s per load. Worth trying, not worth assuming.

### Recommendation

Keep the LLM on CPU with Gemma 4 E2B and accept ~4.6 s turns for now; that is
a usable assistant, not a natural conversation. Highest-value follow-ups:

- **Overlap TTS with generation** — at 134 ms/word decode versus ~700 ms TTS
  first-audio, speech stays ahead of the model after ~6-8 words, so speaking
  sentence one while it writes sentence two removes the generation tail from
  perceived latency.
- **Keep prompts short.** 2.6 s of the budget is prefill.
- **Try the OpenCL exposure** above; it is the only lever that could plausibly
  move latency by an order of magnitude on-device.
- Failing that, move **only** the LLM to a cluster GPU node (an RTX 3060 sits
  on .97) and keep STT and TTS local — the privacy-sensitive audio never
  leaves the phone, and turns would drop comfortably under 2 s.

## Round 7 (2026-07-20) — the on-device checklist run

Ran the 10-item checklist at the bottom of this file against the live phone
(`beamapp@10.42.42.205`, OTP 29). Results, then what it turned up.

### How to reach the phone (read this first — it cost an hour)

The phone starts with `-hidden` + `-kernel connect_all false`
(BeamService.java:557-585). Consequences that are easy to misread as "the
node is down":

- **A hidden node never appears in `nodes()`.** `mix arbor.cluster` uses
  visible nodes only, so it will *never* list the phone no matter how
  healthy it is. Use `nodes(hidden)` or `nodes(connected)` — and only on the
  one node the phone actually dialed, since hidden links aren't transitive.
  Verified locally: from the hidden side, `nodes()` and `nodes(visible)` are
  both `[]` while `nodes(connected)` shows the peer.
- **`connect_all false` means one gateway and no fallback.** If the node in
  `cluster_nodes` (`/sdcard/.beam-config`, BeamService.java:34) is down, the
  phone is isolated with no transitive path.
- Driving it from a third box: nested rpc through the gateway —
  `rpc:call(Arbor, rpc, call, [Phone, M, F, A, T], T+N)`. This is the
  production path and adds no new links to the phone.

### Checklist outcomes

| # | Item | Result |
|---|------|--------|
| 1 | New code on device | PASS (confirmed by behavior, not by rebuild) |
| 2 | `stream_listen` `once:true` | PASS — 4654 ms of an 8000 ms window |
| 3 | `stop_listen` + async dispatch | PARTIAL — buddie half needs earbuds |
| 4 | `speak` / `tts_stop` / JSON args | PASS |
| 5 | STT error path | INCONCLUSIVE — needs a restart, see below |
| 6 | remote_asr chunking | BLOCKED — no remote ASR service exists |
| 7 | Speaker JNI symbols | **RESOLVED** — extractor loads, dim=512 |
| 8 | Mic contention | PASS — `mic busy (stream_listen)` |
| 9 | `buddie_status` fields | PASS — echo half needs earbuds |
| 10 | Auth (9877 + 9876) | PASS |

Detail worth keeping:

- **#2**: `8 once` returned at 4654 ms with `utterance_end_ts` non-zero; the
  control (`8`, no once) ran the full 8315 ms with `utterance_end_ts: 0`.
  Both the flag plumbing (`listen_args/2` → `"8 once"` → `parseListenOnce`)
  and the `stopAtEndpoint` break (SpeechEngine.java:1109-1118) are correct.
- **#4**: `"cancelled"` is the status of the *interrupted `tts` call*
  (SpeechEngine.java:608); `tts_stop`'s own ack is `{"status":"stopping"}`.
  The checklist wording is ambiguous about which. JSON-args regression is
  objectively dead — the reply echoes the text actually spoken:
  `"2 1.5 million dollars"` (duration 1.9) vs `"million dollars"` (1.1).
- **#7**: `Java_..._SpeakerEmbeddingExtractor_newFromFile` fires, extractor
  loads in ~2.5 s, dim=512, enroll/list/forget round-trips clean. **This
  closes the round-2 "JNI symbols unverified" item.**
- **#10**: 9877 unauthenticated → `{"ok":false,"error":"auth required"}`;
  9876 unauthenticated → `ERROR: auth required`; with token → `ok` then `2`.
  The "Bridge auth token generated" log line only appears on *generation*
  (BridgeServer.java:178-186), so a persisted token means no log line —
  absence is not a failure.

### New — first-syllable clipping was NOT fixed

The VAD/pre-roll clipping is still live, and the diagnosis is now exact.
Same phrase ("Baltimore is very cold today") through each path:

| Path | Transcript |
|------|-----------|
| cold `stream_listen` | `"'s very cold to day"` |
| `pre_listen` 0.3 s then handoff | `"Or is very cold to day"` |
| `pre_listen` 1.5 s (buffer full) then handoff | `"Ore is very cold to day"` |
| `transcribe_offline` on a WAV with 1.7 s lead-in | `"Baltimore is very cold today."` |

The offline path is perfect, so the audio is fine — the streaming path loses
the onset. Two facts, both verified:

1. **Cold `stream_listen` has zero pre-roll.** The mic opens when the call
   arrives (SpeechEngine.java:1042-1049); anything already spoken is gone.
   The 1.5 s ring buffer (SpeechEngine.java:912-913) only exists if
   `pre_listen` ran first, and it does work — `Fed 24000 lookback samples
   (1.5s)`.
2. **`Voice.stream_listen` never calls `pre_listen`**, so the arbor
   production path is *always* the cold path.

**But priming the mic does NOT fix it — tested, negative result.** With
`pre_listen` started *before* the cue (mic warm, full 1.5 s lookback fed),
the transcript was still `"Ore is very cold to day"`. So the capture gap is
real but is **not** the cause of the lost first word. Do not spend effort on
pre-roll wiring expecting it to fix clipping; it won't.

**ROOT CAUSE FOUND (2026-07-20, after the `stt` fix made measurement
possible): the online encoder needs ~2.5 s of warm-up audio, and digital
silence does not count.**

Repeating one fixed utterance back to back shows the output tracks length —
it is not a fixed tail window, and everything after the first ~2.5 s is
transcribed *perfectly*:

```
x1 ( 3.68s) : "S over the lazy"
x2 ( 7.36s) : "S over the lazy dog, the quick brown fox jumps over the lazy"
x3 (11.04s) : "S over the lazy dog, the quick brown fox jumps over the lazy
               dog. The quick brown fox jumps over the lazy"
```

So **the model is fine.** What matters is the *content* of the lead-in, not
just its length — all-zero frames give degenerate log-mel features and never
settle the encoder:

```
no padding          : "S over the Lazy do"
+1.0s digital sil   : "S jumps over the lazy do"
+2.5s digital sil   : "S jumps over the Lazy Dog"      <- still broken
+4.0s digital sil   : "Ned Fox jumps over the lazy"
+2.5s quiet dither  : "The quick brown fox jumps over the lazy dog"  <- perfect
```

Confirmed on a real mic recording ("Baltimore is very cold today", 1.7 s of
genuine room tone as lead-in):

```
as recorded (1.7s room tone) : "Or is very cold to day"
+1.0s dither  (2.7s total)   : "Or is very cold to day"
+2.5s dither  (4.2s total)   : "Baltimore is very cold to day"
offline (control)            : "Baltimore is very cold today."
```

Room tone counts only partially (rms ~6.8 is barely above digital silence),
which is why the 1.5 s pre-roll ring buffer was never going to be enough and
why priming the mic didn't help. This also explains every other symptom:
clips shorter than ~2.5 s sit entirely inside the warm-up and return `""`,
and the live path loses exactly its first word.

**Mitigation applied, but it is NOT a reliable fix.** `warmUpStream()` feeds
~2.5 s of low-level dither into every freshly created `OnlineStream`
(`transcribeFile`, `streamListen`, `streamFromExternalAudio`). It is confirmed
to run (`elapsed_ms` rose ~300-400 -> 463) and it helps *sometimes*, but
tested across several phrases with identical audio and only the padding
varying, the result is erratic and non-monotonic:

```
"Baltimore is very cold today"   3.5s->"Ors very cold"
                                 4.0s->"Or is very cold to"
                                 5.0s->"Or is very cold to"   (never recovers)
"Hello world this is a test..."  never recovers "Hello" at any padding
"Send a message to the team..."  3.5s->"About to Morrow"
                                 4.0s->"Send a message to the team about to"
                                 5.0s->"About to"             (worse again)
```

A single-phrase sweep looked cleanly monotonic (2.5s bad → 3.5s first word
recovered → 6.5s complete) and briefly suggested the answer was just a larger
`WARMUP_MS`. Testing more phrases refuted that. **Do not simply raise
`WARMUP_MS` and call it fixed.**

Note also that TTS is stochastic (`noise_scale=0.667`), so `tts_file` yields
different audio every call. Comparing across runs without regenerating from a
single saved file produced two false conclusions during this session; always
generate once and reuse.

**Recommended direction instead — hybrid streaming/offline.** The offline
SenseVoice recognizer has been correct on every sample tested here, on the
exact audio the streaming model mangles, at ~200-400 ms for 2.5-5 s of audio
(~10x realtime). The streaming model is only needed for incremental partials.
So: keep the online recognizer driving live partials for UX, and produce the
**final** transcript by running the offline recognizer over the captured
samples. `streamListen` already has the audio in hand; the extra cost is one
offline pass (~300 ms) at end-of-utterance. That removes onset unreliability
from the result path entirely, rather than trying to pad around it.

### New — `transcribeFile` / `stt` drops all but the last segment

`transcribeFile` (SpeechEngine.java, reached via the `stt` command) feeds the
whole file, calls `inputFinished()`, decodes, then calls `getResult()`
**once**. It never checks `isEndpoint` and never accumulates — unlike
`streamListen`, which does exactly that at lines 1109-1120. Every segment
finalized before end-of-file is silently lost.

Measured on synthetic TTS audio (`tts_file` → `stt` vs `transcribe_offline`):

```
"Baltimore"                     stt=""              offline="Baltimore."
"Hello"                         stt=""              offline="Hello."
"one two three ... eight"       stt=""              offline="1,2,...,8."
"the quick brown fox"           stt="Orn Fox"       offline="The quick brown fox."
"the quick brown fox jumps
 over the lazy dog"             stt="'s jumps over the Lazy"
```

**It is not the endpoints** — trimming trailing silence changes nothing:

```
"Baltimore"    untrimmed=""   trimmed=""
"Hello"        untrimmed=""   trimmed=""
"hello world"  untrimmed="O"  trimmed="O"
"The quick brown fox jumps over the lazy dog"  ->  "S over the Lazy do"
```

Only roughly the **final ~1-1.5 s** ever decodes, and audio shorter than that
yields nothing at all. That is the signature of pushing a whole file into a
*streaming* recognizer in one shot: `transcribeFile` does

```java
stream.acceptWaveform(samples, SAMPLE_RATE);   // entire file, one call
stream.inputFinished();
while (recognizer.isReady(stream)) recognizer.decode(stream);
```

whereas `streamListen` feeds 100 ms chunks and decodes incrementally inside
the read loop — the correct usage for an online model. Fix `transcribeFile`
to do the same (chunked feed + incremental decode, plus endpoint
accumulation), not merely to add endpoint handling.

**Consequence for diagnosis: `stt` results say nothing about model quality.**
Any earlier conclusion drawn from `stt` — including "the streaming model is
inaccurate" — is void. Conversely, `streamListen` *does* drive the model
correctly, and there it produces mostly-right output with only the first word
wrong (`"Baltimore is very cold today"` → `"Ore is very cold to day"`), which
is a far smaller problem than a broken model.

### New — Termux builds never recompiled erlang_src (FIXED)

`build.sh`'s erlc step lived inside the **host-only** `else` branch of the mode
switch (`if [[ "$MODE" == termux ]] … else … <erlc here> … fi`). An on-device
Termux build therefore packaged whatever `.beam` files were already sitting in
`assets/`, and never compiled `erlang_src/*.erl` at all.

On a phone-side build that path did not exist — meaning no edit to
`android.erl`, `speech.erl`, `reconnect.erl` etc. reached the device via a
Termux build unless `prepare_assets.sh` was re-run.

**Correction (2026-07-20):** an earlier version of this note claimed `assets/`
is gitignored and therefore the erlc step was the *only* route to the device.
That is wrong in an important way. `.gitignore` does list `assets/`, but
**973 files under it are tracked anyway** — gitignore has no effect on files
already committed. That includes `assets/erlang/erts_version` and every
`assets/erlang/lib/android/ebin/*.beam`. Consequences worth knowing:

- The committed beams are stale, and any `git checkout -- assets/` or fresh
  clone silently reverts the compiled modules to whatever was last committed.
- `erts_version` is *generated* by `prepare_assets.sh` but also tracked with a
  stale value (16.3.1, i.e. OTP 28) while the real Termux runtime is 17.0.3
  (OTP 29). Restoring it from git resurrects the wrong version, which then
  feeds both build.sh's ERTS-major warning and MainActivity's runtime label.
- Generated artifacts should be untracked (`git rm --cached -r assets/`) so
  the ignore rule actually takes effect. Left as-is for now because it is a
  973-file change and unrelated to the work in flight.

Failure mode was completely silent: no error, no warning, and termux mode
redirects stdout to `build.log`, so nothing appeared on the terminal either.
Caught only because the reconnect fix was built, installed, and the running
node still showed old behaviour — with correctly re-extracted beams that
happened to contain old code.

Fixed by moving the block out of the branch so it runs in both modes, plus an
explicit "Erlang bridge modules recompiled into …" line so success is
greppable in `build.log` instead of being inferred from absence.

Note the ERTS-major check only ever *warned* — the `fi` closes before `erlc`
runs — so a version mismatch never gated compilation and was never the reason
edits went missing.

**`prepare_assets.sh` is the other compile path** (lines 140-149: one `erlc`
per module into `assets/lib/android/ebin`, plus `erts_version` at line 137).
That is how the device beams got built originally. But it is the heavy script
— it rebuilds the runtime and ~300 MB of models — so nobody re-runs it for a
one-line Erlang edit. Before this fix, editing `erlang_src` and running only
`./build.sh` on the phone silently shipped stale beams.

**Version confusion resolved — there was never a mismatch.** Everything on the
device agrees:

```
system_version : Erlang/OTP 29 [erts-17.0.3]
erts_version   : 17.0.3
on-device reconnect.beam compile_info: version "10.0.2" (OTP 29 compiler),
    source /data/data/com.termux/files/home/BeamApp/erlang_src/reconnect.erl
```

Termux's `erlc` and the device runtime are both OTP 29. Two red herrings fed
the confusion: a *stale local* `assets/erlang/erts_version` (16.3.1, an old
sync artifact — `assets/` is gitignored so it is never updated by pull), and
`MainActivity.loadRuntimeLabel()` which **hardcoded the string "OTP 28"** and
appended the real ERTS version to it, so an OTP 29 runtime displayed as
"OTP 28 / ERTS 17.0.3". Fixed to derive OTP from the ERTS major (ERTS 14=26,
15=27, 16=28, 17=29 — OTP = major + 12).

### New — mic is opened two different ways, and AEC hides the phone's own TTS

`openMic()` (used by `stream_listen`, `pre_listen`, `speaker_*`) attaches
`AcousticEchoCanceler` + `NoiseSuppressor` and enables both. `cmdMicRecord`
builds its own `AudioRecord` with the same `VOICE_RECOGNITION` source but
**no AEC and no NS**. Same phrase played through the speaker during capture:

```
mic_record  (no AEC) : peak 11854 (36.2% FS) rms 1203.6  -> offline transcribes it perfectly
stream_listen (AEC)  : ""                                 -> nothing
```

Both behaviours are correct in isolation — AEC doing its job is *desirable*
(it is what stops the assistant transcribing itself, cf. checklist item 9) —
but the inconsistency is a trap: **`mic_record` is not a faithful proxy for
what the recognizer hears**, so it cannot be used to debug listen paths. It
also means TTS-through-the-speaker can't serve as a test signal for
`stream_listen`.

Minor, same function: the `AcousticEchoCanceler` / `NoiseSuppressor` objects
are created and enabled but never `release()`d, and the references go out of
scope — one leaked `AudioEffect` pair per `openMic()` call.

### New — `reconnect.erl` is inert on the phone

`net_kernel:monitor_nodes(true, [{node_type, visible}])` (reconnect.erl:62)
**never fires on a `-hidden` node**, because none of its connections are
visible. Confirmed by a two-node local test (no `nodedown` in 6 s with
`visible`; fires immediately with `{node_type, all}`), then confirmed in
production: `reconnect:status()` reported
`down => ['arbor_dev_4242@10.42.42.42']` over the very link the RPC
traversed, with `total_reconnects => 0`.

So the `{nodedown, ...}` clause is dead code and **after the bootstrap ping
the daemon never reconnects anything** — a mobile drop is permanent until
the app restarts. This is almost certainly what the `-hidden` change traded
away: it fixed the mesh thrashing and silently disabled the reconnect that
was compensating for drops.

**Severity: this is a hard strand, not slow recovery.** The link can only
ever be established *outbound from the phone* — verified 2026-07-20 while
the phone was disconnected:

```
epmd on phone, from this box : {error,address}
epmd on phone, from arbor    : {error,address}
arbor -> phone net_adm:ping  : pang
ICMP to phone                : 100% loss
```

Nothing on the cluster side can re-dial it (NAT/firewall — inbound is
closed). So the phone is the only party that can restore the connection, and
the daemon whose job that is never fires. **One dropped link = phone
unreachable until the app is manually restarted.** It dropped twice during
this session; the first time it recovered, the second time it stayed down.

**Fix (validated 2026-07-20 on a hidden node, full cycle):**

- reconnect.erl:62 — `{node_type, visible}` → `{node_type, all}`
- reconnect.erl:103 — `Connected = nodes()` → `nodes(connected)`; today
  `status/0` reports every target as down even while connected.

Empirically, on a `-hidden` node against a normal peer:

```
{node_type, visible}  nodeup: NOTHING   nodedown: NOTHING
{node_type, all}      nodeup: {nodeup,  N,[{node_type,hidden}]}
                      nodedown: {nodedown,N,[{node_type,hidden}]}
                      then nodeup again on reconnect
```

`all` is a superset of `visible`, so nothing else regresses, and the existing
`{nodedown, Node, _Info}` / `{nodeup, Node, _Info}` clauses already match the
3-tuple shape `monitor_nodes/2` delivers — it really is a one-word change
plus the `status/0` fix.

**Also worth adding: a periodic sweep.** `monitor_nodes` is edge-triggered,
and with `net_ticktime 120` a silent mobile drop takes up to ~2 min to
surface as `nodedown` — and a missed edge means the daemon waits forever.
A level-triggered timer (every ~30-60 s, ping any target not in
`nodes(connected)`) is a few lines and makes reconnection robust regardless
of whether the event fires. Recommended given the link is demonstrably
flaky: the phone dropped and recovered mid-session during this very run.

Both need a rebuild + redeploy to land.

### New — `speaker_verify` window is hardcoded and length-sensitive

`cmdSpeakerVerify` ends with `speakerId.verify(name, wav, 4, threshold)` —
**verify uses 4 s while enrollment uses 5 s**. `SpeakerId.verify()` takes a
`seconds` parameter; the command layer pins it. The arg parser accepts a
name, a `/`-prefixed WAV and a threshold, but has no way to pass a duration.

That matters because the embedding is strongly length-sensitive.
Deterministic (0.370 three runs in a row), on *identical* speech truncated
to different lengths:

```
2.00s→0.318  2.50s→0.881  3.00s→0.871  3.50s→0.515
4.00s→0.370  4.50s→0.900  5.00s→0.898
```

Essentially bimodal: ~0.87-0.90 or ~0.31-0.39. The hardcoded 4 s lands in
the bad region.

- Enrollment, centroid and cosine are all **correct** — SpeakerId.java:350-376
  reviewed, and verifying a 5 s WAV scores **0.898, verified:true**.
- **Do not "tune the threshold" to compensate** (the round-2/round-5 open
  item). No threshold separates 0.31-0.90 for the same speaker; lowering it
  to 0.28 would admit impostors. Fix the capture length first, then tune.
- Silence dilutes too, asymmetrically: +2 s trailing → 0.816, +5 s trailing
  → 0.292, but 2 s *prepended* → 0.880.
- **Naive VAD concatenation makes it worse** — stitching only the voiced
  100 ms windows dropped a 0.898 sample to 0.343. Tested and rejected; the
  discontinuities hurt more than the silence. Any real fix needs proper
  segment-boundary handling, not RMS gating.

### New — remote ASR is misconfigured (blocks #6)

`speech:remote_asr_node()` returns `arbor_gpu2@10.42.42.97`, which **does
not exist** — the node at that IP is `arbor_dev_060c@10.42.42.97` (ping
`pang` vs `pong`). Worse, **no ASR/whisper/speech app runs on any cluster
node**, so there is nothing to chunk audio to. `asr_mode` is currently
`remote`, which means `speech:listen/1` and `speech:stream_listen/1` route
into a dead end.

Note the asymmetry: `Voice.stream_listen` (arbor) invokes the *bridge*
`stream_listen` command and is unaffected by `asr_mode`, while
`speech:stream_listen/1` (on the phone) honours it. Same name, different
routing depending on which side you call it from.

### Smaller things this run turned up

- **`Voice.speak/3` and `Voice.tts_stop/1` discard status** (`{:ok,_} -> :ok`,
  voice.ex:130/147) — a caller cannot tell a completed utterance from a
  cancelled one. The phone returns `{"status":"cancelled", ..., "gen_ms":_,
  "first_audio_ms":_}`; `first_audio_ms` looks like the right t0 for the
  VOICE-32 end-of-utterance→first-audio telemetry.
- **`pre_listen` returns `ok` when it silently skipped.** With the mic held
  it logs `Pre-record skipped: mic busy (stream_listen)` (SpeechEngine.java:903)
  and still answers `ok`. Callers can't tell whether priming happened —
  which now matters, because the clipping fix depends on it.
- **9876 auth is coalescing-sensitive by design.** `gen_tcp:recv(S, 0, ...)`
  on a raw socket (BeamService.java:468) returns whatever is buffered, so a
  client that writes `auth <tok>\ncmd\n` in one go fails with `auth failed`.
  All four in-tree clients wait for the reply, so this is correct — but
  hand-driven `nc` needs a pause between the lines. `{packet, line}` would
  make it robust.
- **#5 can't be tested without a restart.** sherpa keeps the recognizer in
  memory, so renaming `files/stt-model` mid-session changes nothing (tried:
  empty transcript, then restored and verified intact). The
  `isSttReady()` → `{"error":"stt engine not ready"}` path exists and is
  correct by inspection; exercising it needs a restart with the model absent.
- Streamed-TTS underrun risk is real but marginal on this device: for a
  1.9 s utterance, `gen_ms` 2418 vs `first_audio_ms` 859 — generation ran
  slower than realtime but the head start absorbed it. Fixed per-call
  overhead (~1.3 s) dominates for short utterances.
- Mic levels are healthy: silence peak 44 / rms 6.8, speech peak 18223
  (55.6% FS) / rms 1422.6. Not an AGC or gain problem.

### Still needs the earbuds

Items 3 (buddie half) and 9 (echo suppression) — `buddie_status` reports
`state:"disconnected"`, so `buddie_connect` first. Both are quick.

## Round 2 (2026-07-19, later same day) — what got done

tts JSON args (review #9), mic arbiter (+ fixed a pre-record mic leak:
`stopListening` used to abandon an open, recording AudioRecord if no listen
took it over), bounded buddie queue + drop stats, PARTIAL_WAKE_LOCK around
all listens, speaker_enroll/verify/list/forget commands (VOICE-21 enabler,
new `SpeakerId` + upstream-mirrored `SpeakerEmbeddingExtractor` binding),
robust RIFF-chunk WAV parsing, PCA slow-attack AGC, speech.erl dead
map-matching fixed (raw-JSON field scan), remote_asr uses configured node,
console verbs (stop, tts_stop, enroll, verify, speakers), arbor `speak/3`
sends JSON tts args, arbor `speaker_verify/2` + `speaker_enroll/3` wrappers.

## Round 6 (2026-07-19) — cross-platform build

`build.sh` is now dual-mode (Termux behavior unchanged; auto-detected).
Host mode (macOS/Linux + Android SDK):

1. **One-time sync**: `./sync_artifacts.sh <phone-ip>` (Termux sshd on
   8022, same conventions as deploy-to-phone.sh). Pulls the device-built
   pieces the host can't produce: BEAM runtime + sherpa `.so`s
   (`build/lib/`), model assets (~300 MB first time, rsync-incremental
   after), LiteRT jars, and the phone debug keystore (signature
   continuity — without it the first host-built `adb install -r` needs an
   uninstall). Re-sync only when the phone's erlang/sherpa/models change
   (i.e. after re-running `prepare_assets.sh` there).
2. **Build**: `./build.sh` — locates the SDK (ANDROID_SDK_ROOT /
   ANDROID_HOME / ~/Library/Android/sdk), uses newest build-tools
   (aapt2/d8/zipalign/apksigner) + platforms;android-33 + JDK 11+.
   `INSTALL=1 ./build.sh` runs `adb install -r` at the end.
3. **Erlang**: if `erlc` is on PATH the bridge modules are recompiled into
   the assets; the script warns when local ERTS major ≠ device ERTS major
   (`assets/erlang/erts_version`; ERTS 15.x = OTP 27, 14.x = OTP 26 —
   `brew install erlang@NN` to match). No erlc → loud warning that
   erlang_src edits are NOT in the APK.

Also fixed while in there: **the javac step listed source files
explicitly**, so the round-1+ additions (`VadSegmenter.java`,
`SpeakerId.java`, `SpeakerEmbeddingExtractor.java`) would have broken the
on-device build too — now `find src -name '*.java'`.

Caveats / notes:
- Host `d8` comes from build-tools (no r8.jar needed). Min build-tools
  ~31+ recommended; script picks the newest installed.
- `sync_artifacts.sh` uses `rsync --delete` when available — the phone is
  the source of truth for artifacts; locally recompiled erlang beams are
  regenerated by the next `./build.sh` run.
- sdkmanager one-liners if pieces are missing:
  `sdkmanager 'build-tools;34.0.0' 'platforms;android-33'`.

## Round 5 (2026-07-19) — enrollment quality + stream wrapper

- **Multi-take enrollment.** `speaker_enroll <name>` now ADDS a take
  (storage: `speakers/<name>/<n>.emb`; legacy flat files migrate on next
  enrollment). Verification compares against the centroid (mean) of a
  speaker's takes — enroll 3–5 takes in different conditions (quiet room,
  street, earbuds) for best robustness. `speaker_forget` clears all takes;
  results now report `takes`.
- **Configurable verify threshold.** `speaker_verify [name] [wav] [thr]`
  accepts a float token in (0,1] (e.g. `verify hysun 0.7` on the console);
  arbor side takes `threshold:` in `Voice.speaker_verify/2` opts.
- **`gemma:stream/2,3`** — typed streaming wrapper over
  `android:subscribe_stream/1`: `gemma:stream(<<"prompt">>, fun(Chunk) ->
  io:format("~s", [Chunk]) end)`. Single-subscriber limitation documented
  (don't run concurrently with buddie/stream_listen partials).
- **Auth-disabled flag honored everywhere** — MainActivity and
  DictionaryActivity now skip auth when `bridge_auth_disabled` exists,
  matching BridgeServer/BeamService behavior.
- **Decision: skipping PhoneAPI help generation.** Turning the ~100-case
  dispatch switch into a command registry is a large mechanical refactor
  with real regression risk and modest payoff; the help text drift risk is
  documented instead. Revisit if the command set keeps growing.

Backlog is now empty except items needing the device: on-device checklist
run, Silero min_silence tuning (needs field data), verify-threshold tuning
after real enrollments, and the trust-arbor `./bin/mix compile
--warnings-as-errors` + `./bin/mix format` pass.

## Round 4 (2026-07-19) — BEAM command port (9876) auth

The last unauthenticated `eval`-capable port. Same app-private token as the
bridge, same disabled-flag escape hatch:

- **Server**: per-connection `CheckAuth` gate in the BeamService bootstrap —
  first line must be `auth <token>` (reply `ok`); wrong/missing token gets
  `ERROR: auth required/failed` and a closed socket. Token file absent or
  `bridge_auth_disabled` present → no auth (standalone/debug still works).
- **Clients** (all four write-once/read-once callers): BridgeServer
  `cmdBeam`/`cmdCluster` (shared `authBeamPort`, reuses the in-memory
  token), MainActivity console send, DictionaryActivity `evalBeam`. Each
  sends auth, consumes the reply, then sends its command — waiting on the
  reply is what prevents TCP coalescing of auth+command into one recv.
- Bridge dispatch also gained an idempotent `auth` no-op case so a client
  authing against an auth-disabled server gets a clean reply instead of
  "unknown command".
- Still-open nuance: MainActivity/DictionaryActivity send auth even when
  `bridge_auth_disabled` is set (they only check for the token file). The
  server answers gracefully either way — cosmetic only.

Verification (append to checklist): `nc 127.0.0.1 9876` + `eval 1+1.`
should get `ERROR: auth required`; UI console + Dictionary + `cluster_*`
bridge commands must still work; `auth <token from files/bridge_token>`
then `eval 1+1.` over nc should work.

## Round 3 (2026-07-19) — audit + bridge auth

Started with a full working-tree audit (all round 1+2 markers verified
present in both repos — nothing was lost or blocked). The audit caught two
gaps, both fixed:

- **`cmdMicRecord` had been left out of the mic arbiter** (round-2 miss) —
  now claims/releases "mic_record" like every other capture path.
- **gemma.erl had the same dead map-matching as speech.erl**: `gemma:load/1`
  and `gemma:generate/1` returned `{error, RawJson}` even on success. Fixed
  via raw-JSON scanning (`speech:extract_field/2`, now exported); stale
  moduledoc about the partial-collision bug rewritten to describe
  `android:subscribe_stream/1`.

New in round 3:

- **Bridge auth (closes the P2 security item).** BridgeServer generates a
  random token into app-private `files/bridge_token` at startup; every
  connection must send `{"cmd":"auth","args":"<token>"}` as its first line
  or the socket is closed. Same-uid clients read the file: android.erl
  (every (re)connect, fire-and-forget), remote_asr.erl (consumes the auth
  reply), MainActivity (all three sockets). Debug escape hatch: create
  `files/bridge_auth_disabled` and restart. Raw `nc` debugging now needs
  the token (`cat files/bridge_token` via run-as/su) or the flag file.
- **gemma_* errors are protocol-level now** (added to the error-wrapped
  command set) — `gemma_cancel`-during-generation plus proper `{error, _}`
  tuples BEAM-side.
- **Comms facade passthroughs** in trust-arbor: `voice_stop_listen/1`,
  `voice_tts_stop/1`, `voice_speaker_verify/2`, `voice_speaker_enroll/3`.

Round-3 open items:

- **BEAM command port (9876) is still unauthenticated** — it has `eval`,
  which is equivalent power to the bridge's `shell`. Same token approach
  would work; the erlang bootstrap string in BeamService.java is the fiddly
  part. Do next time BeamService is touched.
- **Token path is hardcoded** in android.erl/remote_asr.erl
  (`/data/data/com.example.beamapp/files/bridge_token`, matching the
  existing asr_test.erl convention). If the package name ever changes,
  export it as an env var from BeamService instead.
- **gemma:status/0 spec changed** to `{ok, binary()}` (raw JSON) to match
  reality.

## Noticed during round 2 (still open)

- ~~**SpeakerEmbeddingExtractor JNI symbols unverified.**~~ **RESOLVED
  2026-07-20 (round 7)** — symbols are present and working: extractor loads
  in ~2.5 s, dim=512, enroll/list/forget round-trips clean on-device.
- **Verification threshold is a constant (0.60).** Typical cosine thresholds
  for 3d-speaker/wespeaker models run 0.5–0.65; tune after enrolling real
  voices, then consider exposing it as an arg / config.
  **Superseded 2026-07-20 (round 7): do not tune this yet.** The embedding
  is length-sensitive and the verify window is hardcoded to 4 s (vs 5 s for
  enrollment), which puts scores in a bimodal 0.31-0.90 range for the *same*
  speaker. No threshold separates that. Fix the capture window first.
- **Speaker names are sanitized to `[A-Za-z0-9_-]`** for filenames; verify
  results return the sanitized form.
- **gemma_* errors still arrive ok:true.** Speech commands now fail at the
  protocol level, but gemma results keep the old `{"data":{"error":...}}`
  shape to avoid breaking existing gemma clients. Align later if desired.
- **Single-take enrollment.** Averaging 2–3 enrollment takes would improve
  robustness; v2 idea.
- **`Comms.voice_*` facade** still lacks stop_listen/tts_stop/speaker
  passthroughs (only the Channels.Voice module has them).

## Noticed during the 2026-07-19 voice/VAD fix session

### Protocol / BEAM side
- **speech.erl expects maps that android.erl never produces.** `android.erl
  parse_value/1` returns raw JSON binaries for objects (documented "users can
  decode further"), but `speech.erl` matches `{ok, Result} when is_map(Result)`
  and `maps:get(<<"text">>, ...)` — those clauses are dead code, so
  `speech:listen/1` returns raw JSON binaries on success instead of the
  transcript. Arbor's `Channels.Voice` is unaffected (it Jason-decodes
  itself). Fix: make `parse_value` actually decode objects (small hand-rolled
  parser or push JSON decoding into callers consistently).
- **Old dispatcher double-replied on streaming commands** — after
  `cmdStreamListen` wrote its own final, dispatch also wrote
  `{"id":N,"ok":true,"data":null}` (same id, harmless only by accident).
  FIXED as a side effect of the async-dispatch refactor (null result → no
  wrapper response).
- **`gemma_cancel` was unreachable mid-generation** for the same reason
  `stop_listen` didn't exist: serial dispatch. FIXED — `gemma_generate` /
  `gemma_stream` are now async, so `gemma_cancel` can get through.
- **`maps:foreach/2` in android.erl requires OTP 24+.** The bundled BEAM is
  modern so this is fine, but note the floor.
- **BeamService console has no "stop" verb** — add `stop` →
  `android:call(<<"stop_listen">>)` next time BeamService.java is touched.
- **remote_asr.erl ignores config** — hardcodes `arbor_gpu2@10.42.42.97`
  instead of using `speech:remote_asr_node()`; default node is duplicated in
  two files.
- **android.erl gen_server call timeouts vs long listens**: callers must pass
  a Timeout ≥ the listen window (speech.erl does; arbor's `listen_timeout/2`
  now auto-raises). Any new caller of `android:call` with a long-running
  command must remember this.

### Java / phone side
- **`cmdTts` positional parsing still eats leading numbers** ("2 1.5 million
  dollars" → sid=2, speed=1.5). Review item #9 — still open. Move to JSON
  args (`{"text":...,"sid":...,"speed":...}`) with legacy fallback.
- **BuddieService.audioQueue still unbounded** (review P2) — bound it,
  drop-oldest, expose drop count in `buddie_status`.
- **PCA per-block AGC still normalizes each 256-sample block to peak 0.5**
  (gain ≤200×) — amplifies noise floors between words; replace with fixed
  calibrated gain or slow-attack AGC (review P2).
- **No mic arbiter** — `pre_listen`, `mic_record`, and `stream_listen` can
  still race to open the mic from different connections; loser gets a generic
  "mic not available". A single-owner arbiter with a descriptive error
  ("mic busy: stream_listen") would make failures diagnosable (review P2).
- **Streamed TTS underrun risk on slow devices**: `speak()` now plays chunks
  as VITS generates them; if generation is slower than realtime the track
  underruns (brief gaps). If observed in the field, add a "buffer 2 chunks
  before play()" warm-up or fall back to full pre-generation.
- **`speak()` now serializes concurrent callers** (lock) — previously
  concurrent tts commands overlapped audio. Intentional behavior change.
- **`streamListen` has an unused `micOwned` local** (pre-existing) — remove
  on next touch.
- **`readWavSamples` still assumes a 44-byte canonical WAV header** — breaks
  on files with LIST/fact chunks (review P2).
- **Bridge has no auth** — loopback-only now (user's fix), but any local app
  can still reach `shell`, `sms_send`, contacts via :9877. Shared-secret
  handshake line is cheap insurance (review P2).
- **PhoneAPI.help() is hand-maintained** — updated for
  `stop_listen`/`tts_stop`/`buddie_mix`/`once`, but it will drift again;
  consider generating from the dispatch table.
- **VadSegmenter end-of-utterance latency ≈ 0.82 s** (Silero internal
  min_silence 0.5 s + ~320 ms grace) on the chunking path. Once field-tested,
  consider lowering `setMinSilenceDuration` toward 0.3 s — the grace period
  now provides the anti-clipping the long min_silence was compensating for.
- **Speaker verification opportunity** (VOICE-21): the diarization embedding
  model is already on-device; a `speaker_enroll <name>` / `speaker_verify`
  command pair (cosine similarity vs enrolled embedding) is cheap to add.
- **Wake lock during long listens** still not acquired (review P2) — Doze
  will eat VAD sessions; acquire/release around `listen*`.

### trust-arbor side
- **`Channels.Voice` return-shape change**: `listen`/`stream_listen`/
  `buddie_listen` now return `{:error, reason}` for STT failures instead of
  `{:ok, error_json}`. The old island `Voice.Session` already matches
  `{:error, _}` so it degrades gracefully, but any new caller should handle
  the error tuple. Needs `./bin/mix compile --warnings-as-errors` +
  `./bin/mix format` verification (no sandbox toolchain available when the
  change was made).
- **VP-08 wiring notes**: PhoneLoop should call `buddie_listen(node, secs,
  once: true)` (or `stream_listen` fallback) for turn-taking; use
  `Voice.stop_listen/1` for PTT-cancel and shutdown; `utterance_end_ts` in
  the phone result JSON is the correct t0 for VOICE-32
  end-of-utterance→ack/first-audio telemetry (phone and core clocks are the
  same only if NTP-close — compute deltas on one side where possible).
- **`Comms.voice_listen` facade** (arbor_comms/lib/arbor/comms.ex) may want a
  `stop_listen`/`tts_stop` passthrough for symmetry when VP-08 lands.

## How to verify this session's changes on-device

> **Ran 2026-07-20 — see Round 7 at the top for results.** Corrections to the
> steps below, learned the hard way:
> - The phone is `-hidden`: it will **never** show in `nodes()` or
>   `mix arbor.cluster`. Check `nodes(hidden)` / `nodes(connected)` on the
>   gateway node instead.
> - Step 4: `"cancelled"` is the status of the *interrupted `tts` call*, not
>   of `tts_stop` (whose ack is `{"status":"stopping"}`). `Voice.tts_stop/1`
>   discards it — use the raw command to see it.
> - Step 5 cannot be tested without restarting the app; the recognizer is
>   already in memory, so renaming the model dir mid-session does nothing.
> - Step 6 is blocked until a remote ASR service actually exists (see Round 7).
> - Step 7: verify uses a hardcoded 4 s window vs enrollment's 5 s, and 4 s
>   sits in the unreliable regime — a low score is not necessarily a bad
>   enrollment. Don't tune the threshold before fixing the window.
> - `android:call/2` is `(Cmd, Args)`, **not** `(Cmd, Timeout)` — use
>   `call/3` for a timeout. `call(<<"vibrate">>, 15000)` is a 15-second buzz.

1. Rebuild APK (`./build.sh`), reinstall, restart BeamService.
2. From a cluster node:
   `Voice.stream_listen(:"beamapp@<ip>", 8, once: true)` — speak one
   sentence; expect return right after you stop talking, with partials no
   longer killing the call.
3. `Voice.stop_listen(:"beamapp@<ip>")` while a 30 s `buddie_listen` runs —
   expect prompt final result; toast/battery calls should work *during* the
   listen (async dispatch).
4. `Voice.speak(node, "testing one two three")` then `Voice.tts_stop(node)`
   mid-playback — expect status "cancelled". Also
   `Voice.speak(node, "2 1.5 million dollars")` — must speak the whole
   phrase (JSON args), not eat "2 1.5" as sid/speed.
5. STT error path: rename the stt model dir → `stream_listen` should return
   `{:error, "stt engine not ready"}`, not a fake transcript.
6. Remote chunking: `remote_asr:listen(15)` — chunks should no longer clip
   first syllables (pre-roll now applies).
7. Speaker id (console): `enroll hysun` two or three times (speak 5 s
   each; response shows rising `takes`), then `verify` (speak 4 s) →
   expect `{"name":"hysun","score":...,"verified":true,"takes":3}`; a
   different voice should score below 0.60; `verify hysun 0.8` raises the
   bar. If the JNI lacks extractor symbols you'll get
   `{error, "speaker extractor init failed: ..."}` — report that back.
8. Mic contention: start `stream_listen 30`, then `record 5` on a second
   connection → expect `{error, "mic busy (stream_listen)"}`.
9. Buddie: `buddie_status` now shows `queued_samples` / `dropped_chunks`;
   with earbuds connected + TTS playing, transcripts should no longer echo
   the assistant's own speech (mic-only default).
10. Auth: after reinstall, `logcat -s BridgeServer` should show "Bridge auth
    token generated"; all BEAM/UI paths must still work (they auth
    transparently). A bare `nc 127.0.0.1 9877` + any command should get
    `"auth required"` and a closed socket. `remote_asr:listen(10)` must
    still stream chunks (its socket auths + consumes the reply). Gemma:
    `gemma:load(...)` now returns plain `ok` on success (previously
    `{error, RawJson}` — callers relying on that inversion, if any, will
    notice).
