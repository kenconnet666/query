# 手动发布到 Maven 中央仓库

发布由维护者在本机手动执行，CI 不发布。根 `pom.xml` 的 `release` profile 负责生成源码包和 javadoc 包、签名，并上传到 Central Portal；上传后要在网页上确认，才会正式发布。

## 发布的内容

| 构件 | 文件 |
| --- | --- |
| `query-parent` | POM（各模块的父 POM，必须一起发布） |
| `query-core` | jar、sources、javadoc，以及方言模块测试用的 `tests` jar |
| `query-hana`、`query-postgres`、`query-mysql`、`query-spring` | jar、sources、javadoc |

每个文件都附带 `.asc` 签名和校验和。jar 的清单里写了 `Automatic-Module-Name`（例如 `org.dpdns.zerodep.query.hana`），在模块路径上也能使用。

## 一次性准备

1. **Central Portal 账号**：在 <https://central.sonatype.com> 注册或用 GitHub 登录。
2. **命名空间**：groupId 是 `org.dpdns.zerodep.query`，要在 Portal 的 Namespaces 页面验证 `org.dpdns.zerodep`。Portal 会给出一个验证码，把它加到域名 `zerodep.dpdns.org` 的 DNS TXT 记录里，再点 Verify。
   - 如果这个域名无法通过验证，可以改用 GitHub 命名空间 `io.github.kenconnet666`：用 GitHub 登录 Portal 时会自动验证。这样要把根 POM 和各模块的 groupId、`dependencyManagement` 里的 groupId、`automatic.module.name` 和文档里的坐标一起改掉。包名可以不改。
3. **GPG 密钥**：Windows 上安装 Gpg4win，然后生成密钥并上传到公钥服务器，Central 会从那里取公钥验证签名：

   ```powershell
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```

4. **上传令牌**：在 Portal 的 View Account → Generate User Token 生成令牌，在 `~/.m2/settings.xml` 里通过环境变量引用，不写明文：

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>${env.CENTRAL_TOKEN_USERNAME}</username>
         <password>${env.CENTRAL_TOKEN_PASSWORD}</password>
       </server>
     </servers>
   </settings>
   ```

   `<id>` 必须是 `central`，与 POM 里 `publishingServerId` 一致。

## 每次发布

1. **确认版本**：根 POM 和各模块的版本不带 `-SNAPSHOT`；`CHANGELOG.md` 的"未发布"改成发布日期；`docs/api.md` 里依赖示例的版本号与之一致。
2. **确认 CI**：要发布的提交已经合并到 `master`，GitHub Actions 全部通过（包括三库实库和 Spring 各版本）。
3. **本机预检**：不签名、不上传，确认源码包和 javadoc 包能生成：

   ```powershell
   $env:JAVA_HOME = 'C:\Users\Administrator\.jdks\jbr-17.0.14'
   .\mvnw.cmd --batch-mode -P release '-Dgpg.skip' verify
   ```

4. **上传**：

   ```powershell
   $env:CENTRAL_TOKEN_USERNAME = '<令牌用户名>'
   $env:CENTRAL_TOKEN_PASSWORD = '<令牌密码>'
   $env:MAVEN_GPG_PASSPHRASE = '<GPG 密码>'      # 不设置时由 gpg-agent 弹窗询问
   .\mvnw.cmd --batch-mode -P release deploy
   ```

   插件把所有模块打成一个包上传，并等到 Portal 校验完成（`waitUntil=validated`）。校验失败时命令会报错，原因在 Portal 的 Deployments 页面里。

5. **确认发布**：在 <https://central.sonatype.com/publishing/deployments> 检查文件清单，点 Publish；发现问题就点 Drop，修好后重新上传。发布后通常 30 分钟内能在 <https://repo1.maven.org/maven2/org/dpdns/zerodep/query/> 看到。已发布的版本不能删除或覆盖。
6. **打标签**：

   ```powershell
   git tag -a v1.0.0 -m "1.0.0"
   git push origin v1.0.0
   ```

   在 GitHub 上用这个标签建一个 Release，正文取 `CHANGELOG.md` 里对应的一节。
7. **进入下一个版本**：

   ```powershell
   .\mvnw.cmd --batch-mode versions:set '-DnewVersion=1.1.0-SNAPSHOT' '-DgenerateBackupPoms=false'
   ```

   再在 `CHANGELOG.md` 顶部加一节"未发布"。

## TypeScript

`typescript/packages/*` 的版本号与 Java 保持一致，但目前都是私有包，不发布到 npm。要发布时再单独决定包名（`@query` 这个 scope 需要先在 npm 上注册）和构建产物。
