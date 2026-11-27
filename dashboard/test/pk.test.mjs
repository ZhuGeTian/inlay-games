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
// 每题独立截止，旧题慢请求和越题修改都不能改变有效答案。
test('每题120秒、提前换题、旧请求隔离及十分钟超时',()=>{
 const store=openStore(':memory:');let time=Date.now();const pk=openPk(store.db,()=>time),a={id:'a'},b={id:'b'};
 try{
  assert.throws(()=>pk.join(a,'甲'),{status:400});seedPk(store);pk.join(a,'甲');let room=pk.join(b,'乙').room;const start=room.startedAt;time=start;
  const source=JSON.parse(store.db.prepare('SELECT data FROM pk_rooms WHERE id=?').get(room.id).data);const answers=Array(5).fill('');answers[0]=source.questions[0].answer;
  room=pk.state(a).room;assert.equal(room.questions.filter(Boolean).length,1);assert.equal(room.endsAt-start,120000);
  assert.throws(()=>pk.answer(a,{roomId:room.id,questionIndex:0,revision:1,answers:[...answers.slice(0,1),'B','','','']}),{status:400});
  pk.answer(a,{roomId:room.id,questionIndex:0,revision:1,answers},true);
  time+=1000;room=pk.answer(b,{roomId:room.id,questionIndex:0,revision:1,answers:Array(5).fill('')},true).room;
  assert.equal(room.questionIndex,1);assert.equal(room.endsAt-time,120000);assert.equal(room.submitted,false);
  const stale=pk.answer(a,{roomId:room.id,questionIndex:0,revision:9,answers},true).room;assert.equal(stale.questionIndex,1);assert.equal(stale.submitted,false);
  time=room.endsAt;room=pk.state(a).room;assert.equal(room.questionIndex,2);assert.equal(room.endsAt-time,120000);
  time=room.endsAt+240000;room=pk.state(a).room;assert.equal(room.phase,'finished');assert.deepEqual(room.players.map(p=>p.score),[1,0]);
  pk.join(a,'甲');room=pk.join(b,'乙').room;time=room.startedAt+599999;assert.equal(pk.state(a).room.phase,'playing');time++;assert.equal(pk.state(a).room.phase,'finished');
 }finally{store.close();}
});
// 每道题都提前提交，仍须经过完整五题，重启后保留题号和截止时间。
test('逐题提前提交和重启恢复',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'pk-test-'));let store;let time=Date.now();const a={id:'a'},b={id:'b'};
 try{store=openStore(join(dir,'db.sqlite'));seedPk(store);let pk=openPk(store.db,()=>time);pk.join(a,'甲');let r=pk.join(b,'乙').room;time=r.startedAt;
  for(let i=0;i<5;i++){
   const input={roomId:r.id,questionIndex:i,revision:i+1,answers:Array(5).fill('')};pk.answer(a,input,true);r=pk.answer(b,input,true).room;
   assert.equal(r.phase,i===4?'finished':'playing');
   if(i<4){assert.equal(r.questionIndex,i+1);assert.equal(r.endsAt-time,120000);}
   if(i===1){const end=r.endsAt;store.close();store=openStore(join(dir,'db.sqlite'));pk=openPk(store.db,()=>time);assert.equal(pk.state(a).room.endsAt,end);assert.equal(pk.state(a).room.questionIndex,2);}
  }
  assert.deepEqual(r.players.map(p=>p.score),[0,0]);
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
  const input={roomId:room.id,questionIndex:0,revision:1,answers:Array(5).fill('')};assert.equal((await req('pk/submit',input,guest,'https://evil.example')).status,403);
  assert.equal((await req('pk/submit',input,owner)).status,200);room=(await req('pk/submit',input,guest)).data.room;assert.equal(room.questionIndex,1);assert.equal(room.phase,'playing');
  time=room.endsAt+360000;assert.equal((await req('pk/state',undefined,guest)).data.room.phase,'finished');
  await req('pk/logout',{},guest);assert.equal((await req('pk/state',undefined,guest)).status,401);
  const expired=(await req('pk/invite',{},owner)).data.token;time+=600000;assert.equal((await req('pk/login',{token:expired,name:'乙'})).status,401);
 }finally{await app.close();}
});
