#!/bin/bash
#
# gen-node-cert.sh — Generate a TLS certificate for a BEAM node
#
# Usage: ./gen-node-cert.sh <node-name> [ips] [dns-names]
#
# Examples:
#   ./gen-node-cert.sh phone 10.0.0.5
#   ./gen-node-cert.sh homelab 10.0.0.1,192.168.1.100 homelab.local
#   ./gen-node-cert.sh vps 10.0.0.3,203.0.113.50 vps.example.com
#
# The node name is used as:
#   - Certificate CN
#   - Output directory name ($CA_DIR/nodes/<name>/)
#   - Erlang node name suggestion: <name>@<ip>
#
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <node-name> [ips] [dns-names]"
    echo ""
    echo "  node-name  Name for this node (e.g., phone, homelab, vps)"
    echo "  ips        Comma-separated IPs for SAN (e.g., 10.0.0.5,192.168.1.1)"
    echo "  dns-names  Comma-separated DNS names for SAN (e.g., homelab.local)"
    exit 1
fi

NODE_NAME="$1"
NODE_IPS="${2:-}"
NODE_DNS="${3:-}"

CA_DIR="${CA_DIR:-$(pwd)/beam-ca}"
CERT_DAYS="${CERT_DAYS:-365}"  # 1 year

if [ ! -f "$CA_DIR/ca-key.pem" ]; then
    echo "Error: CA not found at $CA_DIR"
    echo "Run ./gen-ca.sh first."
    exit 1
fi

NODE_DIR="$CA_DIR/nodes/$NODE_NAME"
if [ -f "$NODE_DIR/cert.pem" ]; then
    echo "Certificate already exists for '$NODE_NAME' at $NODE_DIR"
    echo "To regenerate, remove $NODE_DIR and run again."
    exit 1
fi

mkdir -p "$NODE_DIR"

# Build SAN extension
SAN_ENTRIES=""
if [ -n "$NODE_IPS" ]; then
    IFS=',' read -ra IPS <<< "$NODE_IPS"
    for ip in "${IPS[@]}"; do
        SAN_ENTRIES="${SAN_ENTRIES:+${SAN_ENTRIES},}IP:${ip}"
    done
fi
if [ -n "$NODE_DNS" ]; then
    IFS=',' read -ra NAMES <<< "$NODE_DNS"
    for name in "${NAMES[@]}"; do
        SAN_ENTRIES="${SAN_ENTRIES:+${SAN_ENTRIES},}DNS:${name}"
    done
fi

echo "=== Generating key for node '$NODE_NAME' (EC P-256) ==="
openssl ecparam -genkey -name prime256v1 -noout -out "$NODE_DIR/key.pem"
chmod 600 "$NODE_DIR/key.pem"

echo "=== Creating CSR ==="
openssl req -new \
    -key "$NODE_DIR/key.pem" \
    -out "$NODE_DIR/csr.pem" \
    -subj "/CN=${NODE_NAME}/O=Beam Cluster"

echo "=== Signing certificate (valid ${CERT_DAYS} days) ==="

# Build extension file for SAN
EXT_FILE=$(mktemp)
cat > "$EXT_FILE" <<EXTEOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment,keyAgreement
extendedKeyUsage=serverAuth,clientAuth
EXTEOF

if [ -n "$SAN_ENTRIES" ]; then
    echo "subjectAltName=${SAN_ENTRIES}" >> "$EXT_FILE"
fi

openssl x509 -req \
    -in "$NODE_DIR/csr.pem" \
    -CA "$CA_DIR/ca.pem" \
    -CAkey "$CA_DIR/ca-key.pem" \
    -CAcreateserial \
    -out "$NODE_DIR/cert.pem" \
    -days "$CERT_DAYS" \
    -extfile "$EXT_FILE"

rm -f "$EXT_FILE" "$NODE_DIR/csr.pem"

# Copy CA cert into node dir for easy deployment
cp "$CA_DIR/ca.pem" "$NODE_DIR/ca.pem"

echo ""
echo "=== Node certificate created ==="
echo "  Node:     $NODE_NAME"
echo "  Cert:     $NODE_DIR/cert.pem"
echo "  Key:      $NODE_DIR/key.pem"
echo "  CA cert:  $NODE_DIR/ca.pem"
echo ""
echo "Deploy these 3 files to the node:"
echo "  scp $NODE_DIR/{ca.pem,cert.pem,key.pem} <target>:/path/to/certs/"
echo ""
echo "Erlang node name suggestion: ${NODE_NAME}@<wireguard-ip>"
echo ""
openssl x509 -in "$NODE_DIR/cert.pem" -noout -subject -issuer -dates \
    ${SAN_ENTRIES:+-ext subjectAltName} 2>/dev/null || \
openssl x509 -in "$NODE_DIR/cert.pem" -noout -subject -issuer -dates
