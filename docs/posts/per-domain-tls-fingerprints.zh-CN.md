# 为什么全局单一 JA3 不够用：Burp Suite 按域名伪造 TLS 指纹

*仅用于授权渗透测试与安全研究。下文默认你对涉及的目标拥有书面测试授权。*

Burp Suite 有一个跟你发什么内容毫无关系的指纹问题：在你的请求产生第一个字节之前，JVM 已经把你的身份报出去了。

## 那个没人配置过的常量

每条 TLS 连接以 ClientHello 开场：协议版本、密码套件、扩展、椭圆曲线，以及它们出现的顺序。把这个结构做哈希就得到 JA3 —— 它标识的是**协议栈**，不是流量。

Java 的 TLS 栈产生的 JA3，地球上没有任何一款浏览器会产生。它不会因为畸形 payload 或可疑 header 才泄露，它在连接建立阶段泄露，每次都泄露，而且完全一致。WAF 匹配这个哈希不需要任何启发式规则，也不需要打分模型 —— 那是一次查表。

所以 Burp 发出的请求和 Chrome 发出的请求，可以在 HTTP 层字节级完全相同，却被判成两类。因为你根本没走到 HTTP 层。

## 现有方案解决了什么

[@sleeyax](https://github.com/sleeyax) 的 [Awesome TLS](https://github.com/sleeyax/burp-awesome-tls) 几年前就把最难的部分解决了，而且解得很干净：起一个基于 [utls](https://github.com/refraction-networking/utls) 的本地 Go 服务，把 Burp 的出站流量重定向过去，由 Go 发出浏览器形状的 ClientHello。不反射 Burp 内部，不 fork Burp 代码。本文提到的整套架构都是他们的 —— 下面要讲的是在真实项目里用起来之后暴露的两个缺口。

## 缺口一：一次测试从来不止一个源站

任何有规模的测试范围都跨多个主机，而这些主机的威胁模型并不相同。官网挂在宽松的 CDN 后面；API 网关挂在 Cloudflare 后面且 bot score 严格；内网管理后台完全没有 bot 防护，TLS 终端老到跟现代 Chrome 的 ClientHello 协商都费劲。

全局单一指纹逼你用一套配置应付这三种情况。按严格的源站调，可能把老旧的那个搞挂；按老旧的调，严格的那个就过不去。多数人的做法是在测试范围里来回切的时候手动改设置 —— 既繁琐又容易忘。而**忘了是静默的**：不报错，请求照发，只是它的评分跟你以为的不一样。

## 缺口二：同一 IP 上混合指纹，比单一坏指纹更糟

这个缺口是真正让我意外的。

只挂 Proxy 的扩展，会把 Repeater、Intruder、Scanner 留在 Burp 默认的 Java 栈上。于是同一个源 IP，在同样几分钟内，表现为：

- 浏览流量是 Chrome 的 JA3
- 你真正用来打的每一个请求是 Java 的 JA3

没有任何浏览器会这样。同一地址上出现两套截然不同的 TLS 栈，不是比纯 Java 更弱的信号，而是**更强的信号** —— 因为它没有任何正当解释。你把「这看起来是自动化的」升级成了「这是自动化的，而且在试图掩饰」。

顺带一提：Intruder 恰恰是你最不希望被重新分类的地方，因为请求量本身就是让你变得「值得注意」的原因。

## 这个 fork 改了什么

[`burp-awesome-tls-plus`](https://github.com/Robin528919/burp-awesome-tls-plus) 完整保留上游的 Go + JNA 架构，针对这两个缺口做改动：

**按域名规则。** 一条规则可以匹配精确主机（`api.example.com`）或通配后缀（`*.example.com`）。精确优先于通配，通配之间最长后缀优先 —— 所以行为不依赖表格里行的排列顺序。留空的字段继承全局默认值，因此一条规则可以只覆盖指纹，超时保持原样。

**覆盖全部 Burp 工具。** 注册 `HttpHandler` 而不是只挂代理，意味着 Proxy、Repeater、Intruder、Scanner，以及其他扩展发出的请求，全都走同一条路径。Burp 自身的套件流量（更新检查、Collaborator 轮询）被刻意放行，因为重定向它会直接搞坏。

有一个实现细节值得手写规则的人知道：**指纹配置和原始 hex ClientHello 是成对覆盖的**。Go 侧只要存在 hex ClientHello 就永远优先使用它，所以一条只设置了具名 profile 的规则，在全局配了 hex 流的情况下会被静默忽略。因此设置其中任意一个都会清空另一个。

规则存在操作系统配置目录下的 `rules.json`，位于 Burp 项目文件之外 —— 项目换来换去它都在，diff 干净，团队之间可以直接用 git 共享。

## 实测效果

Cloudflare bot score，同一目标，同一源 IP，Burp Professional：

| | 截图 |
| --- | --- |
| Burp 默认 Java 栈 | ![原生 Burp 的 bot score](../cloudflare_bot_score_burp_pro.png) |
| 伪造 Chrome 指纹后 | ![Awesome TLS 的 bot score](../cloudflare_bot_score_awesome_tls.png) |

实际发到线上的 ClientHello，Wireshark 抓包：

![Wireshark 抓到的 ClientHello](../wireshark_capture_client_hello.png)

## 上手

1. 从 [releases](https://github.com/Robin528919/burp-awesome-tls-plus/releases) 下载对应平台的 jar —— 预编译了 8 个 OS/arch 目标，另有 fat jar。不需要本地 Go 工具链。
2. Burp → Extensions → Add → 类型选 *Java* → 选中 jar。
3. 在 Repeater 里打 [tls.peet.ws](https://tls.peet.ws/) 或 [Scrapfly 的 HTTP/2 指纹检测](https://scrapfly.io/web-scraping-tools/http2-fingerprint) 验证。看到的 JA3 跟你选的 profile 对得上就成了。

按域名规则在 Domain Rules 标签页加一行即可，留空的单元格继承默认值。

## 这套东西的边界在哪

TLS 指纹对齐只是众多信号里的一个。它对 JavaScript 挑战、CAPTCHA、IP 信誉、行为时序、TLS 会话复用模式统统无效。目标只要在这些维度上给你打分，你照样会被打分。

还有一点值得说实话：所谓 "Chrome" profile，是某个时间点某个 Chrome 版本的 ClientHello 快照。Chrome 每几周就发一个版本。上个季度完美匹配的 profile，今天就是一个略微过期的浏览器 —— 通常没事，偶尔就是暴露你的那一下。

## 致谢

设计、Go/JNA 架构，以及本项目所依赖的绝大部分代码，都是 [@sleeyax](https://github.com/sleeyax) 和[上游贡献者](https://github.com/sleeyax/burp-awesome-tls/graphs/contributors)的工作。本 fork 在其之上继续开发，沿用同样的 GPL-3.0 许可。完整的[功能对比](../comparison.md)在仓库里。
