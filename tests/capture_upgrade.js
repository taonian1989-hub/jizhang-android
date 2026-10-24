async(page)=>{
 page.setDefaultTimeout(10000);await page.emulateMedia({reducedMotion:'reduce',colorScheme:'light'});await page.setViewportSize({width:393,height:852});await page.goto('http://127.0.0.1:8803');
 if(await page.getByRole('button',{name:'开始我的第一笔',exact:true}).count())await page.getByRole('button',{name:'开始我的第一笔',exact:true}).click();
 await page.locator('.topbar [data-page="settings"]').click();await page.getByRole('button',{name:/关于与隐私/}).click();await page.getByRole('button',{name:'查看示例账本',exact:true}).click();await page.evaluate(()=>{window.scrollTo(0,0);document.querySelector('#toast').classList.remove('show');});
 await page.screenshot({path:'outputs/记账_0.1.1_首页语音入口.png',animations:'disabled'});
 await page.locator('.bottom-nav [data-page="assistant"]').click();await page.screenshot({path:'outputs/记账_0.1.1_助手语音入口.png',animations:'disabled'});
 await page.locator('.bottom-nav [data-page="home"]').click();await page.getByRole('button',{name:'记一笔',exact:true}).click();
 const states=[['expense','支出表单'],['income','收入表单'],['transfer','转账表单'],['refund','退款表单']];
 for(const [type,name] of states){await page.locator(`[data-act="tx-type"][data-type="${type}"]`).click();await page.locator('.sheet').evaluate(el=>el.scrollTop=0);await page.screenshot({path:`outputs/记账_0.1.1_${name}.png`,animations:'disabled'});}
 await page.getByRole('button',{name:'关闭',exact:true}).click();return {screenshots:6,data:'built-in demo only; no user data or live microphone capture'};
}
