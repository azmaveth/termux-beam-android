#!/bin/bash
#
# deploy-to-phone.sh — Deploy TLS certs to the Android BeamApp
#
# Usage: ./deploy-to-phone.sh <phone-wg-ip> [cert-name]
#
# Examples:
#   ./deploy-to-phone.sh 10.0.0.5
#   ./deploy-to-phone.sh 10.0.0.5 phone
#
# This copies ca.pem, cert.pem, key.pem to the phone via scp
# and places them where BeamApp can read them.
#
# Prerequisites:
#   - SSH access to phone (via Termux sshd or WireGuard)
#   - Node cert already generated: ./gen-node-cert.sh phone 10.0.0.5
#
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <phone-ip> [cert-name]"
    echo ""
    echo "  phone-ip    Phone's IP (WireGuard or local network)"
    echo "  cert-name   Node cert name (default: phone)"
    exit 1
fi

PHONE_IP="$1"
NODE_NAME="${2:-phone}"
CA_DIR="${CA_DIR:-$(pwd)/beam-ca}"
NODE_DIR="$CA_DIR/nodes/$NODE_NAME"
PHONE_PORT="${PHONE_SSH_PORT:-8022}"

for f in ca.pem cert.pem key.pem; do
    if [ ! -f "$NODE_DIR/$f" ]; then
        echo "Error: $NODE_DIR/$f not found"
        echo "Run: ./gen-node-cert.sh $NODE_NAME $PHONE_IP"
        exit 1
    fi
done

echo "=== Deploying certs to phone at $PHONE_IP ==="

# Create cert directory on phone
ssh -p "$PHONE_PORT" "$PHONE_IP" "mkdir -p ~/.beam-certs && chmod 700 ~/.beam-certs"

# Copy certs
scp -P "$PHONE_PORT" \
    "$NODE_DIR/ca.pem" \
    "$NODE_DIR/cert.pem" \
    "$NODE_DIR/key.pem" \
    "${PHONE_IP}:~/.beam-certs/"

# Set permissions
ssh -p "$PHONE_PORT" "$PHONE_IP" "chmod 600 ~/.beam-certs/key.pem"

echo ""
echo "=== Certs deployed ==="
echo "  Location on phone: ~/.beam-certs/"
echo "  Files: ca.pem, cert.pem, key.pem"
echo ""
echo "The BeamApp will read these from:"
echo "  /data/data/com.termux/files/home/.beam-certs/"
echo ""
echo "After installing the updated APK, the BEAM node will start"
echo "with TLS distribution using these certificates."
