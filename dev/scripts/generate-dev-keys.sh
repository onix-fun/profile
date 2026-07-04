#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
secrets_dir="$script_dir/../secrets"
private_key="$secrets_dir/account-jwt-private.pem"
public_key="$secrets_dir/account-jwt-public.pem"

mkdir -p "$secrets_dir"

if [ -s "$private_key" ] && [ -s "$public_key" ]; then
    echo "Development RSA keys already exist in $secrets_dir"
    exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$private_key"
openssl pkey -in "$private_key" -pubout -out "$public_key"
chmod 600 "$private_key"
chmod 644 "$public_key"

echo "Generated development RSA keys in $secrets_dir"
