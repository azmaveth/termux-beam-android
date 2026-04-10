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
%%% Note on streaming: the existing `android:call/3` gen_server does not
%%% handle streamed partial responses properly (it replies on the first line
%%% received for a given request id). The `gemma_stream` bridge command still
%%% exists and works from a raw socket (e.g. from the Android MainActivity or
%%% a plain `nc` client), but from the BEAM side use `generate/1` which is
%%% blocking and returns the full response. See TODO below.
-module(gemma).
-export([load/1, generate/1, generate/2, unload/0, status/0, cancel/0]).

%% Load timeouts: model init can be slow (E2B: ~5-15s, E4B: ~15-30s on CPU).
-define(LOAD_TIMEOUT,     120000).  %%  2 min
-define(GENERATE_TIMEOUT, 300000).  %%  5 min
-define(QUICK_TIMEOUT,      5000).

%% @doc Load a .litertlm model file. Blocking — can take several seconds.
-spec load(binary() | string()) -> ok | {error, term()}.
load(Path) ->
    case android:call(<<"gemma_load">>, to_bin(Path), ?LOAD_TIMEOUT) of
        {ok, #{<<"loaded">> := true}} -> ok;
        {ok, #{<<"error">> := Err}}   -> {error, Err};
        {ok, Other}                   -> {error, Other};
        {error, _} = Err              -> Err
    end.

%% @doc Generate a full response to the given prompt. Blocking.
-spec generate(binary() | string()) -> {ok, map()} | {error, term()}.
generate(Prompt) ->
    generate(Prompt, ?GENERATE_TIMEOUT).

%% @doc Generate with a custom timeout (milliseconds).
-spec generate(binary() | string(), pos_integer()) -> {ok, map()} | {error, term()}.
generate(Prompt, Timeout) ->
    case android:call(<<"gemma_generate">>, to_bin(Prompt), Timeout) of
        {ok, #{<<"text">> := _} = Result} -> {ok, Result};
        {ok, #{<<"error">> := Err}}       -> {error, Err};
        {ok, Other}                       -> {error, Other};
        {error, _} = Err                  -> Err
    end.

%% @doc Release the loaded model. Idempotent.
-spec unload() -> ok | {error, term()}.
unload() ->
    case android:call(<<"gemma_unload">>, <<>>, ?QUICK_TIMEOUT) of
        {ok, _}          -> ok;
        {error, _} = Err -> Err
    end.

%% @doc Current engine status — which model is loaded (if any).
-spec status() -> {ok, map()} | {error, term()}.
status() ->
    android:call(<<"gemma_status">>, <<>>, ?QUICK_TIMEOUT).

%% @doc Cancel an in-progress generation (affects any concurrent caller).
-spec cancel() -> ok | {error, term()}.
cancel() ->
    case android:call(<<"gemma_cancel">>, <<>>, ?QUICK_TIMEOUT) of
        {ok, _}          -> ok;
        {error, _} = Err -> Err
    end.

%%% TODO: real streaming support would require android.erl to deliver partial
%%% responses as `{gemma_partial, Id, Chunk}` messages to the caller's mailbox
%%% and only treat the `"ok":true` / `"ok":false` line as the final reply.
%%% The `gemma_stream` bridge command already emits the right wire format
%%% (mirroring `stream_listen`), so only the Erlang side needs wiring.

%% Internal
to_bin(B) when is_binary(B) -> B;
to_bin(L) when is_list(L)   -> list_to_binary(L).
