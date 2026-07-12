# Account Integration

Profile Service depends on Account as the source of users, privacy, sessions, and follow relationships.

## Required Account Settings

When Account and Profile run behind one Caddy proxy on sibling subdomains, Account must set browser cookies for the shared parent domain:

```env
ACCOUNT_HTTP_COOKIE_SECURE=true
ACCOUNT_HTTP_COOKIE_DOMAIN=.onix.fun
ACCOUNT_HTTP_ALLOWED_ORIGINS=https://account.onix.fun,https://profile.onix.fun
```

The Account frontend runtime config must trust profile redirects:

```js
window.__ACCOUNT_CONFIG__ = {
  apiBaseUrl: "/api",
  frontendBasePath: "/",
  trustedRedirectOrigins: ["https://profile.onix.fun"]
};
```

## Required Account Code Patch

Current Account uses `__Host-access_token` when secure cookies are enabled. `__Host-*` cookies cannot include a `Domain` attribute, so they cannot be shared with `profile.onix.fun`.

Patch Account `AuthController.browserCookieName` so `__Host-*` is used only for host-only cookies:

```kotlin
private fun browserCookieName(name: String): String {
    return if (sessionConfig.cookieSecure && sessionConfig.cookieDomain.isNullOrBlank()) "__Host-$name" else name
}
```

With `ACCOUNT_HTTP_COOKIE_DOMAIN=.onix.fun`, Profile Service will receive `access_token` and forward it to Account API as `Authorization: Bearer <token>`.

## Required Account Internal gRPC API

Content and Profile treat Account as the trusted source for identity, privacy, follows, blocks, close-friends membership, and notification preferences. Backend-to-backend Account calls must use Account gRPC, not REST.

Local development uses plaintext gRPC:

```env
ACCOUNT_GRPC_ENABLED=true
ACCOUNT_GRPC_PORT=9097
PROFILE_ACCOUNT_GRPC_URL=account:9097
PROFILE_ACCOUNT_GRPC_TLS=false
CONTENT_ACCOUNT_GRPC_URL=account:9097
CONTENT_ACCOUNT_GRPC_TLS=false
```

Production should enable mTLS with Account server certificate, client CA, and SAN allowlist. User-bound gRPC calls forward the Account access token in `authorization: Bearer <token>` metadata so Account validates the JWT and active session itself.
