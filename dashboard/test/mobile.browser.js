// 在 Playwright 的 browser_run_code_unsafe 中运行；静态站点设为 localhost:18707，接口全部拦截为测试数据。
async (page) => {
 const check=(value,message)=>{if(!value)throw Error(message);};
 const base='http://127.0.0.1:18707/study/';
 const row={event_id:'fixture',occurred_at:Date.now(),stem:'手机布局测试题',paper_title:'测试卷',question_id:'q1',section:'言语理解',selected:'A',correct:0,latest_correct:0};
 await page.route('**/study/api/**',async route=>{
  const u=new URL(route.request().url()),path=u.pathname.split('/api/')[1];let data;
  if(path==='session')data={authenticated:true,aiConfigured:false};
  else if(path==='summary')data={from:u.searchParams.get('from'),to:u.searchParams.get('to'),totals:{attempts:2,questions:1,correct:1,wrong:1,unknown:0,accuracy:50},daily:[{day:u.searchParams.get('to'),attempts:2,correct:1,wrong:1,unknown:0}],sections:[{section:'言语理解',attempts:2,correct:1,wrong:1}]};
  else if(path==='attempts')data={total:20,pages:1,rows:Array.from({length:20},()=>row)};
  else if(path==='attempts/fixture')data={paperTitle:'测试卷',questionId:'q1',occurredAt:Date.now(),section:'言语理解',stem:'用于测试长题滚动。'.repeat(150),options:['甲','乙','丙','丁'],selected:'A',answer:'B',explanation:'参考解析。'.repeat(120)};
  else if(path==='analyses'||path==='devices')data=[];
  else throw Error('Unexpected API '+path);
  await route.fulfill({json:data});
 });
 const results=[];
 for(const width of [320,390,430,1280]){
  await page.setViewportSize({width,height:844});await page.goto(base);await page.locator('.bar-column').last().waitFor();
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  check(!overflow,'页面横向溢出 '+width);
  await page.locator('.bar-column').last().click();await page.waitForFunction(()=>document.querySelectorAll('.bar-column').length===1);
  const bar=await page.locator('.bar-column').boundingBox();check(bar.width<=32.1,'单日柱子膨胀 '+width);
  const svg=await page.locator('.bar-column svg').boundingBox(),chart=await page.locator('.bars').boundingBox();check(svg.y+svg.height<=chart.y+chart.height+1,'柱子溢出图表');
  await page.locator('.question-link').first().click();await page.waitForFunction(()=>document.body.classList.contains('modal-open'));
  const start=await page.evaluate(()=>({top:document.querySelector('#records-panel').getBoundingClientRect().top,saved:-parseFloat(document.body.style.getPropertyValue('--modal-top'))}));
  await page.mouse.move(3,420);await page.mouse.wheel(0,600);await page.waitForTimeout(200);
  check(Math.abs(await page.locator('#records-panel').evaluate(n=>n.getBoundingClientRect().top)-start.top)<1,'蒙层背后滚动 '+width);
  const dialog=page.locator('#detail'),box=await dialog.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.wheel(0,500);await page.waitForTimeout(200);
  check(await dialog.evaluate(n=>n.scrollTop)>0,'长题不可滚动');
  await dialog.evaluate(n=>{n.scrollTop=n.scrollHeight;});await page.mouse.wheel(0,800);await page.waitForTimeout(200);
  check(Math.abs(await page.locator('#records-panel').evaluate(n=>n.getBoundingClientRect().top)-start.top)<1,'弹层底部滚动穿透');
  await page.keyboard.press('Escape');await page.waitForFunction(()=>!document.body.classList.contains('modal-open'));
  check(Math.abs(await page.evaluate(()=>scrollY)-start.saved)<2,'关闭后位置未恢复');
  await page.locator('.question-link').first().click();await page.locator('[data-close="detail"]').click();await page.waitForFunction(()=>!document.body.classList.contains('modal-open'));
  await page.locator('#devices-nav').click();await page.waitForFunction(()=>document.body.classList.contains('modal-open'));await page.locator('[data-close="devices"]').click();await page.waitForFunction(()=>!document.body.classList.contains('modal-open'));
  results.push({width,barWidth:bar.width,scrollLock:'passed'});
 }
 await page.unroute('**/study/api/**');return results;
}
