"""Build with the operator's installed Tizen SDK and selected Samsung certificate."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

root=Path(__file__).resolve().parent
def main():
    tizen=shutil.which("tizen") or shutil.which("tizen.bat")
    if not tizen:
        for directory in [os.environ.get("TIZEN_STUDIO_HOME"),"C:/tizen-studio",Path.home()/"tizen-studio"]:
            if directory:
                candidate=Path(directory)/"tools/ide/bin/tizen.bat"
                if candidate.is_file():tizen=str(candidate);break
    if not tizen:
        raise SystemExit("Не найден Tizen CLI. Установите TV SDK, Web CLI и Samsung Certificate Extension.")
    profile=input("Имя профиля сертификата Samsung для этого ТВ: ").strip()
    if not re.fullmatch(r"[\w .-]{1,80}",profile) or profile.startswith("-"):
        raise SystemExit("Введите имя профиля из Certificate Manager.")
    out=root/"TV-Desk-Samsung.wgt"
    if out.exists():
        raise SystemExit("Файл TV-Desk-Samsung.wgt уже существует. Переместите его перед новой сборкой.")
    with tempfile.TemporaryDirectory(prefix="tvdesk-tizen-") as folder:
        source=Path(folder)/"TVDeskSamsung"
        shutil.copytree(root/"app",source)
        subprocess.run([tizen,"build-web","--",str(source)],check=True,cwd=source)
        built=source/".buildResult"
        if not built.is_dir():raise SystemExit("SDK не создал каталог .buildResult")
        subprocess.run([tizen,"package","-t","wgt","-s",profile,"--",str(built)],check=True,cwd=built)
        packages=list(source.rglob("*.wgt"))
        if len(packages)!=1:raise SystemExit("Не удалось найти единственный собранный WGT")
        import zipfile
        with zipfile.ZipFile(packages[0]) as z:
            if not {"author-signature.xml","signature1.xml","config.xml","index.html"}.issubset(z.namelist()):
                raise SystemExit("SDK не добавил обе подписи. Проверьте профиль сертификата.")
        shutil.copyfile(packages[0],out)
    print("Пакет для установки через Tizen SDK:",out)
if __name__=="__main__":
    try:main()
    except subprocess.CalledProcessError as e:raise SystemExit("Tizen CLI завершился с ошибкой "+str(e.returncode))
