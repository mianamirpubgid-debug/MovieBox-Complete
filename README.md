# 🎬 MovieBox — Complete Working Code (Website + API Backend + APK)

Ye aap ke movie website aur Android APK ka **pura working code** hai. Teen hisse hain:

## 📁 1. `MovieBox-Website/` — Main Movie Website (Next.js 16)

Yahi website hai jo aap ke liye bani thi — MovieBox jaisa UI:

- **Home page**: Banner carousel + poster rows (Movies, Web Series, Anime, Western TV) — bilkul APK jaisa
- **Movies / Series / Search tabs** — sab data MovieBox API se live aata hai
- **Detail page** — movie ka poster, story, episodes list
- **Watch page** — full video player (H.264 + H.265 dono), **koi VIP nahi, sab kuch play**
- Hindi dubbed / Indian content IN cluster se aata hai

### Chalane ka tareeqa (apne computer par):

```bash
cd MovieBox-Website
npm install        # ya: bun install
npm run dev        # phir browser: http://localhost:3000
```

Bas itna hi! `.env.local` ki zaroorat nahi — API base URLs aur signing
keys code ke andar built-in hain (`src/lib/moviebox.ts`).

## 📁 2. `MovieBox-API-Backend/` — FastAPI Backend (Reference)

Ye wo Python backend hai jo source repo (`moviebox-internal-api`) se mila —
API client, signing (X-Client-Token, x-tr-signature), stream resolver, aur
ek aur web dashboard:

```bash
cd MovieBox-API-Backend
pip install -r requirements.txt
python moviebox_api_server.py    # http://localhost:8000

# Dashboard (optional, alag terminal):
cd dashboard
npm install
npm run dev                      # http://localhost:3001
```

## 📁 3. `MovieBox-APK-Source/` — Android APK ka pura source code

**MovieBox HD** Android app (Java, koi external library nahi):

- Home: banner + poster sections (bilkul MovieBox app jaisa)
- Detail: poster, story, Hindi/English dub switch, episodes
- Player: **fullscreen landscape auto-rotate + immersive mode (notification panel hidden)**
- Search: movie/series search with poster grid
- Sab movies play — koi VIP nahi

### APK banane ka tareeqa (PC par):

```bash
# Zaroori: Android SDK build-tools 35 + platform android-34 + JDK 21
# build_apk.sh mein paths change karke:
bash build_apk.sh
# Output: MovieBox-HD.apk (signed)
```

## 📖 Documentation

`MovieBox-API-Backend/OFFICIAL_API_DOCUMENTATION.md` — MovieBox Pro internal
API ka pura documentation (headers, token handshake, signature, endpoints).

## ⚠️ Zaroori Notes

- Ye sirf **educational / personal use** ke liye hai — MovieBox Pro ka
  unofficial reverse-engineered API use hota hai, official app nahi.
- Streams CDN se directly aati hain; kabhi kabhi region/VPN ki zaroorat
  par sakti hai.
- Code deploy karne ke liye Vercel/Node.js hosting chalegi, lekin API
  headers ke sath chalna desktop/laptop par behtar hota hai.
