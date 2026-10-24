const http=require('http'),fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../app/src/main/assets');
const allowed=new Set(['index.html','app.css','app.js','ledger.js','speech.js']);
http.createServer((req,res)=>{const name=req.url==='/'?'index.html':req.url.slice(1);if(!allowed.has(name)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html; charset=utf-8');fs.createReadStream(path.join(root,name)).pipe(res);}).listen(8803,'127.0.0.1',()=>console.log('Jizhang browser preview http://127.0.0.1:8803 (model keys disabled)'));
