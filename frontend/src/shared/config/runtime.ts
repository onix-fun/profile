export interface ProfileRuntimeConfig {
  apiBaseUrl: string;
  frontendBasePath: string;
  accountFrontendUrl: string;
  contentFrontendUrl: string;
  preferenceCookieDomain: string;
  embeddedParentOrigins: string[];
}

declare global {
  interface Window {
    __PROFILE_CONFIG__?: Partial<ProfileRuntimeConfig>;
  }
}

function normalizePath(value: string, fallback: string): string {
  const path = value.trim() || fallback;
  return `/${path.replace(/^\/+|\/+$/g, "")}${path === "/" ? "" : "/"}`.replace("//", "/");
}

const source = window.__PROFILE_CONFIG__ || {};
const accountFrontendUrl = (source.accountFrontendUrl || "http://account.onix.localhost:8088").replace(/\/$/, "");
const contentFrontendUrl = (source.contentFrontendUrl || "http://content.onix.localhost:8088").replace(/\/$/, "");

function inferPreferenceCookieDomain(hostname: string): string {
  if (hostname === "onix.fun" || hostname.endsWith(".onix.fun")) return ".onix.fun";
  if (hostname === "onix.localhost" || hostname.endsWith(".onix.localhost")) return "onix.localhost";
  return "";
}

export const runtimeConfig: ProfileRuntimeConfig = {
  apiBaseUrl: (source.apiBaseUrl || "/api").replace(/\/$/, ""),
  frontendBasePath: normalizePath(source.frontendBasePath || "/", "/"),
  accountFrontendUrl,
  contentFrontendUrl,
  preferenceCookieDomain: source.preferenceCookieDomain?.trim() || inferPreferenceCookieDomain(window.location.hostname),
  embeddedParentOrigins: source.embeddedParentOrigins?.length
    ? source.embeddedParentOrigins.map((value) => new URL(value).origin)
    : [new URL(accountFrontendUrl).origin, new URL(contentFrontendUrl).origin],
};
