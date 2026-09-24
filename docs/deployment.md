# YuanHub Backend 部署说明

本仓库可独立 clone / build / release / deploy，不依赖 YuanHub 前端仓库。

- **CI**（`.github/workflows/ci.yml`）：`push main`、PR、手动触发。执行 `ktlintCheck`、`test`、`assemble`，并上传 jar 产物。**不做任何生产部署。**
- **Release**（`.github/workflows/release.yml`）：仅由本仓库自己的 `v*` tag 触发，使用 `environment: production`，通过 SSH 发布。

前端发版不会触发后端发布；后端也不必跟随前端版本号。

> 仓库内没有 Dockerfile / docker-compose / 既有部署脚本，因此采用 **JAR + systemd** 方案（`deploy/yuanhub-backend.service` 为参考单元）。

## 1. 版本概念

| 字段 | 来源 | 含义 |
| --- | --- | --- |
| `productVersion` | 环境变量 `YUANHUB_PRODUCT_VERSION`（GitHub Variable，部署时写入服务器） | YuanHub 产品版本，后端不硬编码 |
| `backendVersion` | 环境变量 `YUANHUB_BACKEND_VERSION`，默认 `v0.1.0` | 后端自身版本，取本仓库 tag |
| `backendCommit` / `branch` / `commitTime` | 构建期 `GitProperties`（`git.properties`） | 后端构建信息 |

`/version` 同时保留旧的 `title` / `description` / `version` / `git` 字段以兼容既有调用方，响应沿用全局 SNAKE_CASE 命名策略。

## 2. 需要配置的 GitHub 项

`release.yml` 里的 SSH 连接用的是 YuanHub 仓库 `promo-site-deploy.yml` 那套**同名**凭据：

### Secrets

| 名称 | 本仓库是否需要新建 |
| --- | --- |
| `YUANHUB_PROMO_VPS_SSH_KEY` | 见下方说明 |
| `YUANHUB_PROMO_VPS_HOST` | 见下方说明 |
| `YUANHUB_PROMO_VPS_USER` | 见下方说明 |
| `YUANHUB_PROMO_VPS_PORT` | 见下方说明 |

> **GitHub 的 secrets 按仓库隔离。** 如果这 4 个是 **Organization secrets 且已授权给本仓库**，那就什么都不用做；
> 否则请在本仓库 **Settings → Secrets and variables → Actions → Secrets** 里添加这 4 个同名 secret，
> 值与 YuanHub 仓库完全一致（同一台 VPS、同一个部署用户、同一把私钥）。
> 私钥全文需含 `BEGIN` / `END` 行。

### Variables（本仓库新增：Settings → Secrets and variables → Actions → Variables）

| 名称 | 示例 | 说明 |
| --- | --- | --- |
| `YUANHUB_BACKEND_DEPLOY_DIR` | `/var/lib/yuanhub-backend` | 后端部署根目录 |
| `YUANHUB_BACKEND_SERVICE` | `yuanhub-backend` | systemd 服务名（**不要猜，按实际安装的填**） |
| `YUANHUB_BACKEND_URL` | `https://api-hub.maayuan.com` | 稳定后端公网地址；内测和正式开放都不变，需能访问 `/version`。 |
| `YUANHUB_PRODUCT_VERSION` | `0.0.1-beta.1` | 当前线上 YuanHub 产品版本 |
| `YUANHUB_KEEP_RELEASES` | `5` | 可选。保留的历史版本目录数量，默认 5 |

> `environment: production` 会由 GitHub 在首次运行时自动创建，无需手工建；要发布需人工批准就在该 environment 加 Required reviewers。

## 3. 服务器需要提前准备

1. 复用同一台 VPS 上已有的部署用户（`YUANHUB_PROMO_VPS_USER`）；确认其公钥已在服务器 `~/.ssh/authorized_keys`。
2. Java 21 运行时（`/usr/bin/java`，与单元文件保持一致）。
3. 目录与文件布局：

   ```bash
   sudo mkdir -p /var/lib/yuanhub-backend/{releases,shared}
   sudo chown -R deploy:deploy /var/lib/yuanhub-backend
   sudo -u deploy touch /var/lib/yuanhub-backend/shared/backend.env
   chmod 640 /var/lib/yuanhub-backend/shared/backend.env
   ```

   - `shared/backend.env`：**运维维护**（Mongo/Redis 地址、JWT secret、端口等），release workflow 不会覆盖它。
   - `shared/version.env`：由 workflow 每次发布写入 `YUANHUB_BACKEND_VERSION` / `YUANHUB_PRODUCT_VERSION`。
   - `application-prod.yml` 之类的生产配置放在服务器上（仓库已 gitignore），不要提交。

