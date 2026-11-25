// Works out what a pasted link is and returns saveable audio files (or an "open in app" fallback).
import { json, clean, safeFetch, AUDIO_EXT } from '../lib/shared.mjs';

const enc = encodeURIComponent;
const prettyName = p => decodeURIComponent(p.split('/').filter(Boolean).pop() || 'track').replace(/\.[a-z0-9]+$/i, '').replace(/[-_]+/g, ' ').trim();
const openOnly = (url, note, title) => ({ results: [{ title: title || prettyName(url.pathname), openUrl: url.href, source: url.hostname.replace(/^www\./, ''), note }] });

async function getJson(u) {
  const r = await safeFetch(u);
  if (!r.ok) throw new Error(`Service answered ${r.status}`);
  return r.json();
}

/* ---------- direct file ---------- */
const direct = (url, size) => ({
  results: [{ title: prettyName(url.pathname), artist: '', url: url.href, size, filename: decodeURIComponent(url.pathname.split('/').pop()), source: url.hostname.replace(/^www\./, '') }],
});

/* ---------- Internet Archive ---------- */
function pickArchive(id, m, max = 50) {
  const meta = m.metadata || {};
  const files = m.files || [];
  const by = ext => files.filter(f => new RegExp('\\.' + ext + '$', 'i').test(f.name));
  let pick = by('mp3');
  if (!pick.length) pick = by('ogg');
  if (!pick.length) pick = by('m4a');
  if (!pick.length) pick = [...by('flac'), ...by('wav')];
  const artist = [].concat(meta.creator || []).join(', ');
  return pick.slice(0, max).map(f => ({
    title: f.title || prettyName(f.name),
    artist: f.artist || f.creator || artist,
    url: `https://archive.org/download/${enc(id)}/${f.name.split('/').map(enc).join('/')}`,
    size: +f.size || 0,
    filename: f.name.split('/').pop(),
    source: 'Internet Archive',
    note: meta.licenseurl ? 'License: ' + meta.licenseurl : '',
  }));
}
async function archive(url) {
  const parts = url.pathname.split('/').filter(Boolean);
  const id = parts[1];
  if (parts[0] !== 'details' || !id) throw new Error('Use an Internet Archive item link (archive.org/details/…)');
  const m = await getJson(`https://archive.org/metadata/${enc(id)}`);
  if (!m.files) throw new Error('Internet Archive item not found');
  let items = pickArchive(id, m);
  if (parts[2]) items = items.filter(i => i.filename === decodeURIComponent(parts[2]));
  if (!items.length) throw new Error('No audio files found in that item');
  return { results: items };
}

/* ---------- Jamendo ---------- */
const jamendoMap = (x, artist) => ({
  title: x.name, artist: artist || x.artist_name, url: x.audiodownload, filename: `${x.name}.mp3`, source: 'Jamendo', note: 'Creative Commons',
});
async function jamendo(url) {
  const cid = process.env.JAMENDO_CLIENT_ID;
  if (!cid) return { results: [], error: 'Jamendo needs a free client ID. Add JAMENDO_CLIENT_ID in Netlify under Site configuration → Environment variables (get one at devportal.jamendo.com), then redeploy.' };
  const t = url.pathname.match(/\/track\/(\d+)/), a = url.pathname.match(/\/album\/(\d+)/);
  const base = `https://api.jamendo.com/v3.0`;
  let list = [];
  if (t) list = (await getJson(`${base}/tracks/?client_id=${cid}&format=json&id=${t[1]}&audiodlformat=mp32`)).results || [];
  else if (a) {
    const al = ((await getJson(`${base}/albums/tracks/?client_id=${cid}&format=json&id=${a[1]}&audiodlformat=mp32`)).results || [])[0];
    list = (al?.tracks || []).map(x => ({ ...x, artist_name: al.artist_name }));
  } else throw new Error('Use a Jamendo track or album link');
  const ok = list.filter(x => x.audiodownload_allowed && x.audiodownload);
  if (!ok.length) return openOnly(url, 'The artist has not enabled downloads for this one. Open it on Jamendo.');
  return { results: ok.map(x => jamendoMap(x)) };
}

/* ---------- SoundCloud (official API, only tracks the artist made downloadable) ---------- */
async function soundcloud(url) {
  const cid = process.env.SOUNDCLOUD_CLIENT_ID;
  if (!cid) return openOnly(url, 'SoundCloud downloads need a SoundCloud API client ID (SOUNDCLOUD_CLIENT_ID in Netlify). Open it on SoundCloud for now.');
  const j = await getJson(`https://api.soundcloud.com/resolve?url=${enc(url.href)}&client_id=${cid}`);
  const list = j.kind === 'playlist' ? j.tracks || [] : [j];
  const ok = list.filter(x => x.downloadable && x.download_url);
  if (!ok.length) return openOnly(url, 'The artist has not enabled downloads for this one. Open it on SoundCloud.', j.title);
  return { results: ok.map(x => ({ title: x.title, artist: x.user?.username || '', url: `${x.download_url}${x.download_url.includes('?') ? '&' : '?'}client_id=${cid}`, filename: `${x.title}.mp3`, source: 'SoundCloud' })) };
}

