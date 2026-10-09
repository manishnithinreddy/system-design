// Meeting-room booking in one ES module. Times are epoch milliseconds (UTC); a slot is the half-open [start, end).
export const slot = (start, end) => {
  if (!(start < end)) throw new RangeError(`start must be before end: ${start}..${end}`);
  return { start, end };
};
export const overlaps = (a, b) => a.start < b.end && b.start < a.end;

export function mergeIntervals(list) {
  const out = [];
  for (const s of [...list].sort((a, b) => a.start - b.start)) {
    const last = out[out.length - 1];
    if (last && s.start <= last.end) last.end = Math.max(last.end, s.end);
    else out.push({ ...s });
  }
  return out;
}

export function gaps(window, busy) {
  const out = [];
  let cursor = window.start;
  for (const b of mergeIntervals(busy)) {
    if (b.end <= window.start || b.start >= window.end) continue;
    if (b.start > cursor) out.push({ start: cursor, end: b.start });
    cursor = Math.max(cursor, b.end);
  }
  if (cursor < window.end) out.push({ start: cursor, end: window.end });
  return out;
}

// Bookings of one room, sorted by start. Because they never overlap, only the neighbour before and the
// neighbour after the insertion point can clash: binary search + two comparisons.
class RoomCalendar {
  items = [];
  #insertionIndex(start) { // first index whose start > start
    let lo = 0, hi = this.items.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (this.items[mid].slot.start <= start) lo = mid + 1; else hi = mid; }
    return lo;
  }
  conflict(s, now) {
    const i = this.#insertionIndex(s.start);
    for (const j of [i - 1, i]) {
      const b = this.items[j];
      if (b && !(b.holdUntil != null && b.holdUntil <= now) && overlaps(b.slot, s)) return b;
    }
    return null;
  }
  add(b, now) {
    this.items = this.items.filter((x) => !(x.holdUntil != null && x.holdUntil <= now)); // drop expired holds
    this.items.splice(this.#insertionIndex(b.slot.start), 0, b);
  }
  live(now) { return this.items.filter((x) => !(x.holdUntil != null && x.holdUntil <= now)); }
}

export class BookingService {
  rooms = new Map(); calendars = new Map(); nextId = 1;
  constructor(now = () => Date.now()) { this.now = now; }
  addRoom(room) { this.rooms.set(room.id, room); this.calendars.set(room.id, new RoomCalendar()); }

  // Single-threaded JS: check + insert run in one synchronous block, so no other booking can slip in between.
  // (With await between check and insert you WOULD need a lock. Keep the critical section synchronous.)
  place(roomId, userId, slots, holdUntil = null) {
    const cal = this.calendars.get(roomId);
    if (!cal) throw new Error(`unknown room ${roomId}`);
    const now = this.now();
    const conflicts = slots.map((s) => ({ requested: s, existing: cal.conflict(s, now) })).filter((c) => c.existing);
    if (conflicts.length) return { ok: false, booked: [], conflicts };
    const booked = slots.map((s) => ({ id: `b${this.nextId++}`, roomId, userId, slot: s, holdUntil }));
    booked.forEach((b) => cal.add(b, now));
    return { ok: true, booked, conflicts: [] };
  }
  book(roomId, userId, s) { return this.place(roomId, userId, [s]); }
  hold(roomId, userId, s, ttlMs) { return this.place(roomId, userId, [s], this.now() + ttlMs); }
  confirm(bookingId) {
    for (const cal of this.calendars.values()) {
      const b = cal.items.find((x) => x.id === bookingId);
      if (b) { if (b.holdUntil == null || b.holdUntil <= this.now()) return false; b.holdUntil = null; return true; }
    }
    return false;
  }
  cancel(bookingId) {
    for (const cal of this.calendars.values()) {
      const i = cal.items.findIndex((x) => x.id === bookingId);
      if (i >= 0) { cal.items.splice(i, 1); return true; }
    }
    return false;
  }
  bookingsIn(roomId, w) { return this.calendars.get(roomId).live(this.now()).filter((b) => overlaps(b.slot, w)); }
  availability(roomId, w) { return gaps(w, this.bookingsIn(roomId, w).map((b) => b.slot)); }
  #matching(minCap, features) {
    return [...this.rooms.values()]
      .filter((r) => r.capacity >= minCap && features.every((f) => r.features.includes(f)))
      .sort((a, b) => a.capacity - b.capacity || a.id.localeCompare(b.id));
  }
  bookAnyRoom(userId, minCap, features, s) {
    for (const r of this.#matching(minCap, features)) {
      const res = this.book(r.id, userId, s);
      if (res.ok) return res.booked[0];
    }
    return null;
  }
  firstFreeSlot(lengthMs, w, minCap = 1, features = []) {
    let best = null;
    for (const r of this.#matching(minCap, features)) {
      const g = this.availability(r.id, w).find((x) => x.end - x.start >= lengthMs);
      if (g && (!best || g.start < best.slot.start)) best = { room: r, slot: { start: g.start, end: g.start + lengthMs } };
    }
    return best;
  }
  firstCommonFreeSlot(roomIds, lengthMs, w) {
    const busy = roomIds.flatMap((id) => this.bookingsIn(id, w).map((b) => b.slot));
    const g = gaps(w, busy).find((x) => x.end - x.start >= lengthMs);
    return g ? { start: g.start, end: g.start + lengthMs } : null;
  }
  // Subset of RRULE: FREQ=DAILY|WEEKLY;INTERVAL=n;COUNT=n. Fixed-length steps in UTC; no time-zone/DST handling here
  // (the Java version expands in a ZoneId so wall-clock time survives DST).
  bookRecurring(roomId, userId, first, rrule) {
    const p = Object.fromEntries(rrule.split(';').map((kv) => kv.split('=')));
    if (!['DAILY', 'WEEKLY'].includes(p.FREQ) || !p.COUNT) throw new Error('unsupported rule: need FREQ=DAILY|WEEKLY and COUNT');
    const step = (p.FREQ === 'WEEKLY' ? 7 : 1) * Number(p.INTERVAL ?? 1) * 86_400_000;
    const slots = Array.from({ length: Number(p.COUNT) }, (_, i) => slot(first.start + i * step, first.end + i * step));
    return this.place(roomId, userId, slots);
  }
}

// Hotel: count per (type, night); sell up to floor(physical * overbookFactor).
export class HotelInventory {
  sold = new Map(); reservations = new Map(); nextId = 1;
  constructor(physical, overbookFactor, rateCents) { this.physical = physical; this.f = overbookFactor; this.rate = rateCents; }
  limit(type) { return Math.floor(this.physical[type] * this.f); }
  #nights(ci, co) { const n = []; for (let d = ci; d < co; d++) n.push(d); return n; } // day numbers
  available(type, night) { return this.limit(type) - (this.sold.get(`${type}|${night}`) ?? 0); }
  reserve(type, checkIn, checkOut, rooms = 1) {
    const nights = this.#nights(checkIn, checkOut);
    if (!nights.length) throw new RangeError('bad stay');
    if (nights.some((n) => this.available(type, n) < rooms)) return null;
    nights.forEach((n) => this.sold.set(`${type}|${n}`, (this.sold.get(`${type}|${n}`) ?? 0) + rooms));
    const r = { id: `r${this.nextId++}`, type, checkIn, checkOut, rooms, total: nights.reduce((s, n) => s + this.rate(n) * rooms, 0) };
    this.reservations.set(r.id, r);
    return r;
  }
}
