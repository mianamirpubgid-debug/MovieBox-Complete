import { NextRequest, NextResponse } from "next/server";
import { getGenreTop, getTabOperating } from "@/lib/moviebox";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type AnyRec = Record<string, unknown>;

function asSubject(x: unknown): AnyRec | null {
  if (!x || typeof x !== "object") return null;
  const o = x as AnyRec;
  const cand = (o.info || o.subject || o) as AnyRec;
  if (cand && typeof cand === "object" && cand.subjectId) return cand;
  return null;
}

/**
 * Browse grid items.
 *  - tab=<number>  → APK category tab via tab-operating (Hindi-tagged IN content)
 *  - type=<string> → legacy genre-top listing (movie/tv/trending/ugc/sports)
 */
export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const tabId = parseInt(sp.get("tab") || "", 10);
  const page = Math.max(1, parseInt(sp.get("page") || "1", 10) || 1);
  const perPage = Math.min(50, Math.max(10, parseInt(sp.get("perPage") || "30", 10) || 30));

  try {
    if (Number.isFinite(tabId) && tabId > 0) {
      const res = await getTabOperating(tabId, page);
      const items = ((res.data || {}) as { items?: AnyRec[] }).items || [];
      const seen = new Set<string>();
      const subjects = items
        .flatMap((i) => (i.subjects as unknown[]) || [])
        .map(asSubject)
        .filter((s): s is AnyRec => {
          if (!s) return false;
          const id = String(s.subjectId);
          if (seen.has(id)) return false;
          seen.add(id);
          return true;
        });
      return NextResponse.json({
        ok: true,
        tab: tabId,
        page,
        perPage,
        hasMore: subjects.length >= perPage,
        subjects,
      });
    }

    const type = (sp.get("type") || "movie") as "trending" | "movie" | "tv" | "ugc" | "sports";
    const res = await getGenreTop(type, page, perPage);
    const data = (res.data || {}) as { list?: unknown[]; pager?: AnyRec };
    const subjects = (data.list || []).map(asSubject).filter((s): s is AnyRec => !!s);
    const pager = data.pager || {};
    return NextResponse.json({
      ok: true,
      page,
      perPage,
      hasMore: subjects.length >= perPage,
      subjects,
    });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message, subjects: [] }, { status: 500 });
  }
}
