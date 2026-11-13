#!/usr/bin/env python3
import re, sys
from pathlib import Path
ANDROID_MAIN, KOTLIN_ROOT, TERMUX_X11 = "app/android/app/src/main", "app/android/app/src/main/kotlin/com/orailnoor/droiddesk", "app/android/app/src/main/java/com/termux/x11"
QUIRK_CATALOG, PLACEMENT_SELECTOR, COORDINATOR = f"{KOTLIN_ROOT}/display/placement/DeviceQuirkCatalog.kt", f"{KOTLIN_ROOT}/display/placement/DesktopPlacementSelector.kt", f"{KOTLIN_ROOT}/display/DesktopDisplayCoordinator.kt"
EVIDENCE_PROBE, PRESENTATION_EVIDENCE = f"{KOTLIN_ROOT}/display/capability/AndroidPresentationEvidenceProbe.kt", f"{KOTLIN_ROOT}/display/capability/PresentationEvidence.kt"
LOCAL_ALLOWED, PRESENTATION_POLICY, DISPLAY_PROFILE, REFRESH_POLICY = {COORDINATOR, f"{KOTLIN_ROOT}/display/DisplayLocalSessionController.kt", PRESENTATION_EVIDENCE}, f"{KOTLIN_ROOT}/display/policy/PresentationPolicy.kt", f"{KOTLIN_ROOT}/display/DisplayProfile.kt", f"{KOTLIN_ROOT}/display/refresh/DisplayRefreshPolicy.kt"
MAIN_ACTIVITY, DESKTOP_ACTIVITY, INPUT_SENDER, TOUCH_HANDLER = f"{KOTLIN_ROOT}/MainActivity.kt", f"{KOTLIN_ROOT}/view/DesktopActivity.kt", f"{TERMUX_X11}/input/InputEventSender.java", f"{TERMUX_X11}/input/TouchInputHandler.java"
OEM_TOKEN, BUILD_IDENTITY = re.compile(r"(?i)(?:darwin|smartisan|nx888j|pq85a02|\b(?:1(?:_?0){4}_?3|0x0*186a3)[lL]?\b)"), re.compile(r"\bBuild\s*\.\s*(?:MANUFACTURER|BRAND|DEVICE|MODEL|PRODUCT|HARDWARE|FINGERPRINT)\b|\bimport\s+(?:static\s+)?android\.os\.Build\s*(?:as\b|\.\s*\*|\.\s*(?:MANUFACTURER|BRAND|DEVICE|MODEL|PRODUCT|HARDWARE|FINGERPRINT)\b)")
MAIN_DEVICE_INFO_ALLOW, DISPLAY_ENV_POLICY, DISPLAY_LOCAL_WIRE = re.compile(r'"(?:model|brand)"\s+to\s+Build\s*\.\s*(?:MODEL|BRAND)\b(?!\s*\.)'), re.compile(r"\bDisplayEnvironmentPolicy\b"), re.compile(r'["\']displayLocalMode["\']')
DISPLAY_LOCAL_CALLER = re.compile(r"\b(?:isDisplayLocal|DISPLAY_LOCAL|MOVABLE|placementSelection|DisplayLocalRestartAdapter|MovableSecondaryDisplayAdapter|DisplayLocalSessionController)\b")
PLACEMENT_INTERNAL, FIXED_POINTER = re.compile(r"\b(?:DesktopPlacementSelector|DeviceQuirkCatalog|AndroidPlacementPreferenceStore|bootstrapPlacementSelection|bootstrapPlacementStrategy|bootstrapCurrentDevicePlacementStrategy|currentDeviceIdentity)\b"), re.compile(r"\bnew\s+(?:int|boolean|float|long|short|byte)\s*\[\s*10\s*\]|\b(?:mPointers|pointers)\s*\[|\[\s*(?:pointerId|\w+\s*\.\s*getPointerId\s*\([^)]*\))\s*\]|\bMAX_POINTERS\s*=\s*10\b|\bpointerId\s*(?:>=|<|<=|>)\s*(?:10|9)\b")
_MODES_RAW, _NO_CHAIN, _RATE_BASE, _DENSE_CALL = re.compile(r"\b(?:supportedModes|getSupportedModes)\b"), r"(?!\s*(?:\?\.|\.|!!|::|\[|\())", r"(?:refreshRate\b|getRefreshRate\s*\(\s*\))", re.compile(r"\b(?:findPointerIndex|getPointerCount|getActionIndex|indexOf\w*|binarySearch)\s*\(")
_TARGET_HZ, _CMP_OP, _TYPE_HDR = r"(?<!\w)(?<!(?<!\.)\.)(?:60(?:\.0+)?[fFdD]?|59\.94[fFdD]?)(?!(?:\w|\.\d))", r"(?:==|!=|<=|>=|<(?!>)|(?<!-)>|-(?!>)|\+|\.\.\s*<?|!?\bin\b|\b(?:compareTo|equals|minus|plus|coerce\w*)\b)", re.compile(r"\b(?:class|interface|enum|object)\b(?:(?!\bfun\b)(?!\bobject\s*:)[^{;])*$")
WINDOW_REFRESH_ASSIGN, ANIM_START = re.compile(r"\b(?:preferredDisplayModeId|preferredRefreshRate)\s*(?:[+\-*/%]=|\+\+|--|=(?!=))|(?<![\w)\]])(?:\+\+|--)[ \t]*(?:[\w?]+[ \t]*\.[ \t]*)*(?:preferredDisplayModeId|preferredRefreshRate)\b"), re.compile(r"((?:\?\.|\.)\s*animate\s*\(\s*\)(?:\s*(?:\?\.|\.)\s*(?:alpha|alphaBy|scaleX|scaleY|translationX|translationY|x|y|rotation|setDuration|setStartDelay)\s*\([\w.\s+\-*/]*\))*\s*(?:\?\.|\.)\s*)start(\s*\(\s*\))")
COSMETIC_WORDS, STARTUP_WORDS = {"fade", "spinner", "toast", "tooltip", "blink", "ripple", "ime"}, {"start", "starting", "launch", "open", "opening", "session", "ready", "readiness", "desktop", "x11", "lorie", "surface", "connect", "connected", "profile", "handoff"}
SMALLEST_SW_DP, ACTIVE_POINTER_TRACKER, INPUT_EVENT_SENDER, READINESS_GATE, IMPORT_LINE = re.compile(r"\bsmallestScreenWidthDp\b"), re.compile(r"\bActivePointerTracker\b"), re.compile(r"\bInputEventSender\b"), re.compile(r"\bDesktopReadinessGate\b"), re.compile(r"(?m)^\s*import\b[^\n]*")
_THIS_PFX, _NEXT_DECL = r"(?:(?:\w+\s*\.\s*)?this(?:\s*@\s*\w+)?\s*\.\s*)", re.compile(r"[ \t]*\n\s*(?:private\b|internal\b|public\b|protected\b|override\b|companion\b|class\b|object\b|val\b|var\b|fun\b|\}|\Z)")

def _blank(chunk: str) -> str: return "".join("\n" if c == "\n" else " " for c in chunk)
def line_no(text: str, offset: int) -> int: return text.count("\n", 0, offset) + 1
def _uncalled_kt(code_only: str, fn: str, sp: tuple[int, int]) -> bool: return not re.search(rf"\b{re.escape(fn)}\b", code_only[: sp[0]] + code_only[sp[1] :])
def _ident_words(text: str) -> list[str]: return [w.lower() for w in re.findall(r"[A-Z]+(?=[A-Z][a-z]|\b)|[A-Z]?[a-z0-9]+", text)]
def balanced_end(text: str, open_idx: int, open_ch: str, close_ch: str) -> int | None:
    depth = 0
    for idx in range(open_idx, len(text)):
        if (depth := depth + (text[idx] == open_ch) - (text[idx] == close_ch)) == 0: return idx + 1
    return None
