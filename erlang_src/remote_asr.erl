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

-define(ASR_NODE, 'arbor_gpu2@10.42.42.97').
-define(RPC_TIMEOUT, 30000).

%% @doc Listen for up to 30 seconds, transcribe on default GPU node.
listen() -> listen(30).

%% @doc Listen for up to MaxSec seconds.
listen(MaxSec) -> listen(MaxSec, ?ASR_NODE).

%% @doc Listen for up to MaxSec seconds, transcribe on Node.
listen(MaxSec, Node) ->
    io:format("[remote_asr] Listening (max ~ps), transcribing on ~p~n",
              [MaxSec, Node]),
    %% Connect to bridge
    case gen_tcp:connect("127.0.0.1", 9877,
                         [binary, {active, false}, {packet, line},
                          {buffer, 1048576}, {recbuf, 1048576}], 2000) of
        {ok, Sock} ->
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
            Result = recv_loop(Sock, Id, Node, [], 0),
            gen_tcp:close(Sock),
            Result;
        {error, Reason} ->
            {error, {connect, Reason}}
    end.

%% @doc Stop an in-progress listen.
stop() ->
    android:call(<<"stop_listen">>, <<>>, 1000).

%%% Internal

recv_loop(Sock, Id, Node, Texts, ChunkCount) ->
    %% Large timeout: VAD may have long silences
    case gen_tcp:recv(Sock, 0, 60000) of
        {ok, Line} ->
            handle_line(Line, Sock, Id, Node, Texts, ChunkCount);
        {error, timeout} ->
            finalize(Texts, ChunkCount, timeout);
        {error, closed} ->
            finalize(Texts, ChunkCount, closed)
    end.

handle_line(Line, Sock, Id, Node, Texts, ChunkCount) ->
    %% Check if this is a chunk (has "audio" key) or final response
    case binary:match(Line, <<"\"audio\"">>) of
        {_, _} ->
            %% Audio chunk — extract and transcribe
            ChunkIdx = json_int(Line, <<"chunk">>),
            Duration = json_str(Line, <<"duration">>),
            AudioB64 = json_str(Line, <<"audio">>),
            AudioBin = base64:decode(AudioB64),
            io:format("[remote_asr] Chunk ~p: ~.1fKB, ~ss audio~n",
                      [ChunkIdx, byte_size(AudioBin) / 1024.0, Duration]),
            %% RPC to remote ASR
            T0 = erlang:monotonic_time(millisecond),
            case rpc:call(Node, 'Elixir.Asr', transcribe, [AudioBin], ?RPC_TIMEOUT) of
                {ok, #{<<"text">> := Text}} ->
                    RpcMs = erlang:monotonic_time(millisecond) - T0,
                    io:format("[remote_asr]   -> ~s (~pms)~n", [Text, RpcMs]),
                    recv_loop(Sock, Id, Node, [Text | Texts], ChunkCount + 1);
                {badrpc, Reason} ->
                    RpcMs = erlang:monotonic_time(millisecond) - T0,
                    io:format("[remote_asr]   -> RPC failed (~pms): ~p~n",
                              [RpcMs, Reason]),
                    recv_loop(Sock, Id, Node, Texts, ChunkCount + 1);
                Other ->
                    RpcMs = erlang:monotonic_time(millisecond) - T0,
                    io:format("[remote_asr]   -> ~p (~pms)~n", [Other, RpcMs]),
                    %% Try to extract text from unexpected format
                    Text = extract_text(Other),
                    NewTexts = case Text of
                        <<>> -> Texts;
                        _ -> [Text | Texts]
                    end,
                    recv_loop(Sock, Id, Node, NewTexts, ChunkCount + 1)
            end;
        nomatch ->
            %% Final response or other message
            case binary:match(Line, <<"\"ok\":true">>) of
                {_, _} ->
                    finalize(Texts, ChunkCount, ok);
                nomatch ->
                    %% Unexpected line, keep going
                    recv_loop(Sock, Id, Node, Texts, ChunkCount)
            end
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
