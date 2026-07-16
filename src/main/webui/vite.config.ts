import { defineConfig } from 'vite';
import path from 'path';

const PAGES = path.resolve(__dirname, '../../../../pages/packages');
const BLOCKS = path.resolve(__dirname, '../../../../blocks-ui/packages');
const CHANNEL_ACTIVITY = path.resolve(__dirname, '../../../../blocks-ui/components/channel-activity');

export default defineConfig({
  root: 'src',
  resolve: {
    alias: [
      { find: '@casehubio/blocks-ui-channel-activity', replacement: path.resolve(CHANNEL_ACTIVITY, 'src') },
      { find: '@casehubio/pages-component/dist', replacement: path.resolve(PAGES, 'pages-component/src') },
      { find: '@casehubio/pages-data/dist', replacement: path.resolve(PAGES, 'pages-data/src') },
      { find: '@casehubio/pages-ui/dist', replacement: path.resolve(PAGES, 'pages-ui/src') },
      { find: '@casehubio/pages-viz/dist', replacement: path.resolve(PAGES, 'pages-viz/src') },
      { find: '@casehubio/pages-ui-tokens/dist', replacement: path.resolve(PAGES, 'pages-ui-tokens/src') },
      { find: '@casehubio/blocks-ui-core', replacement: path.resolve(BLOCKS, 'blocks-ui-core/src') },
      { find: '@casehubio/pages-ui-tokens', replacement: path.resolve(PAGES, 'pages-ui-tokens/src') },
      { find: '@casehubio/pages-component', replacement: path.resolve(PAGES, 'pages-component/src') },
      { find: '@casehubio/pages-data', replacement: path.resolve(PAGES, 'pages-data/src') },
      { find: '@casehubio/pages-runtime', replacement: path.resolve(PAGES, 'pages-runtime/src') },
      { find: '@casehubio/pages-ui', replacement: path.resolve(PAGES, 'pages-ui/src') },
      { find: '@casehubio/pages-viz', replacement: path.resolve(PAGES, 'pages-viz/src') },
    ],
  },
  esbuild: {
    target: 'es2022',
    tsconfigRaw: {
      compilerOptions: {
        experimentalDecorators: true,
        useDefineForClassFields: false,
      },
    },
  },
});
