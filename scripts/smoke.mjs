import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const endpoint = process.argv[2] || 'ws://127.0.0.1:8091/gomoku/ws';
const clients = [];
class Client {
  messages = [];
  waiters = [];
  constructor(socket) {
    this.socket = socket;
    socket.addEventListener('message', ({ data }) => {
      const message = JSON.parse(data);
      this.messages.push(message);
      for (const waiter of [...this.waiters]) if (waiter.match(message)) waiter.resolve(message);
    });
  }
  static async open() {
    const socket = new WebSocket(endpoint);
    const client = new Client(socket);
    clients.push(client);
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('WebSocket connect timeout')), 10000);
      socket.addEventListener('open', () => { clearTimeout(timeout); resolve(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('WebSocket connection failed')); }, { once: true });
    });
    return client;
  }
  wait(match) {
    const existing = this.messages.find(match);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const waiter = { match, resolve: message => { clearTimeout(timeout); this.waiters = this.waiters.filter(w => w !== waiter); resolve(message); } };
      const timeout = setTimeout(() => { this.waiters = this.waiters.filter(w => w !== waiter); reject(new Error('Message timeout')); }, 10000);
      this.waiters.push(waiter);
    });
  }
  send(type, values = {}) {
    const message = { type, protocol: 1, requestId: randomUUID(), ...values };
    this.socket.send(JSON.stringify(message));
    return message;
  }
  request(type, values = {}) {
    const message = this.send(type, values);
    return this.wait(m => m.requestId === message.requestId);
  }
  close() { this.socket.close(); }
}

try {
  const health = new URL(endpoint.replace(/^ws/, 'http'));
  health.pathname = '/gomoku/health';
  assert.equal((await (await fetch(health)).json()).status, 'ok');
  let black = await Client.open();
  const created = await black.request('create');
  assert.equal(created.type, 'welcome');
  assert.equal(created.seat, 1);
  assert.match(created.roomCode, /^[A-HJ-NP-Z2-9]{8}$/);
  const white = await Client.open();
  const joined = await white.request('join', { roomCode: created.roomCode });
  assert.equal(joined.seat, 2);
  assert.equal(joined.state.status, 'playing');
  const outsider = await Client.open();
  assert.equal((await outsider.request('join', { roomCode: created.roomCode })).type, 'error');
  assert.equal((await outsider.request('move', { x: 0, y: 0, revision: 1 })).type, 'error');
  assert.equal((await outsider.request('resume', { roomCode: created.roomCode, token: 'invalid' })).type, 'error');
  outsider.close();
  assert.equal((await white.request('move', { x: 1, y: 1, revision: 1 })).type, 'error');
  assert.equal((await black.request('move', { x: -1, y: 0, revision: 1 })).type, 'error');
  const first = black.send('move', { x: 0, y: 0, revision: 1 });
  const accepted = await black.wait(m => m.requestId === first.requestId);
  assert.equal(accepted.state.revision, 2);
  const before = black.messages.length;
  black.socket.send(JSON.stringify(first));
  await black.wait(m => black.messages.indexOf(m) >= before && m.requestId === first.requestId);
  assert.equal(black.messages.at(-1).state.revision, 2);
  assert.equal((await white.request('move', { x: 0, y: 0, revision: 2 })).type, 'error');
  assert.equal((await white.request('move', { x: 0, y: 1, revision: 1 })).type, 'error');
  black.close();
  await white.wait(m => m.type === 'state' && !m.state.blackOnline);
  black = await Client.open();
  const resumed = await black.request('resume', { roomCode: created.roomCode, token: created.token });
  assert.equal(resumed.state.revision, 2);
  assert.equal(resumed.state.board[0], 1);
  let revision = 2;
  for (let x = 0; x < 4; x++) {
    const w = await white.request('move', { x, y: 1, revision });
    assert.equal(w.type, 'state'); revision++;
    const b = await black.request('move', { x: x + 1, y: 0, revision });
    assert.equal(b.type, 'state'); revision++;
    const remote = await white.wait(m => m.type === 'state' && m.state.revision === revision);
    assert.deepEqual(b.state, remote.state);
  }
  const won = await white.wait(m => m.state?.status === 'finished');
  assert.equal(won.state.winner, 1);
  assert.equal((await white.request('move', { x: 4, y: 1, revision })).type, 'error');
  await black.request('leave');
  await white.request('leave');
  console.log('PASS: health, create/join, capacity, identity, move validation, deduplication, resume, shared revisions, victory and leave');
} finally {
  for (const client of clients) client.close();
}
