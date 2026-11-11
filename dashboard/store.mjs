import { DatabaseSync } from 'node:sqlite';
import { randomBytes, createHash } from 'node:crypto';
export const hash = value => createHash('sha256').update(value).digest('hex');
export const secret = () => randomBytes(32).toString('base64url');
export const dayOf = time => new Date(time + 8 * 3600000).toISOString().slice(0, 10);
const fail = message => { const e = new Error(message); e.status = 400; throw e; };
const text = (value, max, name, empty = false) => typeof value !== 'string' || value.length > max || (!empty && !value.trim()) ? fail(`无效字段：${name}`) : value.trim();

// 上传的是作答快照，结果由服务根据选项重新计算，不相信客户端分数。
export function validateAttempt(a, now = Date.now()) {
  if (!a || typeof a !== 'object') fail('无效作答');
  const eventId = text(a.eventId, 80, 'eventId');
  if (!/^[a-zA-Z0-9_-]{16,80}$/.test(eventId)) fail('无效作答编号');
  if (!Number.isSafeInteger(a.occurredAt) || a.occurredAt < 1262304000000 || a.occurredAt > now + 300000) fail('作答时间无效，请检查设备时间');
  const paperId = text(a.paperId, 64, 'paperId'); if (!/^[0-9a-f]{64}$/.test(paperId)) fail('题库编号无效');
  const questionId = text(a.questionId, 80, 'questionId'); if (!/^[a-zA-Z0-9_-]+$/.test(questionId)) fail('题目编号无效');
  const type = text(a.type, 16, 'type'); if (!['single', 'multiple', 'boolean'].includes(type)) fail('题型无效');
  if (!Array.isArray(a.options) || a.options.length !== (type === 'boolean' ? 2 : 4)) fail('选项数量无效');
  const options = a.options.map(x => text(x, 8000, 'option', true));
  const selected = text(a.selected, 4, 'selected'), answer = text(a.answer, 4, 'answer', true);
  const valid = v => /^[A-D]+$/.test(v) && [...v].every(c => c.charCodeAt(0) - 65 < options.length) && [...new Set(v)].sort().join('') === v && (type === 'multiple' || v.length === 1);
  if (!valid(selected) || answer && (!valid(answer) || type === 'multiple' && answer.length < 2)) fail('答案选项无效');
  return { eventId, occurredAt: a.occurredAt, paperId, questionId, type,
    paperTitle: text(a.paperTitle, 200, 'paperTitle'), section: text(a.section, 40, 'section', true) || '未分类',
    material: text(a.material || '', 40000, 'material', true), stem: text(a.stem, 16000, 'stem'), options, selected, answer,
    explanation: text(a.explanation, 40000, 'explanation', true), source: text(a.source, 4000, 'source', true), hasImages: a.hasImages === true,
    correct: answer ? Number(answer === selected) : null, day: dayOf(a.occurredAt) };
}

