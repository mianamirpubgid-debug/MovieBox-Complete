import { NextRequest, NextResponse } from "next/server";
import { searchV2 } from "@/lib/moviebox";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type AnyRec = Record<string, unknown>;

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const q = (sp.get("q") || "").trim();
  const page = Math.max(1, parseInt(sp.get("page") || "1", 10) || 1);
  const perPage = Math.min(20, Math.max(6, parseInt(sp.get("perPage") || "20", 10) || 20));
  const type = parseInt(sp.get("type") || "0", 10) || 0; // 0 all, 1 movie, 2 series

  if (!q) return NextResponse.json({ ok: true, q, subjects: [] });

  try {
    const res = await searchV2(q, page, perPage, type);
    const data = (res.data || {}) as { results?: AnyRec[] };
    const out: AnyRec[] = [];
    const seen = new Set<string>();
    for (const r of data.results || []) {
      const subs = (r.subjects as AnyRec[] | undefined) || [];
      for (const s of subs) {
        const sid = String(s.subjectId || "");
        if (!sid || seen.has(sid)) continue;
        seen.add(sid);
        out.push(s);
      }
    }
    return NextResponse.json({ ok: true, q, page, subjects: out, hasMore: out.length >= perPage });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message, subjects: [] }, { status: 500 });
  }
}
