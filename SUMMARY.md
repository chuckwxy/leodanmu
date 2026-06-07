# 追更助手集成概要

## 目标
将追更助手 `week_airing`（本周更新）集成到 Leodanmu 豆瓣"正在追更"分类。用户选中后，后续导航逻辑必须与原追更助手 T4 源完全一致。

## 约束
- 不改追更助手.js 后端逻辑，透明代理
- 不改 DoubanFetcher `vod_tag` 处理（保留`c74fffd`的去除逻辑）
- 不新增 `proxyYlhjCategory` 方法
- 不修改 `categoryContent` / `categoryContentShellFallback`
- 只通过 `detailContent` + `playerContent` 链路代理

## 改动历史

### commit `b027812`（最新 ✅）
仅改 Leodanmu.java 一行：`isYlhjId` 加入 `trackdrive://` `trackplay://` 前缀。
- `detailContent` 的 `proxyYlhjDetail` 现在能处理 `trackdrive://` 和 `track://` ID
- `playerContent` 的 `proxyYlhjPlay` 现在能处理 `trackplay://` ID

### commit `bc0c08a`（已废弃 ❌）
曾尝试添加 `proxyYlhjCategory` + 修改 `categoryContentShellFallback`，但违反用户"不改后续逻辑"的要求，且编译失败（重复 `}` 和重复方法定义）。

### commit `c74fffd`（基线 ✅）
去掉 week_airing 列表的 `vod_tag: "folder"` 标记，让 TVBox 走正常 `detailContent` 链路。

## 路由链
```
┌──────────────────────────────────────────────────────────────┐
│ week_airing 列表 (vod_tag 已去掉 → TVBox 走 detailContent )  │
│   └─ vod_id: track://tv/12345                               │
│                                                              │
│ 1. TVBox → detailContent(["track://tv/12345"])              │
│    Leodanmu.proxyYlhjDetail → API ?id=track://tv/12345      │
│    → 返回 7 个网盘组文件夹 (vod_id: trackdrive://...)        │
│                                                              │
│ 2. 点击文件夹 → TVBox 调用 detailContent(["trackdrive://..."]) │ (isYlhjId 现在含 trackdrive)
│    Leodanmu.proxyYlhjDetail → API ?id=trackdrive://...       │
│    → 返回资源文件列表 (vod_id: link://...)                    │
│                                                              │
│ 3. 点击文件 → TVBox → detailContent(["link://..."])         │
│    Leodanmu.proxyYlhjDetail → API ?id=link://...             │
│    → 返回含 vod_play_url 的详情                              │
│                                                              │
│ 4. 点击播放 → TVBox → playerContent("", "trackplay://...")  │ (isYlhjId 现在含 trackplay)
│    Leodanmu.proxyYlhjPlay → API ?play=trackplay://...&flag=..│
│    → 返回可播放 URL                                          │
└──────────────────────────────────────────────────────────────┘
```

## API 细节
- HOST: `http://192.168.31.77:8160/video/ylhj_tracking`
- Header: `token: sfahefjkahskjfha`
- 容器内 3000 直达返回 404；8160 经反向代理正常（nginx-proxy-manager）
- 查询参数后缀：`?id=...`（detailContent）、`?play=...&flag=...`（playerContent）