def lex_source(text: str, suffix: str) -> tuple[str, str]:
    nc, co, n, delims = [], [], len(text), (('"""', "'''", '"', "'") if suffix == ".dart" else ('"""', '"', "'"))
    def emit(chunk: str, keep_nc: bool, keep_co: bool = False) -> None: nc.append(chunk if keep_nc else _blank(chunk)); co.append(chunk if keep_co else _blank(chunk))
    def scan_string(idx: int, quote: str, raw: bool, interp: bool) -> int:
        emit(quote, True); idx += len(quote)
        while idx < n:
            if len(quote) == 3 and text.startswith(quote, idx):
                end = idx + 3
                while suffix == ".kt" and quote == '"""' and end < n and text[end] == '"': end += 1
                emit(text[idx:end], True); return end
            if len(quote) == 1 and text[idx] in (quote, "\n"): emit(text[idx], True); return idx + 1
            if not raw and text[idx] == "\\" and idx + 1 < n: emit(text[idx : idx + 2], True); idx += 2
            elif interp and text.startswith("${", idx):
                emit("${", True); idx = scan_code(idx + 2, True)
                if idx < n and text[idx] == "}": emit("}", True); idx += 1
            elif interp and text[idx] == "$" and idx + 1 < n and (text[idx + 1].isalpha() or text[idx + 1] == "_"):
                emit("$", True); start = idx = idx + 1
                while idx < n and (text[idx].isalnum() or text[idx] == "_"): idx += 1
                emit(text[start:idx], True, True)
            else: emit(text[idx], True); idx += 1
        return idx
    def scan_code(idx: int, in_interp: bool = False) -> int:
        brace = 1
        while idx < n:
            if in_interp and text[idx] in "{}":
                if (brace := brace + (1 if text[idx] == "{" else -1)) == 0: return idx
                emit(text[idx], True, True); idx += 1
            elif text.startswith("//", idx): end = n if (e := text.find("\n", idx)) == -1 else e; emit(text[idx:end], False); idx = end
            elif text.startswith("/*", idx):
                emit("/*", False); idx, depth, nested = idx + 2, 1, suffix in {".kt", ".dart"}
                while idx < n and depth > 0:
                    step = 2 if (nested and text.startswith("/*", idx)) or text.startswith("*/", idx) else 1
                    depth += 1 if step == 2 and text.startswith("/*", idx) else (-1 if step == 2 else 0); emit(text[idx : idx + step], False); idx += step
            elif suffix == ".dart" and text[idx] == "r" and (idx == 0 or not (text[idx - 1].isalnum() or text[idx - 1] == "_")) and (q := next((d for d in delims if text.startswith(d, idx + 1)), None)): emit("r", True, True); idx = scan_string(idx + 1, q, True, False)
            elif q := next((d for d in delims if text.startswith(d, idx)), None): idx = scan_string(idx, q, suffix == ".kt" and q == '"""', suffix == ".dart" or (suffix == ".kt" and q != "'"))
            else: emit(text[idx], True, True); idx += 1
        return idx
    scan_code(0); return "".join(nc), "".join(co)
def _shallow_text(s: str) -> str:
    out, idx = [], 0
    while idx < len(s): end = balanced_end(s, idx, "{", "}") if s[idx] == "{" else None; out.append(_blank(s[idx:end]) if end else s[idx]); idx = end or idx + 1
    return "".join(out)
def _uncond_text(s: str) -> str:
    sh, buf = _shallow_text(s), list(_shallow_text(s))
    stmt_end = lambda pos: (ws := pos + len(s[pos:]) - len(s[pos:].lstrip())) + (er := len(_rhs_expr(sh[ws:]))) + (1 if ws + er < len(sh) and sh[ws + er] == ";" else 0)
    for m in re.finditer(r"\b(?:if|for|while)\s*\(", sh):
        if pend := balanced_end(sh, m.end() - 1, "(", ")"): se = pend if s[pend:].lstrip().startswith("{") else stmt_end(pend); buf[m.start() : se] = list(_blank(sh[m.start() : se]))
    for m in re.finditer(r"\belse\b", "".join(buf)):
        if not s[m.end() :].lstrip().startswith("{"): se = stmt_end(m.end()); buf[m.start() : se] = list(_blank(sh[m.start() : se]))
    for m in re.finditer(r"(?:->|\?|&&|\|\||\?:)\s*\(", "".join(buf)):
        if pend := balanced_end("".join(buf), m.end() - 1, "(", ")"): buf[m.start() : pend] = list(_blank(sh[m.start() : pend]))
    for m in re.finditer(r"(?:->|\?|&&|\|\||\?:)[^;\n]*", "".join(buf)): buf[m.start() : m.end()] = list(_blank(sh[m.start() : m.end()]))
    return "".join(buf)
def _split_top_commas(s: str) -> list[str]:
    parts, cur, depth = [], [], 0
    for ch in s: depth += (ch in "([{<") - (ch in ")]}>"); parts.append("".join(cur)) or cur.clear() if ch == "," and depth <= 0 else cur.append(ch)
    return [*parts, "".join(cur)]
def _flatten_paren_nl(s: str) -> str:
    out, depth = [], 0
    for ch in s: depth = 0 if ch in ";{}" else max(0, depth + (ch == "(") - (ch == ")")); out.append(" " if ch == "\n" and depth > 0 else ch)
    return "".join(out)
def _rhs_expr(s: str) -> str:
    start = i = len(s) - len(s.lstrip()); depth = 0
    while i < len(s) and not (depth == 0 and (s[i] in ";,})" or (s[i] == "\n" and not (s[start:i].rstrip().endswith(("+", "-", "*", "/", "%", "?", ":", ".", "=", "&&", "||")) or s[i + 1 :].lstrip().startswith((".", "?.", "!!", "?:", "?", ":", "+", "-", "*", "/", "%", "&&", "||")))))): depth = max(0, depth + (s[i] in "([{") - (s[i] in ")]}")); i += 1
    return s[start:i]
def _decl_map_in(sh: str, base: int) -> dict[str, int]:
    out: dict[str, int] = {}
    for m in re.finditer(r"\b(?:val|var)\s+(?:([A-Za-z_]\w*)|\(([^)=;{}\n]+)\))", sh):
        end = base + m.end() + (em.end() + len(_rhs_expr(sh[m.end() + em.end() :])) if (em := re.match(r"(?:\s*:\s*[\w<>?.*, \t]+)?\s*=(?!=)", sh[m.end() :])) else 0)
        for d in ([m.group(1)] if m.group(1) else re.findall(r"\b([A-Za-z_]\w*)\b", re.sub(r":\s*[\w<>?.*]+", "", m.group(2)))): out.setdefault(d, end) if d != "_" else None
    for m in re.finditer(r"(?<![.\w])(?:int|long|float|double|boolean|short|byte|(?:com\.orailnoor\.droiddesk\.[\w.]*\.)?[A-Z]\w*(?:\s*<[^>{};\n]*>)?)(?:\s*\[\s*\])*\s+([^;{}\n]+)", sh):
        cur = base + m.start(1)
        for p in _split_top_commas(m.group(1)):
            if (dm := re.match(r"\s*([A-Za-z_]\w*)(?!\s*\()", p)) and dm.group(1) not in {"_", "in", "is", "as"}: out.setdefault(dm.group(1), cur + len(p))
            cur += len(p) + 1
    return out
