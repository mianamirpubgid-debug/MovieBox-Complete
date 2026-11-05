"use client";

import { useEffect, useRef, useState } from "react";

export type Subject = {
  subjectId?: string;
  subjectType?: number;
  title?: string;
  description?: string;
  releaseDate?: string;
  duration?: string;
  durationSeconds?: number;
  genre?: string;
  countryName?: string;
  language?: string;
  imdbRatingValue?: string;
  rate?: string;
  cover?: { url?: string } | string | null;
  ops?: { cornerBadge?: string } | null;
  cornerBadge?: string;
};

export function getCover(s?: Subject | { cover?: { url?: string } | string | null } | null): string {
  const c = s?.cover;
  if (!c) return "";
  if (typeof c === "string") return c;
  return c.url || "";
}

/** poster <img> with automatic proxy fallback */
export function PosterImg({
  src,
  alt,
  className,
  eager,
}: {
  src: string;
  alt: string;
  className?: string;
  eager?: boolean;
}) {
  const [st, setSt] = useState<{ forSrc: string; cur: string; dead: boolean }>(() => ({
    forSrc: src,
    cur: src,
    dead: !src,
  }));
  // reset derived state during render when src changes (React-endorsed pattern)
  if (st.forSrc !== src) {
    setSt({ forSrc: src, cur: src, dead: !src });
  }
  const cur = st.forSrc === src ? st.cur : src;
  const dead = st.forSrc === src ? st.dead : !src;
  if (dead) {
    return (
      <div className={`flex items-center justify-center bg-neutral-800 text-neutral-600 ${className || ""}`}>
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="M10 9.5v5l4.5-2.5z" fill="currentColor" stroke="none" />
        </svg>
      </div>
    );
  }
  return (
    <img
      src={cur}
      alt={alt}
      className={className}
      loading={eager ? "eager" : "lazy"}
      referrerPolicy="no-referrer"
      onError={() => {
        if (!cur.startsWith("/api/mb/img") && cur === src) {
          setSt((s) => ({ ...s, cur: `/api/mb/img?url=${encodeURIComponent(src)}` }));
        } else {
          setSt((s) => ({ ...s, dead: true }));
        }
      }}
    />
  );
}

export function QualityBadge({ s }: { s?: Subject | null }) {
  const title = (s?.title || "").toUpperCase();
  const tags: string[] = [];
  if (/\[HINDI|DUBBED|HINDI DUB/.test(title)) tags.push("HINDI");
  if (/\bCAM\b|\[CAM\]/.test(title)) tags.push("CAM");
  if (/\bHD\b|1080|720/.test(title)) tags.push("HD");
  const badge = tags[0];
  if (!badge) return null;
  const color =
    badge === "HINDI" ? "bg-orange-600/90" : badge === "CAM" ? "bg-amber-700/90" : "bg-emerald-700/90";
  return (
    <span className={`${color} absolute left-1.5 top-1.5 z-10 rounded-md px-1.5 py-0.5 text-[9px] font-bold tracking-wide text-white`}>
      {badge}
    </span>
  );
}

export function PosterCard({
  s,
  onClick,
  progress,
  subtitle,
}: {
  s: Subject;
  onClick?: () => void;
  progress?: number;
  subtitle?: string;
}) {
  const rating = s.imdbRatingValue || s.rate;
  return (
    <button
      onClick={onClick}
      className="group relative w-[108px] shrink-0 text-left sm:w-[132px]"
      aria-label={s.title || "title"}
    >
      <div className="relative aspect-[2/3] w-full overflow-hidden rounded-xl bg-neutral-800 shadow-lg transition-transform duration-200 group-hover:scale-[1.03]">
        <QualityBadge s={s} />
        <PosterImg src={getCover(s)} alt={s.title || "poster"} className="h-full w-full object-cover" />
        {typeof progress === "number" && progress > 0 && (
          <div className="absolute bottom-0 left-0 right-0 h-1 bg-black/60">
            <div className="h-full bg-rose-500" style={{ width: `${Math.min(100, progress * 100)}%` }} />
          </div>
        )}
        {rating && Number(rating) > 0 && (
          <span className="absolute bottom-1.5 right-1.5 rounded-md bg-black/70 px-1.5 py-0.5 text-[9px] font-semibold text-amber-400">
            ★ {Number(rating).toFixed(1)}
          </span>
        )}
      </div>
      <p className="mt-1.5 line-clamp-2 text-[11px] font-medium leading-tight text-neutral-200 sm:text-xs">
        {s.title || "Untitled"}
      </p>
      <p className="line-clamp-1 text-[10px] text-neutral-500">{subtitle || s.releaseDate?.slice(0, 4) || s.genre || ""}</p>
    </button>
  );
}

export function SectionRow({
  title,
  subjects,
  onPick,
  seeAll,
}: {
  title: string;
  subjects: Subject[];
  onPick?: (s: Subject) => void;
  seeAll?: () => void;
}) {
  if (!subjects.length) return null;
  return (
    <section className="mt-5">
      <div className="mb-2 flex items-center justify-between px-4">
        <h2 className="text-sm font-bold text-neutral-100 sm:text-base">{title}</h2>
        {seeAll && (
          <button onClick={seeAll} className="text-[11px] font-medium text-rose-400 hover:text-rose-300">
            See All ›
          </button>
        )}
      </div>
      <div className="flex gap-3 overflow-x-auto px-4 pb-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {subjects.map((s) => (
          <PosterCard key={s.subjectId} s={s} onClick={() => onPick?.(s)} />
        ))}
      </div>
    </section>
  );
}

export function BannerCarousel({
  banners,
  onPick,
}: {
  banners: { image: string; subjectId?: string; content?: string }[];
  onPick?: (subjectId: string) => void;
}) {
  const [idx, setIdx] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (banners.length < 2) return;
    timer.current = setInterval(() => setIdx((i) => (i + 1) % banners.length), 4500);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [banners.length]);

  if (!banners.length) return null;
  return (
    <div className="relative mx-4 mt-3 overflow-hidden rounded-2xl">
      <div className="aspect-[16/8] w-full sm:aspect-[16/7]">
        {banners.map((b, i) => (
          <button
            key={i}
            onClick={() => b.subjectId && onPick?.(b.subjectId)}
            className={`absolute inset-0 w-full transition-opacity duration-700 ${i === idx ? "opacity-100" : "pointer-events-none opacity-0"}`}
            aria-hidden={i !== idx}
          >
            <PosterImg src={b.image} alt={b.content || "banner"} eager={i === 0} className="h-full w-full object-cover" />
            {b.content && (
              <span className="absolute bottom-3 left-3 max-w-[70%] rounded-lg bg-black/60 px-2.5 py-1.5 text-xs font-semibold text-white backdrop-blur">
                {b.content}
              </span>
            )}
          </button>
        ))}
      </div>
      {banners.length > 1 && (
        <div className="absolute bottom-2 right-3 flex gap-1.5">
          {banners.map((_, i) => (
            <span key={i} className={`h-1.5 w-1.5 rounded-full ${i === idx ? "bg-white" : "bg-white/40"}`} />
          ))}
        </div>
      )}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={`animate-pulse rounded-xl bg-neutral-800/80 ${className || ""}`} />;
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-10">
      <div className="h-9 w-9 animate-spin rounded-full border-2 border-neutral-700 border-t-rose-500" />
      {label && <p className="text-xs text-neutral-400">{label}</p>}
    </div>
  );
}
