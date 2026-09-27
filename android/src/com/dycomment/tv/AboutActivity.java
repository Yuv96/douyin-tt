package com.dycomment.tv;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class AboutActivity extends Activity {
    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = UiTheme.page(this, "抖音抬头版");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(
                UiTheme.text(
                        this,
                        "个人学习项目。\n\n"
                                + "上 / 下：切换视频\n"
                                + "确定：暂停或继续\n"
                                + "左 / 右：推荐面板、播放进度及快进快退\n"
                                + "菜单：互动\n"
                                + "再次按菜单：打开评论\n"
                                + "直播中按右键或菜单：弹幕\n"
                                + "返回：关闭面板或打开设置\n\n"
                                + "账号与登录：从电脑同步账号。\n\n"
                                + "github.com/Yuv96/douyin-tt",
                        20));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(UiTheme.button(this, "返回", () -> finish()));
        setContentView(root);
    }
}
