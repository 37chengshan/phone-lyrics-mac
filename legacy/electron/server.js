const http = require('node:http');
const os = require('node:os');

/** @typedef {{title:string,artist:string,album?:string,durationMs:number,positionMs:number,state:'playing'|'paused'|'stopped',source?:string,ts?:number}} NowPlaying */

class StatusServer {
  /**
   * @param {object} opts
   * @param {number} opts.port
   * @param {(np: NowPlaying) => void} opts.onNowPlaying
   * @param {() => void} [opts.onDisconnectHint]
   */
  constructor(opts) {
    this.port = opts.port;
    this.onNowPlaying = opts.onNowPlaying;
    this.server = null;
    this.lastPayload = null;
    this.lastReceivedAt = 0;
  }

  listLanAddresses() {
    const out = [];
    const ifs = os.networkInterfaces();
    for (const list of Object.values(ifs)) {
      for (const nic of list || []) {
        if (nic.family === 'IPv4' && !nic.internal) out.push(nic.address);
      }
    }
    return out;
  }

  async start() {
    await this.stop();
    this.server = http.createServer((req, res) => {
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
      if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
      }
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/api/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, connected: this.isConnected() }));
        return;
      }
      if (req.method === 'GET' && url.pathname === '/api/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: true,
            port: this.port,
            addresses: this.listLanAddresses(),
            connected: this.isConnected(),
            lastReceivedAt: this.lastReceivedAt,
            lastPayload: this.lastPayload,
          })
        );
        return;
      }
      if (req.method === 'POST' && url.pathname === '/api/now-playing') {
        let body = '';
        req.on('data', (c) => {
          body += c;
          if (body.length > 1_000_000) req.destroy();
        });
        req.on('end', () => {
          try {
            const np = JSON.parse(body);
            if (!np || typeof np.title !== 'string') {
              res.writeHead(400, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ ok: false, error: 'invalid payload' }));
              return;
            }
            const payload = {
              title: np.title,
              artist: np.artist || '',
              album: np.album || '',
              durationMs: Number(np.durationMs) || 0,
              positionMs: Number(np.positionMs) || 0,
              state: ['playing', 'paused', 'stopped'].includes(np.state)
                ? np.state
                : 'playing',
              source: np.source || 'unknown',
              ts: np.ts || Date.now(),
            };
            this.lastPayload = payload;
            this.lastReceivedAt = Date.now();
            this.onNowPlaying(payload);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: true }));
          } catch {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'bad json' }));
          }
        });
        return;
      }
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'not found' }));
    });
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(this.port, '0.0.0.0', resolve);
    });
    return this.port;
  }

  isConnected(timeoutMs = 8000) {
    if (!this.lastReceivedAt) return false;
    return Date.now() - this.lastReceivedAt < timeoutMs;
  }

  async stop() {
    if (!this.server) return;
    await new Promise((resolve) => this.server.close(resolve));
    this.server = null;
  }
}

module.exports = { StatusServer };
