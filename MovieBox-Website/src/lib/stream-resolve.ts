/**
 * Full stream resolution pipeline (server-side).
 * Port of moviebox_api_server.py /stream logic + AnyFilm.apk LocalMovieBoxServer bypass.
 */
import {
  MovieSubject,
  ResourceDetector,
  StreamCandidate,
  bypassTrap,
  getExtCaptions,
  getPlayInfo,
  getPlayInfoV2,
  getResourcePosition,
  getResourceV2,
  getSubject,
  getUgcFirstBySubject,
  headValidate,
  isTrapUrl,
  prioritize,
} from "./moviebox";

type RawStream = {
  url?: string;
  quality?: string;
  resolution?: number;
  codec?: string;
  codecName?: string;
  signCookie?: string;
  cookie?: string;
  id?: string;
  resourceId?: string;
  size?: string;
  sizeBytes?: number;
  duration?: number;
  source: string;
};

export type ResolvedStream = {
  candidates: StreamCandidate[];
  subtitles: { url: string; lanName: string; lan?: string; size?: number }[];
  subject: MovieSubject | null;
  isSeries: boolean;
};

function normCookie(c?: string | null): string {
  if (!c) return "";
  return c;
}

function toCandidate(st: RawStream): StreamCandidate | null {
  const url = st.url || "";
  if (!url) return null;
  const codec = st.codec || st.codecName || "";
  const quality = st.quality || (st.resolution ? `${st.resolution}p` : "Auto");
  const cookie = normCookie(st.signCookie || st.cookie || "");
  return {
    url,
    quality,
    codec: String(codec),
    cookie,
    kind: classify(url),
    size: st.size,
    source: st.source,
  };
}

function classify(url: string): StreamCandidate["kind"] {
  const l = url.toLowerCase();
  if (l.includes(".m3u8")) return "hls";
  if (l.includes(".mpd")) return "dash";
  return "mp4";
}

