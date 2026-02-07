%%% @doc Bridge module for calling Android APIs from BEAM.
%%% Connects to the Java BridgeServer over TCP (port 9877).
%%% Uses a persistent connection managed by a gen_server.
%%%
%%% Usage:
%%%   android:start_link().
%%%   android:device_info().
%%%   android:battery().
%%%   android:vibrate(500).
%%%   android:toast(<<"Hello from Erlang!">>).
%%%   android:sensors().
%%%   android:sensor_start(accelerometer).
%%%   android:sensor_read(accelerometer).
%%%   android:notify(<<"Title">>, <<"Body">>).
%%%   android:clipboard_get().
%%%   android:clipboard_set(<<"copied from BEAM">>).
%%%   android:wifi_info().
%%%   android:location().
%%%   android:packages().
%%%   android:memory_info().

-module(android).
-behaviour(gen_server).

%% Public API
-export([start_link/0, start_link/1, stop/0]).
-export([call/1, call/2, call/3]).
-export([device_info/0, battery/0, vibrate/0, vibrate/1]).
-export([toast/1, notify/2]).
-export([clipboard_get/0, clipboard_set/1]).
-export([sensors/0, sensor_start/1, sensor_read/1, sensor_stop/1]).
-export([wifi_info/0, network_info/0, location/0]).
-export([packages/0, system_prop/1, memory_info/0, screen_brightness/0]).
-export([ping/0]).

%% gen_server callbacks
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-record(state, {
    socket :: gen_tcp:socket() | undefined,
    host :: inet:hostname(),
    port :: inet:port_number(),
    next_id :: pos_integer(),
    pending :: #{pos_integer() => {pid(), reference()}}
}).

-define(SERVER, ?MODULE).
-define(DEFAULT_PORT, 9877).
-define(TIMEOUT, 5000).
-define(SPEECH_TIMEOUT, 60000).

%%% ============================================================
%%% Public API
%%% ============================================================

start_link() -> start_link(?DEFAULT_PORT).

start_link(Port) ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, Port, []).

stop() -> gen_server:stop(?SERVER).

%% Generic call
call(Cmd) -> call(Cmd, <<>>, ?TIMEOUT).
call(Cmd, Args) -> call(Cmd, Args, ?TIMEOUT).
call(Cmd, Args, Timeout) ->
    gen_server:call(?SERVER, {cmd, Cmd, Args}, Timeout).

%% ---- Device ----

device_info() -> call(<<"device_info">>).

battery() -> call(<<"battery">>).

memory_info() -> call(<<"memory_info">>).

screen_brightness() -> call(<<"screen_brightness">>).

system_prop(Prop) when is_binary(Prop) -> call(<<"system_prop">>, Prop);
system_prop(Prop) when is_list(Prop) -> system_prop(list_to_binary(Prop));
system_prop(Prop) when is_atom(Prop) -> system_prop(atom_to_binary(Prop)).

%% ---- Feedback ----

vibrate() -> vibrate(200).
vibrate(Ms) when is_integer(Ms) ->
    call(<<"vibrate">>, integer_to_binary(Ms)).

toast(Msg) when is_binary(Msg) -> call(<<"toast">>, Msg);
toast(Msg) when is_list(Msg) -> toast(list_to_binary(Msg)).

notify(Title, Body) ->
    Arg = <<Title/binary, "|", Body/binary>>,
    call(<<"notify">>, Arg).

%% ---- Clipboard ----

clipboard_get() -> call(<<"clipboard_get">>).
clipboard_set(Text) when is_binary(Text) -> call(<<"clipboard_set">>, Text);
clipboard_set(Text) when is_list(Text) -> clipboard_set(list_to_binary(Text)).

%% ---- Sensors ----

sensors() -> call(<<"sensors_list">>).

sensor_start(Type) -> call(<<"sensor_start">>, sensor_type(Type)).
sensor_read(Type)  -> call(<<"sensor_read">>,  sensor_type(Type)).
sensor_stop(Type)  -> call(<<"sensor_stop">>,  sensor_type(Type)).

%% ---- Network ----

wifi_info() -> call(<<"wifi_info">>).
network_info() -> call(<<"network_info">>).
location() -> call(<<"location">>).

%% ---- Apps ----

packages() -> call(<<"packages">>).

%% ---- Utility ----

ping() -> call(<<"ping">>).

%%% ============================================================
%%% gen_server callbacks
%%% ============================================================

init(Port) ->
    self() ! connect,
    {ok, #state{
        host = "127.0.0.1",
        port = Port,
        next_id = 1,
        pending = #{}
    }}.

