import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Game, Position, START_FEN, legalMoves, perft, uci } from './chess.js';

const ucis = (fen) => legalMoves(Position.fromFen(fen)).map(uci);
const from = (fen, sq) => ucis(fen).filter((m) => m.startsWith(sq)).sort();

test('start position: 20 legal moves, knight and pawn moves', () => {
  assert.equal(ucis(START_FEN).length, 20);
  assert.deepEqual(from(START_FEN, 'b1'), ['b1a3', 'b1c3']);
  assert.deepEqual(from(START_FEN, 'a1'), []); // boxed in by its own pieces
});

test('rook is blocked by the first piece and may capture an enemy one', () => {
  const along = from('4k3/8/8/8/R2p4/8/8/4K3 w - - 0 1', 'a4').filter((m) => m.endsWith('4'));
  assert.deepEqual(along, ['a4b4', 'a4c4', 'a4d4']);
});

test('a pinned piece cannot move; a move into check is refused', () => {
  assert.deepEqual(from('4r1k1/8/8/8/8/8/4B3/4K3 w - - 0 1', 'e2'), []);
  assert.ok(!ucis('7k/8/8/8/8/8/r7/4K3 w - - 0 1').includes('e1e2'));
});

test('castling: allowed, through attack forbidden, out of check forbidden', () => {
  assert.ok(ucis('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1').includes('e1g1'));
  assert.ok(!ucis('5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1').includes('e1g1'));
  assert.ok(ucis('5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1').includes('e1c1'));
  const checked = ucis('4r2k/8/8/8/8/8/8/R3K2R w KQ - 0 1');
  assert.ok(!checked.includes('e1g1') && !checked.includes('e1c1'));
});

test('castling moves the rook', () => {
  const g = new Game('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1');
  g.play('e1g1');
  assert.equal(g.fen, 'r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1');
});

test('en passant works, and is illegal when it exposes the king', () => {
  const g = new Game();
  for (const m of ['e2e4', 'a7a6', 'e4e5', 'd7d5']) g.play(m);
  g.play('e5d6');
  assert.equal(g.fen, 'rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3');
  assert.ok(!ucis('7k/8/8/K2pP2r/8/8/8/8 w - d6 0 1').includes('e5d6'));
});

test('promotion gives four choices and defaults to a queen', () => {
  const g = new Game('8/P6k/8/8/8/8/8/K7 w - - 0 1');
  assert.equal(g.legalMoves().filter((m) => m.from === 48).length, 4);
  g.play('a7a8');
  assert.equal(g.position.board[56], 'Q');
});

test("fool's mate is checkmate; stalemate position is stalemate", () => {
  const g = new Game();
  for (const m of ['f2f3', 'e7e5', 'g2g4', 'd8h4']) g.play(m);
  assert.equal(g.status(), 'checkmate');
  assert.throws(() => g.play('a2a3'), /game is over/);
  assert.equal(new Game('7k/5Q2/6K1/8/8/8/8/8 b - - 0 1').status(), 'stalemate');
});

test('threefold repetition and fifty-move rule', () => {
  const g = new Game();
  for (let i = 0; i < 2; i++) for (const m of ['g1f3', 'g8f6', 'f3g1', 'f6g8']) g.play(m);
  assert.equal(g.status(), 'draw-threefold');
  const h = new Game('4k3/8/8/8/8/8/8/R3K3 w - - 99 80');
  h.play('a1a2');
  assert.equal(h.status(), 'draw-fifty-move');
});

test('undo restores the exact FEN, redo replays; new move clears redo', () => {
  const g = new Game('r3k2r/1P6/8/3pP3/8/8/8/R3K2R w KQkq d6 0 1');
  const start = g.fen;
  for (const m of ['e5d6', 'e8g8', 'b7a8q', 'g8g7', 'e1c1']) g.play(m);
  const end = g.fen;
  while (g.undo());
  assert.equal(g.fen, start);
  while (g.redo());
  assert.equal(g.fen, end);
  g.undo(); g.play('e1d2');
  assert.equal(g.redo(), false);
});

test('perft from the start position: 20, 400, 8902, 197281', () => {
  const p = Position.fromFen(START_FEN);
  [20, 400, 8902, 197281].forEach((n, i) => assert.equal(perft(p, i + 1), n));
  assert.equal(p.toFen(), START_FEN);
});

test('perft Kiwipete 48, 2039, 97862 and endgame 14, 191, 2812, 43238', () => {
  const k = Position.fromFen('r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1');
  [48, 2039, 97862].forEach((n, i) => assert.equal(perft(k, i + 1), n));
  const e = Position.fromFen('8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1');
  [14, 191, 2812, 43238].forEach((n, i) => assert.equal(perft(e, i + 1), n));
});
