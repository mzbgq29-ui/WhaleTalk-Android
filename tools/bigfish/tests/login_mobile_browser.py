import asyncio,json,pathlib
from playwright.async_api import async_playwright
ROOT=pathlib.Path(__file__).resolve().parents[3]/'.local/login-viewer-checks'
ROOT.mkdir(parents=True,exist_ok=True)
async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
  page=await browser.new_page(viewport={'width':360,'height':480},is_mobile=True,has_touch=True)
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  async def open_frame():
   await page.goto('http://127.0.0.1:6081/bigfish-login.html')
   await page.evaluate("window.bigfishConnect('Fixture7')")
   await page.wait_for_function("window.bigfishState.phase==='frame'")
  await open_frame()
  pixels=await page.evaluate("(()=>{var c=document.querySelector('#screen canvas');return [[...c.getContext('2d').getImageData(80,100,1,1).data],[...c.getContext('2d').getImageData(240,100,1,1).data]]})()")
  assert pixels==[[220,40,50,255],[20,170,90,255]]
  await page.locator('#screen canvas').first.tap(position={'x':80,'y':60})
  await page.evaluate('window.bigfishKeyboard()');await page.locator('#keyboard').fill('a测')
  await page.wait_for_timeout(300)
  await page.set_viewport_size({'width':640,'height':360});await page.wait_for_timeout(300)
  box=await page.locator('#screen canvas').first.bounding_box();assert box['width']>100 and box['height']>100
  await page.set_viewport_size({'width':160,'height':120})
  await page.evaluate('window.bigfishFit(false)');await page.wait_for_timeout(300)
  session=await page.context.new_cdp_session(page)
  await session.send('Input.dispatchTouchEvent',{'type':'touchStart','touchPoints':[{'x':140,'y':70}]})
  await session.send('Input.dispatchTouchEvent',{'type':'touchMove','touchPoints':[{'x':100,'y':70}]})
  await session.send('Input.dispatchTouchEvent',{'type':'touchMove','touchPoints':[{'x':20,'y':70}]})
  await session.send('Input.dispatchTouchEvent',{'type':'touchEnd','touchPoints':[]})
  await page.wait_for_timeout(300)
  pan=await page.evaluate("[...document.querySelector('#screen canvas').getContext('2d').getImageData(150,60,1,1).data]")
  assert pan==[20,170,90,255],pan
  await page.evaluate('window.bigfishFit(true)')
  await page.set_viewport_size({'width':360,'height':480})
  await open_frame()
  await page.route('**/core/rfb.js',lambda route:route.abort())
  await page.goto('http://127.0.0.1:6081/bigfish-login.html');await page.evaluate("window.bigfishConnect('Fixture7')")
  await page.wait_for_function("window.bigfishState.phase==='module-error'")
  assert await page.locator('#message').is_visible()
  await page.unroute('**/core/rfb.js');await open_frame()
  await page.screenshot(path=str(ROOT/'mobile-browser-after.png'))
  await page.wait_for_timeout(1500)
  stats=json.loads((ROOT/'fixture-stats.json').read_text())
  assert stats['authenticated']>=3 and 97 in stats['keys'] and (0x01000000|ord('测')) in stats['keys'] and stats['pointer']
  result={'authenticatedFramebufferPixels':True,'touchInput':True,'unicodeKeyboard':True,'viewportResize':True,'zoomAndOneFingerPan':True,'reloadReconnect':True,'moduleFailureHasVisibleError':True,'recoveryAfterFailure':True,'javascriptErrors':errors,'scope':'Mobile Chromium with bundled noVNC 1.3.0 and authenticated RFB fixture; no QQ account'}
  assert not errors
  (ROOT/'mobile-browser-checks.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
  await browser.close()
asyncio.run(main())
