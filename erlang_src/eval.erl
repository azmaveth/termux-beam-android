%%% @doc On-device AI evaluation framework for BeamApp.
%%%
%%% Measures performance and accuracy of the bundled speech and LLM engines:
%%%   - TTS: generation latency, throughput (chars/sec), real-time factor
%%%   - STT: transcription accuracy (WER), latency, real-time factor
%%%   - Round-trip: TTS → WAV → STT → compare against original text
%%%   - Gemma/LLM: load time, generation latency, tokens/sec, simple QA accuracy
%%%   - System: battery, temperature, memory snapshots
%%%
%%% Usage:
%%%   eval:run_all().              %% Run full suite, save results
%%%   eval:run(tts).               %% Run just TTS evals
%%%   eval:run(stt).               %% Run just STT evals (needs TTS for test audio)
%%%   eval:run(roundtrip).         %% Run TTS→STT round-trip accuracy
%%%   eval:run(gemma).             %% Run Gemma evals (model must be loaded)
%%%   eval:run(system).            %% Capture system snapshot only
%%%
%%% Results are saved as JSON to /sdcard/.beam-evals/<timestamp>.json
-module(eval).
-export([run_all/0, run/1]).

-define(EVAL_DIR, "/sdcard/.beam-evals").
-define(TMP_DIR,  "/sdcard/.beam-evals/tmp").

%%% ================================================================
%%% Public API
%%% ================================================================

run_all() ->
    io:format("[eval] Starting full evaluation suite~n"),
    ensure_dirs(),
    T0 = erlang:monotonic_time(millisecond),
    System  = run_system_eval(),
    TTS     = run_tts_eval(),
    STT     = run_stt_eval(),
    RT      = run_roundtrip_eval(),
    Gemma   = run_gemma_eval(),
    Elapsed = erlang:monotonic_time(millisecond) - T0,
    SystemEnd = capture_system_snapshot(),
    Report = #{
        <<"suite">>      => <<"full">>,
        <<"timestamp">>  => timestamp(),
        <<"elapsed_ms">> => Elapsed,
        <<"system">>     => System,
        <<"system_end">> => SystemEnd,
        <<"tts">>        => TTS,
        <<"stt">>        => STT,
        <<"roundtrip">>  => RT,
        <<"gemma">>      => Gemma
    },
    Path = save_report(Report),
    io:format("[eval] Suite complete in ~.1fs — results: ~s~n",
              [Elapsed / 1000, Path]),
    {ok, Report}.

run(tts)       -> ensure_dirs(), wrap_single(<<"tts">>,       fun run_tts_eval/0);
run(stt)       -> ensure_dirs(), wrap_single(<<"stt">>,       fun run_stt_eval/0);
run(roundtrip) -> ensure_dirs(), wrap_single(<<"roundtrip">>, fun run_roundtrip_eval/0);
run(gemma)     -> ensure_dirs(), wrap_single(<<"gemma">>,     fun run_gemma_eval/0);
run(system)    -> ensure_dirs(), wrap_single(<<"system">>,    fun run_system_eval/0).

%%% ================================================================
%%% TTS Evaluation
%%% ================================================================