handle_call({cmd, Cmd, Args}, From, #state{socket = undefined} = State) ->
    %% Try to reconnect
    case connect(State) of
        {ok, State2} -> do_send(Cmd, Args, From, State2);
        {error, _} -> {reply, {error, not_connected}, State}
    end;
handle_call({cmd, Cmd, Args}, From, State) ->
    do_send(Cmd, Args, From, State).

handle_cast(_Msg, State) -> {noreply, State}.

handle_info(connect, State) ->
    case connect(State) of
        {ok, State2} ->
            io:format("[android] Bridge connected on port ~p~n", [State2#state.port]),
            {noreply, State2};
        {error, Reason} ->
            io:format("[android] Bridge connect failed: ~p, retrying...~n", [Reason]),
            erlang:send_after(1000, self(), connect),
            {noreply, State}
    end;

handle_info({tcp, _Sock, Data}, State) ->
    %% May receive multiple lines
    Lines = binary:split(Data, <<"\n">>, [global, trim_all]),
    State2 = lists:foldl(fun handle_response/2, State, Lines),
    {noreply, State2};

handle_info({tcp_closed, _}, State) ->
    io:format("[android] Bridge connection closed, reconnecting...~n"),
    erlang:send_after(1000, self(), connect),
    {noreply, State#state{socket = undefined}};

handle_info({tcp_error, _, Reason}, State) ->
    io:format("[android] Bridge TCP error: ~p~n", [Reason]),
    erlang:send_after(1000, self(), connect),
    {noreply, State#state{socket = undefined}};

handle_info(_Info, State) -> {noreply, State}.

terminate(_Reason, #state{socket = Sock}) ->
    case Sock of
        undefined -> ok;
        _ -> gen_tcp:close(Sock)
    end.

%%% ============================================================
%%% Internal
%%% ============================================================

connect(#state{host = Host, port = Port} = State) ->
    case gen_tcp:connect(Host, Port, [binary, {active, true}, {packet, line},
                                       {buffer, 65536}], 2000) of
        {ok, Sock} -> {ok, State#state{socket = Sock}};
        {error, _} = Err -> Err
    end.

do_send(Cmd, Args, From, #state{socket = Sock, next_id = Id, pending = Pend} = State) ->
    ArgsJson = case Args of
        <<>> -> <<"\"\"">>;
        _ when is_binary(Args) -> <<"\"", (escape_json(Args))/binary, "\"">>;
        _ when is_integer(Args) -> integer_to_binary(Args)
    end,
    Json = iolist_to_binary([
        <<"{\"id\":">>, integer_to_binary(Id),
        <<",\"cmd\":\"">>, Cmd,
        <<"\",\"args\":">>, ArgsJson,
        <<"}\n">>
    ]),
    case gen_tcp:send(Sock, Json) of
        ok ->
            Pend2 = maps:put(Id, From, Pend),
            {noreply, State#state{next_id = Id + 1, pending = Pend2}};
        {error, Reason} ->
            {reply, {error, Reason}, State#state{socket = undefined}}
    end.

handle_response(Line, #state{pending = Pend} = State) ->
    %% Parse response: {"id":N,"ok":true/false,"data":...} or {"error":...}
    IdStr = json_get(Line, <<"id">>),
    Id = try binary_to_integer(IdStr) catch _:_ -> 0 end,
    case maps:take(Id, Pend) of
        {From, Pend2} ->
            Ok = json_get(Line, <<"ok">>),
            Result = case Ok of
                <<"true">> ->
                    RawData = json_get_raw(Line, <<"data">>),
                    {ok, parse_value(RawData)};
                _ ->
                    ErrMsg = json_get(Line, <<"error">>),
                    {error, ErrMsg}
            end,
            gen_server:reply(From, Result),
            State#state{pending = Pend2};
        error ->
            State
    end.

%%% ---- Minimal JSON helpers ----

escape_json(Bin) ->
    Bin2 = binary:replace(Bin, <<"\\">>, <<"\\\\">>, [global]),
    Bin3 = binary:replace(Bin2, <<"\"">>, <<"\\\"">>, [global]),
    Bin4 = binary:replace(Bin3, <<"\n">>, <<"\\n">>, [global]),
    binary:replace(Bin4, <<"\r">>, <<"\\r">>, [global]).

%% Extract a string value for a key (unquoted)
json_get(Json, Key) ->
    Search = <<"\"", Key/binary, "\":">>,
    case binary:match(Json, Search) of
        nomatch -> <<>>;
        {Pos, Len} ->
            Start = Pos + Len,
            Rest = binary:part(Json, Start, byte_size(Json) - Start),
            Rest2 = skip_ws(Rest),
            case Rest2 of
                <<"\"", Tail/binary>> ->
                    %% String value — find closing quote
                    extract_string(Tail, <<>>);
                _ ->
                    %% Number/boolean — read until , or }
                    extract_token(Rest2, <<>>)
            end
    end.

%% Extract raw value (including objects/arrays) for a key
json_get_raw(Json, Key) ->
    Search = <<"\"", Key/binary, "\":">>,
    case binary:match(Json, Search) of
        nomatch -> <<"null">>;
        {Pos, Len} ->
            Start = Pos + Len,
            Rest = binary:part(Json, Start, byte_size(Json) - Start),
            Rest2 = skip_ws(Rest),
            extract_raw_value(Rest2)
    end.

extract_string(<<>>, Acc) -> Acc;
extract_string(<<"\\\"", T/binary>>, Acc) -> extract_string(T, <<Acc/binary, "\"">>);
extract_string(<<"\\n", T/binary>>, Acc) -> extract_string(T, <<Acc/binary, "\n">>);
extract_string(<<"\\\\", T/binary>>, Acc) -> extract_string(T, <<Acc/binary, "\\">>);
extract_string(<<"\"", _/binary>>, Acc) -> Acc;
extract_string(<<C, T/binary>>, Acc) -> extract_string(T, <<Acc/binary, C>>).

extract_token(<<>>, Acc) -> Acc;
extract_token(<<C, _/binary>>, Acc) when C =:= $, ; C =:= $}; C =:= $] -> Acc;
extract_token(<<C, T/binary>>, Acc) -> extract_token(T, <<Acc/binary, C>>).

extract_raw_value(<<"\"", _/binary>> = B) ->
    %% String
    <<"\"", T/binary>> = B,
    S = extract_string(T, <<>>),
    <<"\"", S/binary, "\"">>;
extract_raw_value(<<"{", _/binary>> = B) -> extract_balanced(B, ${, $}, 0, <<>>);
extract_raw_value(<<"[", _/binary>> = B) -> extract_balanced(B, $[, $], 0, <<>>);
extract_raw_value(B) -> extract_token(B, <<>>).

extract_balanced(<<>>, _, _, _, Acc) -> Acc;
extract_balanced(<<Open, T/binary>>, Open, Close, D, Acc) ->
    extract_balanced(T, Open, Close, D+1, <<Acc/binary, Open>>);
extract_balanced(<<Close, _/binary>>, _Open, Close, 1, Acc) ->
    <<Acc/binary, Close>>;
extract_balanced(<<Close, T/binary>>, Open, Close, D, Acc) ->
    extract_balanced(T, Open, Close, D-1, <<Acc/binary, Close>>);
extract_balanced(<<C, T/binary>>, Open, Close, D, Acc) ->
    extract_balanced(T, Open, Close, D, <<Acc/binary, C>>).

skip_ws(<<" ", T/binary>>) -> skip_ws(T);
skip_ws(<<"\t", T/binary>>) -> skip_ws(T);
skip_ws(B) -> B.

%% Parse a raw JSON value into an Erlang-friendly representation
%% Returns binary for strings, maps for objects, lists for arrays, etc.
parse_value(<<"\"", _/binary>> = V) ->
    %% String — unquote
    <<"\"", T/binary>> = V,
    %% Remove trailing quote
    S = byte_size(T) - 1,
    case S > 0 of
        true -> binary:part(T, 0, S);
        false -> <<>>
    end;
parse_value(<<"true">>) -> true;
parse_value(<<"false">>) -> false;
parse_value(<<"null">>) -> null;
parse_value(V) ->
    %% Return as-is (raw JSON string for objects/arrays/numbers)
    %% Users can decode further if needed
    V.

%%% ---- Sensor type mapping ----

sensor_type(accelerometer)     -> <<"1">>;
sensor_type(magnetic_field)    -> <<"2">>;
sensor_type(gyroscope)         -> <<"4">>;
sensor_type(light)             -> <<"5">>;
sensor_type(pressure)          -> <<"6">>;
sensor_type(proximity)         -> <<"8">>;
sensor_type(gravity)           -> <<"9">>;
sensor_type(linear_acceleration) -> <<"10">>;
sensor_type(rotation_vector)   -> <<"11">>;
sensor_type(humidity)          -> <<"12">>;
sensor_type(ambient_temperature) -> <<"13">>;
sensor_type(step_counter)      -> <<"19">>;
sensor_type(step_detector)     -> <<"18">>;
sensor_type(heart_rate)        -> <<"21">>;
sensor_type(N) when is_integer(N) -> integer_to_binary(N).
