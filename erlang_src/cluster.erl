%%% @doc Cluster introspection and remote execution helpers.
%%%
%%% Provides convenient wrappers for querying the distributed BEAM cluster
%%% from the phone node, avoiding the quoting/escaping nightmare of beam_eval.
%%%
%%% Usage:
%%%   cluster:nodes_info().
%%%   cluster:apps('sdr_node@10.42.42.104').
%%%   cluster:eval('sdr_node@10.42.42.104', <<"1 + 1">>).
%%%   cluster:sql('sdr_node@10.42.42.104', <<"/home/debian/sdr/priv/sdr.db">>,
%%%               <<"SELECT * FROM aircraft LIMIT 5">>).
%%%   cluster:sdr_recent(10).
%%%   cluster:sdr_aircraft().
-module(cluster).
-export([
    nodes_info/0,
    apps/1,
    eval/2,
    sql/3,
    rpc/4, rpc/5,
    sdr_recent/1,
    sdr_aircraft/0, sdr_aircraft/1,
    sdr_count/0
]).

-define(RPC_TIMEOUT, 15000).
-define(SDR_NODE, 'sdr_node@10.42.42.104').
-define(SDR_DB, <<"/home/debian/sdr/priv/sdr.db">>).

%%% ================================================================
%%% Cluster overview
%%% ================================================================

%% @doc Summary of all connected nodes: name, OTP version, app count, app names.
-spec nodes_info() -> [map()].
nodes_info() ->
    Self = node(),
    AllNodes = [Self | nodes()],
    lists:map(fun(N) ->
        OTP = safe_rpc(N, erlang, system_info, [otp_release]),
        Apps = safe_rpc(N, application, which_applications, []),
        AppNames = case Apps of
            L when is_list(L) -> [element(1, A) || A <- L,
                                  not lists:member(element(1, A),
                                      [kernel, stdlib, compiler, elixir, mix,
                                       logger, hex, crypto, ssl, public_key,
                                       asn1, inets, sasl, syntax_tools])];
            _ -> []
        end,
        #{node => N,
          otp => to_bin(OTP),
          app_count => length(AppNames),
          apps => [atom_to_binary(A) || A <- AppNames]}
    end, AllNodes).

%% @doc List running applications on a remote node (filtered, no OTP boilerplate).
-spec apps(node()) -> {ok, [map()]} | {error, term()}.
apps(Node) ->
    case safe_rpc(Node, application, which_applications, []) of
        L when is_list(L) ->
            {ok, [#{name => atom_to_binary(Name),
                     desc => to_bin(Desc),
                     vsn  => to_bin(Vsn)}
                   || {Name, Desc, Vsn} <- L]};
        Err -> {error, Err}
    end.

%%% ================================================================
%%% Remote execution
%%% ================================================================

%% @doc Evaluate an Elixir expression on a remote node. Returns the result term.
-spec eval(node(), binary()) -> {ok, term()} | {error, term()}.
eval(Node, Code) when is_binary(Code) ->
    case safe_rpc(Node, 'Elixir.Code', eval_string, [Code]) of
        {Result, _Bindings} -> {ok, Result};
        {badrpc, Reason} -> {error, {badrpc, Reason}};
        Other -> {ok, Other}
    end.

%% @doc Run a SQL query against a SQLite database on a remote node.
%% Returns {ok, Columns, Rows} or {error, Reason}.
-spec sql(node(), binary(), binary()) -> {ok, [binary()], list()} | {error, term()}.
sql(Node, DbPath, SQL) ->
    %% Build Elixir code that opens the DB, runs the query, and extracts rows+columns.
    %% We do this as a Code.eval_string because Exqlite connection refs can't cross nodes.
    Code = <<"
        {:ok, conn} = Exqlite.Basic.open(\"", DbPath/binary, "\")
        {ok, _query, result, _conn} = Exqlite.Basic.exec(conn, \"", SQL/binary, "\")
        Exqlite.Basic.close(conn)
        {result.columns, result.rows}
    ">>,
    case safe_rpc(Node, 'Elixir.Code', eval_string, [Code]) of
        {{Cols, Rows}, _Bindings} -> {ok, Cols, Rows};
        {badrpc, Reason} -> {error, {badrpc, Reason}};
        Other -> {error, Other}
    end.

%% @doc Generic RPC with default timeout.
-spec rpc(node(), atom(), atom(), list()) -> term().
rpc(Node, Mod, Fun, Args) ->
    rpc(Node, Mod, Fun, Args, ?RPC_TIMEOUT).

%% @doc Generic RPC with custom timeout.
-spec rpc(node(), atom(), atom(), list(), pos_integer()) -> term().
rpc(Node, Mod, Fun, Args, Timeout) ->
    rpc:call(Node, Mod, Fun, Args, Timeout).

%%% ================================================================
%%% SDR-specific helpers
%%% ================================================================

%% @doc Last N ADS-B observations from the SDR node.
-spec sdr_recent(pos_integer()) -> {ok, [map()]} | {error, term()}.
sdr_recent(Limit) ->
    SQL = <<"SELECT o.icao, o.callsign, o.altitude, o.ground_speed, o.track, "
            "o.lat, o.lon, o.vertical_rate, o.squawk, o.on_ground, o.observed_at, "
            "a.registration, a.type, a.owner "
            "FROM observations o LEFT JOIN aircraft a ON o.icao = a.icao "
            "ORDER BY o.id DESC LIMIT ",
            (integer_to_binary(Limit))/binary>>,
    case sql(?SDR_NODE, ?SDR_DB, SQL) of
        {ok, Cols, Rows} -> {ok, rows_to_maps(Cols, Rows)};
        Err -> Err
    end.

%% @doc All enriched aircraft from the SDR node, most recently seen first.
-spec sdr_aircraft() -> {ok, [map()]} | {error, term()}.
sdr_aircraft() -> sdr_aircraft(50).

-spec sdr_aircraft(pos_integer()) -> {ok, [map()]} | {error, term()}.
sdr_aircraft(Limit) ->
    SQL = <<"SELECT icao, callsign, registration, type, icao_type_code, "
            "manufacturer, owner, first_seen, last_seen, observation_count "
            "FROM aircraft ORDER BY last_seen DESC LIMIT ",
            (integer_to_binary(Limit))/binary>>,
    case sql(?SDR_NODE, ?SDR_DB, SQL) of
        {ok, Cols, Rows} -> {ok, rows_to_maps(Cols, Rows)};
        Err -> Err
    end.

%% @doc Total observation count from the SDR node.
-spec sdr_count() -> {ok, integer()} | {error, term()}.
sdr_count() ->
    case sql(?SDR_NODE, ?SDR_DB, <<"SELECT COUNT(*) as cnt FROM observations">>) of
        {ok, _, [[Count]]} -> {ok, Count};
        Err -> Err
    end.

%%% ================================================================
%%% Internals
%%% ================================================================

safe_rpc(Node, Mod, Fun, Args) ->
    case rpc:call(Node, Mod, Fun, Args, ?RPC_TIMEOUT) of
        {badrpc, Reason} -> {badrpc, Reason};
        Result -> Result
    end.

rows_to_maps(Columns, Rows) ->
    [maps:from_list(lists:zip(Columns, Row)) || Row <- Rows].

to_bin(A) when is_atom(A) -> atom_to_binary(A);
to_bin(L) when is_list(L) -> list_to_binary(L);
to_bin(B) when is_binary(B) -> B;
to_bin(I) when is_integer(I) -> integer_to_binary(I);
to_bin(T) -> list_to_binary(io_lib:format("~p", [T])).
