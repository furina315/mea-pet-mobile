#!/usr/bin/env python3
"""Iconify SVG → Android VectorDrawable 批量生成器。

图标源统一走 Iconify API（https://api.iconify.design/{prefix}/{name}.svg），
生成结果直接写入 app/src/main/res/drawable/。

用法：
    python3 tools/svg2vd.py                    # 按 tools/icons.txt 全量生成
    python3 tools/svg2vd.py mdi/palette ic_x   # 单个生成（调试用）

为什么不用现成依赖：MDI / Font Awesome 6+ / Simple Icons 都没有维护中的
Android/Compose 库（DevSrSouza/compose-icons 停在 FA 5.15.2、SimpleIcons 4.14.0），
而 material-icons-extended 已被 Google 冻结在 1.7.8 且 AAR 达 35MB。
Iconify 已把单色图标统一规范化为单 path + currentColor，并剔除脚本/位图/文字，
所以转 VectorDrawable 几乎零踩坑，每个图标只有几百字节。

清单格式（tools/icons.txt），# 开头为注释：
    <iconify 名称>  <资源名>  [mirror]
`mirror` 会给 vector 加 android:autoMirrored="true"（方向性图标如箭头需要）。
"""
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

SVG_NS = "http://www.w3.org/2000/svg"

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "tools" / "icons.txt"
OUT_DIR = REPO_ROOT / "app" / "src" / "main" / "res" / "drawable"

# 允许出现但不影响渲染的标签
_IGNORABLE = {"svg", "path", "g", "title", "desc", "defs"}


def fetch(icon: str) -> str:
    """取回图标 SVG。Iconify 会拒绝默认的 python-urllib UA（403），需显式带 UA。"""
    url = f"https://api.iconify.design/{icon}.svg"
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise ValueError(f"HTTP {e.code}（图标名可能不存在）") from e
    # 图标不存在时 Iconify 返回 404，但个别情况会回一个空 svg
    if "<path" not in body:
        raise ValueError("返回内容里没有 path，图标名可能不存在")
    return body


def convert(svg_text: str, size_dp: int = 24, mirror: bool = False) -> str:
    root = ET.fromstring(svg_text)

    vb = root.get("viewBox")
    if not vb:
        raise ValueError("SVG 缺少 viewBox")
    nums = [float(x) for x in re.split(r"[ ,]+", vb.strip())]
    if len(nums) != 4:
        raise ValueError(f"viewBox 格式异常: {vb}")
    min_x, min_y, vw, vh = nums
    if min_x != 0 or min_y != 0:
        raise ValueError(f"viewBox 原点非 0，需手动平移: {vb}")

    # 出现不支持的图元就报错，避免静默产出错错误图标
    unsupported = {
        el.tag.split("}")[-1]
        for el in root.iter()
        if el.tag.split("}")[-1] not in _IGNORABLE
    }
    if unsupported:
        raise ValueError(f"含不支持的图元 {sorted(unsupported)}，需手动处理")

    paths = []
    for p in root.iter(f"{{{SVG_NS}}}path"):
        d = p.get("d")
        if not d:
            continue
        even_odd = p.get("fill-rule") == "evenodd" or p.get("clip-rule") == "evenodd"
        paths.append((d, even_odd))
    if not paths:
        raise ValueError("没有找到 path")

    lines = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"']
    lines.append(f'    android:width="{size_dp}dp"')
    lines.append(f'    android:height="{size_dp}dp"')
    lines.append(f'    android:viewportWidth="{vw:g}"')
    lines.append(f'    android:viewportHeight="{vh:g}"')
    if mirror:
        lines.append('    android:autoMirrored="true"')
    lines[-1] += ">"
    for d, even_odd in paths:
        lines.append("    <path")
        # 固定黑色；实际颜色在 Compose 侧由 Icon(tint = ...) 控制，跟随主题
        lines.append('        android:fillColor="#FF000000"')
        if even_odd:
            lines.append('        android:fillType="evenOdd"')
        lines.append(f'        android:pathData="{d}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def parse_manifest(path: Path) -> list[tuple[str, str, bool]]:
    entries = []
    # utf-8-sig：清单可能带 BOM，而 str.strip() 不会去掉 \ufeff
    text = path.read_text(encoding="utf-8-sig")
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2:
            raise ValueError(f"{path}:{lineno} 格式错误: {raw!r}")
        icon, name = parts[0], parts[1]
        mirror = len(parts) > 2 and parts[2] == "mirror"
        entries.append((icon, name, mirror))
    return entries


def generate(entries: list[tuple[str, str, bool]], out_dir: Path) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for icon, name, mirror in entries:
        dest = out_dir / f"{name}.xml"
        try:
            dest.write_text(convert(fetch(icon), mirror=mirror), encoding="utf-8")
            print(f"  OK   {icon:<34} -> {dest.name} ({dest.stat().st_size} B)")
        except Exception as e:  # noqa: BLE001 - 逐个报错并继续，最后统一汇总
            failed += 1
            print(f"  FAIL {icon:<34} -> {e}")
    print(f"\n共 {len(entries)} 个，失败 {failed} 个。输出目录: {out_dir}")
    return 1 if failed else 0


def main() -> int:
    if len(sys.argv) == 3:
        icon, name = sys.argv[1], sys.argv[2]
        return generate([(icon, name, False)], OUT_DIR)
    if len(sys.argv) == 1:
        if not MANIFEST.exists():
            print(f"清单不存在: {MANIFEST}")
            return 2
        return generate(parse_manifest(MANIFEST), OUT_DIR)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
