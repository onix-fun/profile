export interface ContentRuntimeConfig {
  apiBaseUrl: string;
  graphqlUrl: string;
  subscriptionsUrl: string;
  frontendBasePath: string;
  accountFrontendUrl: string;
  profileFrontendUrl: string;
}

declare global {
  interface Window {
    __CONTENT_CONFIG__?: Partial<ContentRuntimeConfig>;
  }
}

function normalizePath(value: string, fallback: string): string {
  const path = value.trim() || fallback;
  return `/${path.replace(/^\/+|\/+$/g, "")}${path === "/" ? "" : "/"}`.replace("//", "/");
}

const source = window.__CONTENT_CONFIG__ || {};

export const runtimeConfig: ContentRuntimeConfig = {
  apiBaseUrl: (source.apiBaseUrl || "/api").replace(/\/$/, ""),
  graphqlUrl: (source.graphqlUrl || "/graphql").replace(/\/$/, ""),
  subscriptionsUrl: (source.subscriptionsUrl || "/subscriptions").replace(/\/$/, ""),
  frontendBasePath: normalizePath(source.frontendBasePath || "/", "/"),
  accountFrontendUrl: (source.accountFrontendUrl || "http://account.onix.localhost:8088").replace(/\/$/, ""),
  profileFrontendUrl: (source.profileFrontendUrl || "http://profile.onix.localhost:8088").replace(/\/$/, ""),
};
