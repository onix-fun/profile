export interface ProfileRuntimeConfig {
  apiBaseUrl: string;
  frontendBasePath: string;
  accountFrontendUrl: string;
  contentFrontendUrl: string;
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

export const runtimeConfig: ProfileRuntimeConfig = {
  apiBaseUrl: (source.apiBaseUrl || "/api").replace(/\/$/, ""),
  frontendBasePath: normalizePath(source.frontendBasePath || "/", "/"),
  accountFrontendUrl: (source.accountFrontendUrl || "http://account.onix.localhost:8088").replace(/\/$/, ""),
  contentFrontendUrl: (source.contentFrontendUrl || "http://content.onix.localhost:8088").replace(/\/$/, ""),
};
