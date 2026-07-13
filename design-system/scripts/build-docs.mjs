import { mkdir, copyFile, writeFile } from "node:fs/promises";

await mkdir("dist", { recursive: true });
await copyFile("src/tokens.css", "dist/tokens.css");
await writeFile("dist/index.html", `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Onix Design System</title>
    <link rel="stylesheet" href="/tokens.css" />
    <style>
      body {
        margin: 0;
        font-family: Inter, ui-sans-serif, system-ui, sans-serif;
        color: var(--onix-color-text);
        background: var(--onix-color-surface-muted);
      }
      main {
        max-width: 860px;
        margin: 0 auto;
        padding: 56px 24px;
      }
      section {
        display: grid;
        gap: 16px;
        border-radius: var(--onix-radius-control);
        padding: 24px;
        background: var(--onix-color-surface);
        box-shadow: var(--onix-shadow-floating);
      }
      h1, p { margin: 0; }
      p { color: var(--onix-color-muted); }
    </style>
  </head>
  <body>
    <main>
      <section>
        <h1>Onix Design System</h1>
        <p>Shared tokens package for Account, Profile, and Content frontends.</p>
      </section>
    </main>
  </body>
</html>
`);
