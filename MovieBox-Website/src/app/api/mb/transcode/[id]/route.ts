import { NextRequest, NextResponse } from "next/server";
import { resolveStreams } from "@/lib/stream-resolve";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 300;

/**
 * Server-side HEVC → H.264 realtime transcode fallback.
 * Used only when the browser cannot decode any resolved candidate (e.g. HEVC-only titles).
 * Outputs a progressively-playable fragmented MP4 (H.264/AAC).
 */
export async function GET(req: NextRequest, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  if (!id) return NextResponse.json({ ok: false, error: "missing id" }, { status: 400 });

  const sp = req.nextUrl.searchParams;
  const season = Math.max(1, parseInt(sp.get("se") || "1", 10) || 1);
  const episode = Math.max(1, parseInt(sp.get("ep") || "1", 10) || 1);
  const height = Math.min(720, Math.max(360, parseInt(sp.get("h") || "480", 10) || 480));

  try {
    const resolved = await resolveStreams(id, season, episode, { validate: true });
    const src = resolved.candidates[0];
    if (!src) {
      return NextResponse.json({ ok: false, error: "No source stream to transcode." }, { status: 404 });
    }

    const headers: Record<string, string> = { "User-Agent": "ExoPlayerLib/2.18.7" };
    if (src.cookie) headers.Cookie = src.cookie;

    const ffArgs = [
      "-hide_banner", "-loglevel", "error",
      "-fflags", "+genpts+discardcorrupt",
      "-i", src.url,
      "-map", "0:v:0", "-map", "0:a:0?",
      "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
      "-profile:v", "baseline", "-level", "3.1",
      "-vf", `scale=-2:${height}`,
      "-crf", "26",
      "-c:a", "aac", "-b:a", "96k", "-ac", "2",
      "-movflags", "frag_keyframe+empty_moov+default_base_moof",
      "-f", "mp4",
      "pipe:1",
    ];

    const { spawn } = await import("child_process");
    const child = spawn("ffmpeg", ffArgs, { stdio: ["ignore", "pipe", "pipe"] });

    child.stderr.on("data", () => { /* drain; errors surface via close */ });

    req.signal.addEventListener("abort", () => {
      try { child.kill("SIGKILL"); } catch { /* already dead */ }
    });

    const { Readable } = await import("stream");
    const webStream = Readable.toWeb(child.stdout) as ReadableStream<Uint8Array>;

    return new Response(webStream, {
      status: 200,
      headers: {
        "Content-Type": "video/mp4",
        "Cache-Control": "no-store",
        "X-Transcode-Source": src.quality || "auto",
        "Access-Control-Allow-Origin": "*",
      },
    });
  } catch (e) {
    return NextResponse.json({ ok: false, error: (e as Error).message }, { status: 500 });
  }
}
