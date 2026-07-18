import axios from "axios";
import { ensureActiveOwnerSession } from "@/shared/api/activeOwnerSession";
import { runtimeConfig } from "@/shared/config/runtime";
import { redirectToAccount } from "@/shared/api/authRedirect";

export const api = axios.create({
  baseURL: runtimeConfig.apiBaseUrl,
  timeout: 9000,
  withCredentials: true,
});

let sessionRefreshRequest: Promise<void> | null = null;

export async function refreshBrowserSession(): Promise<void> {
  if (!sessionRefreshRequest) {
    sessionRefreshRequest = fetch(`${runtimeConfig.apiBaseUrl}/auth/refresh`, {
      method: "POST",
      credentials: "include",
      headers: { "X-Onix-Redirect": window.location.href },
    }).then((response) => {
      if (!response.ok) throw new Error("Unable to refresh session");
    }).finally(() => {
      sessionRefreshRequest = null;
    });
  }
  return sessionRefreshRequest;
}

api.interceptors.request.use(async (config) => {
  config.headers.set("X-Onix-Redirect", window.location.href);
  if (!shouldSkipActiveOwnerSync(config.url)) await ensureActiveOwnerSession();
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config as (typeof error.config & { _sessionRetry?: boolean }) | undefined;
    const loginUrl = error.response?.data?.loginUrl;
    if (error.response?.status === 401 && isOptionalAuthRequest(config)) {
      return Promise.reject(error);
    }
    if (error.response?.status === 401 && typeof loginUrl === "string") {
      if (config && !config._sessionRetry) {
        config._sessionRetry = true;
        try {
          await refreshBrowserSession();
          return api.request(config);
        } catch {
          // Fall through to Account redirect.
        }
      }
      window.location.assign(loginUrl);
      return new Promise(() => undefined);
    }
    if (error.response?.status === 401) {
      redirectToAccount();
      return new Promise(() => undefined);
    }
    return Promise.reject(error);
  },
);

export function apiErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    return error.response?.data?.message || error.response?.data?.code || error.message;
  }
  return "Unexpected error";
}

function shouldSkipActiveOwnerSync(url?: string): boolean {
  if (!url) return false;
  return url.startsWith("/auth/")
    || url === "/session/me"
    || url.startsWith("/organizations/context");
}

function isOptionalAuthRequest(config?: { headers?: unknown } | null): boolean {
  const headers = config?.headers as { get?: (name: string) => unknown; [key: string]: unknown } | undefined;
  return headers?.get?.("X-Onix-Optional-Auth") === "1" || headers?.["X-Onix-Optional-Auth"] === "1";
}
