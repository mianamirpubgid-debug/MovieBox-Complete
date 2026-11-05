import { NextRequest, NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
};

export async function GET(req: NextRequest) {
  const target = req.nextUrl.searchParams.get("url") || "";
  if (!target || !/^https?:\/\//.test(target)) {
    return NextResponse.json({ error: "missing url" }, { status: 400 });
  }
  try {
    const upstream = await fetch(target, {
      headers: {
        "User-Agent": "MovieBox/4.0.02 (Android 14; Pixel 6)",
        Referer: "https://api.inmoviebox.com/",
      },
      cache: "no-store",
    });
    const ctype = upstream.headers.get("content-type") || "image/jpeg";
    if (!upstream.body) {
      return NextResponse.json({ error: "empty" }, { status: 502 });
    }
    return new NextResponse(upstream.body, {
      status: upstream.status,
      headers: {
        ...CORS_HEADERS,
        "Content-Type": ctype,
        "Cache-Control": "public, max-age=86400",
      },
    });
  } catch (e) {
    return NextResponse.json({ error: (e as Error).message }, { status: 502 });
  }
}
