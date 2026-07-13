import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const tokensPath = resolve(root, "tokens/tokens.json");
const distDir = resolve(root, "dist");
const source = JSON.parse(await readFile(tokensPath, "utf8"));

await mkdir(distDir, { recursive: true });

const cssVarNames = {
  bg: "--bg",
  bgGrid: "--bg-grid",
  surface: "--surface",
  surfaceMuted: "--surface-muted",
  surfaceSoft: "--surface-soft",
  surfaceRaised: "--surface-raised",
  surfaceActive: "--surface-active",
  surfaceStrong: "--surface-strong",
  text: "--text",
  muted: "--muted",
  subtle: "--subtle",
  accent: "--accent",
  focusRing: "--focus-ring",
  success: "--success",
  successSoft: "--success-soft",
  info: "--info",
  infoSoft: "--info-soft",
  warning: "--warning",
  warningSoft: "--warning-soft",
  danger: "--danger",
  dangerSoft: "--danger-soft",
  pink: "--pink",
  cyan: "--cyan",
  shadowSm: "--shadow-sm",
  shadow: "--shadow",
  shadowStrong: "--shadow-strong",
  buttonPrimaryBg: "--btn-primary-bg",
  buttonPrimaryText: "--btn-primary-text",
};

function cssDeclarations(values) {
  return Object.entries(cssVarNames)
    .filter(([key]) => values[key] !== undefined)
    .map(([key, variable]) => `  ${variable}: ${values[key]};`)
    .join("\n");
}

const baseTokenCss = `:root {
  font-family: ${source.font.family};
  color: ${source.themes.light.text};
  background: ${source.themes.light.bg};
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color-scheme: light;

${cssDeclarations(source.themes.light)}
  --radius-xs: ${source.radii.xs};
  --radius-sm: ${source.radii.sm};
  --radius-md: ${source.radii.md};
  --radius-lg: ${source.radii.lg};
  --radius-xl: ${source.radii.xl};
  --motion-fast: ${source.motion.fast};
  --motion: ${source.motion.base};
  --toast-success-bg: var(--success-soft);
  --toast-error-bg: var(--danger-soft);
  --danger-section-bg: var(--danger-soft);
}
`;

const darkTokenCss = `:root.dark {
  color-scheme: dark;

${cssDeclarations(source.themes.dark)}
}
`;

const indexCss = `@import "./tokens.css";
@import "./tokens.dark.css";

* {
  box-sizing: border-box;
}

html,
body,
#app {
  width: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  background:
    linear-gradient(var(--bg-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--bg-grid) 1px, transparent 1px),
    var(--bg);
  background-size: 36px 36px;
  margin: 0;
  padding: 0;
  font-size: ${source.font.size.body};
  line-height: ${source.font.lineHeight.body};
  color: var(--text);
}

body {
  overflow-x: hidden;
}

button,
input,
textarea,
select {
  font: inherit;
}

button {
  letter-spacing: 0;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.ui-shell {
  width: min(980px, 100%);
  margin: 0 auto;
  padding: 32px 16px 48px;
  display: grid;
  gap: 20px;
}

.ui-surface {
  background: color-mix(in srgb, var(--surface) 92%, transparent);
  border: 0;
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  color: var(--text);
}

.ui-surface-muted {
  background: var(--surface-muted);
  border-radius: var(--radius-lg);
  box-shadow: none;
}

.ui-surface-danger {
  background: var(--danger-soft);
  border-radius: var(--radius-lg);
  box-shadow: none;
}

.ui-section {
  display: grid;
  gap: 12px;
}

.ui-section-header {
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.ui-section-title {
  margin: 0;
  color: var(--text);
  font-size: ${source.font.size.title};
  font-weight: ${source.font.weight.title};
  line-height: ${source.font.lineHeight.title};
  letter-spacing: 0;
}

.ui-section-caption {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: ${source.font.size.caption};
  font-weight: ${source.font.weight.medium};
  line-height: ${source.font.lineHeight.caption};
}

.ui-list {
  display: grid;
  gap: 2px;
}

.ui-row {
  min-width: 0;
  background: var(--surface);
  border: 0;
  border-radius: var(--radius-md);
  color: var(--text);
  transition: background var(--motion), box-shadow var(--motion), transform var(--motion-fast), color var(--motion);
}

.ui-row-muted {
  background: var(--surface-muted);
}

.ui-row-active {
  background: var(--surface-active);
}

.ui-row-clickable {
  cursor: pointer;
}

.ui-row-clickable:hover {
  background: var(--surface-active);
  transform: translateY(-1px);
}

.ui-empty {
  min-height: 112px;
  display: grid;
  place-items: center;
  padding: 32px;
  border-radius: var(--radius-lg);
  background: var(--surface);
  color: var(--muted);
  text-align: center;
  font-size: 14px;
  font-weight: 560;
}

.ui-icon-tile {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  background: var(--surface-muted);
  color: var(--muted);
}

.ui-icon-tile-sm {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-xs);
}

.ui-icon-info {
  background: var(--info-soft);
  color: var(--info);
}

.ui-icon-success {
  background: var(--success-soft);
  color: var(--success);
}

.ui-icon-warning {
  background: var(--warning-soft);
  color: var(--warning);
}

.ui-icon-danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.ui-icon-pink {
  background: color-mix(in srgb, var(--pink) 14%, transparent);
  color: var(--pink);
}

.ui-icon-cyan {
  background: color-mix(in srgb, var(--cyan) 14%, transparent);
  color: var(--cyan);
}

.ui-field {
  display: grid;
  gap: 7px;
}

.ui-label {
  color: var(--muted);
  font-size: ${source.font.size.label};
  font-weight: ${source.font.weight.bold};
  line-height: ${source.font.lineHeight.label};
}

.ui-caption {
  color: var(--muted);
  font-size: ${source.font.size.caption};
  font-weight: ${source.font.weight.medium};
  line-height: ${source.font.lineHeight.caption};
}

.ui-action-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ui-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 100%;
  min-height: 32px;
  padding: 5px 10px;
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--muted);
  font-size: 13px;
  font-weight: 680;
}

.ui-chip img {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  object-fit: cover;
}

.ui-separator {
  height: 1px;
  background: var(--surface-active);
  opacity: 0.7;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
  }
}

@media (max-width: 640px) {
  .ui-shell {
    padding: 18px 12px 32px;
    gap: 14px;
  }
}
`;

