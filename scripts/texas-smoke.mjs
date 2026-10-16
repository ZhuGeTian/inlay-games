import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const endpoint = process.argv[2] || 'ws://127.0.0.1:8091/gomoku/texas/v2/ws';
const clients = [];

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
  const publicState = ({ hand, toCall, minRaiseTo, maxRaiseTo, legalActions, ...rest }) => rest;
  states.forEach((state, index) => {
    assert.deepEqual(publicState(state), publicState(states[0]));
    assert.equal(state.hand.length, state.inHand[index] ? 2 : 0);
    assert.equal(new Set(state.hand).size, state.hand.length);
    assert.ok(state.hand.every(card => Number.isInteger(card) && card >= 0 && card < 52));
    assert.equal(state.stacks.reduce((sum, value) => sum + value, state.pot), 3000);
    assert.equal(state.roundDelta.reduce((sum, value) => sum + value, 0), 0);
    assert.ok(state.stacks.every(value => Number.isSafeInteger(value) && value >= 0));
    assert.equal(new Set([...state.board, ...state.hand]).size, state.board.length + state.hand.length);
    if (state.status === 'playing') {
      assert.ok(state.revealed.every(cards => cards.length === 0), 'Unfinished hand leaked other private cards');
      if (index + 1 !== state.turn) assert.deepEqual(state.legalActions, []);
    }
  });
  const dealt = [...states[0].board, ...states.flatMap(state => state.hand)];
  assert.equal(new Set(dealt).size, dealt.length, 'Private hands and board must be distinct');
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
  health.pathname = '/gomoku/texas/v2/health';
  const response = await fetch(health, { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok', protocol: 2 });
  const incompatible = await Client.open();
  const rejected = (await incompatible.request('create', { protocol: 99 })).reply;
  assert.equal(rejected.type, 'error');
  assert.equal(rejected.state, null);
  assert.equal(rejected.token, '');
  await incompatible.close();
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
    assert.equal(lastJoin.reply.state.status, index === 1 ? 'waiting' : 'playing');
    welcomes.push(lastJoin.reply);
  }
  let states = await broadcast(players, lastJoin.command.requestId);
  assert.deepEqual(states.map(state => state.hand.length), [2, 2, 2]);
  assert.equal(new Set(welcomes.map(message => message.token)).size, 3);
  assert.ok(welcomes.every(message => message.token.length >= 32));
  const outsider = await Client.open();
  for (const [type, values] of [
    ['join', { roomCode: created.roomCode }],
    ['resume', { roomCode: created.roomCode, token: 'invalid' }],
    ['allin', { revision: states[0].revision }],
  ]) {
    const { reply } = await outsider.request(type, values);
    assert.equal(reply.type, 'error'); assert.equal(reply.state, null); assert.equal(reply.token, '');
  }
  await outsider.close();
  let actions = 0;
  async function act(type, values = {}) {
    const seat = states[0].turn;
    const own = states[seat - 1];
    const result = await players[seat - 1].request(type, { revision: own.revision, ...values });
    assert.equal(result.reply.type, 'state', result.reply.error);
    states = await broadcast(players, result.command.requestId);
    assert.equal(states[0].revision, own.revision + 1);
    actions++;
    return { ...result, seat };
  }
  const first = await act('call');
  const replayBefore = states[first.seat - 1];
  assert.deepEqual((await players[first.seat - 1].request(first.command.type, first.command)).reply.state, replayBefore);
  const conflict = (await players[first.seat - 1].request('fold', { ...first.command, type: 'fold' })).reply;
  assert.equal(conflict.type, 'error'); assert.deepEqual(conflict.state, replayBefore);
  const current = players[states[0].turn - 1];
  for (const values of [
    { revision: states[0].revision - 1, raiseTo: 20 },
    { revision: states[0].revision, raiseTo: -1 },
    { revision: states[0].revision, raiseTo: 999999999 },
  ]) {
    const reply = (await current.request('raise', values)).reply;
    assert.equal(reply.type, 'error'); assert.equal(reply.state.revision, states[0].revision);
  }
  const offlineCursors = players.map(client => client.messages.length);
  const retained = states[2].hand;
  await players[2].close();
  await Promise.all(players.slice(0, 2).map((client, index) => client.wait(message => message.type === 'state' && !message.state.online[2], offlineCursors[index])));
  const paused = (await players[0].request('fold', { revision: states[0].revision })).reply;
  assert.equal(paused.type, 'error'); assert.equal(paused.state.revision, states[0].revision);
  players[2] = await Client.open();
  const resumed = await players[2].request('resume', { roomCode: created.roomCode, token: welcomes[2].token });
  assert.equal(resumed.reply.type, 'welcome'); assert.equal(resumed.reply.seat, 3);
  assert.deepEqual(resumed.reply.state.hand, retained);
  states = await broadcast(players, resumed.command.requestId);
  assert.deepEqual(states[0].online, [true, true, true]);

  while (states[0].status === 'playing' && actions < 20) {
    const own = states[states[0].turn - 1];
    await act(own.toCall === 0 ? 'check' : 'call');
  }
  assert.equal(states[0].status, 'finished', 'Check/call hand must reach showdown');
  assert.equal(states[0].board.length, 5);
  assert.ok(states[0].revealed.every(cards => cards.length === 2));
  assert.equal(states[0].pot, 0);
  async function nextHand() {
    const round = states[0].round;
    for (let index = 0; index < 3; index++) {
      const ready = await players[index].request('ready', { revision: states[index].revision });
      assert.equal(ready.reply.type, 'state', ready.reply.error);
      states = await broadcast(players, ready.command.requestId);
    }
    assert.equal(states[0].status, 'playing');
    assert.equal(states[0].round, round + 1);
  }
  await nextHand();
  const raiser = states[0].turn;
  await act('raise', { raiseTo: states[raiser - 1].minRaiseTo });
  while (states[0].status === 'playing' && actions < 25) await act('fold');
  assert.equal(states[0].status, 'finished');
  assert.deepEqual(states[0].winners, [raiser]);
  assert.ok(states[0].revealed.every(cards => cards.length === 0), 'Fold victory must not reveal hole cards');
  await nextHand();
  await act('allin');
  while (states[0].status === 'playing' && actions < 35) {
    const own = states[states[0].turn - 1];
    await act(own.toCall === 0 ? 'check' : 'call');
  }
  assert.equal(states[0].status, 'finished', 'All-in calls must automatically run out the board');
  assert.equal(states[0].board.length, 5);
  assert.equal(states[0].stacks.reduce((sum, value) => sum + value, 0), 3000);
  assert.ok(states[0].revealed.every(cards => cards.length === 2));
  const left = await players[0].request('leave');
  assert.equal(left.reply.type, 'left');
  await Promise.all(players.slice(1).map(client => client.wait(message => message.type === 'closed' && message.requestId === left.command.requestId)));
  for (const [index, client] of players.entries()) {
    assert.ok(client.messages.filter(message => message.type !== 'welcome').every(message => message.token === ''));
    for (const [otherIndex, welcome] of welcomes.entries()) {
      if (index !== otherIndex) assert.ok(!JSON.stringify(client.messages).includes(welcome.token), 'Another player token leaked');
    }
  }
  console.log(`PASS: Texas health, private deal, auth/revision/dedup/raise validation, reconnect/pause, check-call showdown, ready, raise-fold privacy, all-in runout, chip conservation and room close (${actions} actions)`);
} finally {
  await Promise.allSettled(clients.map(client => client.close()));
}
