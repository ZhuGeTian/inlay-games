// 本地浏览器验收专用：内存数据库与虚拟题目，不读取生产配置。
import {createApp,passwordHash} from '../server.mjs';
import {randomUUID} from 'node:crypto';
const app=createApp({dbPath:':memory:',origin:'http://127.0.0.1:18708',passwordHash:passwordHash('local-preview-only')});
app.store.db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run('test','Fixture IDE','test-hash',Date.now());
for(const [id,type,answer,options] of [['single','single','B',['甲','乙','丙','丁']],['multi','multiple','AC',['甲','乙','丙','丁']],['boolean','boolean','B',['正确','错误']],['unknown','single','',['甲','乙','丙','丁']]])app.store.ingest('test',[{eventId:randomUUID(),occurredAt:Date.now(),paperId:'a'.repeat(64),questionId:id,type,paperTitle:'界面验收测试卷',section:'言语理解',material:'',stem:id+' 测试题：'+ '这是用于验证手机滚动的题目材料。'.repeat(40),options,selected:'A',answer,explanation:'提交后才能看到的解析',source:'测试数据',hasImages:false}]);
app.server.listen(18708,'127.0.0.1');
