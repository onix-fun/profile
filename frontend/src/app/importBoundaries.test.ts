import { expect, test } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, extname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const extensions = new Set([".ts", ".vue"]);
const imports = /from\s+["'](@\/[^"']+)["']|import\(["'](@\/[^"']+)["']\)/g;
const forbidden: Record<string, RegExp> = {
  shared: /^@\/(features|pages|app)\//,
  features: /^@\/(pages|app)\//,
  pages: /^@\/app\//,
};

function files(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = resolve(directory, entry);
    return statSync(path).isDirectory() ? files(path) : extensions.has(extname(path)) ? [path] : [];
  });
}

test("feature layers only depend inward", () => {
  const violations: string[] = [];
  for (const file of files(sourceRoot)) {
    const layer = relative(sourceRoot, file).split("/")[0];
    const rule = forbidden[layer];
    if (!rule) continue;
    for (const match of readFileSync(file, "utf8").matchAll(imports)) {
      const target = match[1] || match[2];
      if (rule.test(target)) violations.push(`${relative(sourceRoot, file)} -> ${target}`);
    }
  }
  expect(violations).toEqual([]);
});
