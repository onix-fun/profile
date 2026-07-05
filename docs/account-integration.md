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

## Required Account Internal Visibility API

Content Service treats Account as the trusted source for privacy, follows, blocks, and close-friends membership. The local dev sync applies `dev/account-patches/0002-internal-visibility-api.patch`, which adds:

```http
GET /api/internal/visibility?ownerId=<uuid>&viewerId=<uuid>
Authorization: Bearer <viewer access token>
```

The response includes `ownerId`, `viewerId`, `isPrivate`, `relationship`, `isBlocked`, and `isCloseFriend`. Content uses this response before exposing profile posts or stories to Profile Service.
