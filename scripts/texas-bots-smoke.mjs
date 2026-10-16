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

const latest = client => client.messages.findLast(message => message.state)?.state;
const botSeats = humans => [0, 1, 2].map(index => index >= humans);

function checkPrivate(client, seat, humans) {
  for (const message of client.messages.filter(message => message.state)) {
    assert.equal(message.protocol, 2);
    const state = message.state;
    assert.deepEqual(state.bots, botSeats(state.status === 'waiting' ? 3 : humans));
    assert.equal(state.hand.length, state.inHand[seat - 1] ? 2 : 0);
    assert.equal(new Set([...state.hand, ...state.board]).size, state.hand.length + state.board.length);
    assert.ok([...state.hand, ...state.board].every(card => Number.isInteger(card) && card >= 0 && card < 52));
    assert.equal(state.stacks.reduce((sum, stack) => sum + stack, state.pot), 3000);
    assert.equal(state.roundDelta.reduce((sum, delta) => sum + delta, 0), 0);
    assert.ok(state.stacks.every(stack => Number.isSafeInteger(stack) && stack >= 0));
    assert.deepEqual(Object.keys(state).sort(), [
      'status', 'street', 'hand', 'board', 'stacks', 'bets', 'contributions',
      'folded', 'allIn', 'inHand', 'dealer', 'smallBlindSeat', 'bigBlindSeat',
      'turn', 'currentBet', 'minRaiseTo', 'maxRaiseTo', 'toCall', 'legalActions',
      'pot', 'payouts', 'returned', 'roundDelta', 'revealed', 'winners', 'ready',
      'online', 'bots', 'round', 'revision', 'matchOver', 'lastAction',
    ].sort());
    if (state.status === 'playing') {
      assert.ok(state.revealed.every(cards => cards.length === 0), 'Active hand leaked opponent cards');
      if (state.turn !== seat) assert.deepEqual(state.legalActions, []);
    }
    if (message.type !== 'welcome') assert.equal(message.token, '');
    state.bots.forEach((bot, index) => { if (bot) assert.equal(state.online[index], true); });
  }
}

