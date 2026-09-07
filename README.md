# NexusPermit

Luminol Nexus 的 Paper 大厅服插件：玩家在网站发起 MC 账号绑定后，在大厅输入 `/v <验证码>` 完成最终核验；进服时自动反查绑定状态并提示。

## 功能

- `/v <验证码>`：核验网站下发的绑定验证码（一次性、10 分钟有效、防伪身份、防爆破）
- 进服反查：自动私发绑定状态提示（已绑定 / 未核验 / 未绑定引导）
- 安全设计：HTTP 全异步不卡主线程、凭据不落日志、响应解析异常兜底、限流与网络异常友好降级

## 环境要求

- Paper 1.20.6（Java 21）
- 可访问 Nexus 后端服务（生产环境必须 HTTPS）

## 快速开始

```bash
./gradlew build
# 产物：build/libs/NexusPermit-<版本>.jar，放入大厅服 plugins/ 目录
```

首次启动生成 `plugins/NexusPermit/config.yml`，向 Nexus 后端管理员获取后端地址与 Secret 填入后重启。

## 文档

完整部署、配置、安全说明与联调清单见 [docs/NexusPermit-Plugin-Guide.md](docs/NexusPermit-Plugin-Guide.md)。
