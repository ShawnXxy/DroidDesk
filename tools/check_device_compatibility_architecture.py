#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ANDROID_MAIN, KOTLIN_ROOT, TERMUX_X11 = "app/android/app/src/main", "app/android/app/src/main/kotlin/com/orailnoor/droiddesk", "app/android/app/src/main/java/com/termux/x11"
QUIRK_CATALOG, PLACEMENT_SELECTOR = f"{KOTLIN_ROOT}/display/placement/DeviceQuirkCatalog.kt", f"{KOTLIN_ROOT}/display/placement/DesktopPlacementSelector.kt"
COORDINATOR, EVIDENCE_PROBE, PRESENTATION_EVIDENCE = f"{KOTLIN_ROOT}/display/DesktopDisplayCoordinator.kt", f"{KOTLIN_ROOT}/display/capability/AndroidPresentationEvidenceProbe.kt", f"{KOTLIN_ROOT}/display/capability/PresentationEvidence.kt"
LOCAL_ALLOWED = {COORDINATOR, f"{KOTLIN_ROOT}/display/DisplayLocalSessionController.kt", PRESENTATION_EVIDENCE}
PRESENTATION_POLICY, DISPLAY_PROFILE, REFRESH_POLICY = f"{KOTLIN_ROOT}/display/policy/PresentationPolicy.kt", f"{KOTLIN_ROOT}/display/DisplayProfile.kt", f"{KOTLIN_ROOT}/display/refresh/DisplayRefreshPolicy.kt"
MAIN_ACTIVITY, DESKTOP_ACTIVITY = f"{KOTLIN_ROOT}/MainActivity.kt", f"{KOTLIN_ROOT}/view/DesktopActivity.kt"
INPUT_SENDER, TOUCH_HANDLER = f"{TERMUX_X11}/input/InputEventSender.java", f"{TERMUX_X11}/input/TouchInputHandler.java"
OEM_TOKEN = re.compile(r"(?i)(?:darwin|smartisan|nx888j|pq85a02|\b(?:1(?:_?0){4}_?3|0x0*186a3)[lL]?\b)")
BUILD_IDENTITY = re.compile(r"\bBuild\s*\.\s*(?:MANUFACTURER|BRAND|DEVICE|MODEL|PRODUCT|HARDWARE|FINGERPRINT)\b|\bimport\s+(?:static\s+)?android\.os\.Build\s*(?:as\b|\.\s*\*|\.\s*(?:MANUFACTURER|BRAND|DEVICE|MODEL|PRODUCT|HARDWARE|FINGERPRINT)\b)")
MAIN_DEVICE_INFO_ALLOW = re.compile(r'"(?:model|brand)"\s+to\s+Build\s*\.\s*(?:MODEL|BRAND)\b(?!\s*\.)')
DISPLAY_ENV_POLICY, DISPLAY_LOCAL_WIRE = re.compile(r"\bDisplayEnvironmentPolicy\b"), re.compile(r'["\']displayLocalMode["\']')
DISPLAY_LOCAL_CALLER = re.compile(r"\b(?:isDisplayLocal|DISPLAY_LOCAL|MOVABLE|placementSelection|DisplayLocalRestartAdapter|MovableSecondaryDisplayAdapter|DisplayLocalSessionController)\b")
INPUT_POLICY_CALL = re.compile(r"\bDesktopInputPolicy\s*(?:\.|::)\s*resolve\b|\bexternalInputRoute\s*\(")
PLACEMENT_INTERNAL = re.compile(r"\b(?:DesktopPlacementSelector|DeviceQuirkCatalog|AndroidPlacementPreferenceStore|bootstrapPlacementSelection|bootstrapPlacementStrategy|bootstrapCurrentDevicePlacementStrategy|currentDeviceIdentity)\b")
PROFILE_POLICY_CALL = re.compile(r"\bDisplayProfilePolicy\s*(?:\.|::)\s*(?:classify|resolve|renderSize)\b")
FIXED_POINTER = re.compile(r"\bnew\s+(?:int|boolean|float|long|short|byte)\s*\[\s*10\s*\]|\b(?:mPointers|pointers)\s*\[" r"|\[\s*(?:pointerId|\w+\s*\.\s*getPointerId\s*\([^)]*\))\s*\]|\bMAX_POINTERS\s*=\s*10\b|\bpointerId\s*(?:>=|<|<=|>)\s*(?:10|9)\b")
_MODES_RAW, _NO_CHAIN, _RATE_EXPR = re.compile(r"\b(?:supportedModes|getSupportedModes)\b"), r"(?!\s*(?:\?\.|\.|!!|::|\[|\())", r"\b(?:refreshRate\b|getRefreshRate\s*\(\s*\))"
_RATE_COMPARE = re.compile(rf"{_RATE_EXPR}[^;{{}}]{{0,80}}?(?<![\w.])(?:60(?:\.0+)?[fFdD]?|59\.94[fFdD]?)(?![\w.])|(?<![\w.])(?:60(?:\.0+)?[fFdD]?|59\.94[fFdD]?)(?![\w.])[^;{{}}]{{0,80}}?{_RATE_EXPR}")
WINDOW_REFRESH_ASSIGN = re.compile(r"\b(?:preferredDisplayModeId|preferredRefreshRate)\s*(?:[+\-*/%]=|\+\+|--|=(?!=))|(?<![\w)\]])(?:\+\+|--)[ \t]*(?:[\w?]+[ \t]*\.[ \t]*)*(?:preferredDisplayModeId|preferredRefreshRate)\b")
COSMETIC_WORDS = {"fade", "spinner", "toast", "tooltip", "blink", "ripple", "ime"}
ANIM_START = re.compile(r"((?:\?\.|\.)\s*animate\s*\(\s*\)(?:\s*(?:\?\.|\.)\s*(?:alpha|alphaBy|scaleX|scaleY|translationX|translationY|x|y|rotation|setDuration|setStartDelay)\s*\([\w.\s+\-*/]*\))*\s*(?:\?\.|\.)\s*)start(\s*\(\s*\))")
STARTUP_WORDS = {"start", "starting", "launch", "open", "opening", "session", "ready", "readiness", "desktop", "x11", "lorie", "surface", "connect", "connected", "profile", "handoff"}
SMALLEST_SW_DP = re.compile(r"\bsmallestScreenWidthDp\b")
ACTIVE_POINTER_TRACKER, INPUT_EVENT_SENDER = re.compile(r"\bActivePointerTracker\b"), re.compile(r"\bInputEventSender\b")
READINESS_GATE, IMPORT_LINE = re.compile(r"\bDesktopReadinessGate\b"), re.compile(r"(?m)^\s*import\b[^\n]*")

