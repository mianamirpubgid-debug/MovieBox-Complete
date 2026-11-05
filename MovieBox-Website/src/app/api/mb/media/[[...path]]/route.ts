import { NextRequest, NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 300;

const CDN_UA = "ExoPlayerLib/2.18.7";
const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, Content-Type, Origin",
  "Access-Control-Expose-Headers": "Content-Range, Content-Length, Accept-Ranges, Content-Type",
};

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: CORS_HEADERS });
}

function mediaHeaders(target: string, ck: string, rf: string, extraPath: string): { url: URL; headers: Record<string, string> } {
  let full = target;
  if (extraPath) {
    // segments appended after the proxy path (HLS/DASH relative URIs)
    full = target.endsWith("/") ? target + extraPath : `${target.replace(/\/$/, "")}/${extraPath}`;
  }
  const url = new URL(full);
  const headers: Record<string, string> = { "User-Agent": CDN_UA };
  if (ck) headers.Cookie = ck;
  if (rf) headers.Referer = rf;
  return { url, headers };
}

function proxyUrlFor(absolute: string, ck: string, rf: string): string {
  const p = new URLSearchParams({ url: absolute });
  if (ck) p.set("ck", ck);
  if (rf) p.set("rf", rf);
  return `/api/mb/media?${p.toString()}`;
}

/** Rewrite an HLS manifest so every child URI flows back through this proxy */
function rewriteHls(manifest: string, baseUrl: string, ck: string, rf: string): string {
  const lines = manifest.split(/\r?\n/);
  const out: string[] = [];
  const baseDir = baseUrl.replace(/[^/]*$/, ""); // strip filename
  for (const line of lines) {
    const t = line.trim();
    if (!t) {
      out.push(line);
      continue;
    }
    // #EXT-X-KEY:...URI="..."  / #EXT-X-MAP:URI="..." / #EXT-X-MEDIA ... URI="..."
    if (t.startsWith("#")) {
      if (t.includes("URI=\"")) {
        const rewritten = t.replace(/URI="([^"]+)"/g, (_m, uri: string) => {
          const abs = uri.startsWith("http") ? uri : new URL(uri, /^https?:/.test(uri) ? undefined : baseDir).toString();
          return `URI="${proxyUrlFor(abs, ck, rf)}"`;
        });
        out.push(rewritten);
      } else {
        out.push(line);
      }
      continue;
    }
    // plain URI line (segment or nested playlist)
    const abs = t.startsWith("http") ? t : new URL(t, baseDir).toString();
    out.push(proxyUrlFor(abs, ck, rf));
  }
  return out.join("\n");
}

/** SRT → WebVTT */
function srtToVtt(srt: string): string {
  const body = srt
    .replace(/\r+/g, "")
    .replace(/^\uFEFF/, "")
    .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
  return `WEBVTT\n\n${body}`;
}

export async function GET(req: NextRequest) {
  try {
    const sp = req.nextUrl.searchParams;
    const target = sp.get("url") || "";
    const ck = sp.get("ck") || "";
    const rf = sp.get("rf") || "";
    const dl = sp.get("dl") || "";
    const vtt = sp.get("vtt") === "1";
    // optional path appended after /api/mb/media/ (HLS segment relative URIs)
    const extraPath = (req.nextUrl.pathname.match(/^\/api\/mb\/media\/(.+)$/) || [])[1] || "";
    const decodedExtra = extraPath ? decodeURIComponent(extraPath) : "";

    if (!target || !/^https?:\/\//.test(target)) {
      return NextResponse.json({ error: "missing url" }, { status: 400 });
    }

    const { url, headers } = mediaHeaders(target, ck, rf, decodedExtra);
    const range = req.headers.get("range");
    if (range) headers.Range = range;

    const upstream = await fetch(url.toString(), {
      method: "GET",
      headers,
      redirect: "follow",
      cache: "no-store",
    });

    const ctype = upstream.headers.get("content-type") || "";
    const isM3u8 = url.pathname.toLowerCase().endsWith(".m3u8") || ctype.includes("mpegurl") || ctype.includes("vnd.apple");
    const isSrt = url.pathname.toLowerCase().endsWith(".srt") || ctype.includes("plain");

    // --- HLS manifest: rewrite child URIs ---
    if (isM3u8) {
      const text = await upstream.text();
      const rewritten = rewriteHls(text, url.toString(), ck, rf);
      return new NextResponse(rewritten, {
        status: upstream.status,
        headers: {
          ...CORS_HEADERS,
          "Content-Type": "application/vnd.apple.mpegurl",
          "Cache-Control": "no-store",
        },
      });
    }

    // --- subtitle: SRT → VTT ---
    if (vtt || (isSrt && !ctype.includes("video"))) {
      const text = await upstream.text();
      const vttText = text.trimStart().startsWith("WEBVTT") ? text : srtToVtt(text);
      return new NextResponse(vttText, {
        status: 200,
        headers: {
          ...CORS_HEADERS,
          "Content-Type": "text/vtt; charset=utf-8",
          "Cache-Control": "public, max-age=3600",
        },
      });
    }

    // --- binary pipe (video/audio/image) with Range support ---
    const outHeaders: Record<string, string> = {
      ...CORS_HEADERS,
      "Content-Type": ctype || "application/octet-stream",
      "Accept-Ranges": upstream.headers.get("accept-ranges") || "bytes",
      "Cache-Control": "no-store",
    };
    const cr = upstream.headers.get("content-range");
    if (cr) outHeaders["Content-Range"] = cr;
    const cl = upstream.headers.get("content-length");
    if (cl) outHeaders["Content-Length"] = cl;
    if (dl) {
      const safe = dl.replace(/[^\w.\- ]+/g, "_");
      outHeaders["Content-Disposition"] = `attachment; filename="${safe}"`;
    }

    if (!upstream.body) {
      return new NextResponse(null, { status: upstream.status, headers: outHeaders });
    }
    return new NextResponse(upstream.body, { status: upstream.status, headers: outHeaders });
  } catch (e) {
    return NextResponse.json({ error: (e as Error).message }, { status: 502 });
  }
}

export async function HEAD(req: NextRequest) {
  const res = await GET(req);
  return new NextResponse(null, { status: res.status, headers: res.headers });
}
