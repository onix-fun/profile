#!/bin/sh
set -eu

config_file="/usr/share/nginx/html/content-runtime-config.js"

js_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

api_base_url=$(js_escape "${CONTENT_API_BASE_URL:-/api}")
graphql_url=$(js_escape "${CONTENT_GRAPHQL_URL:-/graphql}")
subscriptions_url=$(js_escape "${CONTENT_SUBSCRIPTIONS_URL:-/subscriptions}")
frontend_base_path=$(js_escape "${CONTENT_FRONTEND_BASE_PATH:-/}")
account_frontend_url=$(js_escape "${CONTENT_ACCOUNT_FRONTEND_URL:-http://account.onix.localhost:8088}")
profile_frontend_url=$(js_escape "${CONTENT_PROFILE_FRONTEND_URL:-http://profile.onix.localhost:8088}")

cat > "$config_file" <<EOF
window.__CONTENT_CONFIG__ = {
  apiBaseUrl: "$api_base_url",
  graphqlUrl: "$graphql_url",
  subscriptionsUrl: "$subscriptions_url",
  frontendBasePath: "$frontend_base_path",
  accountFrontendUrl: "$account_frontend_url",
  profileFrontendUrl: "$profile_frontend_url"
};
EOF
