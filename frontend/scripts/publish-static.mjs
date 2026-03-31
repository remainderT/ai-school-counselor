import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const frontendRoot = resolve(__dirname, "..");
const distDir = resolve(frontendRoot, "dist");
const staticDir = resolve(frontendRoot, "..", "src", "main", "resources", "static");
const distAssets = resolve(distDir, "assets");
const staticAssets = resolve(staticDir, "assets");

if (!existsSync(distDir)) {
  throw new Error("dist 目录不存在，请先执行 npm run build");
}

mkdirSync(staticDir, { recursive: true });

if (existsSync(staticAssets)) {
  rmSync(staticAssets, { recursive: true, force: true });
}

if (existsSync(distAssets)) {
  cpSync(distAssets, staticAssets, { recursive: true });
}

cpSync(resolve(distDir, "index.html"), resolve(staticDir, "index.html"));

console.log("Published frontend dist to Spring static directory:", staticDir);
