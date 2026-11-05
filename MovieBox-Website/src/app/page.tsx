"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  BannerCarousel,
  PosterCard,
  SectionRow,
  Skeleton,
  Spinner,
  type Subject,
} from "@/components/mb/cards";
import DetailView from "@/components/mb/detail";
import Player from "@/components/mb/player";
import { getFavorites, getHistory, getResume, clearHistory } from "@/lib/store";

type Tab = "home" | "movies" | "series" | "search" | "favorites";
type View = { kind: "tabs" } | { kind: "detail"; subjectId: string } | { kind: "player" };

type HomeData = {
  banners: { image: string; subjectId?: string; content?: string }[];
  sections: { title: string; subjects: Subject[] }[];
  rows: { key: string; title: string; subjects: Subject[] }[];
};

function toSubject(x: Record<string, unknown>): Subject {
  return x as unknown as Subject;
}

export default function Home() {
  const [tab, setTab] = useState<Tab>("home");
  const [view, setView] = useState<View>({ kind: "tabs" });
  const [playerInput, setPlayerInput] = useState<{
    subjectId: string;
    title: string;
    poster?: string;
    se: number;
    ep: number;
    seasonEpisodes?: { se: number; episodes: number[] }[];
    resumeSec: number;
  } | null>(null);

  const [homeData, setHomeData] = useState<HomeData | null>(null);
  const [homeLoading, setHomeLoading] = useState(true);

  // browse grid state
  const [gridTab, setGridTab] = useState<string>("type:movie");
  const [gridSubjects, setGridSubjects] = useState<Subject[]>([]);
  const [gridPage, setGridPage] = useState(1);
  const [gridLoading, setGridLoading] = useState(false);
  const [gridHasMore, setGridHasMore] = useState(true);

  // search state
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<Subject[]>([]);
  const [searching, setSearching] = useState(false);
  const [hotKeywords, setHotKeywords] = useState<string[]>([]);
  const [hotSubjects, setHotSubjects] = useState<Subject[]>([]);
  const [history, setHistory] = useState<ReturnType<typeof getHistory>>([]);
  const [favorites, setFavorites] = useState<ReturnType<typeof getFavorites>>([]);

  const refreshLocal = useCallback(() => {
    setHistory(getHistory());
    setFavorites(getFavorites());
  }, []);

  useEffect(() => {
    refreshLocal();
  }, [refreshLocal]);

  // load home
  useEffect(() => {
    if (homeData || homeLoading === false) return;
    fetch("/api/mb/home")
      .then((r) => r.json())
      .then((j) => {
        if (j.ok) setHomeData({ banners: j.banners || [], sections: j.sections || [], rows: j.rows || [] });
        setHomeLoading(false);
      })
      .catch(() => setHomeLoading(false));
  }, [homeData, homeLoading]);

  // load hot searches for search tab
  useEffect(() => {
    if (tab !== "search" || hotKeywords.length) return;
    fetch("/api/mb/hot")
      .then((r) => r.json())
      .then((j) => {
        setHotKeywords(j.keywords || []);
        setHotSubjects((j.subjects || []).map(toSubject));
      })
      .catch(() => undefined);
  }, [tab, hotKeywords.length]);

  // browse grid loader (tab-operating APK categories or legacy genre lists)
  const loadGrid = useCallback(
    async (tabKey: string, page: number, append: boolean) => {
      setGridLoading(true);
      try {
        const res = await fetch(`/api/mb/list?${tabKey}&page=${page}&perPage=30`);
        const j = await res.json();
        const subjects = (j.subjects || []).map(toSubject);
        setGridHasMore(Boolean(j.hasMore));
        setGridSubjects((prev) => (append ? [...prev, ...subjects] : subjects));
        setGridPage(page);
      } catch {
        if (!append) setGridSubjects([]);
      } finally {
        setGridLoading(false);
      }
    },
    [],
  );

  const openTab = (t: Tab) => {
    setTab(t);
    setView({ kind: "tabs" });
    const tabKey = t === "movies" ? "tab=2" : t === "series" ? "tab=5" : "";
    if (tabKey && gridTab !== tabKey) {
      setGridTab(tabKey);
      loadGrid(tabKey, 1, false);
    }
  };

  const openDetail = useCallback((s: Subject) => {
    if (!s.subjectId) return;
    setView({ kind: "detail", subjectId: s.subjectId });
  }, []);

  const play = useCallback(
    (subject: Subject, se: number, ep: number, seasonEpisodes?: { se: number; episodes: number[] }[]) => {
      if (!subject.subjectId) return;
      const resume = getResume(subject.subjectId!);
      const resumeSec = resume && resume.se === se && resume.ep === ep ? resume.posSec : 0;
      setPlayerInput({
        subjectId: subject.subjectId,
        title: subject.title || "Playing",
        poster: (typeof subject.cover === "string" ? subject.cover : subject.cover?.url) || undefined,
        se,
        ep,
        seasonEpisodes,
        resumeSec,
      });
      setView({ kind: "player" });
    },
    [],
  );

  const doSearch = useCallback((q: string) => {
    setQuery(q);
    if (!q.trim()) {
      setSearchResults([]);
      return;
    }
    setSearching(true);
    // API caps pageSize at 20 — fetch two pages for better coverage
    Promise.all([
      fetch(`/api/mb/search?q=${encodeURIComponent(q)}&page=1&perPage=20`).then((r) => r.json()),
      fetch(`/api/mb/search?q=${encodeURIComponent(q)}&page=2&perPage=20`).then((r) => r.json()),
    ])
      .then(([p1, p2]) => {
        const seen = new Set<string>();
        const all = [...(p1.subjects || []), ...(p2.subjects || [])].map(toSubject).filter((s) => {
          const id = String(s.subjectId || "");
          if (!id || seen.has(id)) return false;
          seen.add(id);
          return true;
        });
        setSearchResults(all);
        setSearching(false);
      })
      .catch(() => setSearching(false));
  }, []);

  // player wrappers
  const onExitPlayer = () => {
    setPlayerInput(null);
    setView({ kind: "detail", subjectId: playerInput?.subjectId || "" });
    refreshLocal();
  };
  const onEpisodeChange = (se: number, ep: number) => {
    if (!playerInput) return;
    setPlayerInput({
      ...playerInput,
      se,
      ep,
      resumeSec: (() => {
        const r = getResume(playerInput.subjectId);
        return r && r.se === se && r.ep === ep ? r.posSec : 0;
      })(),
    });
  };

  const continueWatching = useMemo(() => history.slice(0, 12), [history]);

  // ---------- render helpers ----------
  const renderHome = () => {
    if (homeLoading && !homeData) {
      return (
        <div className="px-4 pt-4">
          <Skeleton className="aspect-[16/8] w-full rounded-2xl" />
          <div className="mt-5 flex gap-3 overflow-hidden">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="aspect-[2/3] w-[132px]" />
            ))}
          </div>
        </div>
      );
    }
    if (!homeData) {
      return (
        <div className="px-6 py-16 text-center text-sm text-neutral-400">
          Could not load home feed.{" "}
          <button className="text-rose-400 underline" onClick={() => (setHomeLoading(true), setHomeData(null))}>
            Retry
          </button>
        </div>
      );
    }
    return (
      <div className="pb-4">
        <BannerCarousel banners={homeData.banners} onPick={(id) => openDetail({ subjectId: id } as Subject)} />

        {continueWatching.length > 0 && (
          <SectionRow
            title="Continue Watching"
            subjects={continueWatching.map((h) => ({
              subjectId: h.subjectId,
              title: h.title,
              cover: h.cover,
              subjectType: h.subjectType,
            }))}
            onPick={(s) => {
              const h = history.find((x) => x.subjectId === s.subjectId);
              if (h) play(s, h.se, h.ep);
            }}
          />
        )}

        {homeData.rows.map((row) => (
          <SectionRow key={row.key} title={row.title} subjects={row.subjects} onPick={openDetail} />
        ))}
        {homeData.sections.map((sec, i) => (
          <SectionRow key={i} title={sec.title} subjects={sec.subjects} onPick={openDetail} />
        ))}
      </div>
    );
  };

  const renderGrid = () => (
    <div className="px-4 pt-4">
      <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
        {gridSubjects.map((s) => <PosterCard key={s.subjectId} s={s} onClick={() => openDetail(s)} />)}
        {gridLoading &&
          Array.from({ length: 12 }).map((_, i) => <Skeleton key={`sk-${i}`} className="aspect-[2/3] w-full" />)}
      </div>
      {gridHasMore && !gridLoading && (
        <button
          onClick={() => loadGrid(gridTab, gridPage + 1, true)}
          className="mx-auto mt-6 block rounded-full bg-neutral-800 px-6 py-2.5 text-xs font-semibold text-neutral-200 hover:bg-neutral-700"
        >
          Load More
        </button>
      )}
    </div>
  );

  const renderSearch = () => (
    <div className="px-4 pt-4">
      <div className="flex items-center gap-2 rounded-2xl bg-neutral-800 px-4 py-3">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-neutral-400">
          <circle cx="11" cy="11" r="8" />
          <path d="M21 21l-4.35-4.35" />
        </svg>
        <input
          value={query}
          onChange={(e) => doSearch(e.target.value)}
          placeholder="Search movies, series, actors…"
          className="w-full bg-transparent text-sm text-neutral-100 outline-none placeholder:text-neutral-500"
        />
        {query && (
          <button onClick={() => doSearch("")} className="text-neutral-400 hover:text-white" aria-label="Clear">
            ✕
          </button>
        )}
      </div>

      {!query && (
        <>
          {hotKeywords.length > 0 && (
            <div className="mt-4">
              <h3 className="mb-2 text-xs font-bold uppercase tracking-wider text-neutral-400">Hot Searches</h3>
              <div className="flex flex-wrap gap-2">
                {hotKeywords.map((k, i) => (
                  <button
                    key={i}
                    onClick={() => doSearch(k)}
                    className="rounded-full bg-neutral-800 px-3.5 py-1.5 text-xs text-neutral-200 hover:bg-neutral-700"
                  >
                    {k}
                  </button>
                ))}
              </div>
            </div>
          )}
          {hotSubjects.length > 0 && <SectionRow title="Trending Searches" subjects={hotSubjects} onPick={openDetail} />}
        </>
      )}

      {searching && <Spinner label="Searching…" />}
      {!searching && query && searchResults.length === 0 && (
        <p className="py-16 text-center text-sm text-neutral-400">No results for “{query}”</p>
      )}
      {!searching && searchResults.length > 0 && (
        <div className="mt-4 grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
          {searchResults.map((s) => (
            <PosterCard key={s.subjectId} s={s} onClick={() => openDetail(s)} />
          ))}
        </div>
      )}
    </div>
  );

  const renderFavorites = () => (
    <div className="px-4 pt-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-bold text-neutral-100">Watchlist</h2>
        {favorites.length > 0 && (
          <button
            onClick={() => {
              localStorage.removeItem("anyfilm_favorites");
              setFavorites([]);
            }}
            className="text-[11px] text-neutral-500 hover:text-rose-400"
          >
            Clear all
          </button>
        )}
      </div>
      {favorites.length === 0 ? (
        <div className="py-20 text-center text-sm text-neutral-400">
          <p className="mb-1 text-2xl">🤍</p>
          Your watchlist is empty. Tap the ♥ on any title.
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
          {favorites.map((f) => (
            <PosterCard
              key={f.subjectId}
              s={{ subjectId: f.subjectId, title: f.title, cover: f.cover, subjectType: f.subjectType }}
              onClick={() => openDetail({ subjectId: f.subjectId } as Subject)}
            />
          ))}
        </div>
      )}

      <div className="mb-3 mt-8 flex items-center justify-between">
        <h2 className="text-sm font-bold text-neutral-100">Watch History</h2>
        {history.length > 0 && (
          <button
            onClick={() => {
              clearHistory();
              setHistory([]);
            }}
            className="text-[11px] text-neutral-500 hover:text-rose-400"
          >
            Clear all
          </button>
        )}
      </div>
      {history.length === 0 ? (
        <p className="py-10 text-center text-sm text-neutral-400">Nothing watched yet.</p>
      ) : (
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
          {history.map((h) => (
            <PosterCard
              key={h.subjectId}
              s={{ subjectId: h.subjectId, title: h.title, cover: h.cover, subjectType: h.subjectType }}
              subtitle={h.subjectType === 2 ? `S${h.se} E${h.ep}` : undefined}
              progress={h.durSec > 0 ? h.posSec / h.durSec : 0}
              onClick={() =>
                play({ subjectId: h.subjectId, title: h.title, cover: h.cover } as Subject, h.se, h.ep)
              }
            />
          ))}
        </div>
      )}
    </div>
  );

  // ---------- shell ----------
  if (view.kind === "player" && playerInput) {
    return (
      <Player
        subjectId={playerInput.subjectId}
        title={playerInput.title}
        poster={playerInput.poster}
        se={playerInput.se}
        ep={playerInput.ep}
        seasonEpisodes={playerInput.seasonEpisodes}
        resumeSec={playerInput.resumeSec}
        onExit={onExitPlayer}
        onEpisodeChange={onEpisodeChange}
      />
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-[#0b0d12] text-neutral-100">
      {/* top bar */}
      <header className="sticky top-0 z-40 flex items-center justify-between border-b border-neutral-800/60 bg-[#0b0d12]/95 px-4 py-3 backdrop-blur">
        <button onClick={() => openTab("home")} className="flex items-center gap-2">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-rose-500 to-orange-500 text-xs font-black text-white">
            A
          </span>
          <span className="text-base font-extrabold tracking-tight">
            Any<span className="text-rose-500">Film</span>
          </span>
        </button>
        <div className="flex items-center gap-2">
          <span className="rounded-full border border-neutral-700 px-2 py-0.5 text-[10px] font-semibold text-neutral-400">
            MovieBox API
          </span>
          <button
            onClick={() => openTab("search")}
            className="flex h-9 w-9 items-center justify-center rounded-full bg-neutral-800 text-neutral-300 hover:bg-neutral-700"
            aria-label="Search"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="8" />
              <path d="M21 21l-4.35-4.35" />
            </svg>
          </button>
        </div>
      </header>

      {/* main */}
      <main className="flex-1 pb-20">
        {view.kind === "detail" ? (
          <DetailView
            subjectId={view.subjectId}
            onBack={() => setView({ kind: "tabs" })}
            onPlay={(se, ep) => {
              // need subject for title/poster — fetch via detail API
              fetch(`/api/mb/detail/${view.subjectId}`)
                .then((r) => r.json())
                .then((j) => {
                  const s = j.subject || {};
                  play(s as Subject, se, ep, j.seasons || []);
                })
                .catch(() => play({ subjectId: view.subjectId } as Subject, se, ep));
            }}
            onPick={(s) => openDetail(s)}
          />
        ) : tab === "home" ? (
          renderHome()
        ) : tab === "movies" ? (
          renderGrid()
        ) : tab === "series" ? (
          renderGrid()
        ) : tab === "search" ? (
          renderSearch()
        ) : (
          renderFavorites()
        )}
      </main>

      {/* bottom nav (APK-style) */}
      <nav className="fixed bottom-0 left-0 right-0 z-40 border-t border-neutral-800/70 bg-[#0b0d12]/97 pb-[env(safe-area-inset-bottom)] backdrop-blur">
        <div className="mx-auto flex max-w-3xl items-stretch justify-around">
          {(
            [
              { key: "home", label: "Home", icon: "M3 10.5L12 3l9 7.5V21a1 1 0 01-1 1h-5v-7h-6v7H4a1 1 0 01-1-1z" },
              { key: "movies", label: "Movies", icon: "M4 4h16v16H4zM4 9h16M4 14h16M9 4v16M14 4v16" },
              { key: "series", label: "Series", icon: "M3 7h18v12H3zM8 3l4 4 4-4" },
              { key: "search", label: "Search", icon: "M11 4a7 7 0 104.9 2.1L21 21" },
              { key: "favorites", label: "Watchlist", icon: "M12 21l-7-7a5.5 5.5 0 017-8 5.5 5.5 0 017 8z" },
            ] as { key: Tab; label: string; icon: string }[]
          ).map((it) => {
            const activeTab = view.kind === "tabs" && tab === it.key;
            return (
              <button
                key={it.key}
                onClick={() => openTab(it.key)}
                className={`flex min-w-[56px] flex-col items-center gap-0.5 px-2 py-2.5 text-[10px] font-medium ${
                  activeTab ? "text-rose-500" : "text-neutral-500 hover:text-neutral-300"
                }`}
                aria-current={activeTab ? "page" : undefined}
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d={it.icon} />
                </svg>
                {it.label}
              </button>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
