#!/usr/bin/env bash
# Print the dsh://connect pairing URL for a phone on the same network.
#
#   ./tools/pair-url.sh <lan-ip> <launch-token> [port]
#
# The launch token is the one in the URL that `dsh web` prints when the host starts:
#   http://127.0.0.1:3080/?token=<launch-token>
# (It rotates on every host restart; the paired cookie lasts 30 days.)
set -euo pipefail

IP="${1:?usage: pair-url.sh <lan-ip> <launch-token> [port]}"
TOKEN="${2:?usage: pair-url.sh <lan-ip> <launch-token> [port]}"
PORT="${3:-3091}"

# The app expects url= to be percent-encoded (it is parsed as a URI query param).
enc_ip="${IP//:/%3A}"
url="http%3A%2F%2F${enc_ip}%3A${PORT}"

printf 'dsh://connect?url=%s&token=%s\n' "$url" "$TOKEN"
