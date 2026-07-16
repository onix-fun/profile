import { runtimeConfig } from "@/runtime-config";

function joinUrl(base: string, path: string): string {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${base.replace(/\/$/, "")}${normalizedPath}`;
}

export function profileUrl(path: string, redirectBack = false): string {
  if (isProfilePath(path.split("?")[0])) return path.startsWith("/") ? path : `/${path}`;
  const url = new URL(joinUrl(runtimeConfig.profileFrontendUrl, path));
  if (redirectBack) url.searchParams.set("redirect", window.location.href);
  return url.toString();
}

export function isProfilePath(path: string): boolean {
  return path === "/me" || path === "/search" || path.startsWith("/u/") || path.startsWith("/o/");
}

export function accountSettingsUrl(redirectUrl: string): string {
  const url = new URL(runtimeConfig.accountFrontendUrl.endsWith("/")
    ? runtimeConfig.accountFrontendUrl
    : `${runtimeConfig.accountFrontendUrl}/`);
  url.searchParams.set("redirect", redirectUrl);
  return url.toString();
}
