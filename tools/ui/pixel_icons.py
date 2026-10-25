#!/usr/bin/env python3
"""Source of the launcher's original 12x12 pixel-art icons.

`python3 tools/ui/pixel_icons.py sheet out.png` renders a preview sheet;
`python3 tools/ui/pixel_icons.py kotlin <PixelIcons.kt>` regenerates the Kotlin table.
Palette chars: '.' transparent, 'k' outline, '#' tint, '=' tint dark, '+' tint light, others fixed colours (see PALETTE).
"""
import sys

PALETTE = {
    'k': 0xFF1E1E1F, 'w': 0xFFFFFFFF, 'l': 0xFFD0D0D0, 's': 0xFF8B8B8B, 'S': 0xFF5A5A5A,
    'g': 0xFF6CC349, 'G': 0xFF3C8527, 'b': 0xFF8B5A2B, 'B': 0xFF5C3A1A, 'y': 0xFFFFD94A, 'Y': 0xFFC8961E,
    'r': 0xFFE05050, 'R': 0xFF9B2C2C, 'c': 0xFF5FE3E3, 'C': 0xFF2AA5A5, 'p': 0xFFE8B88A, 'P': 0xFFC08A5C,
    'h': 0xFF4A3220, 'u': 0xFF4C7BD9, 'U': 0xFF2D4F99, 'o': 0xFFFF9A3C, 'm': 0xFFB45CE0,
}

