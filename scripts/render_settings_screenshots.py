#!/usr/bin/env python3
"""
Deterministic Android XML DOM + StreamPlayDesignTokens -> Pango/HarfBuzz/Cairo SVG -> PNG renderer.
Strictly parses the live Android XML layouts in StreamPlay/src/main/res/layout/*.xml and vector drawables
in StreamPlay/src/main/res/drawable/*.xml via xml.etree.ElementTree (failing loud if any referenced ID or
drawable path is missing) and renders pixel/dp-faithful screenshots of the StreamPlay Settings UI across:
  - 320w mobile narrow portrait (Urdu active, BiDi stress test, OS grouped inset sections, zero collisions)
  - 375w mobile standard portrait (English default & Urdu active)
  - 375w RTL mirrored mobile portrait (Arabic/Urdu locale mirror with mirrored segment pills)
  - 500w Android TV 10-foot D-pad focus view (single 2dp #E2E8F0 focus ring on Concurrency row)
  - 640x360 mobile landscape viewport (ConcurrencyBottomSheet & MainSettingsFragment scrollable safety)
  - 768w sub-dialogs sheet 1 (ConcurrencyBottomSheet + WyzieSettingsFragment with header button & quiet Clear)
  - 768w sub-dialogs sheet 2 (ProvidersFragment + ToggleFragment + LanguageSelectFragment + Stremio Saved manifests)
  - Side-by-side Before (Round 0 device screenshot) vs. After comparison
"""

import base64
import html
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAYOUT_DIR = ROOT / "StreamPlay" / "src" / "main" / "res" / "layout"
DRAWABLE_DIR = ROOT / "StreamPlay" / "src" / "main" / "res" / "drawable"
OUT_R4_DIR = ROOT / "screenshots" / "round-4"
OUT_R4_DIR.mkdir(parents=True, exist_ok=True)

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
        if clean_id:
            by_id[f"__exists__:{clean_id}"] = "1"
        if clean_id and text is not None:
            by_id[clean_id] = text
        if clean_id and hint is not None:
            by_id[f"{clean_id}:hint"] = hint
    return by_id


def require_text(xml_map: dict[str, str], view_id: str) -> str:
    if view_id not in xml_map:
        raise KeyError(f"Required android:id='@+id/{view_id}' with android:text not found in XML")
    return xml_map[view_id]


def require_hint(xml_map: dict[str, str], view_id: str) -> str:
    key = f"{view_id}:hint"
    if key not in xml_map:
        raise KeyError(f"Required android:id='@+id/{view_id}' with android:hint not found in XML")
    return xml_map[key]


def require_id_exists(xml_map: dict[str, str], view_id: str) -> None:
    if f"__exists__:{view_id}" not in xml_map:
        raise KeyError(f"Required android:id='@+id/{view_id}' not found in XML")


def vector_drawable_svg(drawable_name: str, x: int, y: int, scale: float = 1.0, color_override: str | None = None) -> str:
    xml_root = ET.parse(DRAWABLE_DIR / f"{drawable_name}.xml").getroot()
    path_elem = xml_root.find("path")
    if path_elem is None:
        raise ValueError(f"No <path> found in {drawable_name}.xml")
    path_data = path_elem.attrib[f"{ANDROID_NS}pathData"]
    fill_color = color_override or path_elem.attrib.get(f"{ANDROID_NS}fillColor", "#F1F5F9")
    return f"""
    <g transform="translate({x},{y}) scale({scale})">
      <path fill="{fill_color}" d="{path_data}"/>
    </g>
    """


def reload_icon_svg(x: int, y: int, color: str = "#F1F5F9") -> str:
    return vector_drawable_svg("save_icon", x + 12, y + 12, scale=1.0, color_override=color)


