const CACHE_NAME = "hearthsong-shell-v11";
const APP_FILES = ["./", "./index.html", "./manifest.json", "./icon-192.png", "./icon-512.png"];
const DSP_ASSETS = [
  "https://esm.sh/@soundtouchjs/formant-correction-worklet@2.1.1?bundle",
  "https://cdn.jsdelivr.net/npm/@soundtouchjs/formant-correction-worklet@2.1.1/.dist/formant-correction-processor.js"
];
self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(APP_FILES);
    await Promise.all(DSP_ASSETS.map(async url => {
      try {
        const response = await fetch(url, { mode: "cors" });
        if (response.ok) await cache.put(url, response);
      } catch (error) {
        // The app shell remains installable even if the DSP CDN is temporarily unavailable.
      }
    }));
  })());
  self.skipWaiting();
});
self.addEventListener("activate", event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key)))));
  self.clients.claim();
});
self.addEventListener("fetch", event => {
  const request = event.request, url = new URL(request.url);
  if (request.method !== "GET") return;
  const isDspAsset = DSP_ASSETS.includes(request.url);
  if (isDspAsset) {
    event.respondWith(caches.match(request).then(cached => cached || fetch(request).then(response => {
      if (response && response.ok) caches.open(CACHE_NAME).then(cache => cache.put(request, response.clone()));
      return response;
    })));
    return;
  }
  if (url.origin !== self.location.origin) return;
  event.respondWith(caches.match(request).then(cached => {
    if (cached) return cached;
    return fetch(request).then(response => {
      if (response && response.ok) {
        const copy = response.clone();
        caches.open(CACHE_NAME).then(cache => cache.put(request, copy));
      }
      return response;
    }).catch(() => caches.match("./index.html"));
  }));
});
