'use strict';

// Money is kept as INTEGER paise (1 rupee = 100 paise). Never use floats for money:
// 0.1 + 0.2 === 0.30000000000000004 in JavaScript. See LLD/libraries/js/money-and-numbers-in-js.md

const SpotSize = Object.freeze({ SMALL: 'SMALL', MEDIUM: 'MEDIUM', LARGE: 'LARGE' });

// Which spot sizes each vehicle type fits in, smallest (preferred) first.
const VehicleType = Object.freeze({
  MOTORCYCLE: Object.freeze({ name: 'MOTORCYCLE', fitsIn: [SpotSize.SMALL, SpotSize.MEDIUM, SpotSize.LARGE] }),
  CAR: Object.freeze({ name: 'CAR', fitsIn: [SpotSize.MEDIUM, SpotSize.LARGE] }),
  TRUCK: Object.freeze({ name: 'TRUCK', fitsIn: [SpotSize.LARGE] }),
});

class ParkingFullError extends Error {}
class InvalidTicketError extends Error {}

/** Value object: frozen, compared by content. */
function vehicle(licensePlate, type) {
  if (!licensePlate || !licensePlate.trim()) throw new Error('licensePlate is required');
  if (!Object.values(VehicleType).includes(type)) throw new Error('unknown vehicle type');
  return Object.freeze({ licensePlate: licensePlate.replace(/\s/g, '').toUpperCase(), type });
}

class ParkingSpot {
  #occupant = null;
  constructor(floor, number, size) {
    this.floor = floor;
    this.number = number;
    this.size = size;
    Object.freeze(this); // fields can't be reassigned; #occupant (private) still can
  }
  get id() { return `F${this.floor}-${this.size[0]}${this.number}`; }
  get isFree() { return this.#occupant === null; }
  occupy(v) {
    if (this.#occupant !== null) return false;
    this.#occupant = v;
    return true;
  }
  release() { this.#occupant = null; }
}

class ParkingFloor {
  #free = new Map(); // size -> array of free spots, sorted nearest-first
  #capacity = new Map();

  constructor(number, { small = 0, medium = 0, large = 0 } = {}) {
    this.number = number;
    let n = 1;
    for (const [size, count] of [[SpotSize.SMALL, small], [SpotSize.MEDIUM, medium], [SpotSize.LARGE, large]]) {
      const spots = [];
      for (let i = 0; i < count; i++) spots.push(new ParkingSpot(number, n++, size));
      this.#free.set(size, spots);
      this.#capacity.set(size, count);
    }
  }

  // No locks needed: Node runs this synchronously on one thread, so two "gates"
  // (requests) can't interleave inside claimSpot. See event-loop-and-concurrency.md.
  claimSpot(size) {
    return this.#free.get(size).shift() ?? null;
  }

  returnSpot(spot) {
    const list = this.#free.get(spot.size);
    const i = list.findIndex((s) => s.number > spot.number);
    list.splice(i === -1 ? list.length : i, 0, spot); // keep nearest-first order
  }

  freeCount(size) { return this.#free.get(size).length; }
  capacity(size) { return this.#capacity.get(size); }
}

// --- Strategies: plain functions are enough in JS ---

const nearestFirst = (floors, type) => {
  for (const floor of floors) {
    for (const size of type.fitsIn) {
      const spot = floor.claimSpot(size);
      if (spot) return spot;
    }
  }
  return null;
};

const leastCrowdedFloor = (floors, type) => {
  for (const size of type.fitsIn) {
    const sorted = [...floors].sort((a, b) => b.freeCount(size) - a.freeCount(size));
    for (const floor of sorted) {
      const spot = floor.claimSpot(size);
      if (spot) return spot;
    }
  }
  return null;
};

/** Hourly pricing in paise: grace period, round up to whole hours, daily cap. */
function hourlyPricing({ hourlyRatePaise, dailyCapPaise, graceMinutes }) {
  return (type, parkedMs) => {
    if (parkedMs < 0) throw new Error('negative duration');
    if (parkedMs <= graceMinutes * 60_000) return 0;
    const hours = Math.ceil(parkedMs / 3_600_000);
    const days = Math.floor(hours / 24);
    const rest = hours % 24;
    const rate = hourlyRatePaise[type.name];
    const cap = dailyCapPaise[type.name];
    return days * cap + Math.min(rest * rate, cap);
  };
}

class ParkingLot {
  #floors; #allocate; #price; #clock;
  #ticketsById = new Map();
  #plates = new Set();
  #listeners = [];
  #nextTicket = 1;

  constructor({ floors, allocate = nearestFirst, price, clock = { now: () => Date.now() } }) {
    if (!floors?.length) throw new Error('at least one floor');
    this.#floors = floors;
    this.#allocate = allocate;
    this.#price = price;
    this.#clock = clock;
  }

  onAvailabilityChanged(listener) { this.#listeners.push(listener); }

  park(v) {
    if (this.#plates.has(v.licensePlate)) throw new Error(`${v.licensePlate} is already parked`);
    const spot = this.#allocate(this.#floors, v.type);
    if (!spot) throw new ParkingFullError(`No free spot for ${v.type.name}`);
    spot.occupy(v);
    const ticket = Object.freeze({ id: `T${this.#nextTicket++}`, vehicle: v, spot, entryTime: this.#clock.now() });
    this.#ticketsById.set(ticket.id, ticket);
    this.#plates.add(v.licensePlate);
    this.#notify(spot);
    return ticket;
  }

  unpark(ticketId) {
    const ticket = this.#ticketsById.get(ticketId);
    if (!ticket) throw new InvalidTicketError(`unknown or already used ticket ${ticketId}`);
    this.#ticketsById.delete(ticketId);

    const exitTime = this.#clock.now();
    const parkedMs = exitTime - ticket.entryTime;
    const feePaise = this.#price(ticket.vehicle.type, parkedMs);

    ticket.spot.release();
    this.#floors.find((f) => f.number === ticket.spot.floor).returnSpot(ticket.spot);
    this.#plates.delete(ticket.vehicle.licensePlate);
    this.#notify(ticket.spot);
    return Object.freeze({ ticket, exitTime, parkedMs, feePaise });
  }

  freeSpots(size) { return this.#floors.reduce((sum, f) => sum + f.freeCount(size), 0); }

  #notify(spot) {
    const free = this.#floors.find((f) => f.number === spot.floor).freeCount(spot.size);
    for (const l of this.#listeners) {
      try { l(spot.floor, spot.size, free); } catch (e) { console.error('display listener failed:', e.message); }
    }
  }
}

/** Display only: format paise as rupees for humans. */
const formatINR = (paise) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(paise / 100);

module.exports = {
  SpotSize, VehicleType, vehicle, ParkingSpot, ParkingFloor, ParkingLot,
  nearestFirst, leastCrowdedFloor, hourlyPricing, formatINR,
  ParkingFullError, InvalidTicketError,
};
