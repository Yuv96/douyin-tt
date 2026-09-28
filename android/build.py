#!/usr/bin/env python3
"""Rebuild the public Lite APK with reviewed API-21 playback/cache sources."""
import hashlib, os, pathlib, re, shutil, subprocess, urllib.request, zipfile
from cleanup import apply as cleanup
from branding import apply as branding
from package_apk import package_variant
from signing import require_pinned_signer

ROOT=pathlib.Path(__file__).resolve().parent
WORK=pathlib.Path(os.environ.get('BUILD_WORK',str(ROOT/'build')))
WORK.mkdir(exist_ok=True)
SELF_TEST=os.environ.get('SELF_TEST')=='1'
configured_key=os.environ.get('ANDROID5_KEYSTORE')
if not SELF_TEST and not configured_key:
    raise RuntimeError('Production build requires the pinned signing keystore; temporary signing is forbidden')
if configured_key and not pathlib.Path(configured_key).is_file():
    raise RuntimeError('Configured signing keystore does not exist; refusing to generate a replacement')
if configured_key and not os.environ.get('ANDROID5_KEYSTORE_PASSWORD'):
    raise RuntimeError('Configured signing keystore requires ANDROID5_KEYSTORE_PASSWORD')
DIST=ROOT.parent/('dist-test' if SELF_TEST else 'dist')
DIST.mkdir(exist_ok=True)
SDK=pathlib.Path(os.environ['ANDROID_HOME'])
BT=SDK/'build-tools'/'35.0.0'
ANDROID=SDK/'platforms'/'android-35'/'android.jar'
def run(*args): subprocess.run([str(a) for a in args],check=True)
def download(name,url,sha):
    p=WORK/name
    if not p.exists(): urllib.request.urlretrieve(url,p)
    assert hashlib.sha256(p.read_bytes()).hexdigest()==sha, name+' checksum mismatch'
    return p
original=download('original.apk','https://github.com/mytv-android/myDV/releases/download/V1.1.9/myDV.Lite_1.1.9.apk','c01f3b93a7c0b97e481f9f9706a1ff9bc91c3431000be13695b1ca9ffcad69fa')
apktool=download('apktool.jar','https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar','dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423')
vlc=download('libvlc.aar','https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.6/libvlc-all-3.7.6.aar','6b438ab75eb3b307d9699f4594c043f46b0a9a697521bd03729c02c0a400eb8a')
bindings=download('libvlc-3.7.6-sources.jar','https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.6/libvlc-all-3.7.6-sources.jar','95bb03b150ca4cda5fb334b02df2e4f95c627940a1134f805161ab0146566a37')
decoded=WORK/'decoded'
shutil.rmtree(decoded,ignore_errors=True) # Apktool's old build cache must not survive a source rebuild.
run('java','-jar',apktool,'d','-f',original,'-o',decoded)
with zipfile.ZipFile(vlc) as z: z.extractall(WORK/'vlc')
package=decoded/'smali/com/dycomment/tv'
for file in package.glob('PlayerView*.smali'): file.unlink()
for file in package.glob('ModernMenuHelper*.smali'): file.unlink()
assert not list(package.glob('PlayerView*.smali')), 'remove player failed'
main=package/'MainActivity.smali'
text=main.read_text()
if SELF_TEST:
    evidence=ROOT.parent/'evidence'; evidence.mkdir(exist_ok=True)
    dispatch=[]
    for method in ['playAt', 'playVideo', 'loadVideoDetail', 'refreshAndPlay']:
        body=re.search(rf'(?ms)^\.method [^\n]* {method}\([^\n]*\n.*?^\.end method', text)
        if body: dispatch.append(body.group())
    (evidence/'playback-dispatch.txt').write_text('\n\n'.join(dispatch))
