import {
  applyPreferences,
  createPreferenceController,
  isPreferenceMessage,
  readPreferences,
  type OnixLocale,
  type OnixPreferences,
} from "@onix/design-system";
import { runtimeConfig } from "@/shared/config/runtime";

const browserLocale: OnixLocale = navigator.language.toLowerCase().startsWith("ru") ? "ru" : "en";
const options = {
  domain: runtimeConfig.preferenceCookieDomain || undefined,
  defaultTheme: "system" as const,
  defaultLocale: browserLocale,
};

export const preferenceController = createPreferenceController(options);

export function getPreferences(): OnixPreferences {
  return readPreferences(options);
}

export function applyPreferenceMessage(event: MessageEvent, expectedOrigin: string | null): boolean {
  if (!expectedOrigin || event.origin !== expectedOrigin || event.source !== window.parent || !isPreferenceMessage(event.data)) return false;
  applyPreferences(event.data);
  return true;
}
