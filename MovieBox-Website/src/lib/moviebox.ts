/**
 * MovieBox / AOneRoom signed API client (TypeScript port)
 * Sources: github.com/solo12345689/moviebox-internal-api (moviebox_api/*.py)
 *          + AnyFilm.apk decompiled LocalMovieBoxServer / MovieBoxSignatureInterceptor
 * All requests live-fetch from official mobile BFF clusters. No local data.
 */
import crypto from "crypto";

// ---------- constants ----------
export const GATEWAY_SECRET = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";
const APP_ID = "4U01pxRu278GqCZKY9";
const REGIONS = {
  IN: { timezone: "Asia/Kolkata", lang: "en", acceptLanguage: "en-IN,hi-IN;q=0.9,en;q=0.8" },
};
const CLUSTERS = [
  "https://api.inmoviebox.com",
  "https://apig.inmoviebox.com",
  "https://api5.aoneroom.com",
  "https://api6.aoneroom.com",
];

export const TRAP_PATTERNS = [
  "macdn.aoneroom.com",
  "please_update",
  "update_app",
  "b164fbfb4347792950bdfbfb563d39d9",
  "80da52722ee7de7a4303290af6783424",
  "1c7de0bd3393702d9191801f15f88f8d",
  "9a0461bc39da389663bf3dbb17091d3f",
  "aa348f2541d13ffe",
  "notice.mp4",
];

export function isTrapUrl(u?: string | null): boolean {
  if (!u) return true;
  const l = u.toLowerCase();
  return TRAP_PATTERNS.some((p) => l.includes(p));
}

// ---------- crypto helpers ----------
function md5Hex(data: string | Buffer): string {
  return crypto.createHash("md5").update(data).digest("hex");
}

export function generateClientToken(): string {
  const ts = Date.now().toString();
  return `${ts},${md5Hex(ts.split("").reverse().join(""))}`;
}

function generateTrSignature(method: string, url: string, body: string, timestamp: number): string {
  const parsed = new URL(url);
  const path = parsed.pathname;
  const qPairs: [string, string][] = [];
  parsed.searchParams.forEach((v, k) => qPairs.push([k, v]));
  let resource = path;
  if (qPairs.length) {
    qPairs.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
    resource = `${path}?${qPairs.map(([k, v]) => `${k}=${v}`).join("&")}`;
  }
  const bodyForMd5 = body ? body.slice(0, 102400) : "";
  const bodyMd5 = body ? md5Hex(Buffer.from(bodyForMd5, "utf-8")) : "";
  const contentLength = body ? String(Buffer.byteLength(body, "utf-8")) : "";
  const canonical = [
    method.toUpperCase(),
    "application/json",
    "application/json;charset=UTF-8",
    contentLength,
    String(timestamp),
    bodyMd5,
    resource,
  ].join("\n");
  const key = Buffer.from(GATEWAY_SECRET, "base64");
  const sig = crypto.createHmac("md5", key).update(canonical, "utf-8").digest();
  return `${timestamp}|2|${sig.toString("base64")}`;
}

// ---------- device / identity ----------
const DEVICE_ID =
  "86820305" + Array.from({ length: 7 }, () => Math.floor(Math.random() * 10)).join("");

function clientInfo(): string {
  return JSON.stringify({
    package_name: "com.community.oneroom",
    version_name: "4.0.02",
    version_code: 50020126,
    os: "android",
    os_version: "14",
    install_ch: "ps",
    device_id: DEVICE_ID,
    install_store: "ps",
    gaid: "",
    brand: "Google",
    model: "Pixel 6",
    system_language: "en",
    net: "wifi",
    region: "IN",
    timezone: REGIONS.IN.timezone,
    sp_code: "404",
  });
}

// ---------- session (guest token) ----------
type Session = { token: string; userId: string; fetchedAt: number };
const g = globalThis as unknown as { __mbSession?: Session; __mbCache?: Map<string, { t: number; v: unknown }> };
const session: Session = (g.__mbSession ||= {
  token: "",
  userId: "",
  fetchedAt: 0,
});
const apiCache: Map<string, { t: number; v: unknown }> = (g.__mbCache ||= new Map());

function cacheGet(key: string, ttlMs: number): unknown | undefined {
  const hit = apiCache.get(key);
  if (hit && Date.now() - hit.t < ttlMs) return hit.v;
  if (hit) apiCache.delete(key);
  return undefined;
}
function cacheSet(key: string, v: unknown) {
  if (apiCache.size > 400) {
    const oldest = [...apiCache.entries()].sort((a, b) => a[1].t - b[1].t).slice(0, 100);
    oldest.forEach(([k]) => apiCache.delete(k));
  }
  apiCache.set(key, { t: Date.now(), v });
}

