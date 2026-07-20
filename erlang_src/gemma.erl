%%% @doc Gemma / LiteRT-LM on-device LLM interface for the BEAM app.
%%%
%%% Uses Google's LiteRT-LM Kotlin runtime (the same one Google's Edge Gallery
%%% app ships with) via a Java facade (`GemmaEngine.java`) reachable through
%%% the bridge server on port 9877.
%%%
%%% Typical flow:
%%%   ok = gemma:load("/sdcard/Download/gemma-4-e2b-it-int4.litertlm"),
%%%   {ok, #{<<"text">> := Text}} = gemma:generate(<<"Tell me a joke">>),
%%%   ok = gemma:unload().
%%%
%%% Note on streaming: android.erl now routes stream-event lines (partials)
%%% to an optional subscriber (`android:subscribe_stream/1`, delivered as
%%% `{android_stream_event, Id, Line}`) and only treats "ok"-bearing lines
%%% as replies, so `gemma_stream` is usable from the BEAM: subscribe, call
%%% `android:call(<<"gemma_stream">>, Prompt, Timeout)`, and consume partial
%%% events until the call returns. `generate/1` remains the simple blocking
%%% path.
-module(gemma).
-export([load/1, generate/1, generate/2, unload/0, status/0, cancel/0]).
-export([stream/2, stream/3]).

%% Load timeouts: model init can be slow (E2B: ~5-15s, E4B: ~15-30s on CPU).
-define(LOAD_TIMEOUT,     120000).  %%  2 min
-define(GENERATE_TIMEOUT, 300000).  %%  5 min
-define(QUICK_TIMEOUT,      5000).

%% NOTE: android:call returns bridge JSON as a raw binary (objects are not
%% decoded into maps), so results are checked by scanning. The old map
%% pattern-matches here never matched, which made load/1 and generate/1
%% return {error, RawJson} even on success. Bridge-side failures now arrive
%% as {error, Reason} (protocol-level errors for gemma_* commands).

%% @doc Load a .litertlm model file. Blocking — can take several seconds.
-spec load(binary() | string()) -> ok | {error, term()}.
load(Path) ->
    case android:call(<<"gemma_load">>, to_bin(Path), ?LOAD_TIMEOUT) of
        {ok, Result} when is_binary(Result) ->
            case binary:match(Result, <<"\"loaded\":true">>) of
                nomatch -> {error, {unexpected, Result}};
                _ -> ok
            end;
        {ok, Other}      -> {error, {unexpected, Other}};
        {error, _} = Err -> Err
    end.

%% @doc Generate a full response to the given prompt. Blocking.
-spec generate(binary() | string()) -> {ok, map()} | {error, term()}.
generate(Prompt) ->
    generate(Prompt, ?GENERATE_TIMEOUT).

%% @doc Generate with a custom timeout (milliseconds).
%% Returns {ok, #{<<"text">> => Text}} on success.
-spec generate(binary() | string(), pos_integer()) -> {ok, map()} | {error, term()}.
generate(Prompt, Timeout) ->
    case android:call(<<"gemma_generate">>, to_bin(Prompt), Timeout) of
        {ok, Result} ->
            case speech:extract_field(Result, <<"text">>) of
                <<>> -> {error, {no_text, Result}};
                Text -> {ok, #{<<"text">> => Text}}
            end;
        {error, _} = Err -> Err
    end.

%% @doc Streaming generation: Fun(Chunk) is called for each partial text
%% chunk as it is generated; returns {ok, #{<<"text">> => FullText}} when
%% generation completes.
%%
%% Uses android:subscribe_stream/1, which supports a single subscriber —
%% don't run concurrently with another streaming command (buddie_listen
%% partials etc. would be delivered to the same subscriber).
-spec stream(binary() | string(), fun((binary()) -> any())) ->
          {ok, map()} | {error, term()}.
stream(Prompt, Fun) ->
    stream(Prompt, Fun, ?GENERATE_TIMEOUT).

-spec stream(binary() | string(), fun((binary()) -> any()), pos_integer()) ->
          {ok, map()} | {error, term()}.
stream(Prompt, Fun, Timeout) ->
    Relay = spawn_link(fun() -> relay_loop(Fun) end),
    ok = android:subscribe_stream(Relay),
    try
        case android:call(<<"gemma_stream">>, to_bin(Prompt), Timeout) of
            {ok, Result} ->
                case speech:extract_field(Result, <<"text">>) of
                    <<>> -> {error, {no_text, Result}};
                    Text -> {ok, #{<<"text">> => Text}}
                end;
            {error, _} = Err -> Err
        end
    after
        android:unsubscribe_stream(),
        Relay ! stop
    end.

relay_loop(Fun) ->
    receive
        {android_stream_event, _Id, Line} ->
            case speech:extract_field(Line, <<"text">>) of
                <<>> -> ok;
                Chunk -> Fun(Chunk)
            end,
            relay_loop(Fun);
        stop -> ok
    end.

%% @doc Release the loaded model. Idempotent.
-spec unload() -> ok | {error, term()}.
unload() ->
    case android:call(<<"gemma_unload">>, <<>>, ?QUICK_TIMEOUT) of
        {ok, _}          -> ok;
        {error, _} = Err -> Err
    end.

%% @doc Current engine status — which model is loaded (if any).
%% Returns {ok, RawJsonBinary}.
-spec status() -> {ok, binary()} | {error, term()}.
status() ->
    android:call(<<"gemma_status">>, <<>>, ?QUICK_TIMEOUT).

%% @doc Cancel an in-progress generation (affects any concurrent caller).
-spec cancel() -> ok | {error, term()}.
cancel() ->
    case android:call(<<"gemma_cancel">>, <<>>, ?QUICK_TIMEOUT) of
        {ok, _}          -> ok;
        {error, _} = Err -> Err
    end.

%%% Streaming: android:subscribe_stream/1 + android:call(<<"gemma_stream">>,
%%% Prompt, Timeout) delivers partials as {android_stream_event, Id, Line}
%%% while the call blocks until the final "ok" line. A typed wrapper here
%%% (stream/2 with a callback fun) would be a nice addition.

%% Internal
to_bin(B) when is_binary(B) -> B;
to_bin(L) when is_list(L)   -> list_to_binary(L).
