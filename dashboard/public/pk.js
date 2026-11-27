const $=id=>document.getElementById(id);
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
let session=null,game=null,index=0,draft=Array(5).fill(''),revision=0,acked=0,saving=false,joining=false,submitting=false,polling=false,renderKey='',serverAt=0,receivedAt=0;
const inviteToken=new URLSearchParams(location.hash.slice(1)).get('invite');
// 所有写入采用同源验证，访客没有私人看板接口权限。
async function api(path,data){const r=await fetch('/study/api/pk/'+path,{method:data===undefined?'GET':'POST',headers:data===undefined?{}:{'Content-Type':'application/json','X-Study-Request':'1'},body:data===undefined?undefined:JSON.stringify(data)});const value=await r.json();if(!r.ok){const e=Error(value.error||'请求失败');e.status=r.status;throw e;}return value;}
// 错误显示在当前页面，避免网络失败后静默丢失选择。
function notice(text){$('pk-notice').textContent=text;}
function run(fn){return async e=>{try{notice('');await fn(e);}catch(e){notice(e.message);}};}
// 用服务端时间和单调时钟计算剩余时间，本地系统时间不影响交卷期限。
function serverNow(){return serverAt+performance.now()-receivedAt;}
function tick(){if(!game)return;const ms=game.phase==='starting'?game.startedAt-serverNow():game.endsAt-serverNow();$('pk-timer').textContent=game.phase==='finished'?'已结束':game.phase==='waiting'?'':game.phase==='starting'?'准备 '+Math.max(0,Math.ceil(ms/1000))+'秒':Math.floor(Math.max(0,Math.ceil(ms/1000))/60)+':'+String(Math.max(0,Math.ceil(ms/1000))%60).padStart(2,'0');if(game.phase==='playing'&&ms<=0){$('pk-submit').disabled=true;$('pk-question').querySelectorAll('input').forEach(n=>n.disabled=true);}}
// 接收状态时不覆盖尚未保存的本地选择，结算和新局以服务器为准。
function apply(value){
 if(value.now<serverAt||value.room?.id===game?.id&&value.room&&value.room.version<game.version)return;
 serverAt=value.now;receivedAt=performance.now();const room=value.room,newGame=room?.id!==game?.id;
 const newQuestion=room?.questionIndex!==game?.questionIndex;
 if(newGame||newQuestion||room?.phase==='finished'||revision<=acked){draft=room?.answers?.slice()||Array(5).fill('');revision=room?.revision||0;acked=revision;}
 else if(room){acked=Math.max(acked,room.revision);}
 const entering=room&&['starting','playing'].includes(room.phase)&&(!game||game.phase==='waiting');
 if(newGame||newQuestion){index=room?.questionIndex||0;renderKey='';$('pk-save').textContent='';}game=room;if(game&&game.phase!=='finished')index=game.questionIndex;render();if(entering)$('pk-game').scrollIntoView({block:'start'});
}
// 只重建发生切换的题目区域，轮询不打断正在选择的键盘焦点。
function renderQuestion(){
 const host=$('pk-question'),q=game?.questions[index];
 const key=[game?.id,game?.phase,index,game?.submitted].join(':');
 if(key===renderKey){host.querySelectorAll('input').forEach(n=>{n.checked=draft[index].includes(n.value);});return;}
 renderKey=key;host.replaceChildren();host.scrollTop=0;if(!q)return;
 host.append(el('p',`第 ${index+1}/5 题 · ${q.section}`,'hint'));if(q.material)host.append(el('p',q.material,'question-stem'));host.append(el('p',q.stem,'question-stem'));
 const group=el('fieldset');group.append(el('legend',q.type==='multiple'?'请选择答案（多选）':'请选择答案（单选）'));
 q.options.forEach((text,i)=>{const input=el('input'),letter=String.fromCharCode(65+i),label=el('label',undefined,'option');input.type=q.type==='multiple'?'checkbox':'radio';input.name='pk-answer';input.value=letter;input.checked=draft[index].includes(letter);input.disabled=game.phase!=='playing'||game.submitted||serverNow()>=game.endsAt;
  input.onchange=()=>{draft[index]=[...group.querySelectorAll('input:checked')].map(n=>n.value).join('');revision++;$('pk-save').textContent='正在保存选择…';save();renderPages();};label.append(input,el('span',letter+'. '+text));group.append(label);});host.append(group);
 if(game.phase==='finished'){host.append(el('p','参考答案：'+q.answer,'detail-answer'));for(const p of game.players)host.append(el('p',p.name+(p.self?'（你）':'')+'：'+(p.answers[index]||'未作答')+' · '+(p.answers[index]===q.answer?'正确':'错误')));if(q.explanation)host.append(el('p',q.explanation,'detail-explanation'));if(q.source)host.append(el('p','来源：'+q.source,'detail-source'));}
}
// 五个导航按钮反映自己的已选状态，比赛中锁定当前题，结束后可逐题复盘。
function renderPages(){const host=$('pk-pages');host.replaceChildren();for(let i=0;i<5;i++){const b=el('button',String(i+1)+(draft[i]?' ✓':''),'secondary');b.setAttribute('aria-label','第'+(i+1)+'题');b.setAttribute('aria-current',String(i===index));b.disabled=game?.phase!=='finished'&&i!==game?.questionIndex;b.onclick=()=>{index=i;render();};host.append(b);}}
// 大厅、等待和结果共用一页；提交按钮在题目滚动区之外。
function render(){
 const active=game&&['waiting','starting','playing'].includes(game.phase);
 $('match').disabled=Boolean(active)||joining;$('match').textContent=game?.phase==='finished'?'再匹配一局':'开始匹配';$('player-name').disabled=Boolean(active);
 $('cancel-match').hidden=game?.phase!=='waiting';$('match-status').textContent=game?.phase==='waiting'?'匹配中，等待另一位在线玩家。':active?'已匹配，两人共用同一截止时间。':'点击开始匹配，等待另一人加入。';
 $('pk-game').hidden=!game||game.phase==='waiting';if(!game)return;
 $('pk-players').replaceChildren();for(const p of game.players)$('pk-players').append(el('p',p.name+(p.self?'（你）':'')+' · '+(game.phase==='finished'?p.score+'/5 分':p.submitted?'本题已提交':p.answered+'/5 已选')));
 const finished=game.phase==='finished';$('game-title').textContent=finished?'本局结果':game.phase==='starting'?'匹配成功，准备开始':`第 ${game.questionIndex+1}/5 题 · 本题限时2分钟`;
 $('pk-result').textContent=finished?(game.players[0].score===game.players[1].score?'平局':game.players.reduce((a,b)=>a.score>b.score?a:b).name+' 获胜'):game.submitted?'本题已提交，等待对手或本题超时后进入下一题。':'';
 $('pk-submit').hidden=finished||game.phase==='starting';$('pk-submit').disabled=game.submitted||submitting||serverNow()>=game.endsAt;
 $('pk-prev').hidden=!finished;$('pk-next').hidden=!finished;$('pk-prev').disabled=index===0;$('pk-next').disabled=index===4;
 renderPages();$('pk-pages').hidden=!game.questions.length;renderQuestion();tick();
 if(finished)$('pk-save').textContent='本局已结算，成绩独立保存；可切换题号查看双方答案。';
}
// 串行保存完整选择与版本号；失败保留草稿，下一轮轮询继续重试。
async function save(){
 if(saving||!game||game.phase!=='playing'||game.submitted||revision<=acked)return;
 saving=true;const roomId=game.id;
 try{while(game?.id===roomId&&game.phase==='playing'&&!game.submitted&&revision>acked){const sent=revision,result=await api('answer',{roomId,questionIndex:game.questionIndex,revision:sent,answers:draft.slice()});if(game?.id!==roomId)return;acked=Math.max(acked,sent);apply(result);}$('pk-save').textContent=game?.phase==='finished'?'本局已结算。':'选择已保存。';}
 catch(e){$('pk-save').textContent='选择尚未保存：'+e.message+'，正在等待重试；截止前需保持联网。';}
 finally{saving=false;}
}
// 轮询用于匹配、重连和超时结算；服务端始终裁定最后有效选择。
async function poll(){if(!session||polling)return;polling=true;try{apply(await api('state'));await save();}catch(e){notice(e.message);if(e.status===401){session=null;$('pk-app').hidden=true;$('pk-login').hidden=false;}}finally{polling=false;}}
// 主人沿用看板会话，受邀玩家使用独立的PK登录。
async function start(){
 try{session=await api('session');$('pk-login').hidden=true;$('pk-app').hidden=false;$('owner-back').hidden=!session.owner;$('invite-area').hidden=!session.owner;$('guest-logout').hidden=session.owner;
  try{$('player-name').value=session.name||localStorage.getItem('study-pk-name')||'我';}catch{$('player-name').value=session.name||'我';}await poll();
 }catch(e){$('pk-login').hidden=false;$('pk-app').hidden=true;$('pk-login-form').hidden=!inviteToken;$('no-invite').hidden=Boolean(inviteToken);if(e.status!==401)notice(e.message);}
}
$('pk-login-form').onsubmit=run(async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{await api('login',{token:inviteToken,name:$('guest-name').value});history.replaceState(null,'',location.pathname);await start();}finally{b.disabled=false;}});
$('match-form').onsubmit=run(async e=>{e.preventDefault();if(joining)return;joining=true;render();try{apply(await api('join',{name:$('player-name').value}));try{localStorage.setItem('study-pk-name',$('player-name').value);}catch{}}finally{joining=false;render();}});
$('cancel-match').onclick=run(async()=>apply(await api('cancel',{})));
$('invite').onclick=run(async()=>{const r=await api('invite',{});$('invite-url').value=location.origin+'/study/pk/#invite='+r.token;$('invite-result').hidden=false;});
$('copy-invite').onclick=run(async()=>{await navigator.clipboard.writeText($('invite-url').value);notice('邀请链接已复制。');});
$('guest-logout').onclick=run(async()=>{await api('logout',{});session=null;location.reload();});
$('pk-prev').onclick=()=>{index=Math.max(0,index-1);render();};$('pk-next').onclick=()=>{index=Math.min(4,index+1);render();};
$('pk-submit').onclick=run(async()=>{if(submitting||!game||game.submitted)return;if(!draft[index]&&!confirm('本题尚未选择答案，确定提交空白答案？'))return;submitting=true;render();const submittedIndex=game.questionIndex;try{const result=await api('submit',{roomId:game.id,questionIndex:submittedIndex,revision:++revision,answers:draft.slice()});acked=revision;apply(result);$('pk-save').textContent=game?.phase==='finished'?'本局已结算。':game?.questionIndex===submittedIndex?'本题已提交。':'已进入下一题。';}finally{submitting=false;render();}});
// 展示高度在浏览器持久化，长题可滚动且操作按钮始终位于外部。
try{const v=localStorage.getItem('study-pk-height');if(['320','520','800'].includes(v))$('pk-height').value=v;}catch{}
function height(){document.documentElement.style.setProperty('--pk-height',$('pk-height').value+'px');try{localStorage.setItem('study-pk-height',$('pk-height').value);}catch{}}
$('pk-height').onchange=height;height();start();setInterval(poll,2000);setInterval(tick,250);
