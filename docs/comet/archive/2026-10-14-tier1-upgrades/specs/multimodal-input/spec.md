# multimodal-input

## Purpose

把图片作为**图像内容**真正喂给模型（而不是只当文件落在工作区），并由**模型级开关**控制。

## 背景（实测）

- 官方 AG-UI 适配器支持入站内容块：`TextInputContent` / `ImageInputContent` → `TextBlock` / `ImageBlock(Source)`。
- 官方 AG-UI 核心线格式：`content: [{type:"text", text}, {type:"image", source:{type:"data"|"url"|"file", value, mimeType}}]`。
- 现状：前端只发纯文本；图片上传后仅物化到工作区，模型只见路径。

## 设计

- `llm_model.multimodal`（V9 迁移，默认 false）：模型页开关，列表可见。
- 前端：发送时若模型已开启多模态，把附件中的**图片**转 base64 `ImagePart` 随消息内容发送；其它附件仍走上传 + 物化 + 附件清单。
- 后端：未开启 → **剥离**图片块并在回复中说明；开启 → 交官方适配器转 `ImageBlock`。
- 限制：单图 ≤ 5MB、仅 png/jpeg/webp/gif、单轮 ≤ 5 张；超限返回明确业务错误。

## 边界

- 只做图片（不含音频/视频/文档多模态）。
- 用 base64 内联，不依赖厂商访问内网 MinIO。

## 验收标准

- 开关可开关并显示；关闭时图片不发送且有明确提示。
- 开启时桩捕获的请求体出现图像内容，模型据此回答。
- 超限/非法格式/超数量给出明确业务错误。
