'use strict';

// Demo HTTP server using only Node built-ins.
// Run:  node server.js     then:  for i in $(seq 1 8); do curl -s -o /dev/null -w "%{http_code}\n" -H "x-api-key: alice" localhost:3000; done
const http = require('node:http');
const { KeyedRateLimiter } = require('./rateLimiters');
const { rateLimitMiddleware } = require('./middleware');

const limiter = new KeyedRateLimiter({
  configFor: (key) => key.startsWith('premium-')
    ? { algorithm: 'TOKEN_BUCKET', limit: 100, windowMs: 1000 }
    : { algorithm: 'TOKEN_BUCKET', limit: 5, windowMs: 1000 },
});

const rateLimit = rateLimitMiddleware(limiter, {
  // Key by API key when present, else by IP. Behind a load balancer, remoteAddress is the LB's IP!
  keyFor: (req) => req.headers['x-api-key'] ?? req.socket.remoteAddress,
});

const server = http.createServer((req, res) => {
  rateLimit(req, res, () => {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ ok: true }));
  });
});

const port = Number(process.env.PORT ?? 3000);
server.listen(port, () => console.log(`listening on http://localhost:${port}`));
