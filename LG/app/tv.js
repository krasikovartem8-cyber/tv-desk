(function(){
'use strict';
var $=function(id){return document.getElementById(id);};
var brand=document.body.getAttribute('data-brand'),nativeMode=document.body.getAttribute('data-mode')==='native';
var key='tvdesk.02.'+brand+'.'+(nativeMode?'app':'web');
var apps=[],extras=[],profile={title:'Мои приложения',pins:[],hidden:[]};
var token='',endpoint='',timer=null,ack=null,attempt=0,pairing=false,ready=false;
var urls={'web.kinopoisk':'https://www.kinopoisk.ru/watch/','web.youtube':'https://www.youtube.com/','web.rutube':'https://rutube.ru/','web.vkvideo':'https://vkvideo.ru/'};
var lgDefaults=[['com.685631.3411','Кинопоиск'],['youtube.leanback.v4','YouTube'],['netflix','Netflix'],['amazon','Prime Video'],['com.webos.app.browser','Браузер LG']];
function msg(s){$('message').textContent=s;}
function display(s,show){$(s).hidden=!show;}
function systemInfo(){
  var lang=navigator.language||'',zone='',region='';
  try {zone=Intl.DateTimeFormat().resolvedOptions().timeZone||'';}catch(e){}
  var match=lang.match(/[-_]([A-Za-z]{2}|[0-9]{3})(?:$|-)/);
  if(match)region=match[1].toUpperCase();
  return {language:lang,zone:zone,localeRegion:region,clock:new Date().toLocaleString()};
}
function showClock(){
  var now=new Date();
  $('clock').textContent=now.toLocaleDateString()+' · '+now.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
  $('cityText').textContent=profile.city||'';
}
function tvDuid(){
  if(!nativeMode||brand!=='samsung')return '';
  try{return String(window.webapis.productinfo.getDuid()||'').substring(0,160);}
  catch(e){return '';}
}
function openSetup(){
  if(token){msg('Сеанс уже открыт.');return;}
  if(nativeMode&&brand==='samsung')$('duid').textContent='DUID этого ТВ · '+(tvDuid()||'доступен в разделе «Поддержка → Об этом ТВ»');
  display('guide',false);display('service',true);(nativeMode?$('host'):$('pin')).focus();
}
function showGuide(){display('service',false);display('guide',true);$('hideGuide').focus();}
function clean(p){
  var ids=apps.map(function(a){return a.id;});
  function valid(a){return (Array.isArray(a)?a:[]).filter(function(id,i){return ids.indexOf(id)>=0 && a.indexOf(id)===i;});}
  var pins=valid(p.pins);
  return {city:(typeof p.city==='string'?p.city.trim():'').slice(0,80),title:(typeof p.title==='string'&&p.title.trim()?p.title.trim():'Мои приложения').slice(0,48),
    pins:pins,hidden:valid(p.hidden).filter(function(id){return pins.indexOf(id)<0;})};
}
function render(){
  showClock();$('heading').textContent=profile.title;var box=$('tiles');box.textContent='';
  var seen=0;
  function section(label,arr){
    if(!arr.length)return;var h=document.createElement('h3');h.textContent=label;box.appendChild(h);
    arr.forEach(function(a){var b=document.createElement('button');b.textContent=a.name;b.onclick=function(){launch(a);};box.appendChild(b);seen++;});
  }
  section('Избранное',profile.pins.map(function(id){return apps.filter(function(a){return a.id===id;})[0];}).filter(Boolean));
  section(nativeMode?'Приложения':'Веб-сервисы',apps.filter(function(a){return profile.hidden.indexOf(a.id)<0 && profile.pins.indexOf(a.id)<0;}));
  $('empty').textContent=seen?'':(apps.length?'Все ярлыки скрыты. Вернуть их можно с ноутбука.':'Доступные ярлыки не найдены. Откройте настройку или обслуживание.');
}
function fail(e){msg('Телевизор не выполнил действие. '+((e&&(e.errorText||e.message||e.name))||'Проверьте поддержку на этой модели.'));}
function lg(method,params,yes,no){
  try{window.webOS.service.request('luna://com.webos.applicationManager',{method:method,parameters:params,onSuccess:yes,onFailure:no});}
  catch(e){no(e);}
}
function launch(a){
  if(!nativeMode){if(urls[a.id])window.location.assign(urls[a.id]);return;}
  if(brand==='lg'){lg('launch',{id:a.id},function(){},fail);return;}
  try{var mgr=window.tizen.application;
    if(typeof mgr.launch==='function')mgr.launch(a.id,function(){},fail);
    else mgr.launchAppControl(new window.tizen.ApplicationControl('http://tizen.org/appcontrol/operation/default'),a.id,function(){},fail);
  }catch(e){fail(e);}
}
function discover(onReady){
  var finished=false,timeout=setTimeout(function(){if(!finished){msg('Система ТВ не вернула весь каталог. Настройка и обслуживание доступны.');done();}},6500);
  function done(){if(finished)return;finished=true;clearTimeout(timeout);onReady();}
  if(!nativeMode){apps=[{id:'web.kinopoisk',name:'Кинопоиск'},{id:'web.youtube',name:'YouTube'},{id:'web.rutube',name:'RUTUBE'},{id:'web.vkvideo',name:'VK Видео'}];done();return;}
  if(brand==='samsung'){
    try{var tm=window.tizen.application;
      tm.getAppsInfo(function(results){
        var own=tm.getCurrentApplication().appInfo.id;
        apps=results.filter(function(a){return a.id!==own&&a.show!==false&&/^[\w.:-]{1,160}$/.test(a.id);}).slice(0,1024).map(function(a){
          var size=null;try{if(a.size>0)size=a.size;}catch(e){}
          return {id:a.id,name:String(a.name||a.id).slice(0,160),size:size};
        });apps.sort(function(a,b){return a.name.localeCompare(b.name);});done();
      },function(e){fail(e);done();});
    }catch(e){fail(e);done();}return;
  }
  var choices=lgDefaults.slice();extras.forEach(function(id){if(!choices.some(function(a){return a[0]===id;}))choices.push([id,id]);});
  var remaining=choices.length;var found={};
  function finish(){if(--remaining===0){apps=choices.filter(function(x){return found[x[0]];}).map(function(x){return {id:x[0],name:x[1]};});done();}}
  choices.forEach(function(x){lg('getAppLoadStatus',{appId:x[0]},function(r){if(r.exist)found[x[0]]=true;finish();},finish);});
}
function base(){
  if(!nativeMode)return window.location.protocol+'//'+window.location.host;
  var s=$('host').value.trim().replace(/^http:\/\//,'').replace(/\/$/,'');
  var m=/^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$/.exec(s);
  if(!m||m[1].split('.').some(function(x){return Number(x)>255;})||(m[2]&&(Number(m[2])<1||Number(m[2])>65535)))throw Error('Введите IPv4-адрес и порт из панели ноутбука.');
  return 'http://'+m[1]+':'+(m[2]||8765);
}
function req(route,data,callback){
  var xhr=new XMLHttpRequest();
  try{xhr.open('POST',endpoint+'/tv/'+route,true);xhr.timeout=6000;
    xhr.setRequestHeader('Content-Type','text/plain;charset=UTF-8');
    xhr.onload=function(){var r;try{r=JSON.parse(xhr.responseText);}catch(e){r={error:'Проверьте адрес ноутбука'};}
      callback(xhr.status,r);};
    xhr.onerror=xhr.ontimeout=function(){callback(0,{error:'Ноутбук не отвечает. Проверьте сеть и окно TV Desk.'});};
    xhr.send(JSON.stringify(data));
  }catch(e){callback(0,{error:e.message});}
}
function closeLocal(s){
  attempt++;clearTimeout(timer);ack=null;token='';pairing=false;display('leave',false);$('pair').disabled=false;
  msg(s);
}
function sync(g){
  if(g!==attempt||!token)return;
  var data={token:token};
  if(ack){data.ack=ack.seq;data.ok=ack.ok;}
  req('sync',data,function(status,r){
    if(g!==attempt)return;
    if(status===403||status===410){closeLocal('Сеанс завершён.');return;}
    if(status===200){
      if(r.job){
        if(!ack||ack.seq!==r.job.seq){
          try{if(r.job.kind==='profile'){
            var p=clean(r.job.profile);localStorage.setItem(key,JSON.stringify(p));profile=p;render();msg('Профиль сохранён на этом ТВ.');
          }else if(r.job.kind==='guide'){showGuide();msg('Инструкция открыта.');}else throw Error('Неизвестное действие');
          ack={seq:r.job.seq,ok:true};}
          catch(e){ack={seq:r.job.seq,ok:false};fail(e);}
        }
      }else{ack=null;}
    }else msg(r.error||'Связь прервана');
    timer=setTimeout(function(){sync(g);},status===200?1000:3200);
  });
}
$('pair').onclick=function(){
  if(!ready||token||pairing)return;
  var pin=$('pin').value.trim();if(!/^\d{8}$/.test(pin)){msg('Введите восемь цифр из панели.');return;}
  try{endpoint=base();}catch(e){msg(e.message);return;}
  var g=++attempt;pairing=true;$('pair').disabled=true;
  req('join',{brand:brand,mode:nativeMode?'native':'browser',pin:pin,apps:apps,profile:clean(profile),environment:systemInfo(),duid:tvDuid()},function(status,r){
    if(g!==attempt)return;pairing=false;$('pair').disabled=false;
    if(status!==200||!r.token){msg(r.error||'ТВ не подключён');return;}
    token=r.token;ack=null;$('pin').value='';display('service',false);display('leave',true);$('leave').focus();
    msg('Ноутбук подключён. Сеанс открыт на 15 минут.');sync(g);
  });
};
$('addId').onclick=function(){
  var id=$('appId').value.trim();
  if(!/^[A-Za-z0-9._-]{1,100}$/.test(id)||extras.length>=100){msg('Проверьте ID. Допускается до 100 дополнительных приложений.');return;}
  lg('getAppLoadStatus',{appId:id},function(r){
    if(!r.exist){msg('webOS не обнаружила приложение с таким ID.');return;}
    try{if(extras.indexOf(id)<0)extras.push(id);localStorage.setItem(key+'.ids',JSON.stringify(extras));}catch(e){fail(e);return;}
    discover(function(){render();msg('Ярлык проверен и добавлен.');});
  },fail);
};
$('leave').onclick=function(){if(token)req('leave',{token:token},function(){});closeLocal('Сеанс закрыт пультом.');$('setup').focus();};
$('setup').onclick=openSetup;$('hideSetup').onclick=function(){display('service',false);$('setup').focus();};
$('openGuide').onclick=showGuide;$('hideGuide').onclick=function(){display('guide',false);$('openGuide').focus();};
var tips=brand==='lg'?[
 ['Реклама и рекомендации','На webOS 6.0/22 откройте Все настройки → Общие → Система → Дополнительные настройки → Настройки главного экрана. Отключите Home Promotion и Content Recommendation, если они доступны.'],
 ['Удаление приложения','На webOS 22 нажмите Home → Редактировать список приложений. Выберите приложение, корзину и подтвердите удаление.']]:
 [['Память и кеш','Если доступно, откройте Настройки → Поддержка → Обслуживание устройства (Device Care) → Управление хранилищем. В сведениях о приложении можно проверить размер и очистить кеш.'],
 ['Удаление приложения','В разделе Apps откройте настройки приложений, выберите загруженное приложение и нажмите «Удалить». Предустановленные приложения Samsung защищены от удаления.']];
tips.forEach(function(t){var p=document.createElement('p'),b=document.createElement('b');b.textContent=t[0]+'. ';p.appendChild(b);p.appendChild(document.createTextNode(t[1]));$('steps').appendChild(p);});
$('platform').textContent=brand==='lg'?'LG webOS':'Samsung Tizen';
$('mode').textContent=nativeMode?'Приложение TV Desk':'Браузерный режим · веб-ярлыки';
display('hostLabel',nativeMode);display('lgExtra',nativeMode&&brand==='lg');
try{var stored=JSON.parse(localStorage.getItem(key+'.ids')||'[]');if(Array.isArray(stored))extras=stored.filter(function(i){return typeof i==='string'&&/^[\w.-]{1,100}$/.test(i);}).slice(0,100);}catch(e){}
discover(function(){try{profile=clean(JSON.parse(localStorage.getItem(key)||'{}'));}catch(e){}ready=true;render();});
if(nativeMode&&brand==='samsung'){
  var mounts=null,scanning=false;
  function scanUSB(){
    if(scanning||document.hidden||token)return;
    try{
      scanning=true;window.tizen.filesystem.listStorages(function(volumes){
        scanning=false;var next={};
        volumes.forEach(function(v){
          if(v.type!=='EXTERNAL'||v.state!=='MOUNTED')return;
          next[v.label]=true;
          if(mounts&&!mounts[v.label]){
            window.tizen.filesystem.resolve(v.label+'/TV-DESK.SERVICE',function(f){
              if(f.isFile&&!document.hidden&&!token)openSetup();
            },function(){},'r');
          }
        });mounts=next;
      },function(){scanning=false;});
    }catch(e){scanning=false;}
  }
  scanUSB();setInterval(scanUSB,3000);
}
document.addEventListener('keydown',function(e){
  if(e.keyCode===10009||e.keyCode===27||e.keyCode===461){
    if(!$('guide').hidden||!$('service').hidden){e.preventDefault();display('guide',false);display('service',false);$('setup').focus();return;}
    if(nativeMode&&brand==='samsung'){try{window.tizen.application.getCurrentApplication().exit();}catch(err){}}
    return;
  }
  if([37,38,39,40].indexOf(e.keyCode)<0)return;
  if(document.activeElement.tagName==='INPUT'&&(e.keyCode===37||e.keyCode===39))return;
  var nodes=Array.prototype.filter.call(document.querySelectorAll('button,input'),function(b){return !b.disabled&&b.offsetWidth>0;});
  var i=nodes.indexOf(document.activeElement);if(!nodes.length)return;
  i=Math.max(0,Math.min(nodes.length-1,i+(e.keyCode===37||e.keyCode===38?-1:1)));e.preventDefault();nodes[i].focus();
});
showClock();setInterval(function(){if(!document.hidden)showClock();},15000);
$('setup').focus();
}());
