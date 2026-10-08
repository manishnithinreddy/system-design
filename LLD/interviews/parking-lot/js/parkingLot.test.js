'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  SpotSize, VehicleType, vehicle, ParkingFloor, ParkingLot,
  leastCrowdedFloor, hourlyPricing, formatINR, ParkingFullError, InvalidTicketError,
} = require('./parkingLot');

const price = hourlyPricing({
  hourlyRatePaise: { MOTORCYCLE: 2000, CAR: 4000, TRUCK: 10000 },
  dailyCapPaise: { MOTORCYCLE: 15000, CAR: 30000, TRUCK: 80000 },
  graceMinutes: 10,
});
const MIN = 60_000;
const fakeClock = () => { let t = Date.parse('2026-10-08T09:00:00Z'); return { now: () => t, advance: (ms) => { t += ms; } }; };

test('vehicles take the smallest spot that fits', () => {
  const lot = new ParkingLot({ floors: [new ParkingFloor(0, { small: 1, medium: 1, large: 1 })], price });
  assert.equal(lot.park(vehicle('MC1', VehicleType.MOTORCYCLE)).spot.size, SpotSize.SMALL);
  assert.equal(lot.park(vehicle('CAR1', VehicleType.CAR)).spot.size, SpotSize.MEDIUM);
  assert.equal(lot.park(vehicle('TR1', VehicleType.TRUCK)).spot.size, SpotSize.LARGE);
});

test('full lot rejects, small spots are useless to cars', () => {
  const lot = new ParkingLot({ floors: [new ParkingFloor(0, { small: 5, medium: 1 })], price });
  lot.park(vehicle('CAR1', VehicleType.CAR));
  assert.throws(() => lot.park(vehicle('CAR2', VehicleType.CAR)), ParkingFullError);
});

test('pricing: grace, round up, daily cap (in paise)', () => {
  assert.equal(price(VehicleType.CAR, 10 * MIN), 0);
  assert.equal(price(VehicleType.CAR, 11 * MIN), 4000);
  assert.equal(price(VehicleType.CAR, 130 * MIN), 12000);
  assert.equal(price(VehicleType.CAR, 10 * 60 * MIN), 30000);
  assert.equal(price(VehicleType.CAR, (26 * 60 + 5) * MIN), 42000);
  assert.equal(formatINR(42000), '₹420.00');
});

test('ticket single-use, plate cannot park twice, spot returned', () => {
  const clock = fakeClock();
  const lot = new ParkingLot({ floors: [new ParkingFloor(0, { medium: 2 })], price, clock });
  const board = [];
  lot.onAvailabilityChanged((floor, size, free) => board.push(`F${floor} ${size}=${free}`));
  const t = lot.park(vehicle('KA01AB1234', VehicleType.CAR));
  assert.throws(() => lot.park(vehicle('ka 01 ab 1234', VehicleType.CAR)), /already parked/);
  clock.advance(90 * MIN);
  assert.equal(lot.unpark(t.id).feePaise, 8000);
  assert.throws(() => lot.unpark(t.id), InvalidTicketError);
  assert.equal(lot.freeSpots(SpotSize.MEDIUM), 2);
  assert.deepEqual(board, ['F0 MEDIUM=1', 'F0 MEDIUM=2']);
});

test('least-crowded strategy spreads across floors', () => {
  const lot = new ParkingLot({
    floors: [new ParkingFloor(0, { medium: 3 }), new ParkingFloor(1, { medium: 3 })],
    allocate: leastCrowdedFloor, price,
  });
  const floors = new Set([0, 1, 2, 3].map((i) => lot.park(vehicle(`C${i}`, VehicleType.CAR)).spot.floor));
  assert.equal(floors.size, 2);
});

test('freed spot goes back in nearest-first order', () => {
  const lot = new ParkingLot({ floors: [new ParkingFloor(0, { medium: 3 })], price });
  const a = lot.park(vehicle('A', VehicleType.CAR));
  lot.park(vehicle('B', VehicleType.CAR));
  lot.unpark(a.id);
  assert.equal(lot.park(vehicle('C', VehicleType.CAR)).spot.id, a.spot.id);
});
