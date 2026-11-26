"""
Redmi Manager per PC (Windows e Mac) - parte senza interfaccia.

- riceve i backup dei telefoni nella rete di casa (server HTTP + ricerca automatica UDP)
- copia i backup ricevuti su chiavetta USB e NAS (SMB), solo i file nuovi
- si collega da solo al NAS appena lo vede acceso, come MailAllegati
"""
import json
import os
import platform
import random
import re
import secrets
import shutil
import socket
import string
import sys
import threading
import time
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

APP_NAME = "Redmi Manager"
APP_VERSION = "1.0.0"
PORT = 47810
DISCOVERY_PORT = 47811
ROOT = "RedmiManager"           # cartella dei backup (uguale su telefono, PC, chiavetta e NAS)
USB_MARKER = ".redmimanager_chiavetta"
IS_WIN = os.name == "nt"
IS_MAC = sys.platform == "darwin"

# Marche (stesse dell'app Android). Predefinita: Xiaomi / Redmi / POCO
BRANDS = [
    ("xiaomi", "Xiaomi / Redmi / POCO",
     "Sul telefono: Impostazioni › Impostazioni aggiuntive › Backup e ripristino › Backup dispositivo › Esegui il backup. "
     "I file vanno in MIUI/backup/AllBackup e Redmi Manager li copia se è attiva la voce \"Backup di fabbrica\".",
     "1) Con Redmi Manager sul telefono ripristina i \"File\" (contengono MIUI/backup/AllBackup).\n"
     "2) Sul telefono: Impostazioni › Backup e ripristino › Backup dispositivo › Ripristina."),
    ("samsung", "Samsung",
     "Smart Switch › Trasferimento su memoria esterna › Backup (su scheda SD o chiavetta).",
     "Ripristina i \"File\" con Redmi Manager, poi Smart Switch › Trasferimento su memoria esterna › Ripristina."),
    ("huawei", "Huawei / Honor",
     "Impostazioni › Sistema e aggiornamenti › Backup e ripristino › Backup dati › Memoria interna.",
     "Ripristina i \"File\" con Redmi Manager, poi Impostazioni › Sistema › Backup e ripristino › Ripristina."),
    ("oppo", "OPPO / realme / OnePlus",
     "Impostazioni › Impostazioni aggiuntive › Backup e migrazione › Backup locale › Nuovo backup.",
     "Ripristina i \"File\" con Redmi Manager, poi Backup e migrazione › Backup locale › Ripristina."),
    ("vivo", "vivo / iQOO",
     "EasyShare › Backup telefono.",
     "Ripristina i \"File\" con Redmi Manager, poi EasyShare › Ripristina."),
    ("motorola", "Motorola",
     "Nessun backup locale dei dati delle app: Redmi Manager salva tutto il resto.",
     "Ripristina con Redmi Manager e accedi di nuovo alle app."),
    ("pixel", "Google Pixel",
     "Nessun backup locale dei dati delle app: Redmi Manager salva tutto il resto senza Google.",
     "Ripristina con Redmi Manager e accedi di nuovo alle app."),
    ("nothing", "Nothing / CMF",
     "Nessun backup locale dei dati delle app: Redmi Manager salva tutto il resto.",
     "Ripristina con Redmi Manager e accedi di nuovo alle app."),
    ("altro", "Altra marca Android",
     "Se il telefono ha un \"backup locale\", eseguilo e aggiungi la sua cartella tra le \"Altre cartelle\" nell'app.",
     "Ripristina i \"File\" con Redmi Manager, poi usa il ripristino locale del telefono."),
]
DEFAULT_BRAND = "xiaomi"


# ---------------------------------------------------------------- cartelle e configurazione

def app_dir():
    if IS_WIN:
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif IS_MAC:
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    d = os.path.join(base, "RedmiManager")
    os.makedirs(d, exist_ok=True)
    return d


APP_DIR = app_dir()
CONFIG_FILE = os.path.join(APP_DIR, "config.json")
LOG_FILE = os.path.join(APP_DIR, "registro.txt")

DEFAULTS = {
    "disclaimer": 0,
    "pc_id": "",
    "pc_name": "",
    "port": PORT,
    "backup_dir": os.path.join(os.path.expanduser("~"), "Redmi Manager Backup"),
    "brand": DEFAULT_BRAND,
    "tokens": {},               # chiave -> {"device": nome, "since": data}
    "phones": {},               # nome telefono -> ultime informazioni ricevute
    "usb_enabled": False,
    "usb_id": "",               # contenuto del file segnaposto sulla chiavetta scelta
    "usb_label": "",
    "nas_enabled": False,
    "nas_path": "",
    "nas_user": "",
    "nas_domain": "",
    "autostart": False,
    "keep_running": True,
}
DISCLAIMER_VERSION = 1


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except Exception:
        pass
    if not cfg.get("pc_id"):
        cfg["pc_id"] = uuid.uuid4().hex[:12]
    if not cfg.get("pc_name"):
        cfg["pc_name"] = socket.gethostname().split(".")[0] or "PC"
    return cfg


