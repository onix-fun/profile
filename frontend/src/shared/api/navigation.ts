import { runtimeConfig } from "@/shared/config/runtime";

function joinUrl(base: string, path: string): string {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${base.replace(/\/$/, "")}${normalizedPath}`;
}

export function contentUrl(path: string, redirectBack = false): string {
  const url = new URL(joinUrl(runtimeConfig.contentFrontendUrl, path));
  if (redirectBack) url.searchParams.set("redirect", window.location.href);
  return url.toString();
}

export function isContentPath(path: string): boolean {
  return path === "/story/new" || path.startsWith("/p/") || path.startsWith("/story/");
}
