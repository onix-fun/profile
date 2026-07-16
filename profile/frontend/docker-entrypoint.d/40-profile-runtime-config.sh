#!/bin/sh
set -eu

config_file="/usr/share/nginx/html/profile-runtime-config.js"
frame_ancestors_file="/etc/nginx/conf.d/profile-frame-ancestors.conf"

js_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

api_base_url=$(js_escape "${PROFILE_API_BASE_URL:-/api}")
frontend_base_path=$(js_escape "${PROFILE_FRONTEND_BASE_PATH:-/}")
account_frontend_url=$(js_escape "${PROFILE_ACCOUNT_FRONTEND_URL:-http://account.onix.localhost:8088}")
content_frontend_url=$(js_escape "${PROFILE_CONTENT_FRONTEND_URL:-http://content.onix.localhost:8088}")
frame_ancestors="${PROFILE_FRAME_ANCESTORS:-'self' ${PROFILE_CONTENT_FRONTEND_URL:-http://content.onix.localhost:8088}}"

cat > "$config_file" <<EOF
window.__PROFILE_CONFIG__ = {
  apiBaseUrl: "$api_base_url",
  frontendBasePath: "$frontend_base_path",
  accountFrontendUrl: "$account_frontend_url",
  contentFrontendUrl: "$content_frontend_url"
};
EOF

cat > "$frame_ancestors_file" <<EOF
add_header Content-Security-Policy "frame-ancestors $frame_ancestors" always;
EOF
