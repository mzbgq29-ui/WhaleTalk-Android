# 常见问题与排障

## AstrBot 初始密码找不到，或重置后无法登录

在「我的 → 本机管理账号」点击「填入初始密码」。密码默认隐藏，可在密码框内查看。若已修改过密码，请使用设置过的用户名和密码。忘记密码时，在同一页面选择「重置后台密码」，等 AstrBot 恢复运行后重新填入初始密码。开启过两步验证的账号仍需填写验证码。

### 仍在使用 1.7.1

1.7.1 的账号页没有初始密码和重置按钮。可先在「运行详情」停止 **AstrBot**。若曾在终端另行启动 AstrBot，也请退出那个实例。终端命令按当前目录寻找配置，鲸语实际使用的是 `/persistent/astrbot/data/cmd_config.json`，而运行中的 AstrBot 会继续使用它启动时加载的凭据。

打开「工具 → 终端」，新建终端并执行以下代码。它会提示输入并确认新密码，输入时屏幕不会回显。代码只更新 AstrBot 管理密码和会话签名，保留用户名、两步验证和机器人数据。

```bash
/opt/astrbot-venv/bin/python - <<'PY'
import getpass, json, os, runpy, secrets, tempfile, warnings
from pathlib import Path

path = Path('/persistent/astrbot/data/cmd_config.json')
config = json.loads(path.read_text(encoding='utf-8-sig'))
dashboard = config['dashboard']
auth = runpy.run_path('/opt/astrbot/astrbot/core/utils/auth_password.py')
with warnings.catch_warnings():
    warnings.simplefilter('error', getpass.GetPassWarning)
    password = getpass.getpass('新管理密码：')
    if password != getpass.getpass('再次输入：'):
        raise SystemExit('两次密码不一致，配置未修改。')
auth['validate_dashboard_password'](password)
dashboard.update(
    password=auth['hash_md5_dashboard_password'](password),
    pbkdf2_password=auth['hash_dashboard_password'](password),
    password_storage_upgraded=True,
    password_change_required=False,
    jwt_secret=secrets.token_hex(32),
)
with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as output:
    staged = Path(output.name)
    try:
        json.dump(config, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
        staged.replace(path)
    finally:
        staged.unlink(missing_ok=True)
print('密码已重置。当前用户名：' + dashboard.get('username', 'astrbot'))
PY
```

回到「运行详情」重新启动 AstrBot，再用终端显示的用户名和刚设置的密码登录。请保留日志中的密码遮蔽。

## QQ 登录了，但机器人不回复

1. 确认是用**另一个 QQ 账号**发消息；应用默认不处理机器人自己的消息。
2. 查看「连接 QQ」中 QQ 和消息通道的状态。扫码成功只表示登录过程完成，不代表消息通道已经连通。
3. 在「回复规则」核对主人、好友和群的范围，可先用试算检查唤醒条件。群聊测试时按当前预设 @ 机器人。
4. 在「云端模型」测试 API，并确认模型 ID、账户余额和服务权限。如果服务商不支持获取列表，可手动填模型 ID。
5. 发送 `#sl` 查看状态；在「运行日志」分别查看 SnowLuma、运行环境、桌面和 AstrBot 的错误。先停止并重新启动服务，再观察状态。

当前版本修复了一种因 PRoot 路径过长导致 QQ 画面已打开而本机通道无法被发现的问题，详见 [修复说明](消息通道.md)。这不能证明所有不回复都是同一原因；QQ 登录、消息通道、回复规则和模型服务任何一环失败都可能没有回复。


## 一直停在「正在准备 SnowLuma ARM64 运行层」

首页进度条如果只是来回扫、没有百分比，是旧版本的动画，不代表完成了一半。1.4.0 起会显示真实进度（校验、解压文件名和已写容量）。

- 等 1 到 3 分钟通常仍在校验或解压约 143 MB 的运行层，属于正常。
- 超过约 10 分钟仍无进度变化：查看「运行日志」，或在「照料」导出诊断包。常见原因是存储空间不足、写入过慢，或上次取消留下的半成品正在清理。
- 1.4.0 会在解压前检查剩余空间；取消后会保留已解压的文件，下次继续，不必从头再来。整段准备超过 15 分钟会超时并说明原因。
- 有 KernelSU 时也可检查 `/data/user/0/dev.whaletalk.android/files/manager/extensions/snowluma/`：存在 `runtime.installing` 且体积在增加，说明仍在解压。

## 环境准备失败

确认手机架构、Android 版本及可用空间，查看「运行日志」的最近失败记录和运行环境、桌面分类。首次准备资源较多，保持应用运行；如果是升级，先用「重新检查环境」或重试，不要先清除数据。

## 扫码画面空白或太小

等待 QQ 窗口启动，尝试刷新、放大画面或全屏登录；QQ 要求安全验证时在 QQ 窗口完成。若画面持续空白，查看桌面与 SnowLuma 日志。QQ 版本或系统环境变化也可能影响启动。

## 模型测试失败

核对服务地址、API Key、模型 ID、网络与账户余额。地址一般以服务商文档为准；确认服务商是否支持 OpenAI 兼容接口及模型列表接口。不要把完整 API Key、请求头或账号信息公开。

## 如何安全反馈

提交 Issue 时写明应用版本、Android 版本、手机架构、私聊还是群聊、服务状态和重现步骤。分享日志前移除 QQ 号、API Key、Cookie、密码、二维码和其他隐私数据。不要上传完整备份、账号数据库或登录目录。
