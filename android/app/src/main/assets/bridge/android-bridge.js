// Vektorwerk Android: leitet die API-Aufrufe der Weboberfläche an die Kotlin-Engine weiter.
// Die Oberfläche (index.html) ist dieselbe wie am PC; hier wird nur fetch('/api/…') ersetzt,
// Downloads landen in Download/Vektorwerk und per "Teilen" geschickte Bilder werden geladen.
(() => {
  const N = window.VwNative;
  if (!N) return;
  document.documentElement.classList.add('android');

  // Eigene Schrift: Manche Geräte (z. B. HyperOS) liefern für system-ui/sans-serif eine Serifenschrift.
  const font = document.createElement('style');
  font.textContent = `
    @font-face{font-family:"VW Sans";src:url(/bridge/fonts/NotoSans-Regular.woff2) format("woff2");font-weight:100 500;font-display:block}
    @font-face{font-family:"VW Sans";src:url(/bridge/fonts/NotoSans-Bold.woff2) format("woff2");font-weight:600 900;font-display:block}
    html.android body, html.android button, html.android input, html.android select, html.android textarea, html.android .badge{font-family:"VW Sans",sans-serif!important}`;
  document.head.appendChild(font);

  const pending = new Map();
  let seq = 0;

  const toB64 = blob => new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(String(fr.result).split(',')[1] || '');
    fr.onerror = () => rej(fr.error);
    fr.readAsDataURL(blob);
  });
  const fromB64 = (b64, type) => {
    const bin = atob(b64), u = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
    return new Blob([u], { type });
  };

  async function payload(init) {
    const body = init && init.body;
    if (body instanceof FormData) {
      const fields = {}, files = {};
      for (const [k, v] of body.entries()) {
        if (typeof v === 'string') fields[k] = v;
        else files[k] = { b64: await toB64(v), type: v.type || '', name: v.name || 'blob' };
      }
      return { fields, files };
    }
    if (typeof body === 'string') return { body };
    return {};
  }

  window.__vw = {
    done(id) {
      const p = pending.get(id);
      if (!p) return;
      pending.delete(id);
      try { p.res(JSON.parse(N.take(id))); } catch (e) { p.rej(e); }
    },
    async pullShared() {
      let list = [];
      try { list = JSON.parse(N.pendingShared() || '[]'); } catch (e) { return; }
      if (!list.length) return;
      const files = list.map(x => new File([fromB64(x.b64, x.type)], x.name, { type: x.type }));
      const go = () => (typeof addFiles === 'function' ? addFiles(files) : setTimeout(go, 200));
      go();
    },
  };

  const nativeFetch = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    if (url.origin !== location.origin || !url.pathname.startsWith('/api/')) return nativeFetch(input, init);
    const id = 'r' + (++seq);
    const method = ((init && init.method) || 'GET').toUpperCase();
    const body = JSON.stringify(await payload(init));
    const result = new Promise((res, rej) => pending.set(id, { res, rej }));
    N.request(id, method, url.pathname + url.search, body);
    const r = await result;
    const out = r.b64 != null ? fromB64(r.b64, r.type) : r.text;
    return new Response(out, { status: r.status, headers: Object.assign({ 'Content-Type': r.type }, r.headers || {}) });
  };

  async function nativeSave(blob, name) {
    try { N.save(name, blob.type || 'application/octet-stream', await toB64(blob), false); }
    catch (e) { alert('Speichern fehlgeschlagen: ' + e.message); }
  }

  // Downloads: WebView kann <a download> mit Blob-URLs nicht speichern -> native Ablage
  const nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.download && this.href.startsWith('blob:')) {
      const name = this.download;
      nativeFetch(this.href).then(r => r.blob()).then(b => nativeSave(b, name));
      return;
    }
    return nativeClick.call(this);
  };

  document.addEventListener('DOMContentLoaded', () => {
    if (typeof save === 'function') window.save = (blob, name) => nativeSave(blob, name);
    setTimeout(() => window.__vw.pullShared(), 400);
  });
})();
