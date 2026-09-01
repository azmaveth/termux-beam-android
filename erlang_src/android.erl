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
-export([subscribe_stream/1, unsubscribe_stream/0]).
-export([device_info/0, battery/0, vibrate/0, vibrate/1]).
-export([toast/1, notify/2]).
-export([clipboard_get/0, clipboard_set/1]).
-export([sensors/0, sensor_list/0, sensor_start/1, sensor_read/1, sensor_stop/1]).
-export([wifi_info/0, network_info/0, location/0, gps_fix/0, gps_fix/1]).
-export([packages/0, system_prop/1, memory_info/0, screen_brightness/0]).
-export([ping/0]).
%% Environment sensing + extras already implemented in BridgeServer/PhoneAPI,
%% surfaced here so the BEAM can actually call them.
-export([telephony_info/0, wifi_scan/0, screen_state/0, audio_info/0]).
-export([process_info/0, camera_info/0, volume_get/0, media_status/0]).
-export([bt_scan/0, bt_scan/1, bt_scan_ble/0, bt_scan_ble/1]).

%% gen_server callbacks
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-record(state, {
    socket :: gen_tcp:socket() | undefined,
    host :: inet:hostname(),
    port :: inet:port_number(),
    next_id :: pos_integer(),
    pending :: #{pos_integer() => {pid(), reference()}},
    stream_sub :: pid() | undefined,
    rxbuf = <<>> :: binary()
}).

-define(SERVER, ?MODULE).
-define(DEFAULT_PORT, 9877).
-define(TIMEOUT, 5000).
-define(SPEECH_TIMEOUT, 60000).
%% App-private shared secret written by BridgeServer at startup; same-uid
%% clients authenticate each connection with it.
-define(TOKEN_PATH, "/data/data/com.example.beamapp/files/bridge_token").

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

%% @doc Subscribe to bridge stream events (partial STT results, audio chunks).
%% Streaming commands (stream_listen, buddie_listen, remote_listen,
%% gemma_stream) emit progress lines without an "ok" field before the final
%% reply. The subscriber receives them as {android_stream_event, Id, Line}
%% where Line is the raw JSON. Only one subscriber at a time.
-spec subscribe_stream(pid()) -> ok.
subscribe_stream(Pid) when is_pid(Pid) ->
    gen_server:call(?SERVER, {subscribe_stream, Pid}).

%% @doc Remove the stream event subscriber.
-spec unsubscribe_stream() -> ok.
unsubscribe_stream() ->
    gen_server:call(?SERVER, unsubscribe_stream).

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
sensor_list() -> call(<<"sensors_list">>).

sensor_start(Type) -> call(<<"sensor_start">>, sensor_type(Type)).
sensor_read(Type)  -> call(<<"sensor_read">>,  sensor_type(Type)).
sensor_stop(Type)  -> call(<<"sensor_stop">>,  sensor_type(Type)).

%% ---- Network ----

wifi_info() -> call(<<"wifi_info">>).
network_info() -> call(<<"network_info">>).
location() -> call(<<"location">>).
%% Active GPS fix: waits for a live satellite fix (optional timeout seconds,
%% default 15), falling back to last-known. Includes satellites/age_ms/stale.
%% gps_fix waits up to TimeoutSec for a live fix server-side, so the client
%% gen_server:call timeout must exceed that (default Java timeout is 15s).
gps_fix() -> call(<<"gps_fix">>, <<>>, 20000).
gps_fix(TimeoutSec) when is_integer(TimeoutSec) ->
    call(<<"gps_fix">>, integer_to_binary(TimeoutSec), TimeoutSec * 1000 + 5000).

%% ---- Apps ----

packages() -> call(<<"packages">>).

%% ---- Utility ----

ping() -> call(<<"ping">>).

%% ---- Environment sensing & extras (surfaced from existing bridge commands) ----

