%%% @doc Remote ASR via chunked streaming.
%%% Records audio locally, uses VAD to segment speech, and sends each
%%% utterance to a remote GPU node for transcription via RPC.
%%%
%%% Usage:
%%%   remote_asr:listen().           %% 30s max, default node
%%%   remote_asr:listen(15).         %% 15s max
%%%   remote_asr:listen(15, Node).   %% custom node
%%%   remote_asr:stop().             %% stop early
-module(remote_asr).
-export([listen/0, listen/1, listen/2, stop/0]).

-define(RPC_TIMEOUT, 30000).

%% @doc Listen for up to 30 seconds, transcribe on the configured GPU node.
listen() -> listen(30).

%% @doc Listen for up to MaxSec seconds. The node comes from speech config
%% (/sdcard/.beam-config remote_asr_node=... or application env) instead of
%% a second hardcoded default.
listen(MaxSec) -> listen(MaxSec, speech:remote_asr_node()).

%% @doc Listen for up to MaxSec seconds, transcribe on Node.
listen(MaxSec, Node) ->
    io:format("[remote_asr] Listening (max ~ps), transcribing on ~p~n",
              [MaxSec, Node]),
    %% Connect to bridge
    case gen_tcp:connect("127.0.0.1", 9877,
                         [binary, {active, false}, {packet, line},
                          {buffer, 1048576}, {recbuf, 1048576}], 2000) of
        {ok, Sock} ->
            %% Authenticate first (consumes the auth reply line)
            send_auth(Sock),
            %% Send remote_listen command
            Id = erlang:unique_integer([positive]) rem 100000,
            Cmd = iolist_to_binary([
                <<"{\"id\":">>, integer_to_binary(Id),
                <<",\"cmd\":\"remote_listen\",\"args\":\"">>,
                integer_to_binary(MaxSec),
                <<"\"}\n">>
            ]),
            ok = gen_tcp:send(Sock, Cmd),
            %% Receive chunks and transcribe each
            RecvTimeout = max(60000, MaxSec * 1000 + 5000),
            Result = recv_loop(Sock, Id, Node, [], 0, RecvTimeout),
            gen_tcp:close(Sock),
            Result;
        {error, Reason} ->
            {error, {connect, Reason}}
    end.

%% @doc Stop an in-progress listen.
stop() ->
    android:call(<<"stop_listen">>, <<>>, 1000).

%%% Internal

%% Bridge auth: send the app-private token and consume the reply line so
%% the chunk protocol starts clean. No token file → send nothing (only
%% works when the server has auth disabled too).
send_auth(Sock) ->
    case file:read_file("/data/data/com.example.beamapp/files/bridge_token") of
        {ok, Tok0} ->
            Tok = string:trim(Tok0),
            Line = <<"{\"id\":0,\"cmd\":\"auth\",\"args\":\"", Tok/binary, "\"}\n">>,
            ok = gen_tcp:send(Sock, Line),
            case gen_tcp:recv(Sock, 0, 5000) of
                {ok, _AuthReply} -> ok;
                {error, _} -> ok
            end;
        {error, _} ->
            ok
    end.

recv_loop(Sock, Id, Node, Texts, ChunkCount, RecvTimeout) ->
    %% Must outlive the bridge-side recording window: the bridge sends
    %% nothing during silence, so the timeout has to cover all of MaxSec.
    case gen_tcp:recv(Sock, 0, RecvTimeout) of
        {ok, Line} ->
            handle_line(Line, Sock, Id, Node, Texts, ChunkCount, RecvTimeout);
        {error, timeout} ->
            finalize(Texts, ChunkCount, timeout);
        {error, closed} ->
            finalize(Texts, ChunkCount, closed)
    end.

handle_line(Line, Sock, Id, Node, Texts, ChunkCount, RecvTimeout) ->
    %% Check if this is a chunk (has "audio" key) or final response
    case binary:match(Line, <<"\"audio\"">>) of
        {_, _} ->
            case decode_chunk(Line) of
                {ok, ChunkIdx, Duration, AudioBin} ->
                    handle_chunk(ChunkIdx, Duration, AudioBin,
                                 Sock, Id, Node, Texts, ChunkCount, RecvTimeout);
                error ->
                    %% Malformed or truncated chunk line — {packet, line}
                    %% splits lines longer than the socket buffer (1MB) at
                    %% the buffer boundary, so a long utterance's base64 can
                    %% arrive cut mid-stream. Skip it and keep the segments
                    %% already transcribed; the leftover fragments have no
                    %% "audio" key and fall through the keep-going branch.
                    io:format("[remote_asr] Skipping undecodable chunk line "
                              "(~p bytes)~n", [byte_size(Line)]),
                    recv_loop(Sock, Id, Node, Texts, ChunkCount, RecvTimeout)
            end;
        nomatch ->
            %% Final response or other message
            case binary:match(Line, <<"\"ok\":true">>) of
                {_, _} ->
                    finalize(Texts, ChunkCount, ok);
                nomatch ->
                    case binary:match(Line, <<"\"ok\":false">>) of
                        {_, _} ->
                            Err = json_str(Line, <<"error">>),
                            io:format("[remote_asr] Bridge error: ~s~n", [Err]),
                            {error, Err};
                        nomatch ->
                            %% Unexpected line, keep going
                            recv_loop(Sock, Id, Node, Texts, ChunkCount, RecvTimeout)
                    end
            end
    end.

