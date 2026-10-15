import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const endpoint = process.argv[2] || 'ws://127.0.0.1:8091/gomoku/ddz/v2/ws';
const clients = [];
const rank = card => card < 52 ? Math.floor(card / 4) + 3 : card - 36;

class Client {
  messages = [];
  waiters = [];
  failure = null;
  lastSent = 0;
  constructor(socket) {
    this.socket = socket;
    socket.addEventListener('message', ({ data }) => {
      let message;
      try { message = JSON.parse(data); }
      catch { this.fail(new Error('Invalid server JSON')); return; }
      this.messages.push(message);
      for (const waiter of [...this.waiters]) {
        if (this.messages.length > waiter.after && waiter.match(message)) waiter.resolve(message);
      }
    });
    socket.addEventListener('error', () => this.fail(new Error('WebSocket connection failed')));
    socket.addEventListener('close', () => this.fail(new Error('WebSocket closed')));
  }
  fail(error) {
    this.failure = error;
    for (const waiter of [...this.waiters]) waiter.reject(error);
  }
  static async open() {
    const client = new Client(new WebSocket(endpoint));
    clients.push(client);
    try {
      await new Promise((resolve, reject) => {
        const socket = client.socket;
        const finish = error => {
          clearTimeout(timer);
          socket.removeEventListener('open', opened);
          socket.removeEventListener('error', failed);
          socket.removeEventListener('close', failed);
          error ? reject(error) : resolve();
        };
        const opened = () => finish();
        const failed = () => finish(new Error('WebSocket connection failed'));
        const timer = setTimeout(() => finish(new Error('WebSocket connect timeout')), 10000);
        socket.addEventListener('open', opened);
        socket.addEventListener('error', failed);
        socket.addEventListener('close', failed);
      });
    } catch (error) {
      await client.close();
      throw error;
    }
    return client;
  }
  wait(match, after = 0) {
    const existing = this.messages.slice(after).find(match);
    if (existing) return Promise.resolve(existing);
    if (this.failure) return Promise.reject(this.failure);
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timer);
        this.waiters = this.waiters.filter(value => value !== waiter);
      };
      const waiter = {
        match, after,
        resolve: message => { cleanup(); resolve(message); },
        reject: error => { cleanup(); reject(error); },
      };
      const timer = setTimeout(() => waiter.reject(new Error('Message timeout')), 10000);
      this.waiters.push(waiter);
    });
  }
  async request(type, values = {}) {
    // Stay below the production per-socket request limit, even on localhost.
    await delay(Math.max(0, 80 - (Date.now() - this.lastSent)));
    const after = this.messages.length;
    const command = { type, protocol: 2, requestId: randomUUID(), ...values };
    this.socket.send(JSON.stringify(command));
    this.lastSent = Date.now();
    return { command, reply: await this.wait(message => message.requestId === command.requestId, after) };
  }
  async close() {
    if (this.socket.readyState === WebSocket.CLOSED) return;
    await new Promise(resolve => {
      const finish = () => {
        clearTimeout(timer);
        this.socket.removeEventListener('close', finish);
        resolve();
      };
      const timer = setTimeout(finish, 2000);
      this.socket.addEventListener('close', finish, { once: true });
      try { this.socket.close(1000, 'Smoke complete'); } catch { finish(); }
    });
  }
}

function assertViews(states) {
  const publicState = ({ hand, ...rest }) => rest;
  states.forEach((state, index) => {
    assert.deepEqual(publicState(state), publicState(states[0]));
    assert.equal(state.hand.length, state.counts[index]);
    assert.equal(new Set(state.hand).size, state.hand.length);
    assert.ok(state.hand.every(card => Number.isInteger(card) && card >= 0 && card <= 53));
    assert.deepEqual(Object.keys(state).sort(), [
      'status', 'hand', 'counts', 'bottom', 'landlord', 'turn', 'bid', 'bids',
      'trick', 'trickSeat', 'passes', 'revision', 'winner', 'multiplier',
      'roundDelta', 'scores', 'ready', 'online', 'round', 'lastAction',
    ].sort());
  });
  const hands = states.flatMap(state => state.hand);
  assert.equal(new Set(hands).size, hands.length, 'Recipients must have distinct private hands');
}

