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

(async()=>{
  checkInlineScripts('app.html');
  checkInlineScripts('index.html');
  const browser=await chromium.launch({headless:true,args:[
    '--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'
  ]});
  try{
    await runMain(browser,'local-mobile-360',ORIGIN+'/app.html?preview=main',360,800);
    await runMain(browser,'local-mobile-412',ORIGIN+'/app.html?preview=main',412,915);
    await runMain(browser,'deployed-mobile',LIVE+'/app-design-preview.html?preview=main',360,800);
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
