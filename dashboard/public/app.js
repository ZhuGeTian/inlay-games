const $=id=>document.getElementById(id);
const state={page:1,session:null,request:0};
const day=ms=>new Date(ms+8*3600000).toISOString().slice(0,10);
const date=ms=>new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(ms);
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const pct=(correct,wrong)=>correct+wrong?(100*correct/(correct+wrong)).toFixed(1)+'%':'—';
function notice(message){$('notice').textContent=message;$('notice').hidden=!message;}
function loggedOut(){state.request++;state.session=null;$('app').hidden=true;$('login').hidden=false;for(const d of document.querySelectorAll('dialog[open]'))d.close();}
async function api(path,data){const r=await fetch('/study/api/'+path,{method:data===undefined?'GET':'POST',credentials:'same-origin',headers:data===undefined?{}:{'Content-Type':'application/json','X-Study-Request':'1'},body:data===undefined?undefined:JSON.stringify(data)});const value=await r.json();if(!r.ok){if(r.status===401&&!path.startsWith('login'))loggedOut();throw Error(value.error||'请求失败');}return value;}
function run(fn){return async event=>{try{notice('');await fn(event);}catch(e){notice(e.message);}};}
function query(){return new URLSearchParams({from:$('from').value,to:$('to').value,page:state.page,q:$('query').value,section:$('section-filter').value,result:$('result-filter').value});}
function period(days){$('to').value=day(Date.now());$('from').value=day(Date.now()-(days-1)*86400000);document.querySelectorAll('[data-period]').forEach(n=>n.classList.toggle('active',Number(n.dataset.period)===days));state.page=1;}
function metrics(s){const t=s.totals;$('metrics').replaceChildren();for(const [label,value,note] of [['累计作答',t.attempts,'覆盖 '+t.questions+' 道不同题目'],['正确率',t.accuracy===null?'—':t.accuracy+'%','答对 '+t.correct+' / 已判分 '+(t.correct+t.wrong)],['错误作答',t.wrong,'重复练习分别记录，历史错误保留'],['待判分',t.unknown,'缺少参考答案，暂不计入正确率']]){const card=el('article',undefined,'metric');card.append(el('span',label,'metric-label'),el('strong',String(value),'metric-value'),el('span',note,'metric-note'));$('metrics').append(card);}}
// 图表用真实日期补零；点击一天即可查看当天作答。
function trend(s){const host=$('trend');host.replaceChildren();const days=[];const map=new Map(s.daily.map(d=>[d.day,d]));for(let ms=Date.parse(s.from);ms<=Date.parse(s.to);ms+=86400000){const key=new Date(ms).toISOString().slice(0,10);days.push(map.get(key)||{day:key,attempts:0,correct:0,wrong:0,unknown:0});}const max=Math.max(1,...days.map(d=>d.attempts));const chart=el('div',undefined,'bars');for(const d of days){const button=el('button',undefined,'bar-column');button.title=d.day+' · '+d.attempts+'次 · 正确率 '+pct(d.correct,d.wrong);button.setAttribute('aria-label',button.title);const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 20 140');svg.setAttribute('preserveAspectRatio','none');let y=140;for(const [key,color] of [['correct','#38644d'],['wrong','#ce8a61'],['unknown','#cbd2c9']]){const h=130*d[key]/max;y-=h;const r=document.createElementNS(svg.namespaceURI,'rect');for(const [k,v] of Object.entries({x:2,y,width:16,height:h,fill:color,rx:1}))r.setAttribute(k,v);svg.append(r);}button.append(svg);button.onclick=run(async()=>{$('from').value=d.day;$('to').value=d.day;state.page=1;await refresh();$('records-panel').scrollIntoView({behavior:'smooth'});});chart.append(button);}const labels=el('div',undefined,'chart-labels');labels.append(el('span',s.from),el('span','峰值 '+max+' 次'),el('span',s.to));host.append(chart,labels);}
function sections(s){$('sections').replaceChildren();for(const row of s.sections){const n=el('div',undefined,'section-row'),meta=el('div',undefined,'section-meta');meta.append(el('strong',row.section),el('span',pct(row.correct,row.wrong)+' · '+row.attempts+'次'));const bar=el('progress');bar.max=100;bar.value=row.correct+row.wrong?100*row.correct/(row.correct+row.wrong):0;bar.setAttribute('aria-label',row.section+'正确率');n.append(meta,bar);$('sections').append(n);}if(!s.sections.length)$('sections').append(el('p','还没有板块数据。开始练习后，这里会逐渐清晰。','muted'));const old=$('section-filter').value;$('section-filter').replaceChildren(el('option','全部板块'));$('section-filter').firstChild.value='';for(const row of s.sections){const o=el('option',row.section);o.value=row.section;$('section-filter').append(o);}if(old&&!s.sections.some(r=>r.section===old)){const o=el('option',old);o.value=old;$('section-filter').append(o);}$('section-filter').value=old;}
function records(data){$('records').replaceChildren();$('records-empty').hidden=data.total>0;$('record-count').textContent=data.total+' 次作答';$('page-label').textContent='第 '+state.page+' / '+Math.max(1,data.pages)+' 页';$('previous').disabled=state.page<=1;$('next').disabled=state.page>=data.pages;for(const r of data.rows){const tr=el('tr');tr.append(el('td',date(r.occurred_at)));const question=el('td'),button=el('button',r.stem,'question-link');button.onclick=run(()=>detail(r.event_id));question.append(button,el('span',r.paper_title+' · '+r.question_id,'paper-label'));tr.append(question,el('td',r.section),el('td',r.selected));const result=el('td');result.append(el('span',r.correct===null?'未判分':r.correct?'正确':'错误','result '+(r.correct===null?'unknown':r.correct?'correct':'wrong')));if(r.correct===0&&r.latest_correct===1)result.append(el('small','后来已答对','corrected'));tr.append(result);$('records').append(tr);}}
// 固定底页以兼容手机弹层滚动，关闭最后一个弹层时恢复原位置。
let modalScroll=null;
function openDialog(dialog){
 if(dialog.open)return;
 dialog.showModal();
 if(modalScroll)return;
 modalScroll={x:window.scrollX,y:window.scrollY};
 document.body.style.setProperty('--modal-top',-modalScroll.y+'px');
 document.body.style.setProperty('--modal-gutter',(window.innerWidth-document.documentElement.clientWidth)+'px');
 document.body.classList.add('modal-open');
}
// 原生关闭、Esc和退出登录统一释放滚动锁。
function releaseModalScroll(){
 if(!modalScroll||document.querySelector('dialog[open]'))return;
 const {x,y}=modalScroll;modalScroll=null;
 document.body.classList.remove('modal-open');
 document.body.style.removeProperty('--modal-top');
 document.body.style.removeProperty('--modal-gutter');
 window.scrollTo({left:x,top:y,behavior:'instant'});
}
document.querySelectorAll('dialog').forEach(dialog=>dialog.addEventListener('close',releaseModalScroll));
async function detail(id){const r=await api('attempts/'+encodeURIComponent(id)),host=$('detail-content');host.replaceChildren(el('span',r.paperTitle+' · '+r.questionId,'eyebrow'),el('h2','作答详情'),el('p',date(r.occurredAt)+' · '+r.section,'muted'));if(r.material)host.append(el('p',r.material,'question-stem'));host.append(el('p',r.stem,'question-stem'));r.options.forEach((text,i)=>host.append(el('div',String.fromCharCode(65+i)+'. '+(text||'图片选项，请在插件查看原图'),'option'+(r.selected.includes(String.fromCharCode(65+i))?' chosen':''))));host.append(el('p','你的选择：'+r.selected+'　参考答案：'+(r.answer||'暂无'),'detail-answer'));if(r.explanation)host.append(el('p',r.explanation,'detail-explanation'));if(r.source)host.append(el('p','答案来源：'+r.source,'detail-source'));if(r.hasImages)host.append(el('p','本题包含图片，请在 IDE 中查看原图。','hint'));openDialog($('detail'));}
function report(r){$('analysis').replaceChildren(el('div',r.from_day||r.from?'日期 '+(r.from_day||r.from)+' — '+(r.to_day||r.to)+' · '+r.model:'','analysis-meta'),el('p',r.report));}
async function history(){const items=await api('analyses');$('analysis-history').replaceChildren();if(items.length){report(items[0]);for(const item of items){const b=el('button',date(item.created_at)+' 的复盘','history-button');b.onclick=()=>report(item);$('analysis-history').append(b);}}}
async function refresh(){const seq=++state.request;const [s,r]=await Promise.all([api('summary?'+query()),api('attempts?'+query())]);if(seq!==state.request)return;metrics(s);trend(s);sections(s);records(r);$('records-title').textContent=$('result-filter').value==='wrong'?'错题记录':'练习记录';}
async function start(){state.session=await api('session');$('login').hidden=true;$('app').hidden=false;$('ai-state').textContent=state.session.aiConfigured?'由 '+state.session.aiModel+' 分析当前日期范围':'AI尚未配置，统计功能可以正常使用';$('analyze').disabled=!state.session.aiConfigured;await Promise.all([refresh(),history()]);}
async function devices(){const rows=await api('devices');$('device-list').replaceChildren();for(const row of rows){const n=el('div',undefined,'device-row');n.append(el('div',row.name+' · '+(row.revoked?'已撤销':row.last_seen?'最近同步 '+date(row.last_seen):'等待首次同步')));if(!row.revoked){const b=el('button','撤销','secondary');b.onclick=run(async()=>{if(!confirm('停止接收这台设备的新记录？已同步记录仍会保留。'))return;await api('revoke',{id:row.id});await devices();});n.append(b);}$('device-list').append(n);}if(!rows.length)$('device-list').append(el('p','还没有连接的设备。','muted'));openDialog($('devices'));}
$('login-form').onsubmit=run(async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{await api('login',{password:$('password').value});$('password').value='';await start();}finally{b.disabled=false;}});
$('logout').onclick=run(async()=>{await api('logout',{});loggedOut();});
for(const id of ['refresh','apply','search'])$(id).onclick=run(async()=>{state.page=1;await refresh();});
$('query').onkeydown=e=>{if(e.key==='Enter')$('search').click();};
document.querySelectorAll('[data-period]').forEach(b=>b.onclick=run(async()=>{period(Number(b.dataset.period));await refresh();}));
$('previous').onclick=run(async()=>{state.page--;await refresh();});$('next').onclick=run(async()=>{state.page++;await refresh();});
$('wrong-nav').onclick=run(async()=>{$('result-filter').value='wrong';state.page=1;await refresh();$('records-panel').scrollIntoView({behavior:'smooth'});});
$('overview-nav').onclick=run(async()=>{$('result-filter').value='';state.page=1;await refresh();window.scrollTo({top:0,behavior:'smooth'});});
$('devices-nav').onclick=run(devices);$('empty-connect').onclick=run(devices);
$('pair').onclick=run(async()=>{const r=await api('pairing',{});$('pair-code').querySelector('code').textContent=r.code;$('pair-code').hidden=false;});
$('copy-code').onclick=run(async()=>{await navigator.clipboard.writeText($('pair-code').querySelector('code').textContent);notice('配对码已复制。');});
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>$(b.dataset.close).close());
$('analyze').onclick=run(async()=>{const b=$('analyze');b.disabled=true;b.textContent='正在分析…';try{report(await api('analyze',{from:$('from').value,to:$('to').value}));await history();}finally{b.disabled=false;b.textContent='生成本期分析 ↗';}});
period(30);start().catch(e=>{if(e.message!=='请先登录')notice(e.message);});

for(const id of ['record-height','analysis-height']){try{const value=localStorage.getItem('study-'+id);if(['200','320','400','520','700','800'].includes(value))$(id).value=value;}catch{}const apply=()=>{document.documentElement.style.setProperty('--'+id,$(id).value+'px');try{localStorage.setItem('study-'+id,$(id).value);}catch{}};$(id).onchange=apply;apply();}