def _scoped_taint(src: str, seed_re: re.Pattern[str], extra_root: set[str] = frozenset(), param_type_re: str = ""):
    top_m, loops, uncond_all = _decl_map_in(_shallow_text(src), 0), [], _uncond_text(src)
    scopes: list[tuple[int, int, dict[str, int], bool, str, str]] = [*( [(0, len(src), {k: 0 for k in set(extra_root) - set(top_m)}, False, "", "")] if set(extra_root) - set(top_m) else [] ), *( [(0, len(src), {k: 0 for k in top_m}, True, "", _shallow_text(src))] if top_m else [] )]
    for lm in re.finditer(r"\b(?:for|while)\s*\(", src):
        if lend := balanced_end(src, lm.end() - 1, "(", ")"):
            hdr, ws = src[lm.end() : lend - 1], len(src[lend:]) - len(src[lend:].lstrip())
            loops.append((lm.start(), le := (balanced_end(src, lend + ws, "{", "}") if src.startswith("{", lend + ws) else (src.find(";", lend) + 1 or len(src))) or len(src)))
            if lm.group(0).startswith("for") and (fm_d := {k: v for k, v in ({**_decl_map_in(hdr.split(":")[0].split(";")[0], lm.end()), **({k: lend for k in re.findall(r"\b([A-Za-z_]\w*)\b", re.sub(r":\s*[\w<>?.*]+", "", hdr.split(" in ", 1)[0]))} if " in " in hdr else {})}).items() if k not in {"val", "var", "final", "_"}}): scopes.append((lm.start(), le, fm_d, False, "", hdr))
    for fm in re.finditer(r"\bfun\b[^{;=(]*\(", src):
        if (pend := balanced_end(src, fm.end() - 1, "(", ")")) and (em := re.match(r"\s*(?::[^={;]+)?=(?!=)", src[pend:])) and (pvars := set(re.findall(r"\b([A-Za-z_]\w*)\s*:(?!=)", src[fm.end() : pend - 1])) - {"_"}):
            scopes.append((pend + em.end(), min(nd.start() if (nd := _NEXT_DECL.search(src, pend + em.end())) else len(src), min((balanced_end(src, bm.start(), "{", "}") or len(src) for bm in re.finditer(r"\{", src[: fm.start()]) if (balanced_end(src, bm.start(), "{", "}") or len(src)) > fm.start()), default=len(src))), {k: pend + em.end() for k in pvars}, False, "", src[fm.end() : pend - 1]))
    for bm in re.finditer(r"\{", src):
        bend, pre240, sh_buf = balanced_end(src, bm.start(), "{", "}") or len(src), src[max(0, bm.start() - 240) : bm.start()], list(_shallow_text(src[bm.end() : (balanced_end(src, bm.start(), "{", "}") or len(src)) - 1]))
        is_type, tname = bool(_TYPE_HDR.search(pre240)), (tm.group(1) if (tm := re.search(r"(?<![:.])\b(?:class|interface|enum|object)\s+([A-Za-z_]\w*)[^{;]*$", pre240)) else "")
        for fm in re.finditer(r"\bfor\s*\(", "".join(sh_buf)): fe = balanced_end("".join(sh_buf), fm.end() - 1, "(", ")") or fm.end(); sh_buf[fm.start() : fe] = list(_blank("".join(sh_buf)[fm.start() : fe]))
        sh_str, pre, hdr_chunks, bmap = "".join(sh_buf), src[max(0, bm.start() - 600) : bm.start()], [], {k: (bm.start() if is_type else pos) for k, pos in _decl_map_in("".join(sh_buf), bm.end()).items()}
        if pm := re.search(r"\b(?:fun|constructor|catch|void|boolean|int|long|float|double|Object|String|[A-Z]\w*)\b[^{;=(]*\(([\s\S]*)\)\s*(?::[^{;=]+|\s*throws[^{;=]+)?\s*$", pre): hdr_chunks.append(pm.group(1)); bmap.update({a or b: bm.start() for a, b in re.findall(r"\b([A-Za-z_]\w*)\s*:(?!=)|[\w.$\[\]<>?]+\s+([A-Za-z_]\w*)\s*(?=[,)]|$)", pm.group(1)) if (a or b) != "_"})
        if jlm := re.search(r"\(([^()]*)\)\s*->\s*$", pre): hdr_chunks.append(jlm.group(1)); bmap.update({k: bm.start() for k in re.findall(r"\b([A-Za-z_]\w*)\s*(?=[,)]|$)", jlm.group(1)) if k != "_"})
        if (ar := src.find("->", bm.end(), min(bend, bm.end() + 200))) != -1 and not any(c in src[bm.end() : ar] for c in "{};=") and not _is_when_brace(src, bm.start()): hdr_chunks.append(src[bm.end() : ar]); bmap.update({k: bm.start() for k in re.findall(r"\b([A-Za-z_]\w*)\b", re.sub(r":\s*[\w<>?.*]+", "", src[bm.end() : ar])) if k != "_"})
        if bmap: scopes.append((bm.start(), bend, bmap, is_type, tname, "\n".join([*hdr_chunks, sh_str])))
    scopes.sort(key=lambda t: (t[0], -(t[1] - t[0])))
    bindings, p_seeded = [], set()
    for s0, s1, dmap, is_type, tname, decl_hdr in scopes:
        uc_blk = (" " + _uncond_text(src[s0 + 1 : s1 - 1]) + " ") if src.startswith("{", s0) else uncond_all[s0:s1]
        for v, _ in sorted(dmap.items()):
            lv, tv = list(src[s0:s1]), list(src[s0:s1]) if is_type else []
            for c0, c1, cmap, c_type, _, _ in scopes:
                if (c0, c1) != (s0, s1) and s0 <= c0 and c1 <= s1 and v in cmap:
                    ms = max(s0, cmap[v]); lv[ms - s0 : c1 - s0] = list(_blank(src[ms:c1]))
                    if is_type and c_type: tv[c0 - s0 : c1 - s0] = list(_blank(src[c0:c1]))
            if param_type_re and decl_hdr and re.search(rf"(?:\b{re.escape(v)}\s*:\s*{param_type_re}|{param_type_re}\s+{re.escape(v)}\b)", decl_hdr) and not (s0 == 0 and v in extra_root): p_seeded.add(len(bindings))
            bindings.append((s0, s1, v, "".join(lv), "".join(tv), uc_blk, is_type, tname))
    this_pats = lambda s0, s1, v, tvs, is_type, tname: (((1, tvs, rf"(?<!\.\s)(?<!\.)\bthis\s*\.\s*{re.escape(v)}\b"), *(((2, src[s0:s1], rf"(?:\b{re.escape(tname)}\s*\.\s*this|\bthis\s*@\s*{re.escape(tname)})\s*\.\s*{re.escape(v)}\b"),) if tname else ())) if is_type else ())
    asgns = [(bi, m.start(), _rhs_expr(slc[m.end() :]), True if kind else sh_s[m.start()] != " ") for bi, (s0, s1, v, lvs, tvs, sh_s, is_type, tname) in enumerate(bindings) for kind, slc, pat in ((0, lvs, rf"(?<![.\w]){re.escape(v)}\b(?:\s*:\s*[\w<>?.*, \t]+)?"), *this_pats(s0, s1, v, tvs, is_type, tname)) for m in re.finditer(rf"{pat}\s*=(?!=)", slc)]
    tainted = {ai for ai, (_, _, rhs, _) in enumerate(asgns) if seed_re.search(rhs)}
    def is_tainted_at(bi: int, pos: int, kind: int = 0) -> bool:
        s0, s1, v, lvs, tvs, _, is_type, _ = bindings[bi]
        if bi in p_seeded and kind == 0: return s0 <= pos < s1 and lvs[pos - s0] != " "
        if kind or is_type: return (is_type if kind else True) and s0 <= pos < s1 and (src[pos] if kind == 2 else (tvs if kind == 1 else lvs)[pos - s0]) != " " and (bi in p_seeded or (s0 == 0 and v in extra_root and not any(b_i == bi for b_i, *_ in asgns)) or any(ai in tainted for ai, (b_i, *_) in enumerate(asgns) if b_i == bi))
        if not (s0 <= pos < s1 and lvs[pos - s0] != " "): return False
        rel, sh_before = pos - s0, [(u, ai in tainted) for ai, (b_i, u, _, is_sh) in enumerate(asgns) if b_i == bi and is_sh and u < pos - s0]
        last_u, last_t = sh_before[-1] if sh_before else (-1, s0 == 0 and v in extra_root)
        lim = rel if last_u > max((l0 - s0 for l0, l1 in loops if s0 < l0 <= pos < l1), default=-1) else max([rel, *(l1 - s0 for l0, l1 in loops if s0 < l0 <= pos < l1)])
        return last_t or any(last_u < u < lim for ai, (b_i, u, *_) in enumerate(asgns) if b_i == bi and ai in tainted)
    for _ in range(4):
        cur = {bi for bi, (s0, _, v, *_) in enumerate(bindings) if bi in p_seeded or (s0 == 0 and v in extra_root)} | {asgns[ai][0] for ai in tainted}
        tainted.update(ai for ai, (bi, u, rhs, _) in enumerate(asgns) if ai not in tainted and any(bindings[pbi][2] in rhs and is_tainted_at(pbi, bindings[bi][0] + u, k) and re.search(pt, rhs) for pbi in cur for k, _, pt in ((0, bindings[pbi][3], rf"(?<![.\w]){re.escape(bindings[pbi][2])}\b"), *this_pats(bindings[pbi][0], bindings[pbi][1], bindings[pbi][2], bindings[pbi][4], bindings[pbi][6], bindings[pbi][7]))))
    return bindings, {bi for bi, (s0, _, v, *_) in enumerate(bindings) if bi in p_seeded or (s0 == 0 and v in extra_root) or any(ai in tainted for ai, (b_i, *_) in enumerate(asgns) if b_i == bi)}, is_tainted_at, this_pats
