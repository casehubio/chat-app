import { build, context } from "esbuild";
import { readFileSync, writeFileSync, mkdirSync, existsSync, symlinkSync, statSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKGS = resolve(__dirname, ".casehub-packages/packages");
const CHANNEL_ACTIVITY = resolve(__dirname, "../../../../blocks-ui/components/channel-activity");

const isWatch = process.argv.includes("--watch");

mkdirSync("dist", { recursive: true });

const SCOPED_DIR = resolve(__dirname, "node_modules/@casehubio");
mkdirSync(SCOPED_DIR, { recursive: true });
const symlinkPkgs = [
  "pages-primitives", "pages-ui-tokens", "pages-component",
  "pages-data", "pages-runtime", "pages-ui", "pages-viz",
  "pages-ui-components", "pages-table", "pages-iframe-api",
  "mosaic-core",
];
for (const pkg of symlinkPkgs) {
  const target = resolve(PKGS, pkg);
  const link = resolve(SCOPED_DIR, pkg);
  if (!existsSync(link) && existsSync(target)) {
    symlinkSync(target, link, "dir");
  }
}

const html = readFileSync("src/index.html", "utf8").replace('./index.ts', './app.js');
writeFileSync("dist/index.html", html);

function tryResolve(basePath) {
  if (existsSync(basePath) && statSync(basePath).isFile()) return basePath;
  if (existsSync(basePath) && statSync(basePath).isDirectory()) {
    const idx = resolve(basePath, "index.js");
    if (existsSync(idx)) return idx;
  }
  const withJs = basePath + ".js";
  if (existsSync(withJs)) return withJs;
  const withTs = basePath.replace(/\.js$/, ".ts");
  if (withTs !== basePath && existsSync(withTs)) return withTs;
  return null;
}

const casehubResolvePlugin = {
  name: "casehub-resolve",
  setup(b) {
    b.onResolve({ filter: /^@casehubio\// }, (args) => {
      const deepMatch = args.path.match(/^@casehubio\/([^/]+)\/(.+)$/);
      if (deepMatch) {
        const [, pkg, subpath] = deepMatch;
        const pkgDir = resolve(PKGS, pkg);
        const distPath = resolve(pkgDir, "dist", subpath);
        const resolved = tryResolve(distPath);
        if (resolved) return { path: resolved };
        const srcPath = resolve(pkgDir, "src", subpath);
        const resolvedSrc = tryResolve(srcPath);
        if (resolvedSrc) return { path: resolvedSrc };
        return null;
      }
      const bareMatch = args.path.match(/^@casehubio\/([^/]+)$/);
      if (bareMatch) {
        const pkg = bareMatch[1];
        const pkgDir = resolve(PKGS, pkg);
        const distIdx = resolve(pkgDir, "dist", "index.js");
        if (existsSync(distIdx)) return { path: distIdx };
      }
      return null;
    });
  },
};

const options = {
  entryPoints: ["src/index.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2020",
  minify: !isWatch,
  sourcemap: isWatch,
  plugins: [casehubResolvePlugin],
  alias: {
    "@casehubio/mosaic-channel-activity": resolve(CHANNEL_ACTIVITY, "src"),
  },
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
