/* Browser smoke test for the design-preview app.
 * Does not connect to accounts or request real locations. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const { chromium }=require('playwright');

const ORIGIN='http://127.0.0.1:8765';
const LIVE='https://stevejjobs8.github.io/where-are-you';
const OUT='artifacts';
fs.mkdirSync(OUT,{recursive:true});

function checkInlineScripts(filename){
  const html=fs.readFileSync(filename,'utf8');
  const scripts=[...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
  let n=0;
  for(const [,attrs,source] of scripts){
    if(/\bsrc\s*=/.test(attrs)||!source.trim())continue;
    new vm.Script(source,{filename:filename+':inline-'+(++n)});
  }
  assert(n>0,filename+' must contain app logic');
  console.log('PASS inline JavaScript syntax:',filename,n);
}

async function runMain(browser,name,url,width,height){
  const context=await browser.newContext({viewport:{width,height},deviceScaleFactor:1,
    isMobile:width<=450,hasTouch:width<=450,reducedMotion:'reduce'});
  let supabaseCalls=0;
  await context.route(/supabase\.co/,route=>{supabaseCalls++;return route.abort('blockedbyclient');});
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    const res=await page.goto(url,{waitUntil:'domcontentloaded',timeout:45000});
    assert(res&&res.ok(),name+': page request failed');
    await page.locator('#statuschip').waitFor({state:'visible',timeout:20000});
    assert.equal((await page.locator('#pname').innerText()).trim(),'하루');
    assert.match(await page.locator('#pstate').innerText(),/실제 위치 아님/);
    assert.equal(await page.locator('#shareSwitch').isDisabled(),true);
    const mapSrc=await page.locator('#mapframe').getAttribute('src');
    assert(mapSrc?.includes('demo=1'),name+': demo map flag absent');
    await page.waitForFunction(()=>{
      const f=document.querySelector('#mapframe');
      const d=f?.contentDocument;
      return !!(d&&d.querySelector('#stage')&&d.querySelector('#msg')?.textContent!=='불러오는 중');
    },null,{timeout:50000});
    const overlayBounds=await page.evaluate(()=>{
      const a=document.querySelector('#statuschip').getBoundingClientRect();
      const b=document.querySelector('.sharecard').getBoundingClientRect();
      return {headerTop:a.top,headerBottom:a.bottom,footerTop:b.top,footerBottom:b.bottom,vh:innerHeight};
    });
    assert(overlayBounds.headerTop>=-1 && overlayBounds.footerBottom <= height+1,name+': overlay exceeds screen');
    assert(overlayBounds.headerBottom < overlayBounds.footerTop,name+': top and bottom overlap');

    await page.screenshot({path:path.join(OUT,name+'.png'),animations:'disabled'});
    await page.locator('#menuBtn').click();
    await page.waitForFunction(()=>document.querySelector('#sheetMenu').classList.contains('on'));
    await page.locator('#scrim').click({position:{x:2,y:300},force:true});
    await page.waitForFunction(()=>!document.querySelector('#sheetMenu').classList.contains('on'));
    assert.equal(supabaseCalls,0,name+': preview contacted Supabase');
    assert.deepEqual(errors,[],name+': uncaught JS errors');
    console.log('PASS',name,JSON.stringify(overlayBounds),'no Supabase calls');
  }finally{await context.close();}
}


async function runSolo(browser,name,url){
  const context=await browser.newContext({
    viewport:{width:360,height:800},deviceScaleFactor:1,isMobile:true,hasTouch:true,reducedMotion:'reduce'
  });
  let serverCalls=0;
  await context.route(/supabase\.co/,route=>{serverCalls++;return route.abort('blockedbyclient')});
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try {
    const res=await page.goto(url,{waitUntil:'domcontentloaded',timeout:45000});
    assert(res&&res.ok(),name+': failed to open');
    try{ await page.locator('#soloTools').waitFor({state:'visible',timeout:15000}); }
    catch(err){
      const diagnosis=await page.evaluate(()=>({
        solo:typeof SOLO==='undefined'?'unset':SOLO,
        preview:typeof PREVIEW==='undefined'?'unset':PREVIEW,
        panel:document.querySelector('#soloTools')?.className,
        overlay:document.querySelector('#overlay')?.className
      })).catch(e=>({error:e.message}));
      throw new Error(name+' solo panel missing: '+JSON.stringify(diagnosis)+' errors: '+errors.join(' | '));
    }
    assert.equal(await page.locator('#shareSwitch').isDisabled(),true);
    assert.match(await page.locator('#pstate').innerText(),/실제 위치 아님/);
    const mapSrc=await page.locator('#mapframe').getAttribute('src');
    assert(mapSrc&&mapSrc.includes('demo=1'),name+': missing DEMO flag');

    const mapReady=()=> {
      const d=document.querySelector('#mapframe')?.contentDocument;
      return d?.querySelector('#modes button[data-v="bus"]')?.getAttribute('aria-checked')==='true'
        && d?.querySelector('#msg')?.textContent!=='불러오는 중';
    };
    await page.waitForFunction(mapReady,null,{timeout:50000});

    await page.locator('#soloTools [data-solo-mode="subway"]').click();
    await page.waitForFunction(()=>{
      const d=document.querySelector('#mapframe')?.contentDocument;
      return d?.querySelector('#modes button[data-v="subway"]')?.getAttribute('aria-checked')==='true';
    },null,{timeout:12000});
    await page.locator('#soloTools [data-solo-weather="rain"]').click();
    await page.waitForFunction(()=>{
      const d=document.querySelector('#mapframe')?.contentDocument;
      return d?.querySelector('#weathers button[data-v="rain"]')?.getAttribute('aria-checked')==='true';
    },null,{timeout:12000});
    await page.screenshot({path:path.join(OUT,name+'.png'),animations:'disabled'});
    assert.equal(serverCalls,0,name+': simulation contacted realtime backend');
    assert.deepEqual(errors,[],name+': unhandled browser errors');
    console.log('PASS',name,'solo demo controls, no realtime server requests');
  }finally {await context.close();}
}


async function runMockLocationState(browser,name,sharing,minutesOld,hasCoordinates){
  const context=await browser.newContext({viewport:{width:360,height:800},isMobile:true,hasTouch:true,reducedMotion:'reduce'});
  await context.addInitScript(()=>{
    window.AndroidBridge={auth:()=>JSON.stringify({view:'test-view',write:'test-write'})};
  });
  let mockedQueries=0, unexpectedCalls=0;
  await context.route(/supabase\.co/,route=>{unexpectedCalls++;return route.abort('blockedbyclient');});
  await context.route('**/rest/v1/rpc/get_partner_location',route=>{
    mockedQueries++;
    const row={sharing,lat:hasCoordinates?37.55:null,lng:hasCoordinates?126.92:null,
      name:'테스트 짝꿍',accuracy:15,speed:0,activity:'still',
      updated_at:new Date(Date.now()-minutesOld*60000).toISOString()};
    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify([row])});
  });
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try {
    const res=await page.goto(ORIGIN+'/index.html?app=1',{waitUntil:'domcontentloaded',timeout:45000});
    assert(res&&res.ok(),name+': map page failed to open');
    await page.waitForFunction(()=>{
      return document.querySelector('#scene-name')?.textContent==='테스트 짝꿍의 지금';
    },null,{timeout:20000});
    await page.waitForFunction(state=>{
      const text=document.querySelector('#scene-status-text')?.textContent||'';
      return state==='paused' ? text==='위치 공유 중지'
        : state==='no-fix' ? text==='위치 기다리는 중'
        : state==='stale' ? text.includes('마지막 위치')
        : text.includes('확인된 위치');
    },name,{timeout:20000});
    const hidden=await page.locator('#stage').evaluate(el=>el.classList.contains('location-unavailable'));
    assert.equal(hidden,name!=='fresh',name+': incorrect map visibility');
    assert(mockedQueries>0,name+': real location request was never exercised');
    assert.equal(unexpectedCalls,0,name+': unexpected backend access');
    assert.deepEqual(errors,[],name+': unhandled JavaScript errors');
    console.log('PASS',name,'mock partner location and UI status');
  } finally {await context.close();}
}


