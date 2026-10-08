'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { Direction, Status, ElevatorSystem } = require('./elevator');

function run(sys, maxTicks = 200) {
  const opened = [];
  sys.onEvent((e) => { if (e.type === 'DOORS_OPENED') opened.push(`${e.elevatorId}@${e.floor}`); });
  for (let i = 0; i < maxTicks; i++) { sys.tick(); if (sys.isQuiet) return opened; }
  throw new Error('not quiet');
}

test('LOOK order for car calls', () => {
  const sys = new ElevatorSystem({ count: 1, minFloor: 0, maxFloor: 10 });
  sys.pressFloor(0, 5); sys.pressFloor(0, 2); sys.pressFloor(0, 8);
  assert.deepEqual(run(sys), ['0@2', '0@5', '0@8']);
});

test('DOWN hall call is skipped on the way up', () => {
  const sys = new ElevatorSystem({ count: 1, minFloor: 0, maxFloor: 10 });
  sys.pressFloor(0, 9); sys.tick();
  sys.callElevator(5, Direction.DOWN);
  assert.deepEqual(run(sys), ['0@9', '0@5']);
});

test('idle car goes up to a DOWN call and turns', () => {
  const sys = new ElevatorSystem({ count: 1, minFloor: 0, maxFloor: 10 });
  sys.callElevator(7, Direction.DOWN);
  assert.deepEqual(run(sys), ['0@7']);
  assert.equal(sys.snapshots()[0].direction, Direction.IDLE);
});

test('nearest car is chosen', () => {
  const sys = new ElevatorSystem({ count: 2, minFloor: 0, maxFloor: 10 });
  sys.pressFloor(1, 10); run(sys);
  sys.callElevator(8, Direction.UP);
  assert.deepEqual(run(sys), ['1@8']);
});

test('maintenance: calls reassigned or wait', () => {
  const sys = new ElevatorSystem({ count: 1, minFloor: 0, maxFloor: 10 });
  sys.setMaintenance(0, true);
  sys.callElevator(4, Direction.DOWN);
  for (let i = 0; i < 5; i++) sys.tick();
  assert.equal(sys.snapshots()[0].status, Status.MAINTENANCE);
  sys.setMaintenance(0, false);
  assert.deepEqual(run(sys), ['0@4']);
});

test('random calls: every hall-call floor is visited', () => {
  const sys = new ElevatorSystem({ count: 3, minFloor: 0, maxFloor: 20 });
  const requested = new Set(); const opened = new Set();
  sys.onEvent((e) => { if (e.type === 'DOORS_OPENED') opened.add(e.floor); });
  let seed = 11; const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2 ** 31; return seed % n; };
  for (let i = 0; i < 2000; i++) {
    const f = rnd(21);
    if (rnd(2)) { requested.add(f); sys.callElevator(f, f === 20 ? Direction.DOWN : f === 0 ? Direction.UP : (rnd(2) ? Direction.UP : Direction.DOWN)); }
    else sys.pressFloor(rnd(3), f);
    if (i % 3 === 0) sys.tick();
  }
  for (let i = 0; i < 1000 && !sys.isQuiet; i++) sys.tick();
  assert.ok(sys.isQuiet);
  for (const f of requested) assert.ok(opened.has(f), `floor ${f} never served`);
});
