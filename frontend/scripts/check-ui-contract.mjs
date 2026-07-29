import { readFile, readdir } from "node:fs/promises";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const sourceRoot = fileURLToPath(new URL("../src/", import.meta.url));
const failures = [];

async function visit(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) { await visit(path); continue; }
    if (![".css", ".ts", ".vue"].includes(extname(path))) continue;
    const source = await readFile(path, "utf8");
    const relative = path.slice(sourceRoot.length);
    if (relative.includes("/test/") || relative.endsWith(".test.ts")) continue;
    const checks = [
      ["application !important", /!important/],
      ["gradient", /gradient\(/i],
      ["background texture", /background-image\s*:/i],
      ["outline declaration", /\boutline(?:-[\w-]+)?\s*:/i],
      ["legacy visual token", /--onix-(?:border|shadow-(?:sm|md|lg))|--onix-color-border/i],
      ["surface depth above two", /data-onix-surface-depth=["'][3-9]/i],
      ["generic CSS variable", /var\(--(?!onix-)/],
      ["raw palette color", /#[\da-f]{3,8}\b|\brgba?\(/i],
      ["arbitrary Tailwind value", /[\w!:/-]+-\[[^\]]+\]/],
      ["text smaller than 12px", /font-size:\s*(?:[0-9]|1[01])px\b/],
      ["external icon class", /\bpi(?:\s+pi-|-[a-z])/],
    ];
    for (const [label, pattern] of checks) if (pattern.test(source)) failures.push(`${relative}: ${label}`);
    for (const match of source.matchAll(/\bborder(?!-radius|-box)[-\w]*\s*:\s*([^;}\n]+)/gi)) {
      if (!/^(?:0|none)$/i.test(match[1].trim())) failures.push(`${relative}: visible border declaration`);
    }
    for (const match of source.matchAll(/box-shadow\s*:\s*([^;}\n]+)/gi)) {
      if (!/^(?:none|var\(--onix-shadow-(?:floating|overlay|drag)\))$/i.test(match[1].trim())) failures.push(`${relative}: unapproved box shadow`);
    }
    for (const [tag] of source.matchAll(/<[^>]*data-onix-surface-depth=["']2["'][^>]*>/gs)) {
      if (!/data-onix-nesting-reason=["'](?:form-group|list-group|editor-inspector)["']/.test(tag)) failures.push(`${relative}: level two surface without an allowed nesting reason`);
    }
    if (/^(features|pages)\//.test(relative) && /from\s+["']primevue\//.test(source)) failures.push(`${relative}: direct PrimeVue import outside shared/ui`);
  }
}

await visit(sourceRoot);
if (failures.length) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Profile UI contract check passed.");
}