// ---------- core request ----------
type MbResponse = { code?: number; msg?: string; data?: Record<string, unknown> & { [k: string]: unknown }; [k: string]: unknown };

async function ensureGuestToken(force = false): Promise<string> {
  const TOKEN_TTL = 1000 * 60 * 60 * 20; // 20h
  if (!force && session.token && Date.now() - session.fetchedAt < TOKEN_TTL) return session.token;
  const res = await rawRequest<MbResponse>("POST", "/wefeed-mobile-bff/user-api/visitor-login", {}, {}, true);
  if (res?.code === 0 && res.data) {
    const d = res.data as { token?: string; userId?: string; uid?: string };
    if (d.token) {
      session.token = d.token;
      session.userId = String(d.userId || d.uid || "");
      session.fetchedAt = Date.now();
      return session.token;
    }
  }
  throw new Error(`visitor-login failed: ${JSON.stringify(res).slice(0, 200)}`);
}

async function rawRequest<T = MbResponse>(
  method: "GET" | "POST",
  endpoint: string,
  params: Record<string, string | number> = {},
  data: unknown = null,
  isAuthCall = false,
  baseUrlOverride?: string,
): Promise<T | null> {
  const base = baseUrlOverride || CLUSTERS[0];
  const p: Record<string, string | number> = { ...params };
  if (!("host" in p)) p.host = base.replace("https://", "");

  const bodyStr = data != null ? JSON.stringify(data) : "";
  const ts = Date.now();
  const q = Object.keys(p)
    .sort()
    .map((k) => `${k}=${p[k]}`)
    .join("&");
  const url = `${base}${endpoint}?${q}`;

  const headers: Record<string, string> = {
    "User-Agent": "MovieBox/4.0.02 (Android 14; Pixel 6)",
    Accept: "application/json",
    "Accept-Language": REGIONS.IN.acceptLanguage,
    "Content-Type": "application/json;charset=UTF-8",
    "Accept-Encoding": "gzip, deflate",
    "X-Sign-Version": "2.0",
    appid: APP_ID,
    region: "IN",
    lang: REGIONS.IN.lang,
    os: "android",
    "X-Timestamp": String(ts),
    Referer: `${base}/`,
    "X-Client-Token": generateClientToken(),
    "x-tr-signature": generateTrSignature(method, url, bodyStr, ts),
    "X-Play-Mode": "2",
    "X-Client-Info": clientInfo(),
    "X-Client-Status": "0",
  };
  if (!isAuthCall && session.token) {
    headers.Authorization = `Bearer ${session.token}`;
  }
  if (bodyStr) headers["Content-Length"] = String(Buffer.byteLength(bodyStr, "utf-8"));

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const res = await fetch(url, {
      method,
      headers,
      body: method === "POST" ? bodyStr : undefined,
      signal: controller.signal,
      // @ts-expect-error undici dispatcher not typed here
      dispatcher: undefined,
    });
    // dynamic token refresh via x-user header
    const xUser = res.headers.get("x-user");
    if (xUser) {
      try {
        const ud = JSON.parse(xUser) as { token?: string; userId?: string };
        if (ud.token) {
          session.token = ud.token;
          session.userId = String(ud.userId || "");
          session.fetchedAt = Date.now();
        }
      } catch {
        /* ignore */
      }
    }
    const text = await res.text();
    try {
      return JSON.parse(text) as T;
    } catch {
      return { code: -1, msg: `non-json ${res.status}: ${text.slice(0, 120)}` } as unknown as T;
    }
  } catch (e) {
    return { code: -1, msg: `fetch error: ${(e as Error).message}` } as unknown as T;
  } finally {
    clearTimeout(timer);
  }
}

/** Request with path-healing (wefeed prefix toggle) + cluster rotation + 401 recovery */
export async function mbRequest(
  method: "GET" | "POST",
  endpoint: string,
  params: Record<string, string | number> = {},
  data: unknown = null,
  opts: { rotate?: boolean; cacheTtl?: number } = {},
): Promise<MbResponse> {
  const { rotate = false, cacheTtl = 0 } = opts;
  const cacheKey = `${method}:${endpoint}:${JSON.stringify(params)}:${JSON.stringify(data)}`;
  if (cacheTtl > 0) {
    const hit = cacheGet(cacheKey, cacheTtl);
    if (hit !== undefined) return hit as MbResponse;
  }

  await ensureGuestToken();

  const tryEndpoints = [endpoint];
  if (endpoint.startsWith("/wefeed-mobile-bff/")) {
    tryEndpoints.push(endpoint.replace("/wefeed-mobile-bff", ""));
  } else {
    tryEndpoints.push(`/wefeed-mobile-bff${endpoint}`);
  }

  const bases = rotate ? CLUSTERS : [CLUSTERS[0]];
  let last: MbResponse | null = null;

  for (const base of bases) {
    for (const ep of tryEndpoints) {
      let res = await rawRequest<MbResponse>(method, ep, params, data, false, base);
      // 401 → refresh guest token once
      if (res && (res.code === 401 || String(res.msg || "").includes("JWT token has expired") || String(res.msg || "").includes("Unauthorized"))) {
        await ensureGuestToken(true);
        res = await rawRequest<MbResponse>(method, ep, params, data, false, base);
      }
      if (res && res.code === 0 && res.data) {
        if (cacheTtl > 0) cacheSet(cacheKey, res);
        return res;
      }
      last = res;
    }
  }
  return last || { code: -1, msg: "no response" };
}