def find_fixed_pointers(code_only: str) -> list[tuple[int, str]]:
    buf = list(code_only)
    for m in _DENSE_CALL.finditer(code_only):
        end, inner = balanced_end(code_only, m.end() - 1, "(", ")") or len(code_only), code_only[m.end() : (balanced_end(code_only, m.end() - 1, "(", ")") or len(code_only)) - 1]
        if "[" not in inner and not FIXED_POINTER.search(inner) and not re.search(r"(?:>=|<|<=|>)\s*(?:10|9)\b|\b(?:10|9)\s*(?:>=|<|<=|>)", inner): buf[m.start() : end] = ["0", *list(_blank(code_only[m.start() + 1 : end]))]
    clean = "".join(buf); hits = {(m.start(), m.group(0).strip()) for m in FIXED_POINTER.finditer(clean)}
    for sm in re.finditer(r"\[", clean):
        if re.search(r"\bgetPointerId\s*\(", clean[sm.start() : (send := balanced_end(clean, sm.start(), "[", "]") or len(clean))]): hits.add((sm.start(), code_only[sm.start() : send].strip()))
    bindings, active_bis, is_tainted_at, this_pats = _scoped_taint(clean, re.compile(r"\bgetPointerId\s*\("), {"pointerId"})
    for bi in active_bis:
        s0, s1, v, lvs, tvs, _, is_type, tname = bindings[bi]
        for kind, slc, tok in ((0, lvs, rf"(?<![.\w]){re.escape(v)}\b"), *this_pats(s0, s1, v, tvs, is_type, tname)): hits.update((s0 + m.start(), code_only[s0 + m.start() : s0 + m.end()].strip()) for m in re.finditer(rf"\[\s*[^\[\]]*{tok}[^\[\]]*\]|{tok}\s*(?:>=|<|<=|>)\s*(?:10|9)\b|(?:10|9)\s*(?:>=|<|<=|>)\s*{tok}", slc) if is_tainted_at(bi, s0 + m.start(), kind))
    return sorted(hits)
def _rate_comparisons(code_only: str) -> list[tuple[int, str]]:
    if not re.search(rf"\b{_RATE_BASE}", code_only): return []
    flat, hits = _flatten_paren_nl(code_only), {}
    mid = rf"(?:(?![;{{}},]|&&|\|\||\?(?![.:])|(?<![=!<>+\-*/%])=(?!=)|\b(?:val|var|fun|return|if|when|for|while|private|public|internal|and|or)\b|\b(?:int|long|float|double)\b(?!\s*\)))(?:[^\n]|\n\s*(?=(?:\.|\?\.|!!|{_CMP_OP}))))"
    def scan_tok(base: int, seg: str, r_tok: str, pred=None) -> None:
        bi_cmp = rf"\b(?:compare|equals)\s*\({mid}{{0,40}}?(?:({r_tok}){mid}{{0,20}}?,\s*{mid}{{0,20}}?{_TARGET_HZ}|{_TARGET_HZ}{mid}{{0,20}}?,\s*{mid}{{0,20}}?({r_tok}))"
        for m in re.finditer(rf"({r_tok}){mid}{{0,50}}?{_CMP_OP}\s*{mid}{{0,40}}?{_TARGET_HZ}|{_TARGET_HZ}{mid}{{0,40}}?{_CMP_OP}\s*{mid}{{0,50}}?({r_tok})|{bi_cmp}", seg):
            rp = next((m.start(g) for g in range(1, (m.lastindex or 0) + 1) if m.group(g) is not None), m.start())
            if pred is None or pred(base + rp) or pred(base + m.end() - 1): hits.setdefault(base + m.start(), m.group(0).strip())
    scan_tok(0, flat, rf"\b{_RATE_BASE}")
    bindings, active_bis, is_tainted_at, this_pats = _scoped_taint(flat, re.compile(rf"\b{_RATE_BASE}"))
    for bi in active_bis:
        s0, s1, v, lvs, tvs, _, is_type, tname = bindings[bi]
        for kind, slc, tok in ((0, lvs, rf"(?<![.\w]){re.escape(v)}\b"), *this_pats(s0, s1, v, tvs, is_type, tname)): scan_tok(s0, slc, tok, lambda p, b=bi, k=kind: is_tainted_at(b, p, k))
    return sorted(hits.items())
def find_refresh_selections(rel: str, code_only: str) -> list[tuple[int, str]]:
    hits, masked, allow_pats = _rate_comparisons(code_only), list(code_only), []
    if rel == PRESENTATION_EVIDENCE: allow_pats.extend((rf"(?:\bval\s+)?\bsupportedModes\s*:\s*List\s*<\s*DisplayModeEvidence\s*>(?:\s*=\s*this\s*\.\s*supportedModes\b{_NO_CHAIN})?", rf"\bHashSet\s*<\s*Int\s*>\s*\(\s*supportedModes\s*\.\s*size\s*\){_NO_CHAIN}", rf"\bsupportedModes\s*\.\s*all\s*\{{\s*\w+\s*\.\s*add\s*\(\s*\w+\s*\.\s*modeId\s*\)\s*\}}{_NO_CHAIN}", rf"\bsupportedModes\s*=\s*(?:supportedModes\b|Collections\s*\.\s*unmodifiableList\s*\(\s*ArrayList\s*\(\s*supportedModes\s*\)\s*\)){_NO_CHAIN}"))
    elif rel == PRESENTATION_POLICY: allow_pats.append(rf"\brefreshPolicy\s*\([^(),\[]*,\s*[\w?]+\s*\.\s*supportedModes\s*\){_NO_CHAIN}")
    elif rel == EVIDENCE_PROBE:
        allow_pats.extend((r"\bval\s+supportedModes\s*:\s*List\s*<\s*DisplayModeEvidence\s*>", rf"\bsupportedModes\s*=\s*snapshot\s*\.\s*supportedModes\b{_NO_CHAIN}"))
        for m in re.finditer(rf"(?:\bsupportedModes\s*=\s*|\bfun\s+([A-Za-z_]\w*)\s*\(\s*\)\s*=\s*)[\w?]+\s*(?:\?\.|\.)\s*supportedModes\s*\.\s*map\s*\{{\s*\w+\s*\.\s*toEvidence\s*\(\s*\)\s*\}}{_NO_CHAIN}", code_only):
            if not m.group(1) or _uncalled_kt(code_only, m.group(1), m.span()): masked[m.start() : m.end()] = list(_blank(code_only[m.start() : m.end()]))
    elif rel == DESKTOP_ACTIVITY:
        allow_pats.append(rf"\bDisplayRefreshPolicy\s*\.\s*resolve\s*\([^(),\[]*,\s*[\w?]+\s*\.\s*supportedModes\s*\){_NO_CHAIN}")
        map_pat, ctx_spans = rf"(?:\bval\s+([A-Za-z_]\w*)\s*=\s*|\bfun\s+([A-Za-z_]\w*)\s*\(\s*\)\s*=\s*)[\w?]+\s*(?:\?\.|\.)\s*supportedModes\s*(?:\?\.|\.)\s*mapNotNull\s*\{{\s*[\w?]+\s*(?:\?\.|\.)\s*toEvidenceOrNull\s*\(\s*\)\s*\}}(?:\s*(?:\?\.|\.)\s*distinctBy\s*\{{\s*\w+\s*\.\s*modeId\s*\}})?(?:\s*(?:\?\.|\.)\s*orEmpty\s*\(\s*\))?{_NO_CHAIN}", [(cm.start(), balanced_end(code_only, cm.end() - 1, "(", ")") or 0) for cm in re.finditer(r"\bPresentationContext\s*\(", code_only)]
        for m in re.finditer(map_pat, code_only):
            a, fn, outside = m.group(1), m.group(2), code_only[: m.start()] + (" " * (m.end() - m.start())) + code_only[m.end() :]
            if (_uncalled_kt(code_only, fn, m.span()) if fn else True) and (not a or all(any(s <= u.start() < e for s, e in ctx_spans) and not re.match(r"\s*(?:\?\.|\.|!!|::|\[|\()", outside[u.end() :]) for u in re.finditer(rf"\b{re.escape(a)}\b", outside))): masked[m.start() : m.end()] = list(_blank(code_only[m.start() : m.end()]))
    elif rel.endswith(".java"):
        for m in re.finditer(r"\b(?:Object|(?:[\w.]+\.)?Mode\s*\[\s*\])\s+([A-Za-z_]\w*)\s*\([^(){}]*\)\s*\{[^{}]*?(\breturn\s+[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*(?:\s*\(\s*\))?)*\s*\.\s*getSupportedModes\s*\(\s*\)\s*;)\s*\}", code_only):
            if _uncalled_kt(code_only, m.group(1), m.span()): masked[m.start(2) : m.end(2)] = list(_blank(code_only[m.start(2) : m.end(2)]))
    for pat in allow_pats:
        for m in re.finditer(pat, code_only): masked[m.start() : m.end()] = list(_blank(code_only[m.start() : m.end()]))
    return [*hits, *((m.start(), m.group(0).strip()) for m in _MODES_RAW.finditer("".join(masked)))]