pattern=r'(?ms)^\.method public synthetic lambda\$onCreate\$0\$com-dycomment-tv-MainActivity\(Landroid/media/MediaPlayer;\)V\n.*?^\.end method'
replacement='''.method public synthetic lambda$onCreate$0$com-dycomment-tv-MainActivity(Landroid/media/MediaPlayer;)V
    .locals 2
    iget-object v0, p0, Lcom/dycomment/tv/MainActivity;->videoView:Lcom/dycomment/tv/PlayerView;
    iget v1, p0, Lcom/dycomment/tv/MainActivity;->playbackSpeed:F
    invoke-virtual {v0, v1}, Lcom/dycomment/tv/PlayerView;->setPlaybackSpeed(F)V
    invoke-direct {p0}, Lcom/dycomment/tv/MainActivity;->hideLoading()V
    return-void
.end method'''
text,n=re.subn(pattern,lambda _:replacement,text); assert n==1
text,n=re.subn(r'(?ms)^\.method private applySpeed\(\)V\n.*?^\.end method', '''.method private applySpeed()V
    .locals 2
    iget-object v0, p0, Lcom/dycomment/tv/MainActivity;->videoView:Lcom/dycomment/tv/PlayerView;
    if-eqz v0, :done
    iget v1, p0, Lcom/dycomment/tv/MainActivity;->playbackSpeed:F
    invoke-virtual {v0, v1}, Lcom/dycomment/tv/PlayerView;->setPlaybackSpeed(F)V
    :done
    return-void
.end method''',text); assert n==1
for method, target in [('showDanmakuMenu','showQuick'), ('showExitMenu','showSettings'), ('updateClock','updateClock')]:
    pattern = rf'(?ms)^\.method private {method}\(\)V\n.*?^\.end method'
    replacement = f'''.method private {method}()V
    .locals 0
    invoke-static {{p0}}, Lcom/dycomment/tv/InteractionController;->{target}(Landroid/app/Activity;)V
    return-void
.end method'''
    text,n = re.subn(pattern, lambda _: replacement, text); assert n == 1, method
assert 'dispatchKeyEvent(Landroid/view/KeyEvent;)Z' not in text
text += '''
.method public dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 1
    invoke-static {p0, p1}, Lcom/dycomment/tv/InteractionController;->handleKey(Landroid/app/Activity;Landroid/view/KeyEvent;)Z
    move-result v0
    if-eqz v0, :normal
    const/4 v0, 0x1
    return v0
    :normal
    invoke-super {p0, p1}, Landroid/app/Activity;->dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    move-result v0
    return v0
.end method
'''
# Keep selection changes and asynchronous callbacks in the same epoch.
marker='    iput p1, p0, Lcom/dycomment/tv/MainActivity;->currentIndex:I'
assert text.count(marker)==1
text=text.replace(marker,marker+'\n    invoke-static {p0}, Lcom/dycomment/tv/PlaybackCoordinator;->selected(Landroid/app/Activity;)V')
marker='    :cond_9\n    iget-object p1, v1, Lcom/dycomment/tv/DouyinApi$FeedItem;->imageUrls:Ljava/util/List;'
assert text.count(marker)==1
text=text.replace(marker,'    :cond_9\n    invoke-static {p0}, Lcom/dycomment/tv/PlaybackCoordinator;->bound(Landroid/app/Activity;)V\n'+marker.split('\n',1)[1])
for method in ['loadVideoDetail','refreshAndPlay']:
    pattern=rf'(?ms)^\.method private {method}\(Lcom/dycomment/tv/DouyinApi\$FeedItem;\)V\n.*?^\.end method'
    replacement=f'''.method private {method}(Lcom/dycomment/tv/DouyinApi$FeedItem;)V
    .locals 0
    invoke-static {{p0, p1}}, Lcom/dycomment/tv/PlaybackCoordinator;->detail(Landroid/app/Activity;Ljava/lang/Object;)V
    return-void
.end method'''
    text,n=re.subn(pattern,lambda _:replacement,text); assert n==1,method
