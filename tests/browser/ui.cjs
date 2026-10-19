// Optional UI regression: install Playwright locally and use the installed Edge browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const {chromium} = require('playwright');
const assets = path.resolve(__dirname, '../../app/src/main/assets');
const server = http.createServer((request, response) => {
  const file = path.resolve(assets, '.' + new URL(request.url, 'http://localhost').pathname);
  if (!file.startsWith(assets + path.sep) || !fs.existsSync(file)) { response.writeHead(404); response.end(); return; }
  response.setHeader('Content-Type', {'.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.webp':'image/webp'}[path.extname(file)] || 'application/octet-stream');
  response.end(fs.readFileSync(file));
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage({viewport:{width:390, height:780}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => { window.commands=[]; window.PocketApp={postMessage:text=>commands.push(JSON.parse(text))}; });
    await page.goto('http://127.0.0.1:' + server.address().port + '/index.html');
    const now=Date.now();
    const sample = {busy:false,country:'CN',updated:{pc:now,android:now,fab:now},message:'界面测试：示例数据',
      epicAccount:{connected:true,displayName:'示例账号'},fabAccount:{connected:true,displayName:'示例账号'},
      items:[{key:'pc-owned',category:'pc',title:'已拥有示例',status:'owned',localCover:'brand/game-night.webp'},
        {key:'pc-free',category:'pc',title:'可领取示例',status:'unowned',localCover:'brand/game-night.webp'},
        {key:'pc-future',category:'pc',title:'预告示例',status:'unowned',availability:'upcoming',start:new Date(now+3600000).toISOString(),localCover:'brand/game-night.webp'},
        {key:'android-free',category:'android',title:'手机示例',status:'unowned',localCover:'brand/mobile-gift.webp'},
        {key:'fab-owned',category:'fab',title:'素材示例',status:'owned',localCover:'brand/fab-workshop.webp'}],
      history:[{id:'latest',state:'paused',startedAt:now,owners:{epic:{name:'示例账号'}},errors:{},entries:[
        {key:'pc-free',category:'pc',title:'可领取示例',status:'failed',reason:'示例网络故障'},
        {key:'pc-owned',category:'pc',title:'已拥有示例',status:'uncertain',reason:'示例待核实'}]},
        {id:'older',state:'done',startedAt:now-86400000,owners:{},errors:{},entries:[]}]};
    await page.evaluate(value=>receiveState(value), sample);
    for (const width of [320,360,390,480]) {
      await page.setViewportSize({width,height:616});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth), false);
      for (const id of ['claim-all','show-history','resume-batch']) {
        const box=await page.locator('#'+id).boundingBox();assert.ok(box.width>=44&&box.height>=44);
      }
    }
    await page.locator('#claim-all').click();
    assert.deepEqual(await page.evaluate(()=>commands.at(-1)), {action:'claimAll'});
    await page.locator('#select-all').check();
    assert.deepEqual(await page.evaluate(()=>[...selected]), ['pc-free']);
    await page.locator('#claim').click();
    assert.deepEqual(await page.evaluate(()=>commands.at(-1)), {action:'claim',category:'pc',keys:['pc-free']});
    await page.locator('#resume-batch').click();
    assert.deepEqual(await page.evaluate(()=>commands.at(-1)), {action:'resumeBatch'});
    await page.locator('#show-history').click();
    assert.ok((await page.locator('#history-content').innerText()).includes('订单待核实'));
    await page.locator('#history-run').selectOption('older');assert.equal(await page.locator('#retry-batch').isDisabled(),true);
    await page.locator('#history-run').selectOption('latest');assert.equal(await page.locator('#retry-batch').isEnabled(),true);
    await page.locator('#retry-batch').click();assert.equal(await page.locator('#history-dialog').evaluate(dialog=>dialog.open),false);
    await page.evaluate(value=>receiveState({...value,busy:true}), sample);
    for (const id of ['claim-all','resume-batch','claim','switch-account','logout']) assert.equal(await page.locator('#'+id).isDisabled(),true);
    await page.evaluate(value=>receiveState({...value,history:[]}), sample);
    await page.evaluate(() => {
      state.items.find(item=>item.key==='pc-future').start=new Date(Date.now()-1000).toISOString();
      refreshIfDue();
    });
    assert.deepEqual(await page.evaluate(()=>commands.at(-1)), {action:'refresh',category:'pc'});
    assert.equal(await page.locator('[data-key="pc-future"] .card-select input').isEnabled(),true);
    await page.locator('#account').click();await page.locator('#switch-account').click();
    assert.equal(await page.evaluate(()=>selected.size),0);
    assert.deepEqual(await page.evaluate(()=>commands.at(-1)),{action:'switchAccount',category:'pc'});
    await page.evaluate(value=>receiveState({...value,journalError:'记录损坏，自动领取已停止'}),sample);
    assert.equal(await page.locator('#claim-all').isDisabled(),true);
    assert.equal(await page.locator('#resume-batch').isDisabled(),true);
    assert.deepEqual(errors,[]);
    console.log('UI fixture checks passed: 4 widths, all-category action, selection, resume/history, pending orders, time boundaries, busy controls and account switch. No live orders.');
  } finally { await browser.close(); server.close(); }
})().catch(error=>{console.error(error.message);process.exit(1);});
