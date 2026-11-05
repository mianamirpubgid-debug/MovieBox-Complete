"use client";

import { useEffect, useMemo, useState } from "react";
import { PosterCard, PosterImg, SectionRow, Spinner, getCover, type Subject } from "./cards";
import { getFavorites, isFavorite, toggleFavorite } from "@/lib/store";

type DetailData = {
  ok: boolean;
  subject: Subject & {
    staffList?: { name?: string; role?: string; avatar?: { url?: string } | string }[];
    dubs?: { subjectId?: string; lanName?: string; lanCode?: string; original?: boolean }[];
    description?: string;
    duration?: string;
    genre?: string;
    countryName?: string;
    language?: string;
    imdbRatingValue?: string;
    releaseDate?: string;
    seNum?: number;
    isCam?: boolean;
    wantToSeeCount?: number;
    haveSeenCount?: number;
  };
  seasons: { se: number; episodes: number[] }[];
  related: Subject[];
};

export default function DetailView({
  subjectId,
  onBack,
  onPlay,
  onPick,
}: {
  subjectId: string;
  onBack: () => void;
  onPlay: (se: number, ep: number) => void;
  onPick: (s: Subject) => void;
}) {
  const [data, setData] = useState<{ sid: string; data?: DetailData; error?: string }>({ sid: subjectId });
  const [season, setSeason] = useState<number | null>(null);
  const [fav, setFav] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/mb/detail/${subjectId}`)
      .then((r) => r.json())
      .then((j: DetailData) => {
        if (cancelled) return;
        if (!j.ok) {
          setData({ sid: subjectId, error: j.error || "Failed to load details." });
        } else {
          setData({ sid: subjectId, data: j });
        }
        setSeason(j.seasons?.[0]?.se ?? null);
        setFav(isFavorite(subjectId));
      })
      .catch((e) => {
        if (!cancelled) setData({ sid: subjectId, error: (e as Error).message });
      });
    return () => {
      cancelled = true;
    };
  }, [subjectId]);

  const s = data.data?.subject;
  const loading = data.sid !== subjectId || (!data.data && !data.error);
  const error = data.sid === subjectId ? data.error || "" : "";
  const eps = useMemo(() => data.data?.seasons?.find((x) => x.se === season)?.episodes || [], [data, season]);
  const isSeries = (data.data?.seasons?.length || 0) > 0 || (Number(s?.seNum) || 0) > 0;

  const cast = (s?.staffList || []).filter((c) => c.name).slice(0, 14);
  const dubNames = (s?.dubs || []).map((d) => d.lanName || d.lanCode || "").filter(Boolean);

  if (loading) return <Spinner label="Loading details…" />;
  if (error || !s) {
    return (
      <div className="flex flex-col items-center gap-3 px-6 py-24 text-center">
        <p className="text-sm font-semibold text-rose-400">Could not load this title</p>
        <p className="text-xs text-neutral-400">{error}</p>
        <button onClick={onBack} className="rounded-full bg-neutral-800 px-4 py-2 text-xs text-neutral-200 hover:bg-neutral-700">
          Go back
        </button>
      </div>
    );
  }

  const cover = getCover(s);
  const rating = s.imdbRatingValue || (s as unknown as { rate?: string }).rate;

  return (
    <div className="pb-6">
      {/* hero backdrop */}
      <div className="relative">
        <div className="relative h-56 w-full overflow-hidden sm:h-72">
          <PosterImg src={cover} alt={s.title || "backdrop"} eager className="h-full w-full scale-105 object-cover blur-[2px] brightness-[0.45]" />
        </div>
        <button
          onClick={onBack}
          className="absolute left-3 top-3 flex h-10 w-10 items-center justify-center rounded-full bg-black/60 text-white backdrop-blur hover:bg-black/80"
          aria-label="Back"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>

        <div className="absolute inset-x-0 -bottom-16 flex items-end gap-3 px-4">
          <div className="aspect-[2/3] w-24 shrink-0 overflow-hidden rounded-xl border border-neutral-700 bg-neutral-800 shadow-2xl sm:w-28">
            <PosterImg src={cover} alt={s.title || "poster"} className="h-full w-full object-cover" />
          </div>
          <div className="mb-1 min-w-0 flex-1 pb-1">
            <h1 className="line-clamp-2 text-base font-bold leading-snug text-white sm:text-lg">{s.title}</h1>
            <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[10px] text-neutral-300">
              {rating && Number(rating) > 0 && (
                <span className="rounded bg-amber-500/20 px-1.5 py-0.5 font-semibold text-amber-400">★ {Number(rating).toFixed(1)}</span>
              )}
              {s.releaseDate && <span className="rounded bg-neutral-700/60 px-1.5 py-0.5">{s.releaseDate.slice(0, 4)}</span>}
              {s.duration && <span className="rounded bg-neutral-700/60 px-1.5 py-0.5">{s.duration}</span>}
              {s.isCam && <span className="rounded bg-amber-700/80 px-1.5 py-0.5 font-bold text-white">CAM</span>}
              {/\[hindi|dubbed/i.test(s.title || "") && (
                <span className="rounded bg-orange-600/90 px-1.5 py-0.5 font-bold text-white">HINDI</span>
              )}
              {s.countryName && <span className="hidden rounded bg-neutral-700/60 px-1.5 py-0.5 sm:inline">{s.countryName}</span>}
            </div>
          </div>
        </div>
      </div>

      <div className="mt-20 px-4">
        {/* action buttons */}
        <div className="flex items-center gap-2.5">
          <button
            onClick={() => (isSeries ? onPlay(season || data.seasons[0]?.se || 1, 1) : onPlay(1, 1))}
            className="flex h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-rose-600 text-sm font-bold text-white shadow-lg shadow-rose-900/40 hover:bg-rose-500"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
              <path d="M8 5v14l11-7z" />
            </svg>
            Play {isSeries ? "S1 E1" : "Now"}
          </button>
          <button
            onClick={() => setFav(toggleFavorite({ subjectId, title: s.title || "", cover, subjectType: s.subjectType || 1 }))}
            className={`flex h-11 w-11 items-center justify-center rounded-xl border ${
              fav ? "border-rose-500 bg-rose-600/20 text-rose-400" : "border-neutral-700 bg-neutral-800/80 text-neutral-300"
            } hover:bg-neutral-700`}
            aria-label="Watchlist"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill={fav ? "currentColor" : "none"} stroke="currentColor" strokeWidth="2">
              <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0016.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 002 8.5c0 2.3 1.5 4.05 3 5.5l7 7z" />
            </svg>
          </button>
        </div>

        {/* meta line */}
        <p className="mt-3 text-[11px] text-neutral-400">
          {[s.genre, s.language, dubNames.length ? `Audio: ${dubNames.join(", ")}` : ""].filter(Boolean).join(" · ")}
        </p>

        {/* description */}
        <p className="mt-2.5 text-xs leading-relaxed text-neutral-300">
          {s.description?.trim() || "No synopsis available for this title."}
        </p>

        {/* cast */}
        {cast.length > 0 && (
          <div className="mt-5">
            <h3 className="mb-2 text-xs font-bold uppercase tracking-wider text-neutral-400">Cast & Crew</h3>
            <div className="flex gap-3 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              {cast.map((c, i) => (
                <div key={i} className="w-16 shrink-0 text-center">
                  <div className="h-16 w-16 overflow-hidden rounded-full bg-neutral-800">
                    <PosterImg
                      src={typeof c.avatar === "string" ? c.avatar : c.avatar?.url || ""}
                      alt={c.name || "cast"}
                      className="h-full w-full object-cover"
                    />
                  </div>
                  <p className="mt-1 line-clamp-1 text-[10px] text-neutral-300">{c.name}</p>
                  <p className="line-clamp-1 text-[9px] text-neutral-500">{c.role || "Actor"}</p>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* seasons + episodes */}
        {isSeries && (data.data?.seasons?.length || 0) > 0 && (
          <div className="mt-6">
            <div className="mb-2 flex items-center justify-between">
              <h3 className="text-xs font-bold uppercase tracking-wider text-neutral-400">Episodes</h3>
              {(data.data?.seasons?.length || 0) > 1 && (
                <div className="flex gap-1.5">
                  {data.data!.seasons.map((x) => (
                    <button
                      key={x.se}
                      onClick={() => setSeason(x.se)}
                      className={`rounded-full px-3 py-1 text-[11px] font-semibold ${
                        season === x.se ? "bg-rose-600 text-white" : "bg-neutral-800 text-neutral-300 hover:bg-neutral-700"
                      }`}
                    >
                      Season {x.se}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="grid grid-cols-5 gap-2 sm:grid-cols-8">
              {eps.slice(0, 150).map((e) => (
                <button
                  key={e}
                  onClick={() => onPlay(season || 1, e)}
                  className="flex h-9 items-center justify-center rounded-lg bg-neutral-800 text-xs font-medium text-neutral-200 hover:bg-rose-600 hover:text-white"
                >
                  {e}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* related */}
      {data.data?.related?.length > 0 && (
        <div className="px-0">
          <SectionRow title="More Like This" subjects={data.data.related} onPick={onPick} />
        </div>
      )}
    </div>
  );
}
