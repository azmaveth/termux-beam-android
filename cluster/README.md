# Beam Cluster — mTLS Distribution Setup

Secure Erlang node clustering with mutual TLS authentication.

## Quick Start

All commands run on your **homelab** (where the CA key lives).

### 1. Generate the CA (once)

```bash
cd cluster/
./gen-ca.sh
```

Creates `beam-ca/ca.pem` (public) and `beam-ca/ca-key.pem` (keep safe).

### 2. Generate node certificates

```bash
# Phone (over WireGuard)
./gen-node-cert.sh phone 10.0.0.5

# Homelab
./gen-node-cert.sh homelab 10.0.0.1,192.168.1.100 homelab.local

# VPS
./gen-node-cert.sh vps 10.0.0.3,203.0.113.50 vps.example.com
```

### 3. Deploy certs

```bash
# To the phone (via SSH over WireGuard)
./deploy-to-phone.sh 10.0.0.5

# To a VPS
scp beam-ca/nodes/vps/{ca.pem,cert.pem,key.pem} vps:/opt/beam/certs/
```

### 4. Set up non-phone nodes

```bash
# Same cookie on all nodes!
export BEAM_COOKIE='your_secret_cookie'

./setup-node.sh homelab 10.0.0.1 /path/to/certs
./setup-node.sh vps 10.0.0.3 /opt/beam/certs

# Start a node
./node-homelab/start.sh
```

### 5. Phone node

The BeamApp reads certs from `~/.beam-certs/` automatically and starts
with TLS distribution on boot. No manual setup needed after cert deploy.

## Architecture

```
                    WireGuard (10.0.0.0/24)
                  ┌───────────────────────────┐
                  │                           │
  ┌───────────┐   │   ┌───────────┐   ┌───────┴───┐
  │  Phone    │───┼───│ Homelab   │───│   VPS     │
  │ 10.0.0.5  │   │   │ 10.0.0.1  │   │ 10.0.0.3  │
  │ port 9100 │   │   │ port 9100 │   │ port 9100 │
  └───────────┘   │   └───────────┘   └───────────┘
                  │       (CA key)
                  └───────────────────────────┘
                       mTLS over WireGuard
```

## Security

- **CA key** stays on homelab only — never copied to phone or VPS
- **Node keys** are EC P-256 — fast, small, secure
- **mTLS** — both sides verify certificates, only cluster members connect
- **WireGuard** — network-level encryption (defense in depth)
- **Fixed port** (9100) — minimal firewall exposure
- **TLS 1.3** preferred, 1.2 fallback

## Files

| Script | Purpose |
|--------|---------|
| `gen-ca.sh` | Create the CA (once) |
| `gen-node-cert.sh` | Generate a node certificate |
| `setup-node.sh` | Configure a non-Android node |
| `deploy-to-phone.sh` | SCP certs to Android via SSH |
| `inet_tls_dist.conf` | TLS distribution config template |

## Certificate Renewal

```bash
# Remove old cert and regenerate
rm -rf beam-ca/nodes/phone
./gen-node-cert.sh phone 10.0.0.5
./deploy-to-phone.sh 10.0.0.5
# Restart the BeamApp
```
