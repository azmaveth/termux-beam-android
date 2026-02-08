#!/bin/bash
#
# gen-ca.sh — Generate a self-signed CA for Erlang mTLS distribution
#
# Run this ONCE on your homelab. Keep the CA key safe.
# The CA cert (ca.pem) gets copied to every node.
# The CA key (ca-key.pem) stays on this machine only.
#
set -euo pipefail

CA_DIR="${CA_DIR:-$(pwd)/beam-ca}"
CA_DAYS="${CA_DAYS:-3650}"  # 10 years
CA_CN="${CA_CN:-Beam Cluster CA}"

if [ -f "$CA_DIR/ca-key.pem" ]; then
    echo "CA already exists at $CA_DIR"
    echo "  CA cert: $CA_DIR/ca.pem"
    echo "  CA key:  $CA_DIR/ca-key.pem"
    echo ""
    echo "To regenerate, remove $CA_DIR and run again."
    exit 1
fi

mkdir -p "$CA_DIR/nodes"
chmod 700 "$CA_DIR"

echo "=== Generating CA private key (EC P-256) ==="
openssl ecparam -genkey -name prime256v1 -noout -out "$CA_DIR/ca-key.pem"
chmod 600 "$CA_DIR/ca-key.pem"

echo "=== Generating CA certificate (valid ${CA_DAYS} days) ==="
openssl req -new -x509 \
    -key "$CA_DIR/ca-key.pem" \
    -out "$CA_DIR/ca.pem" \
    -days "$CA_DAYS" \
    -subj "/CN=${CA_CN}/O=Beam Cluster" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

echo ""
echo "=== CA created ==="
echo "  CA cert:  $CA_DIR/ca.pem"
echo "  CA key:   $CA_DIR/ca-key.pem  (KEEP THIS SAFE)"
echo "  Node dir: $CA_DIR/nodes/"
echo ""
echo "Next: ./gen-node-cert.sh <node-name> [ip1,ip2,...] [dns1,dns2,...]"
echo ""
openssl x509 -in "$CA_DIR/ca.pem" -noout -subject -dates
