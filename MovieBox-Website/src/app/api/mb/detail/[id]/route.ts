import { NextRequest, NextResponse } from "next/server";
import { getSeasonInfoV2, getSubject } from "@/lib/moviebox";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type AnyRec = Record<string, unknown>;

export async function GET(_req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  if (!id) return NextResponse.json({ ok: false, error: "missing id" }, { status: 400 });

  try {
    const res = await getSubject(id);
    const subject = ((res.data || {}) as AnyRec) || {};
    if (!subject.title) {
      return NextResponse.json({ ok: false, error: res.msg || "not found" }, { status: 404 });
    }

    // seasons/episodes for series
    let seasons: { se: number; episodes: number[] }[] = [];
    if (String(subject.subjectType) === "2" || (Number(subject.seNum) || 0) > 0) {
      try {
        const si = await getSeasonInfoV2(id);
        const raw = ((si.data || {}) as { seasons?: AnyRec[] }).seasons || [];
        seasons = raw
          .map((s) => {
            const se = Number(s.se || s.season || 1);
            let eps: number[] = [];
            const allEp = String(s.allEp || "");
            if (allEp) {
              eps = allEp
                .split(",")
                .map((x) => parseInt(x.trim(), 10))
                .filter((x) => x > 0);
            } else {
              const maxEp = Number(s.maxEp || 0);
              if (maxEp > 0) eps = Array.from({ length: maxEp }, (_, i) => i + 1);
            }
            return { se, episodes: eps };
          })
          .filter((s) => s.episodes.length > 0)
          .sort((a, b) => a.se - b.se);
      } catch {
        /* keep empty */
      }
    }

    // related titles (play-related-rec returns {pager, items})
    let related: AnyRec[] = [];
    try {
      const { getRelatedPlayback } = await import("@/lib/moviebox");
      const dr = await getRelatedPlayback(id);
      const dd = (dr.data || {}) as AnyRec;
      const list = (dd.items || dd.list || dd.subjects || []) as AnyRec[];
      related = list
        .map((x) => ((x as AnyRec).info || (x as AnyRec).subject || x) as AnyRec)
        .filter((x) => x && x.subjectId);
    } catch {
      /* keep empty */
    }

    return NextResponse.json({ ok: true, subject, seasons, related: related.slice(0, 12) });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message }, { status: 500 });
  }
}
