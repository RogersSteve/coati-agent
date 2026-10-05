"""Read-only APK observations; unavailable evidence stays unknown."""
import re


def ocr_evidence(log, pids, boot_id, requested, jev):
    evidence = {"requested": requested, "effective": None, "jev": jev,
                "source": "current_process_main_log_buffer", "boot_id": boot_id,
                "pids": pids, "state": "unknown", "log_line": None}
    if not requested or not jev:
        return {**evidence, "effective": False, "state": "configuration_disabled" if not requested else "classic_route",
                "source": "explicit_condition"}
    for line in log.splitlines():
        m = re.match(r"\s*\S+\s+(\d+)\s+\d+\s+[A-Z]\s+(CoatiService|CoatiJev|OcrEngines)\s*:\s*(.*)", line)
        if not m or m[1] not in pids:
            continue
        message = m[3]
        if "OCR ready: detection pass" in message:
            evidence.update(effective=True, state="ready", log_line=line.strip())
        elif "OCR disabled" in message or "OCR unavailable" in message:
            evidence.update(effective=False, state="disabled" if "disabled" in message else "unavailable", log_line=line.strip())
    return evidence
