// 시험용 리소스팩 서버: Range 지원 (주소에 ?norange 가 있으면 Range 무시). 받은 바이트 수를 기록
const http=require('http'),fs=require('fs');const file=process.argv[3];const port=+process.argv[2];let sent=0;
http.createServer((req,res)=>{const data=fs.readFileSync(file);const r=req.headers.range;const norange=req.url.includes('norange');
 if(req.url.startsWith('/stats')){res.end(String(sent));sent=0;return}
 if(r&&!norange){const m=/bytes=(\d+)-(\d*)/.exec(r);const s=+m[1];const e=m[2]?Math.min(+m[2],data.length-1):data.length-1;
  res.writeHead(206,{'Content-Range':`bytes ${s}-${e}/${data.length}`,'Content-Length':e-s+1,'Accept-Ranges':'bytes'});sent+=e-s+1;res.end(data.subarray(s,e+1));}
 else{res.writeHead(200,{'Content-Length':data.length});sent+=data.length;res.end(data);}
}).listen(port,'127.0.0.1');
