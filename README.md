# StarFlux MC Launcher

StarFlux MC Launcher 是一个使用 Java 和 JavaFX 编写的 Minecraft 启动器。

Java 源码按职责分在 `app`、`account`、`config`、`download`、`game`、`multiplayer` 和 `ui` 包中；启动引导入口保留在根包。

## 当前功能

- 查看、安装和删除已安装的 Minecraft 版本。
- 安装 Fabric、Forge、NeoForge 和 Quilt 模组加载器。
- 使用 Mojang 官方源或 BMCLAPI 镜像下载游戏文件。
- 添加和管理离线账号，并启动游戏。
- 设置 Minecraft 最大内存，并可将版本安装为独立实例。
- 通过 Terracotta（陶瓦联机）创建或加入 Minecraft 联机房间。
- 配置 Minecraft 游戏目录。

## 尚未实现

- Modrinth / CurseForge 资源搜索与下载，以及整合包安装入口目前尚未接入实际功能。
- 正版 Microsoft 账号登录、Java 版本自动选择与下载尚未实现。
- 当前启动器使用其运行环境中的 Java 启动游戏；不同 Minecraft 版本可能要求不同 Java 版本。

首次使用联机功能需要联网下载对应平台的 Terracotta 官方发行包；之后启动器通过其本机 HTTP API 管理房间。房主需先进入 Minecraft 世界并“对局域网开放”，再创建联机房间；好友连接成功后可在 Minecraft 多人游戏列表中加入“陶瓦联机大厅”。Terracotta 独立运行，遵循 AGPL-3.0-or-later（附例外条款），版权归其作者所有。

## 环境要求

- Java 25 或更高版本（启动器和游戏均使用本机 Java）
- Gradle Wrapper 9.9.1（项目已包含 Wrapper）
- 网络连接（首次启动时下载 JavaFX/Gson 运行库；加载版本清单、下载游戏文件和安装模组加载器时也需要）

## 运行

在项目根目录执行：

```powershell
.\gradlew.bat run
```

## 构建

构建可通过 `java -jar` 启动的 JAR：

```powershell
.\gradlew.bat jar
java -jar .\build\libs\StarFlux-1.0-SNAPSHOT.jar
```

首次运行会在 JAR 同级创建 `.starflux`，并下载当前平台的 JavaFX/Gson 运行库。启动器设置保存在 `.starflux\config.properties`，运行日志保存在 `.starflux\logs\launcher.log`。请保持该目录与 JAR 同级且可写；如果目录或运行库被删除，下次启动时会重新创建/下载。

在 Windows 上生成 EXE 启动器与配套 JAR：

```powershell
.\gradlew.bat createExe
```

产物位于 `build\distributions\`，请将 `StarFluxMCLauncher.exe` 和同目录的 JAR 一起分发。运行 EXE 时需要安装 64 位 Java 25 或更高版本；未检测到 Java 时，启动器会提示并打开下载页面。JavaFX/Gson 运行库仍会在首次启动时下载至 EXE/JAR 同级的 `.starflux`。

也可以生成包含 Java 运行时及 JavaFX 依赖的应用镜像压缩包：

```powershell
.\gradlew.bat jlinkZip
```

压缩包位于 `build\distributions\`。解压后运行应用目录中的 `bin\StarFluxMCLauncher.exe`。请保留解压后的完整目录，不要只复制可执行文件。JLink 产物针对构建时所在的平台生成。

## 数据与账号说明

默认游戏目录为 `%USERPROFILE%\.minecraft`，也可以在“设置”页面修改。已安装版本、游戏库文件和资源等保存在该目录中。
在“下载游戏”中勾选“创建独立版本实例”，会将所选版本及其游戏数据安装到 `<游戏目录>\instances\<版本号>`；该实例与默认目录中的版本相互独立，删除独立实例也会删除其中的存档、模组和设置。独立实例会单独保存运行库及资源，可能占用更多磁盘空间。

“设置”页面会显示检测到的系统 RAM；最大内存可通过滑块或文本框设置，两者同步，作为 Minecraft JVM 的 `-Xmx` 参数保存。取值为 1 GB 至检测到的整 GB 数（最高 64 GB），默认值为 4 GB（会按设备内存范围调整）。

目前账号为离线昵称，不会验证 Microsoft 账号身份；离线账号不能用于需要正版验证的服务器。