export function openStore(path) {
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER, revoked INTEGER DEFAULT 0);
    CREATE TABLE IF NOT EXISTS pairing(code_hash TEXT PRIMARY KEY, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS attempts(event_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id), occurred_at INTEGER NOT NULL, day TEXT NOT NULL,
      paper_id TEXT NOT NULL, question_id TEXT NOT NULL, paper_title TEXT NOT NULL, section TEXT NOT NULL, selected TEXT NOT NULL, answer TEXT NOT NULL, correct INTEGER, snapshot TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS attempts_day ON attempts(day, occurred_at);
    CREATE INDEX IF NOT EXISTS attempts_question ON attempts(paper_id, question_id, occurred_at);
    CREATE TABLE IF NOT EXISTS analyses(id INTEGER PRIMARY KEY, created_at INTEGER NOT NULL, from_day TEXT NOT NULL, to_day TEXT NOT NULL, model TEXT NOT NULL, report TEXT NOT NULL);
  `);
  // 先全批校验再事务写入，重复请求只确认原事件，不重复计数或覆盖。
  function ingest(deviceId, values, now = Date.now()) {
    if (!Array.isArray(values) || !values.length || values.length > 50) fail('每批需1–50条作答');
    const attempts = values.map(a => validateAttempt(a, now));
    db.exec('BEGIN IMMEDIATE');
    try {
      for (const a of attempts) {
        const snapshot = JSON.stringify(a);
        const old = db.prepare('SELECT device_id,snapshot FROM attempts WHERE event_id=?').get(a.eventId);
        if (old && (old.snapshot !== snapshot)) { const e = new Error('同一作答编号内容冲突'); e.status = 409; throw e; }
        if (!old) db.prepare('INSERT INTO attempts VALUES(?,?,?,?,?,?,?,?,?,?,?,?)').run(a.eventId, deviceId, a.occurredAt, a.day, a.paperId, a.questionId, a.paperTitle, a.section, a.selected, a.answer, a.correct, snapshot);
      }
      db.prepare('UPDATE devices SET last_seen=? WHERE id=?').run(now, deviceId); db.exec('COMMIT');
      return attempts.map(a => a.eventId);
    } catch (e) { db.exec('ROLLBACK'); throw e; }
  }
  function range(query) {
    const from = query.from || dayOf(Date.now() - 29 * 86400000), to = query.to || dayOf(Date.now());
    const valid = s => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s)) && new Date(s).toISOString().slice(0,10) === s;
    if (!valid(from) || !valid(to) || from > to || Date.parse(to) - Date.parse(from) > 366 * 86400000) fail('日期范围需在一年内');
    return { from, to };
  }
  function summary(query) {
    const { from, to } = range(query);
    const totals = db.prepare(`SELECT count(*) attempts, count(DISTINCT paper_id||':'||question_id) questions,
      coalesce(sum(correct=1),0) correct, coalesce(sum(correct=0),0) wrong, coalesce(sum(correct IS NULL),0) unknown FROM attempts WHERE day BETWEEN ? AND ?`).get(from,to);
    totals.accuracy = totals.correct + totals.wrong ? Math.round(totals.correct * 1000 / (totals.correct + totals.wrong)) / 10 : null;
    const daily = db.prepare(`SELECT day,count(*) attempts,coalesce(sum(correct=1),0) correct,coalesce(sum(correct=0),0) wrong,coalesce(sum(correct IS NULL),0) unknown FROM attempts WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day`).all(from,to);
    const sections = db.prepare(`SELECT section,count(*) attempts,coalesce(sum(correct=1),0) correct,coalesce(sum(correct=0),0) wrong,coalesce(sum(correct IS NULL),0) unknown FROM attempts WHERE day BETWEEN ? AND ? GROUP BY section ORDER BY attempts DESC`).all(from,to);
    return { from,to,timezone:'Asia/Shanghai',totals,daily,sections };
  }
  function list(query) {
    const {from,to} = range(query); const limit = 30;
    const page = Number(query.page || 1); if (!Number.isInteger(page) || page < 1 || page > 100000) fail('页码无效');
    let where = 'a.day BETWEEN ? AND ?', args = [from,to];
    if (query.section) { where += ' AND a.section=?'; args.push(text(query.section,40,'section')); }
    if (query.result) { if (!['wrong','correct','unknown'].includes(query.result)) fail('筛选值无效'); where += query.result === 'unknown' ? ' AND a.correct IS NULL' : ` AND a.correct=${query.result === 'correct' ? 1 : 0}`; }
    if (query.q) { where += ' AND (a.paper_title LIKE ? ESCAPE \'!\' OR json_extract(a.snapshot,\'$.stem\') LIKE ? ESCAPE \'!\')'; const value = '%' + text(query.q,100,'q').replace(/[!%_]/g,'!$&') + '%'; args.push(value,value); }
    const count = db.prepare(`SELECT count(*) count FROM attempts a WHERE ${where}`).get(...args).count;
    const rows = db.prepare(`SELECT a.event_id,a.occurred_at,a.day,a.paper_id,a.question_id,a.paper_title,a.section,a.selected,a.answer,a.correct,
      substr(json_extract(a.snapshot,'$.stem'),1,140) stem,
      (SELECT b.correct FROM attempts b WHERE b.paper_id=a.paper_id AND b.question_id=a.question_id ORDER BY b.occurred_at DESC,b.event_id DESC LIMIT 1) latest_correct
      FROM attempts a WHERE ${where} ORDER BY a.occurred_at DESC,a.event_id DESC LIMIT ? OFFSET ?`).all(...args,limit,(page-1)*limit);
    return {rows,total:count,page,pages:Math.ceil(count/limit)};
  }
  return {db,ingest,range,summary,list,close:()=>db.close()};
}