pattern=r'(?ms)^\.method public synthetic lambda\$onCreate\$2\$com-dycomment-tv-MainActivity\(Landroid/media/MediaPlayer;II\)Z\n.*?^\.end method'
replacement='''.method public synthetic lambda$onCreate$2$com-dycomment-tv-MainActivity(Landroid/media/MediaPlayer;II)Z
    .locals 1
    invoke-static {p0}, Lcom/dycomment/tv/PlaybackCoordinator;->error(Landroid/app/Activity;)Z
    move-result v0
    return v0
.end method'''
text,n=re.subn(pattern,lambda _:replacement,text); assert n==1
marker='.method private hideLoading()V\n    .locals 2'
assert marker in text
text=text.replace(marker,marker+'''
    invoke-static {p0}, Lcom/dycomment/tv/PlaybackCoordinator;->canHideLoading(Landroid/app/Activity;)Z
    move-result v0
    if-nez v0, :a53_hide_loading
    return-void
    :a53_hide_loading
''')
# Avatars and live metadata must not overwrite a selection made while requests were in flight.
for number in [26,29,30]:
    callback=package/f'MainActivity${number}.smali'
    body=callback.read_text()
    descriptor=f'Lcom/dycomment/tv/MainActivity${number};'
    body=body.replace('# instance fields','# instance fields\n.field private selectionEpoch:I\n')
    constructor=re.search(r'(?ms)^\.method constructor <init>.*?^\.end method',body).group()
    updated=constructor.replace('    .locals 0','    .locals 1')
    updated=updated.replace('    return-void',f'''    invoke-static {{p1}}, Lcom/dycomment/tv/PlaybackCoordinator;->token(Landroid/app/Activity;)I
    move-result v0
    iput v0, p0, {descriptor}->selectionEpoch:I
    return-void''')
    body=body.replace(constructor,updated)
    def guard(match):
        method=match.group()
        # New locals use fresh registers; original methods address arguments as pN.
        count=int(re.search(r'\.locals (\d+)',method).group(1)); a=f'v{count}'; b=f'v{count+1}'
        injection=f'''    .locals {count+2}
    iget-object {a}, p0, {descriptor}->this$0:Lcom/dycomment/tv/MainActivity;
    iget {b}, p0, {descriptor}->selectionEpoch:I
    invoke-static {{{a}, {b}}}, Lcom/dycomment/tv/PlaybackCoordinator;->valid(Landroid/app/Activity;I)Z
    move-result {a}
    if-nez {a}, :a53_current
    return-void
    :a53_current'''
        return re.sub(r'    \.locals \d+',lambda _:injection,method,count=1)
    body=re.sub(r'(?ms)^\.method public (?:onLoaded|onResult|onError|synthetic lambda\$onResult)[^\n]*\n.*?^\.end method',guard,body)
    callback.write_text(body)

# Route live room lifecycle to the bounded read-only bootstrap/cursor subscription.
for method, signature, target, args in [('startLiveDanmaku','Ljava/lang/String;','start','p0, p1'),('stopLiveDanmaku','','stop','p0')]:
    pattern=rf'(?ms)^\.method private {method}\({signature}\)V\n.*?^\.end method'
    replacement=f'''.method private {method}({signature})V
    .locals 0
    invoke-static {{{args}}}, Lcom/dycomment/tv/LiveChatController;->{target}(Landroid/app/Activity;{signature})V
    return-void
.end method'''
    text,n=re.subn(pattern,lambda _:replacement,text); assert n==1,method

# Lifecycle hooks keep the notification refresh independent of clock visibility.
for method, target in [('onResume', 'resumed'), ('onPause', 'paused'), ('onDestroy', 'destroyed')]:
    marker=f'    invoke-super {{p0}}, Landroid/app/Activity;->{method}()V'
    assert text.count(marker)==1
    text=text.replace(marker,marker+f'\n    invoke-static {{p0}}, Lcom/dycomment/tv/InteractionController;->{target}(Landroid/app/Activity;)V')

# Keep a bounded history when recommendations append a new page.
callback=package/'MainActivity$25.smali'; body=callback.read_text()
method=re.search(r'(?ms)^\.method public onResult\(.*?^\.end method',body).group()
method=method.replace('    return-void', '    iget-object v0, p0, Lcom/dycomment/tv/MainActivity$25;->this$0:Lcom/dycomment/tv/MainActivity;\n    invoke-static {v0}, Lcom/dycomment/tv/PlaybackCoordinator;->trimFeed(Landroid/app/Activity;)V\n    return-void')
body=re.sub(r'(?ms)^\.method public onResult\(.*?^\.end method',lambda _:method,body); callback.write_text(body)
# Keep quality selectable in settings, but omit its suffix from the metadata statistics.
text,n=re.subn(r'(?s)    \.line 986\n.*?    \.line 987\n    :goto_1\n', '    const-string v2, ""\n\n', text); assert n==1
# The Java selection state owns visibility. Remove the old permanent/6s binding branch.
text,n=re.subn(r'(?s)    \.line 990\n.*?    \.line 1006\n', '    .line 1006\n', text); assert n==1
text,n=re.subn(r'(?ms)^\.method private toggleInfo\(\)V\n.*?^\.end method', '''.method private toggleInfo()V
    .locals 0
    invoke-static {p0}, Lcom/dycomment/tv/PlaybackCoordinator;->reconcileMetadata(Landroid/app/Activity;)V
    return-void
.end method''', text); assert n==1
main.write_text(text)
# Avoid offering upstream APKs with a different package/signature as updates.
updater=package/'UpdateHelper.smali'
text,n=re.subn(r'(?ms)^\.method public static checkUpdate\(Landroid/app/Activity;Z\)V\n.*?^\.end method',
    '.method public static checkUpdate(Landroid/app/Activity;Z)V\n    .locals 0\n    return-void\n.end method',updater.read_text())
