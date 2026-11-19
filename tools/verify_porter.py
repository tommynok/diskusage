#!/usr/bin/env python3
"""Проверка находок аудита Porter IPC (коммит 96852b2) на живом устройстве.

Запуск:  python tools/verify_porter.py
Нужно:   adb в PATH, устройство подключено по USB с включённой отладкой.

Скрипт сам снимает процессы, логи и файлы. От тебя — только тапы в приложении
и Enter в консоли. Все команды adb идут БЕЗ пайпов: фильтрация на стороне Python,
поэтому кавычки и различия Windows/Linux не важны.
"""

import re
import subprocess
import sys
import threading
import time
from pathlib import Path

PKG = "com.diskusage.ng"
PORTER = "eu.darken.porter"
SHIZUKU = "moe.shizuku.privileged.api"
SCAN_RE = re.compile(r"du_scan|libscan|diskusage_scan", re.I)
LOG_PATH = Path(__file__).with_name("porter_verify.log")

log_lines = []
log_lock = threading.Lock()
stop_log = threading.Event()


def adb(*args, timeout=25):
    try:
        p = subprocess.run(["adb", *args], capture_output=True, text=True,
                           timeout=timeout, encoding="utf-8", errors="replace")
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return -1, "[timeout]"
    except FileNotFoundError:
        sys.exit("adb не найден в PATH. Установи platform-tools и добавь в PATH.")


def say(msg=""):
    print(msg, flush=True)


def wait(msg):
    say()
    say(">>> " + msg)
    input("    Нажми Enter когда сделал: ")


def snapshot_procs():
    _, out = adb("shell", "ps", "-A")
    if "[timeout]" in out or not out.strip():
        _, out = adb("shell", "ps")
    return [l for l in out.splitlines() if SCAN_RE.search(l)]


def logcat_thread():
    p = subprocess.Popen(["adb", "logcat", "-v", "time"], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, text=True,
                         encoding="utf-8", errors="replace")
    with log_lock:
        pass
    for line in p.stdout:
        if stop_log.is_set():
            break
        with log_lock:
            log_lines.append(line.rstrip("\n"))
    p.terminate()


def grep_log(pattern):
    rx = re.compile(pattern, re.I)
    with log_lock:
        return [l for l in log_lines if rx.search(l)]


def installed(pkg):
    _, out = adb("shell", "pm", "list", "packages", pkg)
    return any(l.strip() == "package:" + pkg for l in out.splitlines())


