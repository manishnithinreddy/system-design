'use strict';

/**
 * Express-style middleware: (req, res, next).
 * Works with Express, or with the plain http server in server.js.
 */
function rateLimitMiddleware(keyedLimiter, { keyFor = (req) => req.socket.remoteAddress } = {}) {
  return function rateLimit(req, res, next) {
    const key = keyFor(req);
    if (keyedLimiter.tryAcquire(key)) return next();

    res.statusCode = 429;
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Retry-After', '1'); // seconds; a fuller design returns the real wait time
    res.end(JSON.stringify({ error: 'Too Many Requests' }));
  };
}

module.exports = { rateLimitMiddleware };
