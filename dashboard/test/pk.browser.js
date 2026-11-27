// 先运行 retry-preview.mjs；使用两个独立浏览器会话完成邀请、匹配和真实交卷。
async page=>{
 page.setDefaultTimeout(10000);
 const check=(v,m)=>{if(!v)throw Error(m);};
 const base='http://127.0.0.1:18708/study/';
 const context=await page.context().browser().newContext({viewport:{width:320,height:844}}),opponent=await context.newPage();
 try{
  await page.setViewportSize({width:390,height:844});await page.goto(base);
  if(await page.locator('#login').isVisible()){await page.locator('#password').fill('local-preview-only');await page.locator('#login-form button').click();}
  await page.locator('a[href="/study/pk/"]').click();await page.locator('#invite').click();await page.locator('#invite-result').waitFor();const url=await page.locator('#invite-url').inputValue();
  await opponent.goto(url);await opponent.locator('#guest-name').fill('对手乙');await opponent.locator('#pk-login-form button').click();await opponent.locator('#match').waitFor();
  check(await opponent.evaluate(async()=> (await fetch('/study/api/attempts')).status)===401,'访客读到个人记录');
  await page.locator('#player-name').fill('主人甲');await page.locator('#match').click();await page.locator('#cancel-match').waitFor();
  await opponent.locator('#match').click();await page.waitForFunction(()=>document.querySelectorAll('#pk-question input').length>0);await opponent.waitForFunction(()=>document.querySelectorAll('#pk-question input').length>0);
  const [a,b]=await Promise.all([page.evaluate(async()=> (await(await fetch('/study/api/pk/state')).json()).room),opponent.evaluate(async()=> (await(await fetch('/study/api/pk/state')).json()).room)]);
  check(a.id===b.id&&a.endsAt===b.endsAt,'不是同一局或截止时间不同');check(a.questions.filter(Boolean).length===1,'不应提前显示后续题');
  await page.locator('#pk-height').selectOption('520');await page.evaluate(()=>{window.confirm=()=>true;});await opponent.evaluate(()=>{window.confirm=()=>true;});
  for(let i=0;i<5;i++){
   await page.waitForFunction(i=>document.querySelector('#game-title').textContent.startsWith('第 '+(i+1)+'/5'),i);
   await opponent.waitForFunction(i=>document.querySelector('#game-title').textContent.startsWith('第 '+(i+1)+'/5'),i);
   const r=await page.evaluate(async()=> (await(await fetch('/study/api/pk/state')).json()).room),q=r.questions[i];
   check(r.endsAt-r.questionStartedAt===120000,'本题不是两分钟');check(!q.answer,'提前泄露答案');
   const answers=q.stem.startsWith('multi ')?['A','C']:q.stem.startsWith('extra2 ')?['C']:['B'];
   for(const letter of answers)await page.locator('#pk-question input[value="'+letter+'"]').check();
   await page.waitForFunction(()=>document.querySelector('#pk-save').textContent==='选择已保存。');
   if(i===0){await page.reload();await page.waitForFunction(()=>document.querySelectorAll('#pk-question input:checked').length>0);check((await page.evaluate(async()=> (await(await fetch('/study/api/pk/state')).json()).room)).endsAt===r.endsAt,'刷新重置计时');}
   await page.locator('#pk-submit').click();await page.waitForFunction(()=>document.querySelector('#pk-result').textContent.includes('本题已提交'));
   check(await page.locator('.detail-answer').count()===0,'提前泄露答案');
   await opponent.locator('#pk-submit').click();
  }
  await page.waitForFunction(()=>document.querySelector('#game-title').textContent==='本局结果');
  check((await page.locator('#pk-result').innerText()).includes('主人甲 获胜'),'胜负错误');check((await page.locator('#pk-players').innerText()).includes('5/5 分'),'得分错误');check(await page.locator('.detail-answer').count()===1,'结算未显示答案');
  return {twoSessions:true,questions:5,perQuestionMs:120000,result:'5 : 0',guestPrivateAccess:401,mobileWidths:[320,390],reloadAndHeight:'passed'};
 }finally{await context.close();}
}
