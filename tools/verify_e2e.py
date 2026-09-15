"""Runs the packaged app and local SMTP sink; sends nothing externally."""
import datetime as dt,json,os,pathlib,socket,subprocess,tempfile,time,urllib.request,urllib.error

ROOT=pathlib.Path(__file__).resolve().parents[1]
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
http,smtp,inbox=port(),port(),port()
base=f'http://127.0.0.1:{http}'
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(path,data=None,key=None):
 headers={'Content-Type':'application/json'}
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
 try:
  with opener.open(req,timeout=5) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
def wait_for(fn,seconds=35):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  try:
   value=fn()
   if value:return value
  except (OSError,urllib.error.URLError):pass
  time.sleep(.2)
 raise AssertionError('Timed out waiting for condition')
def timestamp(offset):return (dt.datetime.now(dt.timezone.utc)+dt.timedelta(seconds=offset)).isoformat()

with tempfile.TemporaryDirectory(prefix='mail-dispatch-e2e-') as temp:
 log=open(pathlib.Path(temp)/'app.log','w')
 sink=subprocess.Popen(['python3',str(ROOT/'tools/demo_smtp.py'),'--smtp-port',str(smtp),'--http-port',str(inbox),'--fail-first','1'],stdout=log,stderr=log)
 app=None
 try:
  app=subprocess.Popen(['java','-jar',str(ROOT/'target/mail-dispatch-1.0.0.jar'),f'--server.port={http}',f'--spring.mail.port={smtp}','--app.worker.delay-ms=200','--app.retry.base-seconds=1',f'--spring.datasource.url=jdbc:h2:file:{temp}/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE'],cwd=temp,stdout=log,stderr=log)
  wait_for(lambda:request('/api/mails')[0]==200)
  payload={'recipient':'recipient@example.test','subject':'예약 메일 실제 SMTP 검증','body':'로컬 테스트 전용','scheduledAt':timestamp(2)}
  code,job=request('/api/mails',payload,'e2e-key-0001');assert code==201,(code,job)
  code,replay=request('/api/mails',payload,'e2e-key-0001');assert code==200 and replay['id']==job['id']
  code,err=request('/api/mails',{**payload,'subject':'다른 내용'},'e2e-key-0001');assert code==409
  accepted=wait_for(lambda:(lambda j:j if j['status']=='SMTP_ACCEPTED' else None)(request('/api/mails/'+job['id'])[1]))
  assert accepted['attemptCount']==2,accepted
  attempts=request('/api/mails/'+job['id']+'/attempts')[1]
  assert [a['outcome'] for a in attempts]==['FAILED','SMTP_ACCEPTED'],attempts
  with opener.open(f'http://127.0.0.1:{inbox}/messages') as r:received=json.load(r)
  assert len(received)==1 and received[0]['subject']==payload['subject'] and received[0]['body'].strip()==payload['body'],received
  code,cancel=request('/api/mails',{**payload,'scheduledAt':timestamp(60)},'e2e-key-0002');assert code==201
  code,cancel=request('/api/mails/'+cancel['id']+'/cancel',{});assert code==200 and cancel['status']=='CANCELLED'
  code,invalid=request('/api/mails',{**payload,'recipient':'invalid','scheduledAt':timestamp(60)},'e2e-key-0003');assert code==400
  result={'checkedAt':timestamp(0),'result':'PASS','database':'H2 file / MySQL compatibility mode','smtp':'Real JavaMailSender -> local SMTP sink; first RCPT returns 451','checks':['HTTP create 201','Duplicate replay 200; same id','Changed payload 409','SMTP 451 triggers retry','Second SMTP attempt accepted','Attempt history FAILED -> SMTP_ACCEPTED','Exactly one message captured; Korean subject/body verified','Future request cancellation','Invalid email 400'],'externalMailSent':False}
  out=ROOT/'docs/e2e-result.json';out.parent.mkdir(exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False))
 except Exception:
  log.flush();print((pathlib.Path(temp)/'app.log').read_text()[-6000:]);raise
 finally:
  for process in (app,sink):
   if process:
    process.terminate()
    try:process.wait(timeout=10)
    except subprocess.TimeoutExpired:process.kill();process.wait()
  log.close()
