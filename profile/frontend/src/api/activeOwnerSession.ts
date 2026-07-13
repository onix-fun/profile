import { runtimeConfig } from "@/runtime-config";

type OwnerType = "USER" | "ORGANIZATION";

interface ActiveOwnerPreference {
  userId: string;
  ownerType: OwnerType;
  ownerId: string;
  updatedAt: string;
}

interface SessionResponse {
  user?: { id?: string | null } | null;
}

interface OrganizationContext {
  activeOwner?: { ownerType?: string | null; ownerId?: string | null } | null;
  organizations?: Array<{ id: string }>;
}

const STORAGE_KEY = "onix.activeOwner.v1";
let syncRequest: Promise<void> | null = null;
let csrfToken: string | null = null;

export function readActiveOwnerPreference(): ActiveOwnerPreference | null {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(STORAGE_KEY) || "null") as Partial<ActiveOwnerPreference> | null;
    if (!parsed || (parsed.ownerType !== "USER" && parsed.ownerType !== "ORGANIZATION")) return null;
    if (!parsed.userId || !parsed.ownerId || !parsed.updatedAt) return null;
    return {
      userId: parsed.userId,
      ownerType: parsed.ownerType,
      ownerId: parsed.ownerId,
      updatedAt: parsed.updatedAt,
    };
  } catch {
    clearActiveOwnerPreference();
    return null;
  }
}

export function clearActiveOwnerPreference(): void {
  window.localStorage.removeItem(STORAGE_KEY);
}

export async function ensureActiveOwnerSession(): Promise<void> {
  const preference = readActiveOwnerPreference();
  if (!preference) return;
  if (!syncRequest) {
    syncRequest = syncActiveOwnerSession(preference).finally(() => {
      syncRequest = null;
    });
  }
  await syncRequest;
}

async function syncActiveOwnerSession(preference: ActiveOwnerPreference): Promise<void> {
  const session = await requestJson<SessionResponse>("/session/me");
  const userId = session?.user?.id;
  if (!userId) return;
  if (userId !== preference.userId) {
    clearActiveOwnerPreference();
    return;
  }

  const context = await requestJson<OrganizationContext>("/organizations/context");
  if (!context?.activeOwner) return;
  if (!isPreferenceAllowed(preference, userId, context)) {
    clearActiveOwnerPreference();
    return;
  }
  if (context.activeOwner.ownerType === preference.ownerType && context.activeOwner.ownerId === preference.ownerId) return;

  const response = await fetch(apiUrl("/auth/owner/switch"), {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-CSRF-Token": await getCsrfToken(),
      "X-Onix-Redirect": window.location.href,
    },
    body: JSON.stringify({ ownerType: preference.ownerType, ownerId: preference.ownerId }),
  });
  if (response.status === 401) return;
  if (response.status === 403 || response.status === 404) {
    clearActiveOwnerPreference();
    return;
  }
  if (!response.ok) throw new Error("Unable to synchronize active owner");
}

function isPreferenceAllowed(preference: ActiveOwnerPreference, userId: string, context: OrganizationContext): boolean {
  if (preference.ownerType === "USER") return preference.ownerId === userId;
  return Boolean(context.organizations?.some((organization) => organization.id === preference.ownerId));
}

async function getCsrfToken(): Promise<string> {
  if (csrfToken) return csrfToken;
  const response = await fetch(apiUrl("/auth/csrf"), {
    method: "GET",
    credentials: "include",
    headers: { "X-Onix-Redirect": window.location.href },
  });
  if (!response.ok) throw new Error("Unable to get CSRF token");
  const data = await response.json() as { csrfToken?: string };
  csrfToken = data.csrfToken || "";
  return csrfToken;
}

async function requestJson<T>(path: string): Promise<T | null> {
  const response = await fetch(apiUrl(path), {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "X-Onix-Redirect": window.location.href,
    },
  });
  if (response.status === 401 || response.status === 404) return null;
  if (!response.ok) throw new Error("Unable to synchronize active owner");
  return response.json() as Promise<T>;
}

function apiUrl(path: string): string {
  return `${runtimeConfig.apiBaseUrl}${path}`;
}