def _blank(chunk: str) -> str:
    return "".join("\n" if c == "\n" else " " for c in chunk)

def lex_source(text: str, suffix: str) -> tuple[str, str]:
    nc, co, n = [], [], len(text)
    delims = ('"""', "'''", '"', "'") if suffix == ".dart" else ('"""', '"', "'")

    def emit(chunk: str, keep_nc: bool, keep_co: bool = False) -> None:
        nc.append(chunk if keep_nc else _blank(chunk))
        co.append(chunk if keep_co else _blank(chunk))

    def scan_string(idx: int, quote: str, raw: bool, interp: bool) -> int:
        emit(quote, True)
        idx += len(quote)
        while idx < n:
            if len(quote) == 3 and text.startswith(quote, idx):
                end = idx + 3
                while suffix == ".kt" and quote == '"""' and end < n and text[end] == '"':
                    end += 1
                emit(text[idx:end], True)
                return end
            if len(quote) == 1 and text[idx] in (quote, "\n"):
                emit(text[idx], True)
                return idx + 1
            if not raw and text[idx] == "\\" and idx + 1 < n:
                emit(text[idx : idx + 2], True)
                idx += 2
            elif interp and text.startswith("${", idx):
                emit("${", True)
                idx = scan_code(idx + 2, True)
                if idx < n and text[idx] == "}":
                    emit("}", True)
                    idx += 1
            elif interp and text[idx] == "$" and idx + 1 < n and (text[idx + 1].isalpha() or text[idx + 1] == "_"):
                emit("$", True)
                start = idx = idx + 1
                while idx < n and (text[idx].isalnum() or text[idx] == "_"):
                    idx += 1
                emit(text[start:idx], True, True)
            else:
                emit(text[idx], True)
                idx += 1
        return idx

    def scan_code(idx: int, in_interp: bool = False) -> int:
        brace = 1
        while idx < n:
            if in_interp and text[idx] in "{}":
                brace += 1 if text[idx] == "{" else -1
                if brace == 0:
                    return idx
                emit(text[idx], True, True)
                idx += 1
            elif text.startswith("//", idx):
                end = text.find("\n", idx)
                end = n if end == -1 else end
                emit(text[idx:end], False)
                idx = end
            elif text.startswith("/*", idx):
                emit("/*", False)
                idx, depth, nested = idx + 2, 1, suffix in {".kt", ".dart"}
                while idx < n and depth > 0:
                    step = 2 if (nested and text.startswith("/*", idx)) or text.startswith("*/", idx) else 1
                    depth += 1 if step == 2 and text.startswith("/*", idx) else (-1 if step == 2 else 0)
                    emit(text[idx : idx + step], False)
                    idx += step
            elif suffix == ".dart" and text[idx] == "r" and (idx == 0 or not (text[idx - 1].isalnum() or text[idx - 1] == "_")) and (q := next((d for d in delims if text.startswith(d, idx + 1)), None)):
                emit("r", True, True)
                idx = scan_string(idx + 1, q, True, False)
            elif q := next((d for d in delims if text.startswith(d, idx)), None):
                idx = scan_string(idx, q, suffix == ".kt" and q == '"""', suffix == ".dart" or (suffix == ".kt" and q != "'"))
            else:
                emit(text[idx], True, True)
                idx += 1
        return idx

    scan_code(0)
    return "".join(nc), "".join(co)

