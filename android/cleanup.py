"""Auditable compatibility patches for the fixed upstream Lite 1.1.9 input.

Only this pinned binary is supported. Every method replacement fails closed if
its signature changes. The readable implementation lives in src/, not smali.
"""
import pathlib
import json
import os
import re
import xml.etree.ElementTree as ET

METHOD = re.compile(r'(?ms)^\.method [^\n]+\n.*?^\.end method')


def legacy_theme(package):
    """Only audited app-owned literals and drawing calls; never runtime user text."""
    labels = {
        '🔴 直播': '直播', ' 🖼️ ': ' 图集 ', '❤ ': '赞 ', '♥ ': '赞 ',
        '  加载更多 ▼  ': '加载更多', '🔥 ': '', '← 返回': '返回',
        '  ⭐ 精选': '精选', '🔄 换一批': '换一批', '🔥 热搜  ': '热搜',
        '🔴 ': '', '👥 ': '观众 ', '←': '返回', '🔍': '搜索', '✕': '清空',
        '📭  ': '', '🎬 ': '', '👤 ': '', '🔍  ': '', '❌ ': '加载失败：',
        '   ❤️ ': '   粉丝 ', '   🔥 ': '   获赞 ', '›': '进入',
    }
    owners = ('ProfileActivity', 'FeaturedActivity', 'SearchActivity')
    drawing = {
        ('setBackgroundColor', 'I'): ('backgroundColor', 'Landroid/view/View;I'),
        ('setBackground', 'Landroid/graphics/drawable/Drawable;'):
            ('background', 'Landroid/view/View;Landroid/graphics/drawable/Drawable;'),
        ('setTextColor', 'I'): ('textColor', 'Landroid/widget/TextView;I'),
        ('setOnFocusChangeListener', 'Landroid/view/View$OnFocusChangeListener;'):
            ('focusListener', 'Landroid/view/View;Landroid/view/View$OnFocusChangeListener;'),
    }
    encoded_labels = {json.dumps(old, ensure_ascii=True): json.dumps(new, ensure_ascii=True)
                      for old, new in labels.items()}
    seen_labels = set()
    replacements = 0
    focus_replacements = 0
    def normalized(literal):
        return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: '\\u' + m.group(1).lower(), literal)
    for file in package.glob('*.smali'):
        if not file.name.startswith(owners):
            continue
        body = file.read_text()
        if file.name == 'ProfileActivity.smali':
            assert '.method private animateIndicator(Landroid/widget/TextView;)V' in body
            body = replace_method(body, 'animateIndicator', '''    .locals 1
    iget-object v0, p0, Lcom/dycomment/tv/ProfileActivity;->tabIndicator:Landroid/view/View;
    invoke-static {p1, v0}, Lcom/dycomment/tv/LegacyTheme;->alignIndicator(Landroid/widget/TextView;Landroid/view/View;)V
    return-void''')
        # These two Search controls install an asynchronous animator, not navigation logic.
        # Drop the color arguments and install only our focus policy; click handlers stay intact.
        body, focus_count = re.subn(
            r'invoke-static \{([vp]\d+), [vp]\d+, [vp]\d+\}, '
            r'Lcom/dycomment/tv/AnimHelper;->setupFocusAnim\(Landroid/view/View;II\)V',
            lambda m: 'invoke-static {' + m.group(1)
            + '}, Lcom/dycomment/tv/LegacyTheme;->setupFocus(Landroid/view/View;)V', body)
        assert focus_count == (2 if file.name == 'SearchActivity.smali' else 0), 'pinned legacy focus animator calls changed'
        assert 'Lcom/dycomment/tv/AnimHelper;->setupFocusAnim' not in body, 'legacy focus animator remains'
        focus_replacements += focus_count
        def label(match):
            nonlocal replacements
            literal = normalized(match.group(2))
            replacement = encoded_labels.get(literal, literal)
            if literal in encoded_labels:
                seen_labels.add(literal)
                replacements += 1
            if file.name == 'ProfileActivity$ProfileCallbackImpl.smali' and literal == json.dumps('👥 ', ensure_ascii=True):
                replacement = json.dumps('关注 ', ensure_ascii=True)
            return match.group(1) + (replacement if literal in encoded_labels else match.group(2))
        body = re.sub(r'(const-string(?:/jumbo)? [vp]\d+, )("(?:[^"\\]|\\.)*")', label, body)
        remaining = re.findall(r'const-string(?:/jumbo)? [vp]\d+, ("(?:[^"\\]|\\.)*")', body)
        assert not any(normalized(value) in encoded_labels for value in remaining), 'legacy UI label replacement incomplete'
        for (method, signature), (target, arguments) in drawing.items():
            pattern = (r'invoke-virtual(?P<range>/range)? (?P<args>\{[^}]+\}), '
                       r'Landroid/(?:view|widget)/[\w$]+;->' + method
                       + r'\(' + re.escape(signature) + r'\)V')
            body = re.sub(pattern, lambda m: 'invoke-static' + (m.group('range') or '')
                          + ' ' + m.group('args') + ', Lcom/dycomment/tv/LegacyTheme;->'
                          + target + '(' + arguments + ')V', body)
        body = re.sub(r'invoke-virtual(?P<range>/range)? (?P<args>\{[^}]+\}), '
                      r'Landroid/graphics/drawable/GradientDrawable;->setColor\(I\)V',
                      lambda m: 'invoke-static' + (m.group('range') or '') + ' ' + m.group('args')
                      + ', Lcom/dycomment/tv/LegacyTheme;->drawableColor(Landroid/graphics/drawable/GradientDrawable;I)V', body)
        body = re.sub(r'invoke-virtual(?P<range>/range)? (?P<args>\{[^}]+\}), '
                      r'Landroid/graphics/drawable/GradientDrawable;->setShape\(I\)V',
                      lambda m: 'invoke-static' + (m.group('range') or '') + ' ' + m.group('args')
                      + ', Lcom/dycomment/tv/LegacyTheme;->drawableShape(Landroid/graphics/drawable/GradientDrawable;I)V', body)
        if file.stem in owners:
            marker = f'    invoke-direct {{p0}}, Lcom/dycomment/tv/{file.stem};->buildUI()V'
            assert body.count(marker) == 1, (file.name, 'buildUI hook')
            body = body.replace(marker, marker + '\n    invoke-static {p0}, Lcom/dycomment/tv/LegacyTheme;->attach(Landroid/app/Activity;)V')
            if os.environ.get('SELF_TEST') == '1':
                fixture = '''
    invoke-static {p0}, Lcom/dycomment/tv/LegacyUiFixture;->show(Landroid/app/Activity;)Z
    move-result p1
    if-eqz p1, :android5_normal_legacy_page
    return-void
    :android5_normal_legacy_page
'''
                hook = '    invoke-static {p0}, Lcom/dycomment/tv/LegacyTheme;->attach(Landroid/app/Activity;)V'
                body = body.replace(hook, hook + fixture)
        file.write_text(body)
    assert seen_labels == set(encoded_labels), 'pinned legacy UI labels changed; review the source export'
    assert focus_replacements == 2, 'expected exactly two legacy focus animator replacements'
    main = package / 'MainActivity.smali'
    body = main.read_text()
    for old, new in [('🔴 ', ''), ('⏩ +5s', '快进 +5s'), ('⏪ -5s', '快退 -5s')]:
        old_literal = json.dumps(old, ensure_ascii=True)
        pattern = r'(const-string(?:/jumbo)? [vp]\d+, )' + re.escape(old_literal)
        body, count = re.subn(pattern, lambda m: m.group(1) + json.dumps(new, ensure_ascii=True), body)
        assert count == 1, 'pinned MainActivity plain-text label changed'
        replacements += count
        assert old_literal not in body, 'old MainActivity UI label remains'
    main.write_text(body)
    print('LEGACY UI: replaced', replacements, 'audited static labels and', focus_replacements,
          'legacy focus animators; all drawing hooks installed')


