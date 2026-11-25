// Copies the web app into www/ for Capacitor to bundle inside the Android app.
import { cpSync, rmSync, mkdirSync } from 'node:fs';
rmSync('www', { recursive: true, force: true });
mkdirSync('www');
for (const f of ['index.html', 'manifest.webmanifest', 'icon.svg']) cpSync(f, `www/${f}`);
console.log('www/ ready');
