/**
 * map.js — a small, fully self-contained canvas renderer for rider
 * positions and the destination.
 *
 * Why not a real tile-based map here: a genuinely offline map needs
 * pre-downloaded vector/raster tiles, which only exist on-device (see
 * maps/offline/ and the MapLibre GL dependency in the Android build —
 * mobile/android/app/build.gradle.kts). This browser demo has no such
 * tile package to load, and fetching tiles from the internet would
 * misrepresent an "offline-first" app by silently depending on a
 * network connection. Instead this draws a simple grid + rider/
 * destination markers, auto-fit to whatever positions are live right
 * now — enough to validate the interaction design without pretending
 * to be a real basemap. Swap this module's `render()` body for a
 * MapLibre GL JS instance (still fully offline, reading local MBTiles)
 * to get real cartography in an on-device WebView build.
 */
(function (global) {
  let canvas, ctx;

  function init(canvasEl) {
    canvas = canvasEl;
    ctx = canvas.getContext("2d");
    const resize = () => {
      const rect = canvas.parentElement.getBoundingClientRect();
      canvas.width = rect.width * devicePixelRatio;
      canvas.height = rect.height * devicePixelRatio;
      canvas.style.width = rect.width + "px";
      canvas.style.height = rect.height + "px";
      ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);
  }

  function computeBounds(points) {
    const pad = 0.006;
    let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
    points.forEach((p) => {
      minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
      minLon = Math.min(minLon, p.lon); maxLon = Math.max(maxLon, p.lon);
    });
    if (!isFinite(minLat)) return { minLat: 0, maxLat: 1, minLon: 0, maxLon: 1 };
    return { minLat: minLat - pad, maxLat: maxLat + pad, minLon: minLon - pad, maxLon: maxLon + pad };
  }

  function project(lat, lon, bounds, w, h) {
    const x = ((lon - bounds.minLon) / (bounds.maxLon - bounds.minLon || 1)) * w;
    const y = h - ((lat - bounds.minLat) / (bounds.maxLat - bounds.minLat || 1)) * h;
    return [x, y];
  }

  function render(state) {
    if (!ctx) return;
    const w = canvas.width / devicePixelRatio, h = canvas.height / devicePixelRatio;
    ctx.clearRect(0, 0, w, h);

    // background
    ctx.fillStyle = "#0A100D";
    ctx.fillRect(0, 0, w, h);

    if (!state || !state.me) {
      ctx.fillStyle = "#5A6B64";
      ctx.font = "13px -apple-system, sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("No active ride", w / 2, h / 2);
      return;
    }

    const points = [state.me, ...state.riders];
    if (state.destination) points.push(state.destination);
    const bounds = computeBounds(points);

    // faint grid, stands in for a real basemap's graticule
    ctx.strokeStyle = "rgba(255,255,255,0.05)";
    ctx.lineWidth = 1;
    for (let i = 1; i < 8; i++) {
      const gx = (w / 8) * i, gy = (h / 8) * i;
      ctx.beginPath(); ctx.moveTo(gx, 0); ctx.lineTo(gx, h); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, gy); ctx.lineTo(w, gy); ctx.stroke();
    }

    // destination
    if (state.destination) {
      const [dx, dy] = project(state.destination.lat, state.destination.lon, bounds, w, h);
      ctx.font = "20px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("🏁", dx, dy);
    }

    // riders
    state.riders.forEach((r) => {
      const [x, y] = project(r.lat, r.lon, bounds, w, h);
      drawRiderMarker(x, y, r.color || "#E8A23D", r.displayName, r.connection);
    });

    // self, drawn last so it's always on top
    const [mx, my] = project(state.me.lat, state.me.lon, bounds, w, h);
    drawRiderMarker(mx, my, "#2BA79A", "You", "live", true);
  }

  function drawRiderMarker(x, y, color, label, connection, isSelf) {
    ctx.beginPath();
    ctx.arc(x, y, isSelf ? 8 : 6, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.globalAlpha = connection === "lost" ? 0.35 : 1;
    ctx.fill();
    ctx.globalAlpha = 1;
    if (isSelf) {
      ctx.strokeStyle = "#F4F6F2";
      ctx.lineWidth = 2;
      ctx.stroke();
    }
    ctx.fillStyle = "#F4F6F2";
    ctx.font = "11px -apple-system, sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(label, x, y - 12);
  }

  // -----------------------------------------------------------------------
  // QR mockup: a deterministic module-grid preview, NOT a scannable ISO/IEC
  // 18004 QR code. Generating a truly scannable code needs a real QR
  // encoder (on Android, e.g. ZXing) — this canvas only previews what
  // information the QR payload would carry, and is labeled as such in the
  // UI. Swap this for a real QR library call in production.
  // -----------------------------------------------------------------------
  function renderQrMockup(canvasEl, payloadText) {
    const ctx2 = canvasEl.getContext("2d");
    const size = canvasEl.width;
    const cells = 21;
    const cellSize = size / cells;
    ctx2.fillStyle = "#fff";
    ctx2.fillRect(0, 0, size, size);
    ctx2.fillStyle = "#0E1512";

    // deterministic pseudo-random module pattern seeded from the payload,
    // just so the same code always renders the same-looking pattern.
    let seed = 0;
    for (let i = 0; i < payloadText.length; i++) seed = (seed * 31 + payloadText.charCodeAt(i)) >>> 0;
    function next() { seed = (seed * 1103515245 + 12345) >>> 0; return (seed >>> 16) % 100; }

    for (let row = 0; row < cells; row++) {
      for (let col = 0; col < cells; col++) {
        const inFinder = (r, c) => (r < 7 && c < 7) || (r < 7 && c >= cells - 7) || (r >= cells - 7 && c < 7);
        if (inFinder(row, col)) continue;
        if (next() < 45) ctx2.fillRect(col * cellSize, row * cellSize, cellSize, cellSize);
      }
    }
    // finder squares (the three corner markers real QR codes use)
    [[0, 0], [0, cells - 7], [cells - 7, 0]].forEach(([r, c]) => {
      ctx2.fillRect(c * cellSize, r * cellSize, cellSize * 7, cellSize * 7);
      ctx2.fillStyle = "#fff";
      ctx2.fillRect((c + 1) * cellSize, (r + 1) * cellSize, cellSize * 5, cellSize * 5);
      ctx2.fillStyle = "#0E1512";
      ctx2.fillRect((c + 2) * cellSize, (r + 2) * cellSize, cellSize * 3, cellSize * 3);
    });
  }

  global.RidersLive = global.RidersLive || {};
  global.RidersLive.map = { init, render };
  global.RidersLive.renderQrMockup = renderQrMockup;
})(window);
