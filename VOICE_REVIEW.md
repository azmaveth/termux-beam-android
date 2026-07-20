# Voice/VAD Review — prep for VOICE-1.0 (VP-08 phone loop)

> **Status update (2026-07-19, two same-day fix sessions):** P0 #1–#4 FIXED;
> P1 #5–#10 FIXED (VadSegmenter, AEC/NS + mic-only buddie, streamed
> cancelable TTS, async speech dispatch, tts JSON args, utterance_end_ts).
> P2 largely done too: speaker enroll/verify (VOICE-21 enabler), mic
> arbiter, bounded buddie queue, wake locks, robust WAV parsing, PCA slow
> AGC, remote-node config dedup, console stop/verify verbs. Round 3 added
> bridge auth (shared app-private token, all clients wired), fixed
> gemma.erl's dead map-matching, put gemma errors at the protocol level,
> and audited the full tree (all changes verified present). Round 4 closed
> the 9876 command-port auth gap (same token, all four clients). Still
> open: PhoneAPI help generation and the smaller round-2/3/4 items in
> IMPROVEMENT_NOTES.md. trust-arbor `Channels.Voice` gained error handling,
> `:once`, `stop_listen`/`tts_stop`, JSON tts args, and
> `speaker_verify`/`speaker_enroll` — run `./bin/mix compile
> --warnings-as-errors` + `./bin/mix format` there to verify.
> `IMPROVEMENT_NOTES.md` has the full running log + on-device checklist.

Reviewed 2026-07-19 against `trust-arbor/arbor/docs/specs/voice/` (VOICE-1.0.md, VP-07/VP-08).
VP-08 consumes this repo through `Arbor.Comms.Channels.Voice` → `android:call/3` → BridgeServer
commands `listen`, `stream_listen`, `buddie_listen`, `tts`. Findings ordered by how hard they
block that path.

## P0 — will break or badly degrade VP-08

### 1. Partial-result lines corrupt the request/reply protocol from BEAM
`cmdStreamListen` and `cmdBuddieListen` (BridgeServer.java:1117, 1378) stream partials as
`{"id":N,"partial":true,...}` on the same socket, same id, as the pending request.
`android.erl handle_response` (android.erl:253) has no partial filter: the first partial is
taken as the reply — `"ok"` key missing → the caller gets `{error, <<>>}` the moment speech
is detected, and the real final response is dropped (id no longer pending). MainActivity avoids
this only because it parses its own socket. **Any core-driven `stream_listen`/`buddie_listen`
(exactly VP-08's primitives) fails on first speech.**
Fix (small): in `handle_response`, route lines containing `"partial":true` to an optional
subscriber (or ignore them) instead of `maps:take`. Longer term: give streamed commands a
distinct channel/id-space.

### 2. No way to stop or interrupt a listen from the cluster
- `remote_asr:stop/0` calls bridge command `stop_listen` — which has no case in the dispatch
  switch (BridgeServer.java:248ff). It has never worked.
- Nothing stops `streamListen`/`streamFromExternalAudio` remotely: `mic_stop` only stops raw
  `mic_record`; `SpeechEngine.stopListening()` is reachable only via `buddie_disconnect`
  (nuclear) or UI. MainActivity "stops" local listen by closing its socket — the engine keeps
  recording until maxDuration regardless (MainActivity.java:579).
Fix: add a real `stop_listen` command wired to `speechEngine.stopListening()` +
`buddieService.stopListening()`. VP-08's `stop(server)` and VOICE-24's hard budget stop need it.

### 3. Every listen runs the full window — no endpoint-return mode
`streamListen` and `streamFromExternalAudio` loop until `maxMs` even after a completed
utterance (endpoint just resets the stream and keeps going). The arbor-side doc for
`buddie_listen` claims "listens until silence is detected (up to seconds max)" — not true.
Consequence for VP-08's turn loop: every turn costs the full `listen_seconds` (default 8 s)
before the backend even sees text; VOICE-32's first-audio ≤ 2 s target is unreachable.
Fix: add a `once`/utterance mode (e.g. args `"8 once"`): return at the first recognizer
endpoint after speech has been seen (endpoint detection is already enabled,
SpeechEngine.java:251). Keep current behavior for dictation.

### 4. Errors masquerade as transcripts (VOICE-28)
Speech commands return three inconsistent shapes: bare JSON strings (`"stt engine not ready"`,
`"mic not available"`), `{"error":...}` objects, and `{"text":""}`. Arbor's
`Voice.extract_text` (voice.ex:230) returns `{:ok, json}` for anything without a `"text"` key —
so "stt engine not ready" becomes a successful transcript. VOICE-28 demands loud failures.
Fix: normalize every speech-path error to `{"error":"..."}` on the phone; make `extract_text`
return `{:error, reason}` when `"text"` is absent or `"error"` is present.

## P1 — quality/latency issues to fix while you're in there

### 5. Three divergent copies of the VAD gating loop
`streamListen` (no VAD at all — raw mic straight to recognizer), `streamFromExternalAudio`
(VAD + hand-rolled pre-roll ring + hysteresis), `remoteStreamListen` (VAD, **no pre-roll, no
hysteresis** — the onset-clipping bug fixed in abe75f0 for the BLE path still exists here;
first-word attacks are clipped from chunks sent to the GPU node).
Fix: extract one `VadSegmenter` used by all three. Consider replacing the hand-rolled
pre-roll/hysteresis with sherpa's built-in segment queue (`Vad.front()/pop()/flush()`,
Vad.java:41-59) — Silero in sherpa already pads segment starts and applies
min-silence/min-speech internally; you're re-implementing it above `isSpeechDetected()`.

