%%% @doc Store-and-forward sensor buffer for the phone (edge sensing node).
%%%
%%% Samples sensors + environment (light/steps/motion, location, cell towers,
%%% visible Wi-Fi) on its own cadence into a local DETS ring buffer, so readings
%%% are NOT lost while this node is disconnected from the cluster (roaming /
%%% WiFi<->cell handoff). The core-side poller drains the buffer over RPC when
%%% connected:
%%%
%%%   {ok, LastSeq, Entries} = erpc:call(Phone, sensor_buffer, fetch_since, [Since]),
%%%   %% ... publish each Entry onto the percept bus on the core ...
%%%   sensor_buffer:ack(LastSeq).   %% prune drained entries
%%%
%%% Monotonic integer Seq keys make draining idempotent and survive restarts
%%% (next Seq is derived from the max key still on disk at init). The buffer is
%%% bounded: beyond `max` entries the oldest are dropped (a long disconnect costs
%%% the tail, never the whole series).
%%%
%%% Design notes live in arbor: .arbor/roadmap/1-brainstorming/
%%% phone-store-and-forward-percepts.md
-module(sensor_buffer).

-export([start/0, start/1, stop/0, status/0, fetch_since/1, ack/1]).

-define(SERVER, sensor_buffer).
-define(DETS, sensor_buffer_dets).
-define(DEFAULT_INTERVAL, 60000).   %% 60s between sample cycles
-define(DEFAULT_MAX, 50000).        %% ring cap; oldest dropped beyond this
-define(CALL_TIMEOUT, 10000).

%% Sources sampled each cycle: {Kind, fun() -> {ok, JsonBin} | {error, _}}.
%% Each non-error reading becomes one buffered entry. Raw JSON is stored as-is;
%% the core parses it when publishing to the percept bus.
sources() ->
    [{light,    fun() -> android:sensor_read(5) end},   %% ambient light (lux)
     {steps,    fun() -> android:sensor_read(19) end},  %% cumulative step counter
     {motion,   fun() -> android:sensor_read(17) end},  %% significant-motion trigger
     {location, fun() -> android:location() end},       %% last-known fix
     {cell,     fun() -> android:telephony_info() end}, %% operator + serving/neighbor towers
     {wifi_aps, fun() -> android:wifi_scan() end},      %% visible APs
     {battery,  fun() -> android:battery() end},        %% level/charging/temp/voltage (device health)
     {network,  fun() -> android:network_info() end},   %% connectivity state over time
     {ble,      fun() -> android:bt_scan_ble(6) end}].  %% nearby BLE devices (6s scan — the heavy one)

%%% ============================================================
%%% Public API
%%% ============================================================

