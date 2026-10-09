// Chess rules engine in one module. Squares are 0..63 (a1 = 0, h8 = 63). Pieces are FEN letters: 'P' white pawn, 'p' black pawn.
// Same design as the Java version: make/unmake with a memento, pseudo-legal moves filtered by king safety.
'use strict';

export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const WK = 1, WQ = 2, BK = 4, BQ = 8;
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]]; // 0-3 straight, 4-7 diagonal
const KNIGHT = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];

const file = (s) => s & 7;
const rank = (s) => s >> 3;
const on = (f, r) => f >= 0 && f < 8 && r >= 0 && r < 8;
const sq = (f, r) => r * 8 + f;
export const name = (s) => String.fromCharCode(97 + file(s)) + (rank(s) + 1);
export const parseSquare = (n) => sq(n.charCodeAt(0) - 97, n.charCodeAt(1) - 49);
const isWhite = (p) => p === p.toUpperCase();
const colorOf = (p) => (isWhite(p) ? 'w' : 'b');
const dirOf = (c) => (c === 'w' ? 1 : -1);
const is = (p, color, type) => p != null && colorOf(p) === color && p.toUpperCase() === type;

export class Position {
  constructor() {
    this.board = new Array(64).fill(null);
    this.turn = 'w'; this.castling = 0; this.ep = -1; this.halfmove = 0; this.fullmove = 1;
  }

  static fromFen(fen) {
    const t = fen.trim().split(/\s+/);
    const rows = t[0].split('/');
    if (rows.length !== 8) throw new Error('FEN needs 8 ranks');
    const p = new Position();
    rows.forEach((row, i) => {
      let f = 0;
      for (const ch of row) {
        if (/\d/.test(ch)) f += Number(ch); else p.board[sq(f++, 7 - i)] = ch;
      }
    });
    p.turn = t[1] === 'b' ? 'b' : 'w';
    for (const c of t[2] ?? '-') p.castling |= { K: WK, Q: WQ, k: BK, q: BQ }[c] ?? 0;
    p.ep = (t[3] ?? '-') === '-' ? -1 : parseSquare(t[3]);
    p.halfmove = Number(t[4] ?? 0); p.fullmove = Number(t[5] ?? 1);
    return p;
  }

  toFen() {
    let out = '';
    for (let r = 7; r >= 0; r--) {
      let empty = 0;
      for (let f = 0; f < 8; f++) {
        const x = this.board[sq(f, r)];
        if (x == null) { empty++; continue; }
        if (empty) { out += empty; empty = 0; }
        out += x;
      }
      if (empty) out += empty;
      if (r > 0) out += '/';
    }
    const c = (this.castling & WK ? 'K' : '') + (this.castling & WQ ? 'Q' : '') + (this.castling & BK ? 'k' : '') + (this.castling & BQ ? 'q' : '');
    return `${out} ${this.turn} ${c || '-'} ${this.ep < 0 ? '-' : name(this.ep)} ${this.halfmove} ${this.fullmove}`;
  }

  kingSquare(color) { return this.board.findIndex((x) => is(x, color, 'K')); }

