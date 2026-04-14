%%% @doc Test module for remote ASR round-trip latency.
%%% Generates audio locally via TTS, sends binary to remote ASR node,
%%% and measures the full round-trip time.
-module(asr_test).
-export([run/0, run/1, run/2, bench/0, bench/1]).

-define(ASR_NODE, 'arbor_gpu2@10.42.42.97').
-define(WAV_PATH, "/data/data/com.example.beamapp/files/asr_test.wav").
-define(TEST_TEXT, <<"The quick brown fox jumps over the lazy dog.">>).

%% @doc Run a single round-trip test with default text.
run() -> run(?TEST_TEXT).

%% @doc Run a single round-trip test with custom text.
run(Text) -> run(Text, ?ASR_NODE).

%% @doc Run a single round-trip test with custom text and target node.
run(Text, Node) ->
    io:format("=== ASR Round-Trip Test ===~n"),
    io:format("Target: ~p~n", [Node]),
    io:format("Text:   ~s~n", [Text]),
    io:format("~n"),

    %% Step 1: Generate audio via TTS
    io:format("[1/3] Generating audio via TTS...~n"),
    T0 = erlang:monotonic_time(millisecond),
    case android:call(<<"tts_file">>, tts_file_args(?WAV_PATH, Text), 30000) of
        {ok, _TtsResult} ->
            T1 = erlang:monotonic_time(millisecond),
            io:format("      TTS done in ~p ms~n", [T1 - T0]),

            %% Step 2: Read WAV file as binary
            io:format("[2/3] Reading WAV file...~n"),
            case file:read_file(?WAV_PATH) of
                {ok, AudioBin} ->
                    T2 = erlang:monotonic_time(millisecond),
                    io:format("      Read ~p bytes (~.1f KB) in ~p ms~n",
                              [byte_size(AudioBin),
                               byte_size(AudioBin) / 1024.0,
                               T2 - T1]),

                    %% Step 3: RPC to remote ASR
                    io:format("[3/3] Calling Asr.transcribe on ~p...~n", [Node]),
                    T3 = erlang:monotonic_time(millisecond),
                    case rpc:call(Node, 'Elixir.Asr', transcribe, [AudioBin], 30000) of
                        {badrpc, Reason} ->
                            T4 = erlang:monotonic_time(millisecond),
                            io:format("      RPC FAILED after ~p ms: ~p~n", [T4 - T3, Reason]),
                            {error, {badrpc, Reason}};
                        Result ->
                            T4 = erlang:monotonic_time(millisecond),
                            RpcMs = T4 - T3,
                            TotalMs = T4 - T0,
                            io:format("~n--- Results ---~n"),
                            io:format("ASR result:   ~p~n", [Result]),
                            io:format("TTS gen:      ~p ms~n", [T1 - T0]),
                            io:format("File read:    ~p ms~n", [T2 - T1]),
                            io:format("RPC (network + ASR): ~p ms~n", [RpcMs]),
                            io:format("Total:        ~p ms~n", [TotalMs]),
                            io:format("Audio size:   ~.1f KB~n", [byte_size(AudioBin) / 1024.0]),
                            {ok, #{
                                result => Result,
                                tts_ms => T1 - T0,
                                read_ms => T2 - T1,
                                rpc_ms => RpcMs,
                                total_ms => TotalMs,
                                audio_bytes => byte_size(AudioBin)
                            }}
                    end;
                {error, Reason} ->
                    io:format("      Failed to read WAV: ~p~n", [Reason]),
                    {error, {read_file, Reason}}
            end;
        {error, Err} ->
            io:format("      TTS failed: ~p~n", [Err]),
            {error, {tts, Err}}
    end.

%% @doc Run multiple iterations and report statistics.
bench() -> bench(5).

bench(N) ->
    io:format("=== ASR Latency Benchmark (~p iterations) ===~n~n", [N]),
    Results = lists:foldl(fun(I, Acc) ->
        io:format("--- Iteration ~p/~p ---~n", [I, N]),
        case run() of
            {ok, #{rpc_ms := RpcMs, total_ms := TotalMs}} ->
                io:format("~n"),
                [{RpcMs, TotalMs} | Acc];
            {error, Err} ->
                io:format("  FAILED: ~p~n~n", [Err]),
                Acc
        end
    end, [], lists:seq(1, N)),

    case Results of
        [] ->
            io:format("All iterations failed.~n"),
            {error, all_failed};
        _ ->
            RpcTimes = [R || {R, _} <- Results],
            TotalTimes = [T || {_, T} <- Results],
            io:format("~n=== Summary (~p/~p successful) ===~n", [length(Results), N]),
            io:format("RPC latency:   min=~p avg=~p max=~p ms~n",
                      [lists:min(RpcTimes),
                       round(lists:sum(RpcTimes) / length(RpcTimes)),
                       lists:max(RpcTimes)]),
            io:format("Total latency: min=~p avg=~p max=~p ms~n",
                      [lists:min(TotalTimes),
                       round(lists:sum(TotalTimes) / length(TotalTimes)),
                       lists:max(TotalTimes)]),
            {ok, #{
                runs => length(Results),
                rpc_min => lists:min(RpcTimes),
                rpc_avg => round(lists:sum(RpcTimes) / length(RpcTimes)),
                rpc_max => lists:max(RpcTimes),
                total_min => lists:min(TotalTimes),
                total_avg => round(lists:sum(TotalTimes) / length(TotalTimes)),
                total_max => lists:max(TotalTimes)
            }}
    end.

%%% Internal

tts_file_args(Path, Text) when is_list(Text) ->
    tts_file_args(Path, list_to_binary(Text));
tts_file_args(Path, Text) when is_binary(Text) ->
    PathBin = if is_list(Path) -> list_to_binary(Path); true -> Path end,
    <<PathBin/binary, " ", Text/binary>>.