start() -> start(#{}).

start(Opts) when is_map(Opts) ->
    case whereis(?SERVER) of
        undefined ->
            Pid = spawn(fun() -> init(Opts) end),
            register(?SERVER, Pid),
            io:format("[sensor_buffer] started (interval ~p ms)~n",
                      [maps:get(interval, Opts, ?DEFAULT_INTERVAL)]),
            {ok, Pid};
        Pid ->
            io:format("[sensor_buffer] already running at ~p~n", [Pid]),
            {ok, Pid}
    end.

stop() ->
    case whereis(?SERVER) of
        undefined -> ok;
        Pid -> Pid ! stop, ok
    end.

%% Returns {ok, LastSeq, [{Seq, #{kind, ts, json}}]} — entries with Seq > Since,
%% ordered ascending. LastSeq is the highest Seq returned (== Since if empty).
fetch_since(Since) when is_integer(Since) -> call_srv({fetch_since, Since}).

%% Prune all entries with Seq =< UpTo (call after a batch is safely published).
ack(UpTo) when is_integer(UpTo) -> call_srv({ack, UpTo}).

status() -> call_srv(status).

call_srv(Req) ->
    case whereis(?SERVER) of
        undefined -> {error, not_running};
        Pid ->
            Ref = make_ref(),
            Pid ! {Req, self(), Ref},
            receive
                {Ref, Reply} -> Reply
            after ?CALL_TIMEOUT -> {error, timeout}
            end
    end.

%%% ============================================================
%%% Internal
%%% ============================================================

init(Opts) ->
    Interval = maps:get(interval, Opts, ?DEFAULT_INTERVAL),
    Max = maps:get(max, Opts, ?DEFAULT_MAX),
    Path = dets_path(),
    case dets:open_file(?DETS, [{file, Path}, {type, set}, {keypos, 1}]) of
        {ok, _} ->
            Seq0 = max_seq() + 1,
            %% Register the sensors we read (idempotent on the Java side).
            lists:foreach(fun(T) -> catch android:sensor_start(T) end, [5, 19, 17]),
            self() ! sample,
            loop(#{seq => Seq0, interval => Interval, max => Max});
        {error, Reason} ->
            io:format("[sensor_buffer] DETS open failed: ~p (path ~s)~n",
                      [Reason, Path])
            %% process exits; registered name auto-unregisters
    end.

dets_path() ->
    Home = case os:getenv("HOME") of
               false -> "/data/local/tmp";
               H -> H
           end,
    filename:join(Home, "sensor_buffer.dets").

max_seq() ->
    dets:foldl(fun({K, _}, Acc) when is_integer(K) -> erlang:max(K, Acc);
                  (_, Acc) -> Acc
               end, 0, ?DETS).

loop(#{seq := Seq, interval := Interval, max := Max} = St) ->
    receive
        sample ->
            %% Guard the cycle: a transient DETS error must not kill the buffer.
            NewSeq = try sample_all(Seq)
                     catch C:E -> io:format("[sensor_buffer] sample error ~p:~p~n", [C, E]), Seq end,
            (catch prune(Max)),
            erlang:send_after(Interval, self(), sample),
            loop(St#{seq := NewSeq});

        {{fetch_since, Since}, From, Ref} ->
            Reply = try
                        Es = lists:sort(dets:select(?DETS,
                               [{{'$1', '$2'}, [{'>', '$1', Since}], ['$_']}])),
                        Last = case Es of [] -> Since; _ -> element(1, lists:last(Es)) end,
                        {ok, Last, Es}
                    catch C:E -> {error, {C, E}} end,
            From ! {Ref, Reply},
            loop(St);

        {{ack, UpTo}, From, Ref} ->
            Reply = try {ok, dets:select_delete(?DETS,
                          [{{'$1', '_'}, [{'=<', '$1', UpTo}], [true]}])}
                    catch C:E -> {error, {C, E}} end,
            From ! {Ref, Reply},
            loop(St);

        {status, From, Ref} ->
            From ! {Ref, {ok, #{buffered => dets:info(?DETS, size),
                                next_seq => Seq,
                                interval => Interval,
                                max => Max}}},
            loop(St);

        stop ->
            catch dets:close(?DETS),
            io:format("[sensor_buffer] stopped~n"),
            ok;

        _Other ->
            loop(St)
    end.

%% Read every source once; insert each successful reading under a fresh Seq.
sample_all(Seq) ->
    Ts = os:system_time(millisecond),
    lists:foldl(
      fun({Kind, Fun}, S) ->
          case safe_read(Fun) of
              {ok, Json} ->
                  ok = dets:insert(?DETS, {S, #{kind => Kind, ts => Ts, json => Json}}),
                  S + 1;
              skip ->
                  S
          end
      end, Seq, sources()).

safe_read(Fun) ->
    try Fun() of
        {ok, Json} -> {ok, Json};
        _ -> skip
    catch
        _:_ -> skip
    end.

%% Drop the oldest entries when over the cap.
prune(Max) ->
    case dets:info(?DETS, size) of
        Size when is_integer(Size), Size > Max ->
            Keys = lists:sort(dets:select(?DETS, [{{'$1', '_'}, [], ['$1']}])),
            {Old, _} = lists:split(min(Size - Max, length(Keys)), Keys),
            lists:foreach(fun(K) -> dets:delete(?DETS, K) end, Old);
        _ ->
            ok
    end.
