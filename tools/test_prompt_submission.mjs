import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,chmod,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {randomBytes} from 'node:crypto';
const home=await mkdtemp(path.join(tmpdir(),'pi-prompt-test-'));
const bin=path.join(home,'prefix','bin');await mkdir(bin,{recursive:true});
const fake=path.join(bin,'pi');
await writeFile(fake,`
process.on('SIGTERM',()=>process.exit(0));
let buffer='';process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{
 buffer+=chunk;while(buffer.includes('\\n')){
  const i=buffer.indexOf('\\n');const c=JSON.parse(buffer.slice(0,i));buffer=buffer.slice(i+1);
  const reply=(success=true,data={},error='')=>process.stdout.write(JSON.stringify({id:c.id,type:'response',command:c.type,success,data,error})+'\\n');
  if(c.type==='get_state')reply(true,{isStreaming:false,isCompacting:false,sessionId:'test'});
  else if(c.type==='prompt'){
   if(c.message==='slow')setTimeout(()=>reply(true,{disposition:'started'}),32000);
   else if(c.message==='fail')reply(false,{},'test rejected');
   else if(c.message==='late-failure')setTimeout(()=>reply(false,{},'stale rejection'),1500);
   else if(c.streamingBehavior==='steer'||c.streamingBehavior==='followUp')reply(true,{disposition:'queued',behavior:c.streamingBehavior});
   else reply(false,{},'Agent is already processing. Specify streamingBehavior');
  }else reply();
 }
});
`);await chmod(fake,0o700);
const token=randomBytes(32).toString('base64url');const port=35000+process.pid%900;
const bridge=spawn(process.execPath,[path.resolve('app/src/main/assets/pi-android-bridge.mjs')],{env:{...process.env,HOME:home,PREFIX:path.join(home,'prefix'),PI_ANDROID_PORT:String(port),PI_ANDROID_TOKEN:token,PI_ANDROID_PID_FILE:path.join(home,'bridge.pid'),PI_ANDROID_DEBUG_PROMPTS:'1'},stdio:['ignore','pipe','pipe']});
let diagnostics='';bridge.stdout.on('data',c=>diagnostics+=c);bridge.stderr.on('data',c=>diagnostics+=c);
const call=(route,body)=>fetch('http://127.0.0.1:'+port+route,{signal:AbortSignal.timeout(10000),method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
const events=async(after=0)=>{
 for(let attempt=0;attempt<3;attempt++){
  try{return await(await call('/events?after='+after+'&wait=0')).json();}
  catch(error){if(attempt===2)throw error;await new Promise(r=>setTimeout(r,100));}
 }
};
try{
 for(let i=0;;i++){try{if((await call('/health')).status===200)break;}catch{}assert.ok(i<160,diagnostics);await new Promise(r=>setTimeout(r,25));}
 assert.equal((await call('/start',{cwd:home,launchCommand:'pi --mode rpc'})).status,200);
 let start=performance.now();let response=await call('/prompt',{message:'slow'});
 assert.equal(response.status,200);assert.equal((await response.json()).data.disposition,'pending');
 assert.ok(performance.now()-start<1000,'slow preflight must not block the HTTP request');
 for(const behavior of [undefined,'followUp']){
  response=await call('/prompt',{message:'busy',...(behavior?{streamingBehavior:behavior}:{})});assert.equal(response.status,200);
 }
 await call('/prompt',{message:'fail'});
 await new Promise(r=>setTimeout(r,100));
 let batch=await events();
 assert.ok(batch.events.some(e=>e.value.type==='extension_error'&&e.value.error.includes('test rejected')));
 assert.ok(!batch.events.some(e=>e.value.error?.includes('already processing')),'busy input needs an explicit queue behavior');
 const cursor=batch.latest;
 await call('/prompt',{message:'late-failure'});
 assert.equal((await call('/stop',{})).status,200);
 const stoppedCursor=(await events()).latest;
 await new Promise(r=>setTimeout(r,1600));
 batch=await events(stoppedCursor);
 if(batch.events.some(e=>e.value.error?.includes('stale rejection')))console.error('Late-stop events:',JSON.stringify(batch.events));
 assert.ok(!batch.events.some(e=>e.value.error?.includes('stale rejection')),'Stop must discard late preflight failures');
 // New slow submission must remain alive beyond the old 30-second limit.
 await call('/prompt',{message:'slow'});
 const deadline=performance.now()+65000;let completed=false;
 while(performance.now()<deadline){
  batch=await events(cursor);
  assert.ok(!batch.events.some(e=>e.value.error?.includes('RPC timeout after 30s')));
  if(batch.events.some(e=>e.value.type==='prompt_submission_end'&&e.value.disposition==='started')){completed=true;break;}
  await new Promise(r=>setTimeout(r,250));
 }
 if(!completed){
  console.error('Final health:',await(await call('/health')).text());
  console.error('Observed events:',JSON.stringify(batch.events.map(e=>({seq:e.seq,type:e.value.type,disposition:e.value.disposition,error:e.value.error}))));
  console.error('Bridge diagnostics:',diagnostics);
 }
 assert.ok(completed,'preflight exceeding 30 seconds must complete normally');
 console.log('Prompt acceptance, busy queue, delayed preflight, errors and Stop cancellation tests passed');
}finally{await call('/shutdown',{}).catch(()=>{});bridge.kill();await rm(home,{recursive:true,force:true});}