  // Moving from/to these squares kills castling rights (king moved, rook moved, rook captured).
  static #lost(s) {
    return { 0: WQ, 7: WK, 4: WK | WQ, 56: BQ, 63: BK, 60: BK | BQ }[s] ?? 0;
  }

  // Executes the move and returns a memento that unmake() uses to restore the exact previous state.
  make(m) {
    const b = this.board;
    const piece = b[m.from];
    let captured = b[m.to];
    if (m.kind === 'ep') {
      const capSq = m.to - 8 * dirOf(colorOf(piece));
      captured = b[capSq]; b[capSq] = null;
    }
    const memento = { m, piece, captured, castling: this.castling, ep: this.ep, halfmove: this.halfmove, fullmove: this.fullmove };
    b[m.to] = m.promo ? (isWhite(piece) ? m.promo.toUpperCase() : m.promo.toLowerCase()) : piece;
    b[m.from] = null;
    if (m.kind === 'castle') {
      const king = m.to > m.from;
      const rf = king ? m.to + 1 : m.to - 2, rt = king ? m.to - 1 : m.to + 1;
      b[rt] = b[rf]; b[rf] = null;
    }
    this.castling &= ~Position.#lost(m.from) & ~Position.#lost(m.to);
    this.ep = m.kind === 'double' ? (m.from + m.to) / 2 : -1;
    this.halfmove = piece.toUpperCase() === 'P' || captured ? 0 : this.halfmove + 1;
    if (this.turn === 'b') this.fullmove++;
    this.turn = this.turn === 'w' ? 'b' : 'w';
    return memento;
  }

  unmake(u) {
    const { m } = u, b = this.board;
    this.turn = this.turn === 'w' ? 'b' : 'w';
    b[m.from] = u.piece; b[m.to] = null;
    if (m.kind === 'ep') b[m.to - 8 * dirOf(this.turn)] = u.captured; else b[m.to] = u.captured;
    if (m.kind === 'castle') {
      const king = m.to > m.from;
      const rf = king ? m.to + 1 : m.to - 2, rt = king ? m.to - 1 : m.to + 1;
      b[rf] = b[rt]; b[rt] = null;
    }
    this.castling = u.castling; this.ep = u.ep; this.halfmove = u.halfmove; this.fullmove = u.fullmove;
  }
}

export function attacked(p, s, by) {
  const f = file(s), r = rank(s), b = p.board;
  const pr = r - dirOf(by);
  for (const df of [-1, 1]) if (on(f + df, pr) && is(b[sq(f + df, pr)], by, 'P')) return true;
  for (const [df, dr] of KNIGHT) if (on(f + df, r + dr) && is(b[sq(f + df, r + dr)], by, 'N')) return true;
  for (const [df, dr] of DIRS) if (on(f + df, r + dr) && is(b[sq(f + df, r + dr)], by, 'K')) return true;
  for (let i = 0; i < 8; i++) {
    let cf = f + DIRS[i][0], cr = r + DIRS[i][1];
    while (on(cf, cr)) {
      const x = b[sq(cf, cr)];
      if (x != null) {
        if (colorOf(x) === by && ['Q', i < 4 ? 'R' : 'B'].includes(x.toUpperCase())) return true;
        break; // the first piece on a ray blocks everything behind it
      }
      cf += DIRS[i][0]; cr += DIRS[i][1];
    }
  }
  return false;
}

export const inCheck = (p, color) => attacked(p, p.kingSquare(color), color === 'w' ? 'b' : 'w');

