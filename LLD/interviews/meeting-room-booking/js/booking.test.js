import test from 'node:test';
import assert from 'node:assert/strict';
import { slot, overlaps, mergeIntervals, BookingService, HotelInventory } from './booking.js';

const H = 3_600_000;
const t = (h) => Date.UTC(2030, 0, 7) + h * H;
const s = (a, b) => slot(t(a), t(b));
const svc = (now) => {
  const x = new BookingService(now);
  x.addRoom({ id: 'A', capacity: 4, features: ['tv'] });
  x.addRoom({ id: 'B', capacity: 8, features: ['tv', 'vc'] });
  return x;
};

test('half-open overlap edge cases', () => {
  assert.equal(overlaps(s(9, 10), s(10, 11)), false); // touching
  assert.equal(overlaps(s(9, 11), s(10, 12)), true);  // partial
  assert.equal(overlaps(s(9, 12), s(10, 11)), true);  // containment
  assert.equal(overlaps(s(9, 10), s(9, 10)), true);   // identical
  assert.throws(() => s(10, 10), RangeError);
});

test('book, conflict names the blocker, touching allowed, cancel frees', () => {
  const x = svc();
  const a = x.book('A', 'ana', s(9, 10));
  assert.ok(a.ok);
  const r = x.book('A', 'ben', slot(t(9) + 30 * 60000, t(10) + 30 * 60000));
  assert.equal(r.ok, false);
  assert.equal(r.conflicts[0].existing.userId, 'ana');
  assert.ok(x.book('A', 'ben', s(10, 11)).ok);
  assert.ok(x.cancel(a.booked[0].id));
  assert.ok(x.book('A', 'cy', s(9, 10)).ok);
});

test('availability gaps and merge', () => {
  const x = svc();
  x.book('A', 'x', s(10, 11)); x.book('A', 'y', s(11, 12)); x.book('A', 'z', s(15, 16));
  assert.deepEqual(x.availability('A', s(9, 18)), [s(9, 10), s(12, 15), s(16, 18)]);
  assert.deepEqual(mergeIntervals([s(3, 5), s(1, 2), s(2, 4), s(6, 7)]), [s(1, 5), s(6, 7)]);
});

test('any room, first free slot, common free slot', () => {
  const x = svc();
  assert.equal(x.bookAnyRoom('u1', 3, [], s(9, 10)).roomId, 'A');
  assert.equal(x.bookAnyRoom('u2', 3, [], s(9, 10)).roomId, 'B');
  assert.equal(x.bookAnyRoom('u3', 3, [], s(9, 10)), null);
  x.book('A', 'x', s(10, 11));
  const f = x.firstFreeSlot(90 * 60000, s(9, 18));
  assert.deepEqual([f.room.id, f.slot.start], ['B', t(10)]);
  assert.deepEqual(x.firstCommonFreeSlot(['A', 'B'], H, s(9, 18)), s(11, 12));
});

test('recurring series: one clash rejects all and names it', () => {
  const x = svc();
  x.book('A', 'dee', slot(t(9) + 14 * 24 * H, t(10) + 14 * 24 * H));
  const r = x.bookRecurring('A', 'ana', s(9, 10), 'FREQ=WEEKLY;COUNT=4');
  assert.equal(r.ok, false);
  assert.equal(r.conflicts.length, 1);
  assert.equal(r.conflicts[0].requested.start, t(9) + 14 * 24 * H);
  assert.ok(x.book('A', 'zed', s(9, 10)).ok);
  assert.ok(x.bookRecurring('B', 'ana', s(9, 10), 'FREQ=DAILY;COUNT=3').ok);
});

test('holds expire with an injected clock', () => {
  let now = t(8);
  const x = svc(() => now);
  const h = x.hold('A', 'ana', s(9, 10), 5 * 60000).booked[0];
  assert.equal(x.book('A', 'ben', s(9, 10)).ok, false);
  now += 5 * 60000; // exactly at holdUntil
  assert.equal(x.confirm(h.id), false);
  assert.ok(x.book('A', 'ben', s(9, 10)).ok);
});

test('32 concurrent async bookings of the same slot: one winner', async () => {
  const x = svc();
  const res = await Promise.all(Array.from({ length: 32 }, async (_, i) => { await null; return x.book('A', `u${i}`, s(9, 10)).ok; }));
  assert.equal(res.filter(Boolean).length, 1);
});

test('random bookings never overlap (brute force)', () => {
  const x = svc();
  let seed = 7;
  const rnd = (n) => (seed = (seed * 1103515245 + 12345) % 2147483648) % n;
  for (let i = 0; i < 2000; i++) {
    const a = rnd(90), len = 1 + rnd(6);
    const r = x.book('A', 'u', slot(t(0) + a * 15 * 60000, t(0) + (a + len) * 15 * 60000));
    if (r.ok && rnd(4) === 0) x.cancel(r.booked[0].id);
  }
  const all = x.bookingsIn('A', s(0, 48));
  assert.ok(all.length > 5);
  for (let i = 0; i < all.length; i++) for (let j = i + 1; j < all.length; j++) assert.equal(overlaps(all[i].slot, all[j].slot), false);
});

test('hotel: all nights or nothing; overbooking limit 105 of 100', () => {
  const h = new HotelInventory({ STD: 2, DLX: 100 }, 1.05, () => 100);
  assert.ok(h.reserve('STD', 10, 12, 2)); // 1.05 * 2 floors to 2
  assert.equal(h.reserve('STD', 11, 13, 1), null);
  assert.equal(h.available('STD', 12), 2);
  let sold = 0;
  while (h.reserve('DLX', 10, 11)) sold++;
  assert.equal(sold, 105);
});