assert n==1; updater.write_text(text)
# Prefer 720p for a fresh installation on older CPUs. Existing user choice wins.
api=package/'DouyinApi.smali'; text=api.read_text()
start=text.index('.method public static loadVideoQuality('); end=text.index('.end method',start)
method=text[start:end].replace('    invoke-interface {p0, v0, v1}', '    const/4 v1, 0x1\n\n    invoke-interface {p0, v0, v1}')
text=text[:start]+method+text[end:]; api.write_text(text)
manifest=decoded/'AndroidManifest.xml'; text=manifest.read_text()
if 'android.permission.FOREGROUND_SERVICE' not in text:
    text=text.replace('<application', '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n<application', 1)
text=text.replace('</application>', '<activity android:name="com.dycomment.tv.LanSyncActivity" android:exported="false" />\n<service android:name="com.dycomment.tv.LanSyncService" android:exported="false" />\n</application>')
text=text.replace('package="com.dycomment.tv"','package="com.dycomment.tv.android5"').replace('android:label="myDV Lite"','android:label="抖音抬头版"')
text=text.replace('</application>','<activity android:name="com.dycomment.tv.PlaybackSelfTestActivity" android:exported="true" />\n    </application>')
text=text.replace('</application>','<activity android:name="com.dycomment.tv.FollowedLiveActivity" android:exported="false" />\n<activity android:name="com.dycomment.tv.InteractionSelfTestActivity" android:exported="true" />\n</application>')
text=text.replace("</application>",'<activity android:name="com.dycomment.tv.SwitchingSelfTestActivity" android:exported="true" />\n</application>')
text=text.replace("</application>",'<activity android:name="com.dycomment.tv.CommentsSelfTestActivity" android:exported="true" />\n</application>')
text=text.replace('</application>','<activity android:name="com.dycomment.tv.QuickShareActivity" android:exported="false" />\n<activity android:name="com.dycomment.tv.SurfaceCoverTestActivity" android:exported="false" />\n</application>')
if SELF_TEST:
    text=text.replace('</application>', '<activity android:name="com.dycomment.tv.LanSyncSelfTestActivity" android:exported="true" />\n</application>')
    text=text.replace('</application>', '<service android:name="com.dycomment.tv.LanSyncSelfTestActivity$FixtureService" android:exported="false" />\n</application>')
    text=text.replace('</application>', '<receiver android:name="com.dycomment.tv.LanPairingConfirmationReceiver" android:exported="true" />\n</application>')
if not SELF_TEST:
    text=re.sub(r'<activity[^>]+android:name="com.dycomment.tv.(?:PlaybackSelfTestActivity|InteractionSelfTestActivity|SwitchingSelfTestActivity|CommentsSelfTestActivity|SurfaceCoverTestActivity)"[^>]*/>', '', text)
else:
    def export_ui_fixture(match):
        node=re.sub(r' android:exported="[^"]*"', '', match.group())
        return node.replace('<activity', '<activity android:exported="true"', 1)
    text=re.sub(r'<activity\b[^>]*android:name="com.dycomment.tv.(?:ProfileActivity|FeaturedActivity|SearchActivity)"[^>]*>', export_ui_fixture, text)
manifest.write_text(text)
ids=decoded/'res/values/ids.xml'
names=['android5_interaction_panel','android5_menu_panel','android5_comments_panel','android5_follow_badge'] + [f'android5_menu_row_{i}' for i in range(32)]
ids.write_text(ids.read_text().replace('</resources>', ''.join(f'<id name="{name}" />\n' for name in names)+'</resources>'))
# One API-21 translucent material card, with statistics on the author line.
import xml.etree.ElementTree as ET
ET.register_namespace('android','http://schemas.android.com/apk/res/android')
a='{http://schemas.android.com/apk/res/android}'
layout=decoded/'res/layout/activity_main.xml'; tree=ET.parse(layout)
card=next(e for e in tree.iter() if e.get(a+'id')=='@id/infoOverlay')
card.tag='com.dycomment.tv.VideoInfoLayout'
row=card[0]; column=row[1]; author=column[0]; stats=card[1]
card.remove(stats); column.remove(author)
header=ET.Element('LinearLayout',{a+'orientation':'horizontal',a+'gravity':'center_vertical',a+'layout_width':'wrap_content',a+'layout_height':'wrap_content'})
title=column[0]
title.set(a+'maxLines','2'); title.set(a+'ellipsize','end')
author.set(a+'layout_width','wrap_content'); author.set(a+'maxWidth','180dp'); author.set(a+'maxLines','1'); author.set(a+'ellipsize','end')
for key in ['background','paddingTop','paddingBottom','paddingStart','paddingEnd','layout_marginTop']:
    stats.attrib.pop(a+key,None)