export function pseudoLegal(p) {
  const out = [], me = p.turn, foe = me === 'w' ? 'b' : 'w', b = p.board;
  const add = (from, to, kind = 'normal', promo = null) => out.push({ from, to, kind, promo });
  const steps = (s, offsets) => {
    for (const [df, dr] of offsets) {
      const f = file(s) + df, r = rank(s) + dr;
      if (on(f, r) && (b[sq(f, r)] == null || colorOf(b[sq(f, r)]) !== me)) add(s, sq(f, r));
    }
  };
  const slide = (s, from, to) => {
    for (let i = from; i < to; i++) {
      let f = file(s) + DIRS[i][0], r = rank(s) + DIRS[i][1];
      while (on(f, r)) {
        const x = b[sq(f, r)];
        if (x == null) add(s, sq(f, r));
        else { if (colorOf(x) !== me) add(s, sq(f, r)); break; }
        f += DIRS[i][0]; r += DIRS[i][1];
      }
    }
  };
  const addPawn = (from, to, promotes) => {
    if (promotes) for (const t of 'qrbn') add(from, to, 'normal', t); else add(from, to);
  };
  for (let s = 0; s < 64; s++) {
    const x = b[s];
    if (x == null || colorOf(x) !== me) continue;
    switch (x.toUpperCase()) {
      case 'N': steps(s, KNIGHT); break;
      case 'R': slide(s, 0, 4); break;
      case 'B': slide(s, 4, 8); break;
      case 'Q': slide(s, 0, 8); break;
      case 'K': {
        steps(s, DIRS);
        const home = me === 'w' ? 4 : 60, kr = me === 'w' ? WK : BK, qr = me === 'w' ? WQ : BQ;
        if (s === home && (p.castling & (kr | qr)) && !attacked(p, home, foe)) {
          if ((p.castling & kr) && is(b[home + 3], me, 'R') && !b[home + 1] && !b[home + 2]
              && !attacked(p, home + 1, foe) && !attacked(p, home + 2, foe)) add(home, home + 2, 'castle');
          // queenside: b-file square must be empty but may be attacked (the king never crosses it)
          if ((p.castling & qr) && is(b[home - 4], me, 'R') && !b[home - 1] && !b[home - 2] && !b[home - 3]
              && !attacked(p, home - 1, foe) && !attacked(p, home - 2, foe)) add(home, home - 2, 'castle');
        }
        break;
      }
      case 'P': {
        const dir = dirOf(me), f = file(s), r = rank(s), r1 = r + dir;
        const start = me === 'w' ? 1 : 6, last = me === 'w' ? 7 : 0;
        if (!b[sq(f, r1)]) {
          addPawn(s, sq(f, r1), r1 === last);
          if (r === start && !b[sq(f, r + 2 * dir)]) add(s, sq(f, r + 2 * dir), 'double');
        }
        for (const df of [-1, 1]) {
          if (!on(f + df, r1)) continue;
          const t = sq(f + df, r1);
          if (b[t] != null && colorOf(b[t]) !== me) addPawn(s, t, r1 === last);
          else if (b[t] == null && t === p.ep) add(s, t, 'ep');
        }
        break;
      }
    }
  }
  return out;
}

export function legalMoves(p) {
  const me = p.turn;
  return pseudoLegal(p).filter((m) => {
    const u = p.make(m);
    const safe = !inCheck(p, me);
    p.unmake(u);
    return safe;
  });
}

export const uci = (m) => name(m.from) + name(m.to) + (m.promo ?? '');

export function perft(p, depth) {
  const moves = legalMoves(p);
  if (depth === 1) return moves.length;
  let n = 0;
  for (const m of moves) { const u = p.make(m); n += perft(p, depth - 1); p.unmake(u); }
  return n;
}

// A game with undo/redo, draw tracking (repetition key = FEN without the counters) and status.
export class Game {
  #pos; #history = []; #redo = []; #seen = new Map();
  constructor(fen = START_FEN) { this.#pos = Position.fromFen(fen); this.#count(1); }
  get position() { return this.#pos; }
  get fen() { return this.#pos.toFen(); }
  legalMoves() { return legalMoves(this.#pos); }
  #key() { return this.#pos.toFen().split(' ').slice(0, 4).join(' '); }
  #count(d) { this.#seen.set(this.#key(), (this.#seen.get(this.#key()) ?? 0) + d); }

  status() {
    const p = this.#pos;
    if (legalMoves(p).length === 0) return inCheck(p, p.turn) ? 'checkmate' : 'stalemate';
    if ((this.#seen.get(this.#key()) ?? 0) >= 3) return 'draw-threefold';
    if (p.halfmove >= 100) return 'draw-fifty-move';
    return 'ongoing';
  }

  // Accepts coordinate moves like "e2e4" or "e7e8q" (SAN is only implemented in the Java version).
  play(text) {
    if (this.status() !== 'ongoing') throw new Error(`game is over: ${this.status()}`);
    const legal = this.legalMoves();
    const m = legal.find((x) => uci(x) === text) ?? legal.find((x) => uci(x) === `${text}q`);
    if (!m) throw new Error(`illegal move: ${text}`);
    this.#apply(m); this.#redo = [];
    return m;
  }

  #apply(m) { this.#history.push(this.#pos.make(m)); this.#count(1); }
  undo() {
    if (!this.#history.length) return false;
    this.#count(-1);
    const u = this.#history.pop();
    this.#pos.unmake(u); this.#redo.push(u.m);
    return true;
  }
  redo() {
    if (!this.#redo.length) return false;
    this.#apply(this.#redo.pop());
    return true;
  }
}
