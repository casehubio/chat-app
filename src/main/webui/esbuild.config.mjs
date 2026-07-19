import { build, context } from "esbuild";
import { readFileSync, writeFileSync, mkdirSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PAGES = resolve(__dirname, "../../../../pages/packages");
const BLOCKS = resolve(__dirname, "../../../../blocks-ui/packages");
const CHANNEL_ACTIVITY = resolve(__dirname, "../../../../blocks-ui/components/channel-activity");

const isWatch = process.argv.includes("--watch");

mkdirSync("dist", { recursive: true });

const html = readFileSync("src/index.html", "utf8").replace('./index.ts', './app.js');
writeFileSync("dist/index.html", html);

const options = {
  entryPoints: ["src/index.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2020",
  minify: !isWatch,
  sourcemap: isWatch,
  alias: {
    "@casehubio/blocks-ui-channel-activity": resolve(CHANNEL_ACTIVITY, "src"),
    "@casehubio/blocks-ui-core": resolve(BLOCKS, "blocks-ui-core"),
    "@casehubio/pages-primitives": resolve(PAGES, "pages-primitives"),
    "@casehubio/pages-ui-tokens": resolve(PAGES, "pages-ui-tokens"),
    "@casehubio/pages-component": resolve(PAGES, "pages-component"),
    "@casehubio/pages-data": resolve(PAGES, "pages-data"),
    "@casehubio/pages-runtime": resolve(PAGES, "pages-runtime"),
    "@casehubio/pages-ui": resolve(PAGES, "pages-ui"),
    "@casehubio/pages-viz": resolve(PAGES, "pages-viz"),
  },
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