ICONS = {
    "home": [
        ".....kk.....",
        "....k++k....",
        "...k+##=k...",
        "..k+####=k..",
        ".k+######=k.",
        "kkk######kkk",
        "..k######k..",
        "..k#kkkk#k..",
        "..k#kbbk#k..",
        "..k#kbbk#k..",
        "..k=kbbk=k..",
        "..kkkkkkkk..",
    ],
    "grass": [
        "kkkkkkkkkkkk",
        "kggggggggggk",
        "kgGggggGgggk",
        "kGgGgGgGgGGk",
        "kbGbGbbGbGbk",
        "kbbbbBbbbbbk",
        "kbBbbbbbBbbk",
        "kbbbbbbBbbbk",
        "kbbBbbbbbbBk",
        "kbbbbbBbbbbk",
        "kBbbbbbbbBbk",
        "kkkkkkkkkkkk",
    ],
    "search": [
        "...kkkk.....",
        "..k++++k....",
        ".k+wl##=k...",
        ".k+l####k...",
        ".k+#####k...",
        ".k=####=k...",
        "..k====kk...",
        "...kkkk#=k..",
        ".......k#=k.",
        "........k#=k",
        ".........k=k",
        "..........k.",
    ],
    "download": [
        "....kkkk....",
        "....k++k....",
        "....k#=k....",
        "....k#=k....",
        ".kkkk#=kkkk.",
        ".k+######=k.",
        "..k+####=k..",
        "...k+##=k...",
        "k...k==k...k",
        "k#...kk...#k",
        "k=########=k",
        "kkkkkkkkkkkk",
    ],
    "settings": [
        "....kkkk....",
        ".kk.k++k.kk.",
        "k++kk##kk+=k",
        "k+########=k",
        ".k###kk###k.",
        "kk##k..k##kk",
        "k+##k..k##=k",
        "kk##k..k##kk",
        ".k###kk###k.",
        "k+########=k",
        "k==kk##kk==k",
        ".kk.kkkk.kk.",
    ],
    "play": [
        "..kk........",
        "..k+k.......",
        "..k++k......",
        "..k+##k.....",
        "..k+###k....",
        "..k+####k...",
        "..k+####=k..",
        "..k+###=k...",
        "..k+##=k....",
        "..k+#=k.....",
        "..k==k......",
        "..kkk.......",
    ],
    "pickaxe": [
        "..kkkkkkk...",
        ".kccccccck..",
        "kcCCCCCcCck.",
        "kCk.kkkCck..",
        ".k...kbCk...",
        ".....kbk.k..",
        "....kbk.kck.",
        "...kbk...kk.",
        "..kbk.......",
        ".kbk........",
        "kBk.........",
        "kk..........",
    ],
    "painting": [
        "kkkkkkkkkkkk",
        "kbbbbbbbbbbk",
        "kbuuuuuuuubk",
        "kbuuuyyuuubk",
        "kbuuuyyuuubk",
        "kbuuuuuuuubk",
        "kbuGuuuuGubk",
        "kbGGGuuGGGbk",
        "kbgGGGGGgGbk",
        "kbgggggggGbk",
        "kbbbbbbbbbbk",
        "kkkkkkkkkkkk",
    ],
    "chest": [
        ".kkkkkkkkkk.",
        "kbbbbbbbbbbk",
        "kbBBBBBBBBbk",
        "kbBbbbbbbBbk",
        "kkkkkkkkkkkk",
        "kbbbbyybbbbk",
        "kbBbbYYbbBbk",
        "kbBbbbbbbBbk",
        "kbBbbbbbbBbk",
        "kbBBBBBBBBbk",
        "kbbbbbbbbbbk",
        "kkkkkkkkkkkk",
    ],
    "world": [
        "....kkkk....",
        "..kkuuuukk..",
        ".kuugguuuuk.",
        ".kugggguuuk.",
        "kuuggguuGguk",
        "kuuugguGGguk",
        "kuuuuuuGgguk",
        "kuGguuuuguuk",
        ".kgGguuuuuk.",
        ".kuGguuuUUk.",
        "..kkUUUUkk..",
        "....kkkk....",
    ],
    "book": [
        ".kkkkkkkkkk.",
        "krrrrrrrrrrk",
        "krRRRRRRRRwk",
        "krRyyyyyyRwk",
        "krRRRRRRRRwk",
        "krRRRRRRRRwk",
        "krRRyyyyRRwk",
        "krRRRRRRRRwk",
        "krRRRRRRRRwk",
        "krRRRRRRRRlk",
        "kkkkkkkkkkwk",
        ".kkkkkkkkkk.",
    ],
    "gamepad": [
        "............",
        "..kkkkkkkk..",
        ".k++######k.",
        "k+##kk###r=k",
        "k#kk##kk#k=k",
        "k#kk##kkr#=k",
        "k###kk####=k",
        "k#########=k",
        "k===kkkk===k",
        "k==k....k==k",
        ".kk......kk.",
        "............",
    ],
    "shield": [
        ".kkkkkkkkkk.",
        "k++######==k",
        "k+########=k",
        "k+#######g=k",
        "k+######gg=k",
        "k+g####gg#=k",
        "k+gg##gg##=k",
        ".k#gggg##=k.",
        ".k##gg###=k.",
        "..k#####=k..",
        "...k###=k...",
        "....kkkk....",
    ],
    "record": [
        "............",
        "kkkkkkkk....",
        "k++####k..kk",
        "k+#rrr#k.k=k",
        "k#rrRrr#k#=k",
        "k#rRRRr###=k",
        "k#rrRrr#k#=k",
        "k+#rrr#k.k=k",
        "k==#####k.kk",
        "kkkkkkkk....",
        "............",
        "............",
    ],
    "image": [
        "kkkkkkkkkkkk",
        "kllllllllllk",
        "kluuuuuuyylk",
        "kluuuuuuyylk",
        "kluuuuuuuulk",
        "kluuuGuuuulk",
        "kluuGGGuuulk",
        "klugGGGGuGlk",
        "klggggGGGGlk",
        "klgggggggglk",
        "kllllllllllk",
        "kkkkkkkkkkkk",
    ],
    "head": [
        "kkkkkkkkkkkk",
        "khhhhhhhhhhk",
        "khhhhhhhhhhk",
        "khppppppppPk",
        "kppppppppppk",
        "kpwukppukwpk",
        "kppppPPppppk",
        "kpppPhhPpppk",
        "kpphhhhhhppk",
        "kPpppppppPPk",
        "kPPPPPPPPPPk",
        "kkkkkkkkkkkk",
    ],
    "back": [
        "....kk......",
        "...k+k......",
        "..k+#k......",
        ".k+##kkkkkk.",
        "k+########k.",
        "k#########k.",
        "k=########k.",
        ".k=##kkkkkk.",
        "..k=#k......",
        "...k=k......",
        "....kk......",
        "............",
    ],
    "plus": [
        "....kkkk....",
        "....k++k....",
        "....k#=k....",
        "....k#=k....",
        "kkkkk#=kkkkk",
        "k+#########k",
        "k=====#====k",
        "kkkkk#=kkkkk",
        "....k#=k....",
        "....k#=k....",
        "....k==k....",
        "....kkkk....",
    ],
    "more": [
        "....kkkk....",
        "....k+#k....",
        "....k#=k....",
        "....kkkk....",
        "....kkkk....",
        "....k+#k....",
        "....k#=k....",
        "....kkkk....",
        "....kkkk....",
        "....k+#k....",
        "....k#=k....",
        "....kkkk....",
    ],
    "trash": [
        "....kkkk....",
        "kkkkk==kkkkk",
        "k++######==k",
        "kkkkkkkkkkkk",
        ".k+#=#=#=#k.",
        ".k+#=#=#=#k.",
        ".k+#=#=#=#k.",
        ".k+#=#=#=#k.",
        ".k+#=#=#=#k.",
        ".k+#=#=#=#k.",
        ".k========k.",
        ".kkkkkkkkkk.",
    ],
    "edit": [
        "........kkk.",
        ".......krrRk",
        "......kyyRk.",
        ".....kyyYk..",
        "....kyyYk...",
        "...kyyYk....",
        "..kyyYk.....",
        ".kpyYk......",
        ".kppk.......",
        "kSpk........",
        "kSk.........",
        "kk..........",
    ],
    "check": [
        "............",
        "..........kk",
        ".........kgk",
        "........kgGk",
        ".kk....kgGk.",
        "kgGk..kgGk..",
        "kGgGkkgGk...",
        ".kGgGgGk....",
        "..kGgGk.....",
        "...kGk......",
        "....k.......",
        "............",
    ],
    "close": [
        "kkk......kkk",
        "k+#k....k#=k",
        "kk##k..k##kk",
        ".kk##kk##kk.",
        "..kk####kk..",
        "...k####k...",
        "..kk####kk..",
        ".kk##kk##kk.",
        "kk##k..k##kk",
        "k+#k....k#=k",
        "kkk......kkk",
        "............",
    ],
    "refresh": [
        "...kkkkk..k.",
        "..k+####kk#k",
        ".k+#kkkk=##k",
        "k+#k...k###k",
        "k#k...kkkkkk",
        "k#k......k#k",
        "k#k......k#k",
        "k=#k....k#=k",
        ".k=#kkkk#=k.",
        "..k======k..",
        "...kkkkkk...",
        "............",
    ],
    "folder": [
        "............",
        "kkkkk.......",
        "kyyyyk......",
        "kyYYYykkkkk.",
        "kyyyyyyyyyyk",
        "kkkkkkkkkkkk",
        "kyyyyyyyyyyk",
        "kyyyyyyyyyYk",
        "kyyyyyyyyyYk",
        "kyyyyyyyyyYk",
        "kYYYYYYYYYYk",
        "kkkkkkkkkkkk",
    ],
    "info": [
        "...kkkkkk...",
        ".kk++####kk.",
        "k++##kk###=k",
        "k+###kk###=k",
        "k+########=k",
        "k+###kk###=k",
        "k+###kk###=k",
        "k+###kk###=k",
        "k+###kk###=k",
        "k=========.k",
        ".kk======kk.",
        "...kkkkkk...",
    ],
    "warning": [
        ".....kk.....",
        "....kyyk....",
        "....kyyk....",
        "...kyykyk...",
        "...kykkyk...",
        "..kyykkyyk..",
        "..kyykkyyk..",
        ".kyyykkyyyk.",
        ".kyyyyyyyyk.",
        "kyyyykkyyyyk",
        "kYYYYYYYYYYk",
        "kkkkkkkkkkkk",
    ],
    "sun": [
        ".....kk.....",
        ".k...ky...k.",
        "..k.kyyk.k..",
        "...kyyyyk...",
        "..kyyyyyyk..",
        "kkyyyoyyyykk",
        "kyyyyyyyyoyk",
        "..kyyyoyyk..",
        "...kyyyyk...",
        "..k.kyyk.k..",
        ".k...kyk..k.",
        ".....kk.....",
    ],
    "bolt": [
        "......kkkk..",
        ".....kyyyk..",
        "....kyyyk...",
        "...kyyyk....",
        "..kyyyykkkk.",
        ".kyyyyyyyyk.",
        ".kkkkyyyyk..",
        "....kyyyk...",
        "...kyyyk....",
        "..kyyk......",
        "..kyk.......",
        "..kk........",
    ],
    "star": [
        ".....kk.....",
        ".....kyk....",
        "....kyyk....",
        "kkkkkyyykkkk",
        "kyyyyyyyyyyk",
        ".kyyyyyyyYk.",
        "..kyyyyyYk..",
        "..kyyyyyYk..",
        ".kyyYkkYyYk.",
        ".kyYk..kYyk.",
        "kYYk....kYYk",
        "kkk......kkk",
    ],
    "user_add": [
        "...kkkkk....",
        "..khhhhhk...",
        "..kpwppwk...",
        "..kpppppk...",
        "...kpppk....",
        ".kk#####kk..",
        "k+#######k..",
        "k+######kkkk",
        "k+#####kk+=k",
        "k=====kk++++",
        "kkkkkkkk==kk",
        ".........kk.",
    ],
    "list": [
        "kkkkkkkkkkkk",
        "kwwk+######k",
        "kkkkkkkkkkkk",
        "............",
        "kkkkkkkkkkkk",
        "kwwk+######k",
        "kkkkkkkkkkkk",
        "............",
        "kkkkkkkkkkkk",
        "kwwk+######k",
        "kkkkkkkkkkkk",
        "............",
    ],
    "upload": [
        ".....kk.....",
        "....k++k....",
        "...k+##=k...",
        "..k+####=k..",
        ".kkkk#=kkkk.",
        "....k#=k....",
        "....k#=k....",
        "....k==k....",
        "k...kkkk...k",
        "k#........#k",
        "k=########=k",
        "kkkkkkkkkkkk",
    ],
    "stop": [
        "............",
        ".kkkkkkkkkk.",
        ".k++++####k.",
        ".k+######=k.",
        ".k+######=k.",
        ".k+######=k.",
        ".k+######=k.",
        ".k+######=k.",
        ".k+######=k.",
        ".k=======.k.",
        ".kkkkkkkkkk.",
        "............",
    ],
}


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255, (c >> 24) & 255)