/* ---------- Spotify / Apple Music / YouTube / Deezer: find a legal downloadable match ---------- */
async function titleFromLink(url, host) {
  try {
    if (host === 'open.spotify.com') { const j = await getJson(`https://open.spotify.com/oembed?url=${enc(url.href)}`); return { title: clean(j.title), artist: '' }; }
    if (host.includes('youtube') || host === 'youtu.be') { const j = await getJson(`https://www.youtube.com/oembed?format=json&url=${enc(url.href)}`); return { title: clean(j.title), artist: clean(j.author_name).replace(/ - Topic$/, '') }; }
  } catch (_) {}
  const r = await safeFetch(url.href);
  const html = await r.text();
  const og = html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)/i) || html.match(/<title>([^<]+)/i);
  return { title: clean(og ? og[1] : ''), artist: '' };
}
async function findLegal(url, host) {
  const info = await titleFromLink(url, host);
  const q = [info.title, info.artist].filter(Boolean).join(' ').replace(/\(.*?\)|\[.*?\]|official.*|video.*|lyrics?.*|\|.*/gi, '').trim();
  const results = [];
  if (q) {
    const s = await getJson(`https://archive.org/advancedsearch.php?q=${enc(`title:(${q}) AND mediatype:audio`)}&fl[]=identifier&rows=3&output=json`).catch(() => null);
    for (const d of s?.response?.docs || []) {
      const m = await getJson(`https://archive.org/metadata/${enc(d.identifier)}`).catch(() => null);
      if (m?.files) pickArchive(d.identifier, m, 1).forEach(i => results.push({ ...i, note: 'Possible match from the Internet Archive. Check it is the right song. ' + i.note }));
    }
    const cid = process.env.JAMENDO_CLIENT_ID;
    if (cid) {
      const j = await getJson(`https://api.jamendo.com/v3.0/tracks/?client_id=${cid}&format=json&limit=3&search=${enc(q)}&audiodlformat=mp32`).catch(() => null);
      (j?.results || []).filter(x => x.audiodownload_allowed && x.audiodownload).forEach(x => results.push({ ...jamendoMap(x), note: 'Possible match on Jamendo (Creative Commons)' }));
    }
  }
  results.push({ title: info.title || prettyName(url.pathname), artist: info.artist, openUrl: url.href, source: host, note: 'Streaming-only service. Downloads are not possible from here, so open it in the original app.' });
  return { results };
}

/* ---------- podcast feeds / web pages ---------- */
function podcast(xml) {
  const channel = clean((xml.match(/<channel[^>]*>[\s\S]*?<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1]);
  return xml.split(/<item[\s>]/i).slice(1, 41).map(it => {
    const enclosure = it.match(/<enclosure[^>]*>/i)?.[0] || '';
    const u = (enclosure.match(/url=["']([^"']+)/i) || [])[1];
    if (!u) return null;
    return {
      title: clean((it.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1]) || 'Episode',
      artist: channel, url: clean(u), size: +(enclosure.match(/length=["'](\d+)/i) || [])[1] || 0,
      filename: decodeURIComponent(new URL(clean(u)).pathname.split('/').pop() || 'episode.mp3'), source: 'Podcast feed',
    };
  }).filter(Boolean);
}
async function generic(url) {
  const r = await safeFetch(url.href);
  if (!r.ok) throw new Error(`That link answered ${r.status}`);
  const type = r.headers.get('content-type') || '';
  if (/^audio\//i.test(type) || type.includes('octet-stream')) return direct(r.finalUrl, +r.headers.get('content-length') || 0);
  const body = await r.text();
  if (/<rss|<feed|<channel/i.test(body.slice(0, 2000))) {
    const eps = podcast(body);
    if (!eps.length) throw new Error('No audio episodes found in that feed');
    return { results: eps };
  }
  const found = new Set();
  for (const m of body.matchAll(/(?:og:audio(?::secure_url)?["'][^>]*content=|<(?:audio|source)[^>]+src=|href=)["']([^"']+\.(?:mp3|m4a|ogg|opus|wav|flac)(?:\?[^"']*)?)["']/gi))
    try { found.add(new URL(m[1], r.finalUrl).href); } catch (_) {}
  if (!found.size) throw new Error('I could not find a downloadable audio file at that link');
  return { results: [...found].slice(0, 30).map(u => direct(new URL(u), 0).results[0]) };
}

const STREAMING = /(^|\.)(spotify\.com|spotify\.link|music\.apple\.com|youtube\.com|youtu\.be|deezer\.com|tidal\.com|music\.amazon\.com)$/;

export default async req => {
  const raw = new URL(req.url).searchParams.get('url');
  let url;
  try { url = new URL(raw); } catch { return json({ error: 'That does not look like a link' }, 400); }
  const host = url.hostname.toLowerCase().replace(/^www\./, '');
  try {
    if (AUDIO_EXT.test(url.pathname)) return json(direct(url, 0));
    if (host === 'archive.org') return json(await archive(url));
    if (host.endsWith('jamendo.com')) return json(await jamendo(url));
    if (host === 'soundcloud.com' || host === 'on.soundcloud.com' || host === 'm.soundcloud.com') return json(await soundcloud(url));
    if (host.endsWith('bandcamp.com')) return json(openOnly(url, 'Bandcamp downloads happen on Bandcamp (buy or "name your price"). Open it there, then add the file from your phone.'));
    if (host === 'audiomack.com') return json(openOnly(url, 'Audiomack only allows downloads inside its own app for tracks the artist enabled. Download it there, then add the file from your phone.'));
    if (STREAMING.test(host)) return json(await findLegal(url, host));
    return json(await generic(url));
  } catch (e) {
    return json({ error: e.message || 'Something went wrong with that link' }, 200);
  }
};

export const config = { path: '/api/resolve' };
