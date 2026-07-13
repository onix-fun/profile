export function accountSettingsUrl(accountFrontendUrl: string, redirectUrl: string): string {
  const normalizedBase = accountFrontendUrl.endsWith("/") ? accountFrontendUrl : `${accountFrontendUrl}/`;
  const url = new URL(normalizedBase);
  url.searchParams.set("redirect", redirectUrl);
  return url.toString();
}