stats.set(a+'layout_marginStart','12dp'); stats.set(a+'layout_width','wrap_content'); stats.set(a+'textSize','12sp')
stats.set(a+'maxLines','1'); stats.set(a+'ellipsize','end')
header.extend([author,stats]); column.insert(0,header)
for element in [card,row,column,column[1]]:
    element.set(a+'layout_width','wrap_content')
    element.attrib.pop(a+'layout_weight',None)
card.set(a+'layout_gravity','bottom|start')
card.set(a+'visibility','gone')
clock=next(e for e in tree.iter() if e.get(a+'id')=='@id/tvClock')
clock.set(a+'layout_gravity','left|top')
clock.set(a+'layout_marginLeft','16dp')
for key in ['layout_marginStart','layout_marginEnd','layout_marginRight']:
    clock.attrib.pop(a+key,None)
for key in ['paddingBottom','paddingStart','paddingEnd']: card.attrib.pop(a+key,None)
card.set(a+'padding','0dp'); card.set(a+'layout_marginStart','18dp'); card.set(a+'layout_marginEnd','18dp'); card.set(a+'layout_marginBottom','48dp')
column.set(a+'padding','12dp'); column.set(a+'background','@drawable/android5_video_card')
column.set(a+'layout_height','88dp'); column.set(a+'gravity','center_vertical')
row[0].set(a+'layout_width','88dp'); row[0].set(a+'layout_height','88dp')
avatar=row[0]; row.remove(avatar)
avatarFrame=ET.Element('FrameLayout',{a+'layout_width':'88dp',a+'layout_height':'88dp'})
avatar.set(a+'layout_width','match_parent'); avatar.set(a+'layout_height','match_parent')
avatarFrame.append(avatar)
badge=ET.SubElement(avatarFrame,'TextView',{
    a+'id':'@id/android5_follow_badge',a+'layout_width':'wrap_content',a+'layout_height':'wrap_content',
    a+'layout_gravity':'bottom|center_horizontal',a+'gravity':'center',a+'maxWidth':'86dp',
    a+'text':'读取中',a+'textSize':'11sp',a+'textColor':'#ffffffff',a+'maxLines':'1',a+'ellipsize':'end',
    a+'paddingStart':'7dp',a+'paddingEnd':'7dp',a+'paddingTop':'2dp',a+'paddingBottom':'2dp',
    a+'background':'@drawable/android5_follow_badge'})
row.insert(0,avatarFrame)
column[1].set(a+'textColor','#e6ffffff')
tree.write(layout,encoding='utf-8',xml_declaration=True)
(decoded/'res/drawable/android5_video_card.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
  <corners android:radius="12dp" />
  <gradient android:angle="90" android:startColor="#c0000000" android:endColor="#a0000000" />
  <stroke android:width="1dp" android:color="#30ffffff" />
</shape>''')
(decoded/'res/drawable/android5_follow_badge.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
  <corners android:radius="10dp" />
  <solid android:color="#ee262626" />
  <stroke android:width="1dp" android:color="#99ffffff" />
</shape>''')
assert not list(package.glob('PlayerView*.smali')), 'before cleanup'
cleanup(decoded)
branding(decoded)
assert not list(package.glob('PlayerView*.smali')), 'after cleanup'
config=decoded/'apktool.yml' ; text=config.read_text().replace('versionCode: 9','versionCode: 1012').replace('versionName: 1.1.9','versionName: 0.1.5')
assert 'minSdkVersion: 21' in text; config.write_text(text)
assets=decoded/'assets'; assets.mkdir(exist_ok=True)
shutil.copy(ROOT/'LIBVLC-LICENSE.txt',assets/'LIBVLC-LICENSE.txt')
shutil.copy(ROOT/'DOUYIN-IM-LICENSE.txt',assets/'DOUYIN-IM-LICENSE.txt')
shutil.copy(ROOT/'README.md',assets/'ANDROID5-SOURCES.md')
if SELF_TEST:
    for profile,width,height,name in [('high',1280,720,'high'),('baseline',640,360,'baseline'),('baseline',360,640,'portrait')]:
        run('ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i',f'testsrc2=size={width}x{height}:rate=25',
            '-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','16','-c:v','libx264','-preset','veryfast','-crf','30',
            '-profile:v',profile,'-level:v','4.1' if profile=='high' else '3.0','-pix_fmt','yuv420p','-c:a','aac','-b:a','64k','-movflags','+faststart',assets/f'selftest-{name}.mp4')