function humanSize(bytes?: number | string): string {
  const b = typeof bytes === "string" ? parseInt(bytes, 10) : bytes;
  if (!b || b <= 0) return "";
  if (b > 1024 * 1024 * 1024) return `${(b / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  if (b > 1024 * 1024) return `${Math.round(b / (1024 * 1024))} MB`;
  return `${Math.max(1, Math.round(b / 1024))} KB`;
}

export async function resolveStreams(
  subjectId: string,
  season = 1,
  episode = 1,
  opts: { validate?: boolean } = {},
): Promise<ResolvedStream> {
  const validate = opts.validate !== false;
  const detailRes = await getSubject(subjectId);
  const subject = (detailRes.data || {}) as MovieSubject;
  const isSeries = String(subject.subjectType ?? "2") === "2" || (subject.seNum ?? 0) > 0;
  const se = isSeries ? season : 0;
  const ep = isSeries ? episode : 0;

  const collected: RawStream[] = [];
  const subtitleList: { url: string; lanName: string; lan?: string; size?: number }[] = [];
  const seenUrls = new Set<string>();

  // ---- 1) play-info v2 then v1 ----
  for (const fetcher of [getPlayInfoV2, getPlayInfo]) {
    try {
      const res = await fetcher(subjectId, se || undefined, ep || undefined);
      const data = (res.data || {}) as {
        streams?: RawStream[];
        streamList?: RawStream[];
        signCookie?: string;
        subTitleList?: { url?: string; lanName?: string; lan?: string; size?: number }[];
      };
      const list = data.streams || data.streamList || [];
      const globalCookie = data.signCookie || "";
      for (const st of list) {
        if (!st.url) continue;
        collected.push({
          ...st,
          signCookie: st.signCookie || globalCookie,
          source: fetcher.name || "play-info",
        });
      }
      for (const sub of data.subTitleList || []) {
        if (sub.url && !seenUrls.has(sub.url)) {
          seenUrls.add(sub.url);
          subtitleList.push({ url: sub.url, lanName: sub.lanName || sub.lan || "Subtitle", lan: sub.lan, size: sub.size });
        }
      }
      if (list.length) break;
    } catch {
      /* try next */
    }
  }

  // ---- 2) resource/v2 mirrors (isVip=true) ----
  try {
    const res = await getResourceV2(subjectId, se, ep);
    const list = ((res.data || {}) as { list?: (RawStream & { resourceId?: string; duration?: number })[] }).list || [];
    for (const it of list) {
      const link = it.resourceLink || (it as { url?: string }).url || it.downloadUrl;
      if (!link) continue;
      collected.push({
        url: link,
        quality: it.quality || (it.resolution ? `${it.resolution}p` : "Auto"),
        codec: it.codec || it.codecName || "",
        signCookie: it.signCookie || "",
        id: String(it.resourceId || it.id || ""),
        size: humanSize(it.sizeBytes || (it as { size?: string }).size),
        source: "resource/v2",
      });
    }
  } catch {
    /* continue */
  }

  // ---- 3) trap recovery via resource-position/v2 ----
  const recovered: RawStream[] = [];
  for (const st of collected) {
    const url = st.url || "";
    const rid = String(st.resourceId || st.id || "");
    if (isTrapUrl(url) && rid) {
      try {
        const rr = await getResourcePosition(subjectId, rid, url);
        const rl = ((rr.data || {}) as { list?: RawStream[] }).list || [];
        const real = rl[0]?.resourceLink || rl[0]?.url;
        if (real && !isTrapUrl(real)) {
          recovered.push({
            ...st,
            url: real,
            signCookie: rl[0].signCookie || st.signCookie || "",
            source: "resource-position",
          });
        }
      } catch {
        /* skip */
      }
    } else if (!isTrapUrl(url)) {
      recovered.push(st);
    } else if (isTrapUrl(url) && !rid) {
      // trap with signCookie urlprefix bypass (APK Phase 5)
      const bypassed = bypassTrap(st.signCookie || "");
      if (bypassed && !isTrapUrl(bypassed)) {
        recovered.push({ ...st, url: bypassed, source: "urlprefix-bypass" });
      }
    }
  }
  collected.length = 0;
  collected.push(...recovered);

  // ---- 4) detail resourceDetectors (community signed mirrors — most reliable) ----
  const detectors: ResourceDetector[] = subject.resourceDetectors || [];
  for (const det of detectors) {
    const detCookie = normCookie((det as { signCookie?: string }).signCookie || "");
    // detector-level downloadUrl (whole movie)
    if (det.downloadUrl && !isTrapUrl(det.downloadUrl) && (se === 0 || det.type === 0)) {
      collected.push({
        url: det.downloadUrl,
        quality: "Auto",
        codec: "",
        signCookie: detCookie,
        id: String(det.resourceId || ""),
        size: humanSize((det as { size?: string }).size),
        source: "detail.downloadUrl",
      });
    }
    // resolutionList entries (per-episode for series)
    for (const rl of det.resolutionList || []) {
      const link = rl.resourceLink;
      if (!link || isTrapUrl(link)) continue;
      if (se > 0 && rl.episode != null && Number(rl.episode) > 0 && Number(rl.episode) !== ep) continue;
      collected.push({
        url: link,
        quality: rl.resolution ? `${rl.resolution}p` : "Auto",
        codec: "",
        signCookie: detCookie,
        id: String(det.resourceId || ""),
        size: humanSize(rl.size),
        source: "detail.resolutionList",
      });
    }
    // ext captions from detectors
    for (const cap of (det as { extCaptions?: { url?: string; lanName?: string; lan?: string }[] }).extCaptions || []) {
      if (cap.url && !seenUrls.has(cap.url)) {
        seenUrls.add(cap.url);
        subtitleList.push({ url: cap.url, lanName: cap.lanName || cap.lan || "Subtitle", lan: cap.lan });
      }
    }
  }

  // ---- 5) UGC fallback (first-by-subject → ugc/video/download) ----
  if (!collected.length) {
    let ugcRes: { data?: { ugcVideoId?: string; url?: string; videoUrl?: string; playUrl?: string } } | null = null;
    try {
      ugcRes = await getUgcFirstBySubject(subjectId);
      const uvid = ugcRes?.data?.ugcVideoId;
      if (uvid) {
        const { getUgcVideoDownload } = await import("./moviebox");
        const dl = await getUgcVideoDownload(uvid);
        const items = ((dl.data || {}) as { items?: { resources?: { url?: string; format?: string; id?: string; resolution?: number }[]; ugcVideoId?: string }[] }).items || [];
        for (const it of items) {
          for (const r of it.resources || []) {
            if (r.url && !isTrapUrl(r.url)) {
              collected.push({
                url: r.url,
                quality: r.resolution ? `${r.resolution}p` : "Auto",
                codec: "",
                signCookie: "",
                id: String(r.id || it.ugcVideoId || ""),
                source: "ugc.download",
              });
            }
          }
        }
      }
    } catch {
      /* continue */
    }
    // direct url fields on first-by-subject as last resort
    if (!collected.length && ugcRes?.data) {
      const u = ugcRes.data.url || ugcRes.data.videoUrl || ugcRes.data.playUrl;
      if (u && !isTrapUrl(u)) {
        collected.push({ url: u, quality: "Auto", codec: "", signCookie: "", id: "", source: "ugc" });
      }
    }
  }

  // ---- 6) urlprefix bypass for any remaining trap with signCookie ----
  for (const st of collected) {
    if (isTrapUrl(st.url || "") && st.signCookie) {
      const bypassed = bypassTrap(st.signCookie);
      if (bypassed) st.url = bypassed;
    }
  }

  // ---- 7) dedupe + validate + sort ----
  const candidates: StreamCandidate[] = [];
  const seenCand = new Set<string>();
  for (const st of collected) {
    if (!st.url || isTrapUrl(st.url)) continue;
    const c = toCandidate(st);
    if (!c || seenCand.has(c.url)) continue;
    if (!c.codec) {
      // infer codec from URL hints (resource/h265/, x265, hev1 …)
      const l = c.url.toLowerCase();
      if (l.includes("h265") || l.includes("x265") || l.includes("hev1") || l.includes("/h265/")) c.codec = "hevc";
      else if (l.includes("h264") || l.includes("x264") || l.includes("avc1")) c.codec = "h264";
    }
    seenCand.add(c.url);
    candidates.push(c);
  }

  if (validate && candidates.length) {
    const results = await Promise.all(candidates.map((c) => headValidate(c.url, c.cookie)));
    const ok = candidates.filter((_, i) => results[i]);
    if (ok.length) {
      candidates.length = 0;
      candidates.push(...ok);
    }
  }

  candidates.sort((a, b) => prioritize(a) - prioritize(b));

  // ---- 8) external captions (best-effort) ----
  if (subtitleList.length < 2 && candidates.length) {
    try {
      const rid = candidates[0]?.source.includes("detail") ? subjectId : subjectId;
      const ext = await getExtCaptions(subjectId, rid, ep);
      const list =
        ((ext.data || {}) as { extCaptions?: { url?: string; lanName?: string; lan?: string }[] }).extCaptions || [];
      for (const c of list) {
        if (c.url && !seenUrls.has(c.url)) {
          seenUrls.add(c.url);
          subtitleList.push({ url: c.url, lanName: c.lanName || c.lan || "Subtitle", lan: c.lan });
        }
      }
    } catch {
      /* ignore */
    }
  }

  return { candidates: candidates.slice(0, 8), subtitles: subtitleList.slice(0, 10), subject, isSeries };
}
