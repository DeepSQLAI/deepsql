'use strict';

/**
 * Rasterise build/icon.svg to build/icon.png (1024×1024).
 *
 * Run with `npm run icons`. It uses Electron's own Chromium rather than
 * ImageMagick/rsvg so contributors on any OS can regenerate the icon with the
 * dependencies already in this package. electron-builder derives the .icns and
 * .ico it needs from that single PNG at package time.
 */

const fs = require('node:fs');
const path = require('node:path');
const { app, BrowserWindow, nativeImage } = require('electron');

const SIZE = 1024;
const buildDir = path.join(__dirname, '..', 'build');
const svgPath = path.join(buildDir, 'icon.svg');
const pngPath = path.join(buildDir, 'icon.png');

app.disableHardwareAcceleration();

app.whenReady().then(async () => {
  const svg = fs.readFileSync(svgPath, 'utf8');
  const page = `<!doctype html><meta charset="utf-8">
    <style>html,body{margin:0;padding:0;background:transparent}
    svg{display:block;width:${SIZE}px;height:${SIZE}px}</style>${svg}`;

  const window = new BrowserWindow({
    width: SIZE,
    height: SIZE,
    show: false,
    transparent: true,
    frame: false,
    webPreferences: { offscreen: true },
  });

  await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(page)}`);
  const image = await window.webContents.capturePage({
    x: 0,
    y: 0,
    width: SIZE,
    height: SIZE,
  });

  const png = nativeImage.createFromBuffer(image.toPNG()).toPNG();
  fs.writeFileSync(pngPath, png);
  process.stdout.write(`wrote ${pngPath} (${SIZE}x${SIZE}, ${png.length} bytes)\n`);

  window.destroy();
  app.quit();
});
