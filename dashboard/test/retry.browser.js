// 对本地内存测试服务运行，覆盖真实 HTTP 保存；不向生产数据库写入验收记录。
async (page) => {
 const check=(value,message)=>{if(!value)throw Error(message);};
 await page.goto('http://127.0.0.1:18708/study/');
 await page.locator('#password').fill('local-preview-only');await page.locator('#login-form button').click();await page.locator('.question-link').first().waitFor();
 const results=[];
 for(const [width,id,answers] of [[320,'single',['B']],[390,'multi',['A','C']],[430,'boolean',['B']],[1280,'unknown',['B']]]){
  await page.setViewportSize({width,height:844});
  if(id==='single')await page.getByRole('button',{name:/^重做：single 测试题/}).first().click();
  else{await page.locator('.question-link').filter({hasText:id+' 测试题'}).first().click();await page.getByRole('button',{name:'重新做题',exact:true}).click();}
  check(await page.locator('.detail-answer,.detail-explanation,.chosen').count()===0,'重做泄露旧答案');
  check(await page.locator('#retry-form input:checked').count()===0,'保留了旧选择');
  await page.getByRole('button',{name:'提交答案',exact:true}).click();
  check((await page.locator('#detail-actions').innerText()).includes('请先选择答案'),'未提示空选择');
  await page.locator('#detail-height').selectOption('520');
  check(await page.evaluate(()=>localStorage.getItem('study-detail-height'))==='520','高度未保存');
  const before=await page.locator('#records-panel').evaluate(n=>n.getBoundingClientRect().top);
  await page.mouse.move(2,420);await page.mouse.wheel(0,600);await page.waitForTimeout(100);
  check(Math.abs(await page.locator('#records-panel').evaluate(n=>n.getBoundingClientRect().top)-before)<1,'背景滚动穿透');
  for(const answer of answers)await page.locator('#retry-form input[value="'+answer+'"]').check();
  const submitBox=await page.getByRole('button',{name:'提交答案',exact:true}).boundingBox();check(submitBox.y+submitBox.height<=844,'提交按钮超出屏幕');
  const response=page.waitForResponse(r=>r.url().endsWith('/retry')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'提交答案',exact:true}).click();
  check((await response).status()===200,'提交失败');await page.locator('.detail-answer').waitFor();
  check((await page.locator('#detail-actions').innerText()).includes(id==='unknown'?'未判分':'回答正确'),'结果不正确');
  check((await page.locator('.detail-answer').innerText()).includes('你的选择：'+answers.join('')),'选项错误');
  await page.locator('[data-close="detail"]').click();
  await page.waitForFunction(()=>!document.body.classList.contains('modal-open'));
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'页面横向溢出');
  results.push({width,type:id,result:'passed'});
 }
 await page.reload();await page.locator('.question-link').first().waitFor();check(await page.locator('#detail-height').inputValue()==='520','刷新后高度丢失');
 return results;
}
