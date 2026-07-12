export interface SocialLinkInput {
  label: string;
  url: string;
}

export type SocialPlatform =
  | "telegram"
  | "instagram"
  | "x"
  | "tiktok"
  | "youtube"
  | "github"
  | "linkedin"
  | "email"
  | "phone"
  | "website";

export interface SocialPlatformMeta {
  key: SocialPlatform;
  label: string;
  glyph: string;
  color: string;
}

const platformMeta: Record<SocialPlatform, SocialPlatformMeta> = {
  telegram: { key: "telegram", label: "Telegram", glyph: "TG", color: "#229ed9" },
  instagram: { key: "instagram", label: "Instagram", glyph: "IG", color: "#e94891" },
  x: { key: "x", label: "X", glyph: "X", color: "#17191c" },
  tiktok: { key: "tiktok", label: "TikTok", glyph: "TT", color: "#111820" },
  youtube: { key: "youtube", label: "YouTube", glyph: "YT", color: "#ff0033" },
  github: { key: "github", label: "GitHub", glyph: "GH", color: "#24292f" },
  linkedin: { key: "linkedin", label: "LinkedIn", glyph: "in", color: "#0a66c2" },
  email: { key: "email", label: "Email", glyph: "@", color: "#d88a00" },
  phone: { key: "phone", label: "Phone", glyph: "TEL", color: "#2fa55a" },
  website: { key: "website", label: "Website", glyph: "WWW", color: "#3478f6" },
};

export interface SocialLinkView {
  label: string;
  url: string;
  href: string;
  displayUrl: string;
  meta: SocialPlatformMeta;
}

export function describeSocialLink(link: SocialLinkInput): SocialLinkView {
  const label = link.label.trim();
  const url = link.url.trim();
  const parsed = parseUrl(url);
  const meta = platformMeta[parsed ? detectPlatform(parsed) : "website"];

  return {
    label: label || meta.label,
    url,
    href: parsed ? parsed.href : url,
    displayUrl: parsed ? displayUrl(parsed) : url,
    meta,
  };
}

function parseUrl(value: string): URL | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (url.protocol === "http:" || url.protocol === "https:" || url.protocol === "mailto:" || url.protocol === "tel:") return url;
    return null;
  } catch {
    if (/^[a-z0-9.-]+\.[a-z]{2,}(\/.*)?$/i.test(value)) return new URL(`https://${value}`);
    return null;
  }
}

function detectPlatform(url: URL): SocialPlatform {
  if (url.protocol === "mailto:") return "email";
  if (url.protocol === "tel:") return "phone";

  const host = url.hostname.toLowerCase().replace(/^www\./, "");
  if (host === "t.me" || host.endsWith(".t.me") || host === "telegram.me" || host.endsWith(".telegram.org")) return "telegram";
  if (host === "instagram.com" || host.endsWith(".instagram.com")) return "instagram";
  if (host === "x.com" || host === "twitter.com" || host.endsWith(".twitter.com")) return "x";
  if (host === "tiktok.com" || host.endsWith(".tiktok.com")) return "tiktok";
  if (host === "youtube.com" || host.endsWith(".youtube.com") || host === "youtu.be") return "youtube";
  if (host === "github.com" || host.endsWith(".github.com")) return "github";
  if (host === "linkedin.com" || host.endsWith(".linkedin.com")) return "linkedin";
  return "website";
}

function displayUrl(url: URL): string {
  if (url.protocol === "mailto:") return url.pathname;
  if (url.protocol === "tel:") return url.pathname;
  const host = url.hostname.replace(/^www\./, "");
  const path = url.pathname === "/" ? "" : url.pathname.replace(/\/$/, "");
  return `${host}${path}`;
}