// ---------- typed API calls ----------
export type MovieSubject = {
  subjectId?: string;
  subjectType?: number; // 1 movie, 2 series, 3 ugc
  title?: string;
  description?: string;
  releaseDate?: string;
  duration?: string;
  durationSeconds?: number;
  genre?: string;
  countryName?: string;
  language?: string;
  imdbRatingValue?: string;
  cover?: { url?: string } | string | null;
  staffList?: { name?: string; role?: string; avatar?: { url?: string } | string }[];
  wantToSeeCount?: number;
  haveSeenCount?: number;
  dubs?: { subjectId?: string; lanName?: string; lanCode?: string; original?: boolean }[];
  seasonList?: unknown[];
  seNum?: number;
  resourceDetectors?: ResourceDetector[];
  stills?: unknown;
  trailer?: unknown;
  isCam?: boolean;
  vipInfo?: unknown;
  ops?: { cornerBadge?: string } | null;
  [k: string]: unknown;
};

export type ResourceDetector = {
  type?: number;
  totalEpisode?: number;
  source?: string;
  downloadUrl?: string;
  resourceLink?: string;
  resourceId?: string;
  size?: string;
  resolutionList?: {
    episode?: number;
    se?: number;
    resolution?: number;
    resourceLink?: string;
    size?: string;
    linkType?: number;
    uploadBy?: string;
  }[];
  [k: string]: unknown;
};

export function coverUrl(cover: MovieSubject["cover"]): string {
  if (!cover) return "";
  if (typeof cover === "string") return cover;
  return cover.url || "";
}

export function staffName(s: { name?: string; role?: string; avatar?: { url?: string } | string }): string {
  return s.name || "";
}

export async function getTabOperating(tabId: number | string, page = 1) {
  return mbRequest("GET", "/wefeed-mobile-bff/tab-operating", { tabId, page, pageSize: 24 }, null, { cacheTtl: 5 * 60_000 });
}

export async function getTrendingV2(page = 1, pageSize = 24) {
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/trending/v2", { page, pageSize }, null, { cacheTtl: 3 * 60_000 });
}

export async function getTopRec(page = 1, pageSize = 24) {
  return mbRequest("POST", "/wefeed-mobile-bff/subject-api/top-rec", {}, { page, pageSize }, { cacheTtl: 3 * 60_000 });
}

export async function getGenreTop(type: "trending" | "movie" | "tv" | "ugc" | "sports", page = 1, perPage = 24) {
  return mbRequest("POST", "/wefeed-mobile-bff/subject-api/genre-top", {}, { type, page, perPage }, { cacheTtl: 3 * 60_000 });
}

export async function getDailyRec(page = 1, pageSize = 24) {
  return mbRequest("POST", "/wefeed-mobile-bff/subject-api/daily-movie-rec", {}, { page, pageSize }, { cacheTtl: 3 * 60_000 });
}

export async function searchV2(keyword: string, page = 1, perPage = 20, subjectType = 0) {
  const size = Math.min(20, Math.max(1, perPage)); // API hard limit: "Up to 20"
  return mbRequest(
    "POST",
    "/wefeed-mobile-bff/subject-api/search/v2",
    {},
    { keyword, page, perPage: size, pageSize: size, subjectType, type: subjectType, q: keyword },
    { cacheTtl: 2 * 60_000 },
  );
}

export async function getSearchRank() {
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/search-rank/v2", { page: 1, pageSize: 12 }, null, { cacheTtl: 10 * 60_000 });
}

export async function getRankingList(tabId = 1) {
  return mbRequest(
    "GET",
    "/wefeed-mobile-bff/tab/ranking-list",
    { tabId, categoryType: "all", page: 1, perPage: 20 },
    null,
    { cacheTtl: 5 * 60_000 },
  );
}

export async function getSubject(subjectId: string) {
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/get", { subjectId }, null, { cacheTtl: 10 * 60_000, rotate: false });
}

export async function getDetailRec(subjectId: string, perPage = 12) {
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/detail-rec", { subjectId, perPage, page: 1 }, null, { cacheTtl: 5 * 60_000 });
}

export async function getRelatedPlayback(subjectId: string) {
  return mbRequest("POST", "/wefeed-mobile-bff/subject-api/play-related-rec", {}, { subjectId }, { cacheTtl: 5 * 60_000 });
}

