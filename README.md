# 小说朗读（MOSS-TTS-Nano · Android · Material You）

用 [OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano) 的 100M ONNX 模型在**手机本地**朗读小说，
朗读时不需要联网。支持 Android 11+（Material You 动态取色需要 Android 12+，Android 11 使用固定配色）。

## 功能

- 导入 TXT / EPUB：自动识别 UTF-8 / GBK / GB18030 / Big5 / UTF-16 编码，自动整理硬折行
- 自动分章：第X章/回/节、序章、楔子、番外、Chapter N、Markdown 标题；自动丢弃目录页；没有章节标记时按篇幅切分
- 自动分段：按段落 → 句子 → 分句切分，token 预算与官方一致；过长的句子用 ICU 词典分词，在连词/介词前等语义合适的位置断开，不会切在词中间
- 语义停顿：按句末标点、段落、场景分隔线、对话轮换、句子长短决定每段之后的停顿，可整体缩放
- 数字读法：阿拉伯数字按中文语境读（年份、百分数、小数等）
- 声音：内置音色、**克隆音色**（选音频文件或直接录音）、**指定旁白/对话使用不同声音**，可按书保存
- 后台朗读：前台服务 + 通知栏控制，锁屏继续，定时关闭，语速 0.8x–2x
- 点击任意段落从那里开始读，阅读进度自动保存

## 用 GitHub Actions 编译 APK

1. 在 GitHub 新建一个仓库（私有也行），把本目录**全部文件**（包括隐藏的 `.github` 文件夹）传上去。
   推荐用命令行：
   ```bash
   cd MossNovelReader
   git init && git add . && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/你的用户名/仓库名.git
   git push -u origin main
   ```
2. 打开仓库的 **Actions** 页面，会自动开始 `Build APK`（也可以点它 → **Run workflow** 手动触发）。第一次约 8–15 分钟。
3. 完成后：
   - 仓库右侧 **Releases → 最新构建** 里直接下载 `app-release.apk`（手机浏览器就能下）；
   - 或者在这次运行页面底部 **Artifacts** 下载 `MossNovelReader-apk.zip`，解压得到 APK。
4. 手机上允许"安装未知来源应用"，安装 APK。

> 这个 APK 用自动生成的 debug 证书签名，可以直接安装自用。每次 CI 生成的证书不同，
> 所以**升级时要先卸载旧版**（模型和书会丢）。想能覆盖升级，需要自己生成 keystore 放进 GitHub Secrets 并修改 `app/build.gradle.kts` 的签名配置。

## 第一次使用

1. 打开 App → 设置 → **下载模型**（约几百 MB，建议 Wi-Fi）。国内访问不了 Hugging Face 时，把「下载源」切到 hf-mirror；
   仍然不行就在电脑上下载两个仓库（`OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX`、`OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX`），拷到手机后用「从文件夹导入」。
2. 设置里点 **试听测试**，日志里能看到分词器自检结果和实时率（小于 1 表示生成比说话更快）。
3. 首页右下角导入小说，点开，按播放。
4. 克隆音色：设置里先点「下载克隆编码器」，然后 声音管理 → 选择音频文件 / 录音（5–15 秒清晰人声）。

## 调参建议

- 实时率大于 1（听起来卡顿）：降低「每段最大 token」（首段更快出声）、适当增加线程数，或换更新的手机。
- 停顿觉得太短/太长：设置里调「停顿强度」。
- 对话用另一个声音：阅读页 → 声音 → 打开「对话使用另一个声音」。

## 项目结构

```
app/src/main/java/com/mossreader/
  tts/        MossTtsEngine（ONNX 推理，移植自官方 Python/JS 运行时）、SentencePiece（纯 Kotlin）、ModelStore、VoiceStore
  text/       ChapterSplitter、SegmentPlanner、TtsTextNormalizer、EpubReader、TextDecoder
  audio/      AudioDecoder / VoiceRecorder / PcmPlayer / Resampler
  playback/   ReaderController（合成-播放流水线）、PlaybackService
  ui/         Compose + Material 3 界面
```

## 已知限制

- 没有移植官方的 WeTextProcessing（大型 FST 文本正则化），只做了轻量的中文数字读法；英文缩写、复杂单位等可能读得不理想。
- 只支持 TXT / EPUB（不支持 mobi / pdf / docx）。
- 模型是 fp32 的 100M 参数，中低端手机可能达不到实时。
- 语速调节使用系统的变速不变调，个别机型可能不支持。

## 许可

MOSS-TTS-Nano 为 Apache-2.0。克隆音色请只使用你本人或已获授权的声音。