def check():
    known = set(PALETTE) | set(".#=+")
    for name, rows in ICONS.items():
        assert len(rows) == 12, f"{name}: {len(rows)} rows"
        for i, r in enumerate(rows):
            assert len(r) == 12, f"{name} row {i}: {len(r)} '{r}'"
            bad = set(r) - known
            assert not bad, f"{name} row {i}: unknown {bad}"


def sheet(out):
    from PIL import Image
    tint = 0xFFFFFFFF
    scale, cols = 8, 8
    names = list(ICONS)
    rows_n = (len(names) + cols - 1) // cols
    img = Image.new("RGBA", (cols * 14 * scale, rows_n * 14 * scale), (60, 60, 64, 255))
    for idx, n in enumerate(names):
        ox, oy = (idx % cols) * 14 + 1, (idx // cols) * 14 + 1
        for y, r in enumerate(ICONS[n]):
            for x, ch in enumerate(r):
                if ch == '.':
                    continue
                if ch == '#':
                    c = (230, 230, 230, 255)
                elif ch == '=':
                    c = (150, 150, 155, 255)
                elif ch == '+':
                    c = (255, 255, 255, 255)
                else:
                    c = rgb(PALETTE[ch])
                for dy in range(scale):
                    for dx in range(scale):
                        img.putpixel(((ox + x) * scale + dx, (oy + y) * scale + dy), c)
    img.save(out)
    print("names:", ", ".join(names))


def kotlin(out):
    lines = [
        "package pro.cslauncher.app.ui.theme",
        "",
        "// Generated by tools/ui/pixel_icons.py — original 12x12 pixel-art icons. Edit the Python source, not this file.",
        "",
        "object PixelIconData {",
        "    /** Fixed colours (ARGB). '.' transparent, '#' tint, '=' tint dark, '+' tint light. */",
        "    val palette: Map<Char, Long> = mapOf(",
    ]
    lines.append(",\n".join(f"        '{k}' to 0x{v:08X}L" for k, v in PALETTE.items()))
    lines.append("    )")
    lines.append("    val icons: Map<String, List<String>> = mapOf(")
    entries = []
    for n, rows in ICONS.items():
        body = ",\n".join(f'            "{r}"' for r in rows)
        entries.append(f'        "{n}" to listOf(\n{body},\n        )')
    lines.append(",\n".join(entries))
    lines.append("    )")
    lines.append("}")
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    check()
    if sys.argv[1] == "sheet":
        sheet(sys.argv[2])
    else:
        kotlin(sys.argv[2])
