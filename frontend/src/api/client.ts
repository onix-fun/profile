import axios from "axios";
import { runtimeConfig } from "@/runtime-config";
import { redirectToAccount } from "@/api/authRedirect";

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
      headers: { "X-Profile-Redirect": window.location.href },
    }).then((response) => {
      if (!response.ok) throw new Error("Unable to refresh session");
    }).finally(() => {
      sessionRefreshRequest = null;
    });
  }
  return sessionRefreshRequest;
}

api.interceptors.request.use((config) => {
  config.headers.set("X-Profile-Redirect", window.location.href);
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config as (typeof error.config & { _sessionRetry?: boolean }) | undefined;
    const loginUrl = error.response?.data?.loginUrl;
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
