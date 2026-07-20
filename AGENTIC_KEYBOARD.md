# Agentic Keyboard Assessment

## Summary

An agentic keyboard could be a useful feature for this project, but it should start as an opt-in Beam Action Keyboard rather than a full replacement keyboard or an open skill marketplace.

The useful interaction model from Acti is: type an intent, hold or tap a keyboard action control, preview the result, then explicitly apply it. That model matters because this project already has powerful Android automation primitives. A keyboard would make those capabilities available anywhere there is a text field, but it must not silently read, transmit, or act on user input.

This repository already has much of the backend substrate:

- On-device Gemma generation through LiteRT-LM.
- Speech, transcription, TTS, and listening commands.
- Android API access through the bridge server.
- Accessibility-based screen reading, typing, tapping, scrolling, screenshots, and app launching.
- BEAM/Elixir modules that can become a local skill runtime.

The missing piece is a safe, ergonomic invocation surface and a stricter action policy.

## Benefits

- Reduces app switching by making Beam actions available from messages, email, notes, browsers, and other text fields.
- Turns the existing BEAM/Gemma/Android bridge into a daily user experience instead of only a command console.
- Creates a strong local-first mobile-agent differentiator.
- Fits common workflows: rewrite text, summarize selected text, translate, draft replies, generate commands, paste structured output, look up local context, or control phone features.
- Can be more privacy-preserving than cloud agent keyboards if inference and skill execution stay local by default.

## Pros

- High leverage because the backend capabilities already exist.
- Natural UX: text fields become the prompt surface.
- Voice input can reuse the existing speech stack.
- BEAM/Elixir modules can become private skills without building a custom UI for each workflow.
- The feature would align the project around a coherent story: local runtime, local model, local automation, available across Android.

## Cons

- Building a good keyboard is product-heavy: layouts, typing feel, selection behavior, autocorrect, haptics, emoji, language switching, and performance all matter.
- Users are rightly suspicious of third-party keyboards.
- Bugs have a larger blast radius because the keyboard can appear in every app.
- LLM latency and model memory pressure can make typing feel slow or unstable.
- Store review and user trust become harder because this project already uses broad permissions and Accessibility automation.

## Privacy And Security Ramifications

An agentic keyboard plus the existing bridge is extremely powerful. The privacy and security bar should be higher than for a normal app screen.

Required constraints:

- Do not run skills on every keystroke.
- Do not silently capture, store, or transmit typed content.
- Treat typed prompt text, selected text, clipboard text, and screen content as explicit inputs only.
- Disable or heavily restrict agent actions in password, OTP, payment, and other sensitive fields when possible.
- Preview every generated result before insertion.
- Require explicit confirmation before external actions such as sending SMS, opening URLs, writing files, running shell commands, touching UI, or using contacts.
- Add per-skill capability manifests such as `text_only`, `clipboard_read`, `contacts_read`, `sms_send`, `screen_control`, `file_write`, and `shell`.
- Keep a hard policy boundary between generating text and performing actions.
- Keep local inference and local execution as the default path.
- Log only minimal metadata needed for debugging. Avoid logging prompts, clipboard data, screen content, message bodies, contacts, and generated sensitive output.

Repo-specific risks:

- The bridge exposes sensitive commands including shell execution, contacts, SMS, file access, screen control, and screenshots.
- The Accessibility service can read and manipulate the active UI.
- The app requests broad permissions, so a compromised local command path would have serious impact.
- Local TCP services must bind to loopback only and should eventually have an internal authorization layer as defense in depth.
- If distributed or remote control features invoke phone actions, action confirmation and capability restrictions should still happen on the phone.

## Recommendation

Build a narrow MVP first:

1. Add an optional `InputMethodService` named Beam Keyboard.
2. Start with an action bar or command input, not a full general-purpose keyboard.
3. Provide a visible switch-back key to return to the user's normal keyboard.
4. Send only explicit prompts to local Gemma or BEAM.
5. Show generated output inside the keyboard.
6. Let `Insert` commit text into the focused field.
7. Require a separate confirmation sheet for any tool action outside text insertion.
8. Ship with private local skills only.

Do not start with:

- A public Skill Hub.
- Autonomous cross-app planning.
- Background skill execution.
- Cloud model calls by default.
- Automatic clipboard or screen capture.
- Direct shell, SMS, file-write, or screen-control skills available from the keyboard without capability gating.

## Suggested Phases

### Phase 1: Safe Text Actions

- Rewrite selected or typed text.
- Summarize selected or clipboard text with explicit user action.
- Translate text.
- Draft replies.
- Insert generated text only after preview.

### Phase 2: Private Local Skills

- Bind local BEAM/Elixir workflows to named actions.
- Add per-skill capability declarations.
- Add a skill approval UI that clearly shows inputs, tools, and outputs.

### Phase 3: Controlled Android Actions

- Add narrow, deterministic actions such as opening a URL, creating a notification, setting clipboard text, or launching an app.
- Require confirmation before execution.
- Add audit metadata for actions, excluding sensitive content.

### Phase 4: Agentic Screen Workflows

- Allow multi-step UI automation only after the action policy is mature.
- Keep each proposed step visible and cancellable.
- Treat Accessibility-driven actions as high-risk.

## Implementation Notes

- Bind local TCP listeners to `127.0.0.1`.
- Prefer Android local IPC or bound services over unauthenticated TCP for new keyboard-to-service calls.
- Add an action policy layer between skills and `BridgeServer` commands.
- Split commands into risk classes: read-only local, sensitive read, text insertion, external action, destructive action, and arbitrary execution.
- Make the keyboard call a restricted facade, not the full bridge command surface.
- Keep `shell`, file write, SMS send, screen tap/type, and BEAM eval/load out of the default keyboard skill set.

## References

- Acti agentic keyboard concept: https://openacti.com/
- Acti interaction model: https://openacti.com/what-is-agentic-keyboard/
- Acti privacy model: https://openacti.com/privacy/
- Android input method overview: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Google Play AccessibilityService policy: https://support.google.com/googleplay/android-developer/answer/10964491
