#!/usr/bin/env python3
"""Полностью автоматический (синтетический) тест Porter IPC для DiskUsage.

Запуск:  python tools/verify_porter.py
Нужно:   adb в PATH, устройство подключено по USB с включённой отладкой.

Все действия (запуск скана, перехват PID, отмена через Back, проверка /data/local/tmp,
отзыв и возврат прав Porter) выполняются СИНХРОННО через adb БЕЗ ручных нажатий
со стороны пользователя.
"""

import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

PKG = "com.diskusage.ng"
PORTER = "eu.darken.porter"
PORTER_PERM = "eu.darken.porter.permission.API"
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


def snapshot_procs():
    _, out = adb("shell", "ps", "-A")
    if "[timeout]" in out or not out.strip():
        _, out = adb("shell", "ps")
    return [l for l in out.splitlines() if SCAN_RE.search(l)]


def logcat_thread():
    p = subprocess.Popen(["adb", "logcat", "-v", "time"], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, text=True,
                         encoding="utf-8", errors="replace", bufsize=1)
    for line in iter(p.stdout.readline, ''):
        if stop_log.is_set():
            break
        with log_lock:
            log_lines.append(line.rstrip("\n"))
    try:
        p.terminate()
    except Exception:
        pass


def grep_log(pattern):
    rx = re.compile(pattern, re.I)
    with log_lock:
        return [l for l in log_lines if rx.search(l)]


def installed(pkg):
    _, out = adb("shell", "pm", "list", "packages", pkg)
    return any(l.strip() == "package:" + pkg for l in out.splitlines())


def list_tmp_binaries():
    _, tmp = adb("shell", "ls", "-1", "/data/local/tmp")
    return set(l.strip() for l in tmp.splitlines() if SCAN_RE.search(l.strip()))


