import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {backup,DatabaseSync} from 'node:sqlite';
import {createApp,passwordHash,analyze} from '../server.mjs';
import {dayOf,validateAttempt} from '../store.mjs';
const attempt=(extra={})=>({eventId:randomUUID(),occurredAt:Date.now(),paperId:'a'.repeat(64),questionId:'q1',type:'single',paperTitle:'测试卷',section:'言语理解',material:'',stem:'这里填写____。',options:['甲','乙','丙','丁'],selected:'A',answer:'B',explanation:'测试解析',source:'用户提供',...extra});
test('登录、配对、幂等同步、统计、撤销、AI和在线备份',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'study-test-'));let app;
 try{
  app=createApp({dbPath:join(dir,'test.sqlite'),origin:'http://localhost',passwordHash:passwordHash('test-only-password'),ai:{base:'https://example.com',model:'test',key:'test-secret'},fetcher:async(url,opt)=>{const payload=JSON.parse(opt.body);assert.equal(payload.messages.length,2);assert(!opt.body.includes('test-secret'));return new Response(JSON.stringify({choices:[{message:{content:'样本有限，建议复习词语辨析。'}}]}));}});
  await new Promise(r=>app.server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+app.server.address().port+'/study/api/';
  let cookie='';
  async function req(path,data,headers={}){const r=await fetch(base+path,{method:data===undefined?'GET':'POST',headers:{...(data===undefined?{}:{'content-type':'application/json',origin:'http://localhost','x-study-request':'1'}),cookie,...headers},body:data===undefined?undefined:JSON.stringify(data)});return {status:r.status,value:await r.json(),headers:r.headers};}
  assert.equal((await req('summary')).status,401);
  assert.equal((await req('login',{password:'wrong'})).status,401);
  assert.equal((await req('login',{password:'test-only-password'},{origin:'https://attacker.example'})).status,403);
  const login=await req('login',{password:'test-only-password'});assert.equal(login.status,200);cookie=login.headers.get('set-cookie').split(';')[0];assert(login.headers.get('set-cookie').includes('HttpOnly'));
  const code=(await req('pairing',{})).value.code;
  const paired=await req('pair',{code,name:'测试 IDE'});assert.equal(paired.status,200);const auth={authorization:'Bearer '+paired.value.token};
  assert.equal((await req('pair',{code,name:'重放'})).status,401);
  assert.equal((await req('attempts',{attempts:[attempt()]})).status,401);
  const first=attempt({occurredAt:Date.parse('2026-11-10T15:59:59Z')}),second=attempt({occurredAt:Date.parse('2026-11-10T16:00:00Z'),selected:'B'}),unknown=attempt({answer:'',questionId:'q2'});
  assert.equal((await req('attempts',{attempts:[first,second,unknown]},auth)).status,200);
  assert.equal((await req('attempts',{attempts:[first]},auth)).status,200);
  assert.equal((await req('attempts',{attempts:[{...first,selected:'C'}]},auth)).status,409);
  assert.equal((await req('attempts',{attempts:[attempt(),attempt({answer:'Z'})]},auth)).status,400);
  const s=(await req('summary?from=2026-11-10&to=2026-11-11')).value;assert.equal(s.totals.attempts,3);assert.equal(s.totals.accuracy,50);assert.equal(s.totals.unknown,1);assert.equal(s.totals.questions,2);assert.equal(s.daily[0].day,'2026-11-10');assert.equal(s.daily[0].attempts,1);
  const wrong=(await req('attempts?from=2026-11-10&to=2026-11-11&result=wrong')).value;assert.equal(wrong.total,1);assert.equal(wrong.rows[0].latest_correct,1);
  assert.equal((await req('attempts?q=%25')).value.total,0);
  assert.equal((await req('attempts/'+first.eventId)).value.stem,first.stem);
  assert.equal((await req('summary?from=2026-02-30')).status,400);
  assert.equal((await req('analyze',{from:'2026-11-10',to:'2026-11-11'})).status,200);assert.equal((await req('analyses')).value.length,1);
  assert.equal((await req('analyze',{})).status,429);
  await backup(app.store.db,join(dir,'backup.sqlite'));const restored=new DatabaseSync(join(dir,'backup.sqlite'));assert.equal(restored.prepare('SELECT count(*) n FROM attempts').get().n,3);restored.close();
  await req('revoke',{id:paired.value.deviceId});assert.equal((await req('attempts',{attempts:[attempt()]},auth)).status,401);
  const tokenRows=app.store.db.prepare('SELECT token_hash FROM devices').all();assert(!JSON.stringify(tokenRows).includes(paired.value.token));
  await req('logout',{});assert.equal((await req('summary')).status,401);
 }finally{if(app)await app.close();await rm(dir,{recursive:true,force:true});}
});
test('输入边界和未知答案不猜测',()=>{
 assert.equal(dayOf(Date.parse('2026-11-10T16:00:00Z')),'2026-11-11');
 assert.equal(validateAttempt(attempt({answer:''})).correct,null);
 assert.throws(()=>validateAttempt(attempt({selected:'AA'})));
 assert.throws(()=>validateAttempt(attempt({occurredAt:Date.now()+600000})));
 assert.throws(()=>validateAttempt(attempt({options:['A','B']})));
 assert.throws(()=>validateAttempt(attempt({type:'multiple',answer:'A'})));
 assert.equal(validateAttempt(attempt({type:'multiple',selected:'A',answer:'AB'})).correct,0);
});
test('AI无配置、空记录和上游错误不会伪造结果',async()=>{
 const store={summary:()=>({totals:{attempts:1},from:'2026-11-10',to:'2026-11-11'}),list:()=>({rows:[]})};
 await assert.rejects(analyze(store,{},{}),{status:503});
 await assert.rejects(analyze({...store,summary:()=>({totals:{attempts:0}})},{},{base:'https://example.com',model:'test',key:'secret'}),{status:400});
 await assert.rejects(analyze(store,{},{base:'https://example.com',model:'test',key:'secret'},async()=>new Response('sensitive provider text',{status:402})),e=>e.status===502&&!e.message.includes('sensitive'));
});