### 6. No AEC/NS; earbud path mixes speaker audio into STT input
- All mic opens use `MediaRecorder.AudioSource.MIC` with no `AcousticEchoCanceler`/
  `NoiseSuppressor` (SpeechEngine.java:744, 843, 1250; BridgeServer.java:924). In VAD mode the
  phone's own TTS will trigger the VAD and get transcribed. Use `VOICE_RECOGNITION` source +
  enable AEC/NS on the session.
- `handleOpusPacket` (BuddieService.java:639) deliberately mixes the earbud **speaker** frames
  into the mic stream. Fine for note-taking; fatal for a conversation loop (assistant hears
  itself). Make mic-only the default for `buddie_listen`, mixing opt-in.

### 7. TTS latency and cancelability
`speak()` (SpeechEngine.java:519) generates the entire VITS utterance before playback starts
(first-audio = full gen time), plays via deprecated `AudioTrack(STREAM_MUSIC)` MODE_STATIC,
blocks with `Thread.sleep(duration)`, and cannot be cancelled. VOICE-24 (budget hard-stop) and
any future barge-in need cancel; VOICE-32 wants first-audio fast.
Fix: `OfflineTts.generateWithCallback` → `AudioTrack` MODE_STREAM (chunked playback), a
`tts_stop` command, and `AudioAttributes(USAGE_ASSISTANT)`.

### 8. Head-of-line blocking on the single bridge connection
`handleClient` dispatches synchronously per connection (BridgeServer.java:222), and
`android.erl` multiplexes *all* BEAM calls over one socket. A 30 s `buddie_listen` blocks every
other `android:call` (toast, battery, tts, sensor) until it returns; concurrent callers just
time out. `remote_asr.erl` already works around this by opening its own socket.
Fix: dispatch long-running commands (`listen*`, `mic_record`, `gemma_*`) on worker threads,
replying by id when done (the protocol already carries ids — out-of-order replies are safe for
`android.erl`'s pending-map... once finding #1's partial filter is in).

