# WEX - 微信增强模块 (LSPosed/Xposed)

基于 LSPosed 框架的微信功能增强模块。

## 功能

- 底栏悬浮圆角美化
- 加号菜单注入快捷入口
- 首页图片卡 / 日历卡（黄历/天气/一言）/ 音乐卡
- 音乐播放器（搜索QQ & 网易云 / 播放列表 / 历史 / 收藏 / 歌词）
- 媒体通知控制（通知栏/锁屏控制播放）
- 悬浮歌词
- 顶栏全自定义（头像 / 昵称 / 状态 / 标题 / 搜索框）
- 定时关闭

## 环境要求

- Android 7.0+
- LSPosed 框架
- 微信 (com.tencent.mm)

## 构建

```bash
./gradlew assembleDebug
```

## 配置

首次使用时，需在代码中填入自己的音乐 API 密钥和接口地址（位于 `MusicCardFeature.kt` 和 `CalendarCardFeature.kt`）。

## 开源协议

GPL-3.0 — 使用、修改、分发均需保持开源。