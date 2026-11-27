import {randomUUID} from 'node:crypto';
import {hash,secret} from './store.mjs';
const fail=(status,message)=>{throw Object.assign(new Error(message),{status});};
// 参赛昵称只用于展示，不作为身份凭证。
export function pkName(value){if(typeof value!=='string'||!value.trim()||value.trim().length>20)fail(400,'请输入1–20字昵称');return value.trim();}

// 对局保存在SQLite中；服务器重启、刷新页面都不会重置截止时间。
export function openPk(db,now=Date.now){
 db.exec(`CREATE TABLE IF NOT EXISTS pk_invites(token_hash TEXT PRIMARY KEY,expires INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS pk_sessions(token_hash TEXT PRIMARY KEY,name TEXT NOT NULL,expires INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS pk_rooms(id TEXT PRIMARY KEY,phase TEXT NOT NULL,updated INTEGER NOT NULL,data TEXT NOT NULL);`);
 // 抽取曾经做错的不同题目，使用各题最新快照，排除图片及缺少答案的题。
 function questions(){return db.prepare(`WITH latest AS (
 SELECT a.snapshot,row_number() OVER(PARTITION BY a.paper_id,a.question_id ORDER BY a.occurred_at DESC,a.event_id DESC) n
 FROM attempts a WHERE EXISTS(SELECT 1 FROM attempts b WHERE b.paper_id=a.paper_id AND b.question_id=a.question_id AND b.correct=0))
 SELECT snapshot FROM latest WHERE n=1 AND json_extract(snapshot,'$.answer')<>'' AND json_extract(snapshot,'$.hasImages')=0
 AND NOT EXISTS(SELECT 1 FROM json_each(json_extract(snapshot,'$.options')) WHERE trim(value)='') ORDER BY random() LIMIT 5`).all().map(r=>JSON.parse(r.snapshot));}
 // 每次状态变更保存整局快照，避免计分或交卷只写入一半。
 function save(room){room.version=(room.version||0)+1;db.prepare('INSERT OR REPLACE INTO pk_rooms VALUES(?,?,?,?)').run(room.id,room.phase,now(),JSON.stringify(room));}
 // 到时按最后成功保存的答案结算；未作答不计分，同分为平局。
 function settle(room){
  if(room.phase==='waiting'&&now()-room.players[0].seenAt>=30000){room.phase='cancelled';save(room);}
  if(room.phase==='playing'&&(now()>=room.endsAt||room.players.every(p=>p.submittedAt!==null))){
   room.phase='finished';room.players.forEach(p=>{p.score=room.questions.reduce((n,q,i)=>n+Number(p.answers[i]===q.answer),0);p.submittedAt??=room.endsAt;});
   save(room);
  }
  return room;
 }
 // 当前登录身份只能访问自己参加的对局，不接受客户端传入对手身份。
 function current(actor){const row=db.prepare(`SELECT data FROM pk_rooms WHERE EXISTS(SELECT 1 FROM json_each(data,'$.players') WHERE json_extract(value,'$.id')=?) ORDER BY updated DESC,rowid DESC LIMIT 1`).get(actor.id);return row?settle(JSON.parse(row.data)):null;}
 // 结算前只返回自己的选择，答案解析和双方逐题结果在结束后才开放。
 function view(room,actor){
  if(!room||room.phase==='cancelled')return null;
  const self=room.players.find(p=>p.id===actor.id),finished=room.phase==='finished';
  return {id:room.id,version:room.version,phase:room.phase==='playing'&&now()<room.startedAt?'starting':room.phase,startedAt:room.startedAt,endsAt:room.endsAt,
   revision:self.revision,answers:self.answers,submitted:self.submittedAt!==null,
   players:room.players.map(p=>({name:p.name,self:p.id===actor.id,submitted:p.submittedAt!==null,answered:p.answers.filter(Boolean).length,...(finished?{score:p.score,answers:p.answers}:{})})),
   questions:room.phase==='waiting'||now()<room.startedAt?[]:room.questions.map(q=>({type:q.type,section:q.section,material:q.material,stem:q.stem,options:q.options,...(finished?{answer:q.answer,explanation:q.explanation,source:q.source}:{})}))};
 }
 // 轮询保持排队在线；比赛断线仍沿用共同截止时间。
 function state(actor){const room=current(actor);if(room?.phase==='waiting'){room.players[0].seenAt=now();save(room);}return {now:now(),room:view(room,actor)};}
 // 自动匹配最早在线的另一人，同一会话不能与自己匹配。
 function join(actor,name){
  name=pkName(name);let room=current(actor);
  if(room&&['waiting','playing'].includes(room.phase))return state(actor);
  const pool=questions();if(pool.length<5)fail(400,'可用于PK的不同错题不足5道（需有答案且不含图片）');
  const player={id:actor.id,name,answers:Array(5).fill(''),revision:0,submittedAt:null,seenAt:now()};
  for(const row of db.prepare("SELECT data FROM pk_rooms WHERE phase='waiting' ORDER BY updated,rowid").all()){
   const candidate=settle(JSON.parse(row.data));
   if(candidate.phase==='waiting'&&candidate.players[0].id!==actor.id){room=candidate;room.players.push(player);room.phase='playing';room.questions=pool;room.startedAt=now()+3000;room.endsAt=room.startedAt+120000;save(room);return state(actor);}
  }
  room={id:randomUUID(),phase:'waiting',players:[player],questions:[],startedAt:null,endsAt:null};save(room);return state(actor);
 }
 // 取消只允许尚未开局的排队，开局退出不延长时间。
 function cancel(actor){const room=current(actor);if(room?.phase==='waiting'){room.phase='cancelled';save(room);}return state(actor);}
 // 单调版本号防止慢请求覆盖新选择；截止后或已交卷不能改答案。
 function answer(actor,input,submit=false){
  if(!input||typeof input.roomId!=='string')fail(400,'无效对局');
  const room=current(actor);if(!room||room.id!==input.roomId)fail(404,'对局不存在');
  if(room.phase==='finished')return state(actor);
  if(room.phase!=='playing'||now()<room.startedAt)fail(409,'对局尚未开始');
  const player=room.players.find(p=>p.id===actor.id);
  if(player.submittedAt!==null)return state(actor);
  if(!Number.isSafeInteger(input.revision)||input.revision<1||!Array.isArray(input.answers)||input.answers.length!==5)fail(400,'作答格式无效');
  for(let i=0;i<5;i++){
   const v=input.answers[i],q=room.questions[i];
   if(typeof v!=='string'||v.length>4||v!==''&&(!/^[A-D]+$/.test(v)||[...new Set(v)].sort().join('')!==v||[...v].some(c=>c.charCodeAt(0)-65>=q.options.length)||q.type!=='multiple'&&v.length!==1))fail(400,'答案选项无效');
  }
  if(input.revision<player.revision)return state(actor);
  if(input.revision===player.revision&&JSON.stringify(player.answers)!==JSON.stringify(input.answers))fail(409,'另一页面已修改答案，请刷新后继续');
  player.answers=input.answers;player.revision=input.revision;
  if(submit)player.submittedAt=now();save(room);return state(actor);
 }
 // 邀请凭证仅授予PK权限，10分钟有效且只能兑换一次。
 function invite(){const token=secret();db.prepare('DELETE FROM pk_invites WHERE expires<=?').run(now());db.prepare('INSERT INTO pk_invites VALUES(?,?)').run(hash(token),now()+600000);return {token,expiresIn:600};}
 // 先校验昵称再消费邀请，访客登录7天有效且不授予看板权限。
 function login(input){
  const name=pkName(input?.name);if(typeof input.token!=='string'||!/^[\w-]{40,100}$/.test(input.token))fail(400,'邀请链接无效');
  const token=secret();db.exec('BEGIN IMMEDIATE');
  try{if(!db.prepare('DELETE FROM pk_invites WHERE token_hash=? AND expires>? RETURNING token_hash').get(hash(input.token),now()))fail(401,'邀请已过期或使用，请让邀请人重新生成');
   db.prepare('DELETE FROM pk_sessions WHERE expires<=?').run(now());db.prepare('INSERT INTO pk_sessions VALUES(?,?,?)').run(hash(token),name,now()+7*86400000);db.exec('COMMIT');return {token,name};
  }catch(e){db.exec('ROLLBACK');throw e;}
 }
 return {state,join,cancel,answer,invite,login};
}