def line_no(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1

def balanced_end(text: str, open_idx: int, open_ch: str, close_ch: str) -> int | None:
    depth = 0
    for idx in range(open_idx, len(text)):
        depth += (text[idx] == open_ch) - (text[idx] == close_ch)
        if depth == 0:
            return idx + 1
    return None

def _uncalled_kt(code_only: str, fn: str, decl_span: tuple[int, int]) -> bool:
    s, e = decl_span
    return not re.search(rf"\b{re.escape(fn)}\b", code_only[:s] + code_only[e:])

def find_refresh_selections(rel: str, code_only: str) -> list[tuple[int, str]]:
    hits = [(m.start(), m.group(0).strip()) for m in _RATE_COMPARE.finditer(code_only)]
    masked, allow_pats = list(code_only), []
    if rel == PRESENTATION_EVIDENCE:
        allow_pats.extend((
            rf"(?:\bval\s+)?\bsupportedModes\s*:\s*List\s*<\s*DisplayModeEvidence\s*>(?:\s*=\s*this\s*\.\s*supportedModes\b{_NO_CHAIN})?",
            rf"\bHashSet\s*<\s*Int\s*>\s*\(\s*supportedModes\s*\.\s*size\s*\){_NO_CHAIN}",
            rf"\bsupportedModes\s*\.\s*all\s*\{{\s*\w+\s*\.\s*add\s*\(\s*\w+\s*\.\s*modeId\s*\)\s*\}}{_NO_CHAIN}",
            rf"\bsupportedModes\s*=\s*(?:supportedModes\b|Collections\s*\.\s*unmodifiableList\s*\(\s*ArrayList\s*\(\s*supportedModes\s*\)\s*\)){_NO_CHAIN}",
        ))
    elif rel == PRESENTATION_POLICY:
        allow_pats.append(rf"\brefreshPolicy\s*\([^(),\[]*,\s*[\w?]+\s*\.\s*supportedModes\s*\){_NO_CHAIN}")
    elif rel == EVIDENCE_PROBE:
        allow_pats.extend((r"\bval\s+supportedModes\s*:\s*List\s*<\s*DisplayModeEvidence\s*>", rf"\bsupportedModes\s*=\s*snapshot\s*\.\s*supportedModes\b{_NO_CHAIN}"))
        p_map = rf"(?:\bsupportedModes\s*=\s*|\bfun\s+([A-Za-z_]\w*)\s*\(\s*\)\s*=\s*)[\w?]+\s*(?:\?\.|\.)\s*supportedModes\s*\.\s*map\s*\{{\s*\w+\s*\.\s*toEvidence\s*\(\s*\)\s*\}}{_NO_CHAIN}"
        for m in re.finditer(p_map, code_only):
            if not m.group(1) or _uncalled_kt(code_only, m.group(1), m.span()):
                masked[m.start() : m.end()] = list(_blank(code_only[m.start() : m.end()]))
    elif rel == DESKTOP_ACTIVITY:
        allow_pats.append(rf"\bDisplayRefreshPolicy\s*\.\s*resolve\s*\([^(),\[]*,\s*[\w?]+\s*\.\s*supportedModes\s*\){_NO_CHAIN}")
        map_pat = (
            r"(?:\bval\s+([A-Za-z_]\w*)\s*=\s*|\bfun\s+([A-Za-z_]\w*)\s*\(\s*\)\s*=\s*)[\w?]+\s*(?:\?\.|\.)\s*supportedModes\s*(?:\?\.|\.)\s*mapNotNull"
            rf"\s*\{{\s*[\w?]+\s*(?:\?\.|\.)\s*toEvidenceOrNull\s*\(\s*\)\s*\}}(?:\s*(?:\?\.|\.)\s*distinctBy\s*\{{\s*\w+\s*\.\s*modeId\s*\}})?(?:\s*(?:\?\.|\.)\s*orEmpty\s*\(\s*\))?{_NO_CHAIN}"
        )
        ctx_spans = [(cm.start(), balanced_end(code_only, cm.end() - 1, "(", ")") or 0) for cm in re.finditer(r"\bPresentationContext\s*\(", code_only)]
        for m in re.finditer(map_pat, code_only):
            a, fn = m.group(1), m.group(2)
            outside = code_only[: m.start()] + (" " * (m.end() - m.start())) + code_only[m.end() :]
            uses_ok = (_uncalled_kt(code_only, fn, m.span()) if fn else True) and (
                not a or all(any(s <= u.start() < e for s, e in ctx_spans) and not re.match(r"\s*(?:\?\.|\.|!!|::|\[|\()", outside[u.end() :]) for u in re.finditer(rf"\b{re.escape(a)}\b", outside))
            )
            if uses_ok:
                masked[m.start() : m.end()] = list(_blank(code_only[m.start() : m.end()]))
    elif rel.endswith(".java"):
        j_pat = r"\b(?:Object|(?:[\w.]+\.)?Mode\s*\[\s*\])\s+([A-Za-z_]\w*)\s*\([^(){}]*\)\s*\{[^{}]*?(\breturn\s+[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*(?:\s*\(\s*\))?)*\s*\.\s*getSupportedModes\s*\(\s*\)\s*;)\s*\}"
        for m in re.finditer(j_pat, code_only):
            if _uncalled_kt(code_only, m.group(1), m.span()):
                masked[m.start(2) : m.end(2)] = list(_blank(code_only[m.start(2) : m.end(2)]))
    for pat in allow_pats:
        for m in re.finditer(pat, code_only):
            masked[m.start() : m.end()] = list(_blank(code_only[m.start() : m.end()]))
    return [*hits, *((m.start(), m.group(0).strip()) for m in _MODES_RAW.finditer("".join(masked)))]

def allowed_refresh_assigns(code_only: str) -> set[int]:
    allowed: set[int] = set()
    rhs_end = r"(?=[ \t]*(?:[;}]|$|\n\s*(?!\s|\.|\?\.|!!|\?:|[+\-*/%<>=&|\[\(]|(?:as|is|in|and|or|xor|shl|shr|ushr|until|downTo|step|coerce\w*)\b)))"
    for match in re.finditer(r"\bapplyRefreshRequest\s*\(", code_only):
        if re.search(r"\bfun\s+$", code_only[max(0, match.start() - 20) : match.start()]):
            continue
        arg_end = balanced_end(code_only, match.end() - 1, "(", ")")
        rest = code_only[arg_end:] if arg_end is not None else ""
        ws = len(rest) - len(rest.lstrip())
        brace_idx = (arg_end + ws) if arg_end is not None and rest.startswith("{", ws) else None
        body_end = balanced_end(code_only, brace_idx, "{", "}") if brace_idx is not None else None
        body = code_only[brace_idx + 1 : body_end - 1] if brace_idx is not None and body_end is not None else ""
        if header := re.match(r"\s*([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?\s*,\s*([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?\s*->", body):
            inner, base = body[header.end() :], brace_idx + 1 + header.end()
            if "->" in inner or re.search(r"\b(?:fun|for|catch)\b", inner):
                continue
            non_recv_brace = bool(re.search(r"\{", re.sub(r"\b(?:apply|run|with|try|if|else)\b\s*(?:\([^){}]*\)\s*)?\{", "", inner)))
            for prop, param in (("preferredRefreshRate", header.group(1)), ("preferredDisplayModeId", header.group(2))):
                p = re.escape(param)
                if param != "_" and not (param == "it" and non_recv_brace) and not re.search(rf"\b(?:val|var)\s+(?:\([^)=;{{}}]*\b{p}\b|\b{p}\b)", inner):
                    allowed.update(base + a.start() for a in re.finditer(rf"\b{prop}\s*=(?!=)\s*{p}\b{rhs_end}", inner))
    return allowed

def _ident_words(text: str) -> list[str]:
    return [w.lower() for w in re.findall(r"[A-Z]+(?=[A-Z][a-z]|\b)|[A-Z]?[a-z0-9]+", text)]

def _is_cosmetic_ident(ident: str) -> bool:
    words = _ident_words(ident)
    cosmetic_pairs = {("loading", "message"), ("loading", "estimate"), ("mouse", "down"), ("log", "flush"), ("flush", "log")}
    return bool(COSMETIC_WORDS.intersection(words) or cosmetic_pairs.intersection(set(zip(words, words[1:]))))

def _is_when_brace(code_only: str, brace_idx: int) -> bool:
    pre = code_only[:brace_idx].rstrip()
    if pre.endswith(")"):
        depth = 0
        for idx in range(len(pre) - 1, -1, -1):
            depth += (pre[idx] == ")") - (pre[idx] == "(")
            if depth == 0:
                return bool(re.search(r"\bwhen\s*$", pre[:idx]))
    return bool(re.search(r"\bwhen\s*$", pre))

def _decl_bodies(code_only: str, name: str) -> list[str] | None:
    p = re.escape(name)
    if (
        re.search(rf"\bvar\s+{p}\b|\b(?:val|var)\s*\([^)=;{{}}]*\b{p}\b|\bfor\s*\([^)]*\b{p}\b[^)]*(?::|\bin\b)|\bRunnable\s+{p}\s*[,)]|(?<!:\s)(?:(?<![.\w])|\([^()=:]*\b){p}\s*\)?\s*->", code_only)
        or len(re.findall(rf"\b{p}\b(?:[ \t]*:[ \t]*(?:\([^)\n]*\)[ \t]*->[ \t]*)?[\w<>?., \t]+)?[ \t]*=(?!=)", code_only)) > 1
    ):
        return None
    for pm in re.finditer(r"\b(?:fun|constructor|class|catch)\b[^{;=]*\(", code_only):
        pend = balanced_end(code_only, pm.end() - 1, "(", ")")
        if re.search(rf"\b{p}\s*:(?!=)|\bRunnable\s+{p}\b", code_only[pm.end() : (pend - 1) if pend else len(code_only)]):
            return None
    for bm in re.finditer(r"\{", code_only):
        arrow = code_only.find("->", bm.end(), bm.end() + 240)
        seg = code_only[bm.end() : arrow] if arrow != -1 else ""
        if seg and not any(c in seg for c in "{};=") and not re.search(r"\b(?:val|var|fun|Runnable)\b", seg) and not _is_when_brace(code_only, bm.start()) and re.search(rf"\b{p}\b", seg):
            return None
    bodies, decl_re = [], re.compile(rf"\b(?:val|fun(?:\s*<[^>]+>)?(?:\s+[\w?]+\s*\.)?|(?:java\.lang\.)?Runnable|void|boolean|int|long|float|double|Object|String)\s+{p}\b(?:[ \t]*\([^)]*\))?(?:[ \t]*:[ \t]*(?:\([^)\n]*\)[ \t]*->[ \t]*)?[\w<>?., \t]+)?([ \t]*=\s*(?:(?:object\s*:\s*|new\s+)?(?:java\.lang\.)?Runnable(?:\s*\(\s*\))?\s*|\(\s*\)\s*->\s*)?)?[ \t]*")
    next_decl = re.compile(r"[ \t]*\n\s*(?:private\b|internal\b|public\b|protected\b|override\b|val\b|var\b|fun\b|\}|\Z)")
    for m in decl_re.finditer(code_only):
        tail = m.end()
        if tail < len(code_only) and code_only[tail] == "{":
            if (end := balanced_end(code_only, tail, "{", "}")) is None:
                return None
            bodies.append(code_only[tail:end])
        elif m.group(1):
            nl = code_only.find("\n", tail)
            line_body = code_only[tail : len(code_only) if nl == -1 else nl]
            if "{" in line_body or (nl != -1 and not next_decl.match(code_only, nl)):
                return None
            bodies.append(line_body)
        else:
            return None
    return bodies

def _norm_delay(expr: str) -> str:
    depth = 0
    for idx, ch in enumerate(expr):
        depth += (ch == "(") - (ch == ")")
        if depth < 0:
            expr = expr[:idx]
            break
    s, changed = expr.strip(), True
    while changed:
        changed = False
        if s.startswith("(") and balanced_end(s, 0, "(", ")") == len(s):
            s, changed = s[1:-1].strip(), True
        if m := re.match(r"^\(\s*(?:long|int)\s*\)\s*(.+)$", s):
            s, changed = m.group(1).strip(), True
        if m := re.match(r"^(.+?)\s*\.\s*to(?:Long|Int)\s*\(\s*\)$", s):
            s, changed = m.group(1).strip(), True
    return s

def find_250ms_post_delayed(code_only: str) -> list[int]:
    hits, lit_250, qual_ident = [], re.compile(r"(?i)250(?:_?l)?|0x0*fal?"), re.compile(r"[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*")
    p_val = r"\b(?:val|var)\s+([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?(?:\s+get\s*\(\s*\))?\s*=(?!=)\s*([^\n;,}]+)"
    p_def = r"\b([A-Za-z_]\w*)\s*:\s*(?:Long|Int)\s*=(?!=)\s*([^\n;,}]+)"
    decls = [(m.group(1), _norm_delay(m.group(2))) for pat in (p_val, p_def) for m in re.finditer(pat, code_only)]
    for jm in re.finditer(r"\b(?:int|long|Integer|Long)\s+([^;{}\n]+)", code_only):
        decls.extend((dm.group(1), _norm_delay(dm.group(2))) for part in jm.group(1).split(",") if (dm := re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)\s*(.+)", part)))
    const_250 = {name for name, rhs in decls if lit_250.fullmatch(rhs)}
    for _ in range(4):
        const_250.update(name for name, rhs in decls if qual_ident.fullmatch(rhs) and rhs.split(".")[-1].strip() in const_250)
    for fm in re.finditer(r"\bfor\s*\(", code_only):
        fend = balanced_end(code_only, fm.end() - 1, "(", ")")
        hdr = code_only[fm.end() : fend - 1] if fend else ""
        if (hm := re.match(r"\s*(?:final\s+)?(?:(?:int|long|Integer|Long|var|val)\s+)?([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?\s*(?::|\bin\b)([\s\S]*)", hdr)) and any(
            not m.group(1) for m in re.finditer(r"([+\-*/]\s*)?(?<![.\w])(?:250(?:_?[lL])?|0[xX]0*[fF][aA][lL]?)(?![.\w]|[ \t]*[+\-*/])", hm.group(2))
        ):
            const_250.add(hm.group(1))
    ident_re = re.compile(r"^(?:[A-Za-z_]\w*\s*=\s*)?(?:::|this::)?([A-Za-z_]\w*)$")

    def is_250_arg(part: str) -> bool:
        rhs = _norm_delay(re.sub(r"^[A-Za-z_]\w*\s*=\s*", "", part))
        return bool(lit_250.fullmatch(rhs) or (qual_ident.fullmatch(rhs) and rhs.split(".")[-1].strip() in const_250))

    for match in re.finditer(r"\bpostDelayed\s*\(", code_only):
        end = balanced_end(code_only, match.end() - 1, "(", ")")
        if end is None:
            continue
        args, paren, brace = [[]], 0, 0
        for ch in code_only[match.end() : end - 1]:
            paren += (ch == "(") - (ch == ")")
            brace = max(0, brace + (ch == "{") - (ch == "}"))
            args.append([]) if ch == "," and paren == 0 and brace == 0 else args[-1].append(ch)
        rest = code_only[end:]
        ws = len(rest) - len(rest.lstrip())
        trail_end = balanced_end(code_only, end + ws, "{", "}") if rest.startswith("{", ws) else None
        recv_m = re.search(r"([A-Za-z_]\w*)\s*(?:\?\.|\.)\s*$", code_only[: match.start()])
        recv = recv_m.group(1) if recv_m else ""
        arg_texts = ["".join(p).strip() for p in args if "".join(p).strip()]
        cb_args = [*[p for p in arg_texts if not is_250_arg(p)], *([code_only[end + ws : trail_end]] if trail_end else [])]
        cb_ok = bool(cb_args) and all((p.startswith("{") and p.endswith("}")) or ident_re.match(p) for p in cb_args)
        cb_idents = [m.group(1) for p in cb_args if (m := ident_re.match(p))]
        resolved = [_decl_bodies(code_only, name) for name in cb_idents]
        skip_calls = {"if", "for", "while", "when", "switch", "catch", "synchronized", "Runnable", "mapOf", "listOf", "setOf", "arrayOf", "emptyList", "emptySet", "checkNotNull", "require", "MethodChannel"}
        callee_re = re.compile(r"(?<![.\w])(?:this\s*\.\s*)?([A-Za-z_]\w*)\s*\(|(?<![.\w])(?:this)?::([A-Za-z_]\w*)\b|\b([A-Za-z_]\w*)\s*(?:\?\.|\.)\s*(?:run|invoke)\s*\(")
        chunks, seen, bad_callee = [*cb_args, *(b for g in resolved if g for b in g)], set(cb_idents) | skip_calls, any(not g for g in resolved)
        for _ in range(4):
            callees = {next(g for g in m.groups() if g) for c in chunks for m in callee_re.finditer(c)} - seen
            seen.update(callees)
            for name in callees:
                if not (res := _decl_bodies(code_only, name)):
                    bad_callee = True
                else:
                    chunks.extend(res)
        inspected = ANIM_START.sub(r"\1\2", " ".join(chunks))
        is_cosmetic = (
            cb_ok and not bad_callee and (_is_cosmetic_ident(recv) or any(_is_cosmetic_ident(c) for c in cb_idents))
            and not STARTUP_WORDS.intersection(_ident_words(inspected))
        )
        if any(is_250_arg(p) for p in arg_texts) and not is_cosmetic:
            hits.append(match.start())
    return hits

def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    violations: list[str] = []
    android_root, flutter_root = root / ANDROID_MAIN, root / "app/lib"
    files = [*(sorted([*android_root.rglob("*.kt"), *android_root.rglob("*.java")]) if android_root.is_dir() else []), *(sorted(flutter_root.rglob("*.dart")) if flutter_root.is_dir() else [])]
    lexed = {p.relative_to(root).as_posix(): lex_source(p.read_text(encoding="utf-8"), p.suffix) for p in files}

    def add(rel: str, src: str, pattern: re.Pattern[str], category: str) -> None:
        violations.extend(f"{rel}:{line_no(src, m.start())}: {category} ({m.group(0).strip()})" for m in pattern.finditer(src))

    startup_prefixes = (f"{KOTLIN_ROOT}/display/", f"{KOTLIN_ROOT}/view/", f"{KOTLIN_ROOT}/x11/", f"{KOTLIN_ROOT}/service/", f"{TERMUX_X11}/")
    for rel, (no_comments, code_only) in lexed.items():
        if rel.endswith("/DisplayEnvironmentPolicy.kt"):
            violations.append(f"{rel}:1: display-local branching leakage (DisplayEnvironmentPolicy.kt)")
        if rel != QUIRK_CATALOG:
            add(rel, no_comments, OEM_TOKEN, "oem identity leakage")
        add(rel, code_only, DISPLAY_ENV_POLICY, "display-local branching leakage")
        if not rel.startswith(f"{ANDROID_MAIN}/"):
            continue
        if rel == MAIN_ACTIVITY:
            masked = list(code_only)
            for allow in MAIN_DEVICE_INFO_ALLOW.finditer(no_comments):
                masked[allow.start() : allow.end()] = list(_blank(code_only[allow.start() : allow.end()]))
            add(rel, "".join(masked), BUILD_IDENTITY, "oem identity leakage")
        elif rel not in {QUIRK_CATALOG, PLACEMENT_SELECTOR}:
            add(rel, code_only, BUILD_IDENTITY, "oem identity leakage")
        if rel not in LOCAL_ALLOWED and not rel.startswith(f"{KOTLIN_ROOT}/display/placement/"):
            add(rel, code_only, DISPLAY_LOCAL_CALLER, "display-local branching leakage")
            add(rel, no_comments, DISPLAY_LOCAL_WIRE, "display-local branching leakage")
        if rel != PRESENTATION_POLICY and not rel.startswith(f"{KOTLIN_ROOT}/display/input/"):
            add(rel, code_only, INPUT_POLICY_CALL, "misplaced input/placement policy")
        if rel != COORDINATOR and not rel.startswith(f"{KOTLIN_ROOT}/display/placement/"):
            add(rel, code_only, PLACEMENT_INTERNAL, "misplaced input/placement policy")
        if rel not in {PRESENTATION_POLICY, DISPLAY_PROFILE}:
            add(rel, code_only, PROFILE_POLICY_CALL, "misplaced input/placement policy")
        if rel.startswith(f"{TERMUX_X11}/input/"):
            add(rel, code_only, FIXED_POINTER, "fixed pointer array")
        if rel == MAIN_ACTIVITY or rel.startswith(startup_prefixes):
            violations.extend(f"{rel}:{line_no(code_only, offset)}: 250 ms startup delay (postDelayed 250)" for offset in find_250ms_post_delayed(code_only))
        if rel != REFRESH_POLICY:
            violations.extend(f"{rel}:{line_no(code_only, offset)}: direct refresh-mode selection ({snippet})" for offset, snippet in find_refresh_selections(rel, code_only))
            allowed = allowed_refresh_assigns(code_only) if rel == DESKTOP_ACTIVITY else set()
            violations.extend(f"{rel}:{line_no(code_only, m.start())}: direct refresh-mode selection ({m.group(0).strip()})" for m in WINDOW_REFRESH_ASSIGN.finditer(code_only) if m.start() not in allowed)
        add(rel, code_only, SMALLEST_SW_DP, "smallestScreenWidthDp profile classification")

    sender_tracked = INPUT_SENDER in lexed and bool(ACTIVE_POINTER_TRACKER.search(IMPORT_LINE.sub("", lexed[INPUT_SENDER][1])))
    if INPUT_SENDER in lexed and not sender_tracked:
        violations.append(f"{INPUT_SENDER}:1: fixed pointer array (missing ActivePointerTracker)")
    if TOUCH_HANDLER in lexed and not (ACTIVE_POINTER_TRACKER.search(tb := IMPORT_LINE.sub("", lexed[TOUCH_HANDLER][1])) or (sender_tracked and INPUT_EVENT_SENDER.search(tb))):
        violations.append(f"{TOUCH_HANDLER}:1: fixed pointer array (missing ActivePointerTracker)")
    if DESKTOP_ACTIVITY in lexed and not READINESS_GATE.search(IMPORT_LINE.sub("", lexed[DESKTOP_ACTIVITY][1])):
        violations.append(f"{DESKTOP_ACTIVITY}:1: 250 ms startup delay (missing DesktopReadinessGate)")
    if violations:
        print("Device compatibility architecture violations:\n" + "\n".join(f"- {v}" for v in violations))
        return 1
    print("Device compatibility architecture: OK")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
