async(page)=>{
 await page.emulateMedia({reducedMotion:'reduce',colorScheme:'light'});
 if(await page.getByRole('button',{name:'开始我的第一笔',exact:true}).count())await page.getByRole('button',{name:'开始我的第一笔',exact:true}).click();
 await page.locator('.topbar [data-page="settings"]').click();await page.getByRole('button',{name:/关于与隐私/}).click();await page.getByRole('button',{name:'查看示例账本',exact:true}).click();await page.evaluate(()=>{window.scrollTo(0,0);document.querySelector('#toast').classList.remove('show');});
 await page.screenshot({path:'outputs/记账_概览预览.png',animations:'disabled'});
 await page.locator('.bottom-nav [data-page="bills"]').click();await page.screenshot({path:'outputs/记账_账单预览.png',animations:'disabled'});
 await page.locator('.bottom-nav [data-page="insights"]').click();await page.screenshot({path:'outputs/记账_洞察预览.png',animations:'disabled'});
 await page.locator('.bottom-nav [data-page="home"]').click();await page.locator('#quick-input').fill('早餐18，地铁3');await page.getByRole('button',{name:'识别账单',exact:true}).click();await page.evaluate(()=>document.querySelector('#toast').classList.remove('show'));await page.screenshot({path:'outputs/记账_确认卡片预览.png',animations:'disabled'});
 await page.locator('[data-act="discard-drafts"]').click();await page.locator('.bottom-nav [data-page="home"]').click();await page.emulateMedia({reducedMotion:'reduce',colorScheme:'dark'});await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:'outputs/记账_深色预览.png',animations:'disabled'});
 return {screenshots:5,source:'Browser mobile preview of APK assets, sample data clearly labeled'};
}