%% Operator, network type, signal strength + serving & neighbor cell towers
%% (PhoneAPI.telephonyInfo/0 uses getAllCellInfo) — this is the "cell tower scan".
telephony_info() -> call(<<"telephony_info">>).
%% Visible Wi-Fi APs (BSSID/SSID/level/freq), not just the connected one.
wifi_scan()      -> call(<<"wifi_scan">>).
%% Screen on/interactive — a coarse presence signal.
screen_state()   -> call(<<"screen_state">>).
%% Volume levels / audio routing (NOT mic content).
audio_info()     -> call(<<"audio_info">>).
process_info()   -> call(<<"process_info">>).
camera_info()    -> call(<<"camera_info">>).
volume_get()     -> call(<<"volume_get">>).
media_status()   -> call(<<"media_status">>).

%% Bluetooth scans — both block for the scan duration, so use a client call
%% timeout larger than the scan window (like gps_fix). Nearby devices + RSSI =
%% RF presence/density signal.
bt_scan() -> call(<<"bt_scan">>, <<>>, 12000).            %% classic discovery (~default secs)
bt_scan(Secs) when is_integer(Secs) ->
    call(<<"bt_scan">>, integer_to_binary(Secs), Secs * 1000 + 5000).
bt_scan_ble() -> call(<<"bt_scan_ble">>, <<>>, 12000).    %% BLE scan (default 5s)
bt_scan_ble(Secs) when is_integer(Secs) ->
    call(<<"bt_scan_ble">>, integer_to_binary(Secs), Secs * 1000 + 5000).

%%% ============================================================
%%% gen_server callbacks
%%% ============================================================

init(Port) ->
    self() ! connect,
    {ok, #state{
        host = "127.0.0.1",
        port = Port,
        next_id = 1,
        pending = #{},
        stream_sub = undefined
    }}.

