# 自建服务端

一个JDK 21进程即可提供三种游戏，不需要数据库。下面使用示例域名`games.example.com`，请替换为自己的域名；这些是通用部署步骤，不是公共服务器的私有配置。

## 构建与启动

在项目根目录运行：

```bash
./gradlew :server:installDist
```

将整个`server/build/install/server/`目录复制到目标服务器，比如`/opt/inlay-games/server/`。以普通用户运行，保留`bin/`和`lib/`结构。确保JAVA_HOME指向JDK 21或java命令为21：

```bash
GOMOKU_HOST=127.0.0.1 GOMOKU_PORT=8091 /opt/inlay-games/server/bin/server
```

默认即127.0.0.1:8091。直接运行适合试用；长期服务可交由systemd等进程管理器托管，设置相同环境变量和启动命令，以非root用户运行。

## TLS反向代理

客户端只允许本机回环使用明文ws，远程使用wss。为自己的域名配置有效HTTPS证书，在已有的HTTPS Nginx `server` 块中加入：

```nginx
location /gomoku/ {
    proxy_pass http://127.0.0.1:8091;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 75s;
}
```

`proxy_pass`不追加路径，保留原始`/gomoku/...`。服务端只信任回环代理传来的X-Real-IP；代理应覆盖该请求头。服务监听回环即可，公网仅开放HTTPS入口。证书申请和域名解析由部署环境自行完成。

## 路由与客户端地址

| 游戏 | WebSocket | 健康检查 |
| --- | --- | --- |
| 五子棋 | `/gomoku/ws` | `/gomoku/health` |
| 斗地主协议2 | `/gomoku/ddz/v2/ws` | `/gomoku/ddz/v2/health` |
| 德州 | `/gomoku/texas/ws` | `/gomoku/texas/health` |

本版本还将`/gomoku/ddz/ws`和`/gomoku/ddz/health`映射到同一套协议2处理器，**不代表兼容旧协议1斗地主客户端**。

在插件创建／加入对话框填基础地址`wss://games.example.com/gomoku/ws`；插件自动转换为游戏对应路由。同房玩家必须指向同一个服务实例。

本地健康检查：

```bash
curl --fail http://127.0.0.1:8091/gomoku/health
curl --fail http://127.0.0.1:8091/gomoku/ddz/v2/health
curl --fail http://127.0.0.1:8091/gomoku/texas/health
```

前者和德州返回protocol=1，斗地主返回protocol=2。返回健康结果只证明HTTP进程可访问；实际联机可运行README列出的WebSocket冒烟脚本。

## 容量与数据边界

房间存内存，重启丢失；无活动30分钟清理。单进程连接上限400、单IP12，单连接每秒最多20条消息、单帧4096字节。多人共用同一出口IP也共享单IP限额。

这是单实例好友房服务。不要直接在多个实例间随机负载均衡：房间和重连身份不跨进程共享。没有账号、持久化、自动扩容或生产SLA；公开入口应自行监控可用性和资源使用。不要把TLS私钥、服务器凭据或玩家恢复令牌提交到仓库。