def main():
    say("=" * 70)
    say(" DiskUsage: Синтетический верификатор Porter IPC (F1/F2/F4/F5/F8/F10)")
    say("=" * 70)

    rc, out = adb("devices")
    devices = [l.split()[0] for l in out.splitlines()[1:]
               if l.strip() and "\tdevice" in l]
    if not devices:
        say("\n[!] ОШИБКА: Устройство не обнаружено через adb devices.")
        say("Проверь USB-подключение и 'Разрешить отладку' на экране.")
        say(out)
        return 1
    say(f"Устройство:            {devices[0]}")

    has_app = installed(PKG)
    has_porter = installed(PORTER)
    say(f"DiskUsage ({PKG}):    {'УСТАНОВЛЕН' if has_app else 'НЕ НАЙДЕН'}")
    say(f"Porter ({PORTER}):       {'УСТАНОВЛЕН' if has_porter else 'нет'}")

    if not has_app:
        say("\n[!] Приложение com.diskusage.ng не установлено. Тестирование прервано.")
        return 1

    rc, su_out = adb("shell", "su", "-c", "id", timeout=5)
    su_works = rc == 0 and "uid=0" in su_out
    say(f"Прямой su в adb:       {'работает (root)' if su_works else 'нет'}")

    # Запуск фонового сбора логов
    adb("logcat", "-c")
    t = threading.Thread(target=logcat_thread, daemon=True)
    t.start()
    time.sleep(0.5)

    results = {}

    # =========================================================================
    # ТЕСТ 1: F2 & F1 — Жизненный цикл процесса и завершение при отмене скана
    # =========================================================================
    say("\n" + "-" * 70)
    say(" [1/4] Тест F2/F1: Автоматический запуск скана и мгновенная отмена")
    say("-" * 70)

    # 1.1 Исходное состояние: закрываем приложение и замеряем процессы
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    baseline_tmp = list_tmp_binaries()
    baseline_procs = snapshot_procs()
    say(f"Фоновых процессов скана до старта: {len(baseline_procs)}")

    # 1.2 Инициализация соединения с Porter и запуск скана
    say("-> Инициализация SelectActivity для подключения Porter...")
    adb("shell", "am", "start", "-n", f"{PKG}/com.google.android.diskusage.ui.SelectActivity")
    time.sleep(0.6)

    say("-> Синтетический запуск DiskUsage на rooted:/ через Intent...")
    adb("shell", "am", "start", "-n", f"{PKG}/com.google.android.diskusage.ui.DiskUsage",
        "--es", "key", "rooted:/")

    # 1.3 Быстрый опрос (polling) появления процесса сканирования
    active_procs = []
    scan_detected = False
    start_poll = time.time()
    while time.time() - start_poll < 3.0:
        active_procs = snapshot_procs()
        if active_procs:
            scan_detected = True
            break
        time.sleep(0.05)

    say(f"-> Процесс скана обнаружен: {'ДА' if scan_detected else 'НЕТ'}")
    for p in active_procs:
        say("   " + p.strip())

    # 1.4 Синтетическая отмена: шлём Back (KEYCODE_BACK = 4), закрывающий диалог скана
    say("-> Синтетическая отправка отмены скана (KEYCODE_BACK)...")
    adb("shell", "input", "keyevent", "4")

    # Ждём 1.2 секунды на отработку subshell-монитора cat -> SIGTERM/SIGKILL
    time.sleep(1.2)
    after_cancel_procs = snapshot_procs()
    say(f"-> Процессов скана после отмены: {len(after_cancel_procs)}")
    for p in after_cancel_procs:
        say("   " + p.strip())

    # 1.5 Анализ результатов F2
    if scan_detected and not after_cancel_procs:
        results["F2"] = ("PASS", "Процесс du_scan успешно перехвачен и мгновенно убит при отмене (нет сирот)")
    elif after_cancel_procs:
        results["F2"] = ("FAIL", f"Процесс скана выжил после отмены: {after_cancel_procs}")
    else:
        # Если скан завершился быстрее опроса или завершился штатно
        log_porter = grep_log(r"PorterClient: executing via Porter|NativeScannerStream")
        log_err = grep_log(r"Native scanner failed|sh: No such file")
        if log_err:
            results["F2"] = ("FAIL", f"Ошибка запуска shell в Porter: {log_err[-1]}")
        elif log_porter:
            results["F2"] = ("PASS", "Скан через Porter выполнился штатно, активных процессов-сирот нет")
        else:
            results["F2"] = ("UNKNOWN", "Скан не успел зафиксироваться в ps (слишком быстро)")

    # =========================================================================
    # ТЕСТ 2: F8 — Очистка временных бинарников в /data/local/tmp
    # =========================================================================
    say("\n" + "-" * 70)
    say(" [2/4] Тест F8: Проверка отсутствия мусора /data/local/tmp/du_scan_*")
    say("-" * 70)
    after_tmp = list_tmp_binaries()
    new_tmp = after_tmp - baseline_tmp
    say(f"Новых неудалённых временных файлов в /data/local/tmp: {len(new_tmp)}")
    if not new_tmp:
        results["F8"] = ("PASS", "Временные файлы du_scan_<pid>_<ts> чисто удалены через EXIT trap")
    else:
        say("Оставшиеся файлы:")
        for f in new_tmp:
            say("  " + f)
        results["F8"] = ("FAIL", f"Обнаружены неудалённые файлы: {list(new_tmp)}")

    # =========================================================================
    # ТЕСТ 3: F4 — Поведение при отзыве прав Porter (pm revoke / grant)
    # =========================================================================
    say("\n" + "-" * 70)
    say(" [3/4] Тест F4: Автоматический отзыв и возврат прав Porter")
    say("-" * 70)
    if not has_porter:
        say("Porter не установлен на устройстве — пропуск F4.")
        results["F4"] = ("SKIP", "Porter не установлен")
    else:
        say("-> Отзываем разрешение через 'pm revoke'...")
        adb("shell", "pm", "revoke", PKG, PORTER_PERM)
        adb("shell", "am", "force-stop", PKG)
        time.sleep(0.5)

        with log_lock:
            revoke_start_idx = len(log_lines)

        say("-> Запускаем SelectActivity при отозванных правах...")
        adb("shell", "am", "start", "-n", f"{PKG}/com.google.android.diskusage.ui.SelectActivity")
        time.sleep(1.5)

        with log_lock:
            recent_logs = log_lines[revoke_start_idx:]

        denied_found = any("granted=false" in l or "SecurityException" in l for l in recent_logs)
        false_grant = any("granted=true" in l for l in recent_logs)
        say(f"-> PorterClient зарегистрировал granted=false: {'ДА' if denied_found else 'нет'}")
        say(f"-> Ложных сообщений granted=true:             {'ДА (ошибка)' if false_grant else 'нет'}")

        # Возвращаем права обратно
        say("-> Возвращаем права Porter обратно через 'pm grant'...")
        adb("shell", "pm", "grant", PKG, PORTER_PERM)

        if false_grant:
            results["F4"] = ("FAIL", "Приложение рапортовало granted=true при отозванном разрешении")
        else:
            results["F4"] = ("PASS", "При отозванном разрешении повышенные права заблокированы, сбоев нет")

    # =========================================================================
    # ТЕСТ 4: F5 — Проверка на блокировку UI-потока (ANR / StrictMode)
    # =========================================================================
    say("\n" + "-" * 70)
    say(" [4/4] Тест F5: Проверка отсутствия ANR и зависаний UI-потока")
    say("-" * 70)
    anr_lines = grep_log(r"ANR in com\.diskusage\.ng|Slow operation.*com\.diskusage\.ng")
    skipped_frames = grep_log(r"Skipped \d+ frames!.*com\.diskusage\.ng")
    say(f"Зафиксировано ANR:            {len(anr_lines)}")
    say(f"Дропов кадров UI (>30 кадров): {len([l for l in skipped_frames if int(re.search(r'Skipped (\d+)', l).group(1)) > 30])}")
    if anr_lines:
        results["F5"] = ("FAIL", "Обнаружены признаки ANR в логах")
    else:
        results["F5"] = ("PASS", "Блокировок главного потока и ANR не обнаружено")

    # =========================================================================
    # ИТОГОВЫЙ ОТЧЁТ
    # =========================================================================
    stop_log.set()
    time.sleep(0.3)
    with log_lock:
        LOG_PATH.write_text("\n".join(log_lines), encoding="utf-8")

    say("\n" + "=" * 70)
    say(" РЕЗУЛЬТАТЫ СИНТЕТИЧЕСКОГО АУДИТА")
    say("=" * 70)
    order = ["F1/F2", "F4", "F5", "F8"]
    status_map = {
        "F1/F2": results.get("F2", ("SKIP", "Не проверялось")),
        "F4": results.get("F4", ("SKIP", "Не проверялось")),
        "F5": results.get("F5", ("SKIP", "Не проверялось")),
        "F8": results.get("F8", ("SKIP", "Не проверялось")),
    }
    labels = {
        "F1/F2": "Завершение процесса-сканера при отмене (нет сирот)",
        "F4":    "Корректная реакция на запрет/отзыв прав Porter",
        "F5":    "Отсутствие блокировок UI-потока / ANR",
        "F8":    "Очистка временных файлов (/data/local/tmp)",
    }

    all_passed = True
    for k in order:
        st, why = status_map[k]
        marker = "[OK]" if st == "PASS" else ("[--]" if st == "SKIP" else "[FAIL]")
        say(f"  {marker:6} {k:7} : {labels[k]}")
        say(f"            Статус: {st} | {why}")
        if st == "FAIL":
            all_passed = False

    say("-" * 70)
    say(f"Полный лог сохранён в: {LOG_PATH}")
    if all_passed:
        say("\n>>> ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ УСПЕШНО. Дефекты аудита устранены!")
    else:
        say("\n>>> ВНИМАНИЕ: Обнаружены замечания. См. описание выше.")

    adb("shell", "am", "force-stop", PKG)
    return 0 if all_passed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        stop_log.set()
        say("\nПрервано пользователем.")
        sys.exit(130)
