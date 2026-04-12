%%% @doc Persistent cluster reconnection daemon.
%%%
%%% Monitors for nodedown events and automatically re-pings disconnected nodes.
%%% Designed to survive global's aggressive partition teardowns — when global
%%% disconnects all nodes, this process reconnects them within seconds.
%%%
%%% Usage:
%%%   reconnect:start(['node1@host', 'node2@host']).
%%%   reconnect:start().  %% reads from BEAM_CLUSTER_NODES env var
%%%   reconnect:status().
%%%   reconnect:stop().
-module(reconnect).
-export([start/0, start/1, stop/0, status/0]).

-define(INITIAL_DELAY, 3000).   %% 3 seconds before first retry
-define(RETRY_INTERVAL, 10000). %% 10 seconds between retries
-define(MAX_RETRIES, 0).        %% 0 = infinite

start() ->
    %% Read target nodes from env var (same as BeamService sets)
    case os:getenv("BEAM_CLUSTER_NODES") of
        false -> {error, no_cluster_nodes};
        "" -> {error, no_cluster_nodes};
        Str ->
            Nodes = [list_to_atom(string:trim(S))
                     || S <- string:split(Str, ",", all),
                        string:trim(S) =/= ""],
            start(Nodes)
    end.

start(Nodes) when is_list(Nodes) ->
    case whereis(reconnect_daemon) of
        undefined ->
            Pid = spawn(fun() -> init(Nodes) end),
            register(reconnect_daemon, Pid),
            io:format("[reconnect] Started monitoring ~p nodes~n", [length(Nodes)]),
            {ok, Pid};
        Pid ->
            io:format("[reconnect] Already running at ~p~n", [Pid]),
            {ok, Pid}
    end.

stop() ->
    case whereis(reconnect_daemon) of
        undefined -> ok;
        Pid -> Pid ! stop, ok
    end.

status() ->
    case whereis(reconnect_daemon) of
        undefined -> {error, not_running};
        Pid -> Pid ! {status, self()},
               receive
                   {reconnect_status, S} -> {ok, S}
               after 3000 -> {error, timeout}
               end
    end.

%%% Internal

init(TargetNodes) ->
    net_kernel:monitor_nodes(true, [{node_type, visible}]),
    loop(TargetNodes, #{}, 0).

loop(Targets, Retrying, ReconnectCount) ->
    receive
        {nodedown, Node, _Info} ->
            case lists:member(Node, Targets) of
                true ->
                    io:format("[reconnect] ~s went down, will reconnect~n", [Node]),
                    %% Schedule reconnection
                    erlang:send_after(?INITIAL_DELAY, self(), {reconnect, Node}),
                    loop(Targets, maps:put(Node, 0, Retrying), ReconnectCount);
                false ->
                    %% Not a target node — might be a transitive connection
                    %% Still try to reconnect if we knew about it
                    io:format("[reconnect] ~s went down (non-target)~n", [Node]),
                    loop(Targets, Retrying, ReconnectCount)
            end;

        {nodeup, Node, _Info} ->
            case maps:is_key(Node, Retrying) of
                true ->
                    io:format("[reconnect] ~s reconnected~n", [Node]),
                    loop(Targets, maps:remove(Node, Retrying), ReconnectCount + 1);
                false ->
                    loop(Targets, Retrying, ReconnectCount)
            end;

        {reconnect, Node} ->
            case net_adm:ping(Node) of
                pong ->
                    io:format("[reconnect] ~s reconnected!~n", [Node]),
                    loop(Targets, maps:remove(Node, Retrying), ReconnectCount + 1);
                pang ->
                    Attempt = maps:get(Node, Retrying, 0) + 1,
                    %% Schedule next retry
                    erlang:send_after(?RETRY_INTERVAL, self(), {reconnect, Node}),
                    loop(Targets, maps:put(Node, Attempt, Retrying), ReconnectCount)
            end;

        {status, From} ->
            Connected = nodes(),
            Down = [N || N <- Targets, not lists:member(N, Connected)],
            From ! {reconnect_status, #{
                targets => Targets,
                connected => Connected,
                down => Down,
                retrying => Retrying,
                total_reconnects => ReconnectCount
            }},
            loop(Targets, Retrying, ReconnectCount);

        stop ->
            net_kernel:monitor_nodes(false),
            io:format("[reconnect] Stopped~n"),
            ok
    end.