def main():
    say("=" * 68)
    say(" Porter IPC — проверка F2 / F4 / F5 / F10")
    say("=" * 68)

    rc, out = adb("devices")
    devices = [l.split()[0] for l in out.splitlines()[1:]
               if l.strip() and "\tdevice" in l]
    if not devices:
        say("\nУстройство не видно. Проверь: кабель, 'Разрешить отладку' на экране,")
        say("и что adb devices показывает 'device', а не 'unauthorized'/'offline'.")
        say("\n" + out)
        return 1
    say(f"\nУстройство: {devices[0]}")

    has_app = installed(PKG)
    has_porter = installed(PORTER)
    has_shizuku = installed(SHIZUKU)
    say(f"DiskUsage установлен: {'да' if has_app else 'НЕТ'}")
    say(f"Porter установлен:    {'да' if has_porter else 'нет'}")
    say(f"Shizuku установлен:   {'да' if has_shizuku else 'нет'}")
    if not has_app:
        say(f"\nПриложение {PKG} не найдено — проверять нечего. Установи APK.")
        return 1

    rc, su_out = adb("shell", "su", "-c", "id", timeout=12)
    su_works = rc == 0 and "uid=0" in su_out
    say(f"su из adb shell:      {'работает (uid=0)' if su_works else 'не работает / нет root'}")
    say("  (это справка для F10: наличие файла su != рабочий su)")

    adb("logcat", "-c")
    t = threading.Thread(target=logcat_thread, daemon=True)
    t.start()
    time.sleep(0.5)

    results = {}

    # ---------- F2: процесс-сирота после отмены ----------
    say("\n" + "-" * 68)
    say(" F2 — убивается ли скан при отмене")
    say("-" * 68)
    base = snapshot_procs()
    say(f"Фоновых процессов скана сейчас: {len(base)}")
    for l in base:
        say("  " + l.strip())

    wait("Открой DiskUsage, выбери ROOT-раздел (например /data) и ЗАПУСТИ скан.\n"
         "    Дождись, пока он реально побежит (идёт прогресс).")
    during = snapshot_procs()
    say(f"Во время скана найдено: {len(during)}")
    for l in during:
        say("  " + l.strip())

    wait("ОТМЕНИ скан (кнопка отмены / Back) и выйди на экран выбора разделов.\n"
         "    Подожди ~3 секунды после отмены.")
    time.sleep(3)
    after = snapshot_procs()
    say(f"После отмены найдено: {len(after)}")
    for l in after:
        say("  " + l.strip())

    if during and not after:
        results["F2"] = ("PASS", "процесс скана исчез после отмены")
    elif after:
        results["F2"] = ("FAIL", "процесс пережил отмену — сирота под elevated uid")
    elif not during:
        results["F2"] = ("UNKNOWN", "скан не удалось поймать: процесс не виден во время работы")
    else:
        results["F2"] = ("UNKNOWN", "неоднозначно")

    # ---------- F8: предсказуемый временный файл ----------
    say("\n" + "-" * 68)
    say(" F8 — что лежит в /data/local/tmp")
    say("-" * 68)
    _, tmp = adb("shell", "ls", "-l", "/data/local/tmp")
    hits = [l for l in tmp.splitlines() if SCAN_RE.search(l)]
    if hits:
        for l in hits:
            say("  " + l.strip())
        results["F8"] = ("INFO", "временный бинарь присутствует, имя предсказуемое")
    else:
        say("  ничего похожего на du_scan / diskusage_scan")
        results["F8"] = ("INFO", "файлов не найдено (могли быть удалены)")

    # ---------- F4: отказ в правах ----------
    say("\n" + "-" * 68)
    say(" F4 — что происходит после 'Запретить'")
    say("-" * 68)
    if not has_porter:
        say("Porter не установлен — пункт пропускается.")
        results["F4"] = ("SKIP", "Porter не установлен")
    else:
        wait("В приложении Porter ЗАПРЕТИ доступ для DiskUsage\n"
             "    (Настройки/приложения -> DiskUsage -> запретить, либо отозвать в самом Porter).\n"
             "    Затем на экране выбора разделов нажми карточку «Root, Shizuku или Porter»\n"
             "    (иконка щита) и тапни root-раздел.")
        time.sleep(2)
        denied = grep_log(r"SecurityException|PorterSecurity|not connected|attach failed|no more data")
        granted = grep_log(r"PorterClient.*granted=true|permission.*granted|attached with granted=true")
        say(f"Строк с отказом/ошибкой: {len(denied)}")
        for l in denied[-8:]:
            say("  " + l.strip())
        say(f"Строк с 'granted=true': {len(granted)}")
        for l in granted[-8:]:
            say("  " + l.strip())
        if granted and not denied:
            results["F4"] = ("FAIL", "клиент рапортует granted=true без единого отказа")
        elif denied:
            results["F4"] = ("PASS", "отказ зафиксирован, скан не прошёл")
        else:
            results["F4"] = ("UNKNOWN", "в логе нет ни отказа, ни grant — проверь что тапнул")

    # ---------- F5: блокировка UI-потока ----------
    say("\n" + "-" * 68)
    say(" F5 — ANR / блокировка главного потока")
    say("-" * 68)
    wait("Ещё раз нажми карточку «Root, Shizuku или Porter» (или меню ⋮ ->\n"
             "    «Доступ к Shizuku / Porter»). Porter при этом может быть остановлен.\n"
             "    Смотри, не подвисает ли экран на несколько секунд.")
    time.sleep(2)
    anr = grep_log(r"ANR in|Slow operation|Slow delivery|StrictMode|Skipped \d+ frames")
    porter_main = grep_log(r"PorterClient")
    say(f"Подозрительных строк (ANR/StrictMode/skipped frames): {len(anr)}")
    for l in anr[-10:]:
        say("  " + l.strip())
    if anr:
        results["F5"] = ("FAIL", "есть признаки блокировки/ANR")
    else:
        results["F5"] = ("PASS", "явных признаков блокировки в логе нет")
    say(f"Всего строк PorterClient в логе: {len(porter_main)}")

    # ---------- итог ----------
    stop_log.set()
    time.sleep(0.4)
    with log_lock:
        LOG_PATH.write_text("\n".join(log_lines), encoding="utf-8")

    say("\n" + "=" * 68)
    say(" ИТОГ")
    say("=" * 68)
    order = ["F2", "F4", "F5", "F8"]
    labels = {
        "F2": "процесс-сирота после отмены скана",
        "F4": "поведение после 'Запретить'",
        "F5": "блокировка UI-потока / ANR",
        "F8": "предсказуемый временный бинарь",
    }
    for k in order:
        st, why = results.get(k, ("SKIP", "не выполнялось"))
        say(f"  {k:4} {st:8} {labels[k]}")
        say(f"       {why}")
    say(f"\nПолный лог: {LOG_PATH}")
    say("Строк в логе: %d" % len(log_lines))
    say("\nНапоминание: F2 — единственный по-настоящему опасный пункт.")
    say("F4 аудитор понизил до LOW: сервер Porter сам отказывает в createHost,")
    say("так что обхода прав нет, максимум неверное состояние в UI.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        stop_log.set()
        say("\nПрервано пользователем.")
        sys.exit(130)
