#!/bin/bash
#
# setup-node.sh — Configure Erlang TLS distribution on a node
#
# Usage: ./setup-node.sh <node-name> <ip> <cert-dir> [dist-port]
#
# Examples:
#   ./setup-node.sh homelab 10.0.0.1 /opt/beam/certs
#   ./setup-node.sh vps 10.0.0.3 /opt/beam/certs 9100
#
# This generates:
#   - vm.args        — BEAM VM flags for TLS distribution
#   - sys.config     — Erlang application config
#   - inet_tls.conf  — TLS distribution options
#   - start.sh       — Script to start the node
#
# Prerequisites:
#   - Erlang/OTP installed
#   - cert.pem, key.pem, ca.pem in <cert-dir>
#
set -euo pipefail

if [ $# -lt 3 ]; then
    echo "Usage: $0 <node-name> <ip> <cert-dir> [dist-port]"
    echo ""
    echo "  node-name   Erlang node short name (e.g., homelab)"
    echo "  ip          IP address for this node (e.g., 10.0.0.1)"
    echo "  cert-dir    Directory containing ca.pem, cert.pem, key.pem"
    echo "  dist-port   Fixed distribution port (default: 9100)"
    exit 1
fi

NODE_NAME="$1"
NODE_IP="$2"
CERT_DIR="$3"
DIST_PORT="${4:-9100}"
COOKIE="${BEAM_COOKIE:-beam_cluster_$(openssl rand -hex 8)}"

# Verify certs exist
for f in ca.pem cert.pem key.pem; do
    if [ ! -f "$CERT_DIR/$f" ]; then
        echo "Error: $CERT_DIR/$f not found"
        echo "Deploy certs first: scp ca.pem cert.pem key.pem $CERT_DIR/"
        exit 1
    fi
done

NODE_DIR="$(pwd)/node-${NODE_NAME}"
mkdir -p "$NODE_DIR"

echo "=== Generating TLS distribution config ==="

# inet_tls.conf — TLS options for distribution
cat > "$NODE_DIR/inet_tls.conf" <<EOF
[{server, [
    {certfile, "${CERT_DIR}/cert.pem"},
    {keyfile, "${CERT_DIR}/key.pem"},
    {cacertfile, "${CERT_DIR}/ca.pem"},
    {verify, verify_peer},
    {fail_if_no_peer_cert, true},
    {secure_renegotiate, true},
    {versions, ['tlsv1.3', 'tlsv1.2']}
]},
{client, [
    {certfile, "${CERT_DIR}/cert.pem"},
    {keyfile, "${CERT_DIR}/key.pem"},
    {cacertfile, "${CERT_DIR}/ca.pem"},
    {verify, verify_peer},
    {secure_renegotiate, true},
    {versions, ['tlsv1.3', 'tlsv1.2']}
]}].
EOF

# sys.config
cat > "$NODE_DIR/sys.config" <<EOF
[{kernel, [
    {inet_dist_listen_min, ${DIST_PORT}},
    {inet_dist_listen_max, ${DIST_PORT}}
]}].
EOF

# vm.args
cat > "$NODE_DIR/vm.args" <<EOF
-name ${NODE_NAME}@${NODE_IP}
-setcookie ${COOKIE}
-proto_dist inet_tls
-ssl_dist_optfile ${NODE_DIR}/inet_tls.conf
-kernel inet_dist_listen_min ${DIST_PORT}
-kernel inet_dist_listen_max ${DIST_PORT}
EOF

# start.sh
cat > "$NODE_DIR/start.sh" <<'STARTEOF'
#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

exec erl \
    -args_file "$DIR/vm.args" \
    -config "$DIR/sys.config" \
    -noshell \
    -eval "
        io:format(\"~n=== Node: ~p ===~n\", [node()]),
        io:format(\"Proto: ~p~n\", [net_kernel:get_net_ticktime()]),
        io:format(\"TLS distribution active.~n\"),
        io:format(\"~nTo connect: net_adm:ping('othernode@ip').~n~n\"),
        ok
    "
STARTEOF
chmod +x "$NODE_DIR/start.sh"

echo ""
echo "=== Node '$NODE_NAME' configured ==="
echo ""
echo "  Directory:   $NODE_DIR/"
echo "  Node name:   ${NODE_NAME}@${NODE_IP}"
echo "  Dist port:   ${DIST_PORT}"
echo "  Cookie:      ${COOKIE}"
echo "  TLS config:  $NODE_DIR/inet_tls.conf"
echo ""
echo "  Start:       $NODE_DIR/start.sh"
echo ""
echo "  Firewall:    allow TCP ${DIST_PORT} from cluster peers"
echo "               allow TCP 4369 (EPMD) from cluster peers"
echo ""
echo "IMPORTANT: Use the same cookie on ALL nodes."
echo "  export BEAM_COOKIE='${COOKIE}'"
echo "  Then run setup-node.sh for each additional node."
echo ""
echo "To connect nodes (from erl shell):"
echo "  net_adm:ping('othernode@otherip')."
echo "  nodes()."
