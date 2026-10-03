# MC IPv6 Guardian

Linux Docker 维护容器：读取指定路由器的 RA，跟踪有效公网 /64 前缀，维护固定后缀的 IPv6 入口，通过专用 TCP 转发连接现有 Minecraft 容器，并更新指定 Cloudflare AAAA。前缀变化时保留旧管理地址最多 10 分钟，无需重建 MC 网络或重启游戏。

`guardian.py` 使用宿主机网络，只有 NET_ADMIN/NET_RAW capabilities、只读根文件系统及专用状态目录，不挂载 Docker socket 或宿主机根目录。仅修改自身的 `ip6 mc_ipv6_guardian` nftables 表和管理地址。辅助端口只接受经本表 DNAT 的连接。

## 配置与部署

1. 复制 `config.example.json` 为 `config.json`，填写真实网卡、链路本地网关、游戏后端、MOTD、域名及现有 AAAA 的 zone/record ID。更新 `compose.yml` 的描述标签；`group_add` 应匹配运行目录组 GID。
2. 创建 `secrets/`（权限 700），将有对应区域 DNS 编辑权限的 CF Token 写入 `secrets/cf_api_token`（640，所属组匹配 compose）。Token 只读挂载，不放入环境变量、镜像、参数或日志。
3. 创建可写 `state/`，在启动前将 Cloudflare 中当前 AAAA 记录的完整 API result 对象保存为 `state/dns-before.json`（600）。该文件用于回滚；必须核对记录 name/id/type，不含 Token。
4. 执行 `docker compose up -d --build`。通过 `docker compose ps`、`docker logs --tail 30 mc-ipv6-guardian` 与 `state/status.json` 检查状态。对公网入口发出 Minecraft status 请求，并核对预期 MOTD。

程序每 60 秒刷新。无有效前缀、后端状态不匹配或 DNS 更新失败时每 15 秒重试，180 秒未成功则 healthcheck 失败。Cloudflare 请求使用 IPv4，以避免旧 IPv6 失效时无法更新 DNS。DNS TTL 为 60，仅 DNS；日志最大 3×5 MB。

## 回滚

```sh
docker compose stop
docker compose run --rm --no-deps --entrypoint python3 guardian /app/rollback.py
docker compose down
```

仅删除本容器管理的地址与 nftables 表，并恢复 `state/dns-before.json`。若原 IPv6 前缀已失效，恢复旧记录不能恢复连接。转发不保留客户端源 IP，游戏内看到代理地址，原始连接地址记录在维护容器日志中。

## Clash / Mihomo 直连

使用规则模式并将以下规则置于代理规则之前（替换域名），启用 DNS IPv6，避免为 MC 域名分配 fake-ip：

```yaml
rules:
  - DOMAIN,mc.example.com,DIRECT
dns:
  ipv6: true
  fake-ip-filter:
    - mc.example.com
```

使用 Clash Verge 全局脚本/覆写保存规则，不能只修改运行时生成文件。其他流量仍需维持原 GLOBAL 策略时可在全部规则末尾使用 `MATCH,GLOBAL`。域名规则会跟随 AAAA，避免固定旧前缀。

## 验证

`python -m unittest discover -s . -p test_guardian.py` 验证 RA 来源、有效前缀、限定 nftables 表和 DNS 记录身份。运行配置、状态、Token 不随源码发布。

## 本机代理直连模式

使用 Clash/Mihomo 时保持规则模式（mode: rule），为 MC 域名设置 DIRECT；全局模式会忽略这些直连规则。需要保留其他流量的原全局出口时，以 MATCH,GLOBAL 作为末尾规则。动态 IPv6 前缀优先使用域名直连，不将过期地址永久写成规则。
