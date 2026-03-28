# leodanmu

私有构建仓库，用于自动生成 leodm.jar。

## 使用说明

### 自动构建

每次向 `master` 分支推送代码，GitHub Actions 会自动：
1. 编译 Android APK
2. 用 apktool 提取并重新打包生成 `leodm.jar`
3. 发布新 Release，包含 `leodm.jar` 和 `leodm.jar.md5`

### 手动触发

在 [Actions 页面](https://github.com/leotvgo/leodanmu/actions) 点击 `Build leodm.jar` → `Run workflow`。

### 下载最新版本

前往 [Releases](https://github.com/leotvgo/leodanmu/releases/latest) 下载最新的 `leodm.jar`。

## 目录结构

```
├── app/          # Android 项目源码
├── jar/          # apktool 打包模板及产物
│   ├── 3rd/      # apktool jar
│   └── spider.jar/  # 打包模板
├── json/         # 配置文件
├── gradle/       # Gradle wrapper
└── .github/
    └── workflows/
        └── build.yml  # 自动构建 workflow
```

## 构建环境

- JDK 17
- Gradle 8.x
- apktool 2.11.0
