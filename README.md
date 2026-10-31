# 安山岩下载器 · AndesiteDownloader

> 一款为 PCL / HMCL 等启动器提供版本下载功能的独立 Minecraft 资源下载器。


## 📖 简介

**安山岩下载器** 是一款运行于本地的开源桌面工具。由于近期部分启动器的内置下载功能受限，本软件旨在作为一个**纯粹的下载辅助程序**，帮助玩家快速、稳定地获取 Minecraft 游戏本体及各类模组加载器（Fabric / Forge / NeoForge）的核心文件。

本软件**不包含**游戏启动功能，**不生成**启动脚本，**不修改**游戏本体。下载完成后，您可以直接在 PCL、HMCL 等主流启动器中刷新版本列表并启动游戏。

## ✨ 特性

- **多源加速**：默认使用 [BMCLAPI](https://bmclapidoc.bangbang93.com/) 镜像加速，失败自动回落至官方源。
- **加载器支持**：支持原版、Fabric、Forge、NeoForge 的完整安装。
- **强制隔离**：所有下载的实例均强制隔离在 `versions/{实例名}/` 目录下，互不干扰。
- **断点续传**：支持大文件断点续传与 SHA1 完整性校验，重复安装极速跳过。
- **现代界面**：基于 JavaFX 21 构建，淡青色主题，无边框圆角窗口，丝滑连贯。
- **绝对纯净**：无账号系统、无广告、无遥测，不收集任何用户数据。

## 📥 下载与运行

请前往 [Releases](https://github.com/SutaNeya/AndesiteDownloader/releases) 页面下载最新版本的 jar 包：

- `AndesiteDownloader-win.jar` —— Windows 版
- `AndesiteDownloader-linux.jar` —— Linux 版

linux需要libgtk-3-0图形库
运行需要jre17/21/25

### 运行环境要求

- **Java 17 / 21 / 25**（推荐使用 Java 21 LTS）
- Windows 10/11 或 主流 Debian 系 Linux 发行版

### 启动方式

**Windows:**
双击运行，或在命令行执行：
```bat
java -jar AndesiteDownloader-win.jar