def allowed_refresh_assigns(code_only: str) -> set[int]:
    allowed, rhs_end = set(), r"(?=[ \t]*(?:[;}]|$|\n\s*(?!\s|\.|\?\.|!!|\?:|[+\-*/%<>=&|\[\(]|(?:as|is|in|and|or|xor|shl|shr|ushr|until|downTo|step|coerce\w*)\b)))"
    for match in re.finditer(r"\bapplyRefreshRequest\s*\(", code_only):
        if re.search(r"\bfun\s+$", code_only[max(0, match.start() - 20) : match.start()]) or (arg_end := balanced_end(code_only, match.end() - 1, "(", ")")) is None: continue
        rest = code_only[arg_end:]
        brace_idx = (arg_end + (ws := len(rest) - len(rest.lstrip()))) if rest.startswith("{", len(rest) - len(rest.lstrip())) else None
        body = code_only[brace_idx + 1 : body_end - 1] if brace_idx is not None and (body_end := balanced_end(code_only, brace_idx, "{", "}")) is not None else ""
        if (header := re.match(r"\s*([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?\s*,\s*([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?\s*->", body)) and "->" not in (inner := body[header.end() :]) and not re.search(r"\b(?:fun|for|catch)\b", inner):
            base, non_recv_brace = brace_idx + 1 + header.end(), bool(re.search(r"\{", re.sub(r"\b(?:apply|run|with|try|if|else)\b\s*(?:\([^){}]*\)\s*)?\{", "", inner)))
            for prop, param in (("preferredRefreshRate", header.group(1)), ("preferredDisplayModeId", header.group(2))):
                p = re.escape(param)
                if param != "_" and not (param == "it" and non_recv_brace) and not re.search(rf"\b(?:val|var)\s+(?:\([^)=;{{}}]*\b{p}\b|\b{p}\b)", inner):
                    allowed.update(base + a.start() for a in re.finditer(rf"\b{prop}\s*=(?!=)\s*{p}\b{rhs_end}", inner))
    return allowed
def _is_cosmetic_ident(ident: str) -> bool: w = _ident_words(ident); return bool(COSMETIC_WORDS.intersection(w) or {("loading", "message"), ("loading", "estimate"), ("mouse", "down"), ("log", "flush"), ("flush", "log")}.intersection(set(zip(w, w[1:]))))
def _is_when_brace(code_only: str, brace_idx: int) -> bool:
    pre, depth = code_only[:brace_idx].rstrip(), 0
    for idx in range(len(pre) - 1, -1, -1) if pre.endswith(")") else ():
        if (depth := depth + (pre[idx] == ")") - (pre[idx] == "(")) == 0: return bool(re.search(r"\bwhen\s*$", pre[:idx]))
    return bool(re.search(r"\bwhen\s*$", pre))
def _decl_bodies(code_only: str, name: str) -> list[str] | None:
    p = re.escape(name)
    if re.search(rf"\bvar\s+{p}\b|\b(?:val|var)\s*\([^)=;{{}}]*\b{p}\b|\bfor\s*\([^)]*\b{p}\b[^)]*(?::|\bin\b)|\bRunnable\s+{p}\s*[,)]|(?<!:\s)(?:(?<![.\w])|\([^()=:]*\b){p}\s*\)?\s*->", code_only) or len(re.findall(rf"\b{p}\b(?:[ \t]*:[ \t]*(?:\([^)\n]*\)[ \t]*->[ \t]*)?[\w<>?., \t]+)?[ \t]*=(?!=)", code_only)) > 1: return None
    if any(re.search(rf"\b{p}\s*:(?!=)|\bRunnable\s+{p}\b", code_only[pm.end() : (pend - 1) if (pend := balanced_end(code_only, pm.end() - 1, "(", ")")) else len(code_only)]) for pm in re.finditer(r"\b(?:fun|constructor|class|catch)\b[^{;=(]*\(", code_only)): return None
    if any((arrow := code_only.find("->", bm.end(), bm.end() + 240)) != -1 and not any(c in (seg := code_only[bm.end() : arrow]) for c in "{};=") and not re.search(r"\b(?:val|var|fun|Runnable)\b", seg) and not _is_when_brace(code_only, bm.start()) and re.search(rf"\b{p}\b", seg) for bm in re.finditer(r"\{", code_only)): return None
    bodies = []
    for m in re.finditer(rf"\b(?:val|fun(?:\s*<[^>]+>)?(?:\s+[\w?]+\s*\.)?|(?:java\.lang\.)?Runnable|void|boolean|int|long|float|double|Object|String)\s+{p}\b(?:[ \t]*\([^)]*\))?(?:[ \t]*:[ \t]*(?:\([^)\n]*\)[ \t]*->[ \t]*)?[\w<>?., \t]+)?([ \t]*=\s*(?:(?:object\s*:\s*|new\s+)?(?:java\.lang\.)?Runnable(?:\s*\(\s*\))?\s*|\(\s*\)\s*->\s*)?)?[ \t]*", code_only):
        if (tail := m.end()) < len(code_only) and code_only[tail] == "{":
            if (end := balanced_end(code_only, tail, "{", "}")) is None: return None
            bodies.append(code_only[tail:end])
        elif m.group(1) and "{" not in (lb := code_only[tail : len(code_only) if (nl := code_only.find("\n", tail)) == -1 else nl]) and (nl == -1 or _NEXT_DECL.match(code_only, nl)): bodies.append(lb)
        else: return None
    return bodies
def _norm_delay(expr: str) -> str:
    depth, s = 0, expr
    for idx, ch in enumerate(expr):
        if (depth := depth + (ch == "(") - (ch == ")")) < 0: s = expr[:idx]; break
    s, changed = s.strip(), True
    while changed:
        s, changed = (s[1:-1].strip(), True) if s.startswith("(") and balanced_end(s, 0, "(", ")") == len(s) else (s, False)
        if m := re.match(r"^(?:\(\s*(?:long|int)\s*\)\s*(.+)|(.+?)\s*\.\s*to(?:Long|Int)\s*\(\s*\))$", s): s, changed = (m.group(1) or m.group(2)).strip(), True
    return s
