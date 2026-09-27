"""Install our deterministic API-21 / Android TV launcher identity in CI."""
import io
import os
import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent
ANDROID = 'http://schemas.android.com/apk/res/android'
A = '{' + ANDROID + '}'
SVG = '{http://www.w3.org/2000/svg}'
NAME = '抖音抬头版'
BACKGROUND = '#09090B'


def _write_xml(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


def _svg_bytes(mark, background=False, legacy=False):
    root = ET.Element(SVG + 'svg', {
        'width': '108', 'height': '108',
        'viewBox': '18 18 72 72' if legacy else '0 0 108 108',
    })
    if background:
        ET.SubElement(root, SVG + 'rect', {
            'width': '108', 'height': '108', 'fill': BACKGROUND,
        })
    for path in mark.findall(SVG + 'path'):
        ET.SubElement(root, path.tag, dict(path.attrib))
    return ET.tostring(root, encoding='utf-8')


def _vector(mark, monochrome=False):
    root = ET.Element('vector', {
        A + 'width': '108dp', A + 'height': '108dp',
        A + 'viewportWidth': '108', A + 'viewportHeight': '108',
    })
    for path in mark.findall(SVG + 'path'):
        if monochrome and path.get('fill') != '#FFFFFF':
            continue
        attrs = {A + 'fillColor': path.get('fill'), A + 'pathData': path.get('d')}
        if path.get('fill-rule') == 'evenodd':
            attrs[A + 'fillType'] = 'evenOdd'
        ET.SubElement(root, 'path', attrs)
    return root


def apply(decoded):
    # Rendering dependencies belong in CI; importing build tooling does no rendering.
    import cairosvg
    from PIL import Image, ImageDraw, ImageFont

    ET.register_namespace('android', ANDROID)
    decoded = pathlib.Path(decoded)
    res = decoded / 'res'
    mark = ET.parse(ROOT / 'branding/mark.svg').getroot()
    square = _svg_bytes(mark, background=True, legacy=True)
    foreground = _svg_bytes(mark)
    for density, pixels in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                            ('xxhdpi', 144), ('xxxhdpi', 192)]:
        output = res / ('mipmap-' + density) / 'ta_tou_launcher.png'
        output.parent.mkdir(parents=True, exist_ok=True)
        cairosvg.svg2png(bytestring=square, write_to=str(output),
                        output_width=pixels, output_height=pixels)

    # API 21 uses the PNGs. fillType is API 24+, and these vectors are API 26-only.
    _write_xml(res / 'drawable-v26/ta_tou_foreground.xml', _vector(mark))
    _write_xml(res / 'drawable-v33/ta_tou_monochrome.xml', _vector(mark, monochrome=True))
    colors = ET.Element('resources')
    ET.SubElement(colors, 'color', {'name': 'ta_tou_background'}).text = BACKGROUND
    _write_xml(res / 'values/ta_tou_branding.xml', colors)
    for version in [26, 33]:
        adaptive = ET.Element('adaptive-icon')
        ET.SubElement(adaptive, 'background', {A + 'drawable': '@color/ta_tou_background'})
        ET.SubElement(adaptive, 'foreground', {A + 'drawable': '@drawable/ta_tou_foreground'})
        if version >= 33:
            ET.SubElement(adaptive, 'monochrome', {A + 'drawable': '@drawable/ta_tou_monochrome'})
        _write_xml(res / f'mipmap-anydpi-v{version}/ta_tou_launcher.xml', adaptive)

    font_path = pathlib.Path(os.environ.get(
        'BRANDING_CJK_FONT', '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc'))
    if not font_path.is_file():
        raise RuntimeError('Install fonts-noto-cjk in CI before generating the TV banner')
    # Draw at 4× and downsample for clean television-scale Chinese glyphs.
    scale = 4
    banner = Image.new('RGB', (320 * scale, 180 * scale), BACKGROUND)
    glyph = Image.open(io.BytesIO(cairosvg.svg2png(
        bytestring=foreground, output_width=160 * scale, output_height=160 * scale)))
    banner.paste(glyph, (-10 * scale, 5 * scale), glyph)
    draw = ImageDraw.Draw(banner)
    # TTC face 2 in the distro Noto Sans CJK collection is Simplified Chinese.
    font = ImageFont.truetype(str(font_path), 28 * scale, index=2)
    bounds = draw.textbbox((0, 0), NAME, font=font)
    width = bounds[2] - bounds[0]
    height = bounds[3] - bounds[1]
    if width > 156 * scale:
        raise RuntimeError('TV banner title exceeds its safe text area')
    draw.text((140 * scale - bounds[0], 90 * scale - height / 2 - bounds[1]),
              NAME, font=font, fill='#FFFFFF')
    banner_path = res / 'drawable-xhdpi/ta_tou_banner.png'
    banner_path.parent.mkdir(parents=True, exist_ok=True)
    banner.resize((320, 180), Image.Resampling.LANCZOS).save(banner_path)

    manifest_path = decoded / 'AndroidManifest.xml'
    manifest = ET.parse(manifest_path)
    root = manifest.getroot()
    app = root.find('application')
    if app is None:
        raise RuntimeError('Decoded manifest has no application')
    identity = {A + 'icon': '@mipmap/ta_tou_launcher',
                A + 'roundIcon': '@mipmap/ta_tou_launcher',
                A + 'banner': '@drawable/ta_tou_banner', A + 'label': NAME}
    app.attrib.update(identity)
    launchers = []
    for node in app:
        if node.tag not in ['activity', 'activity-alias']:
            continue
        launcher_filters = [intent for intent in node.findall('intent-filter')
                            if any(action.get(A + 'name') == 'android.intent.action.MAIN'
                                   for action in intent.findall('action'))
                            and any(category.get(A + 'name') in (
                                'android.intent.category.LAUNCHER',
                                'android.intent.category.LEANBACK_LAUNCHER')
                                    for category in intent.findall('category'))]
        if launcher_filters:
            launchers.append(node)
            node.attrib.update(identity)
            # Android TV must be able to discover the actual MAIN entry point.
            if not any(category.get(A + 'name') == 'android.intent.category.LEANBACK_LAUNCHER'
                       for intent in launcher_filters for category in intent.findall('category')):
                ET.SubElement(launcher_filters[0], 'category', {
                    A + 'name': 'android.intent.category.LEANBACK_LAUNCHER'})
        else:
            for key in ['icon', 'roundIcon', 'banner']:
                if A + key in node.attrib:
                    node.set(A + key, identity[A + key])
    if not launchers:
        raise RuntimeError('No MAIN launcher activity to brand')
    for name, required in [('android.software.leanback', 'true'),
                           ('android.hardware.touchscreen', 'false')]:
        feature = next((node for node in root.findall('uses-feature')
                        if node.get(A + 'name') == name), None)
        if feature is None:
            feature = ET.Element('uses-feature', {A + 'name': name})
            root.insert(0, feature)
        feature.set(A + 'required', required)
    manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)

    previews = decoded.parent / 'branding-preview'
    previews.mkdir(parents=True, exist_ok=True)
    cairosvg.svg2png(bytestring=square, write_to=str(previews / 'icon.png'),
                    output_width=512, output_height=512)
    banner.save(previews / 'banner.png')
    print('BRANDING:', len(launchers), 'launcher entries; previews:', previews)
