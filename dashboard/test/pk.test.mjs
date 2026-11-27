import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createApp,passwordHash} from '../server.mjs';
import {openStore} from '../store.mjs';
import {openPk} from '../pk.mjs';
// 测试题覆盖重复错题、已纠正题、无答案和图片题，只写独立数据库。
function seedPk(store,count=7){
 store.db.prepare('INSERT OR IGNORE INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run('test','PK fixture','pk-test-hash',Date.now());
 const base={occurredAt:Date.now(),paperId:'a'.repeat(64),type:'single',paperTitle:'PK测试',section:'言语理解',material:'',stem:'测试题',options:['甲','乙','丙','丁'],selected:'A',answer:'B',explanation:'不可提前展示的解析',source:'测试来源',hasImages:false};
 for(let i=0;i<count;i++)store.ingest('test',[{...base,eventId:randomUUID(),questionId:'q'+i,stem:'第'+i+'题',...(i===1?{type:'multiple',answer:'BC'}:i===2?{type:'boolean',options:['正确','错误']}:{} )}]);
 for(let i=0;i<3;i++)store.ingest('test',[{...base,eventId:randomUUID(),questionId:'q0'}]);
 store.ingest('test',[{...base,eventId:randomUUID(),questionId:'image',hasImages:true},{...base,eventId:randomUUID(),questionId:'unknown',answer:''},{...base,eventId:randomUUID(),questionId:'blank-option',options:['','乙','丙','丁']}]);
}
// 使用可控时钟验证120秒边界，避免测试依赖真实等待。
test('PK匹配、题目去重、答案隔离、超时、防重放和退出排队',()=>{
 const store=openStore(':memory:');let time=Date.now();const pk=openPk(store.db,()=>time),a={id:'a'},b={id:'b'},c={id:'c'};
 try{
  assert.throws(()=>pk.join(a,'甲'),{status:400});seedPk(store);
  const count=store.db.prepare('SELECT count(*) n FROM attempts').get().n;
  assert.throws(()=>pk.join(a,''),{status:400});let waiting=pk.join(a,'甲');assert.equal(waiting.room.phase,'waiting');
  assert.equal(pk.join(a,'甲').room.id,waiting.room.id);assert.equal(pk.state(a).room.questions.length,0);
  const joined=pk.join(b,'乙'),id=joined.room.id;assert.equal(joined.room.players.length,2);assert.equal(joined.room.phase,'starting');assert.equal(joined.room.endsAt-joined.room.startedAt,120000);
  assert.throws(()=>pk.answer(a,{roomId:id,revision:1,answers:Array(5).fill('B')}),{status:409});
  time+=3000;let game=pk.state(a).room;assert.equal(game.phase,'playing');assert.equal(game.questions.length,5);assert.equal(new Set(game.questions.map(q=>q.stem)).size,5);assert(game.questions.every(q=>!q.answer&&!q.explanation&&!q.source));
  const privateRoom=JSON.parse(store.db.prepare('SELECT data FROM pk_rooms WHERE id=?').get(id).data);assert(privateRoom.questions.every(q=>!['image','unknown','blank-option'].includes(q.questionId)));
  const answers=privateRoom.questions.map(q=>q.answer);
  assert.throws(()=>pk.answer(c,{roomId:id,revision:1,answers}),{status:404});
  assert.throws(()=>pk.answer(a,{roomId:id,revision:1,answers:['Z',...answers.slice(1)]}),{status:400});
  pk.answer(a,{roomId:id,revision:2,answers});pk.answer(a,{roomId:id,revision:1,answers:Array(5).fill('A')});assert.deepEqual(pk.state(a).room.answers,answers);
  assert.throws(()=>pk.answer(a,{roomId:id,revision:2,answers:Array(5).fill('A')}),{status:409});
  const other=pk.state(b).room;assert(!other.players[0].answers);assert(!other.players[0].score);assert.deepEqual(other.answers,Array(5).fill(''));
  pk.answer(a,{roomId:id,revision:3,answers},true);assert.equal(pk.state(a).room.phase,'playing');assert.equal(pk.state(a).room.submitted,true);
  pk.answer(a,{roomId:id,revision:4,answers:Array(5).fill('A')});assert.deepEqual(pk.state(a).room.answers,answers);
  time=privateRoom.endsAt-1;pk.answer(b,{roomId:id,revision:1,answers:[answers[0],'','','','']});
  time=privateRoom.endsAt;const ended=pk.answer(b,{roomId:id,revision:2,answers},true).room;assert.equal(ended.phase,'finished');assert.deepEqual(ended.players.map(p=>p.score),[5,1]);assert(ended.questions.every(q=>q.answer));
  assert.deepEqual(pk.answer(b,{roomId:id,revision:2,answers},true).room.players,ended.players);
  assert.equal(store.db.prepare('SELECT count(*) n FROM attempts').get().n,count);
  pk.join(a,'甲');pk.cancel(a);assert.equal(pk.state(a).room,null);
  pk.join(a,'甲');time+=30000;assert.equal(pk.state(a).room,null);assert.equal(pk.join(c,'丙').room.phase,'waiting');
 }finally{store.close();}
});
// 持久化恢复后仍保留截止时间，双方提前交卷也能确定平局。
test('PK重启恢复与提前平局',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'pk-test-'));let store;let time=Date.now();const a={id:'a'},b={id:'b'};
 try{store=openStore(join(dir,'db.sqlite'));seedPk(store);let pk=openPk(store.db,()=>time);pk.join(a,'甲');let r=pk.join(b,'乙').room;const end=r.endsAt;store.close();store=openStore(join(dir,'db.sqlite'));pk=openPk(store.db,()=>time);assert.equal(pk.state(a).room.endsAt,end);time+=3000;
  pk.answer(a,{roomId:r.id,revision:1,answers:Array(5).fill('')},true);r=pk.answer(b,{roomId:r.id,revision:1,answers:Array(5).fill('')},true).room;assert.equal(r.phase,'finished');assert.deepEqual(r.players.map(p=>p.score),[0,0]);assert(time<end);
 }finally{store?.close();await rm(dir,{recursive:true,force:true});}
});
// 真实HTTP验证访客只持有PK权限，不能读取个人记录或生成新邀请。
test('PK邀请登录、同源校验、访客隔离和双人提交',async()=>{
 let time=Date.now();const app=createApp({dbPath:':memory:',origin:'http://localhost',passwordHash:passwordHash('pk-test'),pkNow:()=>time});
 try{seedPk(app.store);await new Promise(r=>app.server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+app.server.address().port+'/study/api/';
  const req=async(path,data,cookie='',origin='http://localhost')=>{const r=await fetch(base+path,{method:data===undefined?'GET':'POST',headers:{cookie,origin,'x-study-request':'1','content-type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});return {status:r.status,data:await r.json(),cookie:r.headers.get('set-cookie')?.split(';')[0]};};
  assert.equal((await req('pk/state')).status,401);assert.equal((await req('pk/invite',{})).status,401);
  const owner=(await req('login',{password:'pk-test'})).cookie;
  const token=(await req('pk/invite',{},owner)).data.token;
  assert.equal((await req('pk/login',{token,name:'乙'},'','https://evil.example')).status,403);
  assert.equal((await req('pk/login',{token,name:''})).status,400);
  const guest=(await req('pk/login',{token,name:'乙'})).cookie;assert(guest.startsWith('moyu_pk='));assert.equal((await req('pk/login',{token,name:'丙'})).status,401);
  for(const endpoint of ['summary','attempts','devices','analyses','session'])assert.equal((await req(endpoint,undefined,guest)).status,401);
  assert.equal((await req('pk/invite',{},guest)).status,403);assert.equal((await req('pairing',{},guest)).status,401);
  await req('pk/join',{name:'甲'},owner);let room=(await req('pk/join',{name:'乙'},guest)).data.room;time+=3000;
  assert.equal((await req('pk/state',undefined,guest)).data.room.id,room.id);
  const input={roomId:room.id,revision:1,answers:Array(5).fill('')};assert.equal((await req('pk/submit',input,guest,'https://evil.example')).status,403);
  assert.equal((await req('pk/submit',input,owner)).status,200);room=(await req('pk/submit',input,guest)).data.room;assert.equal(room.phase,'finished');
  await req('pk/logout',{},guest);assert.equal((await req('pk/state',undefined,guest)).status,401);
  const expired=(await req('pk/invite',{},owner)).data.token;time+=600000;assert.equal((await req('pk/login',{token:expired,name:'乙'})).status,401);
 }finally{await app.close();}
});