shutil.copytree(WORK/'vlc/jni',decoded/'lib',dirs_exist_ok=True)
shipped_abis = ('x86',) if SELF_TEST else ('armeabi-v7a', 'x86')
for library_dir in (decoded/'lib').iterdir():
    if library_dir.is_dir() and library_dir.name not in shipped_abis:
        shutil.rmtree(library_dir)
# Link the app resources before replacing the original DEX.
shutil.rmtree(decoded/'build',ignore_errors=True)
(WORK/'base.apk').unlink(missing_ok=True)
run('java','-jar',apktool,'b','-f',decoded,'-o',WORK/'base.apk')
classes=WORK/'classes'; shutil.rmtree(classes, ignore_errors=True); classes.mkdir(exist_ok=True)
sources=list((ROOT/'src').rglob('*.java'))
if SELF_TEST: sources+=list((ROOT/'tests/java').rglob('*.java'))
classpath=os.pathsep.join(map(str,[ANDROID, WORK/'vlc/classes.jar']))
run('javac','-source','8','-target','8','-encoding','UTF-8','-classpath',classpath,'-d',classes,*sources)
compiled=WORK/'compiled.jar'
with zipfile.ZipFile(compiled,'w') as z:
    for f in classes.rglob('*.class'): z.write(f,f.relative_to(classes))
with zipfile.ZipFile(WORK/'base.apk') as z: (WORK/'original.dex').write_bytes(z.read('classes.dex'))
dexdir=WORK/'dex'; shutil.rmtree(dexdir,ignore_errors=True); dexdir.mkdir(exist_ok=True)
run(BT/'d8','--min-api','21','--lib',ANDROID,'--output',dexdir,WORK/'original.dex',compiled,WORK/'vlc/classes.jar')
keystore=configured_key or str(WORK/'test-only.p12')
password=os.environ.get('ANDROID5_KEYSTORE_PASSWORD','android5-build')
if not pathlib.Path(keystore).exists():
    run('keytool','-genkeypair','-keystore',keystore,'-storetype','PKCS12','-storepass',password,'-keypass',password,
        '-alias','android5','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Douyin TT Local Check')
os.environ['BUILD_SIGN_PASSWORD']=password
variants=[('', ('x86',))] if SELF_TEST else [('', ('armeabi-v7a','x86')), ('-armv7', ('armeabi-v7a',))]
built=[]
for suffix, abis in variants:
    unsigned=WORK/('unsigned'+suffix+'.apk')
    aligned=WORK/('aligned'+suffix+'.apk')
    package_variant(WORK/'base.apk', dexdir, unsigned, abis)
    run(BT/'zipalign','-f','4',unsigned,aligned)
    apk=DIST/('douyin-tt-0.1.5'+suffix+'.apk')
    run(BT/'apksigner','sign','--ks',keystore,'--ks-key-alias','android5','--ks-pass','env:BUILD_SIGN_PASSWORD','--min-sdk-version','21','--out',apk,aligned)
    run(BT/'apksigner','verify','--verbose','--min-sdk-version','21',apk)
    if configured_key or not SELF_TEST:
        digest=require_pinned_signer(apk,BT/'apksigner')
        (DIST/'SIGNING_CERTIFICATE.txt').write_text('SHA-256: '+digest+'\nMatches the pinned signing certificate.\n')
    built.append(apk)
    print('BUILT',apk,'bytes=',apk.stat().st_size)
shutil.copy(bindings,DIST/bindings.name)
shutil.copy(ROOT/'LIBVLC-LICENSE.txt',DIST/'LIBVLC-LICENSE.txt')
(DIST/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(apk.read_bytes()).hexdigest()+'  '+apk.name+'\n' for apk in built))