def find_250ms_post_delayed(code_only: str) -> list[int]:
    if not re.search(r"\bpostDelayed\s*\(", code_only): return []
    hits, lit_250, qual_ident = [], re.compile(r"(?i)250(?:_?l)?|0x0*fal?"), re.compile(r"[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*")
    blocks = [(bm.start(), balanced_end(code_only, bm.start(), "{", "}") or len(code_only)) for bm in re.finditer(r"\{", code_only)]
    loops = [(lm.start(), (balanced_end(code_only, lend + ws, "{", "}") if code_only.startswith("{", lend + ws) else (code_only.find(";", lend) + 1 or code_only.find("\n", lend + ws) + 1 or len(code_only))) or len(code_only)) for lm in re.finditer(r"\b(?:for|while)\s*\(", code_only) if (lend := balanced_end(code_only, lm.end() - 1, "(", ")")) for ws in (len(code_only[lend:]) - len(code_only[lend:].lstrip()),)]
    def decl_scope(pos: int) -> tuple[int, int, tuple[str, ...]]:
        if not (enc := [b for b in blocks if b[0] < pos < b[1]]): return (0, len(code_only), ())
        op, cl, pre = *enc[-1], code_only[max(0, enc[-1][0] - 160) : enc[-1][0]]
        if re.search(r"\bcompanion\s+object\b[^{;]*$", pre) and len(enc) >= 2:
            cm, pm = re.search(r"\bcompanion\s+object\s+([A-Za-z_]\w*)\s*$", pre), re.search(r"(?<![:.])\b(?:class|object)\s+([A-Za-z_]\w*)[^{;]*$", code_only[max(0, enc[-2][0] - 160) : enc[-2][0]])
            return (*enc[-2], tuple(x for x in (cm.group(1) if cm else "", pm.group(1) if pm else "") if x))
        return (op, cl, (tm.group(1),) if (tm := re.search(r"(?<![:.])\b(?:class|object)\s+([A-Za-z_]\w*)[^{;]*$", pre)) else ())
    fun_params = []
    for fm in re.finditer(r"\bfun\b[^{;=(]*\(", code_only):
        if not (pend := balanced_end(code_only, fm.end() - 1, "(", ")")): continue
        if bm := re.match(r"\s*(?::[^{;=]+)?\{", code_only[pend:]): bs, be = pend + bm.end() - 1, balanced_end(code_only, pend + bm.end() - 1, "{", "}") or len(code_only)
        elif em := re.match(r"\s*(?::[^={;]+)?=(?!=)", code_only[pend:]): bs, be = pend + em.end(), min(nd.start() if (nd := _NEXT_DECL.search(code_only, pend + em.end())) else len(code_only), min((b[1] - 1 for b in blocks if b[0] < fm.start() < b[1]), default=len(code_only)))
        else: continue
        fun_params.extend((pm.group(1), bs, be, _norm_delay(pm.group(3) or ""), fm.start()) for pspec in _split_top_commas(code_only[fm.end() : pend - 1]) if (pm := re.match(r"\s*(?:(?:vararg|crossinline|noinline|val|var)\s+)*([A-Za-z_]\w*)\s*:\s*([^=]+?)(?:\s*=(?!=)\s*([\s\S]+))?$", pspec)))
    def shadowed(name: str, outer_s: int, pos: int) -> bool:
        p = re.escape(name)
        if any(pn == name and outer_s < bs <= pos < be and not df for pn, bs, be, df, _ in fun_params): return True
        for op, cl in blocks:
            if outer_s < op < pos < cl and not any(pn == name and bs == op and df for pn, bs, _, df, _ in fun_params):
                sh = _shallow_text(code_only[op + 1 : cl - 1 if _TYPE_HDR.search(code_only[max(0, op - 240) : op]) else pos])
                if re.search(rf"\b(?:val|var)\s+(?:{p}\b|\([^)=;{{}}]*\b{p}\b)", sh) or any(re.match(rf"\s*{p}\b", d) for jm in re.finditer(r"\b(?:int|long|Integer|Long)\s+([^;{}\n]+)", sh) for d in _split_top_commas(jm.group(1))): return True
                if (ar := code_only.find("->", op + 1, min(pos, op + 200))) != -1 and not any(c in code_only[op + 1 : ar] for c in "{};=") and not _is_when_brace(code_only, op) and re.search(rf"\b{p}\b", re.sub(r":\s*[\w<>?.*]+", "", code_only[op + 1 : ar])): return True
                base = max(0, op - 500)
                for pm in re.finditer(r"\b(?:constructor|catch|for)\b[^{;=(]*\(|(?:void|boolean|int|long|float|double|Object|String|[A-Z]\w*)\s+[A-Za-z_]\w*\s*\(|\(", code_only[base:op]):
                    pend, is_jlam = balanced_end(code_only, base + pm.end() - 1, "(", ")"), pm.group(0) == "(" and bool((p2 := balanced_end(code_only, base + pm.end() - 1, "(", ")")) and re.match(r"\s*->\s*$", code_only[p2:op]))
                    if pend and pend <= op and (is_jlam or (pm.group(0) != "(" and not any(c in code_only[pend:op] for c in "{;"))) and re.search(rf"\b{p}\s*:(?!=)|[\w.$\[\]<>?]+\s+{p}\s*(?=[,)]|$)" + (rf"|\b{p}\b" if is_jlam else ""), code_only[base + pm.end() : pend - 1]): return True
        return False
    writes = [(pn, bs, df, bs, be, (), True) for pn, bs, be, df, _ in fun_params if df]
    writes.extend((m.group(1), m.start(), _norm_delay(m.group(2) or ""), *decl_scope(m.start()), True) for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)(?:\s*:\s*[\w<>?., \t]+)?(?:\s+get\s*\(\s*\))?(?:\s*=(?!=)\s*(?:\n\s*)?([^\n;,}]+))?", code_only))
    writes.extend((dm.group(1), jm.start(), _norm_delay(dm.group(2) or ""), *decl_scope(jm.start()), True) for jm in re.finditer(r"\b(?:int|long|Integer|Long)\s+([^;{}\n]+)", code_only) for p in _split_top_commas(jm.group(1)) if (dm := re.match(r"\s*([A-Za-z_]\w*)(?:\s*=(?!=)\s*(.+))?", p)))
    for n, s0, s1, own in sorted({(n, s0, s1, own) for n, _, _, s0, s1, own, _ in writes if s0 > 0}):
        off, sh_blk = (s0 + 1, _uncond_text(code_only[s0 + 1 : s1 - 1])) if code_only[s0] == "{" else (s0, _uncond_text(code_only[s0:s1]))
        for am in re.finditer((rf"{_THIS_PFX}{re.escape(n)}\s*=(?!=)\s*(?:\n\s*)?([^\n;,}}]+)|" if own else "") + rf"(?<![.\w]){re.escape(n)}\s*=(?!=)\s*(?:\n\s*)?([^\n;,}}]+)", code_only[off : s1 - (1 if code_only[s0] == "{" else 0)]):
            if (own and am.group(1) and am.lastindex == 1) or (not re.search(r"\b(?:val|var|int|long|Integer|Long)\b[^;={}\n]*$", code_only[off : off + am.start()]) and not (own and shadowed(n, s0, off + am.start())) and not any(n == pn and bs <= off + am.start() < be and (bs, be) != (s0, s1) for pn, bs, be, _, _ in fun_params)):
                writes.append((n, off + am.start(), _norm_delay(am.group(1) or am.group(am.lastindex or 1)), s0, s1, own, True if own else sh_blk[am.start()] != " "))
    for fm in re.finditer(r"\bfor\s*\(", code_only):
        if (fend := balanced_end(code_only, fm.end() - 1, "(", ")")) and (hm := re.match(r"\s*(?:final\s+)?(?:(?:int|long|Integer|Long|var|val)\s+)?([A-Za-z_]\w*)(?:\s*:\s*[\w?]+)?\s*(?::|\bin\b)([\s\S]*)", code_only[fm.end() : fend - 1])) and any(not m.group(1) for m in re.finditer(r"([+\-*/]\s*)?(?<![.\w])(?:250(?:_?[lL])?|0[xX]0*[fF][aA][lL]?)(?![.\w]|[ \t]*[+\-*/])", hm.group(2))):
            ws = len(code_only[fend:]) - len(code_only[fend:].lstrip())
            writes.append((hm.group(1), fm.start(), "250", fm.start(), (balanced_end(code_only, fend + ws, "{", "}") if code_only.startswith("{", fend + ws) else (code_only.find(";", fend) + 1 or code_only.find("\n", fend + ws) + 1 or len(code_only))) or len(code_only), (), True))
    w_250 = {wi for wi, (_, _, rhs, _, _, _, _) in enumerate(writes) if lit_250.fullmatch(rhs)}
    def binding_250_at(name: str, s0: int, s1: int, own: tuple[str, ...], pos: int) -> bool:
        if own or s0 == 0: return any(wi in w_250 for wi, (wn, _, _, ws0, ws1, wown, _) in enumerate(writes) if (wn, ws0, ws1, wown) == (name, s0, s1, own))
        if not (sh_w := [(wp, wi in w_250) for wi, (wn, wp, _, ws0, ws1, _, is_sh) in enumerate(writes) if (wn, ws0, ws1) == (name, s0, s1) and is_sh and wp <= pos]): return False
        last_wp, last_is = sh_w[-1]
        lim = pos if last_wp > max((l0 for l0, l1 in loops if s0 < l0 <= pos < l1), default=-1) else max([pos, *(l1 for l0, l1 in loops if s0 < l0 <= pos < l1)])
        return last_is or any(last_wp < wp < lim for wi, (wn, wp, _, ws0, ws1, _, _) in enumerate(writes) if (wn, ws0, ws1) == (name, s0, s1) and wi in w_250)
    def is_250_at(pos: int, part: str) -> bool:
        rhs = _norm_delay(re.sub(r"^[A-Za-z_]\w*\s*=\s*", "", part))
        if lit_250.fullmatch(rhs): return True
        if not qual_ident.fullmatch(rhs): return False
        parts, keys = [seg.strip() for seg in rhs.split(".")], {(wn, ws0, ws1, wown) for wn, _, _, ws0, ws1, wown, _ in writes}
        if len(parts) == 1: return any(wn == parts[0] and ws0 <= pos < ws1 and not shadowed(parts[0], ws0, pos) and binding_250_at(wn, ws0, ws1, wown, pos) for wn, ws0, ws1, wown in keys)
        return (any(wn == parts[-1] and ws0 <= pos < ws1 and bool(wown) and binding_250_at(wn, ws0, ws1, wown, pos) for wn, ws0, ws1, wown in keys) if parts[0] in {"this", "Companion"} else (not shadowed(parts[0], -1, pos) and any(wn == parts[-1] and parts[-2] in wown and binding_250_at(wn, ws0, ws1, wown, pos) for wn, ws0, ws1, wown in keys)))
    for _ in range(4): w_250.update(wi for wi, (_, wp, rhs, _, _, _, _) in enumerate(writes) if wi not in w_250 and rhs and is_250_at(wp, rhs))
    unwrap_cb = lambda part: s[m.end() : -1].strip() if (m := re.match(r"^(?:java\.lang\.)?Runnable\s*\(", (s := re.sub(r"^[A-Za-z_]\w*\s*=\s*", "", part).strip()))) and balanced_end(s, m.end() - 1, "(", ")") == len(s) else s
    is_inline_cb = lambda s: balanced_end(s, m.end() - 1, "{", "}") == len(s) if (m := re.match(r"^(?:(?:object\s*:\s*|new\s+)?(?:java\.lang\.)?Runnable(?:\s*\(\s*\))?\s*)?\{", s)) else bool(re.match(r"^\(\s*\)\s*->\s*\S", s))
    ident_re, callee_re = re.compile(r"^(?:[A-Za-z_]\w*\s*=\s*)?(?:::|this::)?([A-Za-z_]\w*)$"), re.compile(r"(?<![.\w])(?:this\s*\.\s*)?([A-Za-z_]\w*)\s*\(|(?<![.\w])(?:this)?::([A-Za-z_]\w*)\b|\b([A-Za-z_]\w*)\s*(?:\?\.|\.)\s*(?:run|invoke)\s*\(")
    skip_calls = {"if", "for", "while", "when", "switch", "catch", "synchronized", "Runnable", "mapOf", "listOf", "setOf", "arrayOf", "emptyList", "emptySet", "checkNotNull", "require", "MethodChannel"}
    for match in re.finditer(r"\bpostDelayed\s*\(", code_only):
        if (end := balanced_end(code_only, match.end() - 1, "(", ")")) is None: continue
        args, paren, brace = [[]], 0, 0
        for ch in code_only[match.end() : end - 1]:
            paren += (ch == "(") - (ch == ")"); brace = max(0, brace + (ch == "{") - (ch == "}")); args.append([]) if ch == "," and paren == 0 and brace == 0 else args[-1].append(ch)
        rest, recv = code_only[end:], (recv_m.group(1) if (recv_m := re.search(r"([A-Za-z_]\w*)\s*(?:\?\.|\.)\s*$", code_only[: match.start()])) else "")
        trail_end = balanced_end(code_only, end + (ws := len(rest) - len(rest.lstrip())), "{", "}") if rest.startswith("{", len(rest) - len(rest.lstrip())) else None
        arg_texts = ["".join(p).strip() for p in args if "".join(p).strip()]
        cb_args = [unwrap_cb(p) for p in [*[p for p in arg_texts if not is_250_at(match.start(), p)], *([code_only[end + ws : trail_end]] if trail_end else [])]]
        cb_ok, cb_idents = bool(cb_args) and all(is_inline_cb(p) or ident_re.match(p) for p in cb_args), [m.group(1) for p in cb_args if (m := ident_re.match(p))]
        resolved = [_decl_bodies(code_only, name) for name in cb_idents]
        chunks, seen, bad_callee = [*cb_args, *(b for g in resolved if g for b in g)], set(cb_idents) | skip_calls, any(not g for g in resolved)
        for _ in range(4):
            callees = {next(g for g in m.groups() if g) for c in chunks for m in callee_re.finditer(c) if not (m.group(1) and re.search(r"\b(?:fun|void)\s+$", c[max(0, m.start() - 20) : m.start()]))} - seen
            seen.update(callees)
            for name in callees:
                if not (res := _decl_bodies(code_only, name)): bad_callee = True
                else: chunks.extend(res)
        if any(is_250_at(match.start(), p) for p in arg_texts) and not (cb_ok and not bad_callee and (_is_cosmetic_ident(recv) or any(_is_cosmetic_ident(c) for c in cb_idents)) and not STARTUP_WORDS.intersection(_ident_words(ANIM_START.sub(r"\1\2", " ".join(chunks))))): hits.append(match.start())
    return hits