export async function getSeasonInfoV2(subjectId: string) {
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/season-info/v2", { subjectId }, null, { cacheTtl: 10 * 60_000 });
}

export async function getPlayInfoV2(subjectId: string, se?: number, ep?: number) {
  const params: Record<string, string | number> = { subjectId };
  if (se && se > 0) params.se = se;
  if (ep && ep > 0) params.ep = ep;
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/play-info/v2", params, null, { rotate: true });
}

export async function getPlayInfo(subjectId: string, se?: number, ep?: number) {
  const params: Record<string, string | number> = { subjectId };
  if (se && se > 0) params.se = se;
  if (ep && ep > 0) params.ep = ep;
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/play-info", params, null, { rotate: true });
}

export async function getResourceV2(subjectId: string, se = 0, ep = 0) {
  const params: Record<string, string | number> = {
    subjectId,
    page: 1,
    perPage: 20,
    all: 0,
    startPosition: 1,
    endPosition: 1,
    pagerMode: 2,
    resolution: 0,
    isVip: "true",
  };
  if (se > 0 || ep > 0) {
    params.se = se;
    params.epFrom = ep;
    params.epTo = ep;
  }
  return mbRequest("GET", "/wefeed-mobile-bff/subject-api/resource/v2", params, null, { rotate: true });
}

export async function getResourcePosition(subjectId: string, resourceId: string, failUrl: string) {
  return mbRequest(
    "GET",
    "/wefeed-mobile-bff/subject-api/resource-position/v2",
    { subjectId, resourceId, failUrl, failCode: "404", resourceNum: "0", isVip: "true" },
    null,
    { rotate: true },
  );
}

export async function getUgcFirstBySubject(subjectId: string) {
  return mbRequest("GET", "/wefeed-mobile-bff/ugc/video/first-by-subject", { subjectId }, null, { rotate: true });
}

export async function getUgcVideoDownload(ugcVideoId: string) {
  return mbRequest("GET", "/wefeed-mobile-bff/ugc/video/download", { ugcVideoId }, null, { rotate: true });
}

export async function getExtCaptions(subjectId: string, resourceId: string, episode = 0) {
  return mbRequest(
    "GET",
    "/wefeed-mobile-bff/subject-api/get-ext-captions",
    { resourceId, subjectId, episode },
    null,
    { rotate: false },
  );
}

// ---------- urlprefix trap bypass (APK LocalMovieBoxServer logic) ----------
export function bypassTrap(signCookie: string): string | null {
  if (!signCookie || !signCookie.includes("urlprefix=")) return null;
  try {
    const start = signCookie.indexOf("urlprefix=") + 10;
    let end = signCookie.indexOf(":", start);
    if (end === -1) end = signCookie.indexOf(";", start);
    if (end === -1) end = signCookie.length;
    const b64 = signCookie.slice(start, end);
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const decoded = Buffer.from(padded, "base64").toString("utf-8");
    if (!decoded.startsWith("http")) return null;
    return decoded.endsWith("/") ? `${decoded}index.m3u8` : `${decoded}/index.m3u8`;
  } catch {
    return null;
  }
}

// ---------- stream candidate validation ----------
export type StreamCandidate = {
  url: string;
  quality: string;
  codec: string;
  cookie: string;
  kind: "mp4" | "hls" | "dash";
  size?: string;
  source: string;
};

function classify(url: string): StreamCandidate["kind"] {
  const l = url.toLowerCase();
  if (l.includes(".m3u8")) return "hls";
  if (l.includes(".mpd")) return "dash";
  return "mp4";
}

export async function headValidate(url: string, cookie: string, timeoutMs = 5000): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const headers: Record<string, string> = { "User-Agent": "ExoPlayerLib/2.18.7" };
    if (cookie) headers.Cookie = cookie;
    const res = await fetch(url, { method: "HEAD", headers, signal: controller.signal, redirect: "follow" });
    return [200, 206, 302].includes(res.status);
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

export function prioritize(st: StreamCandidate): number {
  const u = st.url.toLowerCase();
  const codec = (st.codec || "").toLowerCase();
  let score = 0;
  // HEVC is undecodable in many browsers → strong penalty
  if (
    codec.includes("hevc") ||
    codec.includes("265") ||
    codec.includes("hev1") ||
    codec.includes("hvc1") ||
    u.includes("h265") ||
    u.includes("x265") ||
    u.includes("hevc") ||
    u.includes("hev1")
  )
    score += 8;
  // unknown codec → slight penalty so explicit h264 wins
  if (!codec || codec === "unknown") score += 3;
  if (st.kind === "mp4") score += 0;
  else if (st.kind === "hls") score += 2;
  else score += 4; // dash
  return score;
}
