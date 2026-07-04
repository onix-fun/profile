import axios from "axios";
import { runtimeConfig } from "@/runtime-config";

export const api = axios.create({
  baseURL: runtimeConfig.apiBaseUrl,
  timeout: 9000,
  withCredentials: true,
});

api.interceptors.request.use((config) => {
  config.headers.set("X-Profile-Redirect", window.location.href);
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const loginUrl = error.response?.data?.loginUrl;
    if (error.response?.status === 401 && typeof loginUrl === "string") {
      window.location.assign(loginUrl);
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