def _policy_calls(code_only: str, cls: str, members: str, extra_fn: str = "") -> list[tuple[int, str]]:
    if cls not in code_only and (not extra_fn or extra_fn not in code_only): return []
    raw, qual_cls = re.sub(r"`([A-Za-z_]\w*)`", r" \1 ", code_only), rf"com\.orailnoor\.droiddesk\.[\w.]*\b{cls}\b"
    tnames, prev_n = {cls, *re.findall(rf"\bimport\s+{qual_cls}\s+as\s+([A-Za-z_]\w*)", raw), *(["INSTANCE"] if re.search(rf"\bimport\s+static\s+{qual_cls}\s*\.\s*INSTANCE\b", raw) else [])}, 0
    hits, src = {(m.start(), code_only[m.start() : m.end()].strip()) for m in re.finditer(rf"\bimport\s+(?:static\s+)?{qual_cls}\s*\.\s*(?!INSTANCE\b)(?:\*|(?:{members})\b)", raw)}, re.sub(r"(?<=[\w)])(\s*\.\s*INSTANCE)\b(?!\s*\()", lambda m: _blank(m.group(1)), raw)
    while len(tnames) != prev_n:
        prev_n, tnr = len(tnames), "|".join(sorted((re.escape(x) for x in tnames), key=len, reverse=True))
        tnames.update(re.findall(rf"\btypealias\s+([A-Za-z_]\w*)\s*=\s*(?<!\.)(?:com\.orailnoor\.droiddesk\.[\w.]*\.)?(?:{tnr})\b", src))
    tnr = "|".join(sorted((re.escape(x) for x in tnames), key=len, reverse=True))
    def scan_recv(s0: int, slc: str, tok: str, pred) -> None:
        hits.update((s0 + m.start(), code_only[s0 + m.start() : s0 + m.end()].strip()) for m in re.finditer(rf"{tok}(?:\s*\))*\s*(?:\?\.|\.|::)\s*(?:{members})\b", slc) if pred(s0 + m.start()))
        for sm in re.finditer(rf"\b(?:(with)\s*\(\s*\(*\s*{tok}\s*\)*\s*\)|{tok}(?:\s*\))*\s*(?:\?\.|\.)\s*(?:(run|apply)|(let|also))\b)\s*\{{", slc):
            if not pred(s0 + sm.start()): continue
            body = slc[sm.end() : (balanced_end(slc, sm.end() - 1, "{", "}") or len(slc)) - 1]
            lp = re.escape(lm.group(1) if (lm := re.match(r"\s*([A-Za-z_]\w*)\s*->", body)) else "it")
            hits.update((s0 + sm.end() + m.start(), code_only[s0 + sm.end() + m.start() : s0 + sm.end() + m.end()].strip()) for m in re.finditer(rf"\b{lp}\s*(?:\?\.|\.|::)\s*(?:{members})\b" if sm.group(3) else rf"(?:(?<!\.\s)(?<!\.)(?<![:\w])(?<!::\s)(?<!\bval\s)(?<!\bvar\s)(?<!\bfun\s)(?:{members})\s*\(|(?<![.\w])::\s*(?:{members})\b|\bthis\s*(?:\?\.|\.|::)\s*(?:{members})\b)", body))
    if cls in code_only:
        scan_recv(0, src, qual_cls, lambda _: True)
        bindings, active_bis, is_tainted_at, this_pats = _scoped_taint(src, re.compile(rf"^\(*\s*{qual_cls}\s*\)*$"), tnames, rf"(?<!\.)(?:com\.orailnoor\.droiddesk\.[\w.]*\.)?(?:{tnr})\b")
        for bi in active_bis:
            s0, s1, v, lvs, tvs, _, is_type, tname = bindings[bi]
            for kind, slc, tok in ((0, lvs, rf"(?<![.\w]){re.escape(v)}\b"), *this_pats(s0, s1, v, tvs, is_type, tname)): scan_recv(s0, slc, tok, lambda p, b=bi, k=kind: is_tainted_at(b, p, k))
    if extra_fn and extra_fn in code_only:
        fn_roots = {extra_fn, *re.findall(rf"\bimport\s+com\.orailnoor\.droiddesk\.[\w.]*\b{extra_fn}\s+as\s+([A-Za-z_]\w*)", src)}
        hits.update([*((m.start(), code_only[m.start() : m.end()].strip()) for m in re.finditer(rf"\bimport\s+(?:static\s+)?com\.orailnoor\.droiddesk\.[\w.]*\b{extra_fn}\b", src)), *_policy_calls(code_only, "ExternalInputPolicyKt", extra_fn)])
        fb, fab, fit, ftp = _scoped_taint(src, re.compile(r"(?!x)x"), fn_roots)
        for bi in fab:
            s0, s1, v, lvs, tvs, _, is_type, tname = fb[bi]
            for kind, slc, tok in ((0, lvs, rf"(?<![.\w]){re.escape(v)}\b"), *ftp(s0, s1, v, tvs, is_type, tname)): hits.update((s0 + m.start(), code_only[s0 + m.start() : s0 + m.end()].strip()) for m in re.finditer(rf"(?:(?<![.\w])::\s*{re.escape(v)}\b|{tok}\s*\()", slc) if fit(bi, s0 + m.start(), kind))
    return sorted(hits)
