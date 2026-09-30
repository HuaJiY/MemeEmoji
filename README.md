# MemeEmoji 🎭

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?logo=minecraft)
![Fabric](https://img.shields.io/badge/Fabric-0.19.3-orange?logo=fabric)
![ModernUI](https://img.shields.io/badge/ModernUI-3.13.0.1-green)

Minecraft 1.21.1 Fabric 模组，把自定义图片/GIF 丢进文件夹，聊天时用 :名字: 就能发送。兼容 ModernUI 现代文本引擎。

不再需要数据包、不需要额外资源包，图片丢进去直接能用。

---

## 效果

![demo](https://github.com/user-attachments/assets/xxxxx) <!-- 你可以替换成实际的截图 -->

## 功能

- 🖼️ **图片/GIF 直接丢** — 支持 PNG、JPG、WebP、GIF、BMP，不用转格式
- 📝 **:名字: 发送** — 中文名也支持，大小写不敏感
- 🎨 **ModernUI 兼容** — 与 ModernUI 文本引擎无缝集成，不互相干扰
- 🔄 **服务端自动同步** — 服务器装了这个 mod，客户端的表情自动下发，无需每个玩家手动装
- 📐 **三种大小预设** — Small / Medium / Large，点几下配置文件就能调
- 🖱️ **表情选择界面** — 聊天框旁边有个按钮，点开选表情
- 💨 **缓存加速** — 图片裁切后缓存到磁盘，下次启动飞快

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（≥0.16.0）
2. 下载 [Fabric API](https://modrinth.com/mod/fabric-api) 放进 mods/
3. 下载 [ModernUI](https://modrinth.com/mod/modern-ui) 放进 mods/（可选但推荐）
4. 下载 [Forge Config API Port](https://modrinth.com/mod/forge-config-api-port) 放进 mods/（ModernUI 的依赖）
5. 下载 MemeEmoji 放进 mods/

## 使用

### 添加表情

把图片文件直接丢进 .minecraft/config/memeemoji/emoji/ 目录：

`
config/memeemoji/emoji/
├── 猫猫.webp
├── 狗头.jpg
├── happy.png
└── 草.gif
`

文件名（不含扩展名）就是在聊天里用的 :名字:。中文名、英文名、数字都支持。

### 在聊天里使用

`
:猫猫: 今天天气真不错
`

发送后 :猫猫: 会被替换成对应的图片。

### 表情选择界面

在聊天框输入时，输入框右侧有个 😊 按钮，点击打开表情选择面板。

## 配置

文件位置：.minecraft/config/memeemoji/config.json

`json
{
  "enabled": true,
  "sizePreset": "medium",
  "smallCellSize": 24,
  "mediumCellSize": 48,
  "largeCellSize": 96,
  "maxCellSize": 128
}
`

| 字段 | 默认值 | 说明 |
|------|--------|------|
| nabled | 	rue | 是否启用 |
| sizePreset | "medium" | 大小预设："small" / "medium" / "large" |
| smallCellSize | 24 | 小号时每个表情的像素大小 |
| mediumCellSize | 48 | 中号时每个表情的像素大小 |
| largeCellSize | 96 | 大号时每个表情的像素大小 |
| maxCellSize | 128 | 格大小上限，防止意外值撑爆聊天 |

配置文件不存在时会自动生成；缺字段时会自动补全。

## 构建

`ash
git clone https://github.com/HuaJiY/MemeEmoji.git
cd MemeEmoji
./gradlew.bat build
`

构建产物：uild/libs/memeemoji-0.1.0.jar

## 技术原理

（给好奇的人看）

- **字体注入**：把所有图片拼成一张图集，通过 BitmapProvider 注册为自定义字体，码位从 PUA 区 U+E000 开始分配
- **文本拦截**：通过 Mixin 注入 StringDecomposer.iterate/iterateFormatted，将 :名字: 替换为对应的 PUA 码位
- **ModernUI 集成**：TextLayoutProcessor.createVanillaLayout 的布局计算路径会跳过替换，只聊天渲染路径走替换，避免 advance 计算错误
- **资源包注入**：生成的图集写入 config/memeemoji/generated/，通过 PackRepositoryMixin 挂进资源包列表，不走 Fabric 内置资源包 API（因为那个只能指向 mod jar 内部路径）

## 鸣谢

- [Twemoji](https://github.com/Leclowndu93150/Twemoji) — 原始灵感来源
- [ModernUI](https://github.com/BloCamLimb/ModernUI-MC) — 现代文本引擎