const indexJs = `export const tokens = ${JSON.stringify(
  {
    font: source.font,
    spacing: source.spacing,
    radii: source.radii,
    motion: source.motion,
  },
  null,
  2,
)};

export const themes = ${JSON.stringify(source.themes, null, 2)};

export const semanticColors = {
  success: "var(--success)",
  info: "var(--info)",
  warning: "var(--warning)",
  danger: "var(--danger)",
  pink: "var(--pink)",
  cyan: "var(--cyan)",
};

export const platforms = ${JSON.stringify(source.platforms, null, 2)};

export const spacing = tokens.spacing;
export const typography = tokens.font;
export const radii = tokens.radii;
export const motion = tokens.motion;

export function createClassName(...parts) {
  return parts.filter(Boolean).join(" ");
}

export function getToneVars(tone) {
  const tones = {
    neutral: { color: "var(--muted)", background: "var(--surface-muted)" },
    info: { color: "var(--info)", background: "var(--info-soft)" },
    success: { color: "var(--success)", background: "var(--success-soft)" },
    warning: { color: "var(--warning)", background: "var(--warning-soft)" },
    danger: { color: "var(--danger)", background: "var(--danger-soft)" },
    pink: { color: "var(--pink)", background: "color-mix(in srgb, var(--pink) 14%, transparent)" },
    cyan: { color: "var(--cyan)", background: "color-mix(in srgb, var(--cyan) 14%, transparent)" },
  };
  return tones[tone] || tones.neutral;
}
`;

const indexDts = `export type ThemeName = "light" | "dark";
export type Tone = "neutral" | "info" | "success" | "warning" | "danger" | "pink" | "cyan";

export interface ToneVars {
  color: string;
  background: string;
}

export declare const tokens: {
  font: {
    family: string;
    size: Record<"caption" | "label" | "control" | "body" | "title", string>;
    weight: Record<"regular" | "medium" | "semibold" | "bold" | "strong" | "title", number>;
    lineHeight: Record<"caption" | "label" | "body" | "title", number>;
  };
  spacing: Record<"0" | "1" | "2" | "3" | "4" | "5" | "6" | "8" | "10" | "12", string>;
  radii: Record<"xs" | "sm" | "md" | "lg" | "xl" | "pill", string>;
  motion: Record<"fast" | "base", string>;
};

export declare const themes: Record<ThemeName, Record<string, string>>;
export declare const semanticColors: Record<string, string>;
export declare const platforms: Record<string, { label: string; color: string }>;
export declare const spacing: typeof tokens.spacing;
export declare const typography: typeof tokens.font;
export declare const radii: typeof tokens.radii;
export declare const motion: typeof tokens.motion;
export declare function createClassName(...parts: Array<string | false | null | undefined>): string;
export declare function getToneVars(tone: Tone): ToneVars;
`;

const docsHtml = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Onix Design System</title>
    <link rel="stylesheet" href="/index.css" />
    <style>
      main {
        width: min(920px, calc(100vw - 32px));
        margin: 0 auto;
        padding: 48px 0;
      }

      .swatches {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 12px;
        margin-top: 24px;
      }

      .swatch {
        min-height: 96px;
        border: 1px solid var(--surface-strong);
        border-radius: var(--radius-sm);
        background: var(--surface);
        padding: 14px;
      }

      .chip {
        display: inline-flex;
        width: 100%;
        height: 32px;
        border-radius: var(--radius-xs);
        margin-bottom: 10px;
      }
    </style>
  </head>
  <body>
    <main>
      <h1>Onix Design System</h1>
      <p>Shared tokens, CSS primitives, and package exports for Account, Profile, and Content frontends.</p>
      <section class="swatches">
        <article class="swatch"><span class="chip" style="background: var(--text)"></span><strong>Text</strong></article>
        <article class="swatch"><span class="chip" style="background: var(--surface-muted)"></span><strong>Surface muted</strong></article>
        <article class="swatch"><span class="chip" style="background: var(--info)"></span><strong>Info</strong></article>
        <article class="swatch"><span class="chip" style="background: var(--success)"></span><strong>Success</strong></article>
        <article class="swatch"><span class="chip" style="background: var(--warning)"></span><strong>Warning</strong></article>
        <article class="swatch"><span class="chip" style="background: var(--danger)"></span><strong>Danger</strong></article>
      </section>
    </main>
  </body>
</html>
`;

await Promise.all([
  writeFile(resolve(distDir, "tokens.css"), baseTokenCss),
  writeFile(resolve(distDir, "tokens.dark.css"), darkTokenCss),
  writeFile(resolve(distDir, "index.css"), indexCss),
  writeFile(resolve(distDir, "index.js"), indexJs),
  writeFile(resolve(distDir, "index.d.ts"), indexDts),
  writeFile(resolve(distDir, "index.html"), docsHtml),
]);
