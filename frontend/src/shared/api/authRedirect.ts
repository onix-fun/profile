import { runtimeConfig } from "@/shared/config/runtime";

export function accountRedirectUrl(currentUrl = window.location.href): string {
  const base = runtimeConfig.accountFrontendUrl.endsWith("/")
    ? runtimeConfig.accountFrontendUrl
    : `${runtimeConfig.accountFrontendUrl}/`;
  return `${base}?redirect=${encodeURIComponent(currentUrl)}`;
}

export function redirectToAccount(currentUrl = window.location.href): void {
  window.location.assign(accountRedirectUrl(currentUrl));
}
