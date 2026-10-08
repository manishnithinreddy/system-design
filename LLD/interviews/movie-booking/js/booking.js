'use strict';

// Same design as the Java version: per-show seat states, all-or-nothing holds with a TTL,
// idempotent confirm, refund policy as a function. Money in integer paise.
// No locks needed: each method runs to completion on Node's single thread (no await inside).
// In a real service, seat state lives in a DB/Redis, and THEN atomicity must come from there.

const State = Object.freeze({ AVAILABLE: 'AVAILABLE', HELD: 'HELD', BOOKED: 'BOOKED' });

const isFree = (s, now) => s.state === State.AVAILABLE || (s.state === State.HELD && now >= s.expiresAt);

const standardRefund = (booking, now, showStartsAt) => {
  const hoursBefore = (showStartsAt - now) / 3_600_000;
  if (hoursBefore >= 24) return booking.amountPaise;
  if (hoursBefore >= 2) return Math.floor(booking.amountPaise / 2);
  return 0;
};

class BookingError extends Error {}

class BookingService {
  #shows = new Map();     // showId -> { show, seats: Map<seatId, state> }
  #holds = new Map();
  #bookings = new Map();
  #bookingByHold = new Map();
  #seq = 0;

  constructor({ now = () => Date.now(), holdMs = 10 * 60_000, refund = standardRefund, maxSeats = 10 } = {}) {
    Object.assign(this, { now, holdMs, refund, maxSeats });
  }

  addShow(show) {
    if (this.#shows.has(show.id)) throw new BookingError(`show exists: ${show.id}`);
    const seats = new Map(show.seats.map((s) => [s.id, { state: State.AVAILABLE }]));
    this.#shows.set(show.id, { show, seats });
  }

  freeSeats(showId) {
    const { seats } = this.#show(showId);
    const now = this.now();
    return [...seats].filter(([, s]) => isFree(s, now)).map(([id]) => id).sort();
  }

  holdSeats(showId, userId, seatIds) {
    const { show, seats } = this.#show(showId);
    const now = this.now();
    if (now >= show.startsAt) throw new BookingError('show already started');
    if (!seatIds.length || seatIds.length > this.maxSeats) throw new BookingError(`choose 1-${this.maxSeats} seats`);
    if (new Set(seatIds).size !== seatIds.length) throw new BookingError('duplicate seat');
    const types = new Map(show.seats.map((s) => [s.id, s.type]));
    let amount = 0;
    for (const id of seatIds) {
      if (!types.has(id)) throw new BookingError(`no seat ${id}`);
      amount += show.pricePaise[types.get(id)];
    }
    if (!seatIds.every((id) => isFree(seats.get(id), now))) {       // check ALL...
      throw new BookingError('some of those seats were just taken');
    }
    const hold = Object.freeze({ id: `H${++this.#seq}`, showId, userId, seatIds: [...seatIds], expiresAt: now + this.holdMs, amountPaise: amount });
    for (const id of seatIds) seats.set(id, { state: State.HELD, holdId: hold.id, expiresAt: hold.expiresAt }); // ...then change all
    this.#holds.set(hold.id, hold);
    return hold;
  }

  confirmBooking(holdId, paymentRef) {
    const existing = this.#bookingByHold.get(holdId);
    if (existing) return existing;                                   // idempotent retry
    const hold = this.#holds.get(holdId);
    if (!hold) throw new BookingError(`unknown hold ${holdId}`);
    const { seats } = this.#show(hold.showId);
    const now = this.now();
    const valid = hold.seatIds.every((id) => {
      const s = seats.get(id);
      return s.state === State.HELD && s.holdId === holdId && now < s.expiresAt;
    });
    if (!valid) throw new BookingError('hold expired or released; please choose seats again');
    const booking = Object.freeze({ id: `B${++this.#seq}`, holdId, showId: hold.showId, userId: hold.userId, seatIds: hold.seatIds, amountPaise: hold.amountPaise, paymentRef, bookedAt: now, cancelled: false });
    for (const id of hold.seatIds) seats.set(id, { state: State.BOOKED, bookingId: booking.id });
    this.#bookings.set(booking.id, booking);
    this.#bookingByHold.set(holdId, booking);
    return booking;
  }

  releaseHold(holdId) {
    const hold = this.#holds.get(holdId);
    if (!hold) return;
    const { seats } = this.#show(hold.showId);
    for (const id of hold.seatIds) {
      const s = seats.get(id);
      if (s.state === State.HELD && s.holdId === holdId) seats.set(id, { state: State.AVAILABLE });
    }
  }

  cancelBooking(bookingId) {
    const b = this.#bookings.get(bookingId);
    if (!b) throw new BookingError(`unknown booking ${bookingId}`);
    if (b.cancelled) throw new BookingError('already cancelled');
    const { show, seats } = this.#show(b.showId);
    const now = this.now();
    if (now >= show.startsAt) throw new BookingError('show already started');
    for (const id of b.seatIds) seats.set(id, { state: State.AVAILABLE });
    this.#bookings.set(bookingId, Object.freeze({ ...b, cancelled: true }));
    return this.refund(b, now, show.startsAt);
  }

  #show(id) {
    const s = this.#shows.get(id);
    if (!s) throw new BookingError(`unknown show ${id}`);
    return s;
  }
}

module.exports = { BookingService, BookingError, standardRefund, State };