async function broadcast(players, requestId) {
  const messages = await Promise.all(players.map(client => client.wait(message => message.type === 'state' && message.requestId === requestId)));
  assert.ok(messages.every(message => message.token === ''), 'State broadcasts must not contain tokens');
  const states = messages.map(message => message.state);
  assertViews(states);
  return states;
}

try {
  const health = new URL(endpoint.replace(/^ws/, 'http'));
  health.pathname = '/gomoku/ddz/v2/health';
  const response = await fetch(health, { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok', protocol: 2 });
  const players = [await Client.open()];
  const created = (await players[0].request('create')).reply;
  assert.equal(created.type, 'welcome');
  assert.equal(created.seat, 1);
  assert.equal(created.state.status, 'waiting');
  assert.match(created.roomCode, /^[A-HJ-NP-Z2-9]{8}$/);
  const welcomes = [created];
  let lastJoin;
  for (let index = 1; index < 3; index++) {
    players.push(await Client.open());
    lastJoin = await players[index].request('join', { roomCode: created.roomCode });
    assert.equal(lastJoin.reply.type, 'welcome');
    assert.equal(lastJoin.reply.seat, index + 1);
    assert.equal(lastJoin.reply.state.status, index === 1 ? 'waiting' : 'bidding');
    welcomes.push(lastJoin.reply);
  }
  let states = await broadcast(players, lastJoin.command.requestId);
  assert.deepEqual(states.map(state => state.hand.length), [17, 17, 17]);
  assert.ok(states.every(state => state.bottom.length === 0));
  assert.equal(new Set(welcomes.map(message => message.token)).size, 3);
  assert.ok(welcomes.every(message => message.token.length >= 32));
  const outsider = await Client.open();
  for (const [type, values] of [
    ['join', { roomCode: created.roomCode }],
    ['resume', { roomCode: created.roomCode, token: 'invalid' }],
    ['play', { cards: [0], revision: states[0].revision }],
  ]) {
    const { reply } = await outsider.request(type, values);
    assert.equal(reply.type, 'error');
    assert.equal(reply.state, null);
    assert.equal(reply.token, '');
  }
  await outsider.close();

  const bid = await players[0].request('bid', { bid: 3, revision: states[0].revision });
  assert.equal(bid.reply.type, 'state');
  states = await broadcast(players, bid.command.requestId);
  assert.deepEqual(states.map(state => state.hand.length), [20, 17, 17]);
  assert.equal(states[0].status, 'playing');
  assert.equal(states[0].landlord, 1);
  assert.equal(states[0].bottom.length, 3);
  assert.ok(states[0].bottom.every(card => states[0].hand.includes(card)));

  const beforeReplay = states[0];
  assert.deepEqual((await players[0].request('bid', bid.command)).reply.state, beforeReplay);
  const conflict = (await players[0].request('bid', { ...bid.command, bid: 2 })).reply;
  assert.equal(conflict.type, 'error');
  assert.deepEqual(conflict.state, beforeReplay);
  for (const values of [
    { cards: [-1], revision: beforeReplay.revision },
    { cards: [states[1].hand[0]], revision: beforeReplay.revision },
    { cards: [beforeReplay.hand[0]], revision: beforeReplay.revision - 1 },
    { cards: [beforeReplay.hand[0], beforeReplay.hand[0]], revision: beforeReplay.revision },
  ]) {
    const { reply } = await players[0].request('play', values);
    assert.equal(reply.type, 'error');
    assert.deepEqual(reply.state, beforeReplay);
  }

  const offlineCursors = players.map(client => client.messages.length);
  const retainedHand = states[2].hand;
  await players[2].close();
  await Promise.all(players.slice(0, 2).map((client, index) => client.wait(message => message.type === 'state' && !message.state.online[2], offlineCursors[index])));
  const paused = (await players[0].request('play', { cards: [states[0].hand[0]], revision: states[0].revision })).reply;
  assert.equal(paused.type, 'error');
  assert.equal(paused.state.revision, states[0].revision);
  players[2] = await Client.open();
  const resumed = await players[2].request('resume', { roomCode: created.roomCode, token: welcomes[2].token });
  assert.equal(resumed.reply.type, 'welcome');
  assert.equal(resumed.reply.seat, 3);
  assert.equal(resumed.reply.token, welcomes[2].token);
  assert.deepEqual(resumed.reply.state.hand, retainedHand);
  states = await broadcast(players, resumed.command.requestId);
  assert.deepEqual(states[0].online, [true, true, true]);

  const played = new Set();
  let winner = 0;
  let actions = 0;
  while (states[0].status === 'playing' && actions < 300) {
    actions++;
    const seat = states[0].turn;
    const own = states[seat - 1];
    assert.ok(seat >= 1 && seat <= 3);
    assert.ok(own.trick.length <= 1);
    const sorted = [...own.hand].sort((a, b) => rank(a) - rank(b) || a - b);
    const card = sorted.find(value => own.trick.length === 0 || rank(value) > rank(own.trick[0]));
    const type = card === undefined ? 'pass' : 'play';
    const result = await players[seat - 1].request(type, { revision: own.revision, ...(card === undefined ? {} : { cards: [card] }) });
    assert.equal(result.reply.type, 'state', result.reply.error);
    states = await broadcast(players, result.command.requestId);
    assert.equal(states[0].revision, own.revision + 1);
    if (card !== undefined) {
      assert.ok(!played.has(card));
      played.add(card);
      assert.equal(states[seat - 1].hand.length, own.hand.length - 1);
      if (states[seat - 1].hand.length === 0) winner = seat;
    } else assert.equal(states[seat - 1].hand.length, own.hand.length);
    const remaining = states.flatMap(state => state.hand);
    assert.equal(remaining.length + played.size, 54);
    assert.ok(remaining.every(value => !played.has(value)));
  }
  assert.equal(states[0].status, 'finished', 'Natural singles game did not finish within 300 actions');
  assert.ok(winner > 0);
  assert.equal(states[0].winner, winner);
  assert.equal(states[winner - 1].hand.length, 0);
  const scores = states[0].scores;
  assert.equal(scores.reduce((sum, value) => sum + value, 0), 0);
  assert.deepEqual(states[0].roundDelta, scores);
  const landlordWon = winner === states[0].landlord;
  assert.ok(scores.every((score, index) => (score > 0) === ((index + 1 === states[0].landlord) === landlordWon)));
  const round = states[0].round;
  for (let index = 0; index < 3; index++) {
    const ready = await players[index].request('ready', { revision: states[index].revision });
    assert.equal(ready.reply.type, 'state');
    states = await broadcast(players, ready.command.requestId);
  }
  assert.equal(states[0].status, 'bidding');
  assert.equal(states[0].round, round + 1);
  assert.deepEqual(states[0].scores, scores);
  assert.deepEqual(states[0].counts, [17, 17, 17]);
  assert.ok(states.every(state => state.bottom.length === 0));

  const left = await players[0].request('leave');
  assert.equal(left.reply.type, 'left');
  await Promise.all(players.slice(1).map(client => client.wait(message => message.type === 'closed' && message.requestId === left.command.requestId)));
  for (const [index, client] of players.entries()) {
    assert.ok(client.messages.filter(message => message.type !== 'welcome').every(message => message.token === ''));
    for (const [otherIndex, welcome] of welcomes.entries()) {
      if (index !== otherIndex) assert.ok(!JSON.stringify(client.messages).includes(welcome.token), 'Another player token leaked');
    }
  }
  console.log(`PASS: DDZ health, private three-player deal, outsider/auth rejection, bid, validation/dedup, reconnect/pause, natural game (${actions} actions), scores, rematch and room close`);
} finally {
  await Promise.allSettled(clients.map(client => client.close()));
}