_cfg_lock = threading.RLock()


def save_config(cfg):
    with _cfg_lock:
        tmp = CONFIG_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=1, ensure_ascii=False)
        os.replace(tmp, CONFIG_FILE)


_log_lock = threading.Lock()
_log_listeners = []


def log(text):
    line = datetime.now().strftime("%d/%m %H:%M:%S") + "  " + str(text)
    with _log_lock:
        try:
            if os.path.exists(LOG_FILE) and os.path.getsize(LOG_FILE) > 500_000:
                with open(LOG_FILE, "r", encoding="utf-8", errors="replace") as f:
                    keep = f.readlines()[-1000:]
                with open(LOG_FILE, "w", encoding="utf-8") as f:
                    f.writelines(keep)
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass
    for cb in list(_log_listeners):
        try:
            cb(line)
        except Exception:
            pass


def read_log(lines=400):
    try:
        with open(LOG_FILE, "r", encoding="utf-8", errors="replace") as f:
            return "".join(f.readlines()[-lines:])
    except Exception:
        return ""


def human_size(n):
    n = float(n or 0)
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {u}" if u in ("B", "KB") else f"{n:.1f} {u}".replace(".", ",")
        n /= 1024
    return f"{n:.2f} TB".replace(".", ",")


def human_time(ms):
    if not ms:
        return "mai"
    return datetime.fromtimestamp(ms / 1000).strftime("%d/%m/%Y %H:%M")


def stamp_to_human(name):
    m = re.match(r"(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})", name or "")
    if not m:
        return name
    y, mo, d, h, mi = m.groups()
    return f"{d}/{mo}/{y} {h}:{mi}"


# ---------------------------------------------------------------- password NAS

def get_nas_password():
    try:
        import keyring
        return keyring.get_password("RedmiManager", "nas") or ""
    except Exception:
        return ""


def set_nas_password(pw):
    try:
        import keyring
        if pw:
            keyring.set_password("RedmiManager", "nas", pw)
        else:
            try:
                keyring.delete_password("RedmiManager", "nas")
            except Exception:
                pass
        return True
    except Exception:
        return False


# ---------------------------------------------------------------- rete

def local_ips():
    """Indirizzi IPv4 del PC nella rete di casa (il primo e' quello usato per uscire)."""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.168.1.1", 9))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    priv = [ip for ip in ips if ip.startswith(("192.168.", "10.")) or re.match(r"172\.(1[6-9]|2\d|3[01])\.", ip)]
    return priv or ips


def port_open(host, port, timeout=1.5):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def scan_nas():
    """Cerca nella rete del PC i dispositivi con condivisione file (porta 445)."""
    ips = local_ips()
    if not ips:
        return []
    prefix = ips[0].rsplit(".", 1)[0]
    found = []
    lock = threading.Lock()

    def probe(n):
        h = f"{prefix}.{n}"
        if h in ips:
            return
        if port_open(h, 445, 0.4):
            try:
                name = socket.gethostbyaddr(h)[0]
            except Exception:
                name = ""
            with lock:
                found.append((h, name))

    threads = [threading.Thread(target=probe, args=(n,), daemon=True) for n in range(1, 255)]
    for i in range(0, len(threads), 50):
        batch = threads[i:i + 50]
        for t in batch:
            t.start()
        for t in batch:
            t.join(3)
    return sorted(found, key=lambda x: int(x[0].rsplit(".", 1)[1]))


# ---------------------------------------------------------------- percorsi sicuri

_BAD = re.compile(r'[<>:"|?*\x00-\x1f]')


def clean_rel(path):
    """Percorso relativo sicuro: niente '..', niente percorsi assoluti, caratteri validi su Windows."""
    parts = []
    for seg in (path or "").replace("\\", "/").split("/"):
        seg = seg.strip()
        if not seg or seg in (".", ".."):
            continue
        seg = _BAD.sub("_", seg).rstrip(" .")
        if seg:
            parts.append(seg)
    return parts


def safe_join(base, rel):
    parts = clean_rel(rel)
    full = os.path.abspath(os.path.join(base, *parts)) if parts else os.path.abspath(base)
    b = os.path.abspath(base)
    if full != b and not full.startswith(b + os.sep):
        raise ValueError("percorso non valido")
    return full


# ---------------------------------------------------------------- server per i telefoni

