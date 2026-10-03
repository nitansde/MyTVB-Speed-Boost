# MyTVB-Speed-Boost

这是基于 [qianxuntudou-ops/MyTVB](https://github.com/qianxuntudou-ops/MyTVB) 的 BTR 增强版本。

本分支接入了 [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper) 的 CDN 调度思路：保留 B 站媒体签名，只扩展兼容的大陆/海外 CDN 节点，再复用 MyTVB 原有的延迟画像和故障切换 DataSource，改善海外播放冷门视频时的首段和卡顿恢复。实现位于 `app/src/main/java/com/mytvb/feature/player/btr/BtrCdnResolver.kt`，不会改动登录、会员、画质或媒体权限。

BTR 项目采用 MIT License，协议文本见 [BTR-MIT.txt](https://github.com/MrTangLuyao/Bilibili-thread-ripper/blob/main/LICENSE)。MyTVB 上游未在仓库根目录提供独立 LICENSE 文件，本分支保留其原始 README 和归属声明；发布时请同时保留上游版权与免责声明。

---

# MyTVB

📺 一个专为 Android TV 设计的第三方 Bilibili 客户端，像素级对齐 BLBL。

## ✨ 主要功能

- 🎬 **视频播放** - 番剧、电影、电视剧、UGC 视频
- 📡 **直播 & CCTV** - 直播弹幕；央视 1-17 套遥控器上下切台
- 💬 **弹幕引擎** - 轻量化引擎，支持人物区域智能防挡。
- ✈️ **空降助手** - 自动跳过恰饭/开场/片尾片段，进度条显示片段标记
- 🎮 **互动视频 / 抖音模式** - 互动分支选择；上下滑动切换推荐
- 👶 **青少年模式** - 家长控制的内容过滤
- 🖱️ **长按快捷操作** - 视频卡片长按唤起快捷菜单
- 📲 **扫码登录** - TV 端扫码快速登录

## 💡 小技巧

- ⏩ **播放倍速** - 播放中长按 OK 临时 2 倍速（在当前倍速上再翻倍，松手还原）；可开启"音乐区视频默认1倍速"让音乐不受全局倍速影响
- 🔄 **回到顶部 / 刷新** - 列表页按菜单键回到顶部并刷新当前分区
- 🎛️ **自定义控制区按键** - 设置 → 播放设置里可自定义默认播放倍速（最高 3.0x）、常驻显示播放倍率，并支持把播放速度等高频操作按键添加到播放控制区

## 🛠️ 技术栈

- **Kotlin 2.1.0** · MVVM + Koin 依赖注入
- **Coroutines + Flow + LiveData** 异步
- **Retrofit + OkHttp + Gson** 网络层
- **Media3 (ExoPlayer)** 视频播放（CCTV 走 WebView）
- **弹幕引擎**（Protobuf 协议解析）
- **DataStore Preferences** 数据存储
- **AndroidX** UI 组件
- **minSdk 23 (Android 6.0)** / **targetSdk 35 (Android 15)**

## 📷 APP 截图

主界面
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/b746a1dd-8243-4378-a1ea-c023460323b7" />

播放 & 弹幕
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/4a0199e0-64a0-4762-9d45-8ec2ff58524a" />

屏蔽功能
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/cae6be14-85cb-41e0-bfee-098214ffbb43" />

## 🙏 感谢

- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) - B 站 API 收集整理
- [BBLL](https://github.com/xiaye13579/BBLL) - 绝大部分页面和操作逻辑参考自 BBLL 🥰
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) / [BiliPai](https://github.com/jay3-yy/BiliPai) / [Blbl](https://github.com/cat3399/blbl) - 部分关键功能参考
- 其它开源第三方 B 站客户端

## ⚠️ 免责声明

- 📚 **用途**：本项目仅供学习交流与技术研究，不得用于任何非法活动或干扰 B 站正常运营
- ©️ **版权**：所有视频、弹幕、图片、文字等内容版权归 Bilibili 及原创作者所有，本项目不存储任何上述内容，仅作播放呈现
- ™️ **商标**：「Bilibili」「B 站」及相关 Logo、形象均为上海宽娱数码科技有限公司的商标，本项目与之无任何关联
- 🔒 **隐私**：本项目不收集、不上传任何用户数据；登录凭据保存在本地，不上传至任何第三方服务器
- 🚫 **无担保**：本项目「按现状」提供，不保证功能可用、稳定或持续更新，因使用本项目产生的任何直接或间接损失，作者不承担责任
- 🚫 **禁止宣传**：不得在 B 站、官方账号区域（含微博评论区）及微信公众号宣传本项目
- 🚫 **禁止牟利**：不得利用本项目牟利；本项目无任何盈利，第三方盈利与本项目无关
- 📮 **侵权联系**：若本项目侵犯了您的合法权益，请联系作者，确认后将在第一时间处理或下架

> 💡 代码由 Codex 和 [智谱 AI](https://bigmodel.cn/) 编写，如有问题请联系 [OpenAI](https://openai.com/) 或 [智谱](https://bigmodel.cn/) 😤
