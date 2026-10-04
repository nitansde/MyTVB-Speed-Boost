// Execute pinned, unmodified upstream algorithms. Test-only instrumentation exposes closures.
// No network traffic. Re-run with: node tools/btr/generate-parity-fixtures.cjs
const fs = require('node:fs');
const vm = require('node:vm');
let at = 0;
const context = vm.createContext({performance: {now: () => at}, WeakRef, AbortController, DOMException,
  setTimeout, clearTimeout, URL, console, fetch: () => {throw Error('network disabled');}});
vm.runInContext(fs.readFileSync(__dirname + '/upstream-range-core.js', 'utf8'), context);
let source = fs.readFileSync(__dirname + '/upstream-idm-downloader.js', 'utf8');
source = source.replace('return Object.freeze({ downloadRange, applySettings, getConcurrency: () => semaphore.limit });',
  'return Object.freeze({ downloadRange, applySettings, getConcurrency: () => semaphore.limit, policy: {assignPrimaries, adaptiveMinChunk, recordMeter, hedgeDelayMs, hedgeDue, createEarlyHedge} });');
vm.runInContext(source, context);
const factory = context.__BILI_IDM_DOWNLOADER_FACTORY__;
const makePolicy = () => factory.createDownloader({getSettings: () => ({autoConcurrency:false})}).policy;
let seed = 42;
const random = () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296;
const assignments = [];
for (let scenario = 0; scenario < 30; scenario++) {
  const policy = makePolicy();
  let speeds;
  const resolver = {speed: url => speeds[+url]};
  const ranges = [];
  for (let step = 0; step < 8; step++) {
    speeds = scenario === 0 ? [0,0,0] : scenario === 1 ? [4000000,1000000,0] : Array.from({length: 2+scenario%7}, () => random()<.3 ? 0 : Math.floor(random()*4000000));
    const count = 2+Math.floor(random()*30);
    const urls = speeds.map((_,i)=>String(i));
    ranges.push({speeds,count,expected:policy.assignPrimaries(urls,resolver,count).map(Number)});
  }
  assignments.push(ranges);
}
const sizing = [];
for(let i=0;i<80;i++) {
  const policy=makePolicy(); const bytes=Math.floor(random()*2000000), elapsed=1+Math.floor(random()*2500);
  policy.recordMeter(bytes,elapsed);
  const length=65536+Math.floor(random()*20000000), limit=1+Math.floor(random()*32),hosts=1+Math.floor(random()*8);
  const minimum=policy.adaptiveMinChunk({minChunkBytes:65536},length,limit,hosts);
  sizing.push({bytes,elapsed,length,limit,hosts,minimum,delay:policy.hedgeDelayMs({hedgeDelayMs:900}),pieces:context.__BILI_RANGE_CORE__.splitRange(71,70+length,limit,minimum)});
}
const hedges=[];
for(let i=0;i<300;i++) {
  const policy=makePolicy(); const meterBytes=100000+Math.floor(random()*2000000),meterMs=100+Math.floor(random()*2000);
  policy.recordMeter(meterBytes,meterMs);
  const started=1000, now=1000+Math.floor(random()*3000),bytes=Math.floor(random()*1000000),length=1500000,lastProgress=now-Math.floor(random()*1500),delay=250+Math.floor(random()*650),deadline=now+Math.floor(random()*6000),copyBps=i%3?random()*3000000:0;
  at=now;
  const p={startedAt:started,recorder:{bytes},length,seenBytes:bytes,seenAt:lastProgress};
  hedges.push({meterBytes,meterMs,started,now,bytes,length,lastProgress,delay,deadline,copyBps,expected:policy.hedgeDue(p,delay,deadline,copyBps,policy.createEarlyHedge(1))});
}
const auto=[];
for(let scenario=0;scenario<20;scenario++) {
  at=0; const controller=factory.createAutoConcurrency({now:()=>at}); const events=[];
  for(let i=0;i<160;i++) {
    at += 250+Math.floor(random()*700);
    const type=i===0?'newSession':['delivered','activity','demand','buffer','stall','slow','pushback','newSession'][Math.floor(random()*8)];
    const args=type==='delivered'?[Math.floor(random()*2000000)]:type==='demand'?[16,16,random()<.5?0:5]:type==='buffer'?[random()*20,true]:type==='pushback'?[429]:[];
    controller[type](...args); const s=controller.status();
    events.push({at,type,args,expected:{threads:s.threads,level:s.level,throughputBps:s.throughputBps,saturation:s.saturation,trial:s.trial?{from:s.trial.from,to:s.trial.level,ageMs:s.trial.ageMs}:null}});
  }
  auto.push(events);
}
const output = {upstream:'bbf4d3dee502a16e424232ae6a51705f52b0e60d',assignments,sizing,hedges,auto};
const path=__dirname+'/../../app/src/test/resources/btr-parity.json';
fs.mkdirSync(require('node:path').dirname(path),{recursive:true});fs.writeFileSync(path,JSON.stringify(output));
console.log('Generated upstream fixtures: 240 assignments, 80 split/meter cases, 300 hedges, 3200 auto-controller events.');
