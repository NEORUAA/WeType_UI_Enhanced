<div align="center">
<img src="./assets/icon.png" width="120px"/>

# WeType UI Enhanced

一个以 **微信输入法（WeType）界面美化与个性化** 为主要功能的 Xposed 模块，同时为作用域其它输入法解锁 MIUI 全面屏优化限制。


<p align="center">

![Android 12 or later](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&amp;logoColor=white)
![LSPosed 102](https://img.shields.io/badge/LSPosed-Modern_API_102-5C6BC0)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)
![License](https://img.shields.io/badge/License-AGPLv3-orange)

</p>
</div>

---

## 1.29 修复说明

- 在微信输入法 Application.attach 完成后，使用宿主实际类加载器安装 hook，修复 Tinker 热更新后候选背景圆角等美化设置不生效的问题；热重载沿用实际加载器。
- 使用绘制合成替代浮层显隐干预，保留宿主原有的工具栏展开及页面切换动画，避免透明页面透出底层内容。
- 保留键盘选择页共用的返回控件与导航区域；背景更新区分等待布局、实际隐藏和有效布局，避免布局期间背景被清空。
- 统一识别设置页共用的返回 / 标题与右侧工具栏，修复展开全部工具后设置、隔空传送图标被误遮挡的问题；其他浮层仍可按透明度覆盖这些控件。
- 修正 SVG 导入提示的 Compose 资源读取问题，版本号更新为 1.29 / versionCode 39。

修复代码已在微信输入法 3.5.4（57201）上收到设置与动画恢复正常的实机反馈，后续 1.28.3 由使用者在手机上测试并确认没有问题。1.29 保留相同功能代码，仅更新版本号和发布文档；debug / release 构建、94 个单元测试和 release lint 均通过（0 错误、82 个警告）。普通用户覆盖更新需要使用与原安装包兼容的签名证书，第三方 Fork 的测试签名包不能直接覆盖上游正式签名包。

## 功能

### 微信输入法美化

- 自定义浅色 / 深色模式下的窗口背景颜色与透明度
- 自定义浅色 / 深色按键颜色、透明度与圆角
- 自定义背景模糊强度、平滑圆角及边缘高光效果
- 自定义输入法全局品牌强调色
- 调节工具栏图标背景透明度
- 调节候选词背景透明度与圆角
- 调节首个候选词及候选栏拼音边距
- 支持阻止微信输入法热更新

### 高级材质与液态玻璃

在支持相应系统视效接口的 HyperOS 设备上，可在设置的「外观」分组中启用：

- **高级材质**：采用超级小爱输入法的系统磨砂背景效果，保留模块的自定义平滑圆角。
- **系统液态玻璃**：在高级材质下启用原生玻璃渲染，支持调节折射、厚度、模糊及高光等参数；使用「颜色」分组保存的浅色 / 深色背景色与透明度作为 tint。

两项功能默认关闭，需要系统视效处于开启状态；不支持的设备上，对应开关不可用。

### MIUI / HyperOS 附加功能

在支持小米全面屏键盘优化的 MIUI / HyperOS 系统上，提供三方输入法解锁全面屏键盘优化限制，解锁小米短语的包名校验，修复三方输入法无法获取系统剪贴板列表的问题。

该部分并非模块主要功能，在非小米系统上不会启用，也不影响 WeType 美化功能。

## 效果预览

默认效果为 iOS 27 Apple 官方设计稿内的配色、圆角等数值
<details open>
<summary>#FB7299 主题色截图</summary>
<table>
  <tr>
    <td><img src="./assets/prew/dark_1.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/dark_2.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/light_1.jpg" width="200" alt="浅色模式"></td>
    <td><img src="./assets/prew/light_2.jpg" width="200" alt="浅色模式"></td>
  </tr>
</table>
</details>

## 使用要求

- Android 12+
- LSPosed / 兼容的 Xposed 框架
- Xposed API Version ≥ 102
- 微信输入法

安装模块后，在 LSPosed 中启用模块并勾选 **微信输入法** 作用域，然后重启微信输入法。

模块生效后，可通过桌面入口进入设置；也可以点击微信输入法「关于」页面中的 Logo 打开寄生设置页。

部分设置修改后需要重启微信输入法进程才能完全生效。

## 测试环境

设备：Xiaomi 17 Pro

HyperOS 4.0.0.27 Beta

Android 17

LSPosed v2.1.1-it (7846)

微信输入法：3.5.3.56201

## 兼容性

模块主要针对微信输入法进行适配。微信输入法内部实现、资源名称或云端热修复发生变化时，部分功能可能暂时失效。

MIUI / HyperOS 相关附加功能仅针对小米系统，不适用于其他厂商的系统级输入法优化实现。

## 下载

请前往本仓库或下方模块仓库的 Releases 下载最新版本。

Xposed 模块仓库：https://github.com/Xposed-Modules-Repo/com.xposed.wetypehook

## 开源致谢

感谢项目 [MIUI_IME_Unlock(MIT)](https://github.com/RC1844/MIUI_IME_Unlock) 提供的解锁 MIUI 全面屏优化限制功能

感谢 [miuix](https://github.com/compose-miuix-ui/miuix) 提供的 Compose UI 库

## 开源许可

本项目自 2026.8.16 起换用 AGPL-3.0 许可协议，要求修改和分发的同时也公开源码，且使用相同的许可协议。
