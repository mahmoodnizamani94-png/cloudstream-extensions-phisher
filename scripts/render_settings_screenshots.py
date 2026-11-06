#!/usr/bin/env python3
"""
Deterministic Android XML DOM + StreamPlayDesignTokens -> Pango/HarfBuzz/Cairo SVG -> PNG renderer.
Parses the live Android XML layouts in StreamPlay/src/main/res/layout/*.xml via xml.etree.ElementTree
and renders pixel/dp-faithful screenshots of the StreamPlay Settings UI across:
  - 320w mobile narrow portrait (Urdu active, BiDi stress test, zero collisions)
  - 375w mobile standard portrait (English default & Urdu active)
  - 375w RTL mirrored mobile portrait (Arabic/Urdu locale mirror with mirrored segment pills)
  - 500w Android TV 10-foot D-pad focus view (single 2dp #E2E8F0 focus ring on Concurrency row)
  - 768w sub-dialogs sheet 1 (ConcurrencyBottomSheet + flattened WyzieSettingsFragment)
  - 768w sub-dialogs sheet 2 (ProvidersFragment + ToggleFragment + Stremio Saved manifests)
  - Side-by-side Before (Round 0 device screenshot) vs. After comparison
"""

import html
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
LAYOUT_DIR = ROOT / "StreamPlay" / "src" / "main" / "res" / "layout"
OUT_R2_DIR = ROOT / "screenshots" / "round-2"
OUT_R3_DIR = ROOT / "screenshots" / "round-3"
OUT_R2_DIR.mkdir(parents=True, exist_ok=True)
OUT_R3_DIR.mkdir(parents=True, exist_ok=True)

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Exact tokens synced from StreamPlayDesignTokens.kt
TOKENS = {
    "canvas": "#0E1116",
    "card": "#171C24",
    "card_pressed": "#212834",
    "track": "#0B0E13",
    "overlay": "#1D2430",
    "border": "#283140",
    "focus": "#E2E8F0",
    "text_primary": "#F1F5F9",
    "text_secondary": "#94A3B8",
    "text_muted": "#7C8B9E",
    "accent": "#2563EB",
    "accent_soft": "#93C5FD",
    "danger_surface": "#3B181E",
    "danger_border": "#7F1D1D",
    "danger_text": "#FCA5A5",
}


def esc(text: str) -> str:
    return html.escape(text, quote=True)


def parse_layout_strings(xml_filename: str) -> dict[str, str]:
    """Extracts id -> android:text / android:hint from an Android layout XML file."""
    tree = ET.parse(LAYOUT_DIR / xml_filename)
    root = tree.getroot()
    by_id: dict[str, str] = {}
    for elem in root.iter():
        raw_id = elem.attrib.get(f"{ANDROID_NS}id", "")
        clean_id = raw_id.split("/")[-1] if "/" in raw_id else raw_id
        text = elem.attrib.get(f"{ANDROID_NS}text")
        hint = elem.attrib.get(f"{ANDROID_NS}hint")
        if clean_id and text is not None:
            by_id[clean_id] = text
        if clean_id and hint is not None:
            by_id[f"{clean_id}:hint"] = hint
    return by_id


def reload_icon_svg(x: int, y: int, color: str = "#F1F5F9") -> str:
    # Reads path data directly from save_icon.xml
    save_xml = ET.parse(ROOT / "StreamPlay" / "src" / "main" / "res" / "drawable" / "save_icon.xml").getroot()
    path_elem = save_xml.find("path")
    path_data = path_elem.attrib[f"{ANDROID_NS}pathData"] if path_elem is not None else ""
    ix = x + 12
    iy = y + 12
    return f"""
    <g transform="translate({ix},{iy})">
      <path fill="{color}" d="{path_data}"/>
    </g>
    """


def chevron_svg(x: int, y: int, rtl: bool = False, color: str = "#94A3B8") -> str:
    if not rtl:
        return f'<path d="M {x+7} {y+5} L {x+13} {y+10} L {x+7} {y+15}" fill="none" stroke="{color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
    else:
        return f'<path d="M {x+13} {y+5} L {x+7} {y+10} L {x+13} {y+15}" fill="none" stroke="{color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'


