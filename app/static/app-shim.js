// Denon Pilot - tryb aplikacji Android (samodzielny, bez serwera).
// Gdy strona dziala w aplikacji (window.AndroidApp istnieje), zapytania /api/* i
// "WebSocket" /ws trafiaja przez most do wbudowanego backendu (AvrBackend.java),
// ktory rozmawia z amplitunerem wprost. W zwyklej przegladarce plik nic nie robi.
(function () {
  if (!window.AndroidApp || !window.AndroidApp.api) return;
  document.documentElement.classList.add('in-app');

  const oczekujace = {};
  let seq = 0;
  window.__apiResult = function (id, tekst) {
    const res = oczekujace[id];
    if (res) { delete oczekujace[id]; res(tekst); }
  };

  const origFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : input.url;
    const u = new URL(url, location.href);
    if (u.origin !== location.origin || !u.pathname.startsWith('/api/')) return origFetch(input, init);
    const metoda = ((init && init.method) || 'GET').toUpperCase();
    const body = (init && init.body) ? String(init.body) : '';
    return new Promise(function (resolve) {
      const id = ++seq;
      oczekujace[id] = function (tekst) {
        resolve(new Response(tekst, { status: 200, headers: { 'Content-Type': 'application/json' } }));
      };
      window.AndroidApp.api(id, metoda, u.pathname + u.search, body);
    });
  };

  // "WebSocket" /ws: stan amplitunera wypychany z Javy
  const gniazda = new Set();
  function AppWS(url) {
    this.url = url;
    this.readyState = 0;
    gniazda.add(this);
    const self = this;
    setTimeout(function () {
      self.readyState = 1;
      if (self.onopen) self.onopen({});
      window.AndroidApp.wsOpen();
    }, 0);
  }
  AppWS.prototype.send = function () {};
  AppWS.prototype.close = function () {
    this.readyState = 3;
    gniazda.delete(this);
    if (this.onclose) this.onclose({});
  };
  const OrigWS = window.WebSocket;
  window.WebSocket = function (url, protokoly) {
    const path = new URL(url, location.href).pathname;
    return path === '/ws' ? new AppWS(url) : new OrigWS(url, protokoly);
  };
  window.__avrPush = function (json) {
    gniazda.forEach(function (g) { if (g.onmessage) g.onmessage({ data: json }); });
  };
})();