%% Decode one audio-chunk line defensively. A complete line is one JSON
%% object ending in "}"; a fragment truncated at the line-buffer boundary
%% is not, and a truncated base64 payload makes base64:decode/1 badarg —
%% either way return error instead of crashing recv_loop.
decode_chunk(Line) ->
    Trimmed = string:trim(Line),
    Complete = byte_size(Trimmed) > 0 andalso binary:last(Trimmed) =:= $},
    case Complete of
        true ->
            try
                {ok, json_int(Line, <<"chunk">>),
                     json_str(Line, <<"duration">>),
                     base64:decode(json_str(Line, <<"audio">>))}
            catch
                _:_ -> error
            end;
        false ->
            error
    end.

%% Transcribe one decoded audio chunk on the remote node, then keep receiving.
handle_chunk(ChunkIdx, Duration, AudioBin, Sock, Id, Node, Texts, ChunkCount, RecvTimeout) ->
    io:format("[remote_asr] Chunk ~p: ~.1fKB, ~ss audio~n",
              [ChunkIdx, byte_size(AudioBin) / 1024.0, Duration]),
    %% RPC to remote ASR
    T0 = erlang:monotonic_time(millisecond),
    case rpc:call(Node, 'Elixir.Asr', transcribe, [AudioBin], ?RPC_TIMEOUT) of
        {ok, #{<<"text">> := Text}} ->
            RpcMs = erlang:monotonic_time(millisecond) - T0,
            io:format("[remote_asr]   -> ~s (~pms)~n", [Text, RpcMs]),
            recv_loop(Sock, Id, Node, [Text | Texts], ChunkCount + 1, RecvTimeout);
        {badrpc, Reason} ->
            RpcMs = erlang:monotonic_time(millisecond) - T0,
            io:format("[remote_asr]   -> RPC failed (~pms): ~p~n",
                      [RpcMs, Reason]),
            recv_loop(Sock, Id, Node, Texts, ChunkCount + 1, RecvTimeout);
        Other ->
            RpcMs = erlang:monotonic_time(millisecond) - T0,
            io:format("[remote_asr]   -> ~p (~pms)~n", [Other, RpcMs]),
            %% Try to extract text from unexpected format
            Text = extract_text(Other),
            NewTexts = case Text of
                <<>> -> Texts;
                _ -> [Text | Texts]
            end,
            recv_loop(Sock, Id, Node, NewTexts, ChunkCount + 1, RecvTimeout)
    end.

finalize(Texts, ChunkCount, Reason) ->
    RevTexts = lists:reverse(Texts),
    FullText = iolist_to_binary(lists:join(<<" ">>, RevTexts)),
    io:format("[remote_asr] Done: ~p chunks, reason=~p~n", [ChunkCount, Reason]),
    io:format("[remote_asr] Text: ~s~n", [FullText]),
    {ok, #{text => FullText, chunks => ChunkCount, segments => RevTexts}}.

%% Extract text from various response formats
extract_text({ok, Map}) when is_map(Map) ->
    maps:get(<<"text">>, Map, <<>>);
extract_text(_) -> <<>>.

%%% Minimal JSON helpers (binary scanning)

json_str(Json, Key) ->
    Search = <<"\"", Key/binary, "\":\"">>,
    case binary:match(Json, Search) of
        nomatch -> <<>>;
        {Pos, Len} ->
            Start = Pos + Len,
            Rest = binary:part(Json, Start, byte_size(Json) - Start),
            extract_until_quote(Rest, <<>>)
    end.

json_int(Json, Key) ->
    Search = <<"\"", Key/binary, "\":">>,
    case binary:match(Json, Search) of
        nomatch -> 0;
        {Pos, Len} ->
            Start = Pos + Len,
            Rest = binary:part(Json, Start, byte_size(Json) - Start),
            extract_int(Rest, 0)
    end.

extract_until_quote(<<>>, Acc) -> Acc;
extract_until_quote(<<"\"", _/binary>>, Acc) -> Acc;
extract_until_quote(<<"\\\"", T/binary>>, Acc) ->
    extract_until_quote(T, <<Acc/binary, "\"">>);
extract_until_quote(<<C, T/binary>>, Acc) ->
    extract_until_quote(T, <<Acc/binary, C>>).

extract_int(<<C, T/binary>>, Acc) when C >= $0, C =< $9 ->
    extract_int(T, Acc * 10 + (C - $0));
extract_int(_, Acc) -> Acc.