/* Account relationship and deletion tests use a fake RPC server; no real user data. */
async function runAccountLifecycle(browser) {
  const context=await browser.newContext({viewport:{width:360,height:800},isMobile:true,hasTouch:true});
  await context.addInitScript(()=>{
    // Only seed the top-level app once; iframe navigations must not restore cleared data.
    if(window.top!==window || sessionStorage.getItem('wy-test-seeded')) return;
    sessionStorage.setItem('wy-test-seeded','1');
    localStorage.setItem('wy-onboarded','1');
    localStorage.setItem('wy-consent','1');
    localStorage.setItem('wy-name','테스터');
    localStorage.setItem('wy-partner','partner-test');
    localStorage.setItem('wy-pname','하루');
    localStorage.setItem('wy-creds',JSON.stringify({view:'test-owner',write:'test-secret'}));
  });
  let unpairAllowed=false,deleteAllowed=false,linked=true,unpairCalls=0,deleteCalls=0;
  const fulfill=(route,data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
  await context.route(/supabase\.co/,route=>route.abort('blockedbyclient'));
  await context.route('**/rest/v1/rpc/get_partner',route=>
    fulfill(route,[{partner:linked?'partner-test':null,partner_name:linked?'하루':null}]));
  await context.route('**/rest/v1/rpc/get_partner_location',route=>
    fulfill(route,[{lat:null,lng:null,sharing:false,name:'하루',updated_at:new Date().toISOString()}]));
  await context.route('**/rest/v1/rpc/my_access_summary',route=>fulfill(route,[]));
  await context.route('**/rest/v1/rpc/unpair',route=>{
    unpairCalls++;
    if(!unpairAllowed)return fulfill(route,{error:'temporary network failure'},503);
    linked=false;
    return fulfill(route,null);
  });
  await context.route('**/rest/v1/rpc/delete_me',route=>{
    deleteCalls++;
    if(!deleteAllowed)return fulfill(route,{error:'temporary network failure'},503);
    return fulfill(route,null);
  });
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const openMenu=async()=>{
    if(!(await page.locator('#sheetMenu').isVisible())) await page.locator('#menuBtn').click();
    await page.locator('#sheetMenu').waitFor({state:'visible'});
    await page.waitForFunction(()=>{
      const rect=document.querySelector('#mPair').getBoundingClientRect();
      return rect.top>=0 && rect.bottom <= window.innerHeight;
    },null,{timeout:6000});
  };
  try{
    await page.goto(ORIGIN+'/app.html',{waitUntil:'domcontentloaded',timeout:45000});
    await page.locator('#statuschip').waitFor({state:'visible',timeout:15000});
    await openMenu();
    await page.locator('#mPair').click();
    await page.locator('#pUnpair').click();
    await page.locator('#dlgYes').click();
    await page.waitForFunction(()=>document.querySelector('#toast')?.textContent.includes('연결 해제에 실패'));
    assert.equal(await page.evaluate(()=>localStorage.getItem('wy-partner')),'partner-test',
      'Failed server unpair must preserve linked account');
    assert.equal(unpairCalls,1);

    unpairAllowed=true;
    await page.locator('#pUnpair').click();
    await page.locator('#dlgYes').click();
    await page.waitForFunction(()=>document.querySelector('#empty')&&!document.querySelector('#empty').classList.contains('hide'));
    assert.equal(await page.evaluate(()=>localStorage.getItem('wy-partner')),null);
    assert.equal(unpairCalls,2);
    console.log('PASS unpair failure retains link; successful unpair clears link');

    await openMenu();
    await page.locator('#mDelete').click();
    await page.locator('#dlgYes').click();
    await page.waitForFunction(()=>document.querySelector('#toast')?.textContent.includes('서버 삭제에 실패'));
    assert.equal(await page.evaluate(()=>localStorage.getItem('wy-onboarded')),'1');
    assert((await page.evaluate(()=>localStorage.getItem('wy-creds')))?.includes('test-secret'));
    assert.equal(deleteCalls,1);

    deleteAllowed=true;
    await openMenu();
    await page.locator('#mDelete').click();
    await page.locator('#dlgYes').click();
    await page.waitForFunction(()=>localStorage.getItem('wy-onboarded')===null);
    assert.equal(await page.evaluate(()=>localStorage.getItem('wy-creds')),null);
    assert.equal(deleteCalls,2);
    assert.deepEqual(errors,[]);
    console.log('PASS failed account deletion preserves credentials; confirmed deletion clears credentials');
  }finally{await context.close();}
}

async function runRemoteDisconnect(browser) {
  const context=await browser.newContext({viewport:{width:360,height:800},isMobile:true,hasTouch:true});
  await context.addInitScript(()=>{
    // Only seed the top-level app once; iframe navigations must not restore cleared data.
    if(window.top!==window || sessionStorage.getItem('wy-test-seeded')) return;
    sessionStorage.setItem('wy-test-seeded','1');
    localStorage.setItem('wy-onboarded','1');
    localStorage.setItem('wy-name','테스터');
    localStorage.setItem('wy-partner','cached-partner');
    localStorage.setItem('wy-pname','하루');
    localStorage.setItem('wy-creds',JSON.stringify({view:'test-owner',write:'test-secret'}));
  });
  await context.route(/supabase\.co/,r=>r.abort('blockedbyclient'));
  await context.route('**/rest/v1/rpc/get_partner',r=>r.fulfill({
    status:200,contentType:'application/json',body:JSON.stringify([{partner:null,partner_name:null}])}));
  await context.route('**/rest/v1/rpc/my_access_summary',r=>r.fulfill({
    status:200,contentType:'application/json',body:'[]'}));
  const page=await context.newPage();
  try{
    await page.goto(ORIGIN+'/app.html',{waitUntil:'domcontentloaded',timeout:45000});
    try{
      await page.waitForFunction(()=>localStorage.getItem('wy-partner')===null
        && !document.querySelector('#empty')?.classList.contains('hide'),null,{timeout:15000});
    }catch(e){
      const details=await page.evaluate(()=>({
        partner:localStorage.getItem('wy-partner'),
        savedCreds:!!localStorage.getItem('wy-creds'),
        hasOnboarded:localStorage.getItem('wy-onboarded'),
        statePartner:typeof state==='undefined'?'missing':state.partner,
        overlay:document.querySelector('#overlay')?.className,
        empty:document.querySelector('#empty')?.className,
        toast:document.querySelector('#toast')?.textContent
      }));
      throw new Error('remote unlink UI state not updated: '+JSON.stringify(details)+' '+e.message);
    }
    assert.equal(await page.locator('#statuschip').isVisible(),false);
    console.log('PASS server-side partner unlink clears cached relationship');
  }finally{await context.close();}
}


async function runPipBrowserPreview(browser,name,url){
  const context=await browser.newContext({
    viewport:{width:360,height:800},isMobile:true,hasTouch:true,reducedMotion:'reduce'
  });
  await context.addInitScript(()=>{
    window.__pipRequested=false;
    window.__pipReady=false;
    window.AndroidBridge={
      isPipSupported:()=>true,
      setPipReady:on=>{window.__pipReady=!!on;},
      enterPip:()=>{
        window.__pipRequested=true;
        window.WY?.onPipModeChanged(true);
      },
      auth:()=>JSON.stringify({view:'',write:''}),
      isSharing:()=>false,
      shareStatus:()=>''
    };
  });
  let serverCalls=0;
  await context.route(/supabase\.co/,route=>{
    serverCalls++;return route.abort('blockedbyclient');
  });
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(url,{waitUntil:'domcontentloaded',timeout:45000});
    await page.locator('#pipBtn').waitFor({state:'visible',timeout:20000});
    await page.waitForFunction(()=>window.__pipReady===true);
    assert.equal(await page.locator('#mapframe').isVisible(),true);
    await page.locator('#pipBtn').click();
    await page.waitForFunction(()=>window.__pipRequested && document.body.classList.contains('pip-mode'));
    assert.equal(await page.locator('#overlay').isVisible(),false,'Controls must be hidden in PiP');
    await page.waitForFunction(()=>{
      const d=document.querySelector('#mapframe')?.contentDocument;
      return d?.body.classList.contains('pip')
        && d?.querySelector('#pipLabel')?.textContent.includes('실제 위치 아님');
    },null,{timeout:25000});
    assert.equal(await page.locator('#mapframe').isVisible(),true);
    await page.screenshot({path:path.join(OUT,name+'.png')});
    await page.evaluate(()=>window.WY.onPipModeChanged(false));
    await page.waitForFunction(()=>!document.body.classList.contains('pip-mode'));
    assert.equal(await page.locator('#overlay').isVisible(),true);
    assert.equal(await page.locator('#pipBtn').isVisible(),true);
    assert.equal(serverCalls,0,'Demo PiP must not connect to location server');
    assert.deepEqual(errors,[],name+': browser JS errors');
    console.log('PASS',name,'native PiP button, compact map, truthful demo label, restore UI');
  }finally{await context.close();}
}

