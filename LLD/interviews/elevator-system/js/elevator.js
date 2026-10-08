'use strict';

// Same design as the Java version: LOOK scheduling, a command queue drained by tick(),
// strategies as plain functions. JS has no TreeSet, so stops are small sorted arrays
// (a building has at most ~100 floors, so O(n) inserts are irrelevant).
// See LLD/libraries/js/sorted-collections-in-js.md

const Direction = Object.freeze({ UP: 'UP', DOWN: 'DOWN', IDLE: 'IDLE' });
const Status = Object.freeze({ IDLE: 'IDLE', MOVING: 'MOVING', DOORS_OPEN: 'DOORS_OPEN', MAINTENANCE: 'MAINTENANCE' });

class SortedSet {
  #a = [];
  add(x) { if (!this.#a.includes(x)) { this.#a.push(x); this.#a.sort((p, q) => p - q); } }
  delete(x) { const i = this.#a.indexOf(x); if (i >= 0) this.#a.splice(i, 1); return i >= 0; }
  has(x) { return this.#a.includes(x); }
  ceiling(x) { return this.#a.find((v) => v >= x); }               // smallest >= x
  floor(x) { return [...this.#a].reverse().find((v) => v <= x); }  // largest <= x
  higher(x) { return this.#a.find((v) => v > x); }
  lower(x) { return [...this.#a].reverse().find((v) => v < x); }
  first() { return this.#a[0]; }
  last() { return this.#a[this.#a.length - 1]; }
  get size() { return this.#a.length; }
  clear() { this.#a = []; }
  toArray() { return [...this.#a]; }
}

class Elevator {
  constructor(id, minFloor, maxFloor) {
    Object.assign(this, { id, minFloor, maxFloor });
    this.floor = minFloor;
    this.direction = Direction.IDLE;
    this.status = Status.IDLE;
    this.up = new SortedSet();
    this.down = new SortedSet();
    this.hallCalls = [];
  }

  get pendingStops() { return this.up.size + this.down.size; }
  get hasStops() { return this.pendingStops > 0; }
  get inService() { return this.status !== Status.MAINTENANCE; }

  addCarCall(f) {
    if (f > this.floor) this.up.add(f);
    else if (f < this.floor) this.down.add(f);
    else if (this.status !== Status.MOVING) this.status = Status.DOORS_OPEN;
  }

  addHallCall(call) {
    this.hallCalls.push(call);
    (call.direction === Direction.UP ? this.up : this.down).add(call.floor);
  }

  enterMaintenance() {
    const unserved = this.hallCalls;
    this.hallCalls = [];
    this.up.clear(); this.down.clear();
    this.direction = Direction.IDLE;
    this.status = Status.MAINTENANCE;
    return unserved;
  }

  nextTarget() {
    if (this.direction === Direction.UP) {
      const ahead = this.up.ceiling(this.floor);
      if (ahead !== undefined) return ahead;
      if (this.down.size && this.down.last() >= this.floor) return this.down.last();
      return this.#turnAround(Direction.DOWN);
    }
    if (this.direction === Direction.DOWN) {
      const ahead = this.down.floor(this.floor);
      if (ahead !== undefined) return ahead;
      if (this.up.size && this.up.first() <= this.floor) return this.up.first();
      return this.#turnAround(Direction.UP);
    }
    const all = [...this.up.toArray(), ...this.down.toArray()];
    return all.reduce((best, s) => (best === undefined || Math.abs(s - this.floor) < Math.abs(best - this.floor) ? s : best), undefined);
  }

  #turnAround(d) {
    if (!this.hasStops) return undefined;
    this.direction = d;
    return this.nextTarget();
  }

  step(tick, emit) {
    if (this.status === Status.MAINTENANCE) return;
    if (this.status === Status.DOORS_OPEN) {
      this.status = this.hasStops ? Status.MOVING : Status.IDLE;
      if (!this.hasStops) this.direction = Direction.IDLE;
      return;
    }
    const target = this.nextTarget();
    if (target === undefined) { this.direction = Direction.IDLE; this.status = Status.IDLE; return; }
    if (target !== this.floor) {
      this.direction = target > this.floor ? Direction.UP : Direction.DOWN;
      this.floor += this.direction === Direction.UP ? 1 : -1;
      this.status = Status.MOVING;
    }
    if (this.#serveCurrentFloor()) {
      this.status = Status.DOORS_OPEN;
      emit({ tick, elevatorId: this.id, type: 'DOORS_OPENED', floor: this.floor });
    }
  }

  #serveCurrentFloor() {
    const f = this.floor;
    let servedUp = false, servedDown = false;
    if (this.direction !== Direction.DOWN) {
      servedUp = this.up.delete(f);
      if (this.up.higher(f) === undefined && this.down.has(f)) {
        servedDown = this.down.delete(f);
        if (servedDown && !servedUp) this.direction = Direction.DOWN;
      }
    }
    if (!servedUp && !servedDown && this.direction !== Direction.UP) {
      servedDown = this.down.delete(f);
      if (this.down.lower(f) === undefined && this.up.has(f)) {
        servedUp = this.up.delete(f);
        if (servedUp && !servedDown) this.direction = Direction.UP;
      }
    }
    this.hallCalls = this.hallCalls.filter((c) => !(c.floor === f
      && ((servedUp && c.direction === Direction.UP) || (servedDown && c.direction === Direction.DOWN))));
    return servedUp || servedDown;
  }

  sweepEnd() {
    if (this.direction === Direction.UP) return Math.max(this.floor, this.up.last() ?? this.floor, this.down.last() ?? this.floor);
    if (this.direction === Direction.DOWN) return Math.min(this.floor, this.down.first() ?? this.floor, this.up.first() ?? this.floor);
    return this.floor;
  }
}

// --- strategies: (elevators, call) => elevator | undefined ---

function nearestCar(elevators, call) {
  const cost = (e) => {
    let travel;
    if (e.direction === Direction.IDLE) travel = Math.abs(e.floor - call.floor);
    else if ((e.direction === Direction.UP && call.direction === Direction.UP && call.floor >= e.floor)
      || (e.direction === Direction.DOWN && call.direction === Direction.DOWN && call.floor <= e.floor)) {
      travel = Math.abs(call.floor - e.floor);
    } else {
      const end = e.sweepEnd();
      travel = Math.abs(end - e.floor) + Math.abs(end - call.floor);
    }
    return travel + e.pendingStops;
  };
  return elevators.filter((e) => e.inService)
    .reduce((best, e) => (best === undefined || cost(e) < cost(best) ? e : best), undefined);
}

function leastBusy(elevators, call) {
  return elevators.filter((e) => e.inService)
    .reduce((best, e) => {
      if (best === undefined) return e;
      if (e.pendingStops !== best.pendingStops) return e.pendingStops < best.pendingStops ? e : best;
      return Math.abs(e.floor - call.floor) < Math.abs(best.floor - call.floor) ? e : best;
    }, undefined);
}

class ElevatorSystem {
  #elevators; #select; #min; #max;
  #inbox = [];
  #waiting = [];
  #listeners = [];
  #tick = 0;

  constructor({ count, minFloor, maxFloor, select = nearestCar }) {
    this.#min = minFloor; this.#max = maxFloor; this.#select = select;
    this.#elevators = Array.from({ length: count }, (_, i) => new Elevator(i, minFloor, maxFloor));
  }

  // In Node all of this runs on one thread, so a plain array is a safe "queue".
  callElevator(floor, direction) { this.#inbox.push({ kind: 'hall', floor, direction }); }
  pressFloor(elevatorId, floor) { this.#inbox.push({ kind: 'car', elevatorId, floor }); }
  setMaintenance(elevatorId, on) { this.#inbox.push({ kind: 'maintenance', elevatorId, on }); }
  onEvent(fn) { this.#listeners.push(fn); }

  tick() {
    this.#tick++;
    const batch = this.#inbox.splice(0);
    for (const c of batch) this.#apply(c);
    const waiting = this.#waiting.splice(0);
    waiting.forEach((c) => this.#assign(c));
    for (const e of this.#elevators) e.step(this.#tick, (ev) => this.#emit(ev));
  }

  get isQuiet() {
    return !this.#inbox.length && !this.#waiting.length
      && this.#elevators.every((e) => !e.hasStops && e.status !== Status.DOORS_OPEN);
  }

  snapshots() {
    return this.#elevators.map((e) => Object.freeze({
      id: e.id, floor: e.floor, direction: e.direction, status: e.status, up: e.up.toArray(), down: e.down.toArray(),
    }));
  }

  #apply(c) {
    const inRange = (f) => f >= this.#min && f <= this.#max;
    if (c.kind === 'hall' && inRange(c.floor)) this.#assign({ floor: c.floor, direction: c.direction });
    else if (c.kind === 'car') {
      const e = this.#elevators[c.elevatorId];
      if (e.inService && inRange(c.floor)) e.addCarCall(c.floor);
    } else if (c.kind === 'maintenance') {
      const e = this.#elevators[c.elevatorId];
      if (c.on && e.inService) e.enterMaintenance().forEach((call) => this.#assign(call));
      else if (!c.on && !e.inService) e.status = Status.IDLE;
    }
  }

  #assign(call) {
    const car = this.#select(this.#elevators, call);
    if (car) car.addHallCall(call); else this.#waiting.push(call);
  }

  #emit(ev) {
    for (const l of this.#listeners) {
      try { l(ev); } catch (err) { console.error('listener failed:', err.message); }
    }
  }
}

module.exports = { Direction, Status, ElevatorSystem, nearestCar, leastBusy, SortedSet };
