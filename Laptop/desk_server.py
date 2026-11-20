"""TV Desk. Local operator panel and LAN pairing for webOS/Tizen clients."""
import argparse
import hmac
import ipaddress
import json
import re
import secrets
import socket
import threading
import time
import webbrowser
from remote import remote_call
import samsung_id
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
BRANDS = {"lg": "LG", "samsung": "Samsung"}
ID = re.compile(r"[A-Za-z0-9._:-]{1,160}\Z")
lock = threading.Lock()
session = None
admin_key = secrets.token_urlsafe(32)

class Problem(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status

def require(ok, msg, status=400):
    if not ok:
        raise Problem(msg, status)

def safe_text(value, limit):
    return isinstance(value, str) and 0 < len(value.strip()) <= limit and all(ord(c) >= 32 for c in value)

def catalog_data(items):
    require(isinstance(items, list) and len(items) <= 1024, "Недопустимый каталог")
    out, seen = [], set()
    for a in items:
        require(isinstance(a, dict), "Неверная запись каталога")
        i, n = a.get("id"), a.get("name")
        require(isinstance(i, str) and ID.fullmatch(i) and i not in seen and safe_text(n, 160), "Неверный ярлык")
        seen.add(i)
        size = a.get("size")
        out.append({"id": i, "name": n, "size": size if type(size) is int and 0 <= size < 2**50 else None})
    return out

def profile_data(value, apps):
    require(isinstance(value, dict) and safe_text(value.get("title"), 48), "Введите название до 48 символов")
    ids = {a["id"] for a in apps}
    p = {"title":value["title"].strip()}
    if "city" in value:
        city=value["city"]
        require(isinstance(city,str) and len(city)<=80 and all(ord(c)>=32 for c in city),"Проверьте название города")
        p["city"]=city.strip()
    seen = set()
    for key in ("pins", "hidden"):
        seq = value.get(key)
        require(isinstance(seq, list) and len(seq) <= len(ids), "Неверный профиль")
        for i in seq:
            require(isinstance(i, str) and i in ids and i not in seen, "Обновите профиль по каталогу телевизора")
            seen.add(i)
        p[key] = list(seq)
    return p

def addresses():
    try:
        return sorted({ip for ip in socket.gethostbyname_ex(socket.gethostname())[2]
                       if not ipaddress.ip_address(ip).is_loopback})
    except OSError:
        return []

def process(op, data):
    global session
    now = time.monotonic()
    with lock:
        if session and now >= session["until"]:
            session = None
        s = session
        if op == "start":
            brand = data.get("brand")
            require(brand in BRANDS, "Выберите марку")
            require(not s, "Сначала завершите текущий сеанс", 409)
            session = {"id":secrets.token_urlsafe(18), "brand":brand, "pin":"%08d" % secrets.randbelow(10**8),
                       "until":now+180, "bad":0, "token":"", "apps":[], "profile":None, "mode":"",
                       "environment":{}, "duid":"", "peer":"", "last":0, "seq":0, "job":None, "result":None}
            return {"ok":True}
        if op == "status":
            if not s:
                return {"active":False}
            return {**{k:s[k] for k in ("id","brand","pin","apps","profile","mode","seq","result","peer","environment","duid")},
                    "active":True, "paired":bool(s["token"]), "online":s["last"]>now-12 and bool(s["token"]),
                    "pending":bool(s["job"]), "seconds":max(0,int(s["until"]-now))}
        require(s, "Сеанс завершён. Откройте новый сеанс на ноутбуке.", 410)
        if op == "join":
            require(not s["token"], "Код уже использован", 409)
            code = data.get("pin")
            match = isinstance(code,str) and re.fullmatch(r"[0-9]{8}",code) and hmac.compare_digest(code,s["pin"])
            if not match or data.get("brand") != s["brand"]:
                s["bad"] += 1
                if s["bad"] >= 8:
                    session = None
                raise Problem("Проверьте марку и код на ноутбуке", 403)
            apps = catalog_data(data.get("apps"))
            profile = profile_data(data.get("profile"), apps)
            require(data.get("mode") in ("browser","native"), "Неверный режим")
            context=data.get("environment",{})
            if isinstance(context,dict):
                s["environment"]={k:context[k] for k in ("language","zone","localeRegion","clock") if safe_text(context.get(k),100)}
            if s["brand"]=="samsung" and data["mode"]=="native" and safe_text(data.get("duid"),160):
                s["duid"]=data["duid"]
            s.update(peer=data["_peer"],token=secrets.token_urlsafe(32),pin="",until=now+900,last=now,apps=apps,profile=profile,mode=data["mode"])
            return {"token":s["token"]}
        if op in ("sync","leave"):
            token = data.get("token")
            require(s["token"] and isinstance(token,str) and token.isascii() and hmac.compare_digest(token,s["token"]), "ТВ отключён",403)
            if op=="leave":
                session=None
                return {"ok":True}
            s["last"]=now
            job=s["job"]
            if job and data.get("ack")==job["seq"]:
                success=data.get("ok") is True
                if success and job["kind"]=="profile":
                    s["profile"]=job["profile"]
                s["result"]={"seq":job["seq"],"ok":success,"kind":job["kind"]}
                s["job"]=None
            return {"job":s["job"]}
        require(data.get("id")==s["id"], "Телевизор сменился. Обновите страницу.",409)
        if op=="end":
            session=None
            return {"ok":True}
        require(op in ("profile","guide"),"Неизвестное действие",404)
        require(s["token"] and now-s["last"]<12,"Телевизор не на связи",409)
        require(s["job"] is None,"Дождитесь ответа телевизора",409)
        job={"seq":s["seq"]+1,"kind":op}
        if op=="profile":
            job["profile"]=profile_data(data.get("profile"),s["apps"])
            if "city" not in s["profile"]:
                require(not job["profile"].get("city"),"Обновите приложение TV Desk на ТВ для сохранения города")
                job["profile"].pop("city",None)
            elif "city" not in job["profile"]:
                job["profile"]["city"]=s["profile"]["city"]
        s.update(seq=job["seq"],job=job,result=None)
        return {"seq":job["seq"]}

class Handler(BaseHTTPRequestHandler):
    server_version="TVDesk/0.3"
    def log_message(self,*args):
        pass
    def send_data(self,status,body,kind="application/json; charset=utf-8",tv=False):
        if not isinstance(body,bytes):
            body=json.dumps(body,ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type",kind)
        self.send_header("Cache-Control","no-store")
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("X-Frame-Options","DENY")
        self.send_header("Referrer-Policy","no-referrer")
        if tv:
            # Packaged TV apps can have file/null or vendor-assigned origins.
            # Only PIN/token-protected TV operations are exposed cross-origin.
            self.send_header("Access-Control-Allow-Origin","*")
        self.send_header("Content-Length",str(len(body)))
        self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
    def local(self):
        host=self.headers.get("Host","")
        return (ipaddress.ip_address(self.client_address[0]).is_loopback
                and host in {"127.0.0.1:"+str(self.server.server_port),"localhost:"+str(self.server.server_port)})
    def do_GET(self):
        path=urlsplit(self.path).path
        if path in ("/","/TV-Desk.html","/Android-Bluetooth.html","/admin/key"):
            if not self.local():
                return self.send_data(403,{"error":"Панель открывается только на ноутбуке"})
            if path=="/admin/key":
                return self.send_data(200,{"key":admin_key,"addresses":addresses(),"port":self.server.server_port})
            file=ROOT/("Android-Bluetooth.html" if "Android-Bluetooth" in path else "TV-Desk.html")
            return self.send_data(200,file.read_bytes(),"text/html; charset=utf-8")
        m=re.fullmatch(r"/(lg|samsung)(?:/(index.html|tv.js|tv.css)?)?",path)
        if m:
            if path in ("/lg","/samsung"):
                self.send_response(302);self.send_header("Location",path+"/");self.end_headers();return
            name=m[2] or "index.html"
            if name=="index.html":
                file=ROOT/"browser"/(m[1]+".html")
            else:
                file=ROOT/"browser"/name
            mime={"tv.js":"text/javascript","tv.css":"text/css","index.html":"text/html"}[name]
            return self.send_data(200,file.read_bytes(),mime+"; charset=utf-8")
        self.send_data(404,{"error":"Страница не найдена"})
    def do_POST(self):
        path=urlsplit(self.path).path
        tv=path in ("/tv/join","/tv/sync","/tv/leave")
        try:
            if not tv:
                require(path in {"/admin/"+x for x in ("start","status","end","profile","guide","remote/connect","remote/key","remote/end","remote/status","sdb/list","sdb/read")}, "Нет такого действия",404)
                require(self.local() and self.headers.get("X-TV-Desk")==admin_key,"Откройте панель на ноутбуке",403)
                origin=self.headers.get("Origin")
                require(not origin or origin=="http://"+self.headers["Host"],"Запрос из другой страницы",403)
            n=self.headers.get("Content-Length","")
            require(n.isdigit() and len(n)<=7 and 0<int(n)<=524288,"Неверный размер запроса",413)
            data=json.loads(self.rfile.read(int(n)))
            require(isinstance(data,dict),"Нужен JSON-объект")
            if path=="/tv/join":data["_peer"]=self.client_address[0]
            if path in ("/admin/sdb/list","/admin/sdb/read"):
                try:result={"devices":samsung_id.devices()} if path.endswith("/list") else {"duid":samsung_id.read(data.get("serial"))}
                except ValueError as e:raise Problem(str(e),409)
            elif path.startswith("/admin/remote/"):
                try:result=remote_call(path.rsplit("/",1)[1],data)
                except ValueError as e:raise Problem(str(e),409)
            else:result=process(path.rsplit("/",1)[1],data)
            self.send_data(200,result,tv=tv)
        except Problem as e:self.send_data(e.status,{"error":str(e)},tv=tv)
        except (ValueError,TypeError,UnicodeError,RecursionError):self.send_data(400,{"error":"Неверный запрос"},tv=tv)

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--port",type=int,default=8765)
    parser.add_argument("--no-browser",action="store_true")
    args=parser.parse_args()
    try:
        with ThreadingHTTPServer(("0.0.0.0",args.port),Handler) as server:
            front="http://127.0.0.1:"+str(server.server_port)
            print("TV Desk • Откройте на ноутбуке",front,flush=True)
            if not args.no_browser:webbrowser.open(front)
            print("Оставьте окно открытым. Для выключения нажмите Ctrl+C.",flush=True)
            server.serve_forever()
    except KeyboardInterrupt:pass
    except OSError as e:raise SystemExit("Не удалось запустить панель. Возможно, она уже открыта. "+str(e))