run_tts_eval() ->
    io:format("[eval:tts] Running TTS benchmarks~n"),
    Sentences = tts_test_sentences(),
    Results = lists:map(fun({Label, Text}) ->
        io:format("[eval:tts]   ~s... ", [Label]),
        T0 = erlang:monotonic_time(millisecond),
        case android:call(<<"tts">>, list_to_binary(Text), 30000) of
            {ok, #{<<"gen_ms">> := GenMs, <<"duration">> := Duration} = R} ->
                Wall = erlang:monotonic_time(millisecond) - T0,
                Chars = length(Text),
                CharsPerSec = Chars * 1000.0 / max(1, GenMs),
                RTF = GenMs / max(1, Duration * 1000),
                io:format("~.0fms gen, ~.1fs audio, ~.0f chars/s~n",
                          [GenMs, Duration, CharsPerSec]),
                #{<<"label">>     => list_to_binary(Label),
                  <<"chars">>     => Chars,
                  <<"gen_ms">>    => GenMs,
                  <<"wall_ms">>   => Wall,
                  <<"duration">>  => Duration,
                  <<"chars_per_sec">> => round(CharsPerSec * 10) / 10,
                  <<"rtf">>      => round(RTF * 1000) / 1000,
                  <<"passed">>   => true};
            {ok, #{<<"error">> := Err}} ->
                io:format("ERROR: ~s~n", [Err]),
                #{<<"label">> => list_to_binary(Label),
                  <<"error">> => Err, <<"passed">> => false};
            {error, Err} ->
                io:format("ERROR: ~p~n", [Err]),
                #{<<"label">> => list_to_binary(Label),
                  <<"error">> => to_bin(Err), <<"passed">> => false}
        end
    end, Sentences),
    Passed = length([R || #{<<"passed">> := true} = R <- Results]),
    io:format("[eval:tts] ~p/~p passed~n", [Passed, length(Results)]),
    #{<<"results">> => Results, <<"passed">> => Passed,
      <<"total">> => length(Results)}.

tts_test_sentences() ->
    [{"short",  "Hello world"},
     {"medium", "The quick brown fox jumps over the lazy dog near the riverbank"},
     {"long",   "Artificial intelligence has transformed the way we interact with "
                "technology. From voice assistants to autonomous vehicles, machine "
                "learning models are becoming increasingly capable of understanding "
                "and generating human language with remarkable accuracy."},
     {"numbers", "The temperature is 72 degrees at 3:45 PM on January 15th, 2026"},
     {"technical", "The Erlang virtual machine runs on BEAM with OTP version 28"}].

%%% ================================================================
%%% STT Evaluation (uses TTS-generated audio as test input)
%%% ================================================================

run_stt_eval() ->
    io:format("[eval:stt] Running STT benchmarks~n"),
    %% Generate test audio via TTS, then transcribe with streaming STT
    Sentences = stt_test_sentences(),
    Results = lists:map(fun({Label, Text}) ->
        WavPath = lists:flatten(io_lib:format("~s/stt_~s.wav", [?TMP_DIR, Label])),
        io:format("[eval:stt]   ~s: generating audio... ", [Label]),
        case tts_to_file(WavPath, Text) of
            {ok, #{<<"duration">> := AudioDur}} ->
                io:format("transcribing... "),
                T0 = erlang:monotonic_time(millisecond),
                case android:call(<<"stt">>, list_to_binary(WavPath), 60000) of
                    {ok, #{<<"text">> := SttText} = SttR} ->
                        SttMs = erlang:monotonic_time(millisecond) - T0,
                        RTF = SttMs / max(1, AudioDur * 1000),
                        io:format("~.0fms (RTF ~.3f)~n", [SttMs, RTF]),
                        #{<<"label">>   => list_to_binary(Label),
                          <<"ref">>     => list_to_binary(Text),
                          <<"hyp">>     => SttText,
                          <<"audio_dur">> => AudioDur,
                          <<"stt_ms">>  => SttMs,
                          <<"rtf">>     => round(RTF * 1000) / 1000,
                          <<"passed">>  => true};
                    {ok, Other} ->
                        io:format("unexpected: ~p~n", [Other]),
                        #{<<"label">> => list_to_binary(Label),
                          <<"error">> => to_bin(Other), <<"passed">> => false};
                    {error, Err} ->
                        io:format("ERROR: ~p~n", [Err]),
                        #{<<"label">> => list_to_binary(Label),
                          <<"error">> => to_bin(Err), <<"passed">> => false}
                end;
            {error, Err} ->
                io:format("TTS failed: ~p~n", [Err]),
                #{<<"label">> => list_to_binary(Label),
                  <<"error">> => to_bin(Err), <<"passed">> => false}
        end
    end, Sentences),
    Passed = length([R || #{<<"passed">> := true} = R <- Results]),
    io:format("[eval:stt] ~p/~p passed~n", [Passed, length(Results)]),
    #{<<"results">> => Results, <<"passed">> => Passed,
      <<"total">> => length(Results)}.

stt_test_sentences() ->
    [{"short",    "Hello world"},
     {"medium",   "The quick brown fox jumps over the lazy dog"},
     {"long",     "Artificial intelligence has transformed the way we interact "
                  "with technology from voice assistants to autonomous vehicles"},
     {"numbers",  "Call me at 555 1234 before 3 PM tomorrow"},
     {"names",    "Doctor Sarah Johnson works at Mount Sinai Hospital"}].

%%% ================================================================
%%% Round-trip TTS→STT Accuracy (WER)
%%% ================================================================

run_roundtrip_eval() ->
    io:format("[eval:roundtrip] Running TTS→STT round-trip accuracy~n"),
    Sentences = roundtrip_test_sentences(),
    Results = lists:map(fun({Label, Text}) ->
        WavPath = lists:flatten(io_lib:format("~s/rt_~s.wav", [?TMP_DIR, Label])),
        io:format("[eval:roundtrip]   ~s: ", [Label]),
        case tts_to_file(WavPath, Text) of
            {ok, _} ->
                case android:call(<<"stt">>, list_to_binary(WavPath), 60000) of
                    {ok, #{<<"text">> := Hyp}} ->
                        WER = word_error_rate(Text, binary_to_list(Hyp)),
                        io:format("WER=~.1f%~n", [WER * 100]),
                        #{<<"label">>  => list_to_binary(Label),
                          <<"ref">>    => list_to_binary(Text),
                          <<"hyp">>    => Hyp,
                          <<"wer">>    => round(WER * 1000) / 1000,
                          <<"passed">> => WER < 0.5};
                    {ok, Other} ->
                        io:format("stt fail: ~p~n", [Other]),
                        #{<<"label">> => list_to_binary(Label),
                          <<"error">> => to_bin(Other), <<"passed">> => false};
                    {error, Err} ->
                        io:format("error: ~p~n", [Err]),
                        #{<<"label">> => list_to_binary(Label),
                          <<"error">> => to_bin(Err), <<"passed">> => false}
                end;
            {error, Err} ->
                io:format("tts fail: ~p~n", [Err]),
                #{<<"label">> => list_to_binary(Label),
                  <<"error">> => to_bin(Err), <<"passed">> => false}
        end
    end, Sentences),
    AvgWER = case [W || #{<<"wer">> := W} <- Results] of
        [] -> -1;
        Ws -> lists:sum(Ws) / length(Ws)
    end,
    Passed = length([R || #{<<"passed">> := true} = R <- Results]),
    io:format("[eval:roundtrip] ~p/~p passed, avg WER=~.1f%~n",
              [Passed, length(Results), AvgWER * 100]),
    #{<<"results">> => Results, <<"passed">> => Passed,
      <<"total">> => length(Results), <<"avg_wer">> => round(AvgWER * 1000) / 1000}.

roundtrip_test_sentences() ->
    [{"greeting",  "Hello how are you doing today"},
     {"weather",   "It is seventy two degrees and sunny outside"},
     {"question",  "What time does the meeting start tomorrow morning"},
     {"statement", "The red car parked next to the building belongs to my neighbor"},
     {"technical", "Please update the configuration file and restart the server"},
     {"mixed",     "I need 3 copies of report number 47 by Friday afternoon"}].

%%% ================================================================
%%% Gemma / LLM Evaluation
%%% ================================================================

run_gemma_eval() ->
    io:format("[eval:gemma] Running Gemma benchmarks~n"),
    case gemma:status() of
        {ok, #{<<"loaded">> := true}} -> run_gemma_eval_loaded();
        _ ->
            io:format("[eval:gemma] No model loaded — skipping~n"),
            #{<<"skipped">> => true, <<"reason">> => <<"no model loaded">>}
    end.

run_gemma_eval_loaded() ->
    %% Latency benchmark — short/medium/long prompts
    LatencyResults = lists:map(fun({Label, Prompt}) ->
        io:format("[eval:gemma]   ~s: ", [Label]),
        T0 = erlang:monotonic_time(millisecond),
        case gemma:generate(list_to_binary(Prompt), 120000) of
            {ok, #{<<"text">> := Text, <<"elapsed_ms">> := ElapsedMs}} ->
                Wall = erlang:monotonic_time(millisecond) - T0,
                %% Rough token count: split on whitespace
                Tokens = length(string:tokens(binary_to_list(Text), " \t\n")),
                TokPerSec = Tokens * 1000.0 / max(1, ElapsedMs),
                io:format("~p tok in ~.1fs (~.1f tok/s)~n",
                          [Tokens, ElapsedMs / 1000, TokPerSec]),
                #{<<"label">>       => list_to_binary(Label),
                  <<"prompt">>      => list_to_binary(Prompt),
                  <<"response">>    => Text,
                  <<"tokens">>      => Tokens,
                  <<"elapsed_ms">>  => ElapsedMs,
                  <<"wall_ms">>     => Wall,
                  <<"tok_per_sec">> => round(TokPerSec * 10) / 10,
                  <<"passed">>      => true};
            {error, Err} ->
                io:format("ERROR: ~p~n", [Err]),
                #{<<"label">> => list_to_binary(Label),
                  <<"error">> => to_bin(Err), <<"passed">> => false}
        end
    end, gemma_latency_prompts()),
    %% Accuracy benchmark — factual QA
    QAResults = lists:map(fun({Question, Expected}) ->
        Prompt = "Answer in one word or short phrase: " ++ Question,
        io:format("[eval:gemma]   QA: ~s → ", [Question]),
        case gemma:generate(list_to_binary(Prompt), 60000) of
            {ok, #{<<"text">> := Answer}} ->
                AnswerLower = string:lowercase(binary_to_list(Answer)),
                ExpLower = string:lowercase(Expected),
                Match = string:find(AnswerLower, ExpLower) =/= nomatch,
                io:format("~s [~s]~n", [Answer, if Match -> "PASS"; true -> "FAIL" end]),
                #{<<"question">> => list_to_binary(Question),
                  <<"expected">> => list_to_binary(Expected),
                  <<"answer">>   => Answer,
                  <<"match">>    => Match,
                  <<"passed">>   => Match};
            {error, Err} ->
                io:format("ERROR: ~p~n", [Err]),
                #{<<"question">> => list_to_binary(Question),
                  <<"error">> => to_bin(Err), <<"passed">> => false}
        end
    end, gemma_qa_pairs()),
    LatPassed = length([R || #{<<"passed">> := true} = R <- LatencyResults]),
    QAPassed  = length([R || #{<<"passed">> := true} = R <- QAResults]),
    #{<<"latency">> => #{<<"results">> => LatencyResults,
                         <<"passed">> => LatPassed,
                         <<"total">> => length(LatencyResults)},
      <<"qa">>      => #{<<"results">> => QAResults,
                         <<"passed">> => QAPassed,
                         <<"total">> => length(QAResults)}}.

gemma_latency_prompts() ->
    [{"short",  "What is 2+2?"},
     {"medium", "Explain what an Erlang process is in two sentences."},
     {"long",   "Write a short paragraph about the history of the "
                "Erlang programming language, including who created it "
                "and why it was designed."}].

gemma_qa_pairs() ->
    [{"What is the capital of France?",     "Paris"},
     {"What planet is closest to the Sun?", "Mercury"},
     {"What is H2O commonly known as?",     "water"},
     {"Who wrote Romeo and Juliet?",        "Shakespeare"},
     {"What is the boiling point of water in Celsius?", "100"},
     {"What color do you get mixing red and blue?",     "purple"},
     {"How many continents are there?",     "7"},
     {"What is the chemical symbol for gold?", "Au"}].

%%% ================================================================
%%% System Snapshot
%%% ================================================================

run_system_eval() ->
    io:format("[eval:system] Capturing system snapshot~n"),
    capture_system_snapshot().

capture_system_snapshot() ->
    Device  = safe_call(<<"device_info">>),
    Battery = safe_call(<<"battery">>),
    Memory  = safe_call(<<"memory_info">>),
    Speech  = safe_call(<<"speech_status">>),
    Gemma   = safe_call(<<"gemma_status">>),
    #{<<"device">>  => Device,
      <<"battery">> => Battery,
      <<"memory">>  => Memory,
      <<"speech">>  => Speech,
      <<"gemma">>   => Gemma}.

%%% ================================================================
%%% Helpers
%%% ================================================================

tts_to_file(WavPath, Text) ->
    Args = list_to_binary(WavPath ++ " " ++ Text),
    case android:call(<<"tts_file">>, Args, 30000) of
        {ok, #{<<"status">> := <<"ok">>} = R} -> {ok, R};
        {ok, #{<<"error">> := Err}}           -> {error, Err};
        {error, _} = Err                      -> Err;
        Other                                 -> {error, Other}
    end.

safe_call(Cmd) ->
    case android:call(Cmd, <<>>, 5000) of
        {ok, R}    -> R;
        {error, E} -> #{<<"error">> => to_bin(E)}
    end.

%% Word Error Rate — Levenshtein distance on word sequences.
word_error_rate(Ref, Hyp) ->
    RefWords = normalize_words(Ref),
    HypWords = normalize_words(Hyp),
    case length(RefWords) of
        0 -> case length(HypWords) of 0 -> 0.0; _ -> 1.0 end;
        N -> min(1.0, levenshtein(RefWords, HypWords) / N)
    end.

normalize_words(Text) ->
    Lower = string:lowercase(Text),
    %% Strip punctuation, split on whitespace
    Cleaned = [C || C <- Lower, C >= $a andalso C =< $z
                               orelse C >= $0 andalso C =< $9
                               orelse C =:= $\s],
    string:tokens(Cleaned, " ").

levenshtein(S, T) ->
    M = length(S),
    N = length(T),
    %% Build matrix row by row
    Row0 = lists:seq(0, N),
    {_, LastRow} = lists:foldl(fun(Si, {I, PrevRow}) ->
        {_, NewRow} = lists:foldl(fun(Tj, {J, [Prev | _] = Acc}) ->
            Diag = lists:nth(J, PrevRow),
            Up   = lists:nth(J + 1, PrevRow),
            Cost = if Si =:= Tj -> 0; true -> 1 end,
            Val  = min(min(Prev + 1, Up + 1), Diag + Cost),
            {J + 1, [Val | Acc]}
        end, {1, [I]}, T),
        {I + 1, lists:reverse(NewRow)}
    end, {1, Row0}, S),
    lists:last(LastRow).

timestamp() ->
    {{Y,Mo,D},{H,Mi,S}} = calendar:local_time(),
    list_to_binary(io_lib:format("~4..0B-~2..0B-~2..0BT~2..0B:~2..0B:~2..0B",
                                 [Y,Mo,D,H,Mi,S])).

save_report(Report) ->
    {{Y,Mo,D},{H,Mi,S}} = calendar:local_time(),
    Filename = io_lib:format("~s/eval_~4..0B~2..0B~2..0B_~2..0B~2..0B~2..0B.json",
                             [?EVAL_DIR, Y,Mo,D,H,Mi,S]),
    Path = lists:flatten(Filename),
    Json = format_json(Report),
    ok = file:write_file(Path, Json),
    Path.

ensure_dirs() ->
    filelib:ensure_dir(?EVAL_DIR ++ "/x"),
    filelib:ensure_dir(?TMP_DIR ++ "/x"),
    ok.

wrap_single(Name, Fun) ->
    T0 = erlang:monotonic_time(millisecond),
    Result = Fun(),
    Elapsed = erlang:monotonic_time(millisecond) - T0,
    Report = #{<<"suite">> => Name, <<"timestamp">> => timestamp(),
               <<"elapsed_ms">> => Elapsed, Name => Result},
    Path = save_report(Report),
    io:format("[eval] ~s complete in ~.1fs — results: ~s~n",
              [Name, Elapsed / 1000, Path]),
    {ok, Report}.

%% Minimal JSON formatter for maps/lists/atoms/binaries/numbers.
format_json(M) when is_map(M) ->
    Pairs = maps:fold(fun(K, V, Acc) ->
        [[$", to_list(K), $", $:, format_json(V)] | Acc]
    end, [], M),
    [${, lists:join($,, Pairs), $}];
format_json(L) when is_list(L) ->
    [$[, lists:join($,, [format_json(E) || E <- L]), $]];
format_json(B) when is_binary(B) ->
    [$", escape_json_str(binary_to_list(B)), $"];
format_json(true)  -> "true";
format_json(false) -> "false";
format_json(null)  -> "null";
format_json(I) when is_integer(I) -> integer_to_list(I);
format_json(F) when is_float(F)   -> io_lib:format("~.3f", [F]).

escape_json_str([])     -> [];
escape_json_str([$" | T])  -> [$\\, $" | escape_json_str(T)];
escape_json_str([$\\ | T]) -> [$\\, $\\ | escape_json_str(T)];
escape_json_str([$\n | T]) -> [$\\, $n | escape_json_str(T)];
escape_json_str([$\r | T]) -> [$\\, $r | escape_json_str(T)];
escape_json_str([$\t | T]) -> [$\\, $t | escape_json_str(T)];
escape_json_str([C | T]) when C < 16#20 ->
    lists:flatten(io_lib:format("\\u~4.16.0B", [C])) ++ escape_json_str(T);
escape_json_str([C | T]) -> [C | escape_json_str(T)].

to_bin(B) when is_binary(B) -> B;
to_bin(A) when is_atom(A)   -> atom_to_binary(A);
to_bin(L) when is_list(L)   -> list_to_binary(L);
to_bin(I) when is_integer(I) -> integer_to_binary(I);
to_bin(T) -> list_to_binary(io_lib:format("~p", [T])).

to_list(B) when is_binary(B) -> binary_to_list(B);
to_list(A) when is_atom(A)   -> atom_to_list(A);
to_list(L) when is_list(L)   -> L.