def replace_method(text, name, instructions):
    count = 0
    def patch(match):
        nonlocal count
        head = match.group().splitlines()[0]
        if re.search(r' ' + re.escape(name) + r'\(', head):
            count += 1
            return head + '\n' + instructions + '\n.end method'
        return match.group()
    result = METHOD.sub(patch, text)
    assert count == 1, (name, count)
    return result


def apply(decoded):
    package = decoded / 'smali/com/dycomment/tv'
    for prefix in ['MsTokenHelper','VideoProxyServer']:
        for file in package.glob(prefix+'*.smali'):
            file.unlink()
    main = package / 'MainActivity.smali'
    text = main.read_text()
    for name, target in [('showComments', 'CommentsPanel;->show'),
                         ('showCookieDialog', 'CredentialHealth;->open'),
                         ('showMsTokenInputDialog', 'CredentialHealth;->open')]:
        text = replace_method(text, name, '    .locals 0\n    invoke-static {p0}, Lcom/dycomment/tv/' + target + '(Landroid/app/Activity;)V\n    return-void')
    # Remove the old HTTP server, raw credential editors, music launcher and old About text.
    for name in ['showManualCookieDialog', 'showMsTokenManualDialog', 'showLanPushDialog',
                 'showTokenLanPushDialog', 'startCookieServer', 'stopCookieServer',
                 'handleCookieRequest', 'resetCookie', 'openQishui', 'showAbout', 'handleCommentEmpty', 'showPersonalizationMenu']:
        text = replace_method(text, name, '    .locals 0\n    return-void')
    for name in ['getPushPageHtml', 'getTokenPushPageHtml', 'getLocalIpAddress']:
        text = replace_method(text, name, '    .locals 1\n    const-string v0, ""\n    return-object v0')
    main.write_text(text)

    # Remove every copy of the upstream shared account, including inlined constants.
    api = package / 'DouyinApi.smali'
    text = api.read_text()
    cookie_field = '.field private static cookie:Ljava/lang/String;'
    assert text.count(cookie_field) == 1, 'pinned credential field changed'
    text = text.replace(cookie_field, '.field private static volatile cookie:Ljava/lang/String;')
    api.write_text(text)
    secret = re.search(r'\.field private static final DEFAULT_COOKIE:Ljava/lang/String; = (".*")', text).group(1)
    for file in package.glob('*.smali'):
        body = file.read_text()
        if secret in body:
            file.write_text(body.replace(secret, '""'))

    # The three-argument overload is the central upstream read path.
    api = package / 'DouyinApi.smali'
    text = api.read_text()
    pattern = r'(?ms)^\.method private static safeGet\(Ljava/lang/String;Ljava/util/Map;Z\)Lorg/json/JSONObject;\n.*?^\.end method'
    replacement = """.method private static safeGet(Ljava/lang/String;Ljava/util/Map;Z)Lorg/json/JSONObject;
    .locals 1
    invoke-static {p0, p1, p2}, Lcom/dycomment/tv/LegacyRequests;->get(Ljava/lang/String;Ljava/util/Map;Z)Lorg/json/JSONObject;
    move-result-object v0
    return-object v0
.end method"""
    text,count=re.subn(pattern,lambda _:replacement,text); assert count==1
    api.write_text(text)

    profile = package / 'ProfileActivity.smali' 
    text = replace_method(profile.read_text(), 'loadThumbnail', '''    .locals 0
    invoke-static {p0, p1, p2}, Lcom/dycomment/tv/ProfileImages;->bind(Landroid/app/Activity;Ljava/lang/String;Landroid/widget/ImageView;)V
    return-void''')
    text = replace_method(text, 'lambda$loadThumbnail$11$com-dycomment-tv-ProfileActivity', """    .locals 0
    invoke-static {p0, p1, p2}, Lcom/dycomment/tv/ProfileImages;->fromWorker(Landroid/app/Activity;Ljava/lang/String;Landroid/widget/ImageView;)V
    return-void""")
    marker = '    invoke-super {p0}, Landroid/app/Activity;->onDestroy()V' 
    assert marker in text
    text = text.replace(marker, marker + '\n    invoke-static {p0}, Lcom/dycomment/tv/ProfileImages;->close(Landroid/app/Activity;)V')
    profile.write_text(text)
    for number in [21, 22]:
        file = package / f'ProfileActivity${number}.smali'
        owner = f'Lcom/dycomment/tv/ProfileActivity${number};'
        file.write_text(replace_method(file.read_text(), 'run', f'''    .locals 3
    iget-object v0, p0, {owner}->this$0:Lcom/dycomment/tv/ProfileActivity;
    iget-object v1, p0, {owner}->val$url:Ljava/lang/String;
    iget-object v2, p0, {owner}->val$iv:Landroid/widget/ImageView;
    invoke-static {{v0, v1, v2}}, Lcom/dycomment/tv/ProfileImages;->fromWorker(Landroid/app/Activity;Ljava/lang/String;Landroid/widget/ImageView;)V
    return-void'''))

    # Palette constants used by original Java-built screens, including focus handlers.
    # Preserve alpha but use black surfaces / white text / one pink accent.
    colors = {
        -16185079: 0xff000000, -15329768: 0xff000000, -15329762: 0xff000000,
        -14803422: 0xff262626, -15066594: 0x33ffffff, -10066330: 0x99ffffff,
        -10066313: 0x99ffffff, -6710887: 0xb3ffffff, -119723: 0xffff5b79,
        872295509: 0x33ff5b79, 16657493: 0x00ff5b79, 222595327: 0x00000000, 368979029: 0x00000000, -2236963: 0xe6ffffff,
    }
    def literal(value):
        signed = value if value < 2**31 else value-2**32
        return ('-0x'+format(-signed,'x')) if signed<0 else '0x'+format(signed,'x')
    for file in package.glob('*.smali'):
        if not file.name.startswith(('ProfileActivity','MainActivity','SearchActivity','FeaturedActivity','AnimHelper')):
            continue
        text=file.read_text()
        for old,new in colors.items():
            text=re.sub(r'(?<![\w-])'+re.escape(literal(old & 0xffffffff))+r'\b',literal(new),text)
        file.write_text(text)

    legacy_theme(package)
    from profile_patch import apply as profile_patch
    profile_patch(package, replace_method)

    manifest = decoded / 'AndroidManifest.xml'
    tree=ET.parse(manifest); android='{http://schemas.android.com/apk/res/android}'
    app=tree.getroot().find('application'); app.set(android+'label','抖音抬头版'); app.set(android+'allowBackup','false'); app.set(android+'extractNativeLibs','true')
    for node in list(app):
        if 'Qishui' in node.get(android+'name',''): app.remove(node)
    ET.SubElement(app,'activity',{android+'name':'com.dycomment.tv.AboutActivity',android+'exported':'false',android+'screenOrientation':'landscape'})
    tree.write(manifest,encoding='utf-8',xml_declaration=True)
    # Runtime-constructed legacy menus still use the application dialog theme.
    styles=decoded/'res/values/styles.xml'
    if styles.exists():
        tree=ET.parse(styles)
        for style in tree.getroot():
            if style.tag=='style':
                for name,value in [('android:colorAccent','#ffff5b79'),('android:colorBackground','#ff000000'),('android:textColorPrimary','#ffffffff'),('android:textColorSecondary','#b3ffffff')]:
                    old=next((n for n in style if n.get('name')==name),None)
                    if old is None: old=ET.SubElement(style,'item',{'name':name})
                    old.text=value
        tree.write(styles,encoding='utf-8',xml_declaration=True)
    # Drop classes now unreachable from kept top-level classes. Annotation-only
    # InnerClasses metadata is not a runtime dependency. MsTokenHelper is provided by Java.
    files={f'Lcom/dycomment/tv/{f.stem};':f for f in package.glob('*.smali')}
    bodies={k:re.sub(r'(?ms)^\s*\.annotation .*?^\s*\.end annotation','',f.read_text()) for k,f in files.items()}
    roots={k for k,f in files.items() if '$' not in f.stem and not f.stem.startswith('Qishui')}
    live=set(roots); todo=list(roots)
    while todo:
        k=todo.pop()
        for ref in re.findall(r'Lcom/dycomment/tv/[\w$]+;',bodies[k]):
            if ref in files and ref not in live:
                live.add(ref); todo.append(ref)
    removed=0
    for k,f in files.items():
        if k not in live: f.unlink(); removed+=1
    print('CLEANUP removed unreachable classes:',removed)
