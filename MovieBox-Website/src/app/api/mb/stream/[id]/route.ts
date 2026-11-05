import { NextRequest, NextResponse } from "next/server";
import { resolveStreams } from "@/lib/stream-resolve";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  if (!id) return NextResponse.json({ ok: false, error: "missing id" }, { status: 400 });

  const sp = req.nextUrl.searchParams;
  const season = Math.max(0, parseInt(sp.get("se") || "1", 10) || 1);
  const episode = Math.max(0, parseInt(sp.get("ep") || "1", 10) || 1);
  const validate = sp.get("validate") !== "0";

  try {
    const result = await resolveStreams(id, season, episode, { validate });
    if (!result.candidates.length) {
      return NextResponse.json(
        {
          ok: false,
          error: "No playable stream found for this title right now.",
          subject: { subjectId: id, title: result.subject?.title, subjectType: result.subject?.subjectType },
          isSeries: result.isSeries,
          subtitles: result.subtitles,
        },
        { status: 404 },
      );
    }
    return NextResponse.json({
      ok: true,
      isSeries: result.isSeries,
      candidates: result.candidates,
      subtitles: result.subtitles,
      subject: { subjectId: id, title: result.subject?.title, subjectType: result.subject?.subjectType },
    });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message }, { status: 500 });
  }
}
