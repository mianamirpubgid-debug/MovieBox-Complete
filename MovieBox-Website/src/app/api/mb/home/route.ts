import { NextResponse } from "next/server";
import {
  MovieSubject,
  coverUrl,
  getDailyRec,
  getRankingList,
  getTabOperating,
  getTopRec,
} from "@/lib/moviebox";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type AnyRec = Record<string, unknown>;

function asSubject(x: unknown): MovieSubject | null {
  if (!x || typeof x !== "object") return null;
  const o = x as AnyRec;
  const cand = (o.info || o.subject || o) as AnyRec;
  if (cand && typeof cand === "object" && (cand.subjectId || cand.title)) {
    return cand as MovieSubject;
  }
  return null;
}

function normSubjects(list: unknown[]): MovieSubject[] {
  return list.map(asSubject).filter((s): s is MovieSubject => !!s && !!s.subjectId);
}

export async function GET() {
  try {
    const [tab, topRec, tabMovies, tabSeries, tabAnime, tabWestern, ranking, daily] = await Promise.all([
      getTabOperating(1, 1).catch(() => null),
      getTopRec(1, 24).catch(() => null),
      // APK category tabs (IN cluster → [Hindi] content, same as app home)
      getTabOperating(2, 1).catch(() => null),   // Movies
      getTabOperating(5, 1).catch(() => null),   // Indian Web Series / TV
      getTabOperating(8, 1).catch(() => null),   // Anime
      getTabOperating(19, 1).catch(() => null),  // Western TV Series
      getRankingList(1).catch(() => null),
      getDailyRec(1, 24).catch(() => null),
    ]);

    // banners + curated sections from tab-operating
    const banners: { image: string; subjectId?: string; content?: string }[] = [];
    const sections: { title: string; subjects: MovieSubject[] }[] = [];
    const seenSectionIds = new Set<string>();
    const tabItems = ((tab?.data || {}) as { items?: AnyRec[] }).items || [];
    for (const item of tabItems) {
      const type = String(item.type || "");
      if (type === "BANNER") {
        const bl = ((item.banner as AnyRec | undefined)?.banners as AnyRec[] | undefined) || [];
        for (const b of bl) {
          const img = coverUrl(b.image as MovieSubject["cover"]);
          const sid = String(b.subjectId || "0");
          if (img && sid && sid !== "0") banners.push({ image: img, subjectId: sid, content: String(b.content || "") });
        }
      } else if (type.startsWith("SUBJECTS") || type === "RANKING_LIST_MULTI_TAB") {
        const subjects = normSubjects((item.subjects as unknown[]) || []).filter((s) => {
          if (seenSectionIds.has(s.subjectId!)) return false;
          seenSectionIds.add(s.subjectId!);
          return true;
        });
        const title = String(item.title || "Section");
        if (subjects.length >= 3 && sections.length < 6) {
          sections.push({ title, subjects: subjects.slice(0, 18) });
        }
      }
    }

    const trending = normSubjects(((topRec?.data || {}) as { items?: unknown[] }).items || []);
    const movies = normSubjects(((tabMovies?.data || {}) as { items?: unknown[] }).items?.flatMap((i) => (i.subjects as unknown[]) || []) || []);
    const tv = normSubjects(((tabSeries?.data || {}) as { items?: unknown[] }).items?.flatMap((i) => (i.subjects as unknown[]) || []) || []);
    const anime = normSubjects(((tabAnime?.data || {}) as { items?: unknown[] }).items?.flatMap((i) => (i.subjects as unknown[]) || []) || []);
    const western = normSubjects(((tabWestern?.data || {}) as { items?: unknown[] }).items?.flatMap((i) => (i.subjects as unknown[]) || []) || []);
    const rankSubjects = normSubjects(((ranking?.data || {}) as { subjects?: unknown[] }).subjects || []);
    const dailyList = normSubjects(((daily?.data || {}) as { list?: unknown[] }).list || []);

    // Hindi dubbed bucket — titles carry "[Hindi]" / "Hindi Dubbed" tags on the IN cluster
    const isHindi = (s: MovieSubject) => /\[hindi|dubbed|hindi dub/i.test(s.title || "");
    const seen = new Set<string>();
    const hindiAll = [...rankSubjects, ...movies, ...dailyList, ...trending].filter((s) => {
      if (!isHindi(s) || seen.has(s.subjectId!)) return false;
      seen.add(s.subjectId!);
      return true;
    });

    const dedupe = (list: MovieSubject[]) => {
      const s = new Set<string>();
      return list.filter((x) => (s.has(x.subjectId!) ? false : (s.add(x.subjectId!), true)));
    };

    return NextResponse.json({
      ok: true,
      banners: banners.slice(0, 12),
      sections,
      rows: [
        { key: "hindi", title: "Hindi Dubbed 🇮🇳", subjects: dedupe(hindiAll).slice(0, 20) },
        { key: "movies", title: "Trending Movies", subjects: dedupe(movies).slice(0, 20) },
        { key: "rank", title: "Top Rankings", subjects: dedupe(rankSubjects).slice(0, 20) },
        { key: "tv", title: "Indian Web Series", subjects: dedupe(tv).slice(0, 20) },
        { key: "trending", title: "Trending Now", subjects: dedupe(trending).slice(0, 20) },
        { key: "western", title: "Hollywood Series", subjects: dedupe(western).slice(0, 20) },
        { key: "anime", title: "Anime", subjects: dedupe(anime).slice(0, 20) },
        { key: "daily", title: "Daily Picks", subjects: dedupe(dailyList).slice(0, 20) },
      ],
    });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message }, { status: 500 });
  }
}
