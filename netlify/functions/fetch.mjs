// Streams an audio file through Netlify so the app can save it even when the
// source site does not allow direct browser downloads (CORS).
import { json, safeFetch, AUDIO_EXT } from '../lib/shared.mjs';

const MAX = 19 * 1024 * 1024; // Netlify streamed responses cap at ~20 MB
const OK_TYPE = /^(audio\/|application\/octet-stream|application\/ogg|binary\/|video\/mp4)/i;

export default async req => {
  const target = new URL(req.url).searchParams.get('url');
  try {
    const r = await safeFetch(target, { timeout: 25000 });
    if (!r.ok) return json({ error: `Source answered ${r.status}` }, 502);
    const type = r.headers.get('content-type') || '';
    if (!OK_TYPE.test(type) && !AUDIO_EXT.test(r.finalUrl.pathname))
      return json({ error: 'That link is not an audio file' }, 415);
    const len = +r.headers.get('content-length') || 0;
    if (len > MAX) return json({ error: 'File is over 19 MB, too big for the link service' }, 413);

    let seen = 0;
    const limiter = new TransformStream({
      transform(chunk, ctl) {
        seen += chunk.byteLength;
        if (seen > MAX) ctl.error(new Error('too big'));
        else ctl.enqueue(chunk);
      },
    });
    const headers = { 'content-type': type || 'audio/mpeg', 'cache-control': 'no-store', 'access-control-allow-origin': '*' };
    if (len) headers['content-length'] = String(len);
    return new Response(r.body.pipeThrough(limiter), { headers });
  } catch (e) {
    return json({ error: e.message || 'Could not fetch that file' }, 502);
  }
};

export const config = { path: '/api/fetch' };
