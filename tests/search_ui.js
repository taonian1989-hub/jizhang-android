async(page)=>{
 await page.emulateMedia({reducedMotion:'reduce'});
 await page.locator('.topbar [data-page="settings"]').click();
 await page.locator('[data-act="search-settings"]').click();
 const text=await page.getByRole('dialog',{name:'联网搜索',exact:true}).innerText();
 if(!text.includes('DeepSeek 官方 Search API')||!text.includes('无需 GLM 搜索配置'))throw Error('Search provider instructions incorrect');
 await page.screenshot({path:'outputs/记账_0.1.2_DeepSeek搜索设置.png',animations:'disabled'});
 await page.getByRole('button',{name:'配置 DeepSeek',exact:true}).click();
 if(await page.locator('#model-endpoint').inputValue()!=='https://api.deepseek.com/chat/completions')throw Error('Search configuration opens wrong provider');
 await page.getByRole('button',{name:'关闭',exact:true}).click();
 return {searchProvider:'deepseek',configuration:'DeepSeek official key',noGlmKeyRequired:true};
}
