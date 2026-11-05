"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Spinner } from "./cards";
import { saveHistory } from "@/lib/store";

export type Candidate = {
  url: string;
  quality: string;
  codec: string;
  cookie: string;
  kind: "mp4" | "hls" | "dash" | "transcode";
  size?: string;
  source: string;
};

export type SubtitleTrack = { url: string; lanName: string; lan?: string };

export type PlayerInput = {
  subjectId: string;
  title: string;
  poster?: string;
  se?: number;
  ep?: number;
  seasonEpisodes?: { se: number; episodes: number[] }[];
  onExit: () => void;
  onEpisodeChange?: (se: number, ep: number) => void;
  resumeSec?: number;
};

function proxied(url: string, ck?: string, opts?: { vtt?: boolean; dl?: string }) {
  const p = new URLSearchParams({ url });
  if (ck) p.set("ck", ck);
  if (opts?.vtt) p.set("vtt", "1");
  if (opts?.dl) p.set("dl", opts.dl);
  return `/api/mb/media?${p.toString()}`;
}

// --- browser decode capability probing (HEVC is unsupported in many browsers) ---
const probeVideo =
  typeof document !== "undefined" ? document.createElement("video") : null;

function codecSupported(codec: string, kind: Candidate["kind"]): boolean {
  if (kind === "hls" || kind === "transcode") return true; // hls.js handles its own; transcode is already h264
  const c = (codec || "").toLowerCase();
  const isHevc = c.includes("hevc") || c.includes("265") || c.includes("hev1") || c.includes("hvc1");
  const isAvc = c.includes("264") || c.includes("avc1") || c.includes("h264");
  if (probeVideo) {
    if (isAvc) return probeVideo.canPlayType('video/mp4; codecs="avc1.640028"') !== "";
    if (isHevc)
      return (
        probeVideo.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') !== "" ||
        probeVideo.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"') !== ""
      );
    // unknown codec: assume HEVC-style risk only if URL hints h265; else optimistic
    return true;
  }
  return true;
}

function isHevcUrl(u: string): boolean {
  const l = (u || "").toLowerCase();
  return l.includes("h265") || l.includes("x265") || l.includes("hev1") || l.includes("hevc") || l.includes("/h265/");
}

export function fmtTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return "0:00";
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = Math.floor(sec % 60);
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

