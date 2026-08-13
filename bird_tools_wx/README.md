# AI 观鸟小程序

已实现：AI 观鸟问答首页、Flash / Pro 模型切换、`max_tokens` 配置、地点偏好、关注/不关注鸟种设置，以及地点明细中的不关注鸟过滤。

## 部署前配置

1. 在 `miniprogram/app.js` 填写微信云开发环境 ID。
2. 上传并部署 `cloudfunctions/quickstartFunctions`，选择“云端安装依赖”。
3. 在云函数环境变量中配置 `DEEPSEEK_API_KEY`。不要把 Key 放到小程序前端代码或本地缓存中。

当前云函数会代理 DeepSeek 请求，API Key 始终只保留在云端。

## 观鸟记录查询

Android 端的真实记录查询依赖观鸟记录中心的 RSA 签名、AES 解密和验证码流程。小程序需要将现有 Python 服务部署为 HTTPS 后端，再由云函数调用，才能安全复用该能力；当前小程序不会伪造“真实记录”或地点明细数据。

---

# 云开发 quickstart

这是云开发的快速启动指引，其中演示了如何上手使用云开发的三大基础能力：

- 数据库：一个既可在小程序前端操作，也能在云函数中读写的 JSON 文档型数据库
- 文件存储：在小程序前端直接上传/下载云端文件，在云开发控制台可视化管理
- 云函数：在云端运行的代码，微信私有协议天然鉴权，开发者只需编写业务逻辑代码

## 参考文档

- [云开发文档](https://developers.weixin.qq.com/miniprogram/dev/wxcloud/basis/getting-started.html)
