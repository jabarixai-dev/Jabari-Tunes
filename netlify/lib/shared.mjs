// Shared helpers for the Jabari Tunes Netlify functions.
export const UA = 'Mozilla/5.0 (compatible; JabariTunes/1.0; personal music player)';
export const AUDIO_EXT = /\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)$/i;

export const json = (o, s = 200) =>
  new Response(JSON.stringify(o), {
    status: s,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });

export const clean = s =>
  (s || '')
    .replace(/<!\[CDATA\[|\]\]>/g, '')
    .replace(/<[^>]+>/g, '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&#0?39;|&apos;/g, "'").replace(/&amp;/g, '&')
    .trim();

// Block requests to private / local addresses (basic SSRF guard).
export function assertPublic(url) {
  if (!/^https?:$/.test(url.protocol)) throw new Error('Only http(s) links are supported');
  const h = url.hostname.toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal') || h.startsWith('['))
    throw new Error('That address is not allowed');
  const m = h.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (m) {
    const a = +m[1], b = +m[2];
    if (a === 0 || a === 10 || a === 127 || a >= 224 || (a === 169 && b === 254) ||
        (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168))
      throw new Error('That address is not allowed');
  }
}

// fetch that follows redirects manually so every hop is checked.
export async function safeFetch(u, opts = {}) {
  let url = new URL(u);
  for (let i = 0; i < 6; i++) {
    assertPublic(url);
    const r = await fetch(url, {
      ...opts,
      redirect: 'manual',
      headers: { 'user-agent': UA, ...(opts.headers || {}) },
      signal: AbortSignal.timeout(opts.timeout || 8000),
    });
    const loc = r.headers.get('location');
    if (r.status >= 300 && r.status < 400 && loc) { url = new URL(loc, url); continue; }
    r.finalUrl = url;
    return r;
  }
  throw new Error('Too many redirects');
}
