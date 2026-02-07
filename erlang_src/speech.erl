%%% @doc Speech interface for the BEAM app.
%%% Uses sherpa-onnx JNI for local STT/TTS (no subprocess overhead).
-module(speech).
-export([say/1, say/2, listen/0, listen/1, stream_listen/0, stream_listen/1,
         transcribe/1, status/0]).

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
-spec listen() -> {ok, binary()} | {error, term()}.
listen() ->
    listen(5).

%% @doc Record from microphone for N seconds and transcribe.
%% Uses the combined listen command which handles record + STT via JNI.
-spec listen(pos_integer()) -> {ok, binary()} | {error, term()}.
listen(Seconds) ->
    %% Model is already loaded in JNI — just need recording + inference time
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

%% @doc Streaming listen: records from mic with real-time STT and VAD.
%% Returns when speech endpoint is detected or timeout.
-spec stream_listen() -> {ok, binary()} | {error, term()}.
stream_listen() ->
    stream_listen(10).

%% @doc Streaming listen with custom max duration.
-spec stream_listen(pos_integer()) -> {ok, binary()} | {error, term()}.
stream_listen(MaxSeconds) ->
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

%% @doc Transcribe a WAV file using sherpa-onnx JNI.
-spec transcribe(binary() | string()) -> {ok, binary()} | {error, term()}.
transcribe(WavPath) ->
    case android:call(<<"stt">>, to_bin(WavPath), 30000) of
        {ok, #{<<"text">> := Text}} when Text =/= <<>> ->
            {ok, Text};
        {ok, #{<<"error">> := Err}} ->
            {error, Err};
        {ok, Other} ->
            {error, Other};
        {error, _} = Err ->
            Err
    end.

%% @doc Check speech engine status (which components are loaded).
-spec status() -> {ok, map()} | {error, term()}.
status() ->
    android:call(<<"speech_status">>, <<>>, 5000).

%% Internal
to_bin(B) when is_binary(B) -> B;
to_bin(L) when is_list(L) -> list_to_binary(L).
