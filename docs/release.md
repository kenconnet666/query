# 手动发布到 Maven 中央仓库

发布由维护者在本机手动执行，CI 不发布。根 `pom.xml` 的 `release` profile 负责生成源码包和 javadoc 包、签名，并上传到 Central Portal；上传后要在网页上确认，才会正式发布。

## 发布的内容

| 构件 | 文件 |
| --- | --- |
| `query-parent` | POM（各模块的父 POM，必须一起发布） |
| `query-core` | jar、sources、javadoc，以及方言模块测试用的 `tests` jar |
| `query-hana`、`query-postgres`、`query-mysql`、`query-sqlite`、`query-spring`、`query-maven-plugin` | jar、sources、javadoc |

每个文件都附带 `.asc` 签名和校验和。jar 的清单里写了 `Automatic-Module-Name`（例如 `org.dpdns.zerodep.query.hana`），在模块路径上也能使用。

## 一次性准备

1. **Central Portal 账号**：在 <https://central.sonatype.com> 注册或用 GitHub 登录。
2. **命名空间**：groupId 是 `org.dpdns.zerodep`，已在 Portal 的 Namespaces 页面验证通过：Portal 给出验证码，加到 `zerodep.dpdns.org` 根域名的 TXT 记录里，几分钟后自动变成 Verified。这条 TXT 记录保留，不要删。
3. **GPG 密钥**：本机用 Git for Windows 自带的 gpg 2.4，不另装 Gpg4win。生成密钥后，公钥要传到 **`keys.openpgp.org` 并验证邮箱**，同时传一份到 `keyserver.ubuntu.com`。Ubuntu 的节点同步不一致，只传它会让 Central 间歇性报 `Could not find a public key`；openpgp 稳定，但邮箱未验证前不公开用户名。

   ```powershell
   gpg --quick-generate-key "<名字> <邮箱>" rsa4096 sign 3y   # 口令在 pinentry 窗口里输入
   gpg --list-secret-keys --keyid-format long
   gpg --armor --export <指纹>   # 提交到 https://keys.openpgp.org 并点邮件里的验证链接，再提交到 keyserver.ubuntu.com/pks/add
   ```

   `gpg --send-keys` 在 Git 自带的 gpg 上会报 `No data`，改用 HTTP 接口提交。在 `~/.gnupg/gpg-agent.conf` 里写 `pinentry-program C:/Program Files/Git/usr/bin/pinentry-w32.exe`，否则从 PowerShell 启动的 gpg-agent 找不到 pinentry（`No pinentry`）。私钥的吊销证书在 `~\.gnupg\openpgp-revocs.d\`，备份到仓库之外。
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
   .\mvnw.cmd --batch-mode -P release "-Dgpg.executable=C:\Program Files\Git\usr\bin\gpg.exe" deploy
   ```

   PowerShell 的 PATH 里没有 `gpg`，所以要显式指定；GPG 口令由 pinentry 窗口询问。插件把所有模块打成一个包上传，并等到 Portal 校验完成（`waitUntil=validated`）。校验失败时命令会报错，原因在 Portal 的 Deployments 页面里；只是公钥没取到时，重试几次即可，失败的 deployment 不影响发布，可以在 Portal 里 Drop。

5. **确认发布**：在 <https://central.sonatype.com/publishing/deployments> 检查文件清单，点 Publish；发现问题就点 Drop，修好后重新上传。发布后通常 30 分钟内能在 <https://repo1.maven.org/maven2/org/dpdns/zerodep/> 看到。已发布的版本不能删除或覆盖。
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

## TypeScript（npm）

六个包 `@zerodep-dev/query-core|postgres|mysql|hana|sqlite` 和 `@zerodep-dev/idb` 属于 npm 组织 `zerodep-dev`，版本号与 Java 保持一致。发布前提：账号开启 2FA，环境变量 `NPM_TOKEN` 是勾选了 Bypass 2FA、范围为 `@zerodep-dev` 的 Granular Access Token，`~/.npmrc` 写 `//registry.npmjs.org/:_authToken=${NPM_TOKEN}`。

```powershell
cd typescript
$env:COREPACK_ENABLE_DOWNLOAD_PROMPT = '0'
corepack pnpm install --frozen-lockfile
corepack pnpm test
corepack pnpm -r --filter "@zerodep-dev/*" publish --access public --no-git-checks
```

`pnpm publish` 会把 `workspace:*` 换成实际版本号，所以必须用它而不是 `npm publish`。核心包要先于方言包发布，`-r` 会按依赖顺序处理。已发布的版本不能覆盖，只能发新版本；`npm deprecate` 可以标记作废。
