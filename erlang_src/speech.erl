%%% @doc Speech interface for the BEAM app.
%%% Uses sherpa-onnx JNI for local STT/TTS (no subprocess overhead).
%%% Supports remote ASR via cluster RPC when configured.
-module(speech).
-export([say/1, say/2, listen/0, listen/1, stream_listen/0, stream_listen/1,
         transcribe/1, status/0]).
-export([asr_mode/0, set_asr_mode/1, remote_asr_node/0, set_remote_asr_node/1]).
-export([hotwords/0, set_hotwords/1, clear_hotwords/0]).

-define(CONFIG_PATH, "/sdcard/.beam-config").
-define(DEFAULT_ASR_NODE, 'arbor_gpu2@10.42.42.97').

%% @doc Speak text aloud using local TTS (KittenTTS via JNI).
-spec say(binary() | string()) -> {ok, map()} | {error, term()}.
say(Text) ->
    case android:call(<<"tts">>, to_bin(Text), 30000) of
        {ok, Result} when is_map(Result) ->
            case maps:get(<<"error">>, Result, undefined) of
                undefined -> {ok, Result};
                Err -> {error, Err}
            end;
        {ok, Result} -> {ok, Result};
        {error, _} = Err -> Err;
        Other -> {error, Other}
    end.

%% @doc Speak text with options (future: voice, rate, pitch).
-spec say(binary() | string(), map()) -> {ok, map()} | {error, term()}.
say(Text, _Opts) ->
    say(Text).

%% @doc Record from microphone for 5 seconds and transcribe.
%% Routes to local or remote ASR based on config.
-spec listen() -> {ok, binary()} | {error, term()}.
listen() ->
    listen(5).

%% @doc Record from microphone for N seconds and transcribe.
-spec listen(pos_integer()) -> {ok, binary()} | {error, term()}.
listen(Seconds) ->
    case asr_mode() of
        remote -> remote_listen(Seconds);
        local  -> local_listen(Seconds)
    end.

%% @doc Streaming listen: records from mic with real-time STT and VAD.
%% Returns when speech endpoint is detected or timeout.
-spec stream_listen() -> {ok, binary()} | {error, term()}.
stream_listen() ->
    stream_listen(10).

%% @doc Streaming listen with custom max duration.
-spec stream_listen(pos_integer()) -> {ok, binary()} | {error, term()}.
stream_listen(MaxSeconds) ->
    case asr_mode() of
        remote -> remote_listen(MaxSeconds);
        local  -> local_stream_listen(MaxSeconds)
    end.

%% @doc Transcribe a WAV file using configured ASR backend.
-spec transcribe(binary() | string()) -> {ok, binary()} | {error, term()}.
transcribe(WavPath) ->
    case asr_mode() of
        remote -> remote_transcribe(to_bin(WavPath));
        local  -> local_transcribe(to_bin(WavPath))
    end.

%% @doc Check speech engine status (which components are loaded).
-spec status() -> {ok, map()} | {error, term()}.
status() ->
    Mode = asr_mode(),
    Node = remote_asr_node(),
    LocalStatus = android:call(<<"speech_status">>, <<>>, 5000),
    RemoteStatus = case Mode of
        remote ->
            case rpc:call(Node, erlang, whereis, [asr_health_check], 5000) of
                {badrpc, _} ->
                    %% Try the Asr module health check
                    case rpc:call(Node, 'Elixir.Asr', health, [], 5000) of
                        {ok, R} -> {ok, R};
                        {badrpc, Reason} -> {error, {node_unreachable, Reason}};
                        Other -> Other
                    end;
                _ -> {ok, #{node => Node}}
            end;
        local -> not_configured
    end,
    {ok, #{mode => Mode, local => LocalStatus, remote => RemoteStatus,
           remote_node => Node}}.

%%% ============================================================
%%% Hotword Management (delegates to remote ASR node)
%%% ============================================================

