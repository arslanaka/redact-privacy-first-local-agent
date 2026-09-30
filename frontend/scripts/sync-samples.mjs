// Copies the synthetic sample documents into public/samples so the UI can offer one-click demos.
import { cpSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const candidates = [join(here, "..", "..", "samples"), join(here, "..", "samples")];
const source = candidates.find((dir) => existsSync(dir));
const target = join(here, "..", "public", "samples");
if (!source) {
  console.warn("sync-samples: no samples directory found, skipping");
  process.exit(0);
}
mkdirSync(target, { recursive: true });
for (const file of readdirSync(source)) {
  if (/^sample-.*\.(md|txt|pdf)$/.test(file)) cpSync(join(source, file), join(target, file));
}
console.log(`sync-samples: copied samples from ${source}`);