(async()=>{
  checkInlineScripts('app.html');
  checkInlineScripts('index.html');
  const browser=await chromium.launch({headless:true,args:[
    '--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'
  ]});
  try{
    await runMain(browser,'local-mobile-360',ORIGIN+'/app.html?preview=main',360,800);
    await runMain(browser,'local-mobile-412',ORIGIN+'/app.html?preview=main',412,915);
    await runMain(browser,'deployed-mobile',LIVE+'/app-pip-preview.html?preview=main',360,800);
    await runSolo(browser,'solo-local',ORIGIN+'/app.html?preview=main&solo=1');
    await runSolo(browser,'solo-deployed',LIVE+'/app-pip-preview.html?preview=main&solo=1');
    await runPipBrowserPreview(browser,'pip-local',ORIGIN+'/app.html?preview=main&solo=1');
    await runPipBrowserPreview(browser,'pip-deployed',LIVE+'/app-pip-preview.html?preview=main&solo=1');
    await runMockLocationState(browser,'no-fix',true,0,false);
    await runMockLocationState(browser,'paused',false,0,false);
    await runMockLocationState(browser,'stale',true,10,true);
    await runMockLocationState(browser,'fresh',true,0,true);
    await runRemoteDisconnect(browser);
    await runAccountLifecycle(browser);
    const context=await browser.newContext({viewport:{width:360,height:800},isMobile:true,hasTouch:true});
    const p=await context.newPage();
    await p.goto(ORIGIN+'/app.html?preview=main-empty',{waitUntil:'domcontentloaded'});
    await p.locator('#empty').waitFor({state:'visible'});
    assert.equal(await p.locator('#statuschip').isVisible(),false);
    await p.screenshot({path:path.join(OUT,'empty-state.png')});
    await p.locator('#emptyPair').click();
    await p.waitForFunction(()=>document.querySelector('#sheetPair').classList.contains('on'));
    await context.close();
    console.log('PASS empty state and partner link sheet');
  }finally{await browser.close();}
})().catch(e=>{console.error('FAIL:',e.stack||e);process.exitCode=1;});
