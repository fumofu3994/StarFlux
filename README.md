# StarFlux MC Launcher

StarFlux MC Launcher 是一个使用 Java 和 JavaFX 编写的 Minecraft 启动器。

Java 源码按职责分在 `app`、`account`、`config`、`download`、`game`、`multiplayer` 和 `ui` 包中；启动引导入口保留在根包。

## 当前功能

- 创建具名隔离实例，查看、启动、配置、重命名和删除已安装实例。
- 安装 Fabric、Forge、NeoForge 和 Quilt 模组加载器。
- 使用 Mojang 官方源或 BMCLAPI 镜像下载游戏文件。
- 添加和管理离线账号，并启动游戏。
- 为全局或单个实例选择 Java、内存和 JVM 参数；可安装并管理 Temurin Java 8/17/21/25。
- 通过 Terracotta（陶瓦联机）创建或加入 Minecraft 联机房间。
- 配置 Minecraft 游戏目录。

## 尚未实现

- Modrinth / CurseForge 资源搜索与下载，以及整合包安装入口目前尚未接入实际功能。
- 正版 Microsoft 账号登录尚未实现。

首次使用联机功能需要联网下载对应平台的 Terracotta 官方发行包；之后启动器通过其本机 HTTP API 管理房间。房主需先进入 Minecraft 世界并“对局域网开放”，再创建联机房间；好友连接成功后可在 Minecraft 多人游戏列表中加入“陶瓦联机大厅”。Terracotta 独立运行，遵循 AGPL-3.0-or-later（附例外条款），版权归其作者所有。

## 环境要求

- Java 25 或更高版本（运行启动器；游戏可使用管理或手动选择的 Java）
- Gradle Wrapper 9.9.1（项目已包含 Wrapper）
- 网络连接（首次启动时下载 JavaFX/Gson 运行库；加载版本清单、下载游戏文件和安装模组加载器时也需要）

## 运行

在项目根目录执行：

```powershell
.\gradlew.bat run
```

## 构建

执行默认 `build` 任务会同时生成可通过 `java -jar` 启动的 JAR，以及 Windows EXE：

```powershell
.\gradlew.bat build
java -jar .\build\libs\StarFlux-1.0-SNAPSHOT.jar
```

首次运行会在 JAR 同级创建 `.starflux`，并显示进度下载当前平台的 JavaFX/Gson 运行库；下载优先使用阿里云 Maven 镜像，失败时自动切换 Maven Central。启动器设置保存在 `.starflux\config.toml`（旧 `config.properties` 会自动迁移），运行日志保存在 `.starflux\logs\launcher.log`。请保持该目录与 JAR 同级且可写；如果目录或运行库被删除，下次启动时会重新创建/下载。

JAR 位于 `build\libs\`，EXE 与配套 JAR 位于 `build\distributions\`。运行 EXE 时需要安装 64 位 Java 25 或更高版本；未检测到 Java 时，启动器会提示并打开下载页面。JavaFX/Gson 运行库仍会在首次启动时下载至 EXE/JAR 同级的 `.starflux`。如需单独生成 EXE，可执行 `.\gradlew.bat createExe`。

也可以生成包含 Java 运行时及 JavaFX 依赖的应用镜像压缩包：

```powershell
.\gradlew.bat jlinkZip
```

压缩包位于 `build\distributions\`。解压后运行应用目录中的 `bin\StarFluxMCLauncher.exe`。请保留解压后的完整目录，不要只复制可执行文件。JLink 产物针对构建时所在的平台生成。

## 数据与账号说明

默认游戏目录为 `%USERPROFILE%\.minecraft`，也可以在“设置”页面修改。已安装版本、游戏库文件和资源等保存在该目录中。
“下载游戏”默认创建独立实例，可设置实例名称；实例文件位于 `<游戏目录>\instances\` 下，每个实例有独立的版本、存档、模组和设置。安装 Minecraft 本体后，可为对应实例安装 Fabric、Forge、NeoForge 或 Quilt。启动页可按实例配置 Java、内存及 JVM 参数，也可重命名或删除实例。删除独立实例会删除该实例目录中的全部数据；实例独立保存运行库及资源，可能占用更多磁盘空间。

“设置”页面会显示检测到的系统 RAM；最大内存可通过滑块或文本框设置，两者同步，作为 Minecraft JVM 的 `-Xmx` 参数保存。取值为 1 GB 至检测到的整 GB 数（最高 64 GB），默认值为 4 GB（会按设备内存范围调整）。Java 环境会从 `JAVA_HOME`、`JDK_HOME`、`PATH`、常见安装目录和启动器管理目录自动检测，也可以手动选择。自动模式按 Minecraft 的 Java 要求优先选择匹配版本。设置页可直接下载并安装 Eclipse Temurin Java 8/17/21/25 到启动器数据目录 `.starflux\java`；单个实例可继承全局设置、自动选择兼容 Java，或固定到某个已检测的运行时。

目前账号为离线昵称，不会验证 Microsoft 账号身份；离线账号不能用于需要正版验证的服务器。