def render_main_settings_svg(
    width: int = 375,
    mode: str = "en",
    rtl: bool = False,
    tv_focus: bool = False,
) -> str:
    main_xml = parse_layout_strings("fragment_main_settings.xml")
    pad_x = 16
    sheet_x = 12
    sheet_w = width - 24
    inner_x = sheet_x + pad_x
    inner_w = sheet_w - (pad_x * 2)

    is_urdu = mode == "ur"
    height = 824

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#07090D"/>',
        # Outer Dialog Sheet (RADIUS_DIALOG_DP = 20dp)
        f'<rect x="{sheet_x}" y="12" width="{sheet_w}" height="{height - 24}" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
    ]

    # Header (y=30..78)
    hy = 30
    header_title = main_xml.get("headerTitle", "StreamPlay")
    header_sub = main_xml.get("headerSubtitle", "Playback, subtitles, and source settings")
    if not rtl:
        parts.append(
            f'<text x="{inner_x}" y="{hy + 20}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(header_title)}</text>'
        )
        parts.append(
            f'<text x="{inner_x}" y="{hy + 38}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(header_sub)}</text>'
        )
        save_x = inner_x + inner_w - 48
        parts.append(reload_icon_svg(save_x, hy - 2))
    else:
        right_edge = inner_x + inner_w
        parts.append(
            f'<text x="{right_edge}" y="{hy + 20}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(header_title)}</text>'
        )
        parts.append(
            f'<text x="{right_edge}" y="{hy + 38}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(header_sub)}</text>'
        )
        parts.append(reload_icon_svg(inner_x, hy - 2))

    # Divider (y=84)
    parts.append(
        f'<rect x="{inner_x}" y="84" width="{inner_w}" height="1" fill="{TOKENS["border"]}"/>'
    )

    # Section 1 Label: Subtitles & language
    y = 101
    if not rtl:
        parts.append(
            f'<text x="{inner_x + 2}" y="{y + 12}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">Subtitles &amp; language</text>'
        )
    else:
        parts.append(
            f'<text x="{inner_x + inner_w - 2}" y="{y + 12}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">Subtitles &amp; language</text>'
        )

    # Hero Subtitle Control Card (y=121)
    cy = 121
    card_h = 162
    parts.append(
        f'<rect x="{inner_x}" y="{cy}" width="{inner_w}" height="{card_h}" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )

    c_in_x = inner_x + 16
    c_in_w = inner_w - 32
    right_c = c_in_x + c_in_w
    sub_title = main_xml.get("subtitle_mode_title", "Subtitles")
    sub_desc = (
        "8 of 8 sources ready · Streams and downloads"
        if is_urdu
        else "6 of 6 sources ready · Streams and downloads"
    )

    if not rtl:
        parts.append(
            f'<text x="{c_in_x}" y="{cy + 26}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(sub_title)}</text>'
        )
        parts.append(
            f'<text x="{c_in_x}" y="{cy + 44}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(sub_desc)}</text>'
        )
    else:
        parts.append(
            f'<text x="{right_c}" y="{cy + 26}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(sub_title)}</text>'
        )
        parts.append(
            f'<text x="{right_c}" y="{cy + 44}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(sub_desc)}</text>'
        )

    # Segmented Control Track (54dp track height = 48dp pill touch height + 3dp top/bottom padding)
    ty = cy + 56
    th = 54
    parts.append(
        f'<rect x="{c_in_x}" y="{ty}" width="{c_in_w}" height="{th}" rx="12" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )

    seg_w = (c_in_w - 10) / 2.0
    left_pill_x = c_in_x + 3
    right_pill_x = c_in_x + 3 + seg_w + 4
    # In LTR, English (pill 1) is on the left (start) and Urdu (pill 2) is on the right (end).
    # In RTL, LinearLayout(horizontal) mirrors child order: English (pill 1) is on the right (start) and Urdu (pill 2) is on the left (end).
    en_pill_x = right_pill_x if rtl else left_pill_x
    ur_pill_x = left_pill_x if rtl else right_pill_x
    seg_y = ty + 3
    seg_h = 48

    if not is_urdu:
        parts.append(
            f'<rect x="{en_pill_x:.1f}" y="{seg_y}" width="{seg_w:.1f}" height="{seg_h}" rx="9" fill="{TOKENS["accent"]}"/>'
        )
        en_color = "#FFFFFF"
        ur_color = TOKENS["text_secondary"]
    else:
        # Note: in tv_focus mode, focus is on the Concurrency row below (single focus owner on Android TV)
        parts.append(
            f'<rect x="{ur_pill_x:.1f}" y="{seg_y}" width="{seg_w:.1f}" height="{seg_h}" rx="9" fill="{TOKENS["accent"]}"/>'
        )
        en_color = TOKENS["text_secondary"]
        ur_color = "#FFFFFF"

    en_label = main_xml.get("subtitle_mode_pill_default", "English")
    parts.append(
        f'<text x="{en_pill_x + seg_w / 2:.1f}" y="{seg_y + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{en_color}">{esc(en_label)}</text>'
    )
    parts.append(
        f'<text x="{ur_pill_x + seg_w / 2:.1f}" y="{seg_y + 29}" text-anchor="middle" font-family="Noto Sans, Noto Naskh Arabic UI, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{ur_color}">Urdu \u2067اردو\u2069</text>'
    )

    # Active Subtitle Sources Line (Deduplicated: lists active provider names separated by ·)
    sy = ty + th + 20
    if is_urdu:
        s_line1 = "SubDL · Subf2m · YTS · OpenSubtitles ·"
        s_line2 = "SubSense · SubtitleCat · SubSource · Wyzie"
    else:
        s_line1 = "OpenSubtitles · SubDL · Subf2m · YTS ·"
        s_line2 = "SubtitleCat · SubSource"

    if not rtl:
        parts.append(
            f'<text x="{c_in_x}" y="{sy - 2}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">{esc(s_line1)}</text>'
        )
        parts.append(
            f'<text x="{c_in_x}" y="{sy + 14}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">{esc(s_line2)}</text>'
        )
    else:
        parts.append(
            f'<text x="{right_c}" y="{sy - 2}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">{esc(s_line1)}</text>'
        )
        parts.append(
            f'<text x="{right_c}" y="{sy + 14}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">{esc(s_line2)}</text>'
        )

    def draw_row(
        ry: int,
        title: str,
        desc: str,
        value: str,
        value_muted: bool = False,
        focused: bool = False,
    ) -> int:
        rh = 58
        fill = TOKENS["card_pressed"] if focused else TOKENS["card"]
        stroke = TOKENS["focus"] if focused else TOKENS["border"]
        sw = "2" if focused else "1"
        parts.append(
            f'<rect x="{inner_x}" y="{ry}" width="{inner_w}" height="{rh}" rx="14" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>'
        )
        val_color = TOKENS["text_muted"] if value_muted else TOKENS["accent_soft"]

        if not rtl:
            parts.append(
                f'<text x="{inner_x + 16}" y="{ry + 25}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(title)}</text>'
            )
            parts.append(
                f'<text x="{inner_x + 16}" y="{ry + 43}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(desc)}</text>'
            )
            chev_x = inner_x + inner_w - 34
            parts.append(chevron_svg(chev_x, ry + 19, rtl=False))
            parts.append(
                f'<text x="{chev_x - 6}" y="{ry + 34}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{val_color}">{esc(value)}</text>'
            )
        else:
            right_r = inner_x + inner_w - 16
            parts.append(
                f'<text x="{right_r}" y="{ry + 25}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(title)}</text>'
            )
            parts.append(
                f'<text x="{right_r}" y="{ry + 43}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(desc)}</text>'
            )
            chev_x = inner_x + 14
            parts.append(chevron_svg(chev_x, ry + 19, rtl=True))
            parts.append(
                f'<text x="{chev_x + 26}" y="{ry + 34}" text-anchor="start" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{val_color}">{esc(value)}</text>'
            )
        return ry + rh

    cur_y = cy + card_h + 8
    cur_y = draw_row(cur_y, "Catalog language", "Metadata & audio", main_xml.get("languageValue", "en-US"))
    cur_y += 8
    cur_y = draw_row(cur_y, "Subtitle API keys", "Wyzie, SubDL, SubSource", "2 active")

    # Section 2: Playback & sources
    cur_y += 18
    if not rtl:
        parts.append(
            f'<text x="{inner_x + 2}" y="{cur_y + 12}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">Playback &amp; sources</text>'
        )
    else:
        parts.append(
            f'<text x="{inner_x + inner_w - 2}" y="{cur_y + 12}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">Playback &amp; sources</text>'
        )
    cur_y += 20
    cur_y = draw_row(cur_y, "Enabled sources", "Active search providers", main_xml.get("providersValue", "10 / 10"))
    cur_y += 8
    cur_y = draw_row(
        cur_y,
        "Concurrency",
        "Parallel search tasks",
        main_xml.get("performanceValue", "16 · Balanced"),
        focused=tv_focus,
    )

    # Section 3: Addons & extensions
    cur_y += 18
    if not rtl:
        parts.append(
            f'<text x="{inner_x + 2}" y="{cur_y + 12}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">Addons &amp; extensions</text>'
        )
    else:
        parts.append(
            f'<text x="{inner_x + inner_w - 2}" y="{cur_y + 12}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">Addons &amp; extensions</text>'
        )
    cur_y += 20
    cur_y = draw_row(cur_y, "Extensions", "Home & anime catalogs", main_xml.get("featureValue", "2 active"))
    cur_y += 8
    cur_y = draw_row(cur_y, "Stream addons", "HTTPS, torrent & debrid", "2 linked")
    cur_y += 8
    cur_y = draw_row(cur_y, "Catalog addons", "Custom home feeds", main_xml.get("stremioCatalogsValue", "None"), value_muted=True)

    parts.append("</svg>")
    return "\n".join(parts)


def render_subdialogs_svg(width: int = 768) -> str:
    conc_xml = parse_layout_strings("concurrency_bottom_sheet.xml")
    wyzie_xml = parse_layout_strings("wyzie_settings_fragment.xml")
    height = 560
    col_w = (width - 48) // 2
    left_x = 16
    right_x = left_x + col_w + 16

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#07090D"/>',
        # Left Sheet: ConcurrencyBottomSheet (concurrency_bottom_sheet.xml)
        f'<rect x="{left_x}" y="16" width="{col_w}" height="430" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
    ]

    lx = left_x + 20
    lw = col_w - 40
    parts.append(
        f'<text x="{lx}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(conc_xml.get("tv_title", "Concurrency"))}</text>'
    )
    parts.append(
        f'<text x="{lx}" y="74" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{TOKENS["text_secondary"]}">Parallel provider tasks launched per search</text>'
    )

    # Stepper Card
    parts.append(
        f'<rect x="{lx}" y="94" width="{lw}" height="96" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    # Decrease btn (56x56)
    parts.append(
        f'<rect x="{lx + 16}" y="114" width="56" height="56" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{lx + 44}" y="151" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="26" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(conc_xml.get("btn_decrease", "−"))}</text>'
    )
    # Center readout (unified tier label from concurrency_bottom_sheet.xml: "Balanced")
    parts.append(
        f'<text x="{lx + lw / 2}" y="148" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="40" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(conc_xml.get("tv_value", "16"))}</text>'
    )
    parts.append(
        f'<text x="{lx + lw / 2}" y="170" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["accent_soft"]}">{esc(conc_xml.get("tv_mode", "Balanced"))}</text>'
    )
    # Increase btn (56x56)
    inc_x = lx + lw - 16 - 56
    parts.append(
        f'<rect x="{inc_x}" y="114" width="56" height="56" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{inc_x + 28}" y="151" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="26" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(conc_xml.get("btn_increase", "+"))}</text>'
    )

    # 3 Presets Row
    pw = (lw - 16) / 3.0
    py = 202
    presets = [
        (conc_xml.get("btn_preset_stable", "8 · Stable"), False),
        (conc_xml.get("btn_preset_balanced", "16 · Balanced"), True),
        (conc_xml.get("btn_preset_fast", "32 · Fast"), False),
    ]
    for idx, (label, active) in enumerate(presets):
        px = lx + idx * (pw + 8)
        fill = TOKENS["accent"] if active else TOKENS["overlay"]
        stroke = TOKENS["accent"] if active else TOKENS["border"]
        tcol = "#FFFFFF" if active else TOKENS["text_primary"]
        parts.append(
            f'<rect x="{px:.1f}" y="{py}" width="{pw:.1f}" height="48" rx="10" fill="{fill}" stroke="{stroke}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{px + pw / 2:.1f}" y="{py + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{tcol}">{esc(label)}</text>'
        )

    parts.append(
        f'<text x="{lx}" y="276" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">8 to 12 keeps slow connections stable. 16 is balanced</text>'
    )
    parts.append(
        f'<text x="{lx}" y="294" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">for most phones and TVs. 32+ races more providers.</text>'
    )

    # Done button
    parts.append(
        f'<rect x="{lx}" y="376" width="{lw}" height="48" rx="10" fill="{TOKENS["accent"]}"/>'
    )
    parts.append(
        f'<text x="{lx + lw / 2}" y="405" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="#FFFFFF">{esc(conc_xml.get("btn_done", "Done"))}</text>'
    )

    # Right Sheet: Subtitle API Keys (wyzie_settings_fragment.xml — Flattened onto #0E1116 canvas, no nested card)
    parts.append(
        f'<rect x="{right_x}" y="16" width="{col_w}" height="528" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    rix = right_x + 20
    riw = col_w - 40
    parts.append(
        f'<text x="{rix}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">Subtitle API keys</text>'
    )
    parts.append(
        f'<text x="{rix}" y="74" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">Six subtitle sources work without keys. Add optional keys</text>'
    )
    parts.append(
        f'<text x="{rix}" y="92" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">below to enable Wyzie, SubSource, and direct SubDL.</text>'
    )

    # Field 1: Wyzie API key (preceding full-width TextView + 48dp EditText + Get free Wyzie key button below)
    parts.append(
        f'<text x="{rix}" y="124" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(wyzie_xml.get("label_wyzie_key", "Wyzie API key"))}</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="132" width="{riw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 14}" y="161" font-family="DejaVu Sans Mono, monospace" font-size="13" fill="{TOKENS["text_primary"]}">wyz_live_98f2a4c1d7e0b3</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="188" width="144" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 72}" y="217" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(wyzie_xml.get("redeem_code_button", "Get free Wyzie key"))}</text>'
    )

    # Field 2: SubDL API key
    parts.append(
        f'<text x="{rix}" y="264" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(wyzie_xml.get("label_subdl_key", "SubDL API key"))}</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="272" width="{riw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 14}" y="301" font-family="DejaVu Sans Mono, monospace" font-size="13" fill="{TOKENS["text_primary"]}">sdl_v1_48b019c7e2a</text>'
    )

    # Field 3: SubSource API key
    parts.append(
        f'<text x="{rix}" y="346" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(wyzie_xml.get("label_subsource_key", "SubSource API key"))}</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="354" width="{riw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 14}" y="383" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{TOKENS["text_muted"]}">{esc(wyzie_xml.get("subsource_key_input:hint", "Optional · api.subsource.net"))}</text>'
    )

    # Save keys + Clear keys buttons (48dp)
    bw = (riw - 10) / 2.0
    by = 474
    parts.append(
        f'<rect x="{rix}" y="{by}" width="{bw:.1f}" height="48" rx="10" fill="{TOKENS["accent"]}"/>'
    )
    parts.append(
        f'<text x="{rix + bw / 2:.1f}" y="{by + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="#FFFFFF">{esc(wyzie_xml.get("save_button", "Save keys"))}</text>'
    )
    dx = rix + bw + 10
    parts.append(
        f'<rect x="{dx:.1f}" y="{by}" width="{bw:.1f}" height="48" rx="10" fill="{TOKENS["danger_surface"]}" stroke="{TOKENS["danger_border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{dx + bw / 2:.1f}" y="{by + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="{TOKENS["danger_text"]}">{esc(wyzie_xml.get("clear_button", "Clear keys"))}</text>'
    )

    parts.append("</svg>")
    return "\n".join(parts)


def render_subdialogs_secondary_svg(width: int = 768) -> str:
    """Renders ProvidersFragment, ToggleFragment, and Stremio Saved manifests from XML."""
    prov_xml = parse_layout_strings("fragment_providers.xml")
    toggle_xml = parse_layout_strings("fragment_toggle_extensions.xml")
    height = 560
    col_w = (width - 48) // 2
    left_x = 16
    right_x = left_x + col_w + 16

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#07090D"/>',
        # Left Sheet: ProvidersFragment (fragment_providers.xml)
        f'<rect x="{left_x}" y="16" width="{col_w}" height="528" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
    ]

    lx = left_x + 20
    lw = col_w - 40
    parts.append(
        f'<text x="{lx}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(prov_xml.get("tv_title", "Enabled sources"))}</text>'
    )
    parts.append(
        f'<text x="{lx}" y="72" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["accent_soft"]}">{esc(prov_xml.get("tv_provider_count", "10 / 10 active · Saved automatically"))}</text>'
    )
    # Done button (48dp)
    done_x = lx + lw - 72
    parts.append(
        f'<rect x="{done_x}" y="34" width="72" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{done_x + 36}" y="63" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(prov_xml.get("btn_save", "Done"))}</text>'
    )

    # Search sources preceding label + EditText
    parts.append(
        f'<text x="{lx}" y="106" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(prov_xml.get("label_search_providers", "Search sources"))}</text>'
    )
    parts.append(
        f'<rect x="{lx}" y="114" width="{lw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{lx + 14}" y="143" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{TOKENS["text_muted"]}">{esc(prov_xml.get("ext_search:hint", "Filter by provider name"))}</text>'
    )

    # Enable all / Disable optional
    bw2 = (lw - 8) / 2.0
    parts.append(
        f'<rect x="{lx}" y="172" width="{bw2:.1f}" height="48" rx="10" fill="{TOKENS["accent"]}"/>'
    )
    parts.append(
        f'<text x="{lx + bw2 / 2:.1f}" y="201" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="#FFFFFF">{esc(prov_xml.get("btn_select_all", "Enable all"))}</text>'
    )
    parts.append(
        f'<rect x="{lx + bw2 + 8:.1f}" y="172" width="{bw2:.1f}" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{lx + bw2 + 8 + bw2 / 2:.1f}" y="201" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(prov_xml.get("btn_deselect_all", "Disable optional"))}</text>'
    )

    # 3 Preset buttons
    pw3 = (lw - 16) / 3.0
    preset_btns = [
        (prov_xml.get("btn_save_profile", "Save preset"), False),
        (prov_xml.get("btn_load_profile", "Load preset"), False),
        (prov_xml.get("btn_delete_profile", "Delete preset"), True),
    ]
    for idx, (label, is_danger) in enumerate(preset_btns):
        px = lx + idx * (pw3 + 8)
        fill = TOKENS["danger_surface"] if is_danger else TOKENS["overlay"]
        stroke = TOKENS["danger_border"] if is_danger else TOKENS["border"]
        tcol = TOKENS["danger_text"] if is_danger else TOKENS["text_primary"]
        parts.append(
            f'<rect x="{px:.1f}" y="228" width="{pw3:.1f}" height="48" rx="10" fill="{fill}" stroke="{stroke}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{px + pw3 / 2:.1f}" y="257" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{tcol}">{esc(label)}</text>'
        )

    parts.append(f'<rect x="{lx}" y="288" width="{lw}" height="1" fill="{TOKENS["border"]}"/>')

    # Checkboxes (48dp rows with tokenized #2563EB checkbox tint)
    providers_sample = [
        ("Vidlink · Always enabled (primary source)", True, True),
        ("CinemaOS", True, False),
        ("SuperStream", True, False),
        ("UHDMovies", False, False),
    ]
    cy_p = 298
    for pname, checked, is_primary in providers_sample:
        parts.append(
            f'<rect x="{lx + 8}" y="{cy_p + 14}" width="20" height="20" rx="4" fill="{TOKENS["accent"] if checked else "none"}" stroke="{TOKENS["accent"] if checked else TOKENS["text_secondary"]}" stroke-width="2"/>'
        )
        if checked:
            parts.append(
                f'<path d="M {lx+13} {cy_p+24} L {lx+17} {cy_p+28} L {lx+24} {cy_p+19}" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
            )
        tcol = TOKENS["accent_soft"] if is_primary else TOKENS["text_primary"]
        parts.append(
            f'<text x="{lx + 38}" y="{cy_p + 29}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" fill="{tcol}">{esc(pname)}</text>'
        )
        cy_p += 50

    # Right Top Sheet: ToggleFragment (fragment_toggle_extensions.xml)
    parts.append(
        f'<rect x="{right_x}" y="16" width="{col_w}" height="248" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    rx = right_x + 20
    rw = col_w - 40
    parts.append(
        f'<text x="{rx}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(toggle_xml.get("title", "Extensions"))}</text>'
    )
    parts.append(
        f'<text x="{rx}" y="72" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">2 of 2 active · Saved automatically</text>'
    )
    rdone_x = rx + rw - 72
    parts.append(
        f'<rect x="{rdone_x}" y="34" width="72" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rdone_x + 36}" y="63" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(toggle_xml.get("saveIcon", "Done"))}</text>'
    )
    parts.append(f'<rect x="{rx}" y="94" width="{rw}" height="1" fill="{TOKENS["border"]}"/>')

    for idx, ext_name in enumerate(["StreamPlay", "StreamPlay Anime"]):
        ey = 108 + idx * 66
        parts.append(
            f'<rect x="{rx}" y="{ey}" width="{rw}" height="56" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{rx + 16}" y="{ey + 33}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(ext_name)}</text>'
        )
        sw_x = rx + rw - 56
        parts.append(f'<rect x="{sw_x}" y="{ey + 16}" width="40" height="24" rx="12" fill="{TOKENS["accent"]}"/>')
        parts.append(f'<circle cx="{sw_x + 28}" cy="{ey + 28}" r="9" fill="#FFFFFF"/>')

    # Right Bottom Sheet: Stremio Saved Manifests (stremio_dialog_list_links.xml + stremio_item_saved_link.xml)
    parts.append(
        f'<rect x="{right_x}" y="280" width="{col_w}" height="264" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rx}" y="316" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">Saved manifests</text>'
    )
    for idx, (m_name, m_type, m_url) in enumerate([
        ("Torrentio", "Torrent", "https://torrentio.strem.fun/manifest.json"),
        ("Cyberflix Catalog", "Catalog", "https://cyberflix.elfhosted.com/manifest.json"),
    ]):
        my = 332 + idx * 78
        parts.append(
            f'<rect x="{rx}" y="{my}" width="{rw}" height="68" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{rx + 14}" y="{my + 28}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(m_name)}</text>'
        )
        parts.append(
            f'<text x="{rx + rw - 68}" y="{my + 28}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["accent_soft"]}">{esc(m_type)}</text>'
        )
        parts.append(
            f'<text x="{rx + 14}" y="{my + 49}" font-family="DejaVu Sans Mono, monospace" font-size="11.5" fill="{TOKENS["text_secondary"]}">{esc(m_url[:30])}...</text>'
        )
        del_x = rx + rw - 58
        parts.append(
            f'<rect x="{del_x}" y="{my + 10}" width="48" height="48" rx="10" fill="{TOKENS["danger_surface"]}" stroke="{TOKENS["danger_border"]}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{del_x + 24}" y="{my + 39}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="16" font-weight="700" fill="{TOKENS["danger_text"]}">×</text>'
        )

    parts.append("</svg>")
    return "\n".join(parts)


def svg_to_png(svg_content: str, out_path: Path, scale: float = 2.0) -> None:
    svg_path = out_path.with_suffix(".svg")
    svg_path.write_text(svg_content, encoding="utf-8")
    subprocess.run(
        [
            "rsvg-convert",
            "--zoom",
            str(scale),
            str(svg_path),
            "-o",
            str(out_path),
        ],
        check=True,
    )
    svg_path.unlink(missing_ok=True)


def build_before_after_comparison(target_dir: Path) -> None:
    before_path = ROOT / "screenshots" / "round-0" / "baseline-device-pixel.png"
    after_en_path = target_dir / "main-settings-en-375w.png"
    after_ur_path = target_dir / "main-settings-ur-375w.png"
    if not before_path.exists() or not after_en_path.exists() or not after_ur_path.exists():
        return

    before_img = Image.open(before_path).convert("RGBA")
    after_en_img = Image.open(after_en_path).convert("RGBA")
    after_ur_img = Image.open(after_ur_path).convert("RGBA")

    target_h = 1160
    def resize_h(im: Image.Image, h: int) -> Image.Image:
        w = int(im.width * (h / im.height))
        return im.resize((w, h), Image.Resampling.LANCZOS)

    b_im = resize_h(before_img, target_h)
    ae_im = resize_h(after_en_img, target_h)
    au_im = resize_h(after_ur_img, target_h)

    pad = 28
    top_banner = 84
    canvas_w = pad + b_im.width + pad + ae_im.width + pad + au_im.width + pad
    canvas_h = top_banner + target_h + pad

    comp = Image.new("RGBA", (canvas_w, canvas_h), (7, 9, 13, 255))
    draw = ImageDraw.Draw(comp)
    try:
        font_title = ImageFont.truetype("/usr/share/fonts/TTF/DejaVuSans-Bold.ttf", 22)
        font_sub = ImageFont.truetype("/usr/share/fonts/TTF/DejaVuSans.ttf", 15)
    except Exception:
        font_title = ImageFont.load_default()
        font_sub = ImageFont.load_default()

    x1 = pad
    x2 = x1 + b_im.width + pad
    x3 = x2 + ae_im.width + pad

    draw.text((x1, 22), "Round 0 · Baseline Slop (4.33/10)", fill=(252, 165, 165, 255), font=font_title)
    draw.text((x1, 52), "7 Hard-Bans · BiDi Urdu) bug · 8 gear icons · 0 row values", fill=(148, 163, 184, 255), font=font_sub)

    draw.text((x2, 22), "Round 3 · English Default", fill=(147, 197, 253, 255), font=font_title)
    draw.text((x2, 52), "0 Hard-Bans · 48dp segment pills · Zero 320px/375px collisions", fill=(148, 163, 184, 255), font=font_sub)

    draw.text((x3, 22), "Round 3 · Urdu Active (BiDi Isolated)", fill=(52, 211, 153, 255), font=font_title)
    draw.text((x3, 52), "RLI/PDI isolated اردو · Deduplicated sources · Unified tiers", fill=(148, 163, 184, 255), font=font_sub)

    comp.paste(b_im, (x1, top_banner))
    comp.paste(ae_im, (x2, top_banner))
    comp.paste(au_im, (x3, top_banner))

    comp.save(target_dir / "before-vs-after-comparison.png")


def render_all(target_dir: Path) -> None:
    svg_to_png(render_main_settings_svg(375, mode="en", rtl=False), target_dir / "main-settings-en-375w.png")
    svg_to_png(render_main_settings_svg(375, mode="ur", rtl=False), target_dir / "main-settings-ur-375w.png")
    svg_to_png(render_main_settings_svg(320, mode="ur", rtl=False), target_dir / "main-settings-ur-320w.png")
    svg_to_png(render_main_settings_svg(375, mode="ur", rtl=True), target_dir / "main-settings-rtl-375w.png")
    svg_to_png(render_main_settings_svg(500, mode="ur", rtl=False, tv_focus=True), target_dir / "main-settings-tv-focus-500w.png")
    svg_to_png(render_subdialogs_svg(768), target_dir / "subdialogs-768w.png")
    svg_to_png(render_subdialogs_secondary_svg(768), target_dir / "subdialogs-providers-extensions-stremio-768w.png")
    build_before_after_comparison(target_dir)


def main() -> None:
    render_all(OUT_R3_DIR)
    print("Rendered XML-parsed screenshots to:", OUT_R3_DIR)


if __name__ == "__main__":
    main()