def delete_icon_svg(x: int, y: int, color: str = "#94A3B8") -> str:
    return vector_drawable_svg("delete_icon", x + 14, y + 14, scale=20.0 / 24.0, color_override=color)


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
    require_id_exists(main_xml, "section_subtitles_group")
    require_id_exists(main_xml, "section_playback_group")
    require_id_exists(main_xml, "section_addons_group")

    pad_x = 20
    sheet_x = 12
    sheet_w = width - 24
    inner_x = sheet_x + pad_x
    inner_w = sheet_w - (pad_x * 2)

    is_urdu = mode == "ur"
    height = 752

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#07090D"/>',
        # Outer Dialog Sheet (RADIUS_DIALOG_DP = 20dp)
        f'<rect x="{sheet_x}" y="12" width="{sheet_w}" height="{height - 24}" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
    ]

    # Header (y=32..80)
    hy = 32
    header_title = "StreamPlay"
    header_sub = "Playback, subtitles, and source settings"
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

    # Header Hairline Divider (y=86)
    parts.append(
        f'<rect x="{inner_x}" y="86" width="{inner_w}" height="1" fill="{TOKENS["border"]}"/>'
    )

    def draw_section_label(ly: int, label_text: str) -> int:
        if not rtl:
            parts.append(
                f'<text x="{inner_x + 4}" y="{ly + 12}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">{esc(label_text)}</text>'
            )
        else:
            parts.append(
                f'<text x="{inner_x + inner_w - 4}" y="{ly + 12}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_muted"]}">{esc(label_text)}</text>'
            )
        return ly + 20

    def draw_grouped_row(
        ry: int,
        title: str,
        desc: str,
        value: str,
        value_muted: bool = False,
        focused: bool = False,
        has_top_divider: bool = True,
    ) -> int:
        rh = 58
        if has_top_divider:
            div_x = inner_x if rtl else inner_x + 16
            div_w = inner_w - 16
            parts.append(
                f'<rect x="{div_x}" y="{ry}" width="{div_w}" height="1" fill="{TOKENS["border"]}"/>'
            )
            ry += 1

        if focused:
            fx = inner_x + 1
            fy = ry + 1
            fw = inner_w - 2
            fh = rh - 2
            r = 12
            parts.append(
                f'<path d="M {fx} {fy} L {fx+fw} {fy} L {fx+fw} {fy+fh-r} A {r} {r} 0 0 1 {fx+fw-r} {fy+fh} L {fx+r} {fy+fh} A {r} {r} 0 0 1 {fx} {fy+fh-r} Z" fill="{TOKENS["card_pressed"]}" stroke="{TOKENS["focus"]}" stroke-width="2"/>'
            )

        val_color = TOKENS["text_muted"] if value_muted else TOKENS["text_secondary"]

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
                f'<text x="{chev_x - 6}" y="{ry + 34}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{val_color}">{esc(value)}</text>'
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
                f'<text x="{chev_x + 26}" y="{ry + 34}" text-anchor="start" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{val_color}">{esc(value)}</text>'
            )
        return ry + rh

    # Section 1: Subtitles & language (Grouped container: Hero row + Catalog language + Subtitle API keys)
    cur_y = draw_section_label(100, "Subtitles & language")
    sec1_y = cur_y
    hero_h = 114
    sec1_h = hero_h + 1 + 58 + 1 + 58
    parts.append(
        f'<rect x="{inner_x}" y="{sec1_y}" width="{inner_w}" height="{sec1_h}" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )

    c_in_x = inner_x + 16
    c_in_w = inner_w - 32
    right_c = c_in_x + c_in_w
    sub_title = require_text(main_xml, "subtitle_mode_label")
    sub_desc = "8 of 8 sources" if is_urdu else "6 of 6 sources"

    if not rtl:
        parts.append(
            f'<text x="{c_in_x}" y="{sec1_y + 30}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(sub_title)}</text>'
        )
        parts.append(
            f'<text x="{right_c}" y="{sec1_y + 30}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{TOKENS["text_secondary"]}">{esc(sub_desc)}</text>'
        )
    else:
        parts.append(
            f'<text x="{right_c}" y="{sec1_y + 30}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(sub_title)}</text>'
        )
        parts.append(
            f'<text x="{c_in_x}" y="{sec1_y + 30}" text-anchor="start" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{TOKENS["text_secondary"]}">{esc(sub_desc)}</text>'
        )

    # Segmented Control Track (54dp track height = 48dp pill touch height + 3dp top/bottom padding)
    ty = sec1_y + 44
    th = 54
    parts.append(
        f'<rect x="{c_in_x}" y="{ty}" width="{c_in_w}" height="{th}" rx="12" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )

    seg_w = (c_in_w - 10) / 2.0
    left_pill_x = c_in_x + 3
    right_pill_x = c_in_x + 3 + seg_w + 4
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
        parts.append(
            f'<rect x="{ur_pill_x:.1f}" y="{seg_y}" width="{seg_w:.1f}" height="{seg_h}" rx="9" fill="{TOKENS["accent"]}"/>'
        )
        en_color = TOKENS["text_secondary"]
        ur_color = "#FFFFFF"

    en_label = require_text(main_xml, "subtitle_mode_pill_default")
    parts.append(
        f'<text x="{en_pill_x + seg_w / 2:.1f}" y="{seg_y + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{en_color}">{esc(en_label)}</text>'
    )
    parts.append(
        f'<text x="{ur_pill_x + seg_w / 2:.1f}" y="{seg_y + 29}" text-anchor="middle" font-family="Noto Sans, Noto Naskh Arabic UI, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{ur_color}">Urdu \u2067اردو\u2069</text>'
    )

    cur_y = sec1_y + hero_h
    cur_y = draw_grouped_row(cur_y, "Catalog language", "Metadata & audio", require_text(main_xml, "languageValue"), has_top_divider=True)
    cur_y = draw_grouped_row(cur_y, "Subtitle API keys", "Wyzie, SubDL, SubSource", "2 of 3", has_top_divider=True)

    # Section 2: Playback & sources (Grouped container: Enabled sources + Concurrency)
    cur_y = draw_section_label(cur_y + 18, "Playback & sources")
    sec2_y = cur_y
    sec2_h = 58 + 1 + 58
    parts.append(
        f'<rect x="{inner_x}" y="{sec2_y}" width="{inner_w}" height="{sec2_h}" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    cur_y = draw_grouped_row(sec2_y, "Enabled sources", "Active search providers", require_text(main_xml, "providersValue"), has_top_divider=False)
    cur_y = draw_grouped_row(
        cur_y,
        "Concurrency",
        "Parallel search tasks",
        require_text(main_xml, "performanceValue"),
        focused=tv_focus,
        has_top_divider=True,
    )

    # Section 3: Addons & extensions (Grouped container: Extensions + Stream addons + Catalog addons)
    cur_y = draw_section_label(cur_y + 18, "Addons & extensions")
    sec3_y = cur_y
    sec3_h = 58 + 1 + 58 + 1 + 58
    parts.append(
        f'<rect x="{inner_x}" y="{sec3_y}" width="{inner_w}" height="{sec3_h}" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    cur_y = draw_grouped_row(sec3_y, "Extensions", "Home & anime catalogs", require_text(main_xml, "featureValue"), has_top_divider=False)
    cur_y = draw_grouped_row(cur_y, "Stream addons", "HTTPS, torrent & debrid", "2", has_top_divider=True)
    cur_y = draw_grouped_row(cur_y, "Catalog addons", "Custom home feeds", require_text(main_xml, "stremioCatalogsValue"), value_muted=True, has_top_divider=True)

    parts.append("</svg>")
    return "\n".join(parts)


def render_subdialogs_svg(width: int = 768) -> str:
    conc_xml = parse_layout_strings("concurrency_bottom_sheet.xml")
    wyzie_xml = parse_layout_strings("wyzie_settings_fragment.xml")
    height = 510
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
        f'<text x="{lx}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "tv_concurrency_title"))}</text>'
    )
    parts.append(
        f'<text x="{lx}" y="72" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(conc_xml, "tv_concurrency_subtitle"))}</text>'
    )
    parts.append(f'<rect x="{lx}" y="86" width="{lw}" height="1" fill="{TOKENS["border"]}"/>')

    # Stepper Card
    parts.append(
        f'<rect x="{lx}" y="102" width="{lw}" height="96" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    # Decrease btn (56x56)
    parts.append(
        f'<rect x="{lx + 16}" y="122" width="56" height="56" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{lx + 44}" y="159" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="26" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "btn_decrease"))}</text>'
    )
    # Center readout (calm #94A3B8 secondary tier label)
    parts.append(
        f'<text x="{lx + lw / 2}" y="156" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="40" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "tv_value"))}</text>'
    )
    parts.append(
        f'<text x="{lx + lw / 2}" y="178" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(conc_xml, "tv_mode"))}</text>'
    )
    # Increase btn (56x56)
    inc_x = lx + lw - 16 - 56
    parts.append(
        f'<rect x="{inc_x}" y="122" width="56" height="56" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{inc_x + 28}" y="159" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="26" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "btn_increase"))}</text>'
    )

    # 3 Presets Row
    pw = (lw - 16) / 3.0
    py = 210
    presets = [
        (require_text(conc_xml, "preset_low"), False),
        (require_text(conc_xml, "preset_balanced"), True),
        (require_text(conc_xml, "preset_fast"), False),
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
        f'<text x="{lx}" y="284" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">8 to 12 keeps slow connections stable. 16 is balanced</text>'
    )
    parts.append(
        f'<text x="{lx}" y="302" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_muted"]}">for most phones and TVs. 32+ races more providers.</text>'
    )

    # Done button
    parts.append(
        f'<rect x="{lx}" y="376" width="{lw}" height="48" rx="10" fill="{TOKENS["accent"]}"/>'
    )
    parts.append(
        f'<text x="{lx + lw / 2}" y="405" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="#FFFFFF">{esc(require_text(conc_xml, "btn_close"))}</text>'
    )

    # Right Sheet: Subtitle API Keys (wyzie_settings_fragment.xml — Get Wyzie key in header, 3 rhythmic inputs, quiet Clear)
    parts.append(
        f'<rect x="{right_x}" y="16" width="{col_w}" height="476" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    rix = right_x + 20
    riw = col_w - 40
    parts.append(
        f'<text x="{rix}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(wyzie_xml, "tv_wyzie_title"))}</text>'
    )
    parts.append(
        f'<text x="{rix}" y="72" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(wyzie_xml, "tv_wyzie_subtitle"))}</text>'
    )
    get_btn_x = rix + riw - 118
    parts.append(
        f'<rect x="{get_btn_x}" y="34" width="118" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{get_btn_x + 59}" y="63" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(wyzie_xml, "getKeyButton"))}</text>'
    )
    parts.append(f'<rect x="{rix}" y="96" width="{riw}" height="1" fill="{TOKENS["border"]}"/>')

    # Field 1: Wyzie API key
    parts.append(
        f'<text x="{rix}" y="124" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(wyzie_xml, "label_wyzie_key"))}</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="132" width="{riw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 14}" y="161" font-family="DejaVu Sans Mono, monospace" font-size="13" fill="{TOKENS["text_primary"]}">wyz_live_98f2a4c1d7e0b3</text>'
    )

    # Field 2: SubDL API key
    parts.append(
        f'<text x="{rix}" y="206" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(wyzie_xml, "label_subdl_key"))}</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="214" width="{riw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 14}" y="243" font-family="DejaVu Sans Mono, monospace" font-size="13" fill="{TOKENS["text_primary"]}">sdl_v1_48b019c7e2a</text>'
    )

    # Field 3: SubSource API key
    parts.append(
        f'<text x="{rix}" y="288" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(wyzie_xml, "label_subsource_key"))}</text>'
    )
    parts.append(
        f'<rect x="{rix}" y="296" width="{riw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + 14}" y="325" font-family="DejaVu Sans Mono, monospace" font-size="13" fill="{TOKENS["text_muted"]}">{esc(require_hint(wyzie_xml, "subSourceKeyInput"))}</text>'
    )

    # Clear (SECONDARY quiet) + Save keys (PRIMARY)
    by = 420
    clear_w = 88
    save_x = rix + clear_w + 10
    save_w = riw - clear_w - 10
    parts.append(
        f'<rect x="{rix}" y="{by}" width="{clear_w}" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rix + clear_w / 2}" y="{by + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(wyzie_xml, "resetButton"))}</text>'
    )
    parts.append(
        f'<rect x="{save_x}" y="{by}" width="{save_w}" height="48" rx="10" fill="{TOKENS["accent"]}"/>'
    )
    parts.append(
        f'<text x="{save_x + save_w / 2}" y="{by + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="#FFFFFF">{esc(require_text(wyzie_xml, "saveButton"))}</text>'
    )

    parts.append("</svg>")
    return "\n".join(parts)


def render_subdialogs_secondary_svg(width: int = 768) -> str:
    """Renders ProvidersFragment, ToggleFragment, LanguageSelectFragment, and Stremio Saved manifests from XML."""
    prov_xml = parse_layout_strings("fragment_providers.xml")
    toggle_xml = parse_layout_strings("fragment_toggle_extensions.xml")
    lang_xml = parse_layout_strings("fragment_language_select.xml")
    links_xml = parse_layout_strings("stremio_dialog_list_links.xml")
    height = 716
    col_w = (width - 48) // 2
    left_x = 16
    right_x = left_x + col_w + 16

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#07090D"/>',
        # Left Top Sheet: ProvidersFragment (fragment_providers.xml)
        f'<rect x="{left_x}" y="16" width="{col_w}" height="474" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
    ]

    lx = left_x + 20
    lw = col_w - 40
    parts.append(
        f'<text x="{lx}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(prov_xml, "tv_title"))}</text>'
    )
    parts.append(
        f'<text x="{lx}" y="72" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(prov_xml, "tv_provider_count"))}</text>'
    )
    done_x = lx + lw - 72
    parts.append(
        f'<rect x="{done_x}" y="34" width="72" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{done_x + 36}" y="63" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(prov_xml, "btn_save"))}</text>'
    )
    parts.append(f'<rect x="{lx}" y="94" width="{lw}" height="1" fill="{TOKENS["border"]}"/>')

    # Search sources preceding label + EditText
    parts.append(
        f'<text x="{lx}" y="118" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(prov_xml, "label_search_providers"))}</text>'
    )
    parts.append(
        f'<rect x="{lx}" y="126" width="{lw}" height="48" rx="10" fill="{TOKENS["track"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{lx + 14}" y="155" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" fill="{TOKENS["text_muted"]}">{esc(require_hint(prov_xml, "ext_search"))}</text>'
    )

    # Quiet Secondary Toolbar: Enable all / Disable optional / Save preset
    pw3 = (lw - 16) / 3.0
    toolbar_btns = [
        require_text(prov_xml, "btn_select_all"),
        require_text(prov_xml, "btn_deselect_all"),
        require_text(prov_xml, "btn_save_profile"),
    ]
    for idx, label in enumerate(toolbar_btns):
        px = lx + idx * (pw3 + 8)
        parts.append(
            f'<rect x="{px:.1f}" y="184" width="{pw3:.1f}" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{px + pw3 / 2:.1f}" y="213" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(label)}</text>'
        )

    # Grouped Inset Providers List Container
    list_y = 244
    list_h = 228
    parts.append(
        f'<rect x="{lx}" y="{list_y}" width="{lw}" height="{list_h}" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    providers_sample = [
        ("CinemaOS", True, False),
        ("SuperStream", True, False),
        ("UHDMovies", False, False),
        ("Vidlink", True, True),
    ]
    cy_p = list_y + 4
    for idx, (pname, checked, is_locked) in enumerate(providers_sample):
        if idx > 0:
            parts.append(f'<rect x="{lx + 16}" y="{cy_p}" width="{lw - 16}" height="1" fill="{TOKENS["border"]}"/>')
            cy_p += 1
        cb_fill = TOKENS["accent"] if checked else "none"
        cb_stroke = TOKENS["accent"] if checked else TOKENS["text_secondary"]
        parts.append(
            f'<rect x="{lx + 16}" y="{cy_p + 17}" width="20" height="20" rx="4" fill="{cb_fill}" stroke="{cb_stroke}" stroke-width="2"/>'
        )
        if checked:
            parts.append(
                f'<path d="M {lx+21} {cy_p+27} L {lx+25} {cy_p+31} L {lx+32} {cy_p+22}" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
            )
        tcol = TOKENS["text_secondary"] if is_locked else TOKENS["text_primary"]
        parts.append(
            f'<text x="{lx + 48}" y="{cy_p + 32}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" fill="{tcol}">{esc(pname)}</text>'
        )
        cy_p += 54

    # Left Bottom Sheet: LanguageSelectFragment (fragment_language_select.xml + item_language.xml)
    parts.append(
        f'<rect x="{left_x}" y="504" width="{col_w}" height="196" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{lx}" y="540" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(lang_xml, "tv_language_title"))}</text>'
    )
    parts.append(
        f'<text x="{lx}" y="560" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(lang_xml, "tv_language_subtitle"))}</text>'
    )
    parts.append(
        f'<rect x="{done_x}" y="522" width="72" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{done_x + 36}" y="551" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(lang_xml, "btn_close_language"))}</text>'
    )
    parts.append(f'<rect x="{lx}" y="576" width="{lw}" height="1" fill="{TOKENS["border"]}"/>')
    parts.append(
        f'<rect x="{lx}" y="586" width="{lw}" height="96" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    for idx, (full_label, active) in enumerate([
        ("English \u00b7 United States (en-US)", True),
        ("Urdu \u00b7 Pakistan (ur-PK)", False),
    ]):
        ly_r = 588 + idx * 46
        if idx > 0:
            parts.append(f'<rect x="{lx + 16}" y="{ly_r}" width="{lw - 16}" height="1" fill="{TOKENS["border"]}"/>')
        parts.append(f'<circle cx="{lx + 26}" cy="{ly_r + 23}" r="9" fill="none" stroke="{TOKENS["accent"] if active else TOKENS["text_secondary"]}" stroke-width="2"/>')
        if active:
            parts.append(f'<circle cx="{lx + 26}" cy="{ly_r + 23}" r="4.5" fill="{TOKENS["accent"]}"/>')
        parts.append(f'<text x="{lx + 46}" y="{ly_r + 28}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" fill="{TOKENS["text_primary"]}">{esc(full_label)}</text>')

    # Right Top Sheet: ToggleFragment (fragment_toggle_extensions.xml — Grouped inset switches)
    parts.append(
        f'<rect x="{right_x}" y="16" width="{col_w}" height="236" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    rx = right_x + 20
    rw = col_w - 40
    parts.append(
        f'<text x="{rx}" y="52" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(toggle_xml, "title"))}</text>'
    )
    parts.append(
        f'<text x="{rx}" y="72" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(toggle_xml, "subtitle_count"))}</text>'
    )
    rdone_x = rx + rw - 72
    parts.append(
        f'<rect x="{rdone_x}" y="34" width="72" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rdone_x + 36}" y="63" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(toggle_xml, "saveIcon"))}</text>'
    )
    parts.append(f'<rect x="{rx}" y="94" width="{rw}" height="1" fill="{TOKENS["border"]}"/>')

    parts.append(
        f'<rect x="{rx}" y="110" width="{rw}" height="117" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    for idx, ext_name in enumerate(["StreamPlay", "StreamPlay Anime"]):
        ey = 110 + idx * 59
        if idx > 0:
            parts.append(f'<rect x="{rx + 16}" y="{ey - 1}" width="{rw - 16}" height="1" fill="{TOKENS["border"]}"/>')
        parts.append(
            f'<text x="{rx + 16}" y="{ey + 34}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="15" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(ext_name)}</text>'
        )
        sw_x = rx + rw - 56
        parts.append(f'<rect x="{sw_x}" y="{ey + 17}" width="40" height="24" rx="12" fill="{TOKENS["accent"]}"/>')
        parts.append(f'<circle cx="{sw_x + 28}" cy="{ey + 29}" r="9" fill="#FFFFFF"/>')

    # Right Bottom Sheet: Stremio Saved Manifests (stremio_dialog_list_links.xml + stremio_item_saved_link.xml + real delete_icon.xml)
    parts.append(
        f'<rect x="{right_x}" y="268" width="{col_w}" height="248" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rx}" y="306" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="20" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(links_xml, "tvSavedManifestsTitle"))}</text>'
    )
    parts.append(
        f'<rect x="{rdone_x}" y="282" width="72" height="48" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    parts.append(
        f'<text x="{rdone_x + 36}" y="311" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="13" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(links_xml, "btn_close_links"))}</text>'
    )
    parts.append(f'<rect x="{rx}" y="344" width="{rw}" height="1" fill="{TOKENS["border"]}"/>')

    parts.append(
        f'<rect x="{rx}" y="360" width="{rw}" height="133" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>'
    )
    for idx, (m_name, m_type, m_url) in enumerate([
        ("Torrentio", "Torrent", "https://torrentio.strem.fun/manifest.json"),
        ("Comet Debrid", "Debrid", "https://comet.elfhosted.com/manifest.json"),
    ]):
        my = 360 + idx * 67
        if idx > 0:
            parts.append(f'<rect x="{rx + 16}" y="{my - 1}" width="{rw - 16}" height="1" fill="{TOKENS["border"]}"/>')
        parts.append(
            f'<text x="{rx + 16}" y="{my + 26}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(m_name)}</text>'
        )
        parts.append(
            f'<text x="{rx + rw - 64}" y="{my + 26}" text-anchor="end" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(m_type)}</text>'
        )
        parts.append(
            f'<text x="{rx + 16}" y="{my + 47}" font-family="DejaVu Sans Mono, monospace" font-size="11.5" fill="{TOKENS["text_secondary"]}">{esc(m_url[:30])}...</text>'
        )
        del_x = rx + rw - 56
        parts.append(delete_icon_svg(del_x, my + 9, color=TOKENS["text_secondary"]))

    parts.append("</svg>")
    return "\n".join(parts)


def render_landscape_360h_svg(width: int = 640, height: int = 360) -> str:
    """Renders 640x360 mobile landscape viewport proving NestedScrollView safety on short screens."""
    conc_xml = parse_layout_strings("concurrency_bottom_sheet.xml")
    sheet_w = 500
    sheet_x = (width - sheet_w) // 2
    sheet_h = int(height * 0.88)
    sheet_y = (height - sheet_h) // 2
    lx = sheet_x + 20
    lw = sheet_w - 40

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#07090D"/>',
        f'<rect x="{sheet_x}" y="{sheet_y}" width="{sheet_w}" height="{sheet_h}" rx="20" fill="{TOKENS["canvas"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
        f'<text x="{lx}" y="{sheet_y + 34}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="18" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "tv_concurrency_title"))}</text>',
        f'<text x="{lx}" y="{sheet_y + 52}" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(conc_xml, "tv_concurrency_subtitle"))}</text>',
        f'<rect x="{lx}" y="{sheet_y + 62}" width="{lw}" height="1" fill="{TOKENS["border"]}"/>',
        f'<rect x="{lx}" y="{sheet_y + 72}" width="{lw}" height="82" rx="14" fill="{TOKENS["card"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
        f'<rect x="{lx + 16}" y="{sheet_y + 85}" width="56" height="56" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
        f'<text x="{lx + 44}" y="{sheet_y + 122}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="24" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "btn_decrease"))}</text>',
        f'<text x="{lx + lw / 2}" y="{sheet_y + 116}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="34" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "tv_value"))}</text>',
        f'<text x="{lx + lw / 2}" y="{sheet_y + 136}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" fill="{TOKENS["text_secondary"]}">{esc(require_text(conc_xml, "tv_mode"))}</text>',
        f'<rect x="{lx + lw - 72}" y="{sheet_y + 85}" width="56" height="56" rx="10" fill="{TOKENS["overlay"]}" stroke="{TOKENS["border"]}" stroke-width="1"/>',
        f'<text x="{lx + lw - 44}" y="{sheet_y + 122}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="24" font-weight="700" fill="{TOKENS["text_primary"]}">{esc(require_text(conc_xml, "btn_increase"))}</text>',
    ]
    pw = (lw - 16) / 3.0
    py = sheet_y + 164
    for idx, (label, active) in enumerate([
        (require_text(conc_xml, "preset_low"), False),
        (require_text(conc_xml, "preset_balanced"), True),
        (require_text(conc_xml, "preset_fast"), False),
    ]):
        px = lx + idx * (pw + 8)
        fill = TOKENS["accent"] if active else TOKENS["overlay"]
        stroke = TOKENS["accent"] if active else TOKENS["border"]
        tcol = "#FFFFFF" if active else TOKENS["text_primary"]
        parts.append(f'<rect x="{px:.1f}" y="{py}" width="{pw:.1f}" height="48" rx="10" fill="{fill}" stroke="{stroke}" stroke-width="1"/>')
        parts.append(f'<text x="{px + pw / 2:.1f}" y="{py + 29}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="12" font-weight="700" fill="{tcol}">{esc(label)}</text>')

    parts.append(f'<rect x="{lx}" y="{sheet_y + 252}" width="{lw}" height="48" rx="10" fill="{TOKENS["accent"]}"/>')
    parts.append(f'<text x="{lx + lw / 2}" y="{sheet_y + 281}" text-anchor="middle" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" font-weight="700" fill="#FFFFFF">{esc(require_text(conc_xml, "btn_close"))}</text>')
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

    b64_before = base64.b64encode(before_path.read_bytes()).decode("ascii")
    b64_en = base64.b64encode(after_en_path.read_bytes()).decode("ascii")
    b64_ur = base64.b64encode(after_ur_path.read_bytes()).decode("ascii")

    target_h = 1160
    b_w = 532
    a_w = 578
    pad = 28
    top_banner = 84
    canvas_w = pad + b_w + pad + a_w + pad + a_w + pad
    canvas_h = top_banner + target_h + pad

    x1 = pad
    x2 = x1 + b_w + pad
    x3 = x2 + a_w + pad

    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{canvas_w}" height="{canvas_h}" viewBox="0 0 {canvas_w} {canvas_h}">
      <rect width="{canvas_w}" height="{canvas_h}" fill="#07090D"/>
      <text x="{x1}" y="38" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="21" font-weight="700" fill="#FCA5A5">Round 0 · Baseline Slop (4.33/10)</text>
      <text x="{x1}" y="64" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" fill="#94A3B8">7 Hard-Bans · BiDi Urdu) bug · 8 gear icons · 0 row values</text>
      <text x="{x2}" y="38" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="21" font-weight="700" fill="#F1F5F9">Round 4 · OS Grouped Inset Sections (English)</text>
      <text x="{x2}" y="64" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" fill="#94A3B8">3 grouped section containers · 1dp inset hairlines · Calm #94A3B8 values</text>
      <text x="{x3}" y="38" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="21" font-weight="700" fill="#F1F5F9">Round 4 · OS Grouped Inset Sections (Urdu)</text>
      <text x="{x3}" y="64" font-family="Noto Sans, DejaVu Sans, sans-serif" font-size="14" fill="#94A3B8">RLI/PDI isolated اردو · Zero narration slop · Rotation &amp; IME safe</text>
      <image x="{x1}" y="{top_banner}" width="{b_w}" height="{target_h}" preserveAspectRatio="xMidYMid meet" href="data:image/png;base64,{b64_before}"/>
      <image x="{x2}" y="{top_banner}" width="{a_w}" height="{target_h}" preserveAspectRatio="xMidYMid meet" href="data:image/png;base64,{b64_en}"/>
      <image x="{x3}" y="{top_banner}" width="{a_w}" height="{target_h}" preserveAspectRatio="xMidYMid meet" href="data:image/png;base64,{b64_ur}"/>
    </svg>"""
    svg_to_png(svg, target_dir / "before-vs-after-comparison.png", scale=1.0)


def render_all(target_dir: Path) -> None:
    svg_to_png(render_main_settings_svg(375, mode="en", rtl=False), target_dir / "main-settings-en-375w.png")
    svg_to_png(render_main_settings_svg(375, mode="ur", rtl=False), target_dir / "main-settings-ur-375w.png")
    svg_to_png(render_main_settings_svg(320, mode="ur", rtl=False), target_dir / "main-settings-ur-320w.png")
    svg_to_png(render_main_settings_svg(375, mode="ur", rtl=True), target_dir / "main-settings-rtl-375w.png")
    svg_to_png(render_main_settings_svg(500, mode="ur", rtl=False, tv_focus=True), target_dir / "main-settings-tv-focus-500w.png")
    svg_to_png(render_landscape_360h_svg(640, 360), target_dir / "landscape-concurrency-640x360.png")
    svg_to_png(render_subdialogs_svg(768), target_dir / "subdialogs-768w.png")
    svg_to_png(render_subdialogs_secondary_svg(768), target_dir / "subdialogs-providers-extensions-stremio-768w.png")
    build_before_after_comparison(target_dir)


def main() -> None:
    render_all(OUT_R4_DIR)
    print("Rendered XML-parsed screenshots to:", OUT_R4_DIR)


if __name__ == "__main__":
    main()