class PhoneServer:
    """Riceve i backup dei telefoni. I telefoni si collegano con il codice QR o il codice a 6 cifre."""

    def __init__(self, cfg, on_change=None):
        self.cfg = cfg
        self.on_change = on_change or (lambda what: None)
        self.httpd = None
        self.udp = None
        self.code = ""
        self.code_time = 0
        self.wrong = 0
        self.dirty = threading.Event()      # ci sono file nuovi da copiare su chiavetta/NAS
        self.active = {}                    # telefono -> ora dell'ultimo file ricevuto
        self.new_code()

    # --- codice di collegamento
    def new_code(self):
        self.code = "".join(random.SystemRandom().choice(string.digits) for _ in range(6))
        self.code_time = time.time()
        self.wrong = 0
        return self.code

    def code_valid(self):
        return time.time() - self.code_time < 15 * 60

    def qr_text(self):
        ips = ",".join(local_ips())
        return f"redmimanager://pair?h={ips}&p={self.cfg.get('port', PORT)}&c={self.code}"

    @property
    def root(self):
        return self.cfg["backup_dir"]

    def start(self):
        os.makedirs(self.root, exist_ok=True)
        server = self
        port = int(self.cfg.get("port", PORT))

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            server_version = "RedmiManager/" + APP_VERSION

            def log_message(self, *a):
                pass

            # --- utilita'
            def _send(self, code, body=b"", ctype="application/json"):
                if isinstance(body, str):
                    body = body.encode("utf-8")
                elif not isinstance(body, (bytes, bytearray)):
                    body = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

            def _auth(self):
                tok = self.headers.get("X-Token", "")
                info = server.cfg.get("tokens", {}).get(tok) if tok else None
                if not info:
                    self._drain()
                    self._send(401, {"errore": "non autorizzato"})
                    return None
                return info

            def _drain(self):
                n = int(self.headers.get("Content-Length") or 0)
                while n > 0:
                    chunk = self.rfile.read(min(n, 1 << 16))
                    if not chunk:
                        break
                    n -= len(chunk)

            def _json_body(self):
                n = int(self.headers.get("Content-Length") or 0)
                if n > 1_000_000:
                    raise ValueError("troppo grande")
                return json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}

            def _path(self):
                q = parse_qs(urlparse(self.path).query)
                return (q.get("path") or [""])[0]

            def _route(self):
                return urlparse(self.path).path

            # --- GET
            def do_GET(self):
                try:
                    r = self._route()
                    if r == "/api/info":
                        if not self._auth():
                            return
                        return self._send(200, {"app": "redmimanager", "id": server.cfg["pc_id"],
                                                "name": server.cfg["pc_name"], "version": APP_VERSION})
                    if not self._auth():
                        return
                    full = safe_join(server.root, self._path())
                    if r == "/api/size":
                        size = os.path.getsize(full) if os.path.isfile(full) else -1
                        return self._send(200, str(size), "text/plain")
                    if r == "/api/list":
                        if not os.path.isdir(full):
                            return self._send(404, [])
                        items = []
                        for e in os.scandir(full):
                            if e.name.endswith(".parziale"):
                                continue
                            try:
                                isdir = e.is_dir()
                                items.append({"name": e.name, "dir": isdir, "size": 0 if isdir else e.stat().st_size})
                            except OSError:
                                pass
                        return self._send(200, items)
                    if r == "/api/file":
                        if not os.path.isfile(full):
                            return self._send(404, {"errore": "file non trovato"})
                        size = os.path.getsize(full)
                        self.send_response(200)
                        self.send_header("Content-Type", "application/octet-stream")
                        self.send_header("Content-Length", str(size))
                        self.end_headers()
                        with open(full, "rb") as f:
                            shutil.copyfileobj(f, self.wfile, 1 << 18)
                        return
                    self._send(404, {"errore": "sconosciuto"})
                except ValueError as e:
                    self._send(400, {"errore": str(e)})
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as e:
                    log(f"Errore richiesta {self.path}: {e}")
                    try:
                        self._send(500, {"errore": str(e)})
                    except Exception:
                        pass

            # --- POST
            def do_POST(self):
                try:
                    r = self._route()
                    if r == "/api/pair":
                        body = self._json_body()
                        code = str(body.get("code", "")).strip()
                        if not server.code_valid() or code != server.code:
                            server.wrong += 1
                            if server.wrong >= 10:
                                server.new_code()
                                server.on_change("code")
                            return self._send(403, {"errore": "codice errato o scaduto"})
                        tok = secrets.token_urlsafe(24)
                        dev = str(body.get("device") or "telefono")
                        with _cfg_lock:
                            server.cfg.setdefault("tokens", {})[tok] = {
                                "device": dev, "since": datetime.now().strftime("%d/%m/%Y %H:%M")}
                            server._store_phone(body)
                            save_config(server.cfg)
                        server.new_code()
                        log(f"Telefono collegato: {dev} ({body.get('model', '')})")
                        server.on_change("phones")
                        server.on_change("code")
                        return self._send(200, {"token": tok, "id": server.cfg["pc_id"], "name": server.cfg["pc_name"]})
                    if r == "/api/hello":
                        if not self._auth():
                            return
                        body = self._json_body()
                        with _cfg_lock:
                            server._store_phone(body)
                            save_config(server.cfg)
                        server.on_change("phones")
                        return self._send(200, {"ok": True})
                    self._drain()
                    self._send(404, {"errore": "sconosciuto"})
                except Exception as e:
                    self._send(400, {"errore": str(e)})

            # --- PUT: ricezione di un file
            def do_PUT(self):
                tmp = None
                try:
                    info = self._auth()
                    if not info:
                        return
                    full = safe_join(server.root, self._path())
                    if full == os.path.abspath(server.root):
                        raise ValueError("percorso mancante")
                    os.makedirs(os.path.dirname(full), exist_ok=True)
                    tmp = full + ".parziale"
                    with open(tmp, "wb") as f:
                        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                            while True:
                                line = self.rfile.readline().strip()
                                size = int(line.split(b";")[0], 16)
                                if size == 0:
                                    self.rfile.readline()
                                    break
                                remaining = size
                                while remaining > 0:
                                    chunk = self.rfile.read(min(remaining, 1 << 18))
                                    if not chunk:
                                        raise ConnectionError("trasferimento interrotto")
                                    f.write(chunk)
                                    remaining -= len(chunk)
                                self.rfile.readline()
                        else:
                            remaining = int(self.headers.get("Content-Length") or 0)
                            while remaining > 0:
                                chunk = self.rfile.read(min(remaining, 1 << 18))
                                if not chunk:
                                    raise ConnectionError("trasferimento interrotto")
                                f.write(chunk)
                                remaining -= len(chunk)
                    os.replace(tmp, full)
                    tmp = None
                    dev = clean_rel(self._path())[1:2]
                    server.active[dev[0] if dev else info.get("device", "")] = time.time()
                    server.dirty.set()
                    self._send(200, {"ok": True})
                except ValueError as e:
                    self._send(400, {"errore": str(e)})
                except Exception as e:
                    log(f"Errore ricezione {self._path()}: {e}")
                    try:
                        self._send(500, {"errore": str(e)})
                    except Exception:
                        pass
                finally:
                    if tmp and os.path.exists(tmp):
                        try:
                            os.remove(tmp)
                        except OSError:
                            pass

            def do_DELETE(self):
                try:
                    if not self._auth():
                        return
                    full = safe_join(server.root, self._path())
                    if full == os.path.abspath(server.root):
                        raise ValueError("percorso mancante")
                    if os.path.isdir(full):
                        shutil.rmtree(full, ignore_errors=True)
                    elif os.path.exists(full):
                        os.remove(full)
                    server.dirty.set()
                    self._send(200, {"ok": True})
                except ValueError as e:
                    self._send(400, {"errore": str(e)})
                except Exception as e:
                    self._send(500, {"errore": str(e)})

        self.httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
        self.httpd.daemon_threads = True
        threading.Thread(target=self.httpd.serve_forever, daemon=True, name="server").start()
        threading.Thread(target=self._discovery, daemon=True, name="ricerca").start()
        log(f"In attesa dei telefoni su {', '.join(local_ips()) or 'questo PC'} porta {port}")

    def _store_phone(self, body):
        dev = str(body.get("device") or "telefono")
        info = {k: body.get(k) for k in ("model", "brand", "android", "battery", "charging", "total", "free",
                                          "app", "ip", "last_backup")}
        info["seen"] = int(time.time() * 1000)
        self.cfg.setdefault("phones", {})[dev] = info

    def _discovery(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", DISCOVERY_PORT))
            self.udp = s
        except Exception as e:
            log(f"Ricerca automatica non disponibile ({e}): usa il codice QR")
            return
        while True:
            try:
                data, addr = s.recvfrom(1024)
                if data.strip() == b"REDMIMANAGER_CERCA":
                    reply = json.dumps({"app": "redmimanager", "id": self.cfg["pc_id"], "name": self.cfg["pc_name"],
                                        "port": int(self.cfg.get("port", PORT))}).encode()
                    s.sendto(reply, addr)
            except OSError:
                return
            except Exception:
                pass

    def revoke(self, token):
        with _cfg_lock:
            self.cfg.get("tokens", {}).pop(token, None)
            save_config(self.cfg)

    def receiving(self):
        """Telefoni che hanno inviato file negli ultimi 30 secondi"""
        now = time.time()
        return [d for d, t in self.active.items() if now - t < 30]

    def stop(self):
        try:
            if self.httpd:
                self.httpd.shutdown()
        except Exception:
            pass
        try:
            if self.udp:
                self.udp.close()
        except Exception:
            pass


# ---------------------------------------------------------------- destinazioni della copia (chiavetta, NAS)

def parse_nas_path(path):
    """Converte \\\\server\\share\\sub, //server/share/sub o smb://server/share/sub in (server, porta, unc)."""
    p = (path or "").strip()
    p = re.sub(r"^smb://", "", p, flags=re.I)
    p = p.replace("/", "\\").strip("\\")
    parts = [x for x in p.split("\\") if x]
    if len(parts) < 2:
        raise ValueError("Percorso NAS non valido. Esempio: \\\\192.168.1.198\\FRITZ.NAS")
    server = parts[0]
    port = 445
    if ":" in server:
        server, port_s = server.rsplit(":", 1)
        if port_s.strip():
            if not port_s.strip().isdigit():
                raise ValueError(f"Porta NAS non valida: '{port_s}'")
            port = int(port_s)
    if not server:
        raise ValueError("Percorso NAS non valido: manca l'indirizzo del NAS")
    return server, port, "\\\\" + server + "\\" + "\\".join(parts[1:])


def friendly_nas_error(server, port, err):
    t = str(err)
    low = t.lower()
    if any(k in low for k in ("timed out", "10060", "winerror 53", "winerror 64", "no route", "unreachable",
                              "10065", "10061", "connection refused")):
        return (f"Il NAS {server} non risponde (porta {port}). Controlla che sia acceso, che l'indirizzo sia giusto "
                f"e che la condivisione file Windows/SMB sia attiva. [{t[:150]}]")
    if any(k in low for k in ("logon_failure", "access_denied", "access is denied", "accesso negato",
                              "winerror 5]", "winerror 1326", "winerror 86", "password", "1326")):
        return f"Utente o password del NAS non accettati, oppure l'utente non ha i permessi sulla cartella. [{t[:150]}]"
    if any(k in low for k in ("bad_network_name", "winerror 67", "network name")):
        return f"La cartella condivisa indicata non esiste sul NAS: controlla il nome dopo l'indirizzo. [{t[:150]}]"
    return t


class FolderTarget:
    """Copia su una cartella del disco (es. la chiavetta USB)."""

    def __init__(self, base, label):
        self.base = base
        self.label = label
        self.key = "usb"

    def connect(self):
        os.makedirs(self.base, exist_ok=True)

    def size(self, rel):
        p = os.path.join(self.base, *rel.split("/"))
        return os.path.getsize(p) if os.path.isfile(p) else -1

    def copy(self, src, rel):
        dst = os.path.join(self.base, *rel.split("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        tmp = dst + ".parziale"
        shutil.copyfile(src, tmp)
        os.replace(tmp, dst)

    def delete(self, rel):
        p = os.path.join(self.base, *rel.split("/"))
        try:
            if os.path.isfile(p):
                os.remove(p)
            d = os.path.dirname(p)
            while d.startswith(self.base) and d != self.base and os.path.isdir(d) and not os.listdir(d):
                os.rmdir(d)
                d = os.path.dirname(d)
        except OSError:
            pass

    def close(self):
        pass


class NasTarget:
    """Copia sul NAS. Su Windows usa prima la condivisione di rete del sistema (come Esplora file),
    altrimenti il client SMB integrato (Mac e casi particolari). Stessa logica di MailAllegati."""

    def __init__(self, cfg, password):
        self.cfg = cfg
        self.password = password
        self.server, self.port, self.unc = parse_nas_path(cfg["nas_path"])
        self.base = self.unc + "\\" + ROOT
        self.mode = None
        self.key = "nas"
        self.label = "NAS " + self.unc

    def _share_root(self):
        parts = [x for x in self.unc.split("\\") if x]
        return "\\\\" + parts[0] + "\\" + parts[1]

    def _user(self):
        user = (self.cfg.get("nas_user") or "").strip()
        if user and (self.cfg.get("nas_domain") or "").strip():
            user = self.cfg["nas_domain"].strip() + "\\" + user
        return user

    def _native_connect(self):
        user = self._user()
        if user:
            import subprocess
            subprocess.run(["net", "use", self._share_root(), self.password or "", "/user:" + user, "/persistent:no"],
                           capture_output=True, timeout=40, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        os.makedirs(self.base, exist_ok=True)
        os.listdir(self.base)

    def _smb_connect(self):
        import smbclient
        user = self._user() or None
        smbclient.register_session(self.server, username=user, password=self.password or None, port=self.port,
                                   connection_timeout=15, auth_protocol="ntlm" if user else "negotiate",
                                   require_signing=False)
        smbclient.makedirs(self.base, exist_ok=True, port=self.port)

    def connect(self):
        if self.mode:
            return
        if not port_open(self.server, self.port, 3):
            raise ConnectionError(f"Il NAS {self.server} non è raggiungibile (spento o fuori rete)")
        errors = []
        if IS_WIN and self.port == 445:
            try:
                self._native_connect()
                self.mode = "windows"
                return
            except Exception as e:
                errors.append(e)
        try:
            self._smb_connect()
            self.mode = "smb"
            return
        except Exception as e:
            errors.append(e)
        raise ConnectionError(friendly_nas_error(self.server, self.port, errors[0]))

    def _p(self, rel):
        return self.base + "\\" + rel.replace("/", "\\")

    def size(self, rel):
        p = self._p(rel)
        if self.mode == "windows":
            return os.path.getsize(p) if os.path.isfile(p) else -1
        import smbclient
        try:
            st = smbclient.stat(p, port=self.port)
            import stat as st_mod
            return -1 if st_mod.S_ISDIR(st.st_mode) else st.st_size
        except Exception:
            return -1

    def copy(self, src, rel):
        p = self._p(rel)
        d = p.rsplit("\\", 1)[0]
        tmp = p + ".parziale"
        if self.mode == "windows":
            os.makedirs(d, exist_ok=True)
            shutil.copyfile(src, tmp)
            os.replace(tmp, p)
            return
        import smbclient
        smbclient.makedirs(d, exist_ok=True, port=self.port)
        with open(src, "rb") as fi, smbclient.open_file(tmp, mode="wb", port=self.port) as fo:
            shutil.copyfileobj(fi, fo, 1 << 20)
        try:
            smbclient.remove(p, port=self.port)
        except Exception:
            pass
        smbclient.rename(tmp, p, port=self.port)

    def delete(self, rel):
        p = self._p(rel)
        try:
            if self.mode == "windows":
                if os.path.isfile(p):
                    os.remove(p)
            else:
                import smbclient
                smbclient.remove(p, port=self.port)
        except Exception:
            pass

    def list_dir(self, rel):
        p = self._p(rel) if rel else self.base
        if self.mode == "windows":
            return os.listdir(p) if os.path.isdir(p) else []
        import smbclient
        try:
            return smbclient.listdir(p, port=self.port)
        except Exception:
            return []

    def close(self):
        if self.mode == "smb":
            try:
                import smbclient
                smbclient.reset_connection_cache()
            except Exception:
                pass
        self.mode = None


# ---------------------------------------------------------------- chiavetta USB

def removable_drives():
    """Chiavette/dischi rimovibili collegati: lista di (percorso, nome)."""
    out = []
    if IS_WIN:
        try:
            import ctypes
            mask = ctypes.windll.kernel32.GetLogicalDrives()
            for i in range(26):
                if not mask & (1 << i):
                    continue
                root = chr(65 + i) + ":\\"
                t = ctypes.windll.kernel32.GetDriveTypeW(root)
                if t != 2 and not (t == 3 and i > 2):   # 2 = rimovibile; dischi USB esterni appaiono come 3
                    continue
                if root.upper().startswith("C"):
                    continue
                name = ctypes.create_unicode_buffer(261)
                ok = ctypes.windll.kernel32.GetVolumeInformationW(root, name, 261, None, None, None, None, 0)
                if not ok:
                    continue
                out.append((root, f"{root} {name.value}".strip()))
        except Exception:
            pass
    elif IS_MAC:
        try:
            for n in os.listdir("/Volumes"):
                p = os.path.join("/Volumes", n)
                if os.path.ismount(p) and not os.path.realpath(p) == "/":
                    out.append((p, n))
        except Exception:
            pass
    else:
        for base in ("/media", "/run/media", "/mnt"):
            try:
                for root, dirs, _ in os.walk(base):
                    for d in dirs:
                        p = os.path.join(root, d)
                        if os.path.ismount(p):
                            out.append((p, d))
                    if root.count(os.sep) > 3:
                        break
            except Exception:
                pass
    return out


def mark_usb(drive_path, cfg):
    """Scrive sulla chiavetta il file che la fa riconoscere in automatico."""
    uid = cfg.get("usb_id") or uuid.uuid4().hex[:12]
    with open(os.path.join(drive_path, USB_MARKER), "w", encoding="utf-8") as f:
        f.write(uid)
    if IS_WIN:
        try:
            import ctypes
            ctypes.windll.kernel32.SetFileAttributesW(os.path.join(drive_path, USB_MARKER), 2)  # nascosto
        except Exception:
            pass
    cfg["usb_id"] = uid
    return uid


def find_marked_usb(cfg):
    uid = cfg.get("usb_id")
    if not uid:
        return None
    for path, name in removable_drives():
        try:
            with open(os.path.join(path, USB_MARKER), "r", encoding="utf-8") as f:
                if f.read().strip() == uid:
                    return path, name
        except Exception:
            continue
    return None


# ---------------------------------------------------------------- allineamento PC -> chiavetta / NAS

class Index:
    """File gia' copiati su una destinazione: percorso -> 'dimensione:data'."""

    def __init__(self, name):
        self.file = os.path.join(APP_DIR, f"indice_{name}.json")
        try:
            with open(self.file, "r", encoding="utf-8") as f:
                self.map = json.load(f)
        except Exception:
            self.map = {}
        self.dirty = False

    def save(self):
        if not self.dirty:
            return
        tmp = self.file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.map, f)
        os.replace(tmp, self.file)
        self.dirty = False


def local_files(root):
    """Tutti i file dei backup sul PC: percorso relativo -> (percorso completo, firma)."""
    out = {}
    base = os.path.join(root, ROOT)
    if not os.path.isdir(base):
        return out
    for dirpath, dirs, files in os.walk(base):
        for n in files:
            if n.endswith(".parziale"):
                continue
            full = os.path.join(dirpath, n)
            try:
                st = os.stat(full)
            except OSError:
                continue
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            out[rel] = (full, f"{st.st_size}:{int(st.st_mtime)}", st.st_size)
    return out


def mirror(root, target, index_name, log_fn=log, stop=lambda: False, progress=lambda t: None):
    """Copia su target i file nuovi o modificati; cancella quelli tolti dal PC (es. copie vecchie dei dati).
    Restituisce (copiati, cancellati, byte)."""
    idx = Index(index_name)
    files = local_files(root)
    target.connect()
    copied = deleted = 0
    total_bytes = 0
    n = 0
    for rel, (full, sig, size) in files.items():
        if stop():
            break
        n += 1
        if idx.map.get(rel) == sig:
            continue
        if target.size(rel) == size and idx.map.get(rel) is None:
            idx.map[rel] = sig
            idx.dirty = True
            continue
        progress(f"{target.label}: {n}/{len(files)} {rel.rsplit('/', 1)[-1]}")
        target.copy(full, rel)
        idx.map[rel] = sig
        idx.dirty = True
        copied += 1
        total_bytes += size
        if copied % 50 == 0:
            idx.save()
    if not stop():
        for rel in [r for r in idx.map if r not in files]:
            target.delete(rel)
            idx.map.pop(rel, None)
            idx.dirty = True
            deleted += 1
    idx.save()
    if copied or deleted:
        log_fn(f"{target.label}: {copied} file copiati ({human_size(total_bytes)})"
               + (f", {deleted} tolti" if deleted else ""))
    return copied, deleted, total_bytes


class AutoSync:
    """Copia automatica dei backup del PC su chiavetta e NAS appena sono collegati/raggiungibili."""

    def __init__(self, cfg, server, on_status=None):
        self.cfg = cfg
        self.server = server
        self.on_status = on_status or (lambda: None)
        self.nas_state = "spento"       # "spento", "non visto", "collegato", "errore: ..."
        self.usb_state = "spenta"
        self.nas_last = 0
        self.usb_last = 0
        self.busy = ""
        self._force = threading.Event()
        self._stop = False
        self._last_nas_try = 0
        self._usb_present_before = False

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="copia").start()

    def now(self):
        self._force.set()

    def stop(self):
        self._stop = True
        self._force.set()

    def _status(self):
        try:
            self.on_status()
        except Exception:
            pass

    def _loop(self):
        while not self._stop:
            forced = self._force.wait(10)
            self._force.clear()
            if self._stop:
                return
            # non copiare mentre un telefono sta ancora inviando (si aspetta che finisca)
            if self.server and self.server.receiving() and not forced:
                continue
            dirty = self.server.dirty.is_set() if self.server else False
            try:
                self._usb(forced or dirty)
            except Exception as e:
                self.usb_state = f"errore: {e}"
                log(f"Chiavetta: {e}")
            try:
                self._nas(forced, dirty)
            except Exception as e:
                self.nas_state = f"errore: {e}"
            if dirty and self.server and not self.server.receiving():
                self.server.dirty.clear()
            self.busy = ""
            self._status()

    def _usb(self, changed):
        if not self.cfg.get("usb_enabled"):
            self.usb_state = "spenta"
            return
        found = find_marked_usb(self.cfg)
        if not found:
            self.usb_state = "non collegata"
            self._usb_present_before = False
            return
        path, name = found
        just_plugged = not self._usb_present_before
        self._usb_present_before = True
        self.usb_state = f"collegata ({name})"
        if not (changed or just_plugged):
            return
        if just_plugged:
            log(f"Chiavetta {name} collegata: copio i backup")
        self.busy = "Copia sulla chiavetta…"
        self._status()
        t = FolderTarget(os.path.join(path, ROOT), "Chiavetta " + name)
        mirror(self.cfg["backup_dir"], t, "usb_" + self.cfg.get("usb_id", ""),
               progress=lambda m: self._busy(m))
        self.usb_last = int(time.time() * 1000)

    def _busy(self, m):
        self.busy = m
        self._status()

    def _nas(self, forced, changed):
        if not self.cfg.get("nas_enabled") or not self.cfg.get("nas_path"):
            self.nas_state = "spento"
            return
        try:
            server, port, _ = parse_nas_path(self.cfg["nas_path"])
        except ValueError as e:
            self.nas_state = f"errore: {e}"
            return
        now = time.time()
        # controllo veloce ogni minuto: appena il NAS risponde si copia
        if not forced and not changed and now - self._last_nas_try < 60 and self.nas_state != "non visto":
            return
        self._last_nas_try = now
        if not port_open(server, port, 2):
            if self.nas_state not in ("non visto",):
                log(f"NAS {server} non raggiungibile: copio appena torna disponibile")
            self.nas_state = "non visto"
            return
        was_down = self.nas_state != "collegato"
        if not (forced or changed or was_down or now - self.nas_last / 1000 > 1800):
            return
        if was_down:
            log(f"NAS {server} raggiungibile: allineo i backup")
        self.busy = "Copia sul NAS…"
        self._status()
        t = NasTarget(self.cfg, get_nas_password())
        try:
            mirror(self.cfg["backup_dir"], t, "nas_" + re.sub(r"[^A-Za-z0-9]", "_", self.cfg["nas_path"])[:60],
                   progress=lambda m: self._busy(m))
            self.nas_state = "collegato"
            self.nas_last = int(time.time() * 1000)
        except Exception as e:
            self.nas_state = f"errore: {friendly_nas_error(server, port, e)}"
            log(f"NAS: {self.nas_state}")
        finally:
            t.close()


# ---------------------------------------------------------------- elenco dei backup sul PC

def list_backups(root):
    """Telefoni e copie presenti sul PC."""
    base = os.path.join(root, ROOT)
    phones = []
    if not os.path.isdir(base):
        return phones
    for ph in sorted(os.listdir(base)):
        pdir = os.path.join(base, ph)
        if not os.path.isdir(pdir):
            continue
        snaps = []
        ddir = os.path.join(pdir, "Dati")
        if os.path.isdir(ddir):
            for s in sorted(os.listdir(ddir), reverse=True):
                sd = os.path.join(ddir, s)
                if not os.path.isdir(sd):
                    continue
                info = {}
                try:
                    with open(os.path.join(sd, "manifest.json"), "r", encoding="utf-8") as f:
                        info = json.load(f)
                except Exception:
                    pass
                snaps.append({"name": s, "date": stamp_to_human(s), "complete": bool(info.get("completo")),
                              "counts": info.get("conteggi", {}), "path": sd})
        phones.append({"name": ph, "path": pdir, "snapshots": snaps,
                       "archive": os.path.join(pdir, "Archivio"), "apps": os.path.join(pdir, "App")})
    return phones


def folder_size(path):
    total = files = 0
    for dp, _, fs in os.walk(path):
        for n in fs:
            try:
                total += os.path.getsize(os.path.join(dp, n))
                files += 1
            except OSError:
                pass
    return total, files


def copy_tree(src, dst, progress=lambda t: None, stop=lambda: False):
    """Copia una cartella (solo i file mancanti o diversi). Restituisce il numero di file copiati."""
    n = 0
    for dp, _, fs in os.walk(src):
        rel = os.path.relpath(dp, src)
        out = os.path.join(dst, rel) if rel != "." else dst
        os.makedirs(out, exist_ok=True)
        for f in fs:
            if stop():
                return n
            s = os.path.join(dp, f)
            d = os.path.join(out, f)
            if os.path.isfile(d) and os.path.getsize(d) == os.path.getsize(s):
                continue
            progress(f)
            shutil.copyfile(s, d + ".parziale")
            os.replace(d + ".parziale", d)
            n += 1
    return n


def open_folder(path):
    import subprocess
    os.makedirs(path, exist_ok=True)
    if IS_WIN:
        os.startfile(path)  # noqa
    elif IS_MAC:
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


# ---------------------------------------------------------------- avvio automatico

def set_autostart(on):
    exe = sys.executable if getattr(sys, "frozen", False) else None
    try:
        if IS_WIN:
            import winreg
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                               winreg.KEY_SET_VALUE)
            if on:
                cmd = f'"{exe}" --nascosto' if exe else f'"{sys.executable}" "{os.path.abspath(sys.argv[0])}" --nascosto'
                winreg.SetValueEx(k, "RedmiManager", 0, winreg.REG_SZ, cmd)
            else:
                try:
                    winreg.DeleteValue(k, "RedmiManager")
                except FileNotFoundError:
                    pass
            winreg.CloseKey(k)
            return True
        if IS_MAC:
            plist = os.path.expanduser("~/Library/LaunchAgents/it.sirjoe.redmimanager.plist")
            if on:
                args = [exe, "--nascosto"] if exe else [sys.executable, os.path.abspath(sys.argv[0]), "--nascosto"]
                items = "".join(f"<string>{a}</string>" for a in args)
                os.makedirs(os.path.dirname(plist), exist_ok=True)
                with open(plist, "w", encoding="utf-8") as f:
                    f.write('<?xml version="1.0" encoding="UTF-8"?>\n'
                            '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
                            '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
                            '<plist version="1.0"><dict><key>Label</key><string>it.sirjoe.redmimanager</string>'
                            f'<key>ProgramArguments</key><array>{items}</array>'
                            '<key>RunAtLoad</key><true/></dict></plist>\n')
            elif os.path.exists(plist):
                os.remove(plist)
            return True
    except Exception as e:
        log(f"Avvio automatico non impostato: {e}")
    return False


def system_info():
    return f"{platform.system()} {platform.release()}"
