"""
Redmi Manager per PC (Windows e Mac).
Riceve i backup dei telefoni nella rete di casa e li copia su chiavetta USB e NAS.
"""
import os
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

import rm_core as core

FONT = ("Segoe UI", 11) if core.IS_WIN else ("Helvetica", 13)
FONT_B = (FONT[0], FONT[1], "bold")
FONT_BIG = (FONT[0], FONT[1] + 14, "bold")
FONT_T = (FONT[0], FONT[1] + 4, "bold")
ORANGE = "#E8590C"


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


class App:
    def __init__(self, root, hidden=False):
        self.root = root
        self.cfg = core.load_config()
        core.save_config(self.cfg)
        root.title(f"{core.APP_NAME} {core.APP_VERSION}")
        root.geometry("1060x740")
        root.minsize(820, 600)
        try:
            root.iconphoto(True, tk.PhotoImage(file=resource("icona.png")))
        except Exception:
            pass
        style = ttk.Style()
        try:
            if core.IS_WIN:
                style.theme_use("vista")
        except Exception:
            pass
        style.configure("TNotebook.Tab", font=FONT_B, padding=(14, 6))
        style.configure("TButton", font=FONT, padding=(10, 6))
        style.configure("Big.TButton", font=FONT_B, padding=(14, 10))
        style.configure("TLabel", font=FONT)
        style.configure("TCheckbutton", font=FONT)
        style.configure("Treeview", font=FONT, rowheight=28)
        style.configure("Treeview.Heading", font=FONT_B)

        self.server = None
        self.sync = None
        self.tray = None
        self._ui_queue = []

        if self.cfg.get("disclaimer", 0) < core.DISCLAIMER_VERSION:
            if not self.disclaimer(first=True):
                root.destroy()
                return

        self.build()
        self.start_services()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        core._log_listeners.append(lambda line: self.call(lambda: self.add_log(line)))
        self.root.after(500, self.pump)
        self.root.after(1000, self.refresh_loop)
        if hidden:
            self.root.after(200, self.hide)

    # ------------------------------------------------------------------ utilita'

    def call(self, fn):
        """Esegue fn nel thread dell'interfaccia."""
        self._ui_queue.append(fn)

    def pump(self):
        q, self._ui_queue = self._ui_queue, []
        for fn in q:
            try:
                fn()
            except Exception:
                pass
        self.root.after(200, self.pump)

    def bg(self, work, done=None):
        def run():
            try:
                r = work()
                err = None
            except Exception as e:
                r, err = None, e
            if done:
                self.call(lambda: done(r, err))
        threading.Thread(target=run, daemon=True).start()

    # ------------------------------------------------------------------ avvertenze

    def disclaimer(self, first):
        try:
            with open(resource("LEGGIMI_AVVERTENZE.txt"), "r", encoding="utf-8") as f:
                text = f.read()
        except Exception:
            text = "Avvertenze non trovate."
        win = tk.Toplevel(self.root)
        win.title("Avvertenze – Redmi Manager")
        try:
            win.iconphoto(False, tk.PhotoImage(file=resource("icona.png")))
        except Exception:
            pass
        win.geometry("760x600")
        result = {"ok": not first}
        t = scrolledtext.ScrolledText(win, wrap="word", font=(FONT[0], FONT[1] - 1))
        t.insert("1.0", text)
        t.configure(state="disabled")
        bar = ttk.Frame(win)
        bar.pack(side="bottom", fill="x", padx=10, pady=(0, 10))
        t.pack(fill="both", expand=True, padx=10, pady=10)

        def accept():
            result["ok"] = True
            self.cfg["disclaimer"] = core.DISCLAIMER_VERSION
            core.save_config(self.cfg)
            win.destroy()

        if first:
            ttk.Button(bar, text="Accetto", style="Big.TButton", command=accept).pack(side="right")
            ttk.Button(bar, text="Non accetto", command=win.destroy).pack(side="right", padx=8)
        else:
            ttk.Button(bar, text="Chiudi", command=win.destroy).pack(side="right")
        if first:
            # La finestra principale e' nascosta: le avvertenze devono essere una finestra
            # indipendente (non "transient"), altrimenti su Windows restano invisibili.
            self.root.withdraw()
            win.protocol("WM_DELETE_WINDOW", win.destroy)
            win.update_idletasks()
            x = max(0, (win.winfo_screenwidth() - 760) // 2)
            y = max(0, (win.winfo_screenheight() - 600) // 3)
            win.geometry(f"760x600+{x}+{y}")
            win.deiconify()
            win.lift()
            win.attributes("-topmost", True)
            win.after(1500, lambda: win.attributes("-topmost", False))
            win.focus_force()
            self.root.wait_window(win)
            if result["ok"]:
                self.root.deiconify()
                self.root.lift()
                self.root.focus_force()
        else:
            win.transient(self.root)
            win.grab_set()
        return result["ok"]

    # ------------------------------------------------------------------ interfaccia

    def build(self):
        head = tk.Frame(self.root, bg=ORANGE)
        head.pack(fill="x")
        tk.Label(head, text="  Redmi Manager", bg=ORANGE, fg="white", font=FONT_T).pack(side="left", pady=8)
        self.head_status = tk.Label(head, text="", bg=ORANGE, fg="white", font=FONT)
        self.head_status.pack(side="right", padx=12)

        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.nb = nb
        self.tab_phones(nb)
        self.tab_dest(nb)
        self.tab_saved(nb)
        self.tab_restore(nb)
        self.tab_log(nb)

        self.status = ttk.Label(self.root, text="", anchor="w")
        self.status.pack(fill="x", padx=10, pady=(0, 6))

    # --- Telefoni
    def tab_phones(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Telefoni")
        left = ttk.Frame(f)
        left.pack(side="left", fill="y", padx=(0, 16))
        ttk.Label(left, text="Collega un telefono", font=FONT_T).pack(anchor="w")
        ttk.Label(left, text="Nell'app Redmi Manager del telefono:\nImpostazioni › PC di casa ›\n\"Collega con il codice QR\"",
                  justify="left").pack(anchor="w", pady=(4, 8))
        self.qr_label = tk.Label(left, bg="white")
        self.qr_label.pack(pady=4)
        ttk.Label(left, text="oppure inserisci il codice:").pack(anchor="w", pady=(8, 0))
        self.code_label = tk.Label(left, text="", font=FONT_BIG, fg=ORANGE)
        self.code_label.pack(anchor="w")
        ttk.Button(left, text="Nuovo codice", command=self.new_code).pack(anchor="w", pady=6)
        self.ip_label = ttk.Label(left, text="", justify="left")
        self.ip_label.pack(anchor="w", pady=(8, 0))

        right = ttk.Frame(f)
        right.pack(side="left", fill="both", expand=True)
        ttk.Label(right, text="Telefoni collegati", font=FONT_T).pack(anchor="w")
        cols = ("modello", "batteria", "memoria", "visto", "backup")
        tv = ttk.Treeview(right, columns=cols, show="tree headings", height=10)
        tv.heading("#0", text="Telefono")
        tv.column("#0", width=170)
        for c, t, w in zip(cols, ("Modello", "Batteria", "Memoria libera", "Ultimo contatto", "Ultimo backup"),
                           (170, 80, 140, 140, 140)):
            tv.heading(c, text=t)
            tv.column(c, width=w, anchor="w")
        tv.pack(fill="both", expand=True, pady=6)
        self.phones_tv = tv
        bar = ttk.Frame(right)
        bar.pack(fill="x")
        ttk.Button(bar, text="Scollega il telefono scelto", command=self.unpair).pack(side="left")
        ttk.Label(right, text="Il telefono invia i backup a questo PC quando è sulla rete di casa e il programma è aperto\n"
                              "(anche ridotto a icona). Puoi farlo partire da solo all'accensione del PC nella scheda \"Dove salvare\".",
                  justify="left", wraplength=600).pack(anchor="w", pady=10)

    # --- Dove salvare
    def tab_dest(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Dove salvare")

        ttk.Label(f, text="Cartella del PC", font=FONT_T).grid(row=0, column=0, columnspan=3, sticky="w")
        self.dir_var = tk.StringVar(value=self.cfg["backup_dir"])
        ttk.Entry(f, textvariable=self.dir_var, width=70, state="readonly").grid(row=1, column=0, sticky="we", pady=4)
        ttk.Button(f, text="Cambia…", command=self.choose_dir).grid(row=1, column=1, padx=4)
        ttk.Button(f, text="Apri", command=lambda: core.open_folder(self.cfg["backup_dir"])).grid(row=1, column=2)

        ttk.Separator(f).grid(row=2, column=0, columnspan=3, sticky="we", pady=12)
        ttk.Label(f, text="Chiavetta USB", font=FONT_T).grid(row=3, column=0, columnspan=3, sticky="w")
        self.usb_on = tk.BooleanVar(value=self.cfg.get("usb_enabled"))
        ttk.Checkbutton(f, text="Copia i backup sulla chiavetta appena la colleghi", variable=self.usb_on,
                        command=self.save_usb).grid(row=4, column=0, sticky="w")
        self.usb_combo = ttk.Combobox(f, state="readonly", width=40)
        self.usb_combo.grid(row=5, column=0, sticky="w", pady=4)
        ttk.Button(f, text="Aggiorna elenco", command=self.load_drives).grid(row=5, column=1, padx=4)
        ttk.Button(f, text="Usa questa chiavetta", command=self.choose_usb).grid(row=5, column=2)
        self.usb_status = ttk.Label(f, text="")
        self.usb_status.grid(row=6, column=0, columnspan=3, sticky="w")

        ttk.Separator(f).grid(row=7, column=0, columnspan=3, sticky="we", pady=12)
        ttk.Label(f, text="NAS di casa", font=FONT_T).grid(row=8, column=0, columnspan=3, sticky="w")
        self.nas_on = tk.BooleanVar(value=self.cfg.get("nas_enabled"))
        ttk.Checkbutton(f, text="Copia i backup sul NAS (in automatico appena è acceso)", variable=self.nas_on).grid(
            row=9, column=0, sticky="w")
        g = ttk.Frame(f)
        g.grid(row=10, column=0, columnspan=3, sticky="we", pady=4)
        self.nas_path = tk.StringVar(value=self.cfg.get("nas_path", ""))
        self.nas_user = tk.StringVar(value=self.cfg.get("nas_user", ""))
        self.nas_pass = tk.StringVar(value=core.get_nas_password())
        self.nas_dom = tk.StringVar(value=self.cfg.get("nas_domain", ""))
        for i, (lbl, var, show) in enumerate((("Percorso (es. \\\\192.168.1.198\\FRITZ.NAS\\Lavori_Condominio)", self.nas_path, ""),
                                               ("Utente", self.nas_user, ""), ("Password", self.nas_pass, "•"),
                                               ("Dominio (di solito vuoto)", self.nas_dom, ""))):
            ttk.Label(g, text=lbl).grid(row=i, column=0, sticky="w")
            ttk.Entry(g, textvariable=var, width=50, show=show).grid(row=i, column=1, sticky="w", padx=6, pady=2)
        b = ttk.Frame(f)
        b.grid(row=11, column=0, columnspan=3, sticky="w", pady=4)
        ttk.Button(b, text="Scegli la cartella del NAS…", command=self.browse_nas).pack(side="left", padx=(0, 6))
        ttk.Button(b, text="Salva e prova", command=self.test_nas).pack(side="left")
        ttk.Button(b, text="Cerca il NAS in rete", command=self.find_nas).pack(side="left", padx=6)
        ttk.Button(b, text="Copia adesso su chiavetta e NAS", command=lambda: self.sync and self.sync.now()).pack(side="left")
        self.nas_status = ttk.Label(f, text="", wraplength=860)
        self.nas_status.grid(row=12, column=0, columnspan=3, sticky="w")

        ttk.Separator(f).grid(row=13, column=0, columnspan=3, sticky="we", pady=12)
        self.auto_var = tk.BooleanVar(value=self.cfg.get("autostart"))
        ttk.Checkbutton(f, text="Avvia Redmi Manager all'accensione del computer (ridotto a icona)",
                        variable=self.auto_var, command=self.save_autostart).grid(row=14, column=0, sticky="w")
        f.columnconfigure(0, weight=1)
        self.load_drives()

    # --- Backup salvati
    def tab_saved(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Backup salvati")
        ttk.Label(f, text="Backup presenti su questo PC", font=FONT_T).pack(anchor="w")
        cols = ("data", "contenuto", "stato")
        tv = ttk.Treeview(f, columns=cols, show="tree headings")
        tv.heading("#0", text="Telefono / copia")
        tv.column("#0", width=260)
        for c, t, w in zip(cols, ("Data", "Contenuto", "Stato"), (150, 380, 120)):
            tv.heading(c, text=t)
            tv.column(c, width=w, anchor="w")
        tv.pack(fill="both", expand=True, pady=6)
        self.saved_tv = tv
        bar = ttk.Frame(f)
        bar.pack(fill="x")
        ttk.Button(bar, text="Aggiorna", command=self.load_saved).pack(side="left")
        ttk.Button(bar, text="Apri cartella", command=self.open_saved).pack(side="left", padx=6)
        ttk.Button(bar, text="Cancella la copia scelta", command=self.delete_saved).pack(side="left")
        ttk.Button(bar, text="Copia il telefono su chiavetta/cartella…", command=self.export_saved).pack(side="left", padx=6)
        self.saved_info = ttk.Label(f, text="", wraplength=900, justify="left")
        self.saved_info.pack(anchor="w", pady=6)

    # --- Ripristino
    def tab_restore(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Ripristino")
        ttk.Label(f, text="Come ripristinare un telefono", font=FONT_T).pack(anchor="w")
        ttk.Label(f, justify="left", wraplength=900, text=(
            "1. Installa l'app Redmi Manager sul telefono (anche nuovo) e concedi i permessi.\n"
            "2. Collegalo a questo PC con il codice QR (scheda \"Telefoni\"), oppure imposta il NAS o la chiavetta.\n"
            "3. Nell'app premi \"Ripristina un backup\", scegli questo PC, il telefono di origine e la data.\n"
            "4. Scegli cosa rimettere: rubrica, SMS, chiamate, calendario, app, foto e file.\n\n"
            "Il PC, la chiavetta e il NAS contengono gli stessi backup: puoi ripristinare da quello che hai sotto mano.")).pack(anchor="w", pady=6)
        ttk.Separator(f).pack(fill="x", pady=10)
        ttk.Label(f, text="Backup di fabbrica (dati delle app)", font=FONT_T).pack(anchor="w")
        row = ttk.Frame(f)
        row.pack(anchor="w", pady=4)
        ttk.Label(row, text="Marca del telefono:").pack(side="left")
        names = [b[1] for b in core.BRANDS]
        self.brand_combo = ttk.Combobox(row, state="readonly", values=names, width=32)
        ids = [b[0] for b in core.BRANDS]
        cur = self.cfg.get("brand", core.DEFAULT_BRAND)
        self.brand_combo.current(ids.index(cur) if cur in ids else 0)
        self.brand_combo.pack(side="left", padx=6)
        self.brand_combo.bind("<<ComboboxSelected>>", lambda e: self.show_brand())
        self.brand_text = ttk.Label(f, justify="left", wraplength=900)
        self.brand_text.pack(anchor="w", pady=6)
        self.show_brand()
        ttk.Separator(f).pack(fill="x", pady=10)
        ttk.Button(f, text="Avvertenze e informazioni", command=lambda: self.disclaimer(first=False)).pack(anchor="w")

    def tab_log(self, nb):
        f = ttk.Frame(nb, padding=12)
        nb.add(f, text="Registro")
        self.log_text = scrolledtext.ScrolledText(f, wrap="word", font=("Consolas", 10) if core.IS_WIN else ("Menlo", 11))
        self.log_text.pack(fill="both", expand=True)
        self.log_text.insert("end", core.read_log())
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def add_log(self, line):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", line + "\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    # ------------------------------------------------------------------ servizi

    def start_services(self):
        self.server = core.PhoneServer(self.cfg, on_change=lambda what: self.call(lambda: self.on_server_change(what)))
        try:
            self.server.start()
        except OSError as e:
            messagebox.showerror("Redmi Manager",
                                 f"Non riesco ad aprire la porta {self.cfg.get('port')} ({e}).\n"
                                 "Forse Redmi Manager è già aperto: chiudi l'altra finestra.")
        self.sync = core.AutoSync(self.cfg, self.server, on_status=lambda: self.call(self.update_status))
        self.sync.start()
        self.show_code()
        self.load_phones()
        self.load_saved()
        if self.cfg.get("keep_running", True) and core.IS_WIN:
            self.start_tray()

    def on_server_change(self, what):
        if what == "show":
            self.show()
        elif what == "code":
            self.show_code()
        elif what == "phones":
            self.load_phones()

    def refresh_loop(self):
        self.update_status()
        if int(time.time()) % 30 == 0:
            self.load_phones()
        if self.server and self.server.code and not self.server.code_valid():
            self.new_code()
        self.root.after(1000, self.refresh_loop)

    def update_status(self):
        if not self.sync:
            return
        rec = self.server.receiving() if self.server else []
        parts = []
        if rec:
            parts.append("Ricevo il backup da: " + ", ".join(rec))
        if self.sync.busy:
            parts.append(self.sync.busy)
        self.head_status.configure(text="  •  ".join(parts) if parts else "Pronto: in attesa dei telefoni")
        self.usb_status.configure(text="Stato: " + self.sync.usb_state +
                                  (f" – ultima copia {core.human_time(self.sync.usb_last)}" if self.sync.usb_last else "")
                                  + (f"  (chiavetta scelta: {self.cfg.get('usb_label')})" if self.cfg.get("usb_label") else ""))
        self.nas_status.configure(text="Stato: " + self.sync.nas_state +
                                  (f" – ultima copia {core.human_time(self.sync.nas_last)}" if self.sync.nas_last else ""))
        ips = ", ".join(core.local_ips())
        self.status.configure(text=f"Indirizzo di questo PC: {ips}  –  porta {self.cfg.get('port')}  –  backup in {self.cfg['backup_dir']}")

    # ------------------------------------------------------------------ telefoni

    def show_code(self):
        if not self.server:
            return
        self.code_label.configure(text=" ".join([self.server.code[:3], self.server.code[3:]]))
        self.ip_label.configure(text=f"PC: {self.cfg['pc_name']}\nIndirizzo: {', '.join(core.local_ips())}\nIl codice vale 15 minuti.")
        try:
            import qrcode
            from PIL import ImageTk
            q = qrcode.QRCode(box_size=6, border=2)
            q.add_data(self.server.qr_text())
            q.make(fit=True)
            img = q.make_image(fill_color="black", back_color="white").convert("RGB").resize((210, 210))
            self._qr = ImageTk.PhotoImage(img)
            self.qr_label.configure(image=self._qr, text="")
        except Exception as e:
            self.qr_label.configure(text=f"(codice QR non disponibile: {e})", image="")

    def new_code(self):
        if self.server:
            self.server.new_code()
            self.show_code()

    def load_phones(self):
        tv = self.phones_tv
        tv.delete(*tv.get_children())
        tokens = self.cfg.get("tokens", {})
        phones = self.cfg.get("phones", {})
        names = {v.get("device") for v in tokens.values()}
        for name in sorted(names | set(phones)):
            if name not in names:
                continue
            p = phones.get(name, {})
            bat = p.get("battery")
            batt = (f"{bat}%" + (" ⚡" if p.get("charging") else "")) if isinstance(bat, int) and bat >= 0 else ""
            mem = f"{core.human_size(p.get('free'))} su {core.human_size(p.get('total'))}" if p.get("total") else ""
            tv.insert("", "end", iid=name, text=name, values=(p.get("model", ""), batt, mem,
                                                               core.human_time(p.get("seen")), core.human_time(p.get("last_backup"))))

    def unpair(self):
        sel = self.phones_tv.selection()
        if not sel:
            return
        name = sel[0]
        if not messagebox.askyesno("Scollega", f"Scollegare {name}? Non potrà più inviare backup a questo PC finché non lo ricolleghi.\n"
                                                "I backup già salvati restano."):
            return
        for tok, v in list(self.cfg.get("tokens", {}).items()):
            if v.get("device") == name:
                self.server.revoke(tok)
        self.load_phones()

    # ------------------------------------------------------------------ dove salvare

    def choose_dir(self):
        d = filedialog.askdirectory(title="Cartella dove salvare i backup", initialdir=self.cfg["backup_dir"])
        if not d:
            return
        d = os.path.abspath(d)
        if d.replace("/", "\\").startswith("\\\\"):
            if not messagebox.askyesno("Cartella del PC",
                                       "Hai scelto una cartella di rete (NAS) come cartella del PC.\n\n"
                                       "Va bene se vuoi che i backup ricevuti vadano direttamente sul NAS, ma se il NAS è "
                                       "spento i telefoni non potranno salvare sul PC.\n\nConsiglio: lascia una cartella del PC "
                                       "e indica il NAS più sotto, nella sezione \"NAS di casa\".\n\nUsare comunque questa cartella?"):
                return
        self.cfg["backup_dir"] = d
        core.save_config(self.cfg)
        self.dir_var.set(self.cfg["backup_dir"])
        self.load_saved()

    def load_drives(self):
        self.drives = core.removable_drives()
        self.usb_combo["values"] = [n for _, n in self.drives]
        if self.drives:
            self.usb_combo.current(0)
        else:
            self.usb_combo.set("Nessuna chiavetta trovata")

    def choose_usb(self):
        i = self.usb_combo.current()
        if i < 0 or i >= len(self.drives):
            messagebox.showinfo("Chiavetta", "Collega la chiavetta e premi \"Aggiorna elenco\".")
            return
        path, name = self.drives[i]
        try:
            core.mark_usb(path, self.cfg)
        except Exception as e:
            messagebox.showerror("Chiavetta", f"Non riesco a scrivere sulla chiavetta: {e}")
            return
        self.cfg["usb_label"] = name
        self.cfg["usb_enabled"] = True
        self.usb_on.set(True)
        core.save_config(self.cfg)
        messagebox.showinfo("Chiavetta", f"Da ora i backup verranno copiati su {name} ogni volta che la colleghi.")
        self.sync.now()

    def save_usb(self):
        self.cfg["usb_enabled"] = bool(self.usb_on.get())
        core.save_config(self.cfg)
        self.sync.now()

    def save_nas(self):
        self.cfg["nas_enabled"] = bool(self.nas_on.get())
        path = core.fritz_web_to_smb(self.nas_path.get().strip())
        self.nas_path.set(path)
        self.cfg["nas_path"] = path
        self.cfg["nas_user"] = self.nas_user.get().strip()
        self.cfg["nas_domain"] = self.nas_dom.get().strip()
        core.set_nas_password(self.nas_pass.get())
        core.save_config(self.cfg)

    def test_nas(self):
        self.save_nas()
        if not self.cfg["nas_path"]:
            messagebox.showinfo("NAS", "Scrivi il percorso del NAS o premi \"Cerca il NAS in rete\".")
            return
        self.nas_status.configure(text="Provo a collegarmi al NAS…")

        def work():
            t = core.NasTarget(self.cfg, core.get_nas_password())
            try:
                t.connect()
                return t.base
            finally:
                t.close()

        def done(r, err):
            if err:
                messagebox.showerror("NAS non raggiungibile", str(err))
                self.nas_status.configure(text=f"Errore: {err}")
            else:
                if not self.cfg.get("nas_enabled"):
                    self.nas_on.set(True)
                    self.save_nas()
                messagebox.showinfo("NAS collegato", f"Il NAS risponde. I backup verranno copiati in:\n{r}")
                self.sync.now()
        self.bg(work, done)

    def browse_nas(self):
        """Scelta della cartella del NAS con la finestra di Windows/Mac (come in Esplora file)."""
        start = ""
        try:
            start = core.parse_nas_path(self.nas_path.get())[2]
        except Exception:
            start = "\\\\192.168.1.198\\fritz.nas" if core.IS_WIN else ""
        d = filedialog.askdirectory(title="Scegli la cartella del NAS dove salvare i backup", initialdir=start or None)
        if not d:
            return
        d = d.replace("/", "\\")
        if not d.startswith("\\\\"):
            messagebox.showinfo("NAS", "Scegli una cartella di rete (deve iniziare con \\\\, es. \\\\192.168.1.198\\fritz.nas\\...).\n"
                                       "Se il NAS è collegato come lettera (es. Z:), usa il percorso di rete.")
            return
        self.nas_path.set(d)
        self.test_nas()

    def find_nas(self):
        self.nas_status.configure(text="Cerco i NAS nella rete di casa… (circa 15 secondi)")

        def done(found, err):
            if not found:
                self.nas_status.configure(text="Nessun NAS trovato. Controlla che sia acceso e che la condivisione file (SMB) sia attiva.")
                return
            win = tk.Toplevel(self.root)
            win.title("NAS trovati")
            ttk.Label(win, text="Scegli il NAS:", font=FONT_B).pack(anchor="w", padx=10, pady=6)
            lb = tk.Listbox(win, font=FONT, width=50, height=min(10, len(found)))
            for h, n in found:
                lb.insert("end", f"{h}  {('(' + n + ')') if n else ''}")
            lb.pack(padx=10, pady=4)

            def use():
                s = lb.curselection()
                if s:
                    h = found[s[0]][0]
                    self.nas_path.set(f"\\\\{h}\\")
                    self.nas_status.configure(text=f"Completa il percorso con il nome della cartella condivisa "
                                                   f"(per il FRITZ!Box: \\\\{h}\\FRITZ.NAS) e premi \"Salva e prova\".")
                win.destroy()
            ttk.Button(win, text="Usa questo", command=use).pack(pady=8)
            self.nas_status.configure(text=f"Trovati {len(found)} dispositivi.")
        self.bg(core.scan_nas, done)

    def save_autostart(self):
        on = bool(self.auto_var.get())
        if core.set_autostart(on):
            self.cfg["autostart"] = on
            core.save_config(self.cfg)
        else:
            self.auto_var.set(not on)
            messagebox.showwarning("Avvio automatico", "Non è stato possibile impostare l'avvio automatico.")

    # ------------------------------------------------------------------ backup salvati

    def load_saved(self):
        tv = self.saved_tv
        tv.delete(*tv.get_children())
        self.saved = core.list_backups(self.cfg["backup_dir"])
        for p in self.saved:
            last = p["snapshots"][0]["date"] if p["snapshots"] else ""
            tv.insert("", "end", iid="P|" + p["name"], text=p["name"], open=True,
                      values=(last, f"{len(p['snapshots'])} copie dei dati + archivio file e app", ""))
            for s in p["snapshots"]:
                c = s["counts"] or {}
                what = ", ".join(f"{v} {k}" for k, v in c.items())
                tv.insert("P|" + p["name"], "end", iid="S|" + p["name"] + "|" + s["name"], text=s["name"],
                          values=(s["date"], what, "completa" if s["complete"] else "incompleta"))
        self.saved_info.configure(text="Calcolo lo spazio occupato…")

        def work():
            tot, n = core.folder_size(os.path.join(self.cfg["backup_dir"], core.ROOT))
            return tot, n
        self.bg(work, lambda r, e: self.saved_info.configure(
            text=f"Spazio occupato: {core.human_size(r[0])} in {r[1]} file. Le copie vecchie dei dati vengono cancellate "
                 f"dal telefono secondo l'impostazione \"copie da conservare\"." if r else ""))

    def _selected_saved(self):
        sel = self.saved_tv.selection()
        if not sel:
            return None, None
        parts = sel[0].split("|")
        phone = next((p for p in self.saved if p["name"] == parts[1]), None)
        snap = None
        if phone and parts[0] == "S":
            snap = next((s for s in phone["snapshots"] if s["name"] == parts[2]), None)
        return phone, snap

    def open_saved(self):
        phone, snap = self._selected_saved()
        if snap:
            core.open_folder(snap["path"])
        elif phone:
            core.open_folder(phone["path"])
        else:
            core.open_folder(os.path.join(self.cfg["backup_dir"], core.ROOT))

    def delete_saved(self):
        phone, snap = self._selected_saved()
        if not snap:
            messagebox.showinfo("Cancella", "Scegli una copia (una data) da cancellare.")
            return
        if not messagebox.askyesno("Cancella", f"Cancellare la copia del {snap['date']} di {phone['name']}?\n"
                                               "Verrà tolta anche dalla chiavetta e dal NAS alla prossima copia."):
            return
        import shutil
        shutil.rmtree(snap["path"], ignore_errors=True)
        core.log(f"Cancellata la copia {snap['name']} di {phone['name']}")
        self.server.dirty.set()
        self.load_saved()

    def export_saved(self):
        phone, _ = self._selected_saved()
        if not phone:
            messagebox.showinfo("Copia", "Scegli un telefono.")
            return
        d = filedialog.askdirectory(title="Dove copiare il backup (es. una chiavetta)")
        if not d:
            return
        dst = os.path.join(d, core.ROOT, phone["name"])
        self.saved_info.configure(text="Copia in corso…")

        def work():
            return core.copy_tree(phone["path"], dst, progress=lambda f: self.call(
                lambda: self.saved_info.configure(text=f"Copio {f}…")))
        self.bg(work, lambda n, e: self.saved_info.configure(
            text=f"Errore: {e}" if e else f"Copia completata: {n} file copiati in {dst}. "
                                          f"Il telefono può ripristinare da questa cartella (Impostazioni › Chiavetta)."))

    def show_brand(self):
        b = core.BRANDS[max(0, self.brand_combo.current())]
        self.cfg["brand"] = b[0]
        core.save_config(self.cfg)
        self.brand_text.configure(text=f"Come fare il backup di fabbrica:\n{b[2]}\n\nCome ripristinarlo:\n{b[3]}")

    # ------------------------------------------------------------------ chiusura e icona

    def start_tray(self):
        try:
            import pystray
            from PIL import Image
            try:
                img = Image.open(resource("icona.png"))
            except Exception:
                img = Image.new("RGB", (64, 64), ORANGE)
            menu = pystray.Menu(
                pystray.MenuItem("Apri Redmi Manager", lambda: self.call(self.show), default=True),
                pystray.MenuItem("Copia adesso su chiavetta e NAS", lambda: self.sync.now()),
                pystray.MenuItem("Esci", lambda: self.call(self.quit)))
            self.tray = pystray.Icon("RedmiManager", img, "Redmi Manager", menu)
            threading.Thread(target=self.tray.run, daemon=True).start()
        except Exception as e:
            core.log(f"Icona vicino all'orologio non disponibile: {e}")
            self.tray = None

    def hide(self):
        if self.tray:
            self.root.withdraw()
        else:
            self.root.iconify()

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def on_close(self):
        if messagebox.askyesno("Redmi Manager",
                               "Vuoi lasciare Redmi Manager attivo in background?\n\n"
                               "Sì = continua a ricevere i backup dei telefoni e a copiarli su chiavetta e NAS\n"
                               "No = chiudi il programma"):
            self.hide()
        else:
            self.quit()

    def quit(self):
        try:
            if self.sync:
                self.sync.stop()
            if self.server:
                self.server.stop()
            if self.tray:
                self.tray.stop()
        except Exception:
            pass
        self.root.destroy()


def crash_report(exc_text):
    """Salva l'errore in un file e lo mostra, cosi' si capisce perche' il programma non parte."""
    path = os.path.join(core.APP_DIR, "errore_avvio.txt")
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"{core.APP_NAME} {core.APP_VERSION} – {core.system_info()}\n\n{exc_text}")
    except Exception:
        pass
    core.log("ERRORE: " + exc_text.strip().splitlines()[-1] if exc_text.strip() else "ERRORE")
    msg = f"Redmi Manager non riesce ad avviarsi.\n\nL'errore è salvato in:\n{path}\n\n{exc_text[-900:]}"
    if "--prova" in sys.argv:
        return
    try:
        if core.IS_WIN:
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, msg, "Redmi Manager – errore", 0x10)
        else:
            messagebox.showerror("Redmi Manager – errore", msg)
    except Exception:
        pass


def already_running():
    """Se Redmi Manager e' gia' aperto gli chiede di mostrarsi e restituisce True."""
    import urllib.request
    port = int(core.load_config().get("port", core.PORT))
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/mostra", timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


def main():
    import traceback
    hidden = "--nascosto" in sys.argv
    test = "--prova" in sys.argv
    if not test and already_running():
        return
    try:
        root = tk.Tk()
        root.report_callback_exception = lambda *a: core.log("Errore: " + "".join(traceback.format_exception(*a)))
        if test:
            # prova automatica: avvia tutto, attende qualche secondo e chiude
            cfg = core.load_config()
            cfg["disclaimer"] = core.DISCLAIMER_VERSION
            core.save_config(cfg)
            app = App(root)
            def done():
                with open(os.path.join(core.APP_DIR, "prova_ok.txt"), "w", encoding="utf-8") as f:
                    f.write("OK " + ", ".join(core.local_ips()))
                app.quit()
            root.after(6000, done)
        else:
            App(root, hidden=hidden)
        if root.winfo_exists():
            root.mainloop()
    except (KeyboardInterrupt, tk.TclError) as e:
        if test:
            crash_report(traceback.format_exc())
            sys.exit(1)
    except Exception:
        crash_report(traceback.format_exc())
        sys.exit(1)


if __name__ == "__main__":
    main()
