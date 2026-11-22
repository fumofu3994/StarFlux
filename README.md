# StarFlux MC Launcher

StarFlux MC Launcher 是一个使用 Java 和 JavaFX 编写的 Minecraft 启动器。

## 当前功能

- 查看、安装和删除已安装的 Minecraft 版本。
- 安装 Fabric、Forge、NeoForge 和 Quilt 模组加载器。
- 使用 Mojang 官方源或 BMCLAPI 镜像下载游戏文件。
- 添加和管理离线账号，并启动游戏。
- 配置 Minecraft 游戏目录。

## 尚未实现

- Modrinth / CurseForge 资源搜索与下载，以及整合包安装入口目前尚未接入实际功能。
- 正版 Microsoft 账号登录、Java 版本自动选择与下载尚未实现。
- 当前启动器使用其运行环境中的 Java 启动游戏；不同 Minecraft 版本可能要求不同 Java 版本。

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

也可以生成包含 Java 运行时及 JavaFX 依赖的应用镜像压缩包：

```powershell
.\gradlew.bat jlinkZip
```

压缩包位于 `build\distributions\`。解压后运行应用目录中的 `bin\StarFluxMCLauncher.exe`。请保留解压后的完整目录，不要只复制可执行文件。JLink 产物针对构建时所在的平台生成。

## 数据与账号说明

默认游戏目录为 `%USERPROFILE%\.minecraft`，也可以在“设置”页面修改。已安装版本、游戏库文件和资源等保存在该目录中。

目前账号为离线昵称，不会验证 Microsoft 账号身份；离线账号不能用于需要正版验证的服务器。