export default function Player(input: PlayerInput) {
  const { subjectId, title, se = 1, ep = 1, onExit, onEpisodeChange, resumeSec = 0 } = input;
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<{ destroy: () => void } | null>(null);
  const resumeDoneRef = useRef(false);
  const lastSaveRef = useRef(0);

  const [st, setSt] = useState<{
    key: string;
    loading: boolean;
    error?: string;
    candidates?: Candidate[];
    subtitles?: SubtitleTrack[];
  }>({ key: `${subjectId}|${se}|${ep}`, loading: true });
  const [activeIdx, setActiveIdx] = useState(0);
  const [subOn, setSubOn] = useState<SubtitleTrack | null>(null);
  const [showSettings, setShowSettings] = useState<null | "quality" | "subtitle" | "episodes">(null);
  const [curTime, setCurTime] = useState(0);
  const [duration, setDuration] = useState(0);

  const streamKey = `${subjectId}|${se}|${ep}`;
  const loading = st.key !== streamKey || st.loading;
  const error = st.key === streamKey ? st.error || "" : "";
  const subtitles = st.key === streamKey ? st.subtitles || [] : [];

  // reorder candidates by browser decodability (supported first, HEVC last)
  const rawCandidates = st.key === streamKey ? st.candidates || [] : [];
  const candidates = useMemo(() => {
    const decodable = (c: Candidate) => codecSupported(c.codec, c.kind === "dash" ? "mp4" : c.kind) || !isHevcUrl(c.url);
    const ok = rawCandidates.filter(decodable);
    const bad = rawCandidates.filter((c) => !decodable(c));
    const transcode: Candidate = {
      url: `/api/mb/transcode/${subjectId}?se=${se}&ep=${ep}&h=480`,
      quality: "480p",
      codec: "h264",
      cookie: "",
      kind: "transcode",
      source: "server-transcode",
    };
    if (!ok.length) {
      // nothing decodable in this browser → server-side H.264 transcode goes FIRST
      return rawCandidates.length ? [transcode, ...bad] : [];
    }
    return [...ok, ...bad];
  }, [rawCandidates, subjectId, se, ep]);

  const active: Candidate | null = candidates[activeIdx] || null;

  // fetch stream candidates
  useEffect(() => {
    let cancelled = false;
    resumeDoneRef.current = false;
    setActiveIdx(0);
    setSubOn(null);
    (async () => {
      try {
        const res = await fetch(`/api/mb/stream/${subjectId}?se=${se}&ep=${ep}`);
        const j = await res.json();
        if (cancelled) return;
        if (!j.ok || !j.candidates?.length) {
          setSt({
            key: streamKey,
            loading: false,
            error: j.error || "No playable stream found for this title right now.",
          });
          return;
        }
        setSt({ key: streamKey, loading: false, candidates: j.candidates, subtitles: j.subtitles || [] });
      } catch (e) {
        if (!cancelled) {
          setSt({ key: streamKey, loading: false, error: (e as Error).message });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [subjectId, se, ep, streamKey]);

  // attach source to <video>
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !active) return;
    let cancelled = false;

    const cleanup = () => {
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };

    (async () => {
      cleanup();
      const src = active.kind === "transcode" ? active.url : proxied(active.url, active.cookie);
      if (active.kind === "hls") {
        try {
          const HlsMod = (await import("hls.js")).default;
          if (cancelled) return;
          if (HlsMod.isSupported()) {
            const hls = new HlsMod({ enableWorker: true, lowLatencyMode: false, backBufferLength: 60 });
            hls.loadSource(src);
            hls.attachMedia(video);
            hlsRef.current = hls;
          } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
            video.src = src;
          } else {
            setSt({ key: streamKey, loading: false, error: "HLS not supported in this browser." });
          }
        } catch (e) {
          setSt({ key: streamKey, loading: false, error: `Player init failed: ${(e as Error).message}` });
        }
      } else {
        // mp4 / transcode (and any direct source); DASH manifests are rare and unsupported here
        video.src = src;
        video.load();
      }
      // decode-failure watchdog: some browsers start audio-only for HEVC (videoWidth stays 0)
      const decodeCheck = (ev: Event) => {
        if (cancelled) return;
        const el = ev.target as HTMLVideoElement;
        const noVideo = el.videoWidth === 0 && el.duration > 0;
        const hevcSuspect =
          codecSupported(active.codec, active.kind === "dash" ? "mp4" : active.kind) === false || isHevcUrl(active.url);
        if (noVideo && hevcSuspect && activeIdx < candidates.length - 1) {
          setTimeout(() => {
            if (!cancelled) setActiveIdx((i) => Math.min(i + 1, candidates.length - 1));
          }, 400);
        }
      };
      video.addEventListener("loadedmetadata", decodeCheck, { once: true });
      video.addEventListener("durationchange", decodeCheck, { once: true });
      // start playback automatically (APK-style); fall back to muted if blocked
      const tryPlay = () => {
        video.play().catch(() => {
          video.muted = true;
          video.play().catch(() => {
            /* user will press play */
          });
        });
      };
      if (video.readyState >= 3) {
        tryPlay();
      } else {
        video.addEventListener("canplay", tryPlay, { once: true });
      }
    })();

    return () => {
      cancelled = true;
      cleanup();
    };
  }, [active]);

  // subtitle track is rendered as JSX <track> inside <video>
  // resume + progress persistence
  const onTimeUpdate = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    setCurTime(v.currentTime || 0);
    if (v.duration && Number.isFinite(v.duration)) setDuration(v.duration);
    if (!resumeDoneRef.current && resumeSec > 2 && v.currentTime < resumeSec - 5) {
      v.currentTime = resumeSec;
    }
    if (resumeSec > 0) resumeDoneRef.current = true;

    const now = Date.now();
    if (now - lastSaveRef.current > 5000 && v.duration > 0) {
      lastSaveRef.current = now;
      saveHistory({
        subjectId,
        title,
        cover: input.poster || "",
        subjectType: input.seasonEpisodes?.length ? 2 : 1,
        se,
        ep,
        posSec: Math.floor(v.currentTime),
        durSec: Math.floor(v.duration),
        updatedAt: now,
      });
    }
  }, [subjectId, title, input.poster, se, ep, resumeSec, input.seasonEpisodes?.length]);

  const markSeen = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    saveHistory({
      subjectId,
      title,
      cover: input.poster || "",
      subjectType: input.seasonEpisodes?.length ? 2 : 1,
      se,
      ep,
      posSec: Math.floor(v.currentTime || 0),
      durSec: Math.floor(v.duration || 0),
      updatedAt: Date.now(),
    });
  }, [subjectId, title, input.poster, se, ep, input.seasonEpisodes?.length]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (showSettings) setShowSettings(null);
        else {
          markSeen();
          onExit();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onExit, showSettings, markSeen]);

  const currentEpisodes = useMemo(
    () => input.seasonEpisodes?.find((s) => s.se === se)?.episodes || [],
    [input.seasonEpisodes, se],
  );

  const download = (c: Candidate) => {
    const a = document.createElement("a");
    const safeTitle = title.replace(/[^\w\- ]+/g, "").trim() || "video";
    const ext = c.kind === "mp4" ? "mp4" : "ts";
    const suffix = input.seasonEpisodes?.length ? `_S${se}E${ep}` : "";
    a.href = proxied(c.url, c.cookie, { dl: `${safeTitle}${suffix}_${c.quality}.${ext}` });
    a.setAttribute("download", `${safeTitle}${suffix}_${c.quality}.${ext}`);
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  const qualityLabel = (c: Candidate) => {
    const q =
      c.quality && c.quality !== "Auto" ? c.quality : c.kind === "hls" ? "Auto HLS" : c.kind === "transcode" ? "480p" : "Auto";
    const bits: string[] = [q];
    if (c.codec) bits.push(c.codec.toUpperCase());
    if (c.size) bits.push(c.size);
    if (c.kind === "transcode") bits.push("compat");
    return bits.join(" · ");
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-black">
      {/* top overlay */}
      <div className="absolute left-0 right-0 top-0 z-20 flex items-center gap-3 bg-gradient-to-b from-black/90 to-transparent px-3 pb-6 pt-3">
        <button
          onClick={() => {
            markSeen();
            onExit();
          }}
          className="flex h-10 w-10 items-center justify-center rounded-full bg-black/50 text-white hover:bg-black/70"
          aria-label="Back"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-white">{title}</p>
          <p className="text-[11px] text-neutral-300">
            {input.seasonEpisodes?.length ? `Season ${se} · Episode ${ep}` : "Movie"} ·{" "}
            {active ? qualityLabel(active) : "resolving…"}
          </p>
        </div>
        <button
          onClick={() => setShowSettings((s) => (s ? null : "quality"))}
          className="flex h-10 w-10 items-center justify-center rounded-full bg-black/50 text-white hover:bg-black/70"
          aria-label="Settings"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 11-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 110-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 114 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 110 4h-.09a1.65 1.65 0 00-1.51 1z" />
          </svg>
        </button>
      </div>

      {/* video area */}
      <div className="relative flex flex-1 items-center justify-center">
        <video
          ref={videoRef}
          className="h-full w-full bg-black"
          controls
          playsInline
          poster={input.poster}
          preload="auto"
          onTimeUpdate={onTimeUpdate}
          onEnded={() => {
            markSeen();
            const eps = currentEpisodes;
            const i = eps.indexOf(ep);
            if (i >= 0 && i < eps.length - 1) onEpisodeChange?.(se, eps[i + 1]);
          }}
          crossOrigin="anonymous"
        >
          {subOn && (
            <track
              default
              kind="subtitles"
              label={subOn.lanName}
              srcLang={subOn.lan || "en"}
              src={proxied(subOn.url, undefined, { vtt: true })}
            />
          )}
        </video>

        {loading && (
          <div className="absolute inset-0 z-10 flex items-center justify-center bg-black/80">
            <Spinner label={`Resolving stream for ${title}${input.seasonEpisodes?.length ? ` S${se}E${ep}` : ""}…`} />
          </div>
        )}
        {!loading && error && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-4 bg-black/85 px-8 text-center">
            <p className="text-sm font-semibold text-rose-400">Playback failed</p>
            <p className="max-w-md text-xs leading-relaxed text-neutral-300">{error}</p>
            <button
              onClick={() => {
                setSt({ key: streamKey, loading: true });
                fetch(`/api/mb/stream/${subjectId}?se=${se}&ep=${ep}&validate=0`)
                  .then((r) => r.json())
                  .then((j) => {
                    if (j.ok && j.candidates?.length) {
                      setActiveIdx(0);
                      setSt({ key: streamKey, loading: false, candidates: j.candidates, subtitles: j.subtitles || [] });
                    } else {
                      setSt({ key: streamKey, loading: false, error: j.error || "Still no playable stream." });
                    }
                  })
                  .catch((e) => {
                    setSt({ key: streamKey, loading: false, error: (e as Error).message });
                  });
              }}
              className="rounded-full bg-rose-600 px-5 py-2 text-xs font-semibold text-white hover:bg-rose-500"
            >
              Retry without validation
            </button>
          </div>
        )}

        {/* settings sheet */}
        {showSettings && (
          <div className="absolute bottom-0 right-0 top-16 z-30 w-72 overflow-y-auto rounded-tl-2xl border-l border-neutral-800 bg-neutral-950/95 p-4 backdrop-blur">
            <div className="mb-3 flex items-center justify-between">
              <p className="text-sm font-bold text-white">Playback</p>
              <button onClick={() => setShowSettings(null)} className="text-neutral-400 hover:text-white" aria-label="Close">
                ✕
              </button>
            </div>

            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-wider text-neutral-500">Quality / Source</p>
            {candidates.map((c, i) => (
              <button
                key={i}
                onClick={() => {
                  setActiveIdx(i);
                  setShowSettings(null);
                }}
                className={`mb-1 flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-xs ${
                  activeIdx === i ? "bg-rose-600/20 text-rose-300" : "text-neutral-300 hover:bg-neutral-800"
                }`}
              >
                <span className="font-medium">{qualityLabel(c)}</span>
                <span className="text-[10px] text-neutral-500">{c.source}</span>
              </button>
            ))}

            {subtitles.length > 0 && (
              <>
                <p className="mb-1.5 mt-4 text-[10px] font-bold uppercase tracking-wider text-neutral-500">Subtitles</p>
                <button
                  onClick={() => setSubOn(null)}
                  className={`mb-1 w-full rounded-lg px-3 py-2 text-left text-xs ${!subOn ? "bg-rose-600/20 text-rose-300" : "text-neutral-300 hover:bg-neutral-800"}`}
                >
                  Off
                </button>
                {subtitles.map((s, i) => (
                  <button
                    key={i}
                    onClick={() => {
                      setSubOn(s);
                      setShowSettings(null);
                    }}
                    className={`mb-1 w-full rounded-lg px-3 py-2 text-left text-xs ${subOn === s ? "bg-rose-600/20 text-rose-300" : "text-neutral-300 hover:bg-neutral-800"}`}
                  >
                    {s.lanName}
                  </button>
                ))}
              </>
            )}

            <p className="mb-1.5 mt-4 text-[10px] font-bold uppercase tracking-wider text-neutral-500">Download</p>
            {candidates.slice(0, 3).map((c, i) => (
              <button
                key={i}
                onClick={() => download(c)}
                className="mb-1 w-full rounded-lg px-3 py-2 text-left text-xs text-neutral-300 hover:bg-neutral-800"
              >
                ⬇ {qualityLabel(c)}
              </button>
            ))}

            {input.seasonEpisodes?.length && currentEpisodes.length > 0 && (
              <>
                <p className="mb-1.5 mt-4 text-[10px] font-bold uppercase tracking-wider text-neutral-500">
                  Episodes (Season {se})
                </p>
                <div className="grid max-h-44 grid-cols-5 gap-1.5 overflow-y-auto">
                  {currentEpisodes.map((e) => (
                    <button
                      key={e}
                      onClick={() => {
                        onEpisodeChange?.(se, e);
                        setShowSettings(null);
                      }}
                      className={`rounded-md px-0 py-1.5 text-center text-xs ${
                        e === ep ? "bg-rose-600 font-bold text-white" : "bg-neutral-800 text-neutral-300 hover:bg-neutral-700"
                      }`}
                    >
                      {e}
                    </button>
                  ))}
                </div>
                {input.seasonEpisodes.length > 1 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {input.seasonEpisodes.map((s) => (
                      <button
                        key={s.se}
                        onClick={() => {
                          onEpisodeChange?.(s.se, s.episodes[0]);
                          setShowSettings(null);
                        }}
                        className={`rounded-full px-3 py-1 text-[11px] ${
                          s.se === se ? "bg-rose-600 text-white" : "bg-neutral-800 text-neutral-300 hover:bg-neutral-700"
                        }`}
                      >
                        S{s.se}
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}

            <div className="mt-4 border-t border-neutral-800 pt-2 text-[10px] text-neutral-500">
              {fmtTime(curTime)} / {fmtTime(duration)} · source: {active?.source}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