def main() -> int:
    root, violations = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve(), []
    android_root, flutter_root = root / ANDROID_MAIN, root / "app/lib"
    lexed = {p.relative_to(root).as_posix(): lex_source(p.read_text(encoding="utf-8"), p.suffix) for p in [*(sorted([*android_root.rglob("*.kt"), *android_root.rglob("*.java")]) if android_root.is_dir() else []), *(sorted(flutter_root.rglob("*.dart")) if flutter_root.is_dir() else [])]}
    add = lambda rel, src, pattern, category: violations.extend(f"{rel}:{line_no(src, m.start())}: {category} ({m.group(0).strip()})" for m in pattern.finditer(src))
    startup_prefixes = (f"{KOTLIN_ROOT}/display/", f"{KOTLIN_ROOT}/view/", f"{KOTLIN_ROOT}/x11/", f"{KOTLIN_ROOT}/service/", f"{TERMUX_X11}/")
    for rel, (no_comments, code_only) in lexed.items():
        if rel.endswith("/DisplayEnvironmentPolicy.kt"): violations.append(f"{rel}:1: display-local branching leakage (DisplayEnvironmentPolicy.kt)")
        if rel != QUIRK_CATALOG: add(rel, no_comments, OEM_TOKEN, "oem identity leakage")
        add(rel, code_only, DISPLAY_ENV_POLICY, "display-local branching leakage")
        if not rel.startswith(f"{ANDROID_MAIN}/"): continue
        if rel == MAIN_ACTIVITY:
            masked = list(code_only)
            for allow in MAIN_DEVICE_INFO_ALLOW.finditer(no_comments): masked[allow.start() : allow.end()] = list(_blank(code_only[allow.start() : allow.end()]))
            add(rel, "".join(masked), BUILD_IDENTITY, "oem identity leakage")
        elif rel not in {QUIRK_CATALOG, PLACEMENT_SELECTOR}: add(rel, code_only, BUILD_IDENTITY, "oem identity leakage")
        if rel not in LOCAL_ALLOWED and not rel.startswith(f"{KOTLIN_ROOT}/display/placement/"): add(rel, code_only, DISPLAY_LOCAL_CALLER, "display-local branching leakage"); add(rel, no_comments, DISPLAY_LOCAL_WIRE, "display-local branching leakage")
        if rel != PRESENTATION_POLICY and not rel.startswith(f"{KOTLIN_ROOT}/display/input/"): violations.extend(f"{rel}:{line_no(code_only, off)}: misplaced input/placement policy ({snip})" for off, snip in _policy_calls(code_only, "DesktopInputPolicy", "resolve", "externalInputRoute"))
        if rel != COORDINATOR and not rel.startswith(f"{KOTLIN_ROOT}/display/placement/"): add(rel, code_only, PLACEMENT_INTERNAL, "misplaced input/placement policy")
        if rel not in {PRESENTATION_POLICY, DISPLAY_PROFILE}: violations.extend(f"{rel}:{line_no(code_only, off)}: misplaced input/placement policy ({snip})" for off, snip in _policy_calls(code_only, "DisplayProfilePolicy", "classify|resolve|renderSize"))
        if rel.startswith(f"{TERMUX_X11}/input/"): violations.extend(f"{rel}:{line_no(code_only, off)}: fixed pointer array ({snip})" for off, snip in find_fixed_pointers(code_only))
        if rel == MAIN_ACTIVITY or rel.startswith(startup_prefixes): violations.extend(f"{rel}:{line_no(code_only, off)}: 250 ms startup delay (postDelayed 250)" for off in find_250ms_post_delayed(code_only))
        if rel != REFRESH_POLICY:
            violations.extend(f"{rel}:{line_no(code_only, off)}: direct refresh-mode selection ({snip})" for off, snip in find_refresh_selections(rel, code_only))
            allowed = allowed_refresh_assigns(code_only) if rel == DESKTOP_ACTIVITY else set()
            violations.extend(f"{rel}:{line_no(code_only, m.start())}: direct refresh-mode selection ({m.group(0).strip()})" for m in WINDOW_REFRESH_ASSIGN.finditer(code_only) if m.start() not in allowed)
        add(rel, code_only, SMALLEST_SW_DP, "smallestScreenWidthDp profile classification")
    sender_tracked = INPUT_SENDER in lexed and bool(ACTIVE_POINTER_TRACKER.search(IMPORT_LINE.sub("", lexed[INPUT_SENDER][1])))
    if INPUT_SENDER in lexed and not sender_tracked: violations.append(f"{INPUT_SENDER}:1: fixed pointer array (missing ActivePointerTracker)")
    if TOUCH_HANDLER in lexed and not (ACTIVE_POINTER_TRACKER.search(tb := IMPORT_LINE.sub("", lexed[TOUCH_HANDLER][1])) or (sender_tracked and INPUT_EVENT_SENDER.search(tb))): violations.append(f"{TOUCH_HANDLER}:1: fixed pointer array (missing ActivePointerTracker)")
    if DESKTOP_ACTIVITY in lexed and not READINESS_GATE.search(IMPORT_LINE.sub("", lexed[DESKTOP_ACTIVITY][1])): violations.append(f"{DESKTOP_ACTIVITY}:1: 250 ms startup delay (missing DesktopReadinessGate)")
    print("Device compatibility architecture violations:\n" + "\n".join(f"- {v}" for v in violations) if violations else "Device compatibility architecture: OK")
    return 1 if violations else 0
if __name__ == "__main__": raise SystemExit(main())
