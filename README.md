# VivoDuoBar
[![Downloads](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2FGengo0202%2FVivoDuoBar%2Fmain%2Fdocs%2Fbadges%2Fdownloads.json&query=%24.downloads&label=Downloads&color=blue&logo=github)](https://github.com/Gengo0202/VivoDuoBar/releases)
[![Latest release](https://badgen.net/github/release/Gengo0202/VivoDuoBar)](https://github.com/Gengo0202/VivoDuoBar)
[![Android 13+](https://img.shields.io/badge/Android-13%2B-3ddc84)](https://developer.android.com)

用 Apple「灵动岛同款 Duo 圆环」替换系统电池 / Wi-Fi / 信号图标的 LSPosed 模块，针对 **vivo OriginOS** 适配。

> 本项目 fork 自 [kvmy666/duoStatusBar](https://github.com/kvmy666/duoStatusBar)（上游在 OnePlus 15 / OxygenOS 16 上开发测试），
> 感谢原作者 [kvmy1](https://t.me/kvmy1) 的工作与开源。原项目的 Telegram 支持与捐赠入口在 App 内保留。
> 遵循上游 **GPL-3.0** 许可证发布。

## 适配设备

| 项目 | 要求 |
|------|------|
| 品牌 | vivo / iQOO（OriginOS 5 及以上） |
| 系统 | Android 15+（已在 X100s Pro / OriginOS 6 / Android 16 实测） |
| 框架 | LSPosed（模块作用域需勾选「系统界面」） |
| Root | 需要（LSPosed 本身依赖） |

其他品牌（一加/小米/三星等）请使用[上游原版](https://github.com/kvmy666/duoStatusBar)。

## 安装

1. 安装 `VivoDuoBar-x.x.x.apk`；
2. 打开 LSPosed → 模块 → 启用 VivoDuoBar → 作用域勾选「系统界面（com.android.systemui）」；
3. 重启系统界面（或重启手机）；
4. 打开 VivoDuoBar App 开启模块并调整大小 / 位置 / 效果。

## 构建

```
JDK 17 + Android SDK (Platform 36)
gradlew assembleRelease
```

## 反馈问题

重启系统界面后几分钟内，在 App 的模块健康卡片点击 **Send a bug report** 导出诊断文件，附带到 [issue](https://github.com/Gengo0202/VivoDuoBar/issues)。
（OriginOS 会过滤系统界面的 logcat 输出，App 内导出是唯一可靠的日志通道。）



# 以下为上游项目信息

# Duo Status Bar

Duo Status Bar replaces the battery, Wi-Fi and mobile signal icons in your status bar with one clean,
animated indicator.

It is an LSPosed module. It does not modify system files. It only hides the icons it replaces and draws
the new indicator. You can turn it off at any time, and your original icons come back exactly as they
were.

<p align="center">
  <img src="https://cdn.jsdelivr.net/gh/kvmy666/duoStatusBar@main/docs/media/status-bar.png" width="360" alt="Duo Status Bar in the status bar">
</p>

## Requirements

- Android 13 or newer.
- A custom ROM with root.
- LSPosed installed.

## Install

1. Download the APK from [Releases](https://github.com/Gengo0202/VivoDuoBar/releases) and install it.
2. Open LSPosed, go to Modules, and enable Duo Status Bar.
3. In the module's scope, tick **System UI**.
4. Restart System UI, or reboot the phone.
5. Open the Duo Status Bar app and turn the main switch on.

The module is off until you turn it on. Nothing changes before that.

## Settings

- **Battery icon** - show or hide the percentage, change the size, and move the position.
- **Animations** - turn animations on or off, and set the speed.
- **Appearance** - icon colour, smooth graphics, the clock font, and what appears in the middle.
- **Status bar icons** - keep your other icons visible beside the indicator, or hide them.
- **Tap actions** - optional. Handled by the Auto Expand module.
- **About** - see the module status and send a report.

Changing the size needs a System UI restart. The app has a button for it.

## Turn it off

Use the switch in the app, or run one of these commands:

```text
adb shell settings put global duo_statusbar_stage 0   # off
adb shell settings put global duo_statusbar_stage 1   # simple drawing, no native code
adb shell settings put global duo_statusbar_stage 2   # full animation
```

## If something goes wrong

- Issues: https://github.com/kvmy666/duoStatusBar/issues
- Telegram: https://t.me/kvmy1

Send a report from the app's **About** section. It includes your device details and the logs, which
makes the problem much easier to find.

## Support

If you like the app, you can support the developer:
[Buy Me a Coffee](https://www.buymeacoffee.com/kroomfahd) or [PayPal](https://paypal.me/kroomfahd).

## License

GPL-3.0. See [LICENSE](LICENSE).
