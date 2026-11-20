"""Read the device identifier of an explicitly selected, authorised Tizen SDK target."""
import os
from pathlib import Path
import re
import shutil
import subprocess

def sdb():
    name="sdb.exe" if os.name=="nt" else "sdb"
    found=shutil.which(name)
    if found:return found
    for base in (os.environ.get("TIZEN_STUDIO_HOME"),Path.home()/"tizen-studio","C:/tizen-studio",Path.home()/".tizen-extension-platform"):
        if base:
            p=Path(base)/"tools"/name
            if p.is_file():return str(p)
    raise ValueError("SDB не найден. Установите инструменты Tizen SDK и подключите ТВ в Device Manager.")

def run(*args):
    try:
        result=subprocess.run([sdb(),*args],capture_output=True,timeout=15,check=True,
                              encoding="utf-8",errors="replace")
        return result.stdout
    except (subprocess.SubprocessError,OSError):
        raise ValueError("Tizen SDK не получил ответ устройства. Проверьте Developer Mode и соединение.")

def devices():
    found=[]
    for line in run("devices").splitlines():
        fields=line.split()
        if len(fields)>=2 and fields[1]=="device" and re.fullmatch(r"[A-Za-z0-9_.:\[\]-]{1,160}",fields[0]) and not fields[0].startswith("-"):
            found.append({"serial":fields[0],"name":" ".join(fields[2:])[:120] or "Tizen"})
    return found

def identifier(value):
    token=value.strip().strip('"').strip("'")
    if re.fullmatch(r"[A-Za-z0-9.#_-]{8,160}",token) and token.lower() not in {"notfound","undefined","permission","unavailable","unsupported"}:
        return token
    return None

def read(serial):
    if serial not in {d["serial"] for d in devices()}:raise ValueError("Выберите подключённый ТВ из списка SDB")
    caps={}
    for line in run("-s",serial,"capability").splitlines():
        k,sep,v=line.partition(":")
        if sep:caps[k.strip().lower()]=v.strip()
    existing=identifier(caps.get("duid",""))
    if existing:return existing
    secure=caps.get("secure_protocol","").lower() in ("true","yes","1","enabled")
    if secure:
        subcmd=["0","getduidgadget" if caps.get("product_version")=="1.0" else "getduid"]
    elif "secure_protocol" in caps:subcmd=["/opt/etc/duid-gadget","2.0"]
    else:
        raise ValueError("SDK не сообщает способ чтения. Посмотрите Unique ID в разделе «Поддержка → Об этом ТВ».")
    output=run("-s",serial,"shell",*subcmd)
    rows=[x.strip() for x in output.splitlines() if x.strip()]
    candidates=[x.partition(":")[2] for x in rows if x.lower().startswith("duid:")]
    if not candidates and len(rows)==1:candidates=rows
    for value in candidates:
        candidate=identifier(value)
        if candidate:return candidate
    raise ValueError("DUID не распознан. Проверьте Unique ID в разделе поддержки на самом ТВ.")
