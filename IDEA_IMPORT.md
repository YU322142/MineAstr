# IntelliJ IDEA / Gradle 导入说明

## 这个仓库的“模板”在哪里？

NeoForge 项目模板就是 `minecraft-neoforge-1.21.1` 分支的仓库根目录，不是 `src/`，也不是一个需要提交的 `.idea/` 文件夹。完整 Gradle 入口由这些文件组成：

- `settings.gradle`：声明项目名、Gradle 插件仓库和工具链解析器。
- `build.gradle`：NeoForge ModDevGradle、Java 21 工具链、客户端/服务端运行配置、测试和资源处理。
- `gradle.properties`：Minecraft 1.21.1、NeoForge 21.1.219、Parchment 和 MineAstr 版本。
- `gradlew` / `gradlew.bat`：固定使用仓库指定的 Gradle Wrapper。
- `gradle/wrapper/`：Wrapper JAR 和发行版地址。
- `src/main/`：Mod 源码、资源、语言文件和 `neoforge.mods.toml` 模板。
- `src/test/`：JUnit/NeoForge 单元测试。

`.idea/`、`.iml` 和 `run/` 不提交，因为它们包含本机路径和用户界面状态。IDEA 应从 Gradle 文件重新生成本机项目模型。

## 推荐导入流程（Windows）

1. 在 GitHub 分支选择器中选择 `minecraft-neoforge-1.21.1`，不要在 `main` 分支导入项目。
2. 克隆仓库后，在 IDEA 中选择 **File → Open**，打开包含 `settings.gradle` 和 `build.gradle` 的根目录。
3. IDEA 检测到 Gradle 项目时选择 **Trust Project**，并选择 **Import as Gradle Project**。如果没有弹窗，右侧 Gradle 面板点击 **+ / Link Gradle Project**，选择根目录的 `build.gradle`。
4. 在 **Settings → Build, Execution, Deployment → Build Tools → Gradle** 设置：
   - Distribution：`gradle-wrapper.properties` 中的 Wrapper；
   - Gradle JVM：64 位 JDK 21（不是 JRE）；
   - Build and run using：`Gradle`；
   - Run tests using：`Gradle`。
5. 等待 Gradle 同步完成。首次同步会下载 Gradle、Minecraft/NeoForge 开发依赖并执行 `createMinecraftArtifacts`，没有本机缓存时可能需要数分钟和数百 MB 网络流量。
6. 同步完成后，在 Gradle 面板运行 `clean` → `test` → `build`。也可以在 Windows Terminal 执行：

   ```powershell
   .\gradlew.bat clean test build --no-daemon
   ```

7. 客户端和服务端运行配置由 `build.gradle` 的 `neoForge.runs` 生成；不要手工复制其他整合包的 `.idea` 或 `run` 目录。

## 常见“无法编译”原因

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| IDEA 只有普通 Java 目录，没有 Gradle 面板 | 打开了 `src` 或错误分支，而不是仓库根目录 | 重新打开含 `settings.gradle` 的根目录，并链接 `build.gradle`。 |
| `Unsupported class file major version` / toolchain 错误 | 使用了 JRE、32 位 Java，或没有 JDK 21 | 安装 64 位 JDK 21，在 Gradle 设置中明确选择它。 |
| `Could not resolve ...` / Wrapper 下载失败 | 首次构建依赖网络和 Maven/Gradle 源，缓存为空或代理不通 | 先运行 `gradlew.bat --version`，检查代理、镜像和证书，再重试同步。 |
| 找不到 `createMinecraftArtifacts` | 没有通过 Wrapper 完整同步，或直接使用了系统 `gradle` | 使用仓库自带的 `gradlew.bat`，不要使用旧版全局 Gradle。 |
| 能同步但编译源码报错 | IDEA 没有完成 Gradle 导入，仍使用 IDEA 自己的编译器模型 | 将 Build and run/tests 都改成 `Gradle`，重新刷新 Gradle 项目。 |
| 从 `main` 下载后找不到 NeoForge 源码 | `main` 只是项目索引，不是 NeoForge 工程 | 切换到 `minecraft-neoforge-1.21.1` 分支再导入。 |

## 最小环境检查

```powershell
git branch --show-current
java -version
.\gradlew.bat --version
.\gradlew.bat javaToolchains
```

期望结果：当前分支为 `minecraft-neoforge-1.21.1`，Gradle Wrapper 为 `9.2.1`，并且 `javaToolchains` 能找到 Java 21。项目源码目标版本是 Java 21；Java 25 不能替代已配置的 Java 21 工具链。

## English summary

The Gradle template is the repository root of the `minecraft-neoforge-1.21.1` branch. Open the directory containing `settings.gradle` and `build.gradle`; do not open `src/`, `build/`, or `main`. Use the checked-in Gradle Wrapper and a 64-bit JDK 21. `.idea/`, `.iml`, and `run/` are intentionally not committed because they are machine-specific. Run `gradlew.bat clean test build --no-daemon` after the Gradle sync completes.
