#!/bin/sh
set -eu

config_file="/usr/share/nginx/html/profile-runtime-config.js"

js_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

api_base_url=$(js_escape "${PROFILE_API_BASE_URL:-/api}")
frontend_base_path=$(js_escape "${PROFILE_FRONTEND_BASE_PATH:-/}")
account_frontend_url=$(js_escape "${PROFILE_ACCOUNT_FRONTEND_URL:-http://account.localhost:8088}")

cat > "$config_file" <<EOF
window.__PROFILE_CONFIG__ = {
  apiBaseUrl: "$api_base_url",
  frontendBasePath: "$frontend_base_path",
  accountFrontendUrl: "$account_frontend_url"
};
EOF