async function run(humans) {
  const players = [await Client.open()];
  const created = (await players[0].request('create')).reply;
  assert.equal(created.type, 'welcome');
  assert.equal(created.seat, 1);
  assert.equal(created.state.status, 'waiting');
  const identities = [created];
  const code = created.roomCode;
  for (let i = 1; i < humans; i++) {
    players.push(await Client.open());
    const joined = (await players[i].request('join', { roomCode: code })).reply;
    assert.equal(joined.type, 'welcome');
    assert.equal(joined.seat, i + 1);
    assert.equal(joined.state.status, 'waiting');
    identities.push(joined);
  }
  assert.equal(new Set(identities.map(message => message.token)).size, humans);
  assert.ok(identities.every(message => message.token.length >= 32));
  if (humans === 2) {
    const unauthorized = (await players[1].request('start', { revision: 0 })).reply;
    assert.equal(unauthorized.type, 'error');
    assert.equal(unauthorized.state.status, 'waiting');
  }
  const stale = (await players[0].request('start', { revision: -1 })).reply;
  assert.equal(stale.type, 'error');
  const start = await players[0].request('start', { revision: 0 });
  assert.equal(start.reply.type, 'state', start.reply.error);
  assert.equal(start.reply.state.status, 'playing');
  assert.deepEqual(start.reply.state.bots, botSeats(humans));
  assert.deepEqual(start.reply.state.online, [true, true, true]);
  assert.equal(start.reply.state.hand.length, 2);
  const repeated = (await players[0].request('start', start.command)).reply;
  assert.deepEqual(repeated.state, start.reply.state);
  const outsider = await Client.open();
  for (const [type, values] of [
    ['join', {}], ['resume', { token: '' }], ['resume', { token: 'robot' }],
    ['allin', { revision: start.reply.state.revision }],
  ]) {
    const reply = (await outsider.request(type, { roomCode: code, ...values })).reply;
    assert.equal(reply.type, 'error');
    assert.equal(reply.state, null);
    assert.equal(reply.token, '');
  }
  await outsider.close();

  let humanActions = 0;
  async function humanAction() {
    const state = latest(players[0]);
    const client = players[state.turn - 1];
    assert.ok(client, 'Expected a human turn');
    await client.wait(message => message.state?.revision === state.revision);
    const own = latest(client);
    const type = own.toCall === 0 ? 'check' : 'call';
    assert.ok(own.legalActions.includes(type));
    const { reply } = await client.request(type, { revision: own.revision });
    assert.equal(reply.type, 'state', reply.error);
    await players[0].wait(message => message.state?.revision >= reply.state.revision);
    humanActions++;
    return reply.state;
  }

  if (humans === 2) {
    assert.equal(latest(players[0]).turn, 1);
    await humanAction();
    assert.equal(latest(players[0]).turn, 2);
    const before = await humanAction();
    assert.equal(before.turn, 3);
    const cursor = players[0].messages.length;
    await players[1].close();
    const offline = await players[0].wait(message => message.state && !message.state.online[1], cursor);
    assert.equal(offline.state.revision, before.revision);
    await delay(2000);
    assert.equal(latest(players[0]).revision, before.revision, 'Bots moved while a human was offline');
    players[1] = await Client.open();
    const resumed = (await players[1].request('resume', { roomCode: code, token: identities[1].token })).reply;
    assert.equal(resumed.type, 'welcome');
    assert.equal(resumed.seat, 2);
    assert.deepEqual(resumed.state.hand, before.hand);
    assert.equal(resumed.state.round, before.round);
    assert.equal(resumed.state.revision, before.revision);
    await players[0].wait(message => message.state?.online.every(Boolean), cursor);
  }

  const deadline = Date.now() + 180_000;
  while (latest(players[0]).status !== 'finished') {
    assert.ok(Date.now() < deadline, 'Natural hand timed out');
    const state = latest(players[0]);
    if (state.turn > humans) {
      await players[0].wait(message => message.state?.revision > state.revision);
    } else {
      await humanAction();
    }
  }
  const ended = latest(players[0]);
  assert.equal(ended.pot, 0);
  assert.ok(ended.winners.length > 0);
  assert.equal(ended.stacks.reduce((sum, stack) => sum + stack, 0), 3000);
  assert.ok(ended.roundDelta.some(delta => delta !== 0) || ended.winners.length > 1);
  await players[0].wait(message => message.state?.status === 'finished' &&
    message.state.ready.every((value, index) => index < humans || value));
  const readyState = latest(players[0]);
  assert.deepEqual(readyState.ready, botSeats(humans));
  await delay(1000);
  assert.equal(latest(players[0]).revision, readyState.revision, 'Bots must wait for human ready');
  for (let i = 0; i < humans; i++) {
    const revision = latest(players[0]).revision;
    await players[i].wait(message => message.state?.revision === revision);
    const { reply } = await players[i].request('ready', { revision });
    assert.equal(reply.type, 'state', reply.error);
    await players[0].wait(message => message.state?.revision >= reply.state.revision);
  }
  assert.equal(latest(players[0]).round, ended.round + 1);
  assert.equal(latest(players[0]).status, 'playing');
  assert.deepEqual(latest(players[0]).bots, botSeats(humans));
  for (let i = 0; i < humans; i++) {
    // Include the disconnected socket too, to inspect every state it received.
    for (const client of clients.filter(client => client.messages.some(message =>
      message.type === 'welcome' && message.roomCode === code && message.seat === i + 1))) {
      checkPrivate(client, i + 1, humans);
      for (let j = 0; j < humans; j++) if (i !== j) {
        assert.ok(!JSON.stringify(client.messages).includes(identities[j].token), 'Another human token leaked');
      }
    }
  }
  const automatic = players[0].messages.filter(message => message.type === 'state' && !message.requestId)
    .map(message => message.state.lastAction);
  assert.ok(automatic.some(action => /:(fold|check|call|raise|allin)\(/.test(action)), 'Expected robot action');
  assert.ok(automatic.some(action => /:ready\(/.test(action)), 'Expected robot ready');
  const left = await players[0].request('leave');
  assert.equal(left.reply.type, 'left');
  await Promise.all(players.slice(1).map(client => client.wait(message => message.type === 'closed')));
  await Promise.all(players.map(client => client.close()));
  console.log(`PASS: Texas ${humans} human + ${3 - humans} bots, private cards, host start, replay, robot token denial, natural hand (${humanActions} check/call actions), chip conservation, auto-ready/rematch and close${humans === 2 ? ', offline pause/resume' : ''}`);
}

try {
  const health = new URL(endpoint.replace(/^ws/, 'http'));
  health.pathname = '/gomoku/texas/v2/health';
  const response = await fetch(health, { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok', protocol: 2 });
  await run(1);
  await run(2);
} finally {
  await Promise.allSettled(clients.map(client => client.close()));
}
