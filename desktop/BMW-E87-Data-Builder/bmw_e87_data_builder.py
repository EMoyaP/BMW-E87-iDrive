"""Portable Windows GUI for preparing and publishing BMW E87 data bundles."""
from __future__ import annotations

import json
import os
import queue
import sys
import threading
import traceback
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from builder_core import BuildSettings, DataBuilder


def app_dir() -> Path:
    return Path(sys.executable if getattr(sys, "frozen", False) else __file__).resolve().parent


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("BMW E87 Data Builder")
        self.geometry("980x700")
        self.minsize(860, 620)
        self.configure(bg="#050b12")
        self.events: queue.Queue = queue.Queue()
        self.settings_path = app_dir() / "BMW-E87-Data-Builder.settings.json"
        self._build_ui()
        self._load_settings()
        self.after(100, self._drain_events)

    def _build_ui(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TFrame", background="#050b12")
        style.configure("TLabel", background="#050b12", foreground="#d8e8f5", font=("Segoe UI", 10))
        style.configure("Header.TLabel", foreground="#69b8ff", font=("Segoe UI Semibold", 20))
        style.configure("TButton", font=("Segoe UI Semibold", 10), padding=8)
        style.configure("Treeview", background="#071522", fieldbackground="#071522",
                        foreground="#d8e8f5", rowheight=26)
        style.configure("Treeview.Heading", background="#12304a", foreground="white")
        root = ttk.Frame(self, padding=18)
        root.pack(fill="both", expand=True)
        ttk.Label(root, text="BMW E87 · ACTUALIZADOR DE DATOS", style="Header.TLabel").pack(anchor="w")
        ttk.Label(root, text="Prepara, valida y publica cartografía, límites, radares e INVIVE. Los precios de gasolineras no se incluyen.").pack(anchor="w", pady=(0, 14))

        form = ttk.Frame(root)
        form.pack(fill="x")
        self.project = tk.StringVar()
        self.repository = tk.StringVar(value="EMoyaP/BMW-E87-iDrive")
        self.token = tk.StringVar(value=os.environ.get("GITHUB_TOKEN", ""))
        self.lufop = tk.StringVar()
        self.publish = tk.BooleanVar(value=True)
        self._row(form, 0, "Proyecto", self.project, self._browse_project)
        self._row(form, 1, "GitHub", self.repository, None)
        self._row(form, 2, "Token", self.token, None, show="•")
        self._row(form, 3, "ZIP Lufop (opcional)", self.lufop, self._browse_lufop)
        ttk.Checkbutton(form, text="Publicar en GitHub después de validar", variable=self.publish).grid(row=4, column=1, sticky="w", pady=5)

        self.run_button = ttk.Button(root, text="PREPARAR, VALIDAR Y PUBLICAR", command=self._start)
        self.run_button.pack(fill="x", pady=14)
        self.progress = ttk.Progressbar(root, mode="indeterminate")
        self.progress.pack(fill="x", pady=(0, 10))
        self.tree = ttk.Treeview(root, columns=("state", "detail"), show="headings", height=10)
        self.tree.heading("state", text="Estado")
        self.tree.heading("detail", text="Fase / detalle")
        self.tree.column("state", width=110, anchor="center")
        self.tree.column("detail", width=760)
        self.tree.pack(fill="both", expand=True)
        self.log = tk.Text(root, height=7, bg="#02070c", fg="#a9c5d9", insertbackground="white",
                           font=("Consolas", 9), relief="flat", state="disabled")
        self.log.pack(fill="x", pady=(10, 0))
        self.phase_rows = {}

    def _row(self, parent, row, label, variable, browse, show=None):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 10), pady=4)
        entry = ttk.Entry(parent, textvariable=variable, show=show)
        entry.grid(row=row, column=1, sticky="ew", pady=4)
        if browse:
            ttk.Button(parent, text="…", width=4, command=browse).grid(row=row, column=2, padx=(6, 0))
        parent.columnconfigure(1, weight=1)

    def _browse_project(self):
        value = filedialog.askdirectory(title="Selecciona BMW_E87_iDrive_Project")
        if value: self.project.set(value)

    def _browse_lufop(self):
        value = filedialog.askopenfilename(title="Selecciona ZIP Lufop", filetypes=[("ZIP", "*.zip")])
        if value: self.lufop.set(value)

    def _load_settings(self):
        try:
            data = json.loads(self.settings_path.read_text(encoding="utf-8"))
            self.project.set(data.get("project", ""))
            self.repository.set(data.get("repository", self.repository.get()))
            self.lufop.set(data.get("lufop", ""))
            self.publish.set(bool(data.get("publish", True)))
        except Exception:
            candidate = app_dir().parents[1] if len(app_dir().parents) > 1 else Path.cwd()
            if (candidate / "app" / "src" / "main" / "assets").is_dir():
                self.project.set(str(candidate))

    def _save_settings(self):
        data = {"project": self.project.get(), "repository": self.repository.get(),
                "lufop": self.lufop.get(), "publish": self.publish.get()}
        self.settings_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    def _start(self):
        project = Path(self.project.get().strip())
        if not project.is_dir():
            messagebox.showerror("Proyecto", "Selecciona una carpeta de proyecto válida")
            return
        if self.publish.get() and not self.token.get().strip():
            messagebox.showerror("GitHub", "Introduce un token GitHub con permiso para crear releases")
            return
        self._save_settings()
        for row in self.tree.get_children(): self.tree.delete(row)
        self.phase_rows.clear()
        self.run_button.configure(state="disabled")
        self.progress.start(12)
        settings = BuildSettings(project=project, repository=self.repository.get().strip(),
                                 token=self.token.get().strip(),
                                 lufop_zip=Path(self.lufop.get()) if self.lufop.get().strip() else None,
                                 publish=self.publish.get())
        threading.Thread(target=self._worker, args=(settings,), daemon=True).start()

    def _worker(self, settings):
        try:
            manifest = DataBuilder(settings, self._status).run()
            self.events.put(("done", True, str(manifest)))
        except Exception as error:
            self.events.put(("log", traceback.format_exc()))
            self.events.put(("done", False, str(error)))

    def _status(self, phase, state, detail):
        self.events.put(("status", phase, state, detail))

    def _drain_events(self):
        try:
            while True:
                event = self.events.get_nowait()
                if event[0] == "status": self._show_status(*event[1:])
                elif event[0] == "log": self._append_log(event[1])
                elif event[0] == "done": self._done(event[1], event[2])
        except queue.Empty:
            pass
        self.after(100, self._drain_events)

    def _show_status(self, phase, state, detail):
        text = f"{phase} · {detail}"
        if phase in self.phase_rows:
            self.tree.item(self.phase_rows[phase], values=(state, text))
        else:
            self.phase_rows[phase] = self.tree.insert("", "end", values=(state, text))
        self.tree.see(self.phase_rows[phase])
        self._append_log(f"[{state}] {text}\n")

    def _append_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text)
        self.log.see("end")
        self.log.configure(state="disabled")

    def _done(self, success, detail):
        self.progress.stop()
        self.run_button.configure(state="normal")
        if success:
            messagebox.showinfo("Actualización preparada", "Paquete validado correctamente.\n\n" + detail)
        else:
            messagebox.showerror("No se publicó", detail + "\n\nLos datos anteriores no se han modificado.")


if __name__ == "__main__":
    App().mainloop()
