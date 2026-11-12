"""Authenticated raw TCP/WebSocket RFB regression fixture; never a QQ login substitute.
Extract bundled noVNC and set BIGFISH_TEST_NOVNC. --serve binds 5920 and 6081.
"""
import asyncio, pathlib, struct, json, os, shutil
from aiohttp import web, WSMsgType
from playwright.async_api import async_playwright
from cryptography.hazmat.primitives.ciphers import Cipher, modes
try: from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
except ImportError: from cryptography.hazmat.primitives.ciphers.algorithms import TripleDES
PROJECT=pathlib.Path(__file__).resolve().parents[3]
ROOT=PROJECT/'.local/login-viewer-checks'
ROOT.mkdir(parents=True,exist_ok=True)
STATIC=pathlib.Path(os.environ['BIGFISH_TEST_NOVNC'])
SOURCE=PROJECT/'app/src/main/assets/runtime/bigfish-login.html'
PASSWORD='Fixture7'
stats={'connections':0,'authenticated':0,'updates':0,'keys':[],'pointer':[]}
async def ws_handler(request):
 ws=web.WebSocketResponse();await ws.prepare(request);return await rfb_session(ws)
async def rfb_session(ws):
 stats['connections']+=1
 buffer=bytearray()
 async def read(n):
  while len(buffer)<n:
   message=await ws.receive()
   if message.type!=WSMsgType.BINARY: raise ConnectionError('client closed')
   buffer.extend(message.data)
  data=bytes(buffer[:n]);del buffer[:n];return data
 try:
  await ws.send_bytes(b'RFB 003.008\n');assert await read(12)==b'RFB 003.008\n'
  await ws.send_bytes(b'\x01\x02');assert await read(1)==b'\x02'
  challenge=bytes(range(16));await ws.send_bytes(challenge)
  key=bytes(int(f'{b:08b}'[::-1],2) for b in PASSWORD.encode())
  enc=Cipher(TripleDES(key),modes.ECB()).encryptor();expected=enc.update(challenge)+enc.finalize()
  assert await read(16)==expected
  stats['authenticated']+=1
  await ws.send_bytes(struct.pack('!I',0));await read(1)
  fmt=struct.pack('!BBBBHHHBBBxxx',32,24,0,1,255,255,255,16,8,0)
  name=b'Authenticated login display regression fixture'
  await ws.send_bytes(struct.pack('!HH',320,200)+fmt+struct.pack('!I',len(name))+name)
  bpp=32;endian=0;shifts=(16,8,0)
  while not ws.closed:
   typ=(await read(1))[0]
   if typ==0:
    data=await read(19);bpp,depth,endian,truecolor,r,g,b,rs,gs,bs=struct.unpack('!xxxBBBBHHHBBBxxx',data);shifts=(rs,gs,bs)
   elif typ==2:
    data=await read(3);await read(struct.unpack('!H',data[1:])[0]*4)
   elif typ==3:
    await read(9)
    pixels=bytearray()
    for y in range(200):
     for x in range(320):
      rgb=(220,40,50) if x<160 else (20,170,90)
      value=sum(c<<shift for c,shift in zip(rgb,shifts))
      pixels.extend(value.to_bytes(bpp//8,'big' if endian else 'little'))
    await ws.send_bytes(b'\x00\x00'+struct.pack('!H',1)+struct.pack('!HHHHi',0,0,320,200,0)+pixels)
    stats['updates']+=1
    await asyncio.sleep(.05)
   elif typ==4:
    data=await read(7);stats['keys'].append(struct.unpack('!I',data[3:])[0])
   elif typ==5:
    data=await read(5);stats['pointer'].append(list(struct.unpack('!BHH',data)))
   elif typ==6:
    data=await read(7);await read(struct.unpack('!I',data[3:])[0])
   else: raise ValueError('Unexpected RFB message '+str(typ))
 except (ConnectionError,ConnectionResetError):pass
 except Exception as e:stats.setdefault('serverErrors',[]).append(str(e))
 return ws
async def html(request):return web.Response(body=SOURCE.read_bytes(),content_type='text/html')
async def main():
 app=web.Application();app.router.add_get('/websockify',ws_handler);app.router.add_get('/bigfish-login.html',html);app.router.add_static('/',STATIC)
 runner=web.AppRunner(app);await runner.setup();await web.TCPSite(runner,'127.0.0.1',6081).start()
 try:
  async with async_playwright() as p:
   browser=await p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
   page=await browser.new_page(viewport={'width':360,'height':400},device_scale_factor=2,is_mobile=True,has_touch=True)
   errors=[];page.on('pageerror',lambda e:errors.append(str(e)));failed=[];page.on('response',lambda r:failed.append([r.status,r.url]) if r.status>=400 else None)
   await page.goto('http://127.0.0.1:6081/bigfish-login.html')
   try:
    await page.wait_for_function('typeof window.bigfishConnect==="function"',timeout=10000)
    await page.evaluate('(p)=>window.bigfishConnect(p)',PASSWORD)
    await page.wait_for_function('document.querySelector("canvas")?.width===320',timeout=10000)
    await page.wait_for_timeout(1000)
   except Exception as e:errors.append(str(e).split('\n')[0])
   result=await page.evaluate('''()=>({message:document.querySelector('#message').innerText,canvas:[...document.querySelectorAll('canvas')].map(c=>({width:c.width,height:c.height,rect:{width:c.getBoundingClientRect().width,height:c.getBoundingClientRect().height},left:[...c.getContext('2d').getImageData(80,100,1,1).data],right:[...c.getContext('2d').getImageData(240,100,1,1).data]})),state:window.bigfishState})''')
   result.update(errors=errors,failed=failed,server=stats)
   (ROOT/'viewer-result.json').write_text(json.dumps(result,indent=2))
   await page.screenshot(path=str(ROOT/'viewer.png'))
   print(json.dumps(result,indent=2),flush=True)
   await browser.close()
 finally:await runner.cleanup()
async def tcp_handler(reader,writer):
 import types
 class Transport:
  @property
  def closed(self):return writer.is_closing()
  async def send_bytes(self,value):writer.write(value);await writer.drain()
  async def receive(self):
   data=await reader.read(65536)
   return types.SimpleNamespace(type=WSMsgType.BINARY if data else WSMsgType.CLOSED,data=data)
 try:await rfb_session(Transport())
 finally:writer.close();await writer.wait_closed()
async def serve():
 app=web.Application();app.router.add_get('/websockify',ws_handler);app.router.add_get('/bigfish-login.html',html);app.router.add_static('/',STATIC)
 runner=web.AppRunner(app);await runner.setup();await web.TCPSite(runner,'127.0.0.1',6081).start()
 tcp=await asyncio.start_server(tcp_handler,'127.0.0.1',5920)
 print('Authenticated RFB display fixture ready on 127.0.0.1:6081',flush=True)
 try:
  while True:
   (ROOT/'fixture-stats.json').write_text(json.dumps(stats,indent=2))
   await asyncio.sleep(1)
 finally:await runner.cleanup()
if __name__=='__main__':
 import sys
 asyncio.run(serve() if '--serve' in sys.argv else main())
