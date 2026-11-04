# 原生公式组件的对应源码与许可

DSHA 原生富文本使用未修改的 `ru.noties:jlatexmath-android:0.2.0`。同目录的
`jlatexmath-android-v0.2.0.zip` 是上游官方 v0.2.0 标签的完整对应源码归档，包含字体、字体许可和原构建脚本；不是从 APK 反编译的源码。精确 URL、提交、大小及 SHA-256 见 `sources.lock.json`。

核对记录：归档的 Git ZIP 注释提交为 `cc2a83dcf695028b5588f91a90734d94eb5bd02b`。
官方 Maven Central sources JAR 中 182 个 Java 源成员逐字节匹配此归档；官方 AAR 的 77 个字体/资源成员逐字节匹配归档中的 `jlatexmath/src/main/assets/`。这说明对应源码/资源一致，不声称重新编译得到逐字节相同 AAR。

上游 `LICENSE` 声明 GPL-2.0-or-later，并明确提供独立模块链接例外；库仍遵循其原许可。DSHA 没有修改库，不把该例外扩展为所有字体或其他组件都采用 MIT。`COPYING` 与字体目录中的 OFL、Knuth、dsrom 等原许可完整保留。

发布 APK 时，应在同一下载位置提供此原始源码归档，或提供包含本目录完整内容的 DSHA 完整源码 ZIP，以及第三方声明和许可。不要只提供上游外链，或仅提供不含该归档的精简应用源码。这样源码可与本次二进制同处获取，不依赖后续源代码书面请求。APK 的原始字体与字体许可由依赖 AAR 合并到 assets；应用另保留 `jlatexmath-LICENSE.txt` 和 `jlatexmath-COPYING.txt`。

Markwon 4.6.2 的 Apache-2.0、CommonMark Java 0.13.0 的 BSD-2-Clause 原许可也随应用 assets/licenses 保留。运行时只使用原生 Android View/Drawable，不启用 HTML、远程图片加载或远程公式服务。
