#!/usr/bin/env python3
"""校验 12 套主题预设在聊天界面的 WCAG 对比度是否达标。

复刻 ui/theme/Color.kt 的配色推导算法（lighten / darken / desaturate / hueShift
都在 sRGB 编码空间做，与 Compose Color 的分量语义一致），然后按 WCAG 2.1 计算
相对亮度与对比度。

新增预设或调整推导系数后跑一遍：
    python3 tools/contrast_check.py

退出码非 0 表示有配对未达 4.5:1，CI 里可以直接当断言用。

历史背景：早期 lightScheme 用 `primary = seed` + `onPrimary = seed.darken(0.6f)`，
12 套预设的用户气泡对比度只有 1.89~3.90:1，浅色模式下几乎看不清；深色模式因为
用了 lighten(0.5)/darken(0.85) 这种大力度所以一直正常。本脚本就是为了守住这条线。
"""
import sys

# 与 Color.kt 的 S_* 常量保持一致
SEEDS = {
    "紫罗兰 default": 0x6650A4,
    "海洋 ocean": 0x006492,
    "森林 forest": 0x2E7D32,
    "日落 sunset": 0xB85C00,
    "玫瑰 rose": 0xB54E6A,
    "单色 mono": 0x4A4A4A,
    "晴空 sky": 0x5B8FA8,
    "鼠尾草 sage": 0x6B9E7A,
    "丁香 lilac": 0x9A7FA8,
    "陶土 terracotta": 0xA87A6A,
    "琥珀 amber": 0xB59A5A,
    "钢蓝 steel": 0x6A7A8A,
}

WCAG_BODY = 4.5  # 正文最低对比度

# 聊天界面实际用到的前景/背景配对，见 ui/component/ChatBubble.kt
PAIRS = [
    ("用户气泡", "onPrimary", "primary"),
    ("助手气泡", "onSurfaceVariant", "surfaceVariant"),
    ("系统横幅", "onPrimaryContainer", "primaryContainer"),
    ("提示气泡", "onTertiaryContainer", "tertiaryContainer"),
]


def rgb(hexv):
    return ((hexv >> 16 & 255) / 255, (hexv >> 8 & 255) / 255, (hexv & 255) / 255)


def lighten(c, f):
    return tuple(min(1.0, x + (1.0 - x) * f) for x in c)


def darken(c, f):
    return tuple(max(0.0, x * (1.0 - f)) for x in c)


def desaturate(c, f):
    return tuple(x * (1.0 - f) + 0.5 * f for x in c)


def luminance(c):
    def lin(x):
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def light_scheme(seed):
    """对应 Color.kt 的 lightScheme()。"""
    s = desaturate(seed, 0.35)
    t = desaturate(seed, 0.6)
    return {
        "primary": darken(seed, 0.35), "onPrimary": lighten(seed, 0.95),
        "primaryContainer": lighten(seed, 0.82), "onPrimaryContainer": darken(seed, 0.6),
        "secondary": darken(s, 0.35), "onSecondary": lighten(s, 0.95),
        "surfaceVariant": lighten(seed, 0.92), "onSurfaceVariant": darken(seed, 0.3),
        "tertiary": darken(t, 0.35), "onTertiary": lighten(t, 0.95),
        "tertiaryContainer": lighten(t, 0.77), "onTertiaryContainer": darken(t, 0.6),
    }


def dark_scheme(seed):
    """对应 Color.kt 的 darkScheme()。"""
    sp = lighten(seed, 0.5)
    s = desaturate(sp, 0.35)
    t = desaturate(sp, 0.6)
    return {
        "primary": sp, "onPrimary": darken(sp, 0.85),
        "primaryContainer": darken(sp, 0.6), "onPrimaryContainer": lighten(sp, 0.7),
        "secondary": s, "onSecondary": darken(s, 0.85),
        "surfaceVariant": darken(sp, 0.75), "onSurfaceVariant": lighten(sp, 0.4),
        "tertiary": t, "onTertiary": darken(t, 0.85),
        "tertiaryContainer": darken(t, 0.55), "onTertiaryContainer": lighten(t, 0.7),
    }


def main() -> int:
    failures = []
    for mode, builder in (("浅色", light_scheme), ("深色", dark_scheme)):
        print(f"\n{mode}模式（正文需 ≥{WCAG_BODY}:1）")
        print("-" * 74)
        print(f"{'预设':<16}" + "".join(f"{name:>14}" for name, _, _ in PAIRS))
        for preset, hexv in SEEDS.items():
            scheme = builder(rgb(hexv))
            row = f"{preset:<16}"
            for label, fg, bg in PAIRS:
                cr = contrast(scheme[fg], scheme[bg])
                ok = cr >= WCAG_BODY
                if not ok:
                    failures.append(f"{mode}/{preset}/{label} = {cr:.2f}:1")
                row += f"{cr:>11.2f}{'✓' if ok else '✗':>3}"
            print(row)

    print()
    if failures:
        print(f"未达标 {len(failures)} 项：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print(f"全部达标（{len(SEEDS)} 套预设 × {len(PAIRS)} 组配对 × 2 种模式）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
