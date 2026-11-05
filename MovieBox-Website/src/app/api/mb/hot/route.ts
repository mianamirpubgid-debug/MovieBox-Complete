import { NextResponse } from "next/server";
import { getSearchRank } from "@/lib/moviebox";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    const res = await getSearchRank();
    const data = (res.data || {}) as {
      hot?: { name?: string; items?: Record<string, unknown>[] }[];
      everyoneSearch?: Record<string, unknown>[];
    };
    const keywords: string[] = [];
    const subjects: Record<string, unknown>[] = [];
    for (const h of data.hot || []) {
      for (const it of h.items || []) {
        if (it.title && it.subjectId) subjects.push(it);
        if (typeof it.keyword === "string") keywords.push(it.keyword);
      }
    }
    for (const e of data.everyoneSearch || []) {
      if (typeof e.keyword === "string") keywords.push(e.keyword);
    }
    return NextResponse.json({ ok: true, keywords: keywords.slice(0, 12), subjects: subjects.slice(0, 12) });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message, keywords: [], subjects: [] }, { status: 500 });
  }
}
