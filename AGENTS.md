# MemeEmoji — 项目笔记

## 概览

Minecraft 1.21.1 Fabric mod。把图片丢进 config/memeemoji/emoji/，聊天用 :名字: 发出来。

## 关键设计决策

### 资源包注入
- 生成的资源包在 config/memeemoji/generated/，通过 PackRepositoryMixin.discoverAvailable RETURN 注入
- 不走 Fabric registerBuiltinResourcePack（那个只能指向 mod jar 内部的路径）

### 独立纹理（非图集）
- 每个表情有自己的独立 PNG + 独立的 bitmap provider（font.json 里每个表情一项）
- 不超过最大尺寸的图片保持原始分辨率输出，超过最大尺寸的按比例缩小
- 每张图片的 ascent = height - 5（让表情底部在 baseline 以下 5px，背景框底部留 3px）
- lineHeight = maxEmojiHeight + 3（聊天气泡高度适配最大表情）
- PUA 码位从 U+E000 开始，第 i 张表情固定为 U+E000 + i

### 字体渲染
- StringDecomposerMixin 的 3-arg iterate 和 5-arg iterateFormatted 覆盖全部文本链路
- ModernUI 的 TextLayoutProcessor、ModernStringSplitter、FormattedTextWrapper 全部走 5-arg hook
- ChatComponentMixin.getLineHeight()：返回 max(原始行高, maxEmojiHeight + 3)
- ShapingScope 包裹 ModernUI 的 createVanillaLayout，禁用 shaping 防止 advance 数组长度不匹配

### WebP 支持
- 手动 SPI 注册 WebpSupport.ensureRegistered()，不从 spi 服务文件加载
- jar 合并时排除 META-INF/services/* 防冲突

### 服务端同步
- 分片传输（每片 768KB），Start / Chunk / End 三段式，Chunk 携带 width/height
- 单人模式不走同步，直接读本地文件夹

### EmojiPickerScreen
- 纯 Screen 实现，用 render/mouseClicked/mouseScrolled 手动绘制
- 用 ImageTiles.scaleToFill() 把所有表情 bicubic 缩放到统一格子大小
- 按 ESC 关闭，点击表情后回调通知调用方

### ChatScreen 表情按钮
- ChatScreenMixin：init 尾注入缩窄输入框；render 尾注入绘制按钮；mouseClicked HEAD 拦截点击

### 配置
- 文件: config/memeemoji/config.json
- 字段: enabled, maxNameLength, sendToClients, sizePreset(small/medium/large), smallCellSize(24), mediumCellSize(48), largeCellSize(96), maxCellSize(128)

## 踩过的坑

1. **MemeEmojiConfig.DEFAULT 循环引用**: 字段初始值不能引用 DEFAULT 自身，否则 <clinit> 时 NPE。改为字段默认值（无参构造）。
2. **jar 合并 SPI 冲突**: webp-imageio 的 META-INF/services/javax.imageio.spi.ImageReaderSpi 在合并进 mod jar 后与 ImageIO 内置 SPI 冲突。已在 jar task 中 exclude.
3. **ModernUI 3.13.0.1 依赖 ForgeConfigAPIPort >= 21.0.5**: 开发测试环境已放好两个 jar.
4. **不需 modmenu**: ForgeConfigAPIPort 的 modmenu 建议只是版本建议，不影响运行.
5. **ChatScreen 按钮不能用 @Shadow addRenderableWidget**: ModernUI 环境下该 Shadow 不可靠，改为纯手工绘制。
6. **TextLayoutProcessorMixin createVanillaLayout 需 CallbackInfoReturnable**: 因为 createVanillaLayout 是非 void 方法，用 CallbackInfo 会导致 mixin 应用失败。
7. **ModernUI BitmapFont advance 重叠**: 旧版统一图集时 font.json 的 height 必须等于纹理 cell 大小。独立纹理后由每个图片的实际 height 决定。
8. **glyphAscent 计算**: ascent = height - 5，使所有表情底部对齐到 baseline + 5，比聊天气泡底部（baseline）高 3px。
9. **从统一图集迁移到独立纹理**: 改了 EmojiTile（加 width/height）、ImageTiles（加 fitToMax/scaleToFill）、EmojiPack（多 provider）、TileStore（cache v2）、MemeEmoji（cell→maxSize）、MemeEmojiClient（跟踪 maxEmojiHeight）、协议（加 width/height）。
