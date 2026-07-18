import type { RouteLocationNormalizedLoaded } from "vue-router";
import type { ProfileNavButton } from "@/shared/api/types";

export interface OnixNavigateMessage {
  type: "onix:navigate";
  serviceKey?: string;
  path?: string;
  url?: string;
}

export interface OnixProfileRouteMessage {
  type: "onix:profile-route";
  path: string;
}

type QueryValue = string | null | Array<string | null> | undefined;

function firstQuery(value: QueryValue): string {
  return Array.isArray(value) ? value[0] || "" : value || "";
}

export function csvQuery(value: QueryValue): string[] {
  const raw = Array.isArray(value) ? value.join(",") : value || "";
  return raw.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean);
}

export function isEmbeddedProfile(route: Pick<RouteLocationNormalizedLoaded, "query">): boolean {
  return firstQuery(route.query.embed) === "1";
}

export function serviceFilter(route: Pick<RouteLocationNormalizedLoaded, "query">): Set<string> {
  return new Set(csvQuery(route.query.from));
}

export function serviceFilterParam(route: Pick<RouteLocationNormalizedLoaded, "query">): string | undefined {
  const values = csvQuery(route.query.from);
  return values.length ? values.join(",") : undefined;
}

export function embedQuery(route: Pick<RouteLocationNormalizedLoaded, "query">): Record<string, string> {
  if (!isEmbeddedProfile(route)) return {};
  const query: Record<string, string> = { embed: "1" };
  const from = serviceFilterParam(route);
  const parentOrigin = firstQuery(route.query.parentOrigin);
  if (from) query.from = from;
  if (parentOrigin) query.parentOrigin = parentOrigin;
  return query;
}

export function withEmbedQuery(route: Pick<RouteLocationNormalizedLoaded, "query">, path: string): string {
  if (!isEmbeddedProfile(route)) return path;
  const url = new URL(path, window.location.origin);
  Object.entries(embedQuery(route)).forEach(([key, value]) => {
    if (!url.searchParams.has(key)) url.searchParams.set(key, value);
  });
  return `${url.pathname}${url.search}${url.hash}`;
}

export function filterProfileNavigation(buttons: ProfileNavButton[], route: Pick<RouteLocationNormalizedLoaded, "query">): ProfileNavButton[] {
  const allowed = serviceFilter(route);
  if (allowed.size === 0) return buttons;
  return buttons.filter((button) => button.serviceKey === "profile" || allowed.has(button.serviceKey));
}

export function postEmbedNavigation(route: Pick<RouteLocationNormalizedLoaded, "query">, message: Omit<OnixNavigateMessage, "type">): boolean {
  if (!isEmbeddedProfile(route) || window.parent === window) return false;
  const parentOrigin = firstQuery(route.query.parentOrigin);
  if (!parentOrigin) return false;
  window.parent.postMessage({ type: "onix:navigate", ...message } satisfies OnixNavigateMessage, parentOrigin);
  return true;
}

export function postProfileRoute(route: Pick<RouteLocationNormalizedLoaded, "query" | "fullPath">): void {
  if (!isEmbeddedProfile(route) || window.parent === window) return;
  const parentOrigin = firstQuery(route.query.parentOrigin);
  if (!parentOrigin) return;
  const url = new URL(route.fullPath, window.location.origin);
  url.searchParams.delete("embed");
  url.searchParams.delete("parentOrigin");
  const path = `${url.pathname}${url.search}${url.hash}`;
  window.parent.postMessage({ type: "onix:profile-route", path } satisfies OnixProfileRouteMessage, parentOrigin);
}
