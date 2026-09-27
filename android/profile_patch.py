"""Pinned profile entry points; readable layout and paging implementations live in src/."""


def apply(package, replace_method):
    profile = package / 'ProfileActivity.smali'
    text = profile.read_text()
    assert '.method public synthetic lambda$buildVideoCard$4$com-dycomment-tv-ProfileActivity(Lcom/dycomment/tv/DouyinApi$FeedItem;ILandroid/view/View;)V' in text, 'pinned profile playback callback changed'
    for method, target in [('buildVideoGrid', 'LegacyTheme;->profileGrid'),
                           ('focusFirstGridItem', 'ProfileGrid;->focus'),
                           ('loadLikedVideos', 'ProfileFeed;->likes')]:
        assert f'.method private {method}()V' in text, method
        text = replace_method(text, method, '    .locals 0\n'
                              f'    invoke-static {{p0}}, Lcom/dycomment/tv/{target}(Landroid/app/Activity;)V\n'
                              '    return-void')
    assert '.method private appendVideoGrid(Ljava/util/List;)V' in text
    text = replace_method(text, 'appendVideoGrid', '''    .locals 0
    invoke-static {p0, p1}, Lcom/dycomment/tv/LegacyTheme;->appendProfileGrid(Landroid/app/Activity;Ljava/util/List;)V
    return-void''')
    marker = '.method private onTabSelected(I)V\n    .locals 5'
    assert text.count(marker) == 1, 'pinned profile tab hook changed'
    text = text.replace(marker, marker + '\n\n    invoke-static {p0}, Lcom/dycomment/tv/ProfileFeed;->cancel(Landroid/app/Activity;)V')
    profile.write_text(text)