### 9. `tts` positional-arg parsing eats leading numbers
`cmdTts` regex (BridgeServer.java:1042) treats `"2 1.5 million dollars"` as sid=2, speed=1.5,
text="million dollars". Arbor's `speak/3` with a voice set emits exactly this shape on purpose,
but any text starting with two numbers is silently mangled.
Fix: accept JSON args (`{"text":...,"sid":...,"speed":...}`) with the legacy form as fallback.

### 10. Latency telemetry for VOICE-32
Results carry only `duration`/`elapsed_ms`. VOICE-32 needs end-of-utterance→ack and
end-of-utterance→first-audio. Add `utterance_end_ts` (epoch ms of last speech frame) and
per-stage timings (`vad_ms`, `decode_ms`, `punct_ms`) to `stream_listen`/`buddie_listen`
results so the core can compute honest per-turn latency.

## P2 — smaller items / opportunities

- **Speaker verification (VOICE-21)**: the speaker-embedding model is already shipped for
  diarization. Exposing `speaker_enroll <name>` / `speaker_verify` bridge commands (cosine
  similarity against enrolled embedding) gives VP-09+ its gate almost for free.
- **Unbounded audio queue**: `BuddieService.audioQueue` is an unbounded
  `ConcurrentLinkedQueue`; if STT stalls, it grows without limit. Bound it, drop-oldest, count
  drops in `buddie_status`.
- **Per-block AGC in PCA decode** (BuddieService.java:493): normalizing each 256-sample block
  to peak 0.5 (gain up to 200×) amplifies noise floors between words and distorts VAD energy.
  Use a fixed calibrated gain or slow-attack AGC across blocks.
- **Mic ownership**: `cmdMicRecord`, pre-record, and `streamListen` can race to open the mic
  from different sockets; second opener gets a cryptic failure. A small mic-arbiter (single
  owner + `{"error":"mic busy (stream_listen)"}`) makes failures diagnosable.
- **`readWavSamples` / header assumptions**: blind 44-byte seek breaks on WAVs with extra
  chunks; fine for self-produced files, will bite on external ones.
- **Hardcoded remote node** `arbor_gpu2@10.42.42.97` duplicated in speech.erl and
  remote_asr.erl; remote_asr ignores `speech:remote_asr_node()` config.
- **Wake lock during listen**: `wake_lock` exists but nothing acquires it around long listens;
  Doze will eat VAD sessions. Acquire/release around `listen*`.
- **Bridge auth**: loopback-only, but any local app can connect to :9877 and reach `shell`,
  `sms_send`, contacts. A shared-secret handshake line is cheap insurance.
- **Stale arbor-side doc**: `Channels.Voice.listen` doc says "online STT (Google)" — it's
  sherpa-onnx on-device. Fix when touching voice.ex for #4.

## Spec alignment notes (no code change needed here)

- VOICE-25 (raw audio never leaves phone): the arbor path (`listen`/`stream_listen`/
  `buddie_listen`) is always phone-local sherpa — compliant. The remote-ASR feature
  (`remote_listen`, `speech:asr_mode() == remote`) ships WAVs to the GPU node; it's a separate
  console feature, but VP-08 must not route through `speech.erl`'s mode switch (it doesn't).
- VOICE-27 (PTT default): activation lives core-side; phone needs nothing beyond #2/#3.
- The `pre_listen` 1.5 s ring-buffer pattern (SpeechEngine.java:737) is good — keep it, and
  consider honoring it in `remoteStreamListen`'s replacement too.

## Suggested order

1. #1 partial filter + #4 error shapes (unblocks core-driven listen; hours, both repos)
2. #2 stop_listen + #3 endpoint-return mode (turn loop viability)
3. #5 unified VadSegmenter (fixes remote-chunk onset clipping as a side effect)
4. #7 TTS streaming/cancel, #6 AEC + mic-only buddie mode (conversation quality)
5. #8 async dispatch, #10 telemetry, then P2 as touched
