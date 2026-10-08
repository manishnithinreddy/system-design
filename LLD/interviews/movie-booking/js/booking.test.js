'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { BookingService, BookingError } = require('./booking');

const T0 = Date.parse('2026-10-08T10:00:00Z');
const SHOW = Date.parse('2026-10-10T18:30:00Z');
const MIN = 60_000;

function setup() {
  let t = T0;
  const clock = { now: () => t, advance: (ms) => { t += ms; } };
  const svc = new BookingService({ now: clock.now });
  const seats = [];
  for (const row of ['A', 'B', 'C']) for (let n = 1; n <= 5; n++) seats.push({ id: `${row}${n}`, type: row === 'C' ? 'RECLINER' : 'REGULAR' });
  svc.addShow({ id: 'S1', movie: 'Interstellar', startsAt: SHOW, seats, pricePaise: { REGULAR: 25000, RECLINER: 60000 } });
  return { svc, clock };
}

test('hold then confirm', () => {
  const { svc } = setup();
  const h = svc.holdSeats('S1', 'divya', ['A1', 'C2']);
  assert.equal(h.amountPaise, 85000);
  const b = svc.confirmBooking(h.id, 'upi-1');
  assert.deepEqual(b.seatIds, ['A1', 'C2']);
  assert.equal(svc.freeSeats('S1').length, 13);
});

test('all-or-nothing hold', () => {
  const { svc } = setup();
  svc.holdSeats('S1', 'divya', ['B3']);
  assert.throws(() => svc.holdSeats('S1', 'arjun', ['B2', 'B3', 'B4']), BookingError);
  assert.ok(['B2', 'B4'].every((s) => svc.freeSeats('S1').includes(s)));
});

test('expired hold: seats free, late confirm fails', () => {
  const { svc, clock } = setup();
  const slow = svc.holdSeats('S1', 'divya', ['A1', 'A2']);
  clock.advance(10 * MIN);
  svc.holdSeats('S1', 'arjun', ['A2']);
  assert.throws(() => svc.confirmBooking(slow.id, 'late'), /expired/);
});

test('confirm is idempotent; cancel refunds and frees', () => {
  const { svc, clock } = setup();
  const h = svc.holdSeats('S1', 'divya', ['C1']);
  const b1 = svc.confirmBooking(h.id, 'upi');
  assert.equal(svc.confirmBooking(h.id, 'upi'), b1);
  clock.advance(SHOW - T0 - 3 * 60 * MIN);       // 3 h before show
  assert.equal(svc.cancelBooking(b1.id), 30000); // 50%
  assert.ok(svc.freeSeats('S1').includes('C1'));
  assert.throws(() => svc.cancelBooking(b1.id), /already/);
});

test('validation', () => {
  const { svc } = setup();
  assert.throws(() => svc.holdSeats('S1', 'x', []), BookingError);
  assert.throws(() => svc.holdSeats('S1', 'x', ['A1', 'A1']), /duplicate/);
  assert.throws(() => svc.holdSeats('S1', 'x', ['Z9']), /no seat/);
});
