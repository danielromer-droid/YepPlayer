// Optional public service metadata endpoint. NEVER forward Xtream credentials or video through this Worker.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const headers = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=300', 'access-control-allow-origin': '*' };
    if (url.pathname === '/health') return new Response(JSON.stringify({ ok: true, service: 'YepPlayer', version: '0.1.0', timestamp: new Date().toISOString() }), { headers });
    if (url.pathname === '/config') return new Response(JSON.stringify({ app: 'YepPlayer', minimumVersion: '0.1.0', message: 'Use authorised sources only.' }), { headers });
    return new Response(JSON.stringify({ error: 'Not found' }), { status: 404, headers });
  }
};
