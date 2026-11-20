"""One consented LAN remote, no discovery or persistent pairing credentials."""
import base64
import hashlib
import ipaddress
import json
import secrets
import socket
import ssl
import sys
import threading
import time
from pathlib import Path
from urllib.parse import quote, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent/"vendor"))
import websocket

PRIVATE = tuple(ipaddress.ip_network(n) for n in ("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16"))
KEYS = {"up":("UP","KEY_UP"),"down":("DOWN","KEY_DOWN"),"left":("LEFT","KEY_LEFT"),
        "right":("RIGHT","KEY_RIGHT"),"ok":("ENTER","KEY_ENTER"),"home":("HOME","KEY_HOME"),"back":("BACK","KEY_RETURN")}

def dial(ip, port, path, expected=None):
    # Consumer TVs commonly present a self-signed certificate. Keep all channels
    # on the explicitly entered IP and bind the LG input channel to the first certificate.
    tcp=socket.create_connection((ip,port),timeout=5)
    tls=None
    try:
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname=False
        ctx.verify_mode=ssl.CERT_NONE
        tls=ctx.wrap_socket(tcp,server_hostname=ip)
        cert=hashlib.sha256(tls.getpeercert(binary_form=True)).digest()
        if expected is not None and not secrets.compare_digest(cert,expected):
            raise ValueError("Сертификат канала ввода отличается. Повторите сопряжение.")
        ws=websocket.create_connection("wss://"+ip+":"+str(port)+path,socket=tls,timeout=6,
                                       suppress_origin=True,redirect_limit=0)
        if ws.getstatus()!=101:
            ws.close();raise ValueError("Телевизор не открыл канал пульта")
        return ws,cert
    except Exception:
        (tls or tcp).close()
        raise

def wait_event(ws,check):
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        ws.settimeout(max(.1,deadline-time.monotonic()))
        frame=ws.recv()
        if not frame or len(frame)>262144:
            raise ValueError("Телевизор закрыл канал пульта")
        item=json.loads(frame)
        if item.get("type")=="error" or item.get("event") in ("ms.channel.unauthorized","ms.channel.timeOut","ms.error"):
            raise ValueError("ТВ отклонил запрос. Проверьте разрешение на подключение в его меню.")
        if check(item):
            ws.settimeout(4);return item
    raise ValueError("Время подтверждения на телевизоре истекло")

class Remote:
    def __init__(self,brand,ip):
        self.brand,self.ip=brand,ip
        self.id=secrets.token_urlsafe(16)
        self.sockets=[]
        self.expires=time.monotonic()+900
    def connect(self):
        if self.brand=="samsung":
            name=quote(base64.b64encode(b"TV Desk").decode(),safe="")
            ws,_=dial(self.ip,8002,"/api/v2/channels/samsung.remote.control?name="+name)
            self.sockets.append(ws)
            wait_event(ws,lambda m:m.get("event")=="ms.channel.connect")
        else:
            ws,cert=dial(self.ip,3001,"/");self.sockets.append(ws)
            ws.send(json.dumps({"id":"pair","type":"register","payload":{"forcePairing":False,"pairingType":"PROMPT",
                "manifest":{"manifestVersion":1,"appVersion":"0.3.0","permissions":["CONTROL_MOUSE_AND_KEYBOARD"]}}}))
            wait_event(ws,lambda m:m.get("type")=="registered")
            ws.send(json.dumps({"id":"input","type":"request","uri":"ssap://com.webos.service.networkinput/getPointerInputSocket","payload":{}}))
            reply=wait_event(ws,lambda m:m.get("id")=="input" and m.get("type")=="response")
            url=urlsplit(reply.get("payload",{}).get("socketPath",""))
            if url.scheme!="wss" or url.hostname!=self.ip or url.port!=3001 or url.username or not url.path.startswith("/"):
                raise ValueError("Модель не предоставила защищённый канал ввода LG")
            ptr,_=dial(self.ip,3001,url.path+("?"+url.query if url.query else ""),expected=cert);self.sockets.append(ptr)
    def send(self,key):
        if key not in KEYS:raise ValueError("Неизвестная кнопка")
        lg,sm=KEYS[key]
        if self.brand=="lg":self.sockets[-1].send("type:button\nname:"+lg+"\n\n")
        else:self.sockets[-1].send(json.dumps({"method":"ms.remote.control","params":{
            "Cmd":"Click","DataOfCmd":sm,"Option":"false","TypeOfRemote":"SendRemoteKey"}}))
    def close(self):
        for s in reversed(self.sockets):
            try:s.close(timeout=.5)
            except Exception:pass
        self.sockets.clear()

guard=threading.RLock()
device=None
expiry_timer=None

def release():
    global device,expiry_timer
    if expiry_timer:expiry_timer.cancel();expiry_timer=None
    if device:device.close();device=None

def expire(identifier):
    with guard:
        if device and device.id==identifier:release()

def remote_call(op,data):
    global device,expiry_timer
    with guard:
        if device and time.monotonic()>=device.expires:release()
        if op=="connect":
            if device:raise ValueError("Сначала отключите предыдущий пульт")
            try:ip=ipaddress.IPv4Address(data.get("ip",""))
            except ValueError:raise ValueError("Введите локальный IPv4 телевизора")
            if not any(ip in subnet for subnet in PRIVATE):raise ValueError("Введите частный IPv4 домашней сети")
            brand=data.get("brand")
            if brand not in ("lg","samsung"):raise ValueError("Выберите LG или Samsung")
            d=Remote(brand,str(ip))
            try:d.connect()
            except ValueError:d.close();raise
            except Exception:
                d.close();raise ValueError("ТВ не ответил. Проверьте IP, разрешение на экране и поддержку сетевого пульта.")
            device=d;device.expires=time.monotonic()+900
            expiry_timer=threading.Timer(900,expire,args=(device.id,));expiry_timer.daemon=True;expiry_timer.start()
        elif op in ("key","end"):
            if not device or device.id!=data.get("id"):raise ValueError("Сеанс пульта завершён")
            if op=="end":release()
            else:
                try:device.send(data.get("key"))
                except ValueError:raise
                except Exception:release();raise ValueError("Связь с пультом прервана")
        elif op!="status":raise ValueError("Неизвестная команда")
        return {"connected":True,"id":device.id,"brand":device.brand,"ip":device.ip} if device else {"connected":False}