handle_call({cmd, Cmd, Args}, From, #state{socket = undefined} = State) ->
    %% Try to reconnect
    case connect(State) of
        {ok, State2} -> do_send(Cmd, Args, From, State2);
        {error, _} -> {reply, {error, not_connected}, State}
    end;
handle_call({cmd, Cmd, Args}, From, State) ->
    do_send(Cmd, Args, From, State);
handle_call({subscribe_stream, Pid}, _From, State) ->
    {reply, ok, State#state{stream_sub = Pid}};
handle_call(unsubscribe_stream, _From, State) ->
    {reply, ok, State#state{stream_sub = undefined}}.

handle_cast(_Msg, State) -> {noreply, State}.

handle_info(connect, #state{socket = Sock} = State) when Sock =/= undefined ->
    %% Already reconnected via the call path; the timer is a no-op.
    {noreply, State};
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

handle_info({tcp, Sock, Data}, #state{socket = Sock, rxbuf = RxBuf} = State) ->
    %% May receive multiple lines, and a line may span several packets:
    %% process only complete lines, carrying the partial tail in rxbuf.
    Parts = binary:split(<<RxBuf/binary, Data/binary>>, <<"\n">>, [global]),
    Rest = lists:last(Parts),
    Lines = [L || L <- lists:droplast(Parts), L =/= <<>>],
    State2 = lists:foldl(fun handle_response/2, State, Lines),
    {noreply, State2#state{rxbuf = Rest}};

handle_info({tcp_closed, Sock}, #state{socket = Sock} = State) ->
    io:format("[android] Bridge connection closed, reconnecting...~n"),
    State2 = fail_pending(closed, State),
    erlang:send_after(1000, self(), connect),
    {noreply, State2#state{socket = undefined, rxbuf = <<>>}};

handle_info({tcp_error, Sock, Reason}, #state{socket = Sock} = State) ->
    io:format("[android] Bridge TCP error: ~p~n", [Reason]),
    State2 = fail_pending({tcp_error, Reason}, State),
    erlang:send_after(1000, self(), connect),
    {noreply, State2#state{socket = undefined, rxbuf = <<>>}};

handle_info({tcp_error, Sock, _Reason}, State) ->
    %% Error from a stale socket already replaced by a reconnect.
    gen_tcp:close(Sock),
    {noreply, State};

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
    case gen_tcp:connect(Host, Port, [binary, {active, true},
                                       {buffer, 65536}], 2000) of
        {ok, Sock} ->
            send_auth(Sock),
            {ok, State#state{socket = Sock, rxbuf = <<>>}};
        {error, _} = Err -> Err
    end.

%% Bridge auth: send the app-private token as the first line on every
%% connection. The reply (id 0, never pending) is ignored by
%% handle_response. If the token file is unreadable nothing is sent — that
%% only works when the server has auth disabled too.
send_auth(Sock) ->
    case file:read_file(?TOKEN_PATH) of
        {ok, Tok0} ->
            Tok = escape_json(string:trim(Tok0)),
            Line = <<"{\"id\":0,\"cmd\":\"auth\",\"args\":\"", Tok/binary, "\"}\n">>,
            gen_tcp:send(Sock, Line);
        {error, _} ->
            ok
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
            gen_tcp:close(Sock),
            {reply, {error, Reason}, State#state{socket = undefined}}
    end.

handle_response(Line, #state{pending = Pend, stream_sub = Sub} = State) ->
    %% Parse response: {"id":N,"ok":true/false,"data":...} or {"error":...}
    IdStr = json_get(Line, <<"id">>),
    Id = try binary_to_integer(IdStr) catch _:_ -> 0 end,
    case json_get(Line, <<"ok">>) of
        <<>> ->
            %% No "ok" field: a stream event (partial STT result, audio
            %% chunk), not a reply. Forward to the subscriber if there is
            %% one; never consume the pending request — the final reply is
            %% still coming with the same id.
            case Sub of
                undefined -> ok;
                Pid -> Pid ! {android_stream_event, Id, Line}
            end,
            State;
        Ok ->
            case maps:take(Id, Pend) of
                {From, Pend2} ->
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
            end
    end.

%% Reply {error, ...} to every pending caller when the bridge connection
%% drops, instead of letting them sit until their gen_server:call timeout.
fail_pending(Reason, #state{pending = Pend} = State) ->
    maps:foreach(
        fun(_Id, From) ->
            gen_server:reply(From, {error, {bridge_disconnected, Reason}})
        end, Pend),
    State#state{pending = #{}}.

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
extract_raw_value(<<"{", _/binary>> = B) -> extract_balanced(B, ${, $}, 0, false, <<>>);
extract_raw_value(<<"[", _/binary>> = B) -> extract_balanced(B, $[, $], 0, false, <<>>);
extract_raw_value(B) -> extract_token(B, <<>>).

extract_balanced(<<>>, _, _, _, _, Acc) -> Acc;
%% Inside a string: consume escape pairs and content without counting braces
extract_balanced(<<$\\, C, T/binary>>, Open, Close, D, true, Acc) ->
    extract_balanced(T, Open, Close, D, true, <<Acc/binary, $\\, C>>);
extract_balanced(<<$", T/binary>>, Open, Close, D, InStr, Acc) ->
    extract_balanced(T, Open, Close, D, not InStr, <<Acc/binary, $">>);
extract_balanced(<<C, T/binary>>, Open, Close, D, true, Acc) ->
    extract_balanced(T, Open, Close, D, true, <<Acc/binary, C>>);
extract_balanced(<<Open, T/binary>>, Open, Close, D, false, Acc) ->
    extract_balanced(T, Open, Close, D+1, false, <<Acc/binary, Open>>);
extract_balanced(<<Close, _/binary>>, _Open, Close, 1, false, Acc) ->
    <<Acc/binary, Close>>;
extract_balanced(<<Close, T/binary>>, Open, Close, D, false, Acc) ->
    extract_balanced(T, Open, Close, D-1, false, <<Acc/binary, Close>>);
extract_balanced(<<C, T/binary>>, Open, Close, D, false, Acc) ->
    extract_balanced(T, Open, Close, D, false, <<Acc/binary, C>>).

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
