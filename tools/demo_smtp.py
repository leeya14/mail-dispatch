"""Local-only SMTP sink + inbox. Python standard library, never relays mail.
Test switch: --fail-first N returns 451 to the first N RCPT commands.
"""
import argparse, email, email.policy, html, json, socketserver, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

messages=[]
lock=threading.Lock()
failures=0
MAX_BYTES=1_000_000

class SMTP(socketserver.StreamRequestHandler):
 def handle(self):
  global failures
  self.request.settimeout(10)
  def reply(text):self.wfile.write((text+'\r\n').encode());self.wfile.flush()
  reply('220 localhost Mail Dispatch test sink')
  while True:
   raw=self.rfile.readline(8192)
   if not raw:return
   cmd=raw.decode(errors='replace').strip();op=cmd.split(' ',1)[0].upper()
   if op in ('EHLO','HELO'):reply('250 localhost')
   elif op=='MAIL':reply('250 OK')
   elif op=='RCPT':
    with lock:
     fail=failures>0
     if fail:failures-=1
    reply('451 Temporary failure for integration test' if fail else '250 OK')
   elif op=='DATA':
    reply('354 End with a single dot');chunks=[];total=0
    while True:
     line=self.rfile.readline(MAX_BYTES+1)
     if not line:return
     if line in (b'.\r\n',b'.\n'):break
     total+=len(line)
     if total>MAX_BYTES:reply('552 Too large');return
     chunks.append(line[1:] if line.startswith(b'..') else line)
    msg=email.message_from_bytes(b''.join(chunks),policy=email.policy.default)
    body=msg.get_body(preferencelist=('plain',)) if msg.is_multipart() else msg
    with lock:messages.append({'to':str(msg.get('To','')),'subject':str(msg.get('Subject','')),'body':body.get_content() if body else ''})
    reply('250 Accepted by local test sink')
   elif op=='QUIT':reply('221 Bye');return
   elif op in ('RSET','NOOP'):reply('250 OK')
   else:reply('502 Not implemented')

class Inbox(BaseHTTPRequestHandler):
 def do_GET(self):
  with lock:copy=list(messages)
  if self.path=='/messages':data=json.dumps(copy,ensure_ascii=False).encode();kind='application/json; charset=utf-8'
  else:
   cards=''.join('<article><h2>'+html.escape(m['subject'])+'</h2><p>'+html.escape(m['to'])+'</p><pre>'+html.escape(m['body'])+'</pre></article>' for m in reversed(copy))
   data=('<!doctype html><meta charset="utf-8"><title>테스트 수신함</title><style>body{font:16px system-ui;background:#f5f6f8;max-width:900px;margin:40px auto;padding:20px}article{background:white;border:1px solid #ddd;border-radius:12px;padding:20px;margin:15px 0}pre{white-space:pre-wrap;font:inherit}a{color:#ca5218}</style><h1>로컬 테스트 수신함</h1><p>실제 외부 주소로 발송되지 않습니다. 서버 종료 시 수신 기록은 사라집니다.</p><a href="/">새로고침</a> · <a href="http://127.0.0.1:8081">발송 예약</a>'+ (cards or '<p>수신된 메일이 없습니다.</p>')).encode();kind='text/html; charset=utf-8'
  self.send_response(200);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def log_message(self,*args):pass

class Server(socketserver.ThreadingTCPServer):
 allow_reuse_address=True
 daemon_threads=True

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--smtp-port',type=int,default=1025);p.add_argument('--http-port',type=int,default=8025);p.add_argument('--fail-first',type=int,default=0);args=p.parse_args();failures=args.fail_first
 smtp=Server(('127.0.0.1',args.smtp_port),SMTP);threading.Thread(target=smtp.serve_forever,daemon=True).start()
 print(f'Local test SMTP: {args.smtp_port}; inbox: http://127.0.0.1:{args.http_port}',flush=True)
 try:ThreadingHTTPServer(('127.0.0.1',args.http_port),Inbox).serve_forever()
 except KeyboardInterrupt:smtp.shutdown()