4. **必填的生产配置**（否则会带着开发配置启动）。`application.yml` 里 `spring.profiles.active` 默认是 `dev`，
   而 `application-dev.yml` 指向内网 `192.168.31.21` 的 Mongo/Redis —— 所以必须显式切到 prod：

   ```bash
   # /var/lib/yuanhub-backend/shared/backend.env（chmod 640，勿提交）
   SPRING_PROFILES_ACTIVE=prod
   SERVER_ADDRESS=127.0.0.1
   SPRING_DATA_MONGODB_URI=mongodb://<user>:<pass>@<host>:27017/MaaBackend
   SHARE_MONGO_HUB_URI=mongodb://<user>:<pass>@<host>:27017/HubBackend
   SPRING_DATA_REDIS_HOST=<redis-host>
   SPRING_DATA_REDIS_PORT=6379
   SPRING_DATA_REDIS_PASSWORD=<redis-password>
   SHARE_JWT_SECRET=<随机长字符串，务必替换默认值>
   SHARE_PUBLIC_BASE_URL=https://api-hub.maayuan.com
   SHARE_CORS_ALLOWED_ORIGIN_PATTERNS=https://beta-hub.maayuan.com,https://hub.maayuan.com
   SHARE_AVATAR_DIR=/var/lib/yuanhub-backend/data/avatar
   SHARE_MEDIA_DIR=/var/lib/yuanhub-backend/data/media
   SHARE_PRIVATE_MEDIA_DIR=/var/lib/yuanhub-backend/data/private-media
   SHARE_STAR_CAPTURE_DIR=/var/lib/yuanhub-backend/data/star-captures
   LOGGING_FILE_NAME=/var/lib/yuanhub-backend/logs/latest.log
   ```

   另外两处默认值在生产通常要改（可写在服务器的 `application-prod.yml` 里）：

   - `debug.email.no-send: true` 是**基础配置**的默认值 —— 不改的话注册/验证码邮件只会打进日志，不会真的发送。
     需要真实发信时，同时配置 `share.mails` 的 SMTP，并把 `debug.email.no-send` 设为 `false`。
   - `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` 默认 `true`，如需对公网隐藏可设为 `false`。

5. 安装 systemd 单元（替换 `__BACKEND_PATH__` / `__RUN_USER__` 后）：

   ```bash
   sudo cp deploy/yuanhub-backend.service /etc/systemd/system/yuanhub-backend.service
   sudo systemctl daemon-reload
   sudo systemctl enable yuanhub-backend
   ```

6. 允许部署用户重启该服务（否则 release workflow 无法重载）：

   ```bash
   echo 'deploy ALL=(root) NOPASSWD: /bin/systemctl restart yuanhub-backend, /bin/systemctl is-active yuanhub-backend, /bin/systemctl status yuanhub-backend' \
     | sudo tee /etc/sudoers.d/yuanhub-deploy
   sudo chmod 440 /etc/sudoers.d/yuanhub-deploy
   ```

   若 `YUANHUB_BACKEND_SERVICE` 用了别的名字，上面的 sudoers 规则也要同步改。

7. 反向代理（nginx 示例）把公网地址转发到后端端口：

   ```nginx
   server {
     listen 443 ssl;
     server_name api-hub.maayuan.com;

     location / {
       proxy_pass http://127.0.0.1:8080;
       proxy_set_header Host $host;
       proxy_set_header X-Real-IP $remote_addr;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_set_header X-Forwarded-Proto $scheme;
     }
   }
   ```

   Cloudflare 中让 `api-hub.maayuan.com` 指向同一台服务器并开启 Proxy。后端只监听 `127.0.0.1:8080`，不要直接把 8080 暴露到公网。

## 4. 发布流程

```bash
git tag v0.2.0
git push origin v0.2.0
```

workflow 依次执行：

1. `./gradlew test` → `./gradlew assemble`（产出 bootJar）
2. 上传 jar 到 `$YUANHUB_BACKEND_DEPLOY_DIR/releases/v0.2.0/app.jar`
3. 写入 `shared/version.env`，原子切换 `current` 符号链接（先临时链接再 `mv -T`）
4. `systemctl restart $YUANHUB_BACKEND_SERVICE`，并确认服务处于 active
5. health check：轮询 `$YUANHUB_BACKEND_URL/version`，直到 `data.backend_version` 等于本次 tag
6. 创建 GitHub Release（附 jar）
7. 按 `YUANHUB_KEEP_RELEASES` 清理旧版本目录

> 后端没有 `VERSION` 文件：版本号就是 tag 本身，由 workflow 注入 `YUANHUB_BACKEND_VERSION`。

## 5. 人工回滚

```bash
ssh deploy@<HOST>
cd /var/lib/yuanhub-backend
ls -1dt releases/*/
ln -sfn releases/v0.1.0 .current-tmp && mv -T .current-tmp current
# 同步回退 /version 报告的后端版本
printf 'YUANHUB_BACKEND_VERSION=v0.1.0\nYUANHUB_PRODUCT_VERSION=<当前产品版本>\n' > shared/version.env.tmp
mv -f shared/version.env.tmp shared/version.env
sudo systemctl restart $YUANHUB_BACKEND_SERVICE
curl -s localhost:8080/version   # 确认 backend_version
```

## 6. 本地验证

```bash
./gradlew ktlintCheck
./gradlew test
./gradlew assemble
java -jar build/libs/*.jar   # 需本地 Mongo/Redis 配置
```

容器依赖的完整套件（`integrationTest` / `apiSchemaTest` / `realisticTest`）需要 Docker，可在本机按 `./gradlew integrationTest apiSchemaTest` 单独执行；默认 CI 不包含它们。