%% @doc Get current hotwords from the remote ASR service.
-spec hotwords() -> {ok, list()} | {error, term()}.
hotwords() ->
    Node = remote_asr_node(),
    case rpc:call(Node, 'Elixir.Asr', hotwords, [], 5000) of
        {ok, #{<<"hotwords">> := Words}} -> {ok, Words};
        {ok, Result} -> {ok, Result};
        {badrpc, Reason} -> {error, {rpc_failed, Reason}};
        Other -> Other
    end.

%% @doc Set hotwords on the remote ASR service.
%% Words is a list of binaries or strings, e.g.:
%%   speech:set_hotwords([<<"Hysun">>, <<"Kang">>, <<"azmaveth">>]).
-spec set_hotwords(list()) -> {ok, list()} | {error, term()}.
set_hotwords(Words) when is_list(Words) ->
    BinWords = [to_bin(W) || W <- Words],
    Node = remote_asr_node(),
    case rpc:call(Node, 'Elixir.Asr', set_hotwords, [BinWords], 10000) of
        {ok, #{<<"hotwords">> := Applied}} -> {ok, Applied};
        {ok, Result} -> {ok, Result};
        {badrpc, Reason} -> {error, {rpc_failed, Reason}};
        Other -> Other
    end.

%% @doc Clear all hotwords on the remote ASR service.
-spec clear_hotwords() -> ok | {error, term()}.
clear_hotwords() ->
    Node = remote_asr_node(),
    case rpc:call(Node, 'Elixir.Asr', clear_hotwords, [], 5000) of
        {ok, _} -> ok;
        {badrpc, Reason} -> {error, {rpc_failed, Reason}};
        Other -> Other
    end.

%%% ============================================================
%%% ASR Mode Configuration
%%% ============================================================

%% @doc Get current ASR mode (local or remote).
-spec asr_mode() -> local | remote.
asr_mode() ->
    case application:get_env(beamapp, asr_mode) of
        {ok, Mode} -> Mode;
        undefined  -> read_config_asr_mode()
    end.

%% @doc Set ASR mode at runtime (persists in application env, not to disk).
-spec set_asr_mode(local | remote) -> ok.
set_asr_mode(Mode) when Mode =:= local; Mode =:= remote ->
    application:set_env(beamapp, asr_mode, Mode),
    ok.

%% @doc Get the remote ASR node name.
-spec remote_asr_node() -> atom().
remote_asr_node() ->
    case application:get_env(beamapp, remote_asr_node) of
        {ok, Node} -> Node;
        undefined  -> read_config_asr_node()
    end.

%% @doc Set the remote ASR node at runtime.
-spec set_remote_asr_node(atom()) -> ok.
set_remote_asr_node(Node) when is_atom(Node) ->
    application:set_env(beamapp, remote_asr_node, Node),
    ok.

%%% ============================================================
%%% Local ASR (sherpa-onnx JNI)
%%% ============================================================

local_listen(Seconds) ->
    Timeout = (Seconds + 5) * 1000,
    case android:call(<<"listen">>, integer_to_binary(Seconds), Timeout) of
        {ok, Result} when is_map(Result) ->
            case maps:get(<<"text">>, Result, <<>>) of
                <<>> -> {error, Result};
                Text -> {ok, Text}
            end;
        {ok, Result} -> {ok, Result};
        {error, _} = Err -> Err;
        Other -> {error, Other}
    end.

local_stream_listen(MaxSeconds) ->
    Timeout = (MaxSeconds + 5) * 1000,
    case android:call(<<"stream_listen">>, integer_to_binary(MaxSeconds), Timeout) of
        {ok, Result} when is_map(Result) ->
            case maps:get(<<"text">>, Result, <<>>) of
                <<>> -> {error, Result};
                Text -> {ok, Text}
            end;
        {ok, Result} -> {ok, Result};
        {error, _} = Err -> Err;
        Other -> {error, Other}
    end.

local_transcribe(WavPath) ->
    case android:call(<<"stt">>, WavPath, 30000) of
        {ok, #{<<"text">> := Text}} when Text =/= <<>> ->
            {ok, Text};
        {ok, #{<<"error">> := Err}} ->
            {error, Err};
        {ok, Other} ->
            {error, Other};
        {error, _} = Err ->
            Err
    end.

%%% ============================================================
%%% Remote ASR (via cluster RPC to gpu node)
%%% ============================================================

%% @doc Record locally, send WAV to remote node for transcription.
%% Uses mic_record to capture audio, waits for completion, then sends
%% the WAV file to the remote ASR node via RPC.
remote_listen(Seconds) ->
    Timeout = (Seconds + 5) * 1000,
    %% mic_record returns {"status":"recording","path":"...","duration":N}
    %% The android bridge parses this into a map or raw JSON string.
    case android:call(<<"mic_record">>, integer_to_binary(Seconds), Timeout) of
        {ok, Result} ->
            %% Wait for recording to finish
            timer:sleep((Seconds + 1) * 1000),
            %% Get the path — mic_stop returns it, or we can read it from the result
            case android:call(<<"mic_stop">>, <<>>, 5000) of
                {ok, StopResult} ->
                    WavPath = extract_path(StopResult, extract_path(Result, <<>>)),
                    case WavPath of
                        <<>> -> {error, no_wav_path};
                        _ -> remote_transcribe(WavPath)
                    end;
                _ ->
                    %% mic_stop failed, try path from initial result
                    WavPath = extract_path(Result, <<>>),
                    case WavPath of
                        <<>> -> {error, no_wav_path};
                        _ -> remote_transcribe(WavPath)
                    end
            end;
        {error, _} = Err -> Err;
        Other -> {error, Other}
    end.

%% Extract "path" from a bridge response (may be a map or raw JSON string).
extract_path(Result, Default) when is_map(Result) ->
    maps:get(<<"path">>, Result, Default);
extract_path(Result, Default) when is_binary(Result) ->
    %% Try to find path in raw JSON string: "path":"..."
    case binary:match(Result, <<"\"path\":\"">>) of
        {Pos, Len} ->
            Start = Pos + Len,
            Rest = binary:part(Result, Start, byte_size(Result) - Start),
            case binary:match(Rest, <<"\"">>) of
                {QPos, _} -> binary:part(Rest, 0, QPos);
                nomatch -> Default
            end;
        nomatch -> Default
    end;
extract_path(_, Default) -> Default.

%% @doc Send a WAV file to the remote ASR node for transcription.
%% Includes local hotwords from /sdcard/.beam-dictionary if present.
remote_transcribe(WavPath) ->
    Node = remote_asr_node(),
    case file:read_file(WavPath) of
        {ok, WavBinary} ->
            Hotwords = load_local_hotwords(),
            Opts = case Hotwords of
                [] -> [];
                _ -> [{hotwords, Hotwords}]
            end,
            case rpc:call(Node, 'Elixir.Asr', transcribe, [WavBinary, Opts], 30000) of
                {ok, #{<<"text">> := Text}} ->
                    {ok, Text};
                {ok, Result} when is_map(Result) ->
                    case maps:find(<<"text">>, Result) of
                        {ok, Text} -> {ok, Text};
                        error -> {error, {unexpected_result, Result}}
                    end;
                {badrpc, Reason} ->
                    {error, {remote_asr_failed, Reason}};
                Other ->
                    {error, {unexpected, Other}}
            end;
        {error, Reason} ->
            {error, {read_wav_failed, Reason}}
    end.

%% @doc Load hotwords from the local dictionary file.
load_local_hotwords() ->
    case file:read_file("/sdcard/.beam-dictionary") of
        {ok, Bin} ->
            Lines = string:split(binary_to_list(Bin), "\n", all),
            [list_to_binary(string:trim(L)) ||
             L <- Lines,
             string:trim(L) =/= "",
             hd(string:trim(L)) =/= $#];
        {error, _} -> []
    end.

%%% ============================================================
%%% Config file helpers
%%% ============================================================

read_config_asr_mode() ->
    case read_config_value("asr_mode") of
        "remote" -> remote;
        _ -> local
    end.

read_config_asr_node() ->
    case read_config_value("remote_asr_node") of
        "" -> ?DEFAULT_ASR_NODE;
        NodeStr -> list_to_atom(NodeStr)
    end.

read_config_value(Key) ->
    case file:read_file(?CONFIG_PATH) of
        {ok, Bin} ->
            Lines = string:split(binary_to_list(Bin), "\n", all),
            find_config_key(Lines, Key);
        {error, _} -> ""
    end.

find_config_key([], _Key) -> "";
find_config_key([Line | Rest], Key) ->
    Trimmed = string:trim(Line),
    case Trimmed of
        [$# | _] -> find_config_key(Rest, Key);
        "" -> find_config_key(Rest, Key);
        _ ->
            case string:split(Trimmed, "=") of
                [K, V] ->
                    case string:trim(K) of
                        Key -> string:trim(V);
                        _ -> find_config_key(Rest, Key)
                    end;
                _ -> find_config_key(Rest, Key)
            end
    end.

%% Internal
to_bin(B) when is_binary(B) -> B;
to_bin(L) when is_list(L) -> list_to_binary(L).
