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

const latest = client => client.messages.findLast(message => message.state)?.state;
function checkPrivate(client, seat, humans) {
  for (const message of client.messages.filter(message => message.state)) {
    assert.equal(message.protocol, 2);
    const state = message.state;
    assert.deepEqual(state.bots, ListBots(state.status === 'waiting' ? 3 : humans));
    assert.equal(state.hand.length, state.counts[seat - 1]);
    assert.equal(new Set(state.hand).size, state.hand.length);
    assert.deepEqual(Object.keys(state).sort(), [
      'status', 'hand', 'counts', 'bottom', 'landlord', 'turn', 'bid', 'bids',
      'trick', 'trickSeat', 'passes', 'revision', 'winner', 'multiplier',
      'roundDelta', 'scores', 'ready', 'online', 'bots', 'round', 'lastAction',
    ].sort());
    if (message.type !== 'welcome') assert.equal(message.token, '');
  }
}
const ListBots = humans => [0, 1, 2].map(index => index >= humans);

async function run(humans) {
  const players = [await Client.open()];
  const created = (await players[0].request('create')).reply;
  assert.equal(created.type, 'welcome');
  const identities = [created];
  const code = created.roomCode;
  for (let i = 1; i < humans; i++) {
    players.push(await Client.open());
    identities.push((await players[i].request('join', { roomCode: code })).reply);
  }
  if (humans === 2) {
    const unauthorized = (await players[1].request('start', { revision: 0 })).reply;
    assert.equal(unauthorized.type, 'error');
    assert.equal(unauthorized.state.status, 'waiting');
  }
  const stale = (await players[0].request('start', { revision: -1 })).reply;
  assert.equal(stale.type, 'error');
  const start = await players[0].request('start', { revision: 0 });
  assert.equal(start.reply.type, 'state', start.reply.error);
  assert.equal(start.reply.state.status, 'bidding');
  assert.deepEqual(start.reply.state.bots, ListBots(humans));
  assert.equal(start.reply.state.hand.length, 17);
  const repeated = (await players[0].request('start', start.command)).reply;
  assert.deepEqual(repeated.state, start.reply.state);
  const outsider = await Client.open();
  for (const [type, values] of [['join', {}], ['resume', { token: '' }], ['resume', { token: 'robot' }]]) {
    const reply = (await outsider.request(type, { roomCode: code, ...values })).reply;
    assert.equal(reply.type, 'error');
    assert.equal(reply.state, null);
  }
  await outsider.close();
  await players[0].request('bid', { bid: 0, revision: latest(players[0]).revision });
  if (humans === 2) {
    await players[1].wait(message => message.state?.turn === 2);
    const before = (await players[1].request('bid', { bid: 0, revision: latest(players[1]).revision })).reply.state;
    const cursor = players[0].messages.length;
    await players[1].close();
    await players[0].wait(message => message.state && !message.state.online[1], cursor);
    await delay(1700);
    assert.equal(latest(players[0]).revision, before.revision, 'Bots moved while a human was offline');
    players[1] = await Client.open();
    const resumed = (await players[1].request('resume', { roomCode: code, token: identities[1].token })).reply;
    assert.equal(resumed.type, 'welcome');
    assert.deepEqual(resumed.state.hand, before.hand);
    assert.equal(resumed.state.revision, before.revision);
    await players[0].wait(message => message.state?.online.every(Boolean) && message.state.revision >= before.revision, cursor);
  }
  let humanActions = 0;
  const deadline = Date.now() + 180_000;
  while (latest(players[0]).status !== 'finished') {
    assert.ok(Date.now() < deadline, 'Natural game timed out');
    const state = latest(players[0]);
    if (state.turn > humans) {
      await players[0].wait(message => message.state?.revision > state.revision);
      continue;
    }
    const client = players[state.turn - 1];
    await client.wait(message => message.state?.revision === state.revision);
    const own = latest(client);
    let type, values = { revision: own.revision };
    if (own.status === 'bidding') { type = 'bid'; values.bid = own.bid === 0 ? 1 : 0; }
    else {
      const card = own.hand.find(value => own.trick.length === 0 || own.trick.length === 1 && rank(value) > rank(own.trick[0]));
      type = card === undefined ? 'pass' : 'play';
      if (card !== undefined) values.cards = [card];
    }
    const { reply } = await client.request(type, values);
    assert.equal(reply.type, 'state', reply.error);
    await players[0].wait(message => message.state?.revision >= reply.state.revision);
    humanActions++;
  }
  const ended = latest(players[0]);
  assert.equal(ended.counts[ended.winner - 1], 0);
  assert.equal(ended.scores.reduce((a, b) => a + b), 0);
  await players[0].wait(message => message.state?.status === 'finished' && message.state.ready.every((value, index) => index < humans || value));
  const readyState = latest(players[0]);
  assert.deepEqual(readyState.ready, ListBots(humans));
  await delay(900);
  assert.equal(latest(players[0]).revision, readyState.revision, 'Bots must wait for human ready');
  for (let i = 0; i < humans; i++) {
    await players[i].wait(message => message.state?.revision === latest(players[0]).revision);
    const { reply } = await players[i].request('ready', { revision: latest(players[i]).revision });
    assert.equal(reply.type, 'state', reply.error);
    await players[0].wait(message => message.state?.revision >= reply.state.revision);
  }
  assert.equal(latest(players[0]).round, ended.round + 1);
  assert.equal(latest(players[0]).status, 'bidding');
  assert.deepEqual(latest(players[0]).scores, ended.scores);
  for (let i = 0; i < humans; i++) {
    checkPrivate(players[i], i + 1, humans);
    for (let j = 0; j < humans; j++) if (i !== j) assert.ok(!JSON.stringify(players[i].messages).includes(identities[j].token));
  }
  const automatic = players[0].messages.filter(message => message.type === 'state' && !message.requestId).map(message => message.state.lastAction);
  assert.ok(automatic.some(action => /:bid\(/.test(action)), 'Expected robot bid');
  assert.ok(automatic.some(action => /:play\(/.test(action)), 'Expected robot play');
  assert.ok(automatic.some(action => /:ready\(/.test(action)), 'Expected robot ready');
  const left = await players[0].request('leave');
  assert.equal(left.reply.type, 'left');
  await Promise.all(players.slice(1).map(client => client.wait(message => message.type === 'closed')));
  await Promise.all(players.map(client => client.close()));
  console.log(`PASS: DDZ ${humans} human + ${3 - humans} bots, private hands, host start, replay, robot token denial, natural round (${humanActions} human actions), ready/rematch and close${humans === 2 ? ', offline pause/resume' : ''}`);
}

try {
  const health = new URL(endpoint.replace(/^ws/, 'http'));
  health.pathname = '/gomoku/ddz/v2/health';
  const response = await fetch(health, { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok', protocol: 2 });
  await run(1);
  await run(2);
} finally {
  await Promise.allSettled(clients.map(client => client.close()));
}
